#!/usr/bin/env bash
# Sets Radiance's login password: asks for it twice (nothing is echoed or stored in shell history), writes its
# bcrypt hash to .env beside this script, and restarts the app with it. Run it on the server:
#   sudo -u claude /opt/hosting/radiance/set-password.sh
set -euo pipefail
cd "$(dirname "$0")"

[ -f .env ] || { echo "No .env here. See docs/ops/hosting.md." >&2; exit 1; }
read -rsp "New password: " password
echo >&2
read -rsp "Same again: " again
echo >&2
[ "$password" = "$again" ] || { echo "Those didn't match; nothing was changed." >&2; exit 1; }
[ "${#password}" -ge 10 ] || { echo "Use at least 10 characters; nothing was changed." >&2; exit 1; }

hash=$(printf '%s' "$password" | docker run -i --rm httpd:2-alpine htpasswd -inBC 12 "" | tr -d ':\n')
case "$hash" in
  '$2'*) ;;
  *) echo "Couldn't make a hash; nothing was changed." >&2; exit 1 ;;
esac
docker image rm httpd:2-alpine >/dev/null 2>&1 || true

# single quotes: Compose expands $ in .env, and an unquoted hash silently becomes a different value
HASH="$hash" python3 - <<'PY'
import os
import re

with open(".env") as f:
    text = f.read()
line = "RADIANCE_PASSWORD_BCRYPT='" + os.environ["HASH"] + "'"
text, found = re.subn(r"(?m)^RADIANCE_PASSWORD_BCRYPT=.*$", lambda m: line, text)
if not found:
    text = text.rstrip("\n") + "\n" + line + "\n"
with open(".env", "w") as f:  # in place: keeps the file's owner and its 600 mode
    f.write(text)
PY

if [ -z "${SET_PASSWORD_NO_RESTART:-}" ]; then
  docker compose up -d
  echo "Done. Log in with the new password; anyone logged in has to log in again."
fi
