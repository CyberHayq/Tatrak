#!/bin/sh
# Builds src/test/resources/update-test-apk/signed-apk.bin (not .apk: the repository ignores *.apk):
# an empty app signed by a throwaway key that is deleted afterwards, for UpdaterTest's archive-parser
# check. JAR (v1) signature only: Robolectric cannot run Android's v2/v3 verifier on the JVM.
# Prints the signer's SHA-256, which the test pins. Needs the Android SDK and a JDK (keytool).
set -eu
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
bt=$sdk/build-tools/37.0.0
platform=$sdk/platforms/android-37.0/android.jar
out=$(cd "$(dirname "$0")/../.." && pwd)/src/test/resources/update-test-apk/signed-apk.bin
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

cat > "$work/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="am.cyberhayq.chat.updatetest" android:versionCode="7" android:versionName="7">
    <application android:hasCode="false" />
</manifest>
EOF
"$bt/aapt2" link -o "$work/unsigned.apk" --manifest "$work/AndroidManifest.xml" -I "$platform" \
    --min-sdk-version 26 --target-sdk-version 28
keytool -genkeypair -keystore "$work/key.p12" -storetype PKCS12 -storepass secret123 -alias test \
    -keyalg RSA -keysize 2048 -validity 36500 -dname CN=tatrak-update-test >/dev/null 2>&1
mkdir -p "$(dirname "$out")"
"$bt/apksigner" sign --ks "$work/key.p12" --ks-pass pass:secret123 --min-sdk-version 26 \
    --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
    --out "$out" "$work/unsigned.apk"
rm -f "$out.idsig"
"$bt/apksigner" verify --print-certs "$out" | grep -m1 'SHA-256'
