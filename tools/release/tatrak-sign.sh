#!/usr/bin/env bash
# Signs Tatrak's release APKs with the release key; the password is asked once and never stored.
# Usage: tatrak-sign.sh [release-dir]
#   default release-dir: the development repository's build (~/workspace/tatrak/build/outputs/apk/conversationsFree/release)
# The signed APKs stay in <release-dir>/signed under Gradle's names (for a GitHub release), and copies with
# names that cannot be confused go to ~/tatrak-apks: a build at a release tag as tatrak-<tag>-<abi>.apk (every ABI),
# any other build as tatrak-test-<commit>[-dirty]-<yyyymmdd-HHMM>-arm64-v8a.apk. Send or install only from there.
set -euo pipefail

DIR=${1:-$HOME/workspace/tatrak/build/outputs/apk/conversationsFree/release}
KEYSTORE=$HOME/tatrak-release.p12
# build tools from ANDROID_HOME, else ~/Android/Sdk, else an SDK unpacked straight into ~/Android
BT=${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/36.0.0
[ -x "$BT/apksigner" ] || BT=$HOME/Android/build-tools/36.0.0
OUT=$HOME/tatrak-apks
EXPECTED=3bae017d66664f88502ab51a6868621c1f93fd47004b3666796d3cd9a6946d9b  # release certificate SHA-256
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk

cd "$DIR"
shopt -s nullglob
apks=(am.cyberhayq.chat-*.apk)
[ ${#apks[@]} -gt 0 ] || { echo "no APKs in $DIR" >&2; exit 1; }

# label from the repository the APKs were built in: a tag only when HEAD is exactly at it and the tree is clean
repo=$(git -C "$DIR" rev-parse --show-toplevel)
commit=$(git -C "$repo" rev-parse --short HEAD)
dirty=$(git -C "$repo" status --porcelain --untracked-files=no | head -c1)
tag=$(git -C "$repo" describe --tags --exact-match HEAD 2>/dev/null || true)
if [ -n "$tag" ] && [ -z "$dirty" ]; then
  label=$tag
else
  label="test-$commit${dirty:+-dirty}-$(date -r "${apks[0]}" +%Y%m%d-%H%M)"
fi
echo "Built in $repo at $commit: $label"

# refuse a stale build (2026-11-16: an old 0.1.0 build was signed as v0.2.0): the APKs must carry
# HEAD's version and be newer than HEAD's commit
version=$(git -C "$repo" show HEAD:build.gradle | sed -n "s/^ *versionName = '\(.*\)'.*/\1/p" | head -1)
for apk in "${apks[@]}"; do
  case $apk in am.cyberhayq.chat-"$version"-*.apk) ;;
    *) echo "$apk is not version $version of $commit: rebuild first" >&2; exit 1 ;; esac
  [ "$(date -r "$apk" +%s)" -ge "$(git -C "$repo" log -1 --format=%ct HEAD)" ] ||
    { echo "$apk is older than $commit: rebuild first" >&2; exit 1; }
done

rm -rf signed && mkdir signed
read -rsp "Key password: " KSPASS; echo
export KSPASS
trap 'unset KSPASS' EXIT

for apk in "${apks[@]}"; do
  # --alignment-preserved: keep Gradle's ZIP layout, so F-Droid can copy this signature onto its
  # own unsigned build (apksigcopier); apksigner's default realignment made the copy fail to verify
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass env:KSPASS \
    --v1-signing-enabled false --alignment-preserved --out "signed/$apk" "$apk"
  "$BT/zipalign" -c 4 "signed/$apk"
  cert=$("$BT/apksigner" verify --print-certs "signed/$apk" | sed -n 's/.*certificate SHA-256 digest: //p')
  [ "$cert" = "$EXPECTED" ] || { echo "$apk: wrong certificate $cert" >&2; exit 1; }
  echo "signed: $apk"
done
rm -f signed/*.idsig
unset KSPASS
"$BT/apksigner" verify --verbose --print-certs "signed/${apks[0]}" | grep -E "Verified using|DN|SHA-256"

mkdir -p "$OUT"
for apk in signed/am.cyberhayq.chat-*.apk; do
  abi=${apk#signed/am.cyberhayq.chat-*-}; abi=${abi%.apk}   # am.cyberhayq.chat-<version>-<abi>.apk
  # a test build is for the owner's phone: arm64 only (AGENTS.md)
  [ "$label" = "$tag" ] || [ "$abi" = "arm64-v8a" ] || continue
  cp "$apk" "$OUT/tatrak-$label-$abi.apk"
  echo "$OUT/tatrak-$label-$abi.apk  sha256 $(sha256sum "$apk" | cut -c1-16)"
done
