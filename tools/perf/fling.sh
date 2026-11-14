#!/bin/sh
# Frame times of the benchmark build's PerfDemoActivity (generated data, no account needed).
# Usage: tools/perf/fling.sh APK list|chat SERIAL [flings]   (SERIAL from `adb devices`)
# Prints the janky-frame summary of `dumpsys gfxinfo` after the flings.
set -e
APK=$1
SCREEN=${2:-list}
DEV=${3:?SERIAL from adb devices}
N=${4:-48}
ADB="${ADB:-$HOME/Android/platform-tools/adb} -s $DEV"
PKG=am.cyberhayq.chat.benchmark
$ADB install -r "$APK" >/dev/null
$ADB shell am force-stop $PKG
$ADB shell am start -W -n $PKG/am.cyberhayq.chat.perf.PerfDemoActivity -e screen "$SCREEN" >/dev/null
sleep 3
$ADB shell dumpsys gfxinfo $PKG reset >/dev/null
i=0
while [ $i -lt "$N" ]; do
    # the chat list scrolls down, the chat (reversed) scrolls up; alternate directions
    if [ $((i % 2)) -eq 0 ]; then $ADB shell input swipe 540 1800 540 600 120; else $ADB shell input swipe 540 600 540 1800 120; fi
    sleep 0.6
    i=$((i + 1))
done
$ADB shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Slow UI thread"
