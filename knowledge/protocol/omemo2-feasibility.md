---
type: Reference
title: OMEMO 2 implementation feasibility
description: Source-based integration scope, compatibility policy and unverified implementation choices for adding OMEMO 2 alongside legacy OMEMO.
tags: [omemo, encryption, compatibility, planning]
status: draft
generated: { by: codex, at: 2026-11-02 }
sources:
  - id: omemo
    resource: https://xmpp.org/extensions/xep-0384.html
    title: XEP-0384 0.9.1
  - id: sce
    resource: https://xmpp.org/extensions/xep-0420.html
    title: Stanza Content Encryption
  - id: library
    resource: https://github.com/dino/libomemo-c
    title: libomemo-c README and GPL-3.0 licence declaration
  - id: current
    resource: ../../src/main/java/eu/siacs/conversations/crypto/axolotl/AxolotlService.java
    title: Current legacy OMEMO integration
---

**Source reconciliation (2026-11-07):** review against fetched origin/main e60b094a3 records implemented requirements, obsolete proposal wording and remaining gaps. Earlier source and approval statements below are historical; no new runtime validation is supplied.


# Finding and scope

Feasible, but a protocol integration project, not a namespace replacement. This is a
read-only assessment of application code at `833f022c0`; implementation is not authorized.
It would need explicit exceptions to the security-code and dependency rules.

Tatrak uses `signal-protocol-java:2.6.2`, legacy `eu.siacs.conversations.axolotl`,
`SessionCipher`/`SessionBuilder`, and AES-GCM payloads. `AxolotlService.encrypt` accepts
a message body or a remote-file URL. The local Conversations `a4c9808256db` uses the same legacy namespace and library; its
remote freshness was not checked. A follow-up corrected the initial, overly narrow monocles
inspection: monocles `e7d071a782da` includes a separate PQ OMEMO2 implementation as well as
legacy. A live `ls-remote` confirmed that this commit matches monocles master on 2026-11-02.
Its PQ profile is deliberately incompatible with standard OMEMO 2; see below.

The fetched XEP-0384 is version 0.9.1, Experimental. OMEMO 2 uses
`urn:xmpp:omemo:2`, new device/bundle PEP nodes, different ratchet wire encoding and key
derivation, Ed25519 identity representation, and AES-256-CBC plus HMAC for payloads.
SCE carries XML extensions inside the encrypted envelope. This does not encrypt the local
database, hide routing metadata, or add a post-quantum key agreement.

# Implementation choice

Recommended experiment: retain the Java legacy engine and evaluate a pinned
`dino/libomemo-c` revision behind a small JNI adapter for OMEMO 2. Its README documents
legacy and newer OMEMO ratchet support and GPL-3.0 licensing. It supplies crypto, not the
whole Android/XMPP integration. Android packaging, crypto-provider callbacks, storage,
threading, licence obligations and interoperability still need verification. No native
library was built or audited in this investigation.

Alternative: adapt/fork the Java engine using independent interoperability vectors. This
avoids JNI but transfers protocol maintenance and security review to Tatrak. Merely upgrading
Signal's library or changing XML does not implement the OMEMO 2 protocol. No suitable
drop-in Java OMEMO 2 library was established in this bounded search. `libomemo.js` documents
both profiles and interop vectors, but embedding a JavaScript runtime is not proposed.

# Compatibility policy proposed for the first implementation

Keep legacy receive and send support. Separate version-specific device lists, sessions,
prekeys and trust records; use an explicit protocol dimension rather than interpreting old
serialized sessions as new ones. Preserve old identities and message history.

Initially enable OMEMO 2 only in explicitly selected conversations whose intended devices,
including the sender's other devices, can receive it. If legacy is needed, show that policy;
do not silently omit devices, downgrade a pinned OMEMO 2 conversation, or send plaintext.
Temporary PEP failures must not be interpreted as permission to downgrade. Re-evaluate
device membership before sending, including new devices and group-member changes.

Do not initially send two copies of every message: mixed-protocol delivery would require a
separate design for message IDs, duplicate suppression, MAM, receipts and edits. A new
identity must not inherit verified trust merely because its JID/device ID matches the old
one. Identity reuse/conversion and cross-version proof of ownership need a separate decision.

# Integration map

| Area | Existing entry points | Required work / intended preservation |
|---|---|---|
| Protocol and device discovery | `AxolotlService`, `IqGenerator`, `PubSubManager`, `AxolotlManager`, XMPP model classes | New PEP handling, bundles, device lifecycle and namespace dispatch; retain legacy paths |
| Crypto and storage | `XmppAxolotlMessage`, `XmppAxolotlSession`, `SQLiteAxolotlStore`, `DatabaseBackend`, `Account` | Separate state, atomic ratchet persistence, bounded skipped-key storage, crash recovery, migration and rollback |
| Send/receive and offline delivery | `MessageGenerator`, `MessageParser`, `XmppConnectionService` | SCE encode/decode and validation before applying extensions; correct MAM/carbon replay and duplicate handling |
| Message identity and UI | `Message`, trust screens, `TrustKeysGate`, Compose snapshots | Track protocol and authenticated identity; show trust/failure accurately; preserve history search and rendering after decryption |
| Replies, reactions, edits, retractions, stickers | generator/parser and `ModerationManager` | Decide which extensions go inside SCE; keep server-needed routing outside; preserve authenticated retraction target and author checks |
| HTTP photos/files/voice | `HttpUploadConnection`, `HttpDownloadConnection`, `AxolotlService.encrypt` | Existing file-byte encryption can remain; deliver URL/key through the new envelope; audit all `ENCRYPTION_AXOLOTL` checks if adding an enum value |
| Calls and direct file transfer | `JingleRtpConnection`, OMEMO transport classes and Jingle transfer paths | RTP currently uses OMEMO to verify DTLS fingerprints. Keep the existing verified-call path initially and report unsupported combinations; message support alone does not supply new call verification |
| Backup and restore | `ExportBackupWorker`, `ImportBackupWorker` | Include/version new state and honour the include-OMEMO choice. Existing explicit table lists would omit new tables. Conversations-compatible export needs an explicit exclusion/warning policy |

OMEMO 2 can protect structured message content through SCE, but moving extensions inside
encryption requires deliberate handling: a server cannot act on extensions it cannot read.
It also does not make a new device able to decrypt all historical MAM messages. Existing
locally decrypted history should remain readable without re-encryption.

# Delivery gates

1. Pin a library and specification revision; pass external crypto vectors and two-way
   interoperability with an independently implemented OMEMO 2 peer before product wiring.
2. Add versioned storage and basic one-to-one text behind an experimental opt-in, preserving
   legacy. Check restart, interrupted persistence, prekey replenishment, duplicates and
   out-of-order delivery; reject modified ciphertext and malformed envelopes.
3. Cover own multiple devices, missing devices, reconnects, PEP errors, MAM/carbons and
   non-anonymous groups. Verify rollback and backup restoration before broader enablement.
4. Add and test files, replies, reactions, edits, retractions and stickers. Treat new call
   verification as its own interoperability gate. Retest legacy and OpenPGP paths.
5. Run the full unit/screenshot suite, protocol integration tests, and benchmark builds on
   real hardware. Measure startup/session establishment and many-device/group sends.

No duration estimate is validated yet. This is several integration stages; a crypto spike
should determine whether JNI is viable before estimating production delivery.

# Verification limits

Static source inspection and live primary-source documentation only. No OMEMO 2 session,
native build, server feature check, network capture, downgrade test, migration, device test
or performance measurement was executed. Existing full unit and debug assembly Gradle tasks
completed successfully during the assessment (incremental task results may be reused).
No application UI changed, so no screenshots are affected or visually reviewed.

# Follow-up: porting monocles PQ OMEMO2

The first inspection only checked legacy identifiers and missed the separate stack. Source
inspection of current monocles master establishes a concrete Android port candidate:

- `docs/proto-xep-omemo-pqxdh.md` (ProtoXEP 0.0.13) explicitly distinguishes
  `urn:monocles:omemo-pq:1` from standard `urn:xmpp:omemo:2`. It describes mandatory
  PQXDH, hybrid identities and a different payload scheme. These are protocol claims,
  not independently verified security guarantees.
- `XmppOmemo2Message.java`, `AxolotlService.java`, `SQLiteAxolotlStore.java` and
  `crypto/axolotl/legacy/` implement envelope handling and parallel legacy/PQ state.
  The store uses separate `omemo2_*` session/prekey tables and a distinct own identity.
- `settings.gradle` substitutes `org.signal:libsignal-android:0.94.1` with the source
  build in `deps/libsignal/java`. `.gitmodules` points to monocles/pq-omemo-2; the pinned
  gitlink is `10861bff671740174f4e99f7e30dc492fcf6ec8b`. This is a modified Rust/JNI
  libsignal, not simply the stock Maven artifact. `XmppOmemo2Message` imports its `PqAuth`.
- A lexical search finds Omemo2/OMEMO2/omemo2 references in 46 production Java files;
  this is evidence of integration breadth, not an exact port size. Tests include
  `XmppOmemo2MessageTest`, but no monocles tests were run here.
- The monocles repository LICENSE is AGPL-3.0. A port needs licence/provenance handling
  and review of the dependency's licence; no code was copied.

For compatibility with monocles PQ mode, porting this existing integration is a better
starting point than inventing a new implementation. Preserve Tatrak legacy identities and
sessions, adapt only the required protocol/storage/UI/backup paths, and retain opt-in
behaviour. Copying the whole AxolotlService would import unrelated fork policy changes.
For standard OMEMO 2 interoperability, this port does not solve the requirement; it would
be a third distinct protocol if both standard OMEMO 2 and legacy were also supported.

Unverified: native dependency source/security, full migration and call paths, actual
Tatrak-to-monocles exchange, independent audit coverage, ABI packaging and performance.
No runtime vulnerabilities were established by this follow-up.
