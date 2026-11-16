---
type: Runbook
title: Build and screenshot-test setup
description: How to build Tatrak and run its Robolectric/Roborazzi screenshot tests with JDK 25 and Android SDK 37 on the development machine.
tags: [build, gradle, sdk, tests, runbook]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-31T21:00:00Z }
---

# Requirements

| What | Where it comes from | Check |
|------|---------------------|-------|
| JDK 25 | `pacman -S jdk25-openjdk` (Gradle toolchain asks for language version 25; it does not download one) | `ls /usr/lib/jvm` shows `java-25-openjdk` |
| Android SDK with `platforms;android-37.0` and `build-tools;36.0.0`, licences accepted | Android Studio (pacman package `android-studio`) → SDK Manager; SDK root `~/Android/Sdk` | `ls ~/Android/Sdk/platforms ~/Android/Sdk/build-tools ~/Android/Sdk/licenses` |
| `ANDROID_HOME` | `~/Android/Sdk` (with `~/Android`, lint vital resolves no class and fails with 62 `Instantiatable` errors; found 2026-11-13) | `ls $ANDROID_HOME/platforms` |

`ANDROID_HOME` in the shell points to `/opt/android-sdk` (pacman `android-sdk` 26.1.1, owned by
root, no platforms or licences). `local.properties` takes precedence for Gradle, so it does not
matter for builds; the emulator, however, reads `ANDROID_HOME` (see the
[test environment runbook](../ops/test-environment.md)).

Alternative without Studio: AUR `android-platform` (37) and `android-sdk-build-tools` into
`/opt/android-sdk`. That package ships build-tools **37.0.0**, while the Android Gradle Plugin
asks for 36.0.0, so the build fails with "licences have not been accepted … build-tools;36.0.0"
unless `buildToolsVersion = '37.0.0'` is added to the `android {}` block. The Studio SDK avoids
that change.

# Commands

```sh
./gradlew testConversationsFreeDebugUnitTest        # unit + Roborazzi screenshot tests
./gradlew assembleConversationsFreeDebug            # APKs in build/outputs/apk/conversationsFree/debug/
```

Count results:

```sh
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  build/test-results/testConversationsFreeDebugUnitTest/*.xml \
  | awk -F'"' '{t+=$2;f+=$6;e+=$8} END{print "tests",t,"failures",f,"errors",e}'
```

On 2026-10-31: 196 tests, 0 failures. For the emulator use the `x86_64` APK.

# Signed release APK

`assembleConversationsFreeRelease` signs with the release key only when the ignored
`signing.properties` exists; otherwise its APK is unsigned and cannot be installed. Tatrak keeps no
`signing.properties` (owner, 2026-11-14): the release key is a password-protected PKCS#12 file,
`~/tatrak-release.p12`, and its password is typed for every release, never stored.
Agents cannot sign with it; they build the unsigned APKs and the owner runs the signing in their own
terminal. Never generate a replacement release key to update an existing app.

A published release (GitHub and F-Droid's reproducible build) is built from the public
repository's tag (`~/workspace/tatrak-public`, see public export), not from
the development tree, because F-Droid rebuilds that tag and compares it with the signed APKs. It is
built in a temporary worktree of the public repository, removed after signing (the permanent
`~/workspace/tatrak-release` clone was removed on 2026-11-16: its APKs had the same names as the test
builds, and the owner sent an old one to his phone):

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk ANDROID_HOME=$HOME/Android/Sdk
unset ANDROID_SDK_ROOT   # the shell's /opt/android-sdk conflicts with ANDROID_HOME and fails the build
# the release APK carries no git data (vcsInfo off), so a worktree builds the same APK as a clone
git -C ~/workspace/tatrak-public worktree add --detach ~/workspace/tatrak-build-v<version> v<version>
cd ~/workspace/tatrak-build-v<version> && ./gradlew assembleConversationsFreeRelease
~/tatrak-sign.sh ~/workspace/tatrak-build-v<version>/build/outputs/apk/conversationsFree/release
git -C ~/workspace/tatrak-public worktree remove --force ~/workspace/tatrak-build-v<version>
```

`~/tatrak-sign.sh` is a link to `tools/release/tatrak-sign.sh`. It asks for the key password once,
signs every APK in the folder (v2 + v3, v1 off for minSdk 26), checks zipalign and the certificate
digest, and leaves the signed APKs under Gradle's names in `<release-dir>/signed` (for the GitHub
release). It also copies them to `~/tatrak-apks` under names that cannot be confused: a build at a
clean release tag as `tatrak-v<version>-<abi>.apk` (every ABI), any other build as
`tatrak-test-<commit>[-dirty]-<yyyymmdd-HHMM>-arm64-v8a.apk`. Send or install only from
`~/tatrak-apks`.

The signed APKs go to the tag's GitHub release. The release certificate (`CN=Tatrak, O=CyberHayq`,
RSA-2048, valid 2026-11-14 to 2054-04-01; public key SHA-256 `25087311…5b64`) has the SHA-256
digest
`3bae017d66664f88502ab51a6868621c1f93fd47004b3666796d3cd9a6946d9b`, the value for F-Droid's
`AllowedAPKSigningKeys` and the README. First run 2026-11-14 for `v0.1.0`: all five APKs signed
(v2 + v3), aligned, one certificate. The same key first had a certificate named `CN=Android APK
Signing`; it was reissued before anything was published, and that certificate is not used.

A test APK for the owner's phone is built in the development repository and signed the same
way (the phone's install is release-signed, so a debug-signed APK cannot update it):

```sh
cd ~/workspace/tatrak && ./gradlew assembleConversationsFreeRelease
~/tatrak-sign.sh            # default folder: this repository's release build
# -> ~/tatrak-apks/tatrak-test-<commit>[-dirty]-<time>-arm64-v8a.apk
```

When release APKs are only requested for testing, provide the signed `arm64-v8a` APK only, unless
the owner asks for other variants. An agent cannot run the release signing (the password is never
stored); it can sign a separate local test APK with the existing debug key (find its path with
`./gradlew signingReport`) and label it debug-signed:

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk ANDROID_HOME=$HOME/Android/Sdk
BUILD_TOOLS="$ANDROID_HOME/build-tools/36.0.0"
RELEASE_DIR=build/outputs/apk/conversationsFree/release
mkdir -p "$RELEASE_DIR/signed-debug"
for abi in arm64-v8a; do
  APK="am.cyberhayq.chat-<version>-$abi.apk"
  "$BUILD_TOOLS/apksigner" sign --ks "$HOME/.android/debug.keystore" \
    --ks-key-alias AndroidDebugKey --ks-pass pass:android --key-pass pass:android \
    --out "$RELEASE_DIR/signed-debug/$APK" "$RELEASE_DIR/$APK" || exit 1
  "$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$RELEASE_DIR/signed-debug/$APK" || exit 1
  "$BUILD_TOOLS/zipalign" -c 4 "$RELEASE_DIR/signed-debug/$APK" || exit 1
done
```

The debug key is for local testing, not distribution. Android accepts an update only when the
installed app's signing certificate matches (and normally its version code does not decrease).
Do not uninstall or clear data to work around a mismatch. The universal build can have a lower
version code than an ABI-specific build; `adb install -r -d` was used on the API 31 test emulator.

# F-Droid scanner

fdroidserver is not a project dependency; it lives in its own virtualenv outside the repository.
The scanner reads the source "current state" of an app that has a metadata file without `Builds`:

```sh
python3 -m venv ~/.venvs/fdroidserver && ~/.venvs/fdroidserver/bin/pip install fdroidserver
d=$(mktemp -d) && mkdir -p $d/metadata $d/build/am.cyberhayq.chat
git archive HEAD | tar -x -C $d/build/am.cyberhayq.chat
printf 'Categories:\n  - Internet\nLicense: GPL-3.0-only\nRepoType: git\nRepo: https://github.com/CyberHayq/Tatrak.git\n' \
  > $d/metadata/am.cyberhayq.chat.yml
(cd $d && ~/.venvs/fdroidserver/bin/fdroid scanner -r --json am.cyberhayq.chat)   # source tree
(cd $d && ~/.venvs/fdroidserver/bin/fdroid scanner -e <path/to.apk>)              # built APK
```

Expected today: no errors, warnings only for the binary test vectors in
`src/test/resources/media-vectors/`. The scanner deletes the Gradle wrapper in its copy, which is
normal.

# F-Droid build test

`tools/fdroid/am.cyberhayq.chat.yml` is the draft of the fdroiddata recipe (per-ABI builds like
fdroiddata's `eu.siacs.conversations.yml`: JDK 25 from Debian through `sudo`, flavour
`conversations` + `free`, one `output` glob per ABI, `binary:` from the GitHub release,
`AllowedAPKSigningKeys` = the release certificate). `tools/fdroid/test-build.sh <repo> <commit>
[abi]` builds one ABI as F-Droid's CI does: fdroidserver from git in the official
`registry.gitlab.com/fdroid/fdroidserver:buildserver` image (Debian 13, JDK 21 default, JDK 25
installed by the recipe), `fdroid build --on-server`, the repository mounted read-only (so the
still private public repository works), `binary:` left out. It prints the unsigned APK's SHA-256;
compare it with a local unsigned `assembleConversationsFreeRelease` of the same commit (a worktree
of `tatrak-public`, `ANDROID_HOME` = this machine's SDK, `~/Android` on the first dev machine).

```sh
WORK=$(mktemp -d) tools/fdroid/test-build.sh ../tatrak-public <commit>   # about 6 min, needs podman
```

Do not add Conversations' `prebuild` that turns `universalApk` off: the main output then becomes
the first ABI split, `BuildConfig.VERSION_CODE` changes (5000000 → 5000001, used in
`LocationActivity`'s user agent) and the dex no longer matches the APK we sign (found 2026-11-16).
`fdroid lint` outside fdroiddata reports the categories as invalid (it lacks
`config/categories.yml`); `fdroid rewritemeta` leaves the draft unchanged apart from its comment
header, which goes when the recipe is submitted.

# Judging speed: the `benchmark` build

Debug builds are 3–4× slower than release (on 2026-10-31 the first chat build took 315 ms in
debug and 95 ms optimised on the emulator), so smoothness is judged on the `benchmark` build type:
release code (R8, baseline profile) signed with the debug key, installed next to the debug app as
`am.cyberhayq.chat.benchmark` (its own login).

```sh
./gradlew assembleConversationsFreeBenchmark       # APKs in build/outputs/apk/conversationsFree/benchmark/
```

# Recovery history

2026-10-31: JDK 25 and the Flatpak Android Studio (with its SDK and AVDs) had disappeared from
the machine; restored with `jdk25-openjdk` and the pacman `android-studio`, SDK in `~/Android/Sdk`.
