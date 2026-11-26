#!/usr/bin/env bash
# Source this file from a shell before starting the local emulator test environment.
TATRAK_QA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export TATRAK_QA_ROOT
if [ -d "$TATRAK_QA_ROOT/.tools/android-sdk/platform-tools" ]; then
  export ANDROID_HOME="$TATRAK_QA_ROOT/.tools/android-sdk"
else
  export ANDROID_HOME="${ANDROID_HOME:-$TATRAK_QA_ROOT/.tools/android-sdk}"
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_AVD_HOME="$TATRAK_QA_ROOT/.tools/avd"
export APPIUM_HOME="$TATRAK_QA_ROOT/.tools/appium-home"
if [ -x "$TATRAK_QA_ROOT/.tools/java/bin/java" ]; then
  export JAVA_HOME="$TATRAK_QA_ROOT/.tools/java"
else
  export JAVA_HOME="${JAVA_HOME:-$TATRAK_QA_ROOT/.tools/java}"
fi
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$JAVA_HOME/bin:$TATRAK_QA_ROOT/.tools/appium/node_modules/.bin:$PATH"
