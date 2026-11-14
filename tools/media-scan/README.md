# Goal 1 scan: no plaintext media in private storage

`scan.py` checks a **debug** install of Tatrak on a device or emulator for plaintext media
(attachment encryption design, Goal 1 and "Temporary plaintext"; step 7 plan, part 1). Python 3
standard library only. It runs one shell script as the app through
`adb shell run-as <package> sh` (the script comes on stdin), which lists every file of the app's
data folder (`/data/user/0/<package>`) with its size, change time and first 16 bytes; the rules
are applied on the host. It reads nothing else and changes nothing.

```sh
python3 tools/media-scan/scan.py --self-test                 # classifier on generated headers, no device
ADB=~/Android/Sdk/platform-tools/adb python3 tools/media-scan/scan.py --serial <serial> [--strict] [--package am.cyberhayq.chat]
```

Run it on a clean debug install after the emulator checks (photos, video, voice, stickers,
wallpaper, avatars, camera, one shared account QR code), with nothing recording, transcoding or
cropping. Output: one line per finding, a summary, exit code 1 when anything fails.

## Rules (paths relative to the data folder)

| Where | Rule | Level |
|---|---|---|
| `files/attachments/`, `files/wallpapers/<generation>/`, `files/Stickers/<generation>/`, `cache/avatars/` | every file starts with `TATRKENC`; OpenPGP `*.pgp` intermediates skipped | FAIL |
| `cache/Recordings/`, `cache/tatrak_transcode/`, `cache/tatrak_avatar_tmp/` | empty | FAIL |
| `cache/Camera/` | no file older than 24 hours (device clock) | FAIL; younger ones INFO |
| `files/wallpapers/`, `files/Stickers/` outside a generation folder | saved before step 5 | KNOWN, FAIL with `--strict` |
| `files/ShortcutInfoCompatSaver_share_targets/` | share-target avatars written by AndroidX (owner decision B, design Non-Goals) | KNOWN, never fails |
| `databases/`, `no_backup/tatrak-databases/`, `shared_prefs/`, `code_cache/`, `cache/stacktrace.txt` (crash report), `cache/barcodes/` (account QR codes), `files/tatrak_tmp/` (the store's ciphertext in progress), `app_webview/`, `cache/WebView/` | skipped | - |
| anything else | fails when its first bytes are JPEG, PNG, GIF, WebP, HEIF/AVIF/MP4/M4A/3GP (`ftyp`), OGG, AAC ADTS, MP3, WAV, Matroska/WebM or PDF | FAIL |

The code has no HTTP disk cache (OkHttp is built without `Cache`), so none is listed; add its
folder to `ALLOWED` if one appears. No Compose disk cache was found either.

## Limits

* Debug builds only: `run-as` refuses a release or `benchmark` install.
* Only the credential-encrypted data folder; device-protected storage (`/data/user_de/0/...`) and
  app-specific external storage (`Android/data/<package>`) are not scanned.
* A file name containing a newline breaks the listing of that file.
* Format detection reads 16 bytes: a media file with a leading junk byte, or a format not in the
  list (FLAC, AMR, AVI), is not recognised.
