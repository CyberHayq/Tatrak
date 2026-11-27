---
type: Specification
title: Encrypted media file format v1 (byte level)
description: Byte-level specification of Tatrak's encrypted media files (phase 3) - prefix, Tink-compatible AES-GCM-HKDF streaming header and segments, associated data, size arithmetic, writer and reader rules, and the test-vector plan. Review 1 findings applied 2026-11-10.
tags: [specification, encryption, attachments, storage, tink, stream]
status: active
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-10 }
sources:
  - id: design
    resource: design_draft.md
    title: Attachment encryption design (File format, Master key, Storage layout, Limits)
  - id: tink-streaming
    resource: ../../../../tink-ref/src/main/java/com/google/crypto/tink/subtle/AesGcmHkdfStreaming.java
    title: Tink Java AesGcmHkdfStreaming (commit ccd4e6d, outside the repository)
  - id: tink-encrypting-stream
    resource: ../../../../tink-ref/src/main/java/com/google/crypto/tink/subtle/StreamingAeadEncryptingStream.java
    title: Tink Java StreamingAeadEncryptingStream (last-segment rule)
  - id: tink-seekable
    resource: ../../../../tink-ref/src/main/java/com/google/crypto/tink/subtle/StreamingAeadSeekableDecryptingChannel.java
    title: Tink Java StreamingAeadSeekableDecryptingChannel (size arithmetic, header position)
  - id: step1
    resource: ../../build/attachment-encryption.md
    title: Step 1 device measurements (MediaDataSource read pattern)
  - id: review1
    resource: format-review1-findings.md
    title: Review 1, merged findings of three instances (M1-M20)
---

# Purpose

Step 2 of the order of work in the design draft: the exact bytes of an
encrypted media file, written before any code so that two independent reviewers (review 1) can
check it. Everything here is the Tink Java AES-GCM-HKDF streaming AEAD
(`subtle/AesGcmHkdfStreaming`, Tink commit `ccd4e6d`), used with fixed parameters and a
non-zero `firstSegmentOffset`, plus a Tatrak prefix in the bytes that offset reserves and a fixed
encoding of the associated data. Statements about Tink were read from its source, file and
behaviour named in each place.

Notation: `||` is concatenation; `u8`, `u16be`, `u32be` are unsigned integers of 1, 2, 4 bytes,
big-endian; sizes are in bytes; `ceil` rounds up.

# Parameters (format version 1)

| Name | Value | Note |
|---|---|---|
| `C`, ciphertext segment size | 65 536 (64 KiB) | Tink `ciphertextSegmentSize` |
| `T`, GCM tag size | 16 | Tink `TAG_SIZE_IN_BYTES` |
| `K`, derived AES key size | 32 (AES-256) | Tink `keySizeInBytes`; also the salt size |
| HKDF hash | SHA-256 (`HmacSha256`) | Tink `hkdfAlg` |
| Master key (Tink `ikm`) | 32 random bytes, one per database generation | design, "Master key" |
| `NP`, nonce prefix size | 7 | Tink `NONCE_PREFIX_IN_BYTES` |
| `H`, Tink header size | `1 + K + NP` = 40 | Tink `getHeaderLength()` |
| `F`, Tatrak prefix size (Tink `firstSegmentOffset`) | 25 | this page |
| `D`, offset of segment 0 (Tink `getCiphertextOffset()`) | `F + H` = 65 | |
| `P`, plaintext per segment | `C - T` = 65 520 | Tink `plaintextSegmentSize` |
| `P0`, plaintext in segment 0 | `P - D` = 65 455 | |
| Largest plaintext | 2^34 (16 GiB) | design, "Limits" |

These values are constants of version 1; none is stored in the file except `H` (header byte 0)
and the version. A different value is a different version. Every later version keeps `magic` and
`ver` at bytes 0-8 and puts its `ver` into its AD, so a reader can always tell versions apart.

# File layout

```
offset 0      8      9                25   26          58          65
       +------+------+-----------------+----+-----------+-----------+------------------ ...
       |magic | ver  | fileId          |hlen| salt      | nprefix   | segment 0 | segment 1 ...
       |8     | 1    | 16              | 1  | 32        | 7         |
       +------+------+-----------------+----+-----------+-----------+------------------ ...
       \______ Tatrak prefix (F=25) ___/\____ Tink header (H=40) ___/
```

* `magic` = ASCII `TATRKENC` = `54 41 54 52 4B 45 4E 43`.
* `ver` = `01`.
* `fileId` = 16 random bytes (`SecureRandom`), an input to the writer. For attachments it comes
  from whoever reserves the file name, which carries the same bytes as 32 lower-case hex digits
  (design, "Storage layout and naming"); a retry under a reserved name reuses it. For the other
  stores the writer draws it. No security argument depends on `fileId` being unique (see
  "Uniqueness").
* `hlen` = `28` (decimal 40). Tink writes `getHeaderLength()` here and its decrypter rejects any
  other value.
* `salt` = 32 random bytes, new for every file.
* `nprefix` = 7 random bytes, new for every file.
* Segments follow at offset `D` = 65 and are aligned to multiples of `C` **counted from the start
  of the file**, as Tink's seekable channel computes them
  (`StreamingAeadSeekableDecryptingChannel`: segment `i > 0` starts at `i * C`, segment 0 at
  `getCiphertextOffset()`):
  * segment 0 occupies `[65, min(C, S))`, its ciphertext is at most `C - D` = 65 471 bytes;
  * segment `i >= 1` occupies `[i*C, min((i+1)*C, S))`;
  * every segment except the last is full (`C` bytes, segment 0: `C - D`); the last one is
    `T + 1` to full size, or exactly `T` when it is segment 0 of an empty file (`L = 0`).
  * Segment `i` = `AES-256-GCM(subkey, nonce_i, plaintext_i, aad = empty)`, ciphertext followed
    by its 16-byte tag.

The Tatrak prefix is not part of Tink's stream: Tink's encrypting stream does not write the
`firstSegmentOffset` bytes (the caller does), and its seekable decrypter reads the header at
position `firstSegmentOffset` and ignores the bytes before it. Version and `fileId` are
authenticated through the associated data (below); the magic is checked literally.

# Key and nonces

* **Subkey.** `subkey = HKDF-SHA256(ikm = masterKey, salt = salt, info = AD, L = 32)` (RFC 5869,
  extract then expand), as Tink's `deriveKeySpec(salt, aad)`: Tink passes the associated data
  as HKDF `info` and gives GCM no associated data at all. Tatrak computes HKDF with
  BouncyCastle's `HKDFBytesGenerator(SHA256Digest)` initialised with
  `HKDFParameters(ikm, salt, info)` (extract and expand; not `skipExtractParameters`), already in
  the build through `bcmail-jdk18on`.
* **GCM provider.** The app puts its bundled Conscrypt (`conscrypt-android` 2.7.0,
  `build.gradle:102`) at provider position 1 (`Conversations.java:104`); if that fails, the
  platform's providers are used. Tink notes that Conscrypt's GCM decryption needs 16 spare bytes
  in the output buffer: the reader uses the array-returning `doFinal` or an output buffer of at
  least `C` bytes.
* **Nonce of segment `i`** (12 bytes): `nprefix || u32be(i) || last`, where `last` is `01` for the
  file's last segment and `00` otherwise (Tink `paramsForSegment`). `i` starts at 0.
* **Uniqueness.** One subkey encrypts one file: a fresh 256-bit salt per file from
  `SecureRandom` makes two files sharing a subkey negligibly likely (HKDF is not injective, so
  this is a bound under the usual HMAC assumptions, not an exact probability). Within a file,
  `(i, last)` never repeats, so no nonce repeats under a subkey. The random `nprefix` is an extra
  guard should two subkeys ever collide, and is required for Tink compatibility.

# Associated data (AD)

Tink takes the associated data as an opaque byte string; Tatrak fixes its encoding so that no
two different field tuples give the same bytes:

```
AD = u8(len(domain)) || domain
  || u8(version)
  || fileId                      (16 bytes)
  || u8(len(store)) || store
  || u16be(len(name)) || name
```

| Field | Value |
|---|---|
| `domain` | ASCII `tatrak-media` (12 bytes) |
| `version` | `01`, equal to `ver` in the prefix |
| `fileId` | the 16 bytes of the prefix; for attachments also equal to the ID in the file name |
| `store` | ASCII, one of `attachments`, `avatars`, `wallpapers`, `stickers` |
| `name` | empty for `attachments`; otherwise the file's path relative to its store root (table below): one or more components of `[0-9a-z._-]` separated by single `/`, no leading or trailing `/`, no empty, `.` or `..` component, at most 1 024 bytes |

Each length prefix bounds its field, so the encoding is injective. The `name` alphabet gives
every character one byte form, so no Unicode normalization or surrogate rule is needed (owner,
2026-11-10). The writer and the reader reject any other `name` or an over-long field, never
truncate or replace it; for the reader such a file is unavailable, as a missing one. A
human-readable name never goes into a file name; it is stored inside a file, as a sticker pack's
`name.txt`.

| Store | Store root (`name` is relative to it) | Expected identity | `name` example |
|---|---|---|---|
| `attachments` | `files/attachments/` | `fileId` from the file name must equal the header's | empty; the file name and its extension are not in the AD |
| `avatars` | `cache/avatars/` | store and `name`; `fileId` is read from the header | `3a7bd3e2360a3d29eea436fcfb7e44c735d117c4` (SHA-1 hex, `AvatarManager` writes only after checking it) |
| `wallpapers` | `files/wallpapers/<gen>/` (`<gen>/` not in `name`) | as avatars | `9f86d081884c7d65.jpg` (`ChatWallpaper.kt:173-175`) |
| `stickers` | `files/Stickers/<gen>/` (`<gen>/` not in `name`) | as avatars | `pack-ab12cd34ef567890/1a2b3c4d5e6f7081.webp`, `pack-ab12cd34ef567890/name.txt` (`StickerStore.kt:94-96,149-154`) |

Worked AD bytes, with `fileId` = `00 01 02 ... 0f`:

```
attachments, name "" (44 bytes):
0c 74 61 74 72 61 6b 2d 6d 65 64 69 61 01 00 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f
0b 61 74 74 61 63 68 6d 65 6e 74 73 00 00

stickers, name "pack-ab12/st.webp" (58 bytes):
0c 74 61 74 72 61 6b 2d 6d 65 64 69 61 01 00 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f
08 73 74 69 63 6b 65 72 73 00 11 70 61 63 6b 2d 61 62 31 32 2f 73 74 2e 77 65 62 70

stickers, name of 256 times "a" (297 bytes): the same first 39 bytes as above, then
01 00 followed by 256 bytes 61
```

The third example is the shortest name whose length uses the high byte of `u16be`.

The generation UUID is not in the AD: each generation has its own master key, so a file moved
under another generation's name fails its tags anyway. For attachments the file extension is
not in the AD: the MIME type comes from the extension (design, "MIME type"), so the cipher does
not detect a rename that changes only the extension; renaming a file otherwise only makes its row
point at a missing file. For the other stores the extension is part of `name` and is bound.

A changed `ver`, `fileId`, store or name gives a different subkey, so every tag fails; the
reader also compares `ver` and, for attachments, `fileId` with the expected values before
deriving the key, to report the cause.

What the AD does not bind:

* **A different genuine file under the same name.** For avatars, wallpapers and stickers the
  binding is (generation key, store, `name`); `fileId` comes from the file and only separates
  subkeys. Any genuine file written under the same store and name opens, whatever its content;
  this is accepted for v1. Names are derived from content (SHA-1 for avatars, 64-bit truncated
  SHA-256 for wallpapers and sticker images), which makes a different file under the same name
  unlikely but is not a cryptographic guarantee; `name.txt` changes content under one name by
  design, since its pack folder hash covers only the images.
* **Freshness.** An older complete file of the same store and name (and, for attachments, the
  same `fileId`) still opens: whole-file rollback is not detected, accepted for v1. Deletion
  shows as a missing file.
* Account, message and conversation identity.

# Size arithmetic

For a plaintext of `L` bytes (`0 <= L <= 2^34`):

* segments: `n = 1` if `L <= P0`, else `n = 1 + ceil((L - P0) / P)`;
* file size: `S = D + L + T * n`.

Equivalent form (Tink's `expectedCiphertextSize`): `full = (L + D) / P`, `rem = (L + D) mod P`,
`S = full * C + (rem > 0 ? rem + T : 0)`.

| `L` | `n` | `S` |
|---|---|---|
| 0 | 1 | 81 |
| 1 | 1 | 82 |
| 65 454 | 1 | 65 535 |
| 65 455 (`P0`) | 1 | 65 536 |
| 65 456 | 2 | 65 553 |
| 130 975 (`P0 + P`) | 2 | 131 072 |
| 130 976 | 3 | 131 089 |
| 2^34 | 262 209 | 17 184 064 593 |

The segment counter stays far below 2^32 (`u32be`), so it never wraps. Sizes and offsets exceed
2^31: compute them in 64-bit integers before any multiplication, and narrow to `int` only for a
length within one segment.

Reader, from the file size `S`:

* `full = S / C`, `rem = S mod C`; `n = full` if `rem == 0`, else `n = full + 1`;
* the last segment starts at `lastStart = (n == 1) ? D : (n - 1) * C` and is
  `S - lastStart` bytes long (segment 0 never includes the 65 prefix and header bytes: an 81-byte
  file has a 16-byte segment);
* plaintext length `L = S - D - T * n`. This value is not authenticated until reader rule 5
  passes (Tink says the same of its `size()`): code that only displays a size may use it; code
  that publishes or trusts a length opens the file first.

# Writer rules

1. Reject `L > 2^34` before writing when the length is known; a streaming writer stops with an
   error when the written plaintext passes 2^34, and the file is discarded.
2. Write the prefix and the header first (65 bytes); they are never rewritten.
3. Buffer plaintext; encrypt a segment as **not last** only when more plaintext than fits into it
   has arrived. On close, encrypt the buffered rest (possibly empty) as the **last** segment.
   This is Tink's rule (`StreamingAeadEncryptingStream.write`: `while (remaining >
   ptBuffer.remaining())`; `close()` encrypts the rest with `last = true`): a plaintext whose
   length is an exact multiple ends with a full last segment, and only `L = 0` produces an empty
   segment (81-byte file). `flush()` never encrypts a partial segment.
4. Each segment is encrypted once with a fresh `Cipher.init` and one `doFinal`. Conscrypt
   refuses `init(ENCRYPT_MODE)` with the same key and IV as the previous `init` of that `Cipher`
   object; the writer never does that, and deterministic test code must use a new `Cipher` per
   repeated encryption.
5. An aborted or failed write leaves a file that is deleted, never completed later; a retry starts
   over with a new salt and nonce prefix (design, "Writing"); the `fileId` of a reserved name may
   stay.
6. Salt and nonce prefix come only from the writer's random source. The known-answer test fixes
   them by injecting that source through an `internal` constructor used only from the test source
   set; no public API takes a salt or a nonce prefix (a fixed pair reused under the same AD is the
   one way to break the construction).
7. Reject a `name` outside the alphabet of "Associated data".

# Reader rules

Opening a file `f` with expected `store`, `name` and, for attachments, the `fileId` from its name.
A v1 reader always knows the file size; there is no reader for a pipe.

0. Reject a `name` outside the alphabet of "Associated data".
1. `S = size(f)`. Reject if `S < D + T` (81).
2. Read bytes `0, 65)`. Reject unless `magic` is `TATRKENC`, `ver` is `01`, `fileId` equals the
   expected `fileId` (where the name carries one), and `hlen` is `28`.
3. Compute `n` and the last segment's size from `S` (Size arithmetic). Reject if `n > 1` and
   `rem` is in `1..T` (a last segment shorter than a tag, or an empty last segment after a
   non-empty file). Tink rejects `rem < T` but accepts `rem == T`; Tink's writer never produces
   it, so Tatrak's reader accepts a subset of what Tink accepts: exactly the canonical writer
   output, one segment layout per plaintext length. This is not a security rule (headers are
   random, so a plaintext still has many valid ciphertexts). Reject if `L > 2^34`.
4. Derive the subkey from the generation's master key, `salt` and the AD.
5. Decrypt the last segment (`last = 01`) and keep it. Failure means truncation, extension, a
   wrong key, store, name or AD: the file is unavailable (CUJ 8).
6. Random access: plaintext offset `p` lies in segment 0 if `p < P0`, else in segment
   `1 + (p - P0) / P` at offset `(p - P0) mod P`. A segment is read whole, decrypted with one
   `doFinal` and its bytes are returned only after the tag passed; `nonce` uses `last = 01` only
   for segment `n - 1`. Any tag failure stops the read with an error; no partial plaintext of
   that segment is returned. A failed tag or a short read of a segment is an error, never end of
   data: only reaching `L` is end of data.
7. Never fall back to reading the file as plain.

Damage in a segment that is never read stays undetected (design, CUJ 8).

# Why 64 KiB segments

The design left the segment size to this page. 64 KiB is kept:

* Overhead is 16 bytes per 65 536, 0.024 %.
* The step 1 measurement showed `MediaPlayer` reading through a `MediaDataSource` in 2 KB
  chunks, about 900 `readAt` calls for a 253 KB MP4, many backward and overlapping
  ([step 1). With a cache of one or two verified segments
  (design plan item 3) we expect most reads to hit the cache (not measured: step 1 recorded
  read counts, not a hit rate); a miss costs one 64 KiB GCM decryption. Tink's 4 KiB template
  costs less per miss but 16 decryptions and 16 tags to read the same 64 KiB span; its 1 MiB
  template would decrypt 1 MiB for a 2 KB miss and hold 1 MiB per cached segment.
* Photos and avatars are usually below a few segments; a 50 KB avatar is one segment.

Not measured yet: decryption time per segment on a real ARM phone, the cache hit rate with one,
two or four cached segments (and whether the last segment kept by reader rule 5 counts among
them), last-segment verification at open, backward reads and concurrent `readAt` calls (the
speed budgets of the design are checked in step 7). If the budgets fail because of the segment size, a new version
changes it; version 1 files keep theirs.

# Test vectors

**Correction to the design draft (plan item 9).** The plan names a Python `generate.py` using
Tink. Python Tink (a wrapper of Tink C++ configured through keysets) has no way to set
`firstSegmentOffset`: the `AesGcmHkdfStreamingParams` key format carries segment size, derived
key size and HKDF hash only, and Tink Java's key-based constructor sets the offset to 0
(`AesGcmHkdfStreaming(AesGcmHkdfStreamingKey)`: "Current KeyTypeManager always sets this to 0").
With offset 0 the segment boundaries move, so such vectors cannot test this format. The
generator is therefore a single-file Java program in `tools/media-vectors/`, run as
`java -cp tink-1.24.0.jar Generate.java` like `SpecCheck.java` (Tink Java pinned by version and
SHA-256, never an app dependency). It uses the public constructor
`new AesGcmHkdfStreaming(ikm, "HmacSha256", 32, 65536, 25)`, writes the Tatrak prefix itself and
appends `newEncryptingStream(out, ad)`; decryption uses `newSeekableDecryptingChannel` on a
channel over the whole file. Tink's sequential `newDecryptingStream` reads the header at the
stream's current position (`StreamingAeadDecryptingStream.java:121-140`): skip the 25 prefix
bytes first.

Vectors are committed under `src/test/resources/media-vectors/` (made 2026-11-10, see "Vectors
made" below). Their JSON indexes (`kat.json`, `tink.json`) store, per vector, the fields
(`store`, `name`, `fileId`), the AD as hex, the master key, and the file's SHA-256. The app test builds the AD from the fields with the production encoder, compares it
with the hex, and only then decrypts, so a wrong encoder cannot pass by being fed ready bytes.
Plaintexts are a fixed pattern, not random bytes.

* **Tink → Tatrak:** for `attachments`: `L` = 0, 1, `P0 - 1`, `P0`, `P0 + 1`, `P0 + P - 1`,
  `P0 + P`, `P0 + P + 1`; for each other store one short vector with a real-shaped `name`. The
  app's unit tests decrypt all of them, by stream and by random access at every segment
  boundary.
* **Tatrak → Tink:** the same lengths made by `MediaCipher` in a unit test, written to
  `build/`; the generator decrypts them with Tink (a developer step, documented in its README).
* **Known answer:** made before any production code, for `L` = 0, `P0` and `P0 + P + 1` (segment
  0, a middle segment with a non-zero counter, the last flag), with fixed master key, salt,
  nonce prefix and pattern plaintext, computed independently (Python standard library and
  `cryptography`: HKDF and AES-GCM primitives, no Tink; the AD typed as literal hex), so a bug
  shared by Tink's use and ours cannot hide. Tatrak's writer with the same fixed salt and prefix
  (writer rule 6) must produce the same bytes. The SHA-256 of each file is recorded on this page.
* **Negative:** derived at test time from the positive vectors (the master key is in the index),
  not committed (owner, 2026-11-10): one changed byte in each region (magic, `ver`, `fileId`,
  `hlen`, salt, nonce prefix, inside segment 0, inside segment `n - 1`, a tag); two segments
  swapped; a segment duplicated; a segment removed; a segment appended; truncation at a segment
  boundary and inside a segment; `S` of 0, 25, 64, 65 and 80; `rem` in `1..16`, including a
  validly encrypted `rem == T` file; wrong master key; wrong `store`; wrong `name`; wrong
  expected `fileId`; a header `fileId` overwritten to match the expected one (rule 2 passes,
  rule 5 fails; for named stores the AD's `fileId` is taken from the changed header, so rule 5
  fails); a validly encrypted file with `ver = 02` in prefix and AD; the header of
  another file; a segment of another file at the same index; the last segment re-encrypted
  without the `last` flag (made with the test key). Each must be rejected at open (rules 0-5) or
  at the first read of the damaged segment (rule 6), and the test asserts which rule rejected
  it, not merely that something threw.
* **Accepted on purpose:** two independently encrypted files with different content and
  `fileId` under one named-store path (a sticker pack's `name.txt`) both open, including the
  older one; this pins down that v1 has no freshness, so a later change is a decision.
* **AD encoder:** a name of 256 bytes (length `01 00`), a name of 1 024 bytes accepted, 1 025
  rejected.
* **Writer:** exactly `P0` then close; `P0`, a 0-byte write, then close; 1-byte writes;
  `P0 + P`; `flush()` mid-segment changes nothing; a `name` outside the alphabet is rejected.
* **Size arithmetic** as pure functions, no files: `L = 2^34` and `2^34 + 1`, `S` = 17 184 064
  593 and one more, and every boundary of the table above.
* **Device:** the committed vectors run once on an emulator through the debug instrumentation,
  so Conscrypt's GCM and BouncyCastle's HKDF are exercised, not only the JDK's.

# Vectors made (2026-11-10)

`tools/media-vectors/kat.py` and `Generate.java` (commands in their README) wrote 15 vectors;
every plaintext is the pattern "byte `i` is `i mod 251`".

Known answers (no Tink, no Tatrak code; master key `00 01 ... 1f`; AD typed as literal hex in
the script):

| File | Store, name | `fileId` | salt | `nprefix` | `L` | `S` | SHA-256 of the file |
|---|---|---|---|---|---|---|---|
| `kat-attachments-0.bin` | `attachments`, empty | `f0 ... ff` | `40 ... 5f` | `a0 ... a6` | 0 | 81 | `f4decb1c24d6e2a1be42926cab587846c64d2dfee4369fc5324c72f58510f1f5` |
| `kat-stickers-65455.bin` | `stickers`, `pack-01/02.webp` | `e0 ... ef` | `60 ... 7f` | `b0 ... b6` | `P0` | 65 536 | `a56f10637587e9e2358d6570b5b4a410f029e056365e923cdcdede59f85efdb3` |
| `kat-wallpapers-130976.bin` | `wallpapers`, `9f86d081884c7d65.jpg` | `d0 ... df` | `80 ... 9f` | `c0 ... c6` | `P0 + P + 1` | 131 089 | `ddd3765b0a56db611ac8a4e95048cf62b191c5cb40b622c3a8a04908f870d8c7` |

Tink vectors (`tink.json`): `attachments` with `L` = 0, 1, `P0 - 1`, `P0`, `P0 + 1`,
`P0 + P - 1`, `P0 + P`, `P0 + P + 1`; `avatars`, `wallpapers` and `stickers` with `L` = 1 000;
a sticker pack's `name.txt` with `L` = 9. Their key, salts and ids are random.

Checked both ways without any Tatrak code: Tink decrypts all 15, the known answers included
(`Generate.java verify`), and the Python implementation decrypts all 15, Tink's included
(`kat.py check`); each tool builds the AD from the index fields with its own encoder and
compares it with the index hex, and each refuses a file under another store and name. A changed
ciphertext byte and a wrong name in the index are refused by both (tried by hand).

# Review 1 (2026-11-09 to 2026-11-10)

Three independent instances (Claude Opus 5.5 xhigh, Claude Fable 5.2 high, Codex gpt-6-astra
xhigh) reviewed this page at `fdcae29f3`; no Blocker. Their findings are merged in
format-review1-findings (M1-M20) and applied above. All three
answer the five questions below yes / no gap, with the limits now written into "Associated
data" (what the AD does not bind) and "Reader rules" (unread damage stays undetected).

## Questions for review 1

1. Is binding the version and `fileId` only through HKDF `info` (no GCM AD) enough, given that
   Tink does the same with its AD?
2. Is the stricter reader rule (reject `rem == T` when `n > 1`) safe with respect to Tink
   compatibility in both directions?
3. Is the AD encoding injective and complete for the four stores; should the generation UUID or
   the extension be bound after all?
4. Is a Java generator with `firstSegmentOffset = 25` the right replacement for the Python
   script, and is the independent known-answer vector sufficient against a shared mistake?
5. Anything in the layout that makes truncation, extension, reordering or splicing between two
   files of the same generation undetected at open or at read.

# Checked against Tink (2026-11-09, extended 2026-11-10)

`tools/media-vectors/SpecCheck.java` (run instructions in its README) is a preliminary
whole-buffer probe: it implements the format, reader rules 1-5 and the size arithmetic on plain
`javax.crypto`, independent of Tink, and runs it against Tink Java 1.24.0 (Maven Central,
SHA-256 `463bb0cd…a3e175` checked) with `new AesGcmHkdfStreaming(mk, "HmacSha256", 32, 65536, 25)`.
It does not implement rule 6's segment cache or the vectors of "Test vectors". Result on
2026-11-10 after review 2: 465 checks pass, exit 0; an exception other than a rejection stops
the run with exit 1. Eight deliberate mutants each fail it: Tink read through its unverified
`size()`, no `rem == T` rule, the pre-review last-segment wording, a little-endian `name`
length, a zero high byte of the `name` length, no `hlen` check, the last segment verified after
the others, and an unexpected exception inside a check. One AD helper feeds both sides, so the
AD encoding is checked only against the worked examples above.

* For `L` = 0, 1, `P0 - 1`, `P0`, `P0 + 1`, `P0 + P - 1`, `P0 + P`, `P0 + P + 1` and 1 000 000,
  and for all four stores with real-shaped names: Tink's file size equals the Size arithmetic
  formula; Tink reports header length 40 and ciphertext offset 65; byte 25 is `28`; our reader
  (given store and name, taking the AD's `fileId` from the header) decrypts Tink's files; Tink
  decrypts files made by our writer rules, read to its authenticated end of data; both sizes
  agree; a wrong store alone (named stores) or store and name (attachments), a wrong name, a
  changed header `fileId` (rule 5) and a wrong expected `fileId` (rule 2) each fail;
  truncation at a segment boundary fails in our reader and in Tink.
* Empty files with a changed tag, salt or nonce prefix, a wrong key or AD fail in both readers,
  a wrong `hlen` at rule 2 in ours and in Tink (before review 1 the Tink side of `L = 0` checked
  nothing).
* With segment 0 and the last segment both damaged, our reader reports the last one: rule 5
  runs at open, before other segments.
* Two different files under one sticker `name.txt` path both open (no freshness, accepted).
* Tink's random access agrees with the plaintext at each segment boundary of a four-segment
  file.
* Tink ignores the prefix bytes (a changed magic still decrypts in Tink), so the magic check is
  ours alone, as stated in File layout.
* An empty last segment after a full one (`rem == T`, 65 552 bytes for `L = P0`): Tink accepts
  it, our reader rejects it, as stated in Reader rule 3.
* Python Tink: `StreamingAead.new_encrypting_stream(ciphertext_destination, associated_data)`
  (`tink-py` `f9c4fc8`, `tink/streaming_aead/_streaming_aead.py:33`) takes no offset, and the key
  format `AesGcmHkdfStreamingParams` (`proto/aes_gcm_hkdf_streaming.proto`) has only segment size,
  derived key size and HKDF hash. The Java generator stays the plan.
* Nonce reuse: Conscrypt (`055868f`, `common/src/main/java/org/conscrypt/OpenSSLAeadCipher.java:183-188`)
  throws "When using AEAD key and IV must not be re-used" on an encrypt `init` with the previous
  key and IV, except GCM-SIV (`OpenSSLAeadCipherAES.java:162`); the JDK's provider (used by
  Robolectric unit tests) refuses it too (checked by the script). Writer rule 4 holds on both.
  Which Conscrypt release `055868f` belongs to was not checked; the app bundles 2.7.0.

# Not checked

* Not run on Android: HKDF through BouncyCastle's `HKDFBytesGenerator` and GCM through Conscrypt
  are not exercised yet (the script uses the JDK; Robolectric tests run with Conscrypt off); the
  device run of the vectors covers them.
* `MediaCipher` (step 3, 2026-11-10) reads all 15 vectors, reproduces the three known answers
  byte for byte, and Tink and `kat.py` decrypt eleven files it wrote; all on the JDK's providers.
  Not run on Android: Conscrypt's GCM and BouncyCastle's HKDF on a device.
* No test of a full 16 GiB file; the bound is checked as arithmetic only.
* The segment size choice rests on emulator read patterns, not on ARM timings or a measured
  cache hit rate.
