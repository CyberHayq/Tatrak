#!/usr/bin/env bash
# Builds Tatrak the way F-Droid would (fdroidserver's `fdroid build --on-server` in the official
# buildserver image, as fdroiddata's CI does) and prints the unsigned APK's path and SHA-256, to
# compare with a local build of the same commit (reproducibility).
# Usage: [SIGNED=<our signed apk>] test-build.sh <git-repo> <commit> [abi]   (abi: arm64-v8a by default)
# With SIGNED, it also copies that APK's signature onto F-Droid's build and verifies it, as F-Droid
# does before publishing our signed APK (needs fdroidserver in ~/.venvs/fdroidserver, see the runbook).
# The repository is mounted read-only, so a private repository works; the build uses the recipe
# next to this script, with Repo, commit and version pointed at the arguments, without `binary:`.
set -euo pipefail

repo=$(realpath "$1"); commit=$2; abi=${3:-arm64-v8a}
here=$(dirname "$(realpath "$0")")
work=${WORK:-$(mktemp -d)}
image=registry.gitlab.com/fdroid/fdroidserver:buildserver
# the build entry of the chosen ABI: its versionCode ends in the ABI offset of build.gradle's abiCodes
declare -A offsets=([armeabi-v7a]=1 [x86]=2 [x86_64]=3 [arm64-v8a]=4)
code=$(grep -oE "versionCode: [0-9]+${offsets[$abi]}\b" "$here/am.cyberhayq.chat.yml" | grep -oE '[0-9]+$')

mkdir -p "$work/metadata"
# keep only the build entry of the chosen ABI, without `binary:` (nothing published to compare with)
python3 - "$here/am.cyberhayq.chat.yml" "$work/metadata/am.cyberhayq.chat.yml" "$commit" "$code" <<'EOF'
import re, sys
src, dst, commit, code = sys.argv[1:]
text = open(src).read()
head, rest = text.split("Builds:\n", 1)
builds, tail = rest.split("\n\nAllowedAPKSigningKeys", 1)
entry = next(b for b in builds.split("\n\n") if f"versionCode: {code}\n" in b)
entry = re.sub(r"\n    binary: .*", "", entry)
entry = re.sub(r"commit: .*", f"commit: {commit}", entry)
head = head.replace("Repo: https://github.com/CyberHayq/Tatrak.git", "Repo: /src")
open(dst, "w").write(head + "Builds:\n" + entry + "\n\nAllowedAPKSigningKeys" + tail)
EOF
echo "work dir: $work"

podman run --rm -v "$repo:/src:ro" -v "$work:/work" -w /work "$image" bash -euo pipefail -c "
  git config --global --add safe.directory '*'
  command -v sudo >/dev/null || (apt-get update -qq && apt-get install -y -qq sudo >/dev/null)
  git clone -q --depth 1 https://gitlab.com/fdroid/fdroidserver.git /opt/fdroidserver
  export PATH=/opt/fdroidserver:\$PATH ANDROID_HOME=/opt/android-sdk
  fdroid build --verbose --on-server --no-tarball am.cyberhayq.chat:$code
" 2>&1 | tee "$work/build.log" | grep -E "^(ERROR|CRITICAL|WARNING|INFO)|BUILD (SUCCESSFUL|FAILED)" || true

apk=$(ls "$work"/unsigned/*.apk 2>/dev/null | head -1 || true)
[ -n "$apk" ] || { echo "no APK; see $work/build.log" >&2; exit 1; }
echo "$apk"; sha256sum "$apk"
if [ -n "${SIGNED:-}" ]; then
  ~/.venvs/fdroidserver/bin/python -I -c 'import sys; from fdroidserver import apksigcopier as a
a.do_copy(sys.argv[1], sys.argv[2], sys.argv[3], v1_only=a.NO)' "$SIGNED" "$apk" "$work/copied.apk"
  "${APKSIGNER:-apksigner}" verify "$work/copied.apk" && echo "signature copy verifies: F-Droid can publish $SIGNED"
fi
