#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
mkdir -p "$TATRAK_QA_ROOT/artifacts/emulator-run"
for role in A B; do
  port=5554
  [ "$role" = B ] && port=5556
  serial="emulator-$port"
  if ! timeout 5 adb -s "$serial" get-state >/dev/null 2>&1; then
    timeout 7200 emulator -avd "Tatrak_QA_$role" -port "$port" \
      -no-window -no-audio -no-boot-anim -no-snapshot \
      -gpu software -feature -Vulkan -memory 1536 -cores 2 \
      > "$TATRAK_QA_ROOT/artifacts/emulator-run/emulator-$role.log" 2>&1 &
    echo "$!" > "$ANDROID_AVD_HOME/Tatrak_QA_$role.pid"
  fi
done
timeout 240 bash -c '
  while [ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" != 1 ] ||
        [ "$(adb -s emulator-5556 shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" != 1 ]; do
    sleep 2
  done
'
adb devices -l
