---
type: Reference
title: Attachment encryption compared (Signal, SimpleX, Threema, Briar, Wire)
description: Source-checked comparison of how five messengers store files at rest, and what Tatrak takes from each.
tags: [security, encryption, attachments, comparison]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-08T22:00:00Z }
sources:
  - id: design
    resource: ./attachment-encryption.md
    title: Attachment encryption at rest (phase 3)
  - id: reference-sources
    resource: ../project/reference-sources.md
    title: Reference clone revisions and licences
---

Moved out of [attachment encryption](./attachment-encryption.md) on 2026-11-13. It describes how
each app is designed, from its source.

# Comparison

| | Signal | SimpleX | Threema | Briar | Wire | Tatrak plan |
|---|---|---|---|---|---|---|
| Database | SQLCipher, key as passphrase with `kdf_iter=1` | SQLCipher, passphrase, PBKDF2-SHA512 256k (pinned `direct-sqlcipher`) | SQLCipher, hex master key, `kdf_iter=1` | H2 `CIPHER=AES` (mode not checked) | SQLCipher in release builds | SQLCipher raw key (done) |
| Password | none | own passphrase, may stay unstored | Argon2id 128 MiB, t=8, + Keystore | required: scrypt + Keystore HMAC | none | phase 2 |
| Files at rest | always encrypted | encrypted by default | always encrypted | no files; images ≤ 32 KiB inside the DB | plaintext | planned |
| File cipher | AES-256-CTR, IV 0 | XSalsa20-Poly1305 | AES-256-CBC, random IV | H2 cipher | — | AES-256-GCM, 64 KiB chunks |
| Integrity | none (by design) | one tag at the end | none | not established | none at rest | per-chunk and header tags |
| File key | HMAC(app secret, random 32 B) | own key + nonce | one master key for everything | DB key | — | own random key |
| Key location | app secret in prefs (Keystore) + random in DB | `files` table in DB | master key file (Keystore/passphrase) | `db.key` + `db.key.bak` | — | `tatrak_…` table in DB |
| Verified seek | seek, no verification | no | no | n/a | plain seek | yes |
| Temporary plaintext | almost none (encrypted Glide cache, blobs, recording via pipe) | recording, video, share | view/share temp files, cleaned by a timer | almost none | all files plain | not designed yet |
| Other apps | proxy FD on 26+, `MemoryFile` below | temp plaintext file | temp plaintext file | explicit save only | `FileProvider` | proxy FD (minSdk 26) |
| minSdk | 23 | 26 | 24 (24–25: key file without Keystore) | 21 | 26 | 26 (raised from 23, 2026-11-09) |
| Licence | AGPL, ideas only | AGPL, ideas only | AGPL, ideas only | GPL-3.0+, code reusable with attribution | GPL-3.0 | GPL-3.0 |

Evidence per cell: `build/attachment-encryption/compare-opus.md` and `compare-codex.md`.
Points checked by the coordinator: Signal `SqlCipherDatabaseHook.java:13-20`,
`KeyStoreHelper.java:124-130` (no `setKeySize`), `PartProvider.java:101-105,188-203`; Threema
`MasterKeyImpl.kt:30-49` (CBC through `CipherInputStream`), `MasterKeyConfig.kt:6` with
libthreema `memory_cost` in 1 KiB blocks; Wire/Kalium `AssetRepository.kt:580-597` (hash checked
before decryption, file stored plain); Briar `AndroidKeyStrengthener.java` (123 lines).

# What to take from each

- **Signal (idea):** encrypt every path, not only the final file: caches, drafts, recording
  through a pipe; proxy descriptor for other apps.
- **SimpleX (idea):** file keys in the encrypted database. Avoid its plaintext temp files.
- **Threema (idea):** key-file rotation "write new, then delete old alias"; wipe the key on lock
  (phase 2). Avoid one key for everything and CBC without MAC.
- **Briar (code, GPL):** `briar-android/.../AndroidKeyStrengthener.java` @ `fbfebc4` as a phase 2
  candidate (needs adaptation and the owner's agreement on key storage); two copies of the
  key file written in turn (`AccountManagerImpl.java:110-145`).
- **Wire (idea):** verify a download before decrypting and storing it.
