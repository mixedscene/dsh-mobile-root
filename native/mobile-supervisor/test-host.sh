#!/usr/bin/env bash
set -euo pipefail
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
cc -D_GNU_SOURCE -Wall -Wextra -Werror -fPIE -pie "$(dirname "$0")/supervisor.c" -o "$out/supervisor"
set +e
"$out/supervisor" probe >"$out/nonroot.log" 2>&1
nonroot=$?
sudo "$out/supervisor" invalid >"$out/invalid.log" 2>&1
invalid=$?
sudo "$out/supervisor" run >"$out/run.log" 2>&1
missing=$?
set -e
test "$nonroot" -eq 77
test "$invalid" -eq 2
test "$missing" -eq 2
echo 'Host CLI refusal checks passed (not a chroot/device test).'
