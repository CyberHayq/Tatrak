---
type: Reference
title: Local database encryption compared
description: Threat model, key-protection methods and ranking of local database (and attachment) encryption in Molly, SimpleX, monocles, Signal, Element X, Axichat, Tatrak and Conversations-based clients, with where Tatrak would rank after adding a passive and a password mode.
tags: [security, encryption, database, sqlcipher, keystore, threat-model, reference]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-02T12:00:00Z }
sources:
  - id: comparison-evidence
    resource: ../project/comparison-evidence.md
    title: Messenger comparison evidence (pinned clone revisions)
  - id: reference-sources
    resource: ../project/reference-sources.md
    title: Reference clone revisions
---

**Attachments (2026-11-08):** the owner chose to encrypt attachments (phase 3) before the
password mode. The design, and a source-checked comparison with Signal, SimpleX, Threema, Briar
and Wire that corrects the T7 column below, are in [attachment encryption](/protocol/attachment-encryption.md).

**Source reconciliation (2026-11-07):** review against fetched origin/main e60b094a3 records implemented requirements, obsolete proposal wording and remaining gaps. Earlier source and approval statements below are historical; no new runtime validation is supplied.


**Implementation update (2026-11-02):** the owner subsequently authorized implementation.
The working tree now implements passive SQLCipher encryption for history and UnifiedPush;
[current implementation, validation and limits](/build/local-db-encryption.md) supersede
this document's earlier proposal-only/current-plaintext wording. The design/review material
below is retained as decision history, not a claim that all release gates passed.


Static code reading of the read-only reference clones on 2026-11-02 (revisions in
reference sources); no app was installed or run. Each app is
judged in its strongest available setting unless stated otherwise. Paths are relative to each
clone.

# Threats

| # | Threat | Example |
|---|---|---|
| T1 | Device off or not unlocked since boot (BFU) | stolen phone that is powered off |
| T2 | App files leave the device | cloud backup, device transfer, `adb backup`, a file-read bug (FileProvider, path traversal) |
| T3 | Forensic extraction from a device unlocked at least once since boot (AFU) | exploit plus code execution on the device |
| T4 | Root or malware while the app is unlocked | spyware with root |
| T5 | Owner forced to unlock the phone | border, search |
| T6 | Offline password guessing on a copied database | GPU brute force |
| T7 | Photos, videos and documents on disk | decrypted downloads, thumbnails, caches |

Android file-based encryption already covers T1 for every app, including Tatrak. No app covers T4:
the key is in memory while the app is unlocked. The apps differ on T2, T3, T5, T6 and T7.

# Key-protection methods

1. **Random key wrapped by the Android Keystore.** The key is stored encrypted with a
   non-exportable Keystore key. Covers T2. Does not cover T3 or T5, because code running on the
   device can ask the Keystore to unwrap the key. The gain over plain FBE is protection when
   files leak (backups, transfer, file-read bugs).
2. **User passphrase that may stay unstored.** The passphrase is entered at every start and
   is not bound to hardware. Covers T3 and T5 while the app is locked. T6 depends on passphrase
   strength and the KDF, because a copied file can be attacked offline.
3. **Argon2id(password), then HMAC with a Keystore key** (StrongBox requested, otherwise
   whatever AndroidKeyStore gives; TEE is not proven without `KeyInfo`). Covers T6 for a copied
   file: without the hardware key nothing can be checked offline. Argon2id can still be
   computed off-device, and only the HMAC needs the device, so a compromised device remains an
   HMAC oracle. HMAC is not a hardware attempt limit. Covers T3/T5 only while the app is
   locked. An attacker who stays on the device can capture the password at the next entry, and
   coercion to enter the app password is not covered. Cost: the password is needed after
   every cold start or process death, not only after a reboot, and no messages arrive until
   then.

In every inspected app (Signal, SimpleX, Molly, monocles) the Keystore key has no
user-authentication or unlocked-device flag. Another app's process cannot use our Keystore
keys. "Code on the device" in T3 means code running as our app's UID or a compromised OS.

Code evidence:

- Signal, `app/.../crypto/DatabaseSecretProvider.java` and `core/util/.../KeyStoreHelper.java`:
  32 random bytes sealed with Keystore AES-GCM (method 1).
- Molly, `app/.../crypto/PassphraseBasedKdf.java`: Argon2id tuned by benchmark (target 3 s,
  at least 4 iterations and 16 MiB), and HmacSHA256 with a Keystore key when
  `keystore_initialized`. `MasterSecretUtil.java` wraps the master secret with it (method 3).
  The chain is traced:
  - `DatabaseSecretProvider` reads `pref_database_encrypted_secret` through
    `TextSecurePreferences`;
  - those preferences are `EncryptedPreferences` (`util/SecurePreferenceManager.java`), and its
    filter does not exempt that key;
  - the value decrypts only with the cached MasterSecret (`EncryptedPreferences.java:437,451`),
    and the Keystore seal is removed only after that.

  So: password → Argon2id → Keystore HMAC → MasterSecret → encrypted prefs → Keystore-sealed DB
  secret → SQLCipher. The attachment secret takes the same path. `ApplicationContext.onLock`
  stops jobs, clears the MasterSecret and runs `WipeMemoryService`, which ends the process.
  Whether every in-memory copy is actually wiped was not checked.
- monocles, `SecurePasswordStorage.kt`, `Argon2KeyDerivation.kt` and
  `persistance/DatabaseBackend.java` (`getKeyBytes`), `AppSettings.getDatabasePasswordChars`.
  It has three modes:
  - auto: a random 32-byte key plus Keystore HMAC (method 1);
  - stored password: Tink AES-256-GCM in DataStore, which is in effect method 1;
  - `require_password_on_startup`: the stored copy is cleared and the session password is
    entered at start (method 3, Argon2id 64 MiB, 3 passes, 4 lanes).

  The key goes to SQLCipher as a raw `x'..'` key. It covers the main and UnifiedPush
  databases.
- SimpleX, `apps/multiplatform/.../Cryptor.android.kt` and `.../helpers/DatabaseUtils.kt`: an
  initial random passphrase kept with Keystore AES-GCM (`storeDBPassphrase`). The user can set
  their own and not store it (method 2, `views/database/DatabaseEncryptionView.kt:292-297`).
  KDF: the core passes the passphrase via `PRAGMA key` (simplexmq `efaad8e`,
  `Agent/Store/SQLite.hs:120`). The pinned direct-sqlcipher `f814ee6` uses SQLCipher's
  PBKDF2-HMAC-SHA512 with 256,000 iterations. Checked over HTTPS by the Codex review; the
  built APK was not measured.
- Element X, `libraries/encrypted-db/.../RandomDatabaseSecretProvider.kt` and
  `crypto/EncryptedFile.kt`: a random secret in a file encrypted with Tink StreamingAEAD
  `AES256_GCM_HKDF_4KB` under a Keystore master key (method 1). It covers only that module, not
  the Rust SDK media cache.
- Axichat, `lib/src/storage/database.dart` (`PRAGMA key`) and `credential_store.dart`: a
  random secret in `flutter_secure_storage` (method 1). **Not checked:** plugin internals.

# Ranking

| # | App (strongest setting) | DB | Key | T2 | T3/T5 | T6 | T7 attachments |
|---|---|---|---|---|---|---|---|
| 1 | Molly, passphrase | SQLCipher | Argon2id + Keystore HMAC | ✅ | ✅ | ✅ | ✅ |
| 2 | SimpleX, passphrase not stored | SQLCipher | user passphrase | ✅ | ✅ | ⚠️ offline | ✅ per-file keys |
| 3 | monocles, password on startup | SQLCipher | Argon2id + Keystore HMAC | ✅ | ✅ | ✅ | ❌ decrypted files |
| 4 | Signal | SQLCipher | Keystore AES-GCM | ✅ | ❌ | – | ✅ |
| 5 | Element X | SQLCipher (module) | Tink + Keystore | ✅ | ❌ | – | ◐ plaintext preparation/export |
| 6 | Axichat | SQLCipher | secure storage | ✅ | ❌ | – | ❌ |
| 7 | **Tatrak before phase 1 (2026-11-02)** | plain SQLite | – | ◐ out of cloud backup, still in device transfer | ❌ | – | ❌ |
| 8 | Conversations, Cheogram, Prav | plain SQLite | – | ❌ | ❌ | – | ❌ |

The order is a judgement, not an audit result: it weighs attachments high. SimpleX is above
monocles only because of attachments; for the database alone, monocles is stronger thanks to
the hardware binding. Molly is first also because it locks and wipes memory, not only because
of its KDF. Element X is judged on its Android SQLCipher module only, so Signal > Element X >
Axichat does not compare equal coverage. "Attachments encrypted" means confidentiality only:
Signal/Molly use AES-CTR with no authentication (`core/util/.../
ModernEncryptingPartOutputStream.java`, "intentionally no longer includes authentication").
With default settings (no passphrase), Molly, SimpleX and monocles are at method 1, the same
level as Signal.

A Codex review (`build/codex-db-encryption-review.md`, 2026-11-02, not kept in git) raised
these points; the Molly chain, `onLock`, AES-CTR and the monocles key flags were re-checked in
code.

# Where Tatrak would rank with two modes

Proposed modes, following monocles:

- **Passive** (default, no password): a random 32-byte key wrapped by a Keystore AES-GCM key,
  as in Signal. Tatrak would also exclude the database from device transfer.
- **Password**: Argon2id(password) followed by HMAC with a Keystore key, entered at every start
  and never stored. A *stored* password adds nothing over passive mode.

| Tatrak setup | T2 | T3/T5 | T6 | T7 | Rank |
|---|---|---|---|---|---|
| Passive | ✅ | ❌ | – | ❌ | **6 = Axichat**, below Signal and Element X only because of attachments |
| Password | ✅ | ✅ | ✅ | ❌ | **3 = monocles**, level for the database, below SimpleX because of attachments |
| Password + attachment encryption (later) | ✅ | ✅ | ✅ | ✅ | **1–2**, next to Molly |

Without attachment encryption, a password mode protects messages, contacts and OMEMO keys, but
not received photos or files. Attachment encryption is a separate, larger task.

The ranks are projections of families, not promises. Matching monocles also needs a correct
key lifecycle: a lock timeout, manual lock, closing DB connections and clearing secrets.
Matching Molly needs its lock-and-wipe behaviour, not only the KDF and a start dialog.

Order: **passive mode first** while Tatrak stays a background messenger. It must come with a
crash-safe migration, errors that never delete data, a working backup/restore, and exclusion
from device transfer. The password mode follows. If the owner ranks AFU protection above
background delivery, the password mode moves into the first release.

# Passive modes compared (no user password)

All passive modes give the same kind of protection: T2 is covered, T3 and T5 are not, and the
key stays in memory for the whole process. They differ in robustness: where the wrapped key
lives, how the key reaches SQLCipher, what happens when the key is lost, and how plaintext is
migrated.

| App | DB key | Wrapping | Wrapped key stored in | Key to SQLCipher | Key lost / DB corrupt | Backup |
|---|---|---|---|---|---|---|
| Signal | 32 random bytes | Keystore AES-GCM, no flags, no StrongBox (`KeyStoreHelper.java:124-135`) | shared prefs (`PlainTextKeyValueStore`) | hex string with `kdf_iter = 1` (`SqlCipherDatabaseHook`) | `SqlCipherErrorHandler`: diagnostics, then crash; **never wipes** (SQLCipher's default handler deletes the file) | custom backup agent, no raw DB |
| Molly (no passphrase) | as Signal | as Signal; the MasterSecret is wrapped with an all-zero key (`MasterSecretUtil.getUnencryptedKey`), so no extra layer | encrypted prefs | as Signal | as Signal | custom backup agent |
| SimpleX | 32 random bytes as base64 text | Keystore AES-GCM, no flags (`Cryptor.android.kt`) | app prefs | text passphrase, so PBKDF2-SHA512 with 256k iterations runs on every open, which a random key does not need | error dialog; refuses to replace the random passphrase (throws) | `allowBackup=false` |
| monocles (auto) | 32 random bytes | Tink AES-256-GCM (AAD = package name), then HMAC-SHA256 with a second Keystore key, StrongBox if available, otherwise TEE | DataStore | raw `x'..'` key, no KDF | `EncryptionException`; crash-safe `.tmp`/`.bak`/sentinel migration from plaintext with `sqlcipher_export` (`DatabaseBackend.encryptLegacyPlaintextDatabase`, `recoverFromInterruptedMigration`) | `allowBackup=false` |
| Element X | 32 random bytes | Tink StreamingAEAD `AES256_GCM_HKDF_4KB`; keyset wrapped by a Keystore master key | own file per DB (session, cache, push history) | `ClientSecret.fromRawBytes` (format not traced) | `reset()` deletes the secret file (used by `ReplaceDatabaseKey`) | `allowBackup=false` |
| Axichat | 32 chars from 62 (`Random.secure`, about 190 bits) | `flutter_secure_storage` (internals not checked) | secure storage | text `PRAGMA key`, so SQLCipher's default KDF | not checked | `allowBackup=false` |

Takeaways:

- A second Keystore layer (monocles HMAC, Tink over Keystore) adds no security in passive mode:
  any code that runs as our UID can use both keys. It does add a dependency and another way to
  fail.
- Two points matter more: the database must never be wiped on a key or corruption error
  (Signal, SimpleX), and the plaintext migration must be crash-safe (monocles).
- A random key needs no KDF: use a raw key (monocles) or `kdf_iter = 1` (Signal). SimpleX's
  256k PBKDF2 on a random key only slows every open.

# Proposed Tatrak passive mode

What each step takes from the apps above:

1. **Key:** 32 bytes from `SecureRandom`, generated once.
2. **Wrapping:** one Keystore AES-256-GCM key, with no auth and no `setUnlockedDeviceRequired`.
   Either flag would stop background start-up while the screen is locked. No StrongBox, as in
   Signal: StrongBox adds nothing against a process running as our UID and has device quirks.
   The wrapped blob has a version byte and AAD `"tatrak-db-key-v1"`. No Tink.
3. **Storage:** a file in `noBackupFilesDir`, not shared prefs, because Tatrak's shared prefs go
   into cloud backup and device transfer.
4. **SQLCipher:** raw `x'..'` key, no KDF. Covers both databases, `history` and
   `unified-push-distributor`. WAL and journal files are encrypted by SQLCipher itself.
5. **Migration:** monocles' sequence. The wrapped key is written and synced *before* the swap.
   `sqlcipher_export` writes into `.tmp`, then `.tmp` is swapped with `.bak` using a sentinel,
   recovery runs on the next start, and the plaintext `.bak` is deleted only after the new file
   opens. Deletion on flash storage is not guaranteed to erase: the old blocks stay protected
   only by Android FBE.
6. **Errors never delete data:** a corruption handler like Signal's instead of SQLCipher's
   default wipe. A missing or unreadable Keystore key opens a "database key unavailable"
   screen that offers to restore from the encrypted export, and keeps the file.
7. **Backup:** remove `database` from `device-transfer` (`res/xml/data_extraction_rules.xml`).
   An encrypted DB without the Keystore key is useless on the new phone. Moving to a new phone
   then goes only through Tatrak's encrypted export (Argon2id/AES-256), which is a change in
   behaviour for users.
8. **Dependency:** only `net.zetetic:sqlcipher-android` (monocles uses 4.19.1).

Compared with the others, this is Signal's key handling and failure policy, plus monocles'
migration and raw key, minus Tink and the second Keystore layer. The protection level equals
every other passive mode (T2 only). It ranks above none of them on security; the aim is to be
the simplest of them and the safest for user data.

## Review of the passive design (Codex gpt-6-astra xhigh, 2026-11-02)

### Owner decision: unavailable key (2026-11-02)

Distinguish temporary Keystore errors from confirmed loss. Preserve the original databases,
sidecars and wrapped-key blob; never replace them automatically. Offer retry and recovery from
an existing encrypted export. If recovery is impossible, the owner approved an explicit
start-fresh action that keeps the old files separately. A new key belongs to a separate new
generation; it must never overwrite the key blob associated with the preserved databases.
Crash-safe generation activation and retention/cleanup details still need design and tests.

Compatibility with Conversations and monocles is now an explicit design topic. Local
SQLCipher encryption must preserve existing wire behaviour and legacy OMEMO identities.
Logical export/import must remain independent of the local storage key. Tatrak currently
accepts backup headers v2/v4 and offers a Conversations-compatible v2 export; matching
monocles' v4 header/KDF does not establish schema or PQ-key compatibility. The required
cross-client migration coverage remains to be agreed and verified.

Verdict: the key scheme holds; the design is not ready to implement until the blockers below
are solved. Full report: `build/codex-passive-db-review.md`, not kept in git. Claude re-checked
the code facts below.

Blockers:

1. **Migration needs its own protocol:** Tatrak needs a per-DB state machine with one shared key and checked rename/fsync results. The
   marker must live outside backed-up prefs. When the state is unclear, every candidate file is
   kept, and recovery also finds a leftover `.bak` without a marker.
2. **WAL, journal and connections.** Before `sqlcipher_export`: get exclusive access, recover a
   hot journal, checkpoint the WAL and close every handle. Old plaintext `-wal`, `-shm` and
   `-journal` files must not be attached to the new file or simply deleted, because unsaved
   commits would be lost. Arbitrary SQLite temp files are covered only with
   `temp_store=MEMORY`; check this in the AAR.
3. **The recovery screen cannot use the current importer.** `ImportBackupWorker` opens and reads
   the current DB first (`:145-148`, `:178-185`), and export needs a readable DB
   (`ExportBackupWorker.java:160-161`). Recovery has to:
   - keep the old DB pair, its sidecar files and the key blob aside;
   - import into a new encrypted staging DB under a new key;
   - verify it, then activate it.

   Never automatically create a replacement key while any encrypted DB or migration candidate
   exists; the explicit separate-generation start-fresh flow above is the exception. Tell apart: first
   start, restored prefs without a DB, a temporary Keystore error, a bad blob, and a key that is
   really lost.
4. **Background entry points and ANR.** `DatabaseBackend.getInstance` (`:362`) and
   `UnifiedPushDatabase.getInstance` have separate locks. The first access may come from
   `Conversations`, `SystemEventReceiver`, `UnifiedPushDistributor`, `XmppConnectionService` or
   the backup workers, sometimes on the main thread. Needed: one serialised bootstrap and key
   provider, migration on a worker thread, states READY / MIGRATING / KEY_UNAVAILABLE / CORRUPT,
   a bounded wait and a notification from the background. The native library must load before
   either helper opens.

Improvements:

- **Schema and full-text index.** `sqlcipher_export` does not copy `user_version` (now 57).
  Copy it explicitly, and never open the exported file at version 0, which would run
  `onCreate`. Check the FTS4 external-content index and its rowid triggers
  (`DatabaseBackend.java:315-334`) after export, and rebuild it if rowids change.
- **Key blob format.** Explicit layout: version, 12-byte IV, ciphertext, 128-bit tag. Strict
  sizes; unknown versions refused. Atomic write with file and directory fsync, then a test
  unwrap. Pass SQLCipher an ASCII `x'<64 hex>'` key and keep page HMAC on. StrongBox does add
  isolation against physical and side-channel attacks, but not against our own UID, so it stays
  optional. Tink is a convenience library, not an extra security boundary.
- **Complete port to SQLCipher classes,** including `utils/CursorUtils.java`. The
  non-wiping error handler also covers direct opens of the source, `.tmp` and recovery files.
  Take Signal's policy, not its class: its handler also rebuilds FTS. Never log SQL that
  contains the key (`utils/ExceptionHelper.java` writes stack traces to cache).
- **Scope of T2.** It protects the content of the covered DBs only. Still in plaintext:
  - media and attachment copies, avatars, camera and recording cache;
  - prefs, crash logs, the WorkManager DB;
  - old flash blocks.
- **The export is not a full snapshot.** It has listed tables only, no UnifiedPush and no
  files, no single read transaction, and it skips accounts without a password
  (`ExportBackupWorker.java:172-180,291-301`).
- **Measure on `benchmark`:** cold start, migration of a large DB, peak free space (original
  plus copy plus journal), search, import and bulk inserts. Check the AAR's ABI and 16 KiB page
  support.

Required fault tests before release:

- kill at every write, rename and cleanup step;
- ENOSPC, and rename or fsync failure;
- a WAL holding uncommitted data, and a hot journal;
- a corrupt `.tmp`, or one under the wrong key;
- a lost key blob or Keystore alias;
- the UnifiedPush DB opened before `history`, and concurrent first access;
- restored prefs without a DB;
- backups v2 and v4, and a corrupted final tag.

Real SQLCipher JNI and the Keystore need instrumented Android tests; Robolectric is not enough.

# What adding it to Tatrak takes

- **Dependency:** SQLCipher (`net.zetetic:sqlcipher-android`, the one monocles uses) is new and
  needs the owner's approval under "no new dependencies". Argon2id already exists through
  BouncyCastle (`ExportBackupWorker`). A plain Keystore AES-GCM key can replace Tink, so Tink
  is not needed.
- **Security-code approval:** key storage and `persistance/DatabaseBackend` lie outside
  `ui/`, so the owner has to agree ([security untouched](/decisions/security-untouched.md)).
- **Migration:** the existing plaintext database is moved with `sqlcipher_export` (monocles
  `DatabaseBackend.buildAttachSql`), with recovery if the process dies mid-way.
- **Losing the Keystore key loses the database.** A Keystore reset or a vendor bug leaves the
  user with only a restore from the encrypted export (Argon2id/AES-256 since 2026-11-01).
- **Product trade-off in password mode:** after a reboot no messages arrive until the password
  is entered. This needs a notification prompt like monocles.
- **Speed:** measure chat-list and chat-open timings on the `benchmark` build before and after.
