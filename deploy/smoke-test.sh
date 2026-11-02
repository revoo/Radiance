#!/usr/bin/env bash
# Runs the image the way the server will (CI runs this on every milestone branch): as a non-root user with
# a bind-mounted data directory, real credentials and the prod profile. Usage: deploy/smoke-test.sh IMAGE
set -euo pipefail

IMAGE="${1:-radiance:smoke}"
NAME="radiance-smoke-$$"
PORT=18080
USER_ID=1001
PASSWORD="smoke-test-password"
BASE="http://127.0.0.1:$PORT"
WORK="$(mktemp -d)"
DATA="$WORK/data"
mkdir -p "$DATA"
sudo chown "$USER_ID:$USER_ID" "$DATA"

step() {
  STEP="$*"
  echo "== $*"
}
fail() {
  local detail logs
  detail="$*"
  logs=$(docker logs "$NAME" 2>&1 | tail -25 || true)
  echo "SMOKE TEST FAILED at: $STEP: $detail" >&2
  echo "$logs" >&2
  # an annotation, so the reason is readable from the run's summary too
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    local msg="$detail"$'\n'"--- app log ---"$'\n'"$logs"
    msg=${msg//'%'/'%25'}
    msg=${msg//$'\r'/'%0D'}
    msg=${msg//$'\n'/'%0A'}
    echo "::error title=Smoke test failed at: $STEP::${msg: -6000}"
  fi
  exit 1
}
cleanup() {
  docker rm -f "$NAME" "$NAME-s3" "$NAME-caddy" >/dev/null 2>&1 || true
  docker network rm "$NAME" >/dev/null 2>&1 || true
  sudo rm -rf "$WORK"
}
trap cleanup EXIT
STEP="setup"

run_args=(--user "$USER_ID:$USER_ID" -v "$DATA:/app/data")

step "prod refuses to start without real credentials"
if out=$(docker run --rm "${run_args[@]}" "$IMAGE" 2>&1); then fail "it started without credentials"; fi
grep -q "RADIANCE_USERNAME and RADIANCE_PASSWORD_BCRYPT must be set" <<<"$out" || fail "no clear message: $out"

step "one-shot genesis creates a world, its first batch included"
out=$(docker run --rm "${run_args[@]}" -e SPRING_PROFILES_ACTIVE=offline "$IMAGE" genesis --seed 7 --import 2>&1) || fail "genesis failed: $out"
grep -q "First batch: DONE" <<<"$out" || fail "no first batch: $out"
[ "$(grep -c 'Nightly batch is due' <<<"$out" || true)" = 0 ] || fail "the in-process scheduler ran during a one-shot command"

step "the app serves as prod, behind what Caddy would send"
HASH=$(docker run --rm httpd:2-alpine htpasswd -bnBC 10 "" "$PASSWORD" | tr -d ':\n')
docker run -d --name "$NAME" "${run_args[@]}" -p "127.0.0.1:$PORT:8080" -e RADIANCE_USERNAME=sam \
  -e "RADIANCE_PASSWORD_BCRYPT=$HASH" -e JAVA_TOOL_OPTIONS=-Xmx768m "$IMAGE" >/dev/null

for _ in $(seq 1 45); do
  status=$(docker inspect --format '{{.State.Health.Status}}' "$NAME" 2>/dev/null || echo gone)
  [ "$status" = healthy ] && break
  [ "$status" = gone ] && fail "the container died"
  sleep 3
done
[ "$status" = healthy ] || fail "the container's health check never passed ($status)"

step "the SPA, its routes and its assets"
for page in / /messages/abc /people/sarah.kim; do
  html=$(curl -fsS "$BASE$page") || fail "no answer at $page"
  grep -q 'id="root"' <<<"$html" || fail "no page at $page"
done
html=$(curl -fsS "$BASE/")
asset=$(grep -oE '/assets/[^"]+\.js' <<<"$html" | sed -n 1p)
[ -n "$asset" ] || fail "the page names no script"
curl -fsS -o /dev/null "$BASE$asset" || fail "the script isn't served"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/me")" = 401 ] || fail "the API isn't behind the login"

step "log in, onboard, and the world is ready"
headers=$(curl -s -D - -o /dev/null -H 'X-Forwarded-Proto: https' "$BASE/api/me")
xsrf=$(grep -i '^set-cookie: XSRF-TOKEN=' <<<"$headers" | sed -E 's/^[^=]+=([^;]+).*/\1/' | tr -d '\r')
[ -n "$xsrf" ] || fail "no CSRF cookie"
grep -i '^set-cookie: XSRF-TOKEN=' <<<"$headers" | grep -qi 'secure' || fail "the CSRF cookie isn't Secure behind HTTPS"
headers=$(curl -s -D - -o /dev/null -H 'X-Forwarded-Proto: https' -H "Cookie: XSRF-TOKEN=$xsrf" -H "X-XSRF-TOKEN: $xsrf" \
  --data-urlencode username=sam --data-urlencode "password=$PASSWORD" "$BASE/api/auth/login")
session=$(grep -i '^set-cookie: JSESSIONID=' <<<"$headers" | sed -E 's/^[^=]+=([^;]+).*/\1/' | tr -d '\r')
[ -n "$session" ] || fail "login failed: $headers"
grep -i '^set-cookie: JSESSIONID=' <<<"$headers" | grep -qi 'secure' || fail "the session cookie isn't Secure"
new_xsrf=$(grep -i '^set-cookie: XSRF-TOKEN=' <<<"$headers" | tail -1 | sed -E 's/^[^=]+=([^;]+).*/\1/' | tr -d '\r' || true)
if [ -n "$new_xsrf" ]; then xsrf="$new_xsrf"; fi
api() { curl -s -H "Cookie: JSESSIONID=$session; XSRF-TOKEN=$xsrf" -H "X-XSRF-TOKEN: $xsrf" -H 'X-Forwarded-Proto: https' "$@"; }
code=$(api -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/json' -d \
  '{"name":"Smoke Test","bio":"","employer":"","livesIn":"Chicago","circles":[],"interests":[],"backstory":"LOCAL"}' "$BASE/api/profile")
[ "$code" = 200 ] || fail "onboarding answered $code"
feed=$(api "$BASE/api/feed")
grep -q '"body"' <<<"$feed" || fail "the feed is empty: ${feed:0:400}"
code=$(api -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{"text":"hello from the smoke test"}' "$BASE/api/posts")
[ "$code" = 201 ] || fail "posting answered $code"

step "with no R2 keys, backups are loud"
# (into variables: under pipefail, grep -q exiting early would fail the pipeline on SIGPIPE)
logs=$(docker logs "$NAME" 2>&1)
grep -q "BACKUPS ARE NOT LEAVING THIS SERVER" <<<"$logs" || fail "no banner in the app's log"
status_json=$(api "$BASE/api/director/backups")
grep -q "leaving this server" <<<"$status_json" || fail "the SPA isn't told: $status_json"
set +e
out=$(docker run --rm "${run_args[@]}" "$IMAGE" backup 2>&1)
code=$?
set -e
[ "$code" = 3 ] || fail "backup without keys exited $code, not 3: $out"
grep -q "BACKUPS ARE NOT LEAVING THIS SERVER" <<<"$out" || fail "no banner from the one-shot backup"
snapshots=$(ls "$DATA/backups/")
grep -q 'radiance-.*\.db\.gz' <<<"$snapshots" || fail "no local snapshot: $snapshots"

step "a backup while the app runs reaches an S3 store that checks signatures (rclone standing in for R2)"
docker network create "$NAME" >/dev/null
docker network connect --alias radiance "$NAME" "$NAME"
S3_DATA="$WORK/s3"
mkdir -p "$S3_DATA/radiance-backups"
docker run -d --name "$NAME-s3" --network "$NAME" -v "$S3_DATA:/data" rclone/rclone:1.71 serve s3 --addr :9000 \
  --auth-key smoke-access-key,smoke-secret-key --force-path-style=true /data >/dev/null
sleep 3
r2_env=(-e "R2_ENDPOINT=http://$NAME-s3:9000" -e R2_ACCESS_KEY_ID=smoke-access-key -e R2_SECRET_ACCESS_KEY=smoke-secret-key
  -e R2_BACKUP_BUCKET=radiance-backups)
out=$(docker run --rm --network "$NAME" "${run_args[@]}" "${r2_env[@]}" "$IMAGE" backup 2>&1) || fail "backup with keys failed: $out"
grep -q "uploaded to backups/radiance-" <<<"$out" || fail "nothing uploaded: $out"

step "through Caddy, using the real site's proxy rule: pages, and the live stream isn't buffered"
CADDY_DIR="$WORK/caddy"
mkdir -p "$CADDY_DIR"
# the site as deployed, on plain HTTP: no certificate here
sed -E '1s/^.*\{$/:80 {/' "$(dirname "$0")/Caddyfile.radiance" >"$CADDY_DIR/Caddyfile"
docker run -d --name "$NAME-caddy" --network "$NAME" -p 127.0.0.1:18081:80 -v "$CADDY_DIR:/etc/caddy:ro" caddy:2 >/dev/null
sleep 2
page=$(curl -fsS http://127.0.0.1:18081/) || fail "no answer through Caddy"
grep -q 'id="root"' <<<"$page" || fail "no page through Caddy"
stream=$(timeout 5 curl -sN -H "Cookie: JSESSIONID=$session" http://127.0.0.1:18081/api/stream || true)
grep -q "event:hello" <<<"$stream" || fail "the stream didn't get through Caddy promptly: $stream"

step "restore refuses while the app runs"
set +e
out=$(docker run --rm --network "$NAME" "${run_args[@]}" "${r2_env[@]}" "$IMAGE" restore latest 2>&1)
code=$?
set -e
[ "$code" = 3 ] || fail "restore with the app running exited $code, not 3: $out"

step "it stops cleanly"
docker stop -t 30 "$NAME" >/dev/null
code=$(docker inspect --format '{{.State.ExitCode}}' "$NAME")
[ "$code" = 0 ] || [ "$code" = 143 ] || fail "it exited with $code"
docker rm "$NAME" >/dev/null

step "the drill: the disk is lost, the database comes back from the store, and the app serves it"
sudo rm -rf "$DATA"
mkdir -p "$DATA"
sudo chown "$USER_ID:$USER_ID" "$DATA"
out=$(docker run --rm --network "$NAME" "${run_args[@]}" "${r2_env[@]}" "$IMAGE" restore latest 2>&1) || fail "restore failed: $out"
grep -q "Restored radiance-" <<<"$out" || fail "restore said: $out"
docker run -d --name "$NAME" "${run_args[@]}" -p "127.0.0.1:$PORT:8080" -e RADIANCE_USERNAME=sam \
  -e "RADIANCE_PASSWORD_BCRYPT=$HASH" -e JAVA_TOOL_OPTIONS=-Xmx768m "$IMAGE" >/dev/null
for _ in $(seq 1 45); do
  status=$(docker inspect --format '{{.State.Health.Status}}' "$NAME" 2>/dev/null || echo gone)
  [ "$status" = healthy ] && break
  sleep 3
done
[ "$status" = healthy ] || fail "the restored app never got healthy ($status)"
headers=$(curl -s -D - -o /dev/null -H 'X-Forwarded-Proto: https' "$BASE/api/me")
xsrf=$(grep -i '^set-cookie: XSRF-TOKEN=' <<<"$headers" | sed -E 's/^[^=]+=([^;]+).*/\1/' | tr -d '\r')
headers=$(curl -s -D - -o /dev/null -H 'X-Forwarded-Proto: https' -H "Cookie: XSRF-TOKEN=$xsrf" -H "X-XSRF-TOKEN: $xsrf" \
  --data-urlencode username=sam --data-urlencode "password=$PASSWORD" "$BASE/api/auth/login")
session=$(grep -i '^set-cookie: JSESSIONID=' <<<"$headers" | sed -E 's/^[^=]+=([^;]+).*/\1/' | tr -d '\r')
new_xsrf=$(grep -i '^set-cookie: XSRF-TOKEN=' <<<"$headers" | tail -1 | sed -E 's/^[^=]+=([^;]+).*/\1/' | tr -d '\r' || true)
if [ -n "$new_xsrf" ]; then xsrf="$new_xsrf"; fi
feed=$(api "$BASE/api/feed")
grep -q "hello from the smoke test" <<<"$feed" || fail "the restored world lost R's post: ${feed:0:400}"
code=$(api -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{"text":"back from the dead"}' "$BASE/api/posts")
[ "$code" = 201 ] || fail "the restored app can't write: posting answered $code"
docker stop -t 30 "$NAME" >/dev/null

echo "SMOKE TEST PASSED"
