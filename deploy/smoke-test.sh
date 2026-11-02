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

fail() {
  echo "SMOKE TEST FAILED: $*" >&2
  docker logs "$NAME" 2>&1 | tail -40 >&2 || true
  exit 1
}
cleanup() {
  docker rm -f "$NAME" "$NAME-s3" "$NAME-caddy" >/dev/null 2>&1 || true
  docker network rm "$NAME" >/dev/null 2>&1 || true
  sudo rm -rf "$WORK"
}
trap cleanup EXIT

run_args=(--user "$USER_ID:$USER_ID" -v "$DATA:/app/data")

echo "== prod refuses to start without real credentials"
if out=$(docker run --rm "${run_args[@]}" "$IMAGE" 2>&1); then fail "it started without credentials"; fi
grep -q "RADIANCE_USERNAME and RADIANCE_PASSWORD_BCRYPT must be set" <<<"$out" || fail "no clear message: $out"

echo "== one-shot genesis creates a world, its first batch included"
out=$(docker run --rm "${run_args[@]}" -e SPRING_PROFILES_ACTIVE=offline "$IMAGE" genesis --seed 7 --import 2>&1) || fail "genesis failed: $out"
grep -q "First batch: DONE" <<<"$out" || fail "no first batch: $out"
[ "$(grep -c 'Nightly batch is due' <<<"$out" || true)" = 0 ] || fail "the in-process scheduler ran during a one-shot command"

echo "== the app serves as prod, behind what Caddy would send"
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

echo "== the SPA, its routes and its assets"
curl -fsS "$BASE/" | grep -q 'id="root"' || fail "no page at /"
curl -fsS "$BASE/messages/abc" | grep -q 'id="root"' || fail "no page at a client route"
curl -fsS "$BASE/people/sarah.kim" | grep -q 'id="root"' || fail "no page at a dotted client route"
asset=$(curl -fsS "$BASE/" | grep -oE '/assets/[^"]+\.js' | head -1)
[ -n "$asset" ] || fail "the page names no script"
curl -fsS -o /dev/null "$BASE$asset" || fail "the script isn't served"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/me")" = 401 ] || fail "the API isn't behind the login"

echo "== log in, onboard, and the world is ready"
headers=$(curl -s -D - -o /dev/null -H 'X-Forwarded-Proto: https' "$BASE/api/me")
xsrf=$(grep -i '^set-cookie: XSRF-TOKEN=' <<<"$headers" | sed -E 's/^[^=]+=([^;]+).*/\1/' | tr -d '\r')
[ -n "$xsrf" ] || fail "no CSRF cookie"
grep -i '^set-cookie: XSRF-TOKEN=' <<<"$headers" | grep -qi 'secure' || fail "the CSRF cookie isn't Secure behind HTTPS"
headers=$(curl -s -D - -o /dev/null -H 'X-Forwarded-Proto: https' -H "Cookie: XSRF-TOKEN=$xsrf" -H "X-XSRF-TOKEN: $xsrf" \
  --data-urlencode username=sam --data-urlencode "password=$PASSWORD" "$BASE/api/auth/login")
session=$(grep -i '^set-cookie: JSESSIONID=' <<<"$headers" | sed -E 's/^[^=]+=([^;]+).*/\1/' | tr -d '\r')
[ -n "$session" ] || fail "login failed: $headers"
grep -i '^set-cookie: JSESSIONID=' <<<"$headers" | grep -qi 'secure' || fail "the session cookie isn't Secure"
new_xsrf=$(grep -i '^set-cookie: XSRF-TOKEN=' <<<"$headers" | tail -1 | sed -E 's/^[^=]+=([^;]+).*/\1/' | tr -d '\r')
[ -n "$new_xsrf" ] && xsrf="$new_xsrf"
api() { curl -s -H "Cookie: JSESSIONID=$session; XSRF-TOKEN=$xsrf" -H "X-XSRF-TOKEN: $xsrf" -H 'X-Forwarded-Proto: https' "$@"; }
code=$(api -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/json' -d \
  '{"name":"Smoke Test","bio":"","employer":"","livesIn":"Chicago","circles":[],"interests":[],"backstory":"LOCAL"}' "$BASE/api/profile")
[ "$code" = 200 ] || fail "onboarding answered $code"
api "$BASE/api/feed" | grep -q '"body"' || fail "the feed is empty"
code=$(api -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{"text":"hello from the smoke test"}' "$BASE/api/posts")
[ "$code" = 201 ] || fail "posting answered $code"

echo "== with no R2 keys, backups are loud"
docker logs "$NAME" 2>&1 | grep -q "BACKUPS ARE NOT LEAVING THIS SERVER" || fail "no banner in the app's log"
api "$BASE/api/director/backups" | grep -q "leaving this server" || fail "the SPA isn't told"
set +e
out=$(docker run --rm "${run_args[@]}" "$IMAGE" backup 2>&1)
code=$?
set -e
[ "$code" = 3 ] || fail "backup without keys exited $code, not 3: $out"
grep -q "BACKUPS ARE NOT LEAVING THIS SERVER" <<<"$out" || fail "no banner from the one-shot backup"
ls "$DATA/backups/" | grep -q 'radiance-.*\.db\.gz' || fail "no local snapshot"

echo "== a backup while the app runs reaches an S3 store that checks signatures (rclone standing in for R2)"
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

echo "== through Caddy, using the real site's proxy rule: pages, and the live stream isn't buffered"
CADDY_DIR="$WORK/caddy"
mkdir -p "$CADDY_DIR"
# the site as deployed, on plain HTTP: no certificate here
sed -E '1s/^.*\{$/:80 {/' "$(dirname "$0")/Caddyfile.radiance" >"$CADDY_DIR/Caddyfile"
docker run -d --name "$NAME-caddy" --network "$NAME" -p 127.0.0.1:18081:80 -v "$CADDY_DIR:/etc/caddy:ro" caddy:2 >/dev/null
sleep 2
curl -fsS http://127.0.0.1:18081/ | grep -q 'id="root"' || fail "no page through Caddy"
stream=$(timeout 5 curl -sN -H "Cookie: JSESSIONID=$session" http://127.0.0.1:18081/api/stream || true)
grep -q "event:hello" <<<"$stream" || fail "the stream didn't get through Caddy promptly: $stream"

echo "== restore refuses while the app runs"
set +e
out=$(docker run --rm --network "$NAME" "${run_args[@]}" "${r2_env[@]}" "$IMAGE" restore latest 2>&1)
code=$?
set -e
[ "$code" = 3 ] || fail "restore with the app running exited $code, not 3: $out"

echo "== it stops cleanly"
docker stop -t 30 "$NAME" >/dev/null
code=$(docker inspect --format '{{.State.ExitCode}}' "$NAME")
[ "$code" = 0 ] || [ "$code" = 143 ] || fail "it exited with $code"
docker rm "$NAME" >/dev/null

echo "== the drill: the disk is lost, the database comes back from the store, and the app serves it"
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
new_xsrf=$(grep -i '^set-cookie: XSRF-TOKEN=' <<<"$headers" | tail -1 | sed -E 's/^[^=]+=([^;]+).*/\1/' | tr -d '\r')
[ -n "$new_xsrf" ] && xsrf="$new_xsrf"
api "$BASE/api/feed" | grep -q "hello from the smoke test" || fail "the restored world lost R's post"
code=$(api -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{"text":"back from the dead"}' "$BASE/api/posts")
[ "$code" = 201 ] || fail "the restored app can't write: posting answered $code"
docker stop -t 30 "$NAME" >/dev/null

echo "SMOKE TEST PASSED"
