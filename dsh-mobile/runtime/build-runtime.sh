#!/usr/bin/env bash
# Cloud-only builder: run as root on a disposable Ubuntu arm64 runner.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
[[ $(id -u) == 0 && $(uname -m) == aarch64 ]] || { echo 'requires disposable native Linux arm64 runner as root' >&2; exit 1; }
source_dir=$(cd "$(dirname "$0")/../.." && pwd)
out=$(realpath -m "${1:-$source_dir/dsh-mobile/runtime/out}")
mkdir -p "$out"
root=$(mktemp -d "${RUNNER_TEMP:-/tmp}/dsh-mobile-rootfs.XXXXXX")
mounted=()
cleanup() {
  for ((i=${#mounted[@]}-1; i>=0; i--)); do umount -R "${mounted[$i]}" || true; done
  # Keep failed rootfs for runner-local diagnosis; hosted runner disposal removes it.
}
trap cleanup EXIT
apt-get update
apt-get install -y --no-install-recommends debootstrap ca-certificates curl python3
debootstrap --arch=arm64 --variant=minbase noble "$root" https://ports.ubuntu.com/ubuntu-ports
cp /etc/resolv.conf "$root/etc/resolv.conf"
printf '#!/bin/sh\nexit 101\n' > "$root/usr/sbin/policy-rc.d"
chmod 755 "$root/usr/sbin/policy-rc.d"
printf 'deb http://ports.ubuntu.com/ubuntu-ports noble main universe\ndeb http://ports.ubuntu.com/ubuntu-ports noble-updates main universe\ndeb http://ports.ubuntu.com/ubuntu-ports noble-security main universe\n' > "$root/etc/apt/sources.list"
for target in dev proc sys; do
  mount --rbind "/$target" "$root/$target"
  mount --make-rslave "$root/$target"
  mounted+=("$root/$target")
done
mkdir -p "$root/opt/dsh-mobile/build-input/packages/bundle/web-app/presets" "$root/opt/dsh-mobile/build-input/packages/bundle/base"
cp "$source_dir/packages/bundle/base/cordis.patch.yml" "$root/opt/dsh-mobile/build-input/packages/bundle/base/"
cp "$source_dir/packages/bundle/web-app/cordis.patch.yml" "$root/opt/dsh-mobile/build-input/packages/bundle/web-app/"
cp "$source_dir/packages/bundle/web-app/presets/standard.patch.yml" "$root/opt/dsh-mobile/build-input/packages/bundle/web-app/presets/"
cp "$source_dir/dsh-mobile/runtime/"{generate-mobile.mjs,activity.mjs,start-mobile.sh,smoke.mjs} "$root/opt/dsh-mobile/"
cp "$source_dir/dsh-mobile/runtime/"{runtime-docs.py,pack-runtime.py} "$root/opt/dsh-mobile/"
chroot "$root" /bin/bash -euxo pipefail <<'CHROOT'
apt-get update
apt-get install -y --no-install-recommends bash coreutils findutils grep sed gawk tar gzip xz-utils curl ca-certificates ripgrep git less libstdc++6 libatomic1 libcurl4t64 libexpat1 zlib1g
# All compilers and headers are confined to this build and purged before packing.
apt-get install -y --no-install-recommends build-essential python3 pkg-config libssl-dev libcurl4-openssl-dev libexpat1-dev zlib1g-dev gettext
mkdir -p /opt/dsh-mobile/licenses /opt/dsh-mobile/install /usr/local
cd /tmp
curl --fail --location --retry 3 -o node.tar.xz https://nodejs.org/dist/v24.11.0/node-v24.11.0-linux-arm64.tar.xz
curl --fail --location --retry 3 -o SHASUMS256.txt https://nodejs.org/dist/v24.11.0/SHASUMS256.txt
expected=$(awk '$2 == "node-v24.11.0-linux-arm64.tar.xz" {print $1}' SHASUMS256.txt)
[[ "$expected" =~ ^[a-f0-9]{64}$ ]]
printf '%s  node.tar.xz\n' "$expected" | sha256sum -c -
tar -xJf node.tar.xz -C /usr/local --strip-components=1
cp SHASUMS256.txt /opt/dsh-mobile/licenses/node-SHASUMS256.txt
cp /usr/local/LICENSE /opt/dsh-mobile/licenses/Node-LICENSE
curl --fail --location --retry 3 -o git.tar.xz https://www.kernel.org/pub/software/scm/git/git-2.45.4.tar.xz
printf '93f82ed1cab772acf3167db3be2bd2e54876e0ab53c6a9af744940752b961d88  git.tar.xz\n' | sha256sum -c -
tar -xJf git.tar.xz
cd git-2.45.4
make -j2 prefix=/usr/local NO_TCLTK=YesPlease NO_GETTEXT=YesPlease all
strip --strip-unneeded git
make prefix=/usr/local NO_TCLTK=YesPlease NO_GETTEXT=YesPlease install
cp COPYING /opt/dsh-mobile/licenses/Git-COPYING
cp /tmp/git.tar.xz /opt/dsh-mobile/licenses/git-2.45.4-source.tar.xz
cd /opt/dsh-mobile/install
printf '{"name":"dsh-mobile-installation","private":true,"type":"module"}\n' > package.json
npm install --omit=dev --no-audit --no-fund --save-exact @deepseek-ai/dsh@0.2.1-alpha.2 pnpm@11.28.5 js-yaml@4.3.2
node /opt/dsh-mobile/generate-mobile.mjs /opt/dsh-mobile/build-input /opt/dsh-mobile/mobile-bundle /opt/dsh-mobile/install
# A directory dependency becomes an external npm link without installing its graph.
# Install a packed artifact so npm owns and validates the entire mobile dependency graph.
mobile_bundle_tar=$(npm pack --silent /opt/dsh-mobile/mobile-bundle --pack-destination /opt/dsh-mobile)
npm install --omit=dev --no-audit --no-fund --save-exact "/opt/dsh-mobile/$mobile_bundle_tar"
node -e 'const p=require.resolve("@dsh-mobile/runtime-bundle/package.json"); const m=require(p); if(m.name!=="@dsh-mobile/runtime-bundle" || !m.dsh?.bundle?.patch)throw Error("Invalid installed mobile bundle"); console.log("Installed mobile bundle:",p)'
npm list --all --json > /opt/dsh-mobile/licenses/npm-dependency-versions.json
cp package-lock.json /opt/dsh-mobile/licenses/npm-package-lock.json
# Flattened executable symlinks change Node's import.meta.url/require base.
# Regular shell wrappers preserve each application's canonical JS entry path.
write_launcher() {
  destination=$1
  entry=$2
  test -f "$entry"
  rm -f "$destination"
  printf '#!/bin/sh\nexec /usr/local/bin/node %s "$@"\n' "$entry" > "$destination"
  chmod 755 "$destination"
}
write_launcher /usr/local/bin/dsh /opt/dsh-mobile/install/node_modules/@deepseek-ai/dsh/lib/bin.js
write_launcher /usr/local/bin/pnpm /opt/dsh-mobile/install/node_modules/pnpm/bin/pnpm.mjs
write_launcher /usr/local/bin/npm /usr/local/lib/node_modules/npm/bin/npm-cli.js
write_launcher /usr/local/bin/npx /usr/local/lib/node_modules/npm/bin/npx-cli.js
mkdir -p /opt/dsh-mobile/profile-seed /home/dsh /tmp /proc /dev
printf '{"name":"dsh-profile-mobile","private":true,"dependencies":{},"dsh":{"profile":{"bundles":["@dsh-mobile/runtime-bundle"]}}}\n' > /opt/dsh-mobile/profile-seed/package.json
printf '[]\n' > /opt/dsh-mobile/profile-seed/cordis.patch.yml
printf 'autoInstallPeers: false\n' > /opt/dsh-mobile/profile-seed/pnpm-workspace.yaml
chmod 755 /opt/dsh-mobile/start-mobile.sh
HOME=/home/dsh DSH_HOME=/home/dsh/.dsh node /opt/dsh-mobile/smoke.mjs
# Package metadata and notices remain; no compiler is required on the phone.
PYTHONDONTWRITEBYTECODE=1 python3 /opt/dsh-mobile/runtime-docs.py preserve /
apt-get purge -y build-essential gcc g++ cpp make dpkg-dev python3 pkg-config libssl-dev libcurl4-openssl-dev libexpat1-dev zlib1g-dev gettext
apt-get autoremove -y --purge
apt-get clean
HOME=/home/dsh DSH_HOME=/home/dsh/.dsh node /opt/dsh-mobile/smoke.mjs
dpkg-query -W -f='${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\n' > /opt/dsh-mobile/licenses/ubuntu-packages.tsv
printf 'Ubuntu packages: https://ports.ubuntu.com/ubuntu-ports/pool/\nCopyright texts: /usr/share/doc/*/copyright\nNode: https://nodejs.org/dist/v24.11.0/\nGit corresponding source: git-2.45.4-source.tar.xz\nHarness and npm transitive licenses: package directories under /opt/dsh-mobile/install/node_modules\n' > /opt/dsh-mobile/licenses/SOURCES.txt
node -p 'JSON.stringify({node:process.version,pnpm:"11.28.5",harness:"0.2.1-alpha.2",git:require("child_process").execFileSync("git",["--version"],{encoding:"utf8"}).trim()})' > /opt/dsh-mobile/tool-versions.json
mkdir -p /opt/dsh-mobile/corresponding-source
cp /opt/dsh-mobile/licenses/git-2.45.4-source.tar.xz /opt/dsh-mobile/corresponding-source/
sed 's/^deb /deb-src /' /etc/apt/sources.list >> /etc/apt/sources.list.d/mobile-sources.list
apt-get update
cd /opt/dsh-mobile/corresponding-source
dpkg-query -W -f='${source:Package}=${source:Version}\n' | sort -u > installed-source-versions.txt
: > incomplete-source-downloads.txt
while IFS= read -r package; do
  apt-get source --print-uris --download-only --only-source "$package" >> SOURCE-URIS.txt || true
  apt-get source --download-only --only-source "$package" || printf '%s\n' "$package" >> incomplete-source-downloads.txt
done < installed-source-versions.txt
find . -maxdepth 1 -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
cp incomplete-source-downloads.txt /opt/dsh-mobile/licenses/incomplete-source-downloads.txt
rm -rf /opt/dsh-mobile/build-input /opt/dsh-mobile/generate-mobile.mjs /opt/dsh-mobile/runtime-docs.py /opt/dsh-mobile/pack-runtime.py /root/.npm /tmp/* /var/lib/apt/lists/* /var/cache/apt/* /home/dsh/.dsh /home/dsh/.npm /home/dsh/.cache
CHROOT
PYTHONDONTWRITEBYTECODE=1 python3 "$source_dir/dsh-mobile/runtime/runtime-docs.py" cleanup "$root"
cp "$root/opt/dsh-mobile/licenses/dangling-optional-docs.json" "$out/runtime-doc-cleanup.json"
cp "$root/opt/dsh-mobile/licenses/dangling-optional-docs.json" "$out/runtime-doc-cleanup.txt"
PYTHONDONTWRITEBYTECODE=1 python3 "$source_dir/dsh-mobile/runtime/runtime-docs.py" trim "$root"
cp "$root/opt/dsh-mobile/licenses/optional-resource-trim.json" "$out/runtime-resource-trim.txt"
test -d "$root/usr/lib/locale/C.utf8"
for ((i=${#mounted[@]}-1; i>=0; i--)); do umount -R "${mounted[$i]}"; done
mounted=()
# Device nodes are created only by the native supervisor's bind mounts.
find "$root/dev" -mindepth 1 -delete
mkdir -p "$root/dev/pts" "$root/run/lock"
for device in null zero random urandom ptmx tty; do touch "$root/dev/$device"; done
find "$root" -xdev \( -type b -o -type c -o -type p -o -type s \) -delete
rm -f "$root/etc/mtab"
tar -czf "$out/runtime-sources.tar.gz" -C "$root/opt/dsh-mobile/corresponding-source" .
sha256sum "$out/runtime-sources.tar.gz" > "$out/runtime-sources-sha256.txt"
cp "$root/opt/dsh-mobile/licenses/incomplete-source-downloads.txt" "$out/incomplete-source-downloads.txt"
rm -rf "$root/opt/dsh-mobile/corresponding-source"
python3 "$source_dir/dsh-mobile/runtime/pack-runtime.py" "$root" "$out" "${GITHUB_SHA:-development}"
# Verify the distributed representation, not the pre-pack symlink tree.
verified=$(mktemp -d "${RUNNER_TEMP:-/tmp}/dsh-mobile-roundtrip.XXXXXX")
chmod 755 "$verified"
tar --extract --gzip --file "$out/runtime.tar.gz" --directory "$verified" --no-same-owner
mkdir -p "$verified/home/dsh" "$verified/tmp" "$verified/proc" "$verified/sys" "$verified/dev"
chown -R 65534:65534 "$verified/home/dsh" "$verified/tmp"
chmod 700 "$verified/home/dsh"
chmod 1777 "$verified/tmp"
for target in dev proc sys; do
  mount --rbind "/$target" "$verified/$target"
  mount --make-rslave "$verified/$target"
  mounted+=("$verified/$target")
done
chroot --userspec=65534:65534 --groups=3003 "$verified" /usr/bin/env -i \
  HOME=/home/dsh DSH_HOME=/home/dsh/.dsh TMPDIR=/tmp PATH=/usr/local/bin:/usr/bin:/bin LANG=C.UTF-8 \
  DSH_TELEMETRY_DISABLED=1 DSH_TOOLS_MODE=native \
  /usr/local/bin/node /opt/dsh-mobile/smoke.mjs
for ((i=${#mounted[@]}-1; i>=0; i--)); do umount -R "${mounted[$i]}"; done
mounted=()
printf 'PASS post-pack extraction as uid/gid 65534: node, npm, npx, pnpm, Git, PTY, sharp and formal mobile launcher boot. Android device/provider tests NOT RUN.\n' > "$out/runtime-validation.txt"
echo "Runtime outputs: $out/runtime.tar.gz $out/runtime-manifest.json"
