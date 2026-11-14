#!/usr/bin/env python3
"""Goal 1 scan of attachment encryption: no plaintext media in Tatrak's private storage.

Lists every file of a debug install through `adb shell run-as` and checks it against the rules
of tools/media-scan/README.md. Python 3 standard library only. `--self-test` needs no device.
"""
import argparse
import os
import re
import subprocess
import sys

MAGIC = b"TATRKENC"
DAY = 24 * 60 * 60
GENERATION = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

# the store's folders (EncryptedMediaStore); wallpapers and stickers inside a generation folder
STORE = re.compile(r"(files/attachments/|files/wallpapers/%s/|files/Stickers/%s/|cache/avatars/)" % (GENERATION, GENERATION))
# wallpapers and sticker packs saved before step 5, outside a generation folder
LEGACY = re.compile(r"files/(wallpapers|Stickers)/")
# design "Temporary plaintext": empty while nothing records, transcodes or crops
EMPTY_WHEN_IDLE = ("cache/Recordings/", "cache/tatrak_transcode/", "cache/tatrak_avatar_tmp/")
CAMERA = "cache/Camera/"
# owner decision B (2026-11-13): AndroidX writes the share-target avatars, reported, never failing
SHORTCUTS = "files/ShortcutInfoCompatSaver_share_targets/"
# not media by purpose; skipped without a look at their bytes
ALLOWED = (
    "databases/", "no_backup/tatrak-databases/", "shared_prefs/", "code_cache/",
    "cache/stacktrace.txt",  # crash report, utils/ExceptionHelper.java
    "cache/barcodes/",  # account QR codes handed to other apps, services/BarcodeProvider.java
    "files/tatrak_tmp/",  # the store's own temporary folder: ciphertext being written
    "app_webview/", "cache/WebView/",
)


def media_kind(head):
    """Name of the image, audio or video format [head] starts with; None for anything else."""
    if head.startswith(b"\xff\xd8\xff"):
        return "JPEG"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "PNG"
    if head[:6] in (b"GIF87a", b"GIF89a"):
        return "GIF"
    if head.startswith(b"RIFF") and head[8:12] == b"WEBP":
        return "WebP"
    if head.startswith(b"RIFF") and head[8:12] == b"WAVE":
        return "WAV"
    if head[4:8] == b"ftyp":  # HEIF, AVIF, MP4, M4A, 3GP, MOV
        return "ISO-BMFF " + head[8:12].decode("latin-1").strip()
    if head.startswith(b"OggS"):
        return "OGG"
    if head.startswith(b"\x1a\x45\xdf\xa3"):
        return "Matroska/WebM"
    if head.startswith(b"%PDF-"):
        return "PDF"
    if head.startswith(b"ID3"):
        return "MP3"
    if len(head) >= 4 and head[0] == 0xFF and head[1] & 0xF0 == 0xF0 and head[1] & 0x06 == 0:
        return "AAC ADTS"
    # an MPEG audio frame header with valid version, layer, bitrate and sample rate
    if len(head) >= 4 and head[0] == 0xFF and head[1] & 0xE0 == 0xE0 and (head[1] >> 3) & 3 != 1 \
            and (head[1] >> 1) & 3 != 0 and (head[2] >> 4) not in (0, 15) and (head[2] >> 2) & 3 != 3:
        return "MP3"
    return None


def check(path, size, mtime, head, now, strict=False):
    """(level, message) for one file, path relative to the data folder; level FAIL, KNOWN, INFO or None (fine)."""
    kind = media_kind(head)
    described = "%s, %d bytes" % (kind or "not media", size)
    if path.startswith(ALLOWED):
        return None, None
    if STORE.match(path):
        if path.endswith(".pgp"):  # OpenPGP ciphertext, read raw (design "Reading rule")
            return None, None
        return (None, None) if head.startswith(MAGIC) else ("FAIL", "store file not encrypted (%s)" % described)
    if LEGACY.match(path):
        return ("FAIL" if strict else "KNOWN"), "saved before step 5, outside a generation folder (%s)" % described
    if path.startswith(EMPTY_WHEN_IDLE):
        return "FAIL", "temporary plaintext left over (%s)" % described
    if path.startswith(CAMERA):
        age = now - mtime
        if age > DAY:
            return "FAIL", "camera capture older than 24 hours (%s, %.1f h)" % (described, age / 3600)
        return "INFO", "camera capture within its bound (%s, %.1f h)" % (described, age / 3600)
    if path.startswith(SHORTCUTS):
        return "KNOWN", "share-target shortcut bitmap, decision B (%s)" % described
    if kind:
        return "FAIL", "plain media outside the store (%s)" % described
    return None, None


# runs on the device as the app: one line per file, path last
DEVICE_SCRIPT = r"""
echo "NOW $(date +%s)"
find . -type f 2>/dev/null | while IFS= read -r f; do
  set -- $(stat -c '%s %Y' "$f")
  h=$(od -A n -t x1 -N 16 "$f" 2>/dev/null | tr -d ' \n')
  printf 'F\t%s\t%s\t%s\t%s\n' "$1" "$2" "${h:--}" "${f#./}"
done
"""


def parse(output):
    """(now, [(path, size, mtime, head)]) from the device script's output."""
    now, files = None, []
    for line in output.splitlines():
        if line.startswith("NOW "):
            now = int(line[4:])
        elif line.startswith("F\t"):
            _, size, mtime, hexed, path = line.split("\t", 4)
            files.append((path, int(size), int(mtime), b"" if hexed == "-" else bytes.fromhex(hexed)))
    if now is None:
        raise SystemExit("no answer from the device:\n" + output)
    return now, files


def scan(serial, package, strict):
    adb = [os.environ.get("ADB", "adb")] + (["-s", serial] if serial else [])
    result = subprocess.run(adb + ["shell", "run-as", package, "sh"], input=DEVICE_SCRIPT,
                            capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit("adb run-as failed (a debug build of %s installed?):\n%s" % (package, result.stderr + result.stdout))
    now, files = parse(result.stdout)
    counts = {"FAIL": 0, "KNOWN": 0, "INFO": 0}
    for path, size, mtime, head in sorted(files):
        level, message = check(path, size, mtime, head, now, strict)
        if level:
            counts[level] += 1
            print("%-5s %s: %s" % (level, path, message))
    print("%d files scanned: %d failing, %d known exceptions, %d within a temporary bound"
          % (len(files), counts["FAIL"], counts["KNOWN"], counts["INFO"]))
    return 1 if counts["FAIL"] else 0


def self_test():
    gen = "0123abcd-0000-4000-8000-00000000beef"
    jpeg, png = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00", b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR"
    heads = {
        "JPEG": jpeg, "PNG": png, "GIF": b"GIF89a\x01\x00", "WebP": b"RIFF\x10\x00\x00\x00WEBPVP8 ",
        "WAV": b"RIFF\x10\x00\x00\x00WAVEfmt ", "ISO-BMFF heic": b"\x00\x00\x00\x18ftypheic",
        "ISO-BMFF avif": b"\x00\x00\x00\x1cftypavif", "ISO-BMFF M4A": b"\x00\x00\x00\x20ftypM4A ",
        "ISO-BMFF isom": b"\x00\x00\x00\x20ftypisom", "OGG": b"OggS\x00\x02", "AAC ADTS": b"\xff\xf1\x50\x80",
        "MP3": b"\xff\xfb\x90\x64", "Matroska/WebM": b"\x1a\x45\xdf\xa3\x9f", "PDF": b"%PDF-1.7\n",
    }
    for name, head in heads.items():
        assert media_kind(head) == name, (name, media_kind(head))
    assert media_kind(b"ID3\x04\x00") == "MP3"
    for head in (b"", b"TATRKENC\x01", b"SQLite format 3\x00", b"\xff\xfb\xf0\x64", b"\xff\xef\x90\x64", b"hello"):
        assert media_kind(head) is None, head
    now = 10 * DAY
    enc = MAGIC + b"\x01\x02"
    cases = [  # path, head, age in seconds, strict, expected level
        ("files/attachments/tatrak_%s_%s.jpg" % (gen, "0" * 32), enc, 0, False, None),
        ("files/attachments/tatrak_%s_%s.jpg" % (gen, "0" * 32), jpeg, 0, False, "FAIL"),
        ("files/attachments/notes.txt", b"hello", 0, False, "FAIL"),  # not media, but in the store
        ("files/attachments/tatrak_x.jpg.pgp", b"\x85\x01\x0c", 0, False, None),
        ("files/wallpapers/%s/abc.jpg" % gen, enc, 0, False, None),
        ("files/wallpapers/%s/abc.jpg" % gen, jpeg, 0, False, "FAIL"),
        ("files/Stickers/%s/pack-1/a.webp" % gen, enc, 0, False, None),
        ("files/Stickers/%s/pack-1/a.webp" % gen, heads["WebP"], 0, False, "FAIL"),
        ("cache/avatars/0a1b", enc, 0, False, None),
        ("cache/avatars/0a1b", png, 0, False, "FAIL"),
        ("files/wallpapers/abc.jpg", jpeg, 0, False, "KNOWN"),
        ("files/wallpapers/abc.jpg", jpeg, 0, True, "FAIL"),
        ("files/Stickers/pack-1/a.webp", heads["WebP"], 0, False, "KNOWN"),
        ("cache/Recordings/r.ogg", heads["OGG"], 0, False, "FAIL"),
        ("cache/tatrak_transcode/v.mp4", b"", 0, False, "FAIL"),
        ("cache/tatrak_avatar_tmp/crop.png", png, 0, False, "FAIL"),
        ("cache/Camera/c.jpg", jpeg, DAY - 60, False, "INFO"),
        ("cache/Camera/c.jpg", jpeg, DAY + 60, False, "FAIL"),
        ("files/ShortcutInfoCompatSaver_share_targets/x.png", png, 0, False, "KNOWN"),
        ("cache/barcodes/qr.png", png, 0, False, None),
        ("databases/history", jpeg, 0, False, None),
        ("files/tatrak_tmp/0a.part", jpeg, 0, False, None),
        ("cache/0a1b.jpg.pgp", b"\x85\x01\x0c", 0, False, None),
        ("cache/image.jpg", jpeg, 0, False, "FAIL"),
        ("files/avatars/x", heads["ISO-BMFF avif"], 0, False, "FAIL"),
        ("shared_prefs/am.cyberhayq.chat_preferences.xml", b"<?xml", 0, False, None),
        ("cache/notes.bin", b"\x00\x01\x02", 0, False, None),
    ]
    for path, head, age, strict, expected in cases:
        level = check(path, len(head), now - age, head, now, strict)[0]
        assert level == expected, (path, level, expected)
    now_seen, files = parse("NOW 100\nF\t3\t50\tffd8ff\tfiles/a b.jpg\nF\t0\t50\t-\tcache/empty\nnoise\n")
    assert now_seen == 100 and files == [("files/a b.jpg", 3, 50, jpeg[:3]), ("cache/empty", 0, 50, b"")], files
    print("self-test passed: %d formats, %d path cases" % (len(heads) + 1, len(cases)))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", default="am.cyberhayq.chat", help="debuggable package to scan")
    parser.add_argument("--serial", help="device serial from `adb devices` (adb's default otherwise)")
    parser.add_argument("--strict", action="store_true", help="wallpapers and stickers saved before step 5 fail too")
    parser.add_argument("--self-test", action="store_true", help="check the classifier without a device")
    args = parser.parse_args()
    sys.exit(self_test() if args.self_test else scan(args.serial, args.package, args.strict))


if __name__ == "__main__":
    main()
