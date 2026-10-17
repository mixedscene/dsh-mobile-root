#!/bin/sh
set -eu
port="${1:-3080}"
case "$port" in ''|*[!0-9]*) echo 'invalid mobile port' >&2; exit 64;; esac
[ "$port" -ge 1 ] && [ "$port" -le 65535 ] || exit 64
export HOME="${HOME:-/home/dsh}"
export DSH_HOME="${DSH_HOME:-$HOME/.dsh}"
export PATH=/usr/local/bin:/usr/bin:/bin
export DSH_TELEMETRY_DISABLED=1
export DSH_TOOLS_MODE=native
umask 077
mkdir -p "$HOME" "$DSH_HOME/profiles" "$DSH_HOME/query" /tmp
profile="$DSH_HOME/profiles/mobile"
# Native owns a single runtime instance. Never replace an existing user profile.
if [ ! -e "$profile" ]; then
  staging=$(mktemp -d "$DSH_HOME/profiles/.mobile.XXXXXX")
  cp -R /opt/dsh-mobile/profile-seed/. "$staging/"
  mv "$staging" "$profile"
fi
cd "$HOME"
exec /usr/local/bin/dsh --profile mobile --host 127.0.0.1 --port "$port" --no-open
