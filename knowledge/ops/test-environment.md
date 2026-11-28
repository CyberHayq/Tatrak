---
type: Runbook
title: Emulator and local XMPP test server
description: How to start the Android emulators and the Prosody test server in podman, log the app in and send it messages, for checks on a real XMPP connection.
tags: [ops, testing, emulator, prosody, podman, runbook]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-31T21:00:00Z }
sources:
  - id: server
    resource: ../../tools/test-server/
    title: Containerfile, prosody.cfg.lua, start.sh and send.py of the test server
---

Everything needed lives in the repository (`tools/test-server/`) and in the SDK from the
[build setup](../build/build-setup.md); nothing depends on temporary folders.

# Emulators

AVDs are created in Android Studio → Device Manager and stored in `~/.android/avd`:

| AVD name | Android | Use |
|----------|---------|-----|
| `Pixel_6_Android_12` | 12 (API 31) | Behaviour below API 34 (e.g. no predictive-back animator seeking) |
| `Pixel_6_Android_17` | 17 (API 37) | Current Android |
| `Medium_Phone_API_37.0` | 17 (API 37), Play Store image | Spare |
| `Pixel_4_XL` | 10 (API 29) | Attachment encryption device checks (voice into a pipe) |
| `Pixel_5` | 11 (API 30) | Same, API 30 |
| `Tatrak_API_26` | 8.0 (API 26) | Lowest supported API after `minSdk` 26; `MediaPlayer` with `MediaDataSource` |

`Tatrak_API_26` was not made in Android Studio (it found no API 26 image): the image
`system-images;android-26;default;x86_64` (`x86_64-26_r01.zip`, SHA-1
`432f149c048bffce7f9de526ec65b336daf7a0a3`, from
`https://dl.google.com/android/repository/sys-img/android/`) is unpacked into
`~/Android/system-images/android-26/default/`, and its `config.ini` is `Pixel_5`'s with
`android-30` replaced by `android-26` and the skin lines removed. It has no `package.xml`, so
Android Studio may not list it; the `emulator -avd Tatrak_API_26` command below starts it.

On the second dev machine the SDK is `~/Android/Sdk` (adb in `~/Android/Sdk/platform-tools`,
emulator in `~/Android/Sdk/emulator`) and the AVDs live in `~/.android/avd`; use
`ANDROID_AVD_HOME=~/.android/avd`. Their names, ports, serials and API levels are in that
machine's agent memory, not in git; start one as `emulator -avd <avd> -port <port>`. Gesture navigation for back-gesture checks:
`adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural`; a system back swipe is
`input motionevent DOWN 1 1200`, then `MOVE`s to the right and `UP` (screencap between them).

Start one (the shell's `ANDROID_HOME` points at the empty `/opt/android-sdk`, so override it):

```sh
export ANDROID_HOME=~/Android/Sdk ANDROID_SDK_ROOT=~/Android/Sdk ANDROID_AVD_HOME=~/.android/avd
~/Android/Sdk/emulator/emulator -avd Pixel_6_Android_12 -no-snapshot-save -no-audio -no-boot-anim &
ADB=~/Android/Sdk/platform-tools/adb
until [ "$($ADB shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
$ADB install -r build/outputs/apk/conversationsFree/debug/*x86_64.apk
$ADB shell dumpsys deviceidle whitelist +am.cyberhayq.chat   # skips the battery-optimisation dialog
```

`adb reboot` shuts the emulator down instead of restarting it; start it again with the command
above (`ANDROID_AVD_HOME` is required, the AVDs live in `~/.android/avd`). The API 31
emulator's camera app hangs on its splash screen; test camera capture on API 37.

# Test server

Prosody 13 (Debian trixie) in podman. Inside the emulator the host is `10.0.2.2`, so that IP is
the XMPP domain: no DNS, no `/etc/hosts`. Users `tester` and `friend`, password `secret123`,
registered at start. Self-signed certificate, generated once into the data volume so the app
keeps trusting it across rebuilds. Components: `conference.10.0.2.2` (MUC with MAM),
`upload.10.0.2.2` and `stickers.10.0.2.2` (HTTP upload on `https://10.0.2.2:5281/`, the latter
configured like [the sticker runbook](prosody-stickers.md)).

```sh
cd tools/test-server
podman build -t tatrak-prosody .
podman volume create tatrak-prosody-data
podman run -d --name tatrak-prosody -p 127.0.0.1:5222:5222 -p 127.0.0.1:5281:5281 \
  -v tatrak-prosody-data:/var/lib/prosody tatrak-prosody
podman logs -f tatrak-prosody        # "Authenticated as tester@10.0.2.2" once the app is in
```

Invites (2026-11-13): `mod_invites` and `invites_register` are on with
`registration_invite_only = false`, so plain in-band registration still works and a given token
is checked. Create one with `podman exec tatrak-prosody prosodyctl shell invite create_account
10.0.2.2` (any name) or `... create_account <user>@10.0.2.2`; delete test accounts with
`prosodyctl shell user delete <jid>` (needs `admin_shell`, also on since then).

Accounts `mono` and `conv` (password `secret123`) were added on 2026-11-07 for monocles chat and
Conversations in interop checks; they live in the volume. Prosody's
second upload service `stickers.10.0.2.2` (webp/json only) can be picked by other clients for
ordinary files, which then fail with "File type not allowed".

A container older than the repository config misses its later modules: on 2026-11-08 the running
one lacked `lastactivity` (added in `8d21317a4`) and was rebuilt as below. Check with
`podman exec tatrak-prosody grep lastactivity /etc/prosody/prosody.cfg.lua`.

Later sessions: `podman start tatrak-prosody`. After editing the config:
`podman rm -f tatrak-prosody`, build and run again (the volume keeps accounts, archive and certificate).
Removing the volume makes a new certificate; the app then needs to accept it again (simplest:
`adb shell pm clear am.cyberhayq.chat` and log in again).

## Logging the app in

Welcome screen → "I already have an account" → `tester@10.0.2.2` / `secret123` → Next →
"Accept Unknown Certificate?" → **Always** → skip the avatar.

On Android 17 (API 37) there are extra steps: hide the keyboard with `adb shell input keyevent 111`
(BACK would close the form), and after the first Next the app asks "LAN permission required"
(10.0.2.2 is a local address) → Grant → Allow → Next again. After the avatar step, allow notifications.

## Messages from the other side

```sh
podman exec tatrak-prosody python3 /send.py friend tester "Hi" "Second message"
podman exec tatrak-prosody python3 /send.py friend tester --bulk 3000  # one third reply to the previous message
podman exec tatrak-prosody python3 /send.py friend tester --file /path/in/container.webp   # upload + OOB link
```

Copy a file into the container first with `podman cp file.webp tatrak-prosody:/tmp/`.
`--file` uploads through `upload.10.0.2.2`, trusting only the server's own certificate (slixmpp's
built-in upload rejects it as self-signed). It PUTs to the container's own `127.0.0.1:5281`
with the slot's Host header: with podman's pasta networking, `10.0.2.2` inside the container does
not reach Prosody ("Connection refused", 2026-11-27). A webp or png of at most 512 px shows as a sticker.
The app does not fetch it from an unknown contact by itself: tap the download button once
(it checks the size), then once more to download. `send.py` is copied into the image at build time;
after editing it, `podman cp tools/test-server/send.py tatrak-prosody:/send.py` or rebuild.
To send attachments from the app, push generated files to `/sdcard/Pictures` etc. and pick them
with the attach button → File (the system picker grants access). A `content://media` URI shared
with `adb shell am start … ShareWithActivity` fails with "did not grant permission": the shell
cannot grant it. For app-to-app checks, log `friend` in on the second emulator.
Messages are plain text (no OMEMO); the chat from `friend` therefore shows unencrypted
messages, and replies from the app need encryption set to "Clear text" in the classic view's
lock menu, because `friend` has no OMEMO keys.

`send_sequence.py` sends a retraction / original / correction sequence with a stable id, for
checks of XEP-0308 corrections of retracted or locally deleted messages:
`podman cp tools/test-server/send_sequence.py tatrak-prosody:/send_sequence.py`, then
`podman exec tatrak-prosody python3 /send_sequence.py friend tester --order O,R,C --id case1`
(O original "secret <id>", R retraction, C correction "secret <id> EDITED", 1 s apart; use a fresh
`--id` per case). The app logs `dropping correction of <id>` (tag `tatrak`) when it drops one.

# Benchmark CPU profiling on the API 37 emulator

The Google APIs emulator image allows `adb root`; use only the target serial and restore the
normal ADB mode after profiling. The app must be the profileable `benchmark` build. For example:

```sh
ADB=~/Android/Sdk/platform-tools/adb
SERIAL=<serial>   # the API 37 emulator, from `adb devices`
$ADB -s $SERIAL root
$ADB -s $SERIAL wait-for-device
$ADB -s $SERIAL shell setprop security.perf_harden 0
$ADB -s $SERIAL shell 'simpleperf record -e cpu-clock:u -g -f 1000 -p $(pidof am.cyberhayq.chat.benchmark) --duration 25 -o /data/local/tmp/tatrak-snapshot.data'
$ADB -s $SERIAL shell 'simpleperf report -i /data/local/tmp/tatrak-snapshot.data --children --sort comm,dso,symbol -n'
$ADB -s $SERIAL shell rm /data/local/tmp/tatrak-snapshot.data
$ADB -s $SERIAL shell setprop security.perf_harden 1
$ADB -s $SERIAL unroot
```

CPU sample percentages are not wall-clock times; include the sampled workload and denominator
when reporting them. On a production device `adb root` may be unavailable.

# Screenshots and gestures from the shell

```sh
ADB=~/Android/Sdk/platform-tools/adb
$ADB exec-out screencap -p > shot.png                 # PNG of the current screen (1080x2400 on Pixel 6)
$ADB shell input tap 540 1200                          # tap
$ADB shell input swipe 200 1200 800 1200 300           # full swipe in 300 ms
# a slow drag that stays down (to look at the mid-gesture state), then release:
$ADB shell "input motionevent DOWN 200 1200; for x in 300 400 500; do input motionevent MOVE \$x 1205; done"
$ADB exec-out screencap -p > mid.png
$ADB shell input motionevent UP 500 1205
$ADB shell screenrecord --time-limit 5 /sdcard/a.mp4 && $ADB pull /sdcard/a.mp4   # for animations
```

# Keeping agent emulator checks cheap

Owner, 2026-11-13. An agent re-reads its whole conversation on every step, so every full-size
screenshot and every long command output is paid again on each later step. The Sonnet emulator
check of 2026-11-13 took 277 steps with 75 full-size screenshots opened as images; its context
grew to about 330k tokens and it read about 49.8M tokens from the cache in total. Rules for any
agent (and any brief) that runs emulator checks:

- Save screenshots to files; open an image only when it is the evidence you need, downscaled or
  cropped to the area in question:
  `magick shot.png -resize 40% small.png`,
  `magick shot.png -crop 1080x600+0+1800 +repage composer.png` (width x height + x + y).
- Find what to tap with `uiautomator dump` and `grep` for the text or resource id; never read the
  dumped XML whole:
  `$ADB shell uiautomator dump /sdcard/ui.xml >/dev/null && $ADB exec-out cat /sdcard/ui.xml | grep -o 'text="Send"[^>]*bounds="[^"]*"'`.
- Animations: extract frames with `ffmpeg -i a.mp4 -vf fps=30 f_%03d.png` and measure them with a
  short script that prints numbers (for example the y of an edge found by a colour change in one
  pixel column); open at most one small montage
  (`magick montage f_0{10,20,30,40}.png -tile 4x1 -geometry +4+4 -resize 25% m.png`), not every frame.
- Playing or paused audio: `dumpsys audio`'s AudioPlaybackConfiguration state is wrong on the
  emulators (idle while playing, paused after a resume; 2026-11-27, API 30 and 37). Use
  `dumpsys media.audio_flinger | grep -i "tracks of which"` (1 active while playing) and a
  position text that changes between two uiautomator dumps.
- Media session and background playback: `dumpsys media_session | grep -iE "state=|speed"` for
  playing or paused, `dumpsys activity services <pkg> | grep -iE "isForeground|foregroundServiceType"`
  for the foreground service, `dumpsys notification --noredact | grep <channel>` for the
  notification; controls with `cmd media_session dispatch play|pause|play-pause` and
  `input keyevent KEYCODE_HEADSETHOOK`. The session's position only moves on a state change
  (readers extrapolate it from the update time and speed), so read it from a pause, not from two
  dumps while playing. Unplugging headphones cannot be driven from adb.
- Cut command output: `logcat -d | grep -A20 'FATAL EXCEPTION'`, Gradle with `-q` and a grep for
  errors, `dumpsys … | grep <field>`.
- One scenario per agent: split a long check list into several short agents (for example keyboard
  on API 30, keyboard on API 37, reactions and menus), so no context grows large. A brief names the
  exact evidence it wants (numbers, one cropped image), so the agent does not collect more.

# Notes

- The emulator log may warn "Could not connect to proxy at <host>:<port>"; harmless.
- `podman exec … send.py` prints "Task was destroyed but it is pending!" at exit; harmless.
- Earlier checks (2026-10-29/30) ran against an ejabberd and a Prosody whose configs lived in a
  temporary folder and were lost; this setup replaces them.

## Appium regression suite

The executable pytest/Appium suite lives in [`tools/qa/`](../../tools/qa/README.md).
Run its commands from that directory; its Python and Node dependencies are separate from Gradle.
It contains nine regression cases and three smoke cases, including a login case that needs
a separate initial-state installation. Collection and default runs are safe without devices;
real device execution requires `--run-e2e` and explicit serials.
The [recorded results](../../tools/qa/RUN_RESULTS.md) cover Tatrak 0.5.0 on API 31 on 2026-11-25,
not the current development branch. See the [case definitions](../../tools/qa/TEST_CASES.md)
and [remaining catalogue](../../tools/qa/REGRESSION_CASES.md) for exact coverage and limitations.
