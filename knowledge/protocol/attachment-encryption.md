---
type: Design
title: Attachment encryption at rest (phase 3)
description: Owner decisions, the proposed per-file AES-GCM chunk format, the owner decisions taken before the EGM design.
tags: [security, encryption, attachments, sqlcipher, keystore, design, comparison]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-08T22:00:00Z }
sources:
  - id: db-encryption
    resource: ./database-encryption.md
    title: Local database encryption compared (phases 1 and 2)
  - id: db-runbook
    resource: ../build/local-db-encryption.md
    title: Phase 1 implementation (SQLCipher generations)
  - id: reference-sources
    resource: ../project/reference-sources.md
    title: Reference clone revisions and licences
  - id: reports
    resource: ../../build/attachment-encryption/
    title: Agent reports of 2026-11-08 (git-ignored; Codex opinion, Opus and Codex comparisons, briefs, session summary)
---

Nothing here is implemented. Comparisons are from reading the source snapshots listed in
reference sources; no app was built or run. Two independent
agents (Opus 5.5 high, Codex gpt-6.1-sol high) wrote the comparisons; the coordinator re-checked
the points where they disagreed and every finding below against the code.

# Owner decisions (2026-11-08)

- Order: phase 1 (SQLCipher, done) → **phase 3, attachments** → phase 2, password mode, later.
- File keys depend on the database key (they live inside SQLCipher), so a later password mode
  that wraps the database key also protects the files.
- No new dependencies; AGPL code (Signal, SimpleX, Threema, monocles) is not copied.
- **Always on**, no setting (like Signal, Threema and the phase 1 database). Existing private
  plaintext attachments are encrypted in the background, with recovery after a crash.
- **`http/` and `xmpp/jingle/`:** allowed to change only where a file is opened for reading or
  writing (a storage method instead of `FileInputStream`/`FileOutputStream`, plaintext length
  instead of the on-disk length). Transport cryptography, keys, IVs, parsing and hashes stay as
  they are. A download is decrypted into private staging, its tag verified, then published.
- **`minSdk` 26** (owner decision 2026-11-09, Android 8.0): other apps read through a proxy
  descriptor; the earlier API 23–25 shared-memory copy (`MemoryFile`) is dropped. Never a
  plaintext file on disk.
- **No migration** (owner, 2026-11-09): Tatrak has no users yet, so existing plain files are not
  converted and there is no database upgrade; the owner's test installs are reinstalled clean for
  phase 3 (2026-11-09). The migration designed
  in Stage 7 rounds 1-3 was removed at Stage 8.
- **Shared storage:** the "store in shared folders" setting (`use_shared_storage`) is removed with
  its stored value; new attachments go only to the encrypted private folder
  (`FileBackend.getStorageLocation` always internal). Files already in shared folders (old
  setting, imported Conversations/monocles backups) stay where they are, are read as plain files
  (no `tatrak_` name) and are never moved, encrypted or deleted. No notice on update (no users yet).
  The existing "Save" action (`FileBackend.saveInternalToExternal`, from the chat, media viewer
  and media browser) writes a decrypted copy to the same per-type folders as today, warns once on
  first use that the copy is unprotected; a taken name becomes `name (1).ext`.
- **Scope:** everything stored as media, as Signal does: attachments, cached avatars, photo
  wallpapers and sticker packs. Avatars are written in `xmpp/manager/AvatarManager.java`; the
  owner allowed I/O-only changes there too (open, write, length), avatar hashes and PEP/vCard
  handling unchanged. Avatars come last in the order of work (2026-11-09); avatar and sticker
  file names stay hashes (hidden names were dropped: sizes would still match them).
- **Keys in backups** (2026-11-09): Tatrak-format backups carry the master keys, so a recovery
  import after a lost Keystore key opens the old attachments, wallpapers and sticker packs still
  on disk.
  Accepted exposure: one account's backup (with its password and a folder copy) opens every
  account's media; phase 2 must wrap these keys with its password.
- **Jingle** (2026-11-09): the receiver reports success only after the encrypted store published
  the file (a logic change the owner allowed in `xmpp/jingle/`).
- **Temporary plaintext** (voice revised 2026-11-09): voice stays OGG/Opus into a pipe on API
  29+; on API 26-28, and after a failed pipe start, MPEG-4 into a short-lived file as today, with a
  one-time warning dialog and an open-lock mark during every such recording. The hold that opens
  the dialog records nothing; the user holds again, as after the microphone permission request
  (owner, 2026-11-09). Camera photos and transcoded videos keep a short-lived plain file
  (`cache/Camera`, `cache/tatrak_transcode/`), encrypted and deleted right after, leftovers deleted
  at the next start.
- **OpenPGP:** I/O-only change in `crypto/PgpEngine.java` and `crypto/PgpDecryptionService.java`
  (streams from and to the encrypted store); OpenPGP itself unchanged.
- **In-app video:** `MediaPlayer` on a `TextureView` with a `MediaDataSource` on every API level,
  replacing `VideoView` in the media viewer.
- **Speed budgets (strict):** on the `benchmark` build on a real ARM phone, sequential read at
  least 90% of plain, first video frame and seek at most +50 ms, photo opening at most +20 ms, no
  new dropped frames when scrolling a chat with images.
- **Storage layout:** encrypted files stay in the flat folder `files/attachments`, named
  `tatrak_<generationUUID>_<fileId>.<ext>` (option C of three, after reviews by Codex
  gpt-6.1-sol twice and Opus 5.5; details in the
  design draft).

# Proposed design

- **Keys** (owner decision 2026-11-09, replacing a key per file): one random 256-bit master key
  per database generation in a `tatrak_media_key` table of the history database; each file's
  subkey comes from HKDF with the salt of its header. No Keystore alias, no secret outside the
  database. Keys of other generations brought by a backup are kept beside it.
- **Format (owner decision 2026-11-08): STREAM, compatible with Tink's AES-GCM-HKDF streaming
  AEAD**, implemented in Tatrak on `javax.crypto` (no Tink in the app). The published STREAM
  construction (Hoang, Reyhanitabar, Rogaway, Vizár 2015), as used by Tink, Jetpack
  `EncryptedFile` and Element X: per-file subkey by HKDF-SHA256 from the master key and a random
  salt in the stream header, AES-256-GCM segments of 64 KiB with 128-bit tags, nonce = random
  prefix ‖ segment number ‖ "last segment" flag. The header is written first and never rewritten;
  truncation shows as a missing last-segment flag. A short Tatrak prefix (magic, version,
  `fileId`) goes in front, in the space Tink reserves for it (`firstSegmentOffset`), and the
  `fileId` is bound through the associated data. Test vectors are generated with the real Tink
  in a script under `tools/` (developer tool only) and committed; the app's unit tests read them.
  Rejected: the earlier own format with the length and a header tag written at the end (Codex
  opinion, section 3), Tink inside the app (new dependency, `protobuf-javalite` against the
  `protobuf-java 2.5.0` of `signal-protocol-java`, `SeekableByteChannel` only from API 24).
- **Rules:** decrypt each segment with `Cipher.doFinal` before returning any byte (never
  `CipherInputStream`); write append-only, never rewrite a segment or reuse a key (retry = new
  key and fileId); publish with temp file → fsync → rename → one database transaction; keep a
  bounded cache of 1–2 decrypted segments.
- **Reading:** `MediaDataSource` inside the app; other apps through a read-only provider using
  `StorageManager.openProxyFileDescriptor`. Its URIs name the file id, `content://<authority>/
  <fileId>.<ext>` plus `?uuid=<messageUuid>` when a message exists, never the generation id (a
  stable install identifier); unsent drafts use the same form (owner, 2026-11-09).
- **Stickers:** a sent sticker is re-encrypted from its pack into a new attachment, so deleting the
  pack keeps sent stickers (owner, 2026-11-09).
- **Reading rule:** a file named `tatrak_*` is always encrypted: it is decrypted with the active
  generation's master key and must pass its tags; a file of another generation is unavailable,
  never read as plain. Any other file in `files/attachments` is unavailable as well; files in
  shared folders (legacy shared-storage files, files of other apps from imported backups) are read
  as plain files.

# Gaps in the plan

The gaps both reports named before the design (temporary plaintext stages, API 23-25 video, the
file-key table in generations and import, the backup password) are resolved in the
design draft.

# Comparison

The source-checked comparison with Signal, SimpleX, Threema, Briar and Wire, and what to take
from each, is in [attachment encryption compared](./attachment-encryption-comparison.md).

# Next step

Design through the Elephant & Goldfish process before any code, as phase 1 was: `egm-draft`
from this page and the reports (in progress:
design draft), then `egm-review` and
`egm-resolve`.
