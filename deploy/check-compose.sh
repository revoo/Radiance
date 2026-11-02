#!/usr/bin/env bash
# Checks the deploy files against a copy of the server's layout (CI runs this): Radiance included from the
# hosting project, on its default network, with no ports, and a single-quoted bcrypt hash surviving
# Compose's $ expansion. Nothing here touches a server.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
HOSTING="$(mktemp -d)"
trap 'rm -rf "$HOSTING"' EXIT
mkdir -p "$HOSTING/caddy" "$HOSTING/radiance"
cp "$HERE/compose.yaml" "$HOSTING/radiance/compose.yaml"
HASH='$2y$12$abcdefghijklmnopqrstuuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0'
sed "s|^RADIANCE_PASSWORD_BCRYPT=.*|RADIANCE_PASSWORD_BCRYPT='$HASH'|; s|^RADIANCE_USERNAME=.*|RADIANCE_USERNAME=sam|" \
  "$HERE/env.template" >"$HOSTING/radiance/.env"

# the server's compose.yaml, with `radiance: build: ./radiance` replaced as docs/ops/hosting.md says
cat >"$HOSTING/compose.yaml" <<'YAML'
include:
  - radiance/compose.yaml

services:
  caddy:
    image: caddy:2
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./caddy:/etc/caddy:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - hello

  hello:
    image: nginx:alpine
    restart: unless-stopped

volumes:
  caddy_data:
  caddy_config:
YAML

config=$(cd "$HOSTING" && docker compose -p hosting config --format json)
python3 -c '
import json, sys
hash_, hosting = sys.argv[1], sys.argv[2]
c = json.load(sys.stdin)
r = c["services"]["radiance"]
assert not r.get("ports"), "radiance must publish no ports"
assert "build" not in r, "the server must never build"
assert r["image"] == "ghcr.io/revoo/radiance:main", r["image"]
assert list(r["networks"]) == ["default"], r["networks"]
assert list(c["services"]["caddy"]["networks"]) == ["default"], "caddy and radiance must share a network"
assert c["networks"]["default"]["name"] == "hosting_default", c["networks"]
env = r["environment"]
assert env["RADIANCE_PASSWORD_BCRYPT"] == hash_, "the bcrypt hash was mangled: " + str(env["RADIANCE_PASSWORD_BCRYPT"])
assert env["SPRING_PROFILES_ACTIVE"] == "prod"
assert "-Xmx" in env["JAVA_TOOL_OPTIONS"]
assert r["mem_limit"], "no memory cap"
v = r["volumes"][0]
assert v["source"].endswith("/radiance/data") and v["target"] == "/app/data", v
assert r["logging"]["options"]["max-size"]
print("compose layout OK")
' "$HASH" "$HOSTING" <<<"$config"

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
