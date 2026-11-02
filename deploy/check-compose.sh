#!/usr/bin/env bash
# Checks the deploy files against a copy of the server's layout (CI runs this): Radiance as its own project on
# the hosting network, with no ports, and a single-quoted bcrypt hash surviving Compose's $ expansion.
# Nothing here touches a server.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
HOSTING="$(mktemp -d)"
trap 'rm -rf "$HOSTING"' EXIT
mkdir -p "$HOSTING/caddy" "$HOSTING/radiance"
cp "$HERE/compose.yaml" "$HOSTING/radiance/compose.yaml"
HASH='$2y$12$abcdefghijklmnopqrstuuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0'
sed "s|^RADIANCE_PASSWORD_BCRYPT=.*|RADIANCE_PASSWORD_BCRYPT='$HASH'|; s|^RADIANCE_USERNAME=.*|RADIANCE_USERNAME=sam|" \
  "$HERE/env.template" >"$HOSTING/radiance/.env"

config=$(cd "$HOSTING/radiance" && docker compose config --format json)
python3 -c '
import json, sys
hash_ = sys.argv[1]
c = json.load(sys.stdin)
assert c["name"] == "radiance", "its own project: " + c["name"]
r = c["services"]["radiance"]
assert not r.get("ports"), "radiance must publish no ports"
assert "build" not in r, "the server must never build"
assert r["image"] == "ghcr.io/revoo/radiance:main", r["image"]
assert list(r["networks"]) == ["default"], r["networks"]
net = c["networks"]["default"]
assert net["name"] == "hosting_default" and net["external"], "must join the shared network: " + str(net)
env = r["environment"]
# config escapes $ as $$ in what it prints, so its output parses back to the same value
assert env["RADIANCE_PASSWORD_BCRYPT"].replace("$$", "$") == hash_, "the bcrypt hash was mangled: " + str(env["RADIANCE_PASSWORD_BCRYPT"])
assert env["SPRING_PROFILES_ACTIVE"] == "prod"
assert "-Xmx" in env["JAVA_TOOL_OPTIONS"]
assert r["mem_limit"], "no memory cap"
v = r["volumes"][0]
assert v["source"].replace("\\", "/").endswith("/radiance/data") and v["target"] == "/app/data", v
assert r["logging"]["options"]["max-size"]
print("compose layout OK")
' "$HASH" <<<"$config"

# the Caddy site parses, added to what's already there
cat >"$HOSTING/caddy/Caddyfile" <<'CADDY'
test.enigmatica.app {
	reverse_proxy hello:80
}
CADDY
{
  echo
  cat "$HERE/Caddyfile.radiance"
} >>"$HOSTING/caddy/Caddyfile"
docker run --rm -v "$HOSTING/caddy:/etc/caddy:ro" caddy:2 caddy validate --adapter caddyfile --config /etc/caddy/Caddyfile
echo "Caddy site OK"
