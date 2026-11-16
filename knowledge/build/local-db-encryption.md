---
type: Runbook
title: Encrypted local database implementation and checks
description: SQLCipher generations, recovery, portable backup boundaries and reproducible native checks.
tags: [database, encryption, sqlcipher, keystore, testing]
status: draft
generated: { by: codex, at: 2026-11-02 }
sources:
  - id: storage
    resource: ../../src/main/kotlin/am/cyberhayq/chat/storage/DatabaseStorage.kt
    title: Generation selection and key wrapping
  - id: native-checks
    resource: ../../src/debug/java/am/cyberhayq/chat/storage/StorageInstrumentation.java
    title: Debug-only native assertions
  - id: recovery-checks
    resource: ../../tools/test-storage/recovery_checks.py
    title: Reversible emulator fault checks
  - id: design
    resource: ../egm/local-db-encryption/design_draft.md
    title: Design history and accepted requirements
---

## Static security audit (2026-11-16)

Codex Security scan `6aa0d51f-25c9-4091-86a2-394e5a7c4b6d` reviewed the local DB feature at
`892458959`, including key wrapping, both helpers, selectors, migration/recovery, import/export
and OS backup. Independent reviews resumed on `gpt-6.1-sol` high after the previous Astra quota
interruption. The parent rechecked the reported issue and material architecture claims.
This was source review only: no build, tests, emulator, benchmark or fault injection ran.

One low-severity metadata finding: `ImportBackupWorker.writePreview` writes the JID, account UUID
and import summary as plaintext JSON beside the encrypted staging DB. Recovery activates that
stage and retains the preview. A private-file copy reveals these metadata; no password, DB key
or message-body disclosure was established. Normal merge attempts to delete its stage. Keeping
the preview in the encrypted DB would also protect the consent-waiting interval; deleting it
only after recovery would not. Fixed in part (2026-11-16): `DatabaseStorage.dropPreview` deletes
it once the generation is active; the consent-waiting interval is still plaintext.

Current production schema is **60**, minSdk **26**, and native backup format **1001** is supported.
The historical evidence below is not a current test result. `StorageInstrumentation.java` now
asserts the current `DatabaseBackend.DATABASE_VERSION` (a fixed 59 until 2026-11-16); the native
runner has not been run since. One-off monocles-format export can include media; the older blanket statement about
compatible exports below is too broad. Native backups carry generation media keys, including
when files are omitted; the owner-accepted cross-account consequence remains as documented in
the attachment design.

Unreproduced stage-disposal/activation and concurrent `ATTACH` risks, native/platform gaps and
offline reference-comparison limits are recorded in risks. The sealed
report and original reviewer JSON live outside the repository under
`~/.codex/state/plugins/codex-security/scans/tatrak/8924589593103c2857387be9ce73818baf76d8b6_20261115T212511Z_57sa3s20/`.
The scan initially could not save artifacts because `~/.codex` was group-writable; removing only
group write (0775 to 0755) satisfied the plugin's parent-directory check. No source fix or commit
was made.

## Fetched-main update (2026-11-07)

This runbook records November 2 checks. Fetched main e60b094a3 later adds optional media backup/import, orphan-stage cleanup and long-write gating. Native one-off exports can include media; recurring and compatible exports exclude it. Live attachments remain outside SQLCipher encryption. Recovery moves the staged media into `files/attachments` first and then activates the DB pair, moving the media back if activation fails (`ImportBackupWorker.java:540-557`; corrected 2026-11-08, earlier text said the opposite); interruption/failed-move behavior needs validation. See the dated review. Previous tests were not rerun for fetched main.

# Scope and lifecycle

Implemented in the working tree on 2026-11-02; this is development validation, not a
published release or hardware certification. SQLCipher Android 4.20.0 encrypts the complete
history database (schema 59) and the local UnifiedPush database (schema 1). Attachments,
voice files, thumbnails, other caches, shared preferences and WorkManager's own database
are outside this encryption boundary. OMEMO/OpenPGP wire encryption is unchanged.

Each generation is a UUID directory under `noBackupFilesDir/tatrak-databases`, with
`history.db`, `push.db`, a wrapped `key` and versioned activation metadata. One random
32-byte key opens both databases as an ASCII SQLCipher raw hex key. A generation-specific
Android Keystore AES-256-GCM key wraps it; blob layout is version byte 1, IV 12 bytes,
ciphertext 32 bytes and tag 16 bytes, with AAD `tatrak-db-key-v1`. No app password,
authentication binding or mandatory StrongBox is introduced. Keystore protection does
not prevent code executing as Tatrak from obtaining the database key.

Cold startup admits no normal database helpers until a complete keyed pair opens. A clean
install creates and activates an empty generation. The one-way migration from the plaintext
databases of builds before encryption (migration screen, optional pre-migration export,
`migrate`, legacy cleanup) was removed on 2026-11-16: the owner confirmed no such install exists.
A leftover plaintext `history` without an active selector counts as an ambiguous initial state and
leads to the recovery screen; it is never opened.

A checksummed `commit` intent precedes the atomic `active` pointer. Both refer to one whole
generation; startup completes a validated pending intent rather than guessing among
candidate folders. Deleting files does not guarantee secure flash
erasure. Abandoned encrypted staging directories can require later cleanup after a crash.

A bad/missing key or malformed selection blocks normal access; there is no automatic key
replacement or database deletion. Retry, encrypted logical restore, or an explicitly
confirmed fresh start are available. Fresh start retains the inaccessible old generation.
A recovery import moves its media into `files/attachments` before activation; once the `commit`
or `active` selector may name the stage (an unreadable selector counts), a failure leaves the
media there and the next start or "Retry" finishes activation forward
(`DatabaseStorage.isPastCommit`, fixed 2026-11-09). Before that point a failure moves the media
back into the stage.
No Direct Boot support is added; background access requires the first device unlock.

The schema 59 upgrade replaces the FTS4 external-content deletion trigger and rebuilds
its derived index.

# Portable backup boundary

Local Keystore keys and UnifiedPush registrations are not exported. OS cloud/device
transfer rules include shared preferences only; the encrypted generation is in Android's
no-backup directory. Tatrak encrypted logical export remains the portable recovery route.

The importer authenticates a v2/v4 backup through GCM EOF into an isolated encrypted pair.
It validates account ownership and foreign keys and shows unsupported fields/tables,
constraint skips and unsupported crypto state before consent. Cancel discards staging,
leaving active data untouched. Commit uses the owned authenticated stage, never rereads a
mutable external URI, and rechecks account absence in the destination transaction. An
existing bare JID is rejected; UUID/constraint collisions during final insertion roll back
rather than silently expanding accepted losses.

Monocles PQ message flags 8/9/10 are retained as historical states. Readable body text and
source fingerprint provenance survive a native Tatrak export/import round trip. Undecrypted
source states show an unavailable label. PQ fingerprints never become legacy trust and
pending PQ sends are frozen as historical messages. Monocles key/trust/account crypto state
is omitted with a preview warning; fresh keys require verification. PQ sessions and outgoing
PQ mode are not implemented. A Conversations-compatible export is refused when imported PQ
history exists, because that format cannot preserve these semantics; use native Tatrak format.
This does not establish full interoperability with running Conversations or monocles apps.

# Repeatable checks

Use only disposable configured emulators; preserve current app data before installation.
Do not uninstall, clear data, remove Keystore aliases, or resolve certificate mismatch by
wiping. The debug instrumentation has no release/benchmark manifest entry.

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./gradlew -q testConversationsFreeDebugUnitTest assembleConversationsFreeDebug assembleConversationsFreeBenchmark
mkdir -p build/egm-implementation
# Repeat for each test emulator; backup files contain private data and must remain ignored.
SERIAL=<serial>   # from `adb devices`
"$ANDROID_HOME/platform-tools/adb" -s $SERIAL shell am force-stop am.cyberhayq.chat
"$ANDROID_HOME/platform-tools/adb" -s $SERIAL exec-out run-as am.cyberhayq.chat tar -cf - . > build/egm-implementation/$SERIAL-before.tar
chmod 600 build/egm-implementation/$SERIAL-before.tar
# Verify the installed APK and new APK signing certificates match before install -r.
"$ANDROID_HOME/platform-tools/adb" -s $SERIAL install -r build/outputs/apk/conversationsFree/debug/*x86_64.apk
"$ANDROID_HOME/platform-tools/adb" -s $SERIAL shell am instrument -w -r -e mode imports am.cyberhayq.chat/am.cyberhayq.chat.storage.StorageInstrumentation
"$ANDROID_HOME/platform-tools/adb" -s $SERIAL shell am instrument -w -r -e mode store am.cyberhayq.chat/am.cyberhayq.chat.storage.StorageInstrumentation   # encrypted media store (phase 3)
python3 tools/test-storage/recovery_checks.py $SERIAL
```

Require both `PASS` and `INSTRUMENTATION_CODE: -1`; shell/adb exit zero alone does not prove
instrumentation success. Import
checks create disabled synthetic accounts, temporary private encrypted exports and owned
stages, then remove their fixtures and restore backup preferences. It never clears app data.
The fault script restores the original key blob and selector in a `finally` block. Do not
interrupt it while a fault is injected; after host/process failure, restore from the saved
pre-test material with care rather than starting fresh. The script persists the original
wrapped key and selector under `build/egm-implementation/<device>-original-*` with mode 0600
before injecting a fault; restore those matching files if the host dies before `finally`.

# Evidence and remaining limits

Full Gradle unit/Robolectric/Roborazzi suite: 252 tests, zero failures/errors/skips. Debug and
optimized benchmark assemblies succeeded. The test JVM prints an uncaught-exception-handler
NPE warning despite a successful task and zero failing XML cases; native assertions and UI
smoke checks are separate evidence. Chat/PQ and recovery screenshots were inspected.

Historical (before the migration was removed on 2026-11-16): API 31 and API 37 x86_64 native migration checks retained existing account/message counts,
opened both keyed schemas, rejected wrong keys without altering the disposable DB, checked
wrapped-key length and encrypted headers and confirmed plaintext cleanup. Native checks passed 50 assertions per emulator, including a legacy schema 57 fixture
with a stale FTS index: migration repaired only the encrypted copy, upgraded it to 59 and
retained the plaintext source bytes unchanged. Native v2/v4 import checks covered preview, omissions, cancellation, bad final GCM tags, changed external
source after preview, PQ provenance and existing-account rejection. Fault checks completed a
pending commit with a torn active pointer, preserved both databases on key authentication
failure and reopened after restoring the original key. Explicit logical recovery and empty fresh start also
retain the inaccessible original generation; their synthetic recovery generations are removed
by the test script after restoring the original selection. Logs/screenshots are ignored under
`build/egm-implementation/`; they are evidence artifacts, not repository sources.

Still unverified: physical-device Keystore behavior; ARM native execution; runtime on the
API 23 floor; a 16 KiB-page device; actual Keystore alias loss/invalidation; injected ENOSPC,
fsync/power-loss failure at every boundary; large-history performance regression measurements;
real third-party-app backup round trips; a full hot-WAL/hot-journal crash matrix; and background
message/call delivery across reboot/unlock. These are tracked in project risks.

Final foreground smoke on both configured emulators: existing tester accounts authenticated
to the preserved local Prosody server and displayed the newly received `Encrypted DB final
smoke 2026-11-02` message after migration/recovery tests. This is a cleartext XMPP test sender;
live OMEMO/OpenPGP encryption/decryption, audio/video calls and a real UnifiedPush connector's
registration/renewal were not rerun. These flows retain their protocol code, but are not
covered by the native database assertions.
