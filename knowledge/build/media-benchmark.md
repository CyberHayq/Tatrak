---
type: Runbook
title: Media benchmark (attachment encryption speed budgets)
description: How to run MediaBenchmark on the benchmark build - sequential read cold and warm, viewer photo, first video frame and seek, getThumbnail and chat scroll, plain against the encrypted media store - and what "cold" means without root.
tags: [build, benchmark, performance, encryption, storage, android]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-13 }
sources:
  - id: benchmark
    resource: ../../src/benchmark/kotlin/am/cyberhayq/chat/perf/MediaBenchmark.kt
    title: The benchmark activity
  - id: budgets
    resource: ../egm/attachment-encryption/design_draft.md
    title: Attachment encryption design, "Speed budgets" and item 12
  - id: plan
    resource: ../egm/attachment-encryption/step7-plan.md
    title: Step 7 plan, part 2
---

# Purpose

`MediaBenchmark` (benchmark build type only, next to `PerfDemoActivity`) measures the
speed budgets of attachment encryption: the same
generated media read as a plain file and through `EncryptedMediaStore`. **The budgets count only
on a real ARM phone** (the owner's device run); x86_64 emulators decrypt with the host's AES
instructions, so an emulator run is a smoke test that the harness works.

The activity runs inside the `am.cyberhayq.chat.benchmark` install, which on its first start makes
its own empty history database and media key (no account needed). It writes only into that
install's private storage: plain copies in `files/media_benchmark/`, encrypted copies as
attachments in `files/attachments/` (names in `files/media_benchmark/index.txt`). Generated media
only: 100 MB of random bytes, a 20 s H.264 1280x720 video of moving grain (encoded on the device
with `MediaCodec`), one 4032x3024 JPEG and 24 JPEGs of 1920x1440 (gradients, circles, grain).

# What it measures

| Line in the output | How | Budget |
|---|---|---|
| `read cold` | the first read of each 100 MB file after a reboot or a cache drop, 256 KiB buffer; plain `FileInputStream` against `EncryptedMediaStore.inputStream` | encrypted at least 90 % of plain, or at least 50 MB/s, whichever is lower |
| `read warm` | median of 3 further reads each | reported only |
| `viewer photo` | `ViewerImages.decode` (what `MediaViewerActivity` calls) on the plain and the store file, at the viewer's size for the page on screen (twice the screen, at most 4096 px); 1 warm-up, median of 5 each | at most 20 ms later than plain |
| `video first frame`, `video seek` | `MediaPlayer` on a `TextureView`: plain `setDataSource(path)` against the store's `MediaDataSource` (as the viewer); from `setDataSource` to `MEDIA_INFO_VIDEO_RENDERING_START`, then `seekTo(duration / 2)` (the viewer's default mode) to `onSeekComplete`; 1 warm-up, median of 5 each | at most 50 ms later than plain |
| `getThumbnail` | `FileBackend.getThumbnail` of the bound `XmppConnectionService` (the production thumbnail path) on the 24 photos, a new `Message` each time so the bitmap cache never hits; mean per photo, median of 3 passes | reported only (explains the scroll result) |
| `scroll ... run N` | the real `ChatScreen` with 150 photo messages whose `loadThumbnail` / `cachedThumbnail` do what `ChatFragment` does (bounded decode pool, `getThumbnail`, the service's `LruCache`); the cache is emptied before each run; after 2 s for the first screen, 15 synthetic flings toward older messages, 600 ms apart; frames from `Window.addOnFrameMetricsAvailableListener`, janky = `TOTAL_DURATION` above one refresh interval; runs alternate plain, encrypted, three each | janky frames of encrypted not above plain |

Every line goes to logcat under the tag `TatrakMediaBench` and on the screen; the run ends with
`DONE` (or `FAILED: ...` then `DONE`).

## "Cold" without root

A non-rooted phone cannot drop the page cache, so the media are written in a separate start
(`-e step prepare`) and the phone is **rebooted** (and unlocked) before `-e step run`: the first
read of each file in the new boot comes from flash. On a rooted device or an emulator with
`adb root`, `echo 3 > /proc/sys/vm/drop_caches` after a `sync` does the same without a reboot.
When `run` finds no media it prepares them first and labels the first read `first (NOT cold)`.

# Commands

```sh
ADB=~/Android/Sdk/platform-tools/adb
S=<serial from adb devices>
PKG=am.cyberhayq.chat.benchmark
ACT=$PKG/am.cyberhayq.chat.perf.MediaBenchmark
./gradlew assembleConversationsFreeBenchmark
APK=build/outputs/apk/conversationsFree/benchmark/am.cyberhayq.chat.benchmark-*-x86_64.apk   # arm64-v8a on the phone
$ADB -s $S install -r $APK
done_wait() { timeout 1800 sh -c "until $ADB -s $S logcat -d -s TatrakMediaBench:I | grep -q ' DONE'; do sleep 5; done"; }

# 1. prepare (the video encode takes a while on a software encoder)
$ADB -s $S logcat -c
$ADB -s $S shell am force-stop $PKG
$ADB -s $S shell am start -n $ACT -e step prepare
done_wait; $ADB -s $S logcat -d -s TatrakMediaBench:I

# 2. drop the page cache: emulator with adb root, or a reboot (phone; unlock it afterwards)
$ADB -s $S root && $ADB -s $S wait-for-device && $ADB -s $S shell 'sync; echo 3 > /proc/sys/vm/drop_caches'
#   or: $ADB -s $S reboot && $ADB -s $S wait-for-device   (then unlock the screen)

# 3. measure (keep the screen on and the app in front until DONE)
$ADB -s $S logcat -c
$ADB -s $S shell am force-stop $PKG
$ADB -s $S shell am start -n $ACT -e step run
done_wait; $ADB -s $S logcat -d -s TatrakMediaBench:I

# 4. remove the media (about 230 MB)
$ADB -s $S shell am force-stop $PKG
$ADB -s $S shell am start -n $ACT -e step clean
```

For the phone run, repeat step 2 and 3 at least twice and keep both outputs; note the phone
model, Android version and whether it was charging.

# Limits

* Synthetic flings (`dispatchTouchEvent` on the window) are not the touch pipeline of a real
  finger; they are the same for both stores, which is what the budget compares.
* The janky-frame rule (one refresh interval) is simpler than `dumpsys gfxinfo`'s; it is applied
  the same way to both stores.
* Photos carry no EXIF rotation and the chat holds only photos: the scroll measures thumbnail
  decoding, not the mix of a real chat.
* The plain side of the viewer and thumbnail lines goes through the store's plain branch (a
  `FileInputStream` behind `MediaFile`), which is what the app does for a plain file today.
* Not run on any device yet.
