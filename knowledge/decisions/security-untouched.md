---
type: Decision
title: Security and encryption code stays untouched
description: The new UI only calls existing service APIs and opens existing security screens; encryption, key trust and transport security code of Conversations is not modified.
tags: [decision, security, omemo, encryption, policy]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-28T20:29:14Z }
sources:
  - id: plan
    resource: ~/.claude/plans/ticklish-weaving-boole.md
    title: Approved implementation plan (local file, outside the repository)
  - id: conversation-fragment
    resource: ../../src/main/java/eu/siacs/conversations/ui/ConversationFragment.java
    title: ConversationFragment (trustKeysIfNeeded)
  - id: conversation
    resource: ../../src/main/java/eu/siacs/conversations/entities/Conversation.java
    title: Conversation entity (Attributes, getNextEncryption)
---

# Context

Conversations' encryption and security logic passed a good audit. The owner's rule:
Telegram-level smoothness is the goal, but nothing security-related changes.

# Decision

Out of scope for modification (the new UI may only *call* these or *open* their screens):

- Encryption: OMEMO / OpenPGP, the `crypto/` package, key storage.
- Choice of message encryption: `Conversation.getNextEncryption()` and the pre-send logic
  of `ConversationFragment`, which the new chat screen reproduces by calling the same
  methods in the same order rather than reimplementing them.[^plan]
- Key trust: `TrustKeysActivity`, trust/verification calls on the axolotl service.
- Transport: TLS, SASL, DNSSEC, the `xmpp/` package and stanza parsers.

Any change outside `ui/`, `res/` and `entities/Conversation.Attributes` requires the
owner's agreement.[^plan]

# The one allowed near-security change

`ConversationFragment.trustKeysIfNeeded(int requestCode)` (declared at around line 1085)
decides whether the user must first trust new OMEMO keys before sending.[^conversation-fragment]
Its body is extracted **verbatim** into `ui/util/TrustKeysGate.java`
(`static @Nullable Intent intentIfTrustNeeded(Activity, Conversation)`), so the legacy
screen and the new [chat screen](/architecture/chat-screen.md) share one implementation.
`ConversationFragment.trustKeysIfNeeded` then only starts the returned intent. The diff
of this extraction is shown to the owner separately.[^plan]

Status on 2026-10-29: `src/main/java/eu/siacs/conversations/ui/util/TrustKeysGate.java`
(`public final class TrustKeysGate` with `intentIfTrustNeeded`) was being added to the working
tree while this concept was written; the verbatim equivalence to the original method body
has not been reviewed in this bundle.

# Security-visible behaviour in the new UI

- Untrusted OMEMO / invalid-session messages are flagged using the same entity flags as
  the legacy adapter; PGP, "not for this device" and decryption-failure states show the
  placeholder text from `UIHelper.getMessagePreview`.[^plan]
- If `getNextEncryption()` is PGP, the chat opens in the classic `ConversationFragment`;
  the PGP flow is not ported.[^plan]

# Verification

`git diff --stat` outside `ui/`, `res/` and the Kotlin source tree must show only
`Conversation.java` (Attributes field), `ConversationFragment.java` (insets, planned
`ConversationHost` implementation and trust extraction) and `ConversationsActivity.java`.

Current non-UI upstream edit: `Conversation.java` gains the `tatrak_archived_at` attribute
and its getter/setter, nothing else.[^conversation]

[^plan]: Approved implementation plan
[^conversation-fragment]: ConversationFragment
[^conversation]: Conversation entity


## Owner-authorized local-storage exception (2026-11-02)

The owner explicitly authorized passive SQLCipher/Keystore local DB encryption, migration,
recovery and staged backup compatibility work outside the original UI-only boundary. This
permits the required storage, startup, backup and historical-display changes and the pinned
SQLCipher/SQLite dependencies. It does not authorize changes to OMEMO/OpenPGP wire engines,
TLS/SASL, XMPP stanza parsing or outgoing PQ sessions. See the
[implementation runbook](/build/local-db-encryption.md) for tested and untested behavior.

## Owner-authorized attachment-encryption exception (2026-11-08)

For phase 3 ([attachment encryption](/protocol/attachment-encryption.md), design stage) the owner
allowed I/O-only changes in `http/`, `xmpp/jingle/`, `xmpp/manager/AvatarManager.java`,
`crypto/PgpEngine.java` and `crypto/PgpDecryptionService.java`: only where a file is opened for
reading or writing, or its length is read, so that these classes go through the encrypted
storage. Transport cryptography, OpenPGP and its OpenKeychain calls, keys, IVs, hashes (including
avatar hashes), PEP/vCard handling and stanza parsing stay as they are.

Addition (owner, 2026-11-09): in `xmpp/jingle/JingleFileTransferConnection.java` the receiver may
move `complete.set(hashes)` after the output stream is closed, so success is reported only after
the encrypted store published the file. This is a logic change,
not I/O only. Hash verification stays as in upstream.

Addition (owner, 2026-11-09, Stage 9 of the design review): `xmpp/manager/HttpUploadManager.java`
joins the I/O-only list. Only the file length (`:79`, `:118`) and the request body (`:129`) come
from the encrypted store; slot requests, authorisation and upload logic stay as they are. It
uploads stickers and the own avatar straight from their files.
