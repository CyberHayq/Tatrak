---
type: Reference
title: XEP support (Conversations vs. monocles chat)
description: Comparison of the XMPP extensions advertised by Conversations and monocles chat in their DOAP files, with Tatrak's plan for each.
tags: [protocol, xmpp, xep, reference, monocles]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-28T20:29:14Z }
sources:
  - id: conv-doap
    resource: ../../conversations.doap
    title: Conversations DOAP (xmpp:SupportedXep entries)
  - id: monocles-doap
    resource: ../../../monocles-ref/monocles_chat.doap
    title: monocles chat DOAP (reference clone, outside the repository)
  - id: monocles-parser
    resource: ../../../monocles-ref/src/main/java/eu/siacs/conversations/parser/MessageParser.java
    title: monocles MessageParser (inline BoB stickers)
---

# Summary

- Conversations (and therefore Tatrak today) advertises **72** XEPs.[^conv-doap]
- monocles chat advertises **112** XEPs.[^monocles-doap]
- 71 XEPs are in both; 41 only in monocles; 1 (XEP-0455) only in Conversations.
- Tatrak inherits everything Conversations supports and does not change protocol
  code (see [security stays untouched](../decisions/security-untouched.md)).
- Stage 5 of the [roadmap](../project/roadmap.md) ports three features from monocles:

| XEP | Feature | Why |
|-----|---------|-----|
| [XEP-0461](https://xmpp.org/extensions/xep-0461.html) | Message Replies | Telegram-style replies. Done: swipe-to-reply sends `<reply>` plus the quote as fallback ([chat screen](../architecture/chat-screen.md)). |
| [XEP-0424](https://xmpp.org/extensions/xep-0424.html) | Message Retraction | "Delete for everyone". Done. |
| [XEP-0447](https://xmpp.org/extensions/xep-0447.html) | Stateless File Sharing | Not needed for stickers after all: they travel as ordinary webp files (works under OMEMO); an SFS element could later mark them in unencrypted chats. |

monocles also handles inline stickers received via XEP-0231 Bits of Binary
(`MessageParser`)[^monocles-parser]; whether Tatrak needs this is open. Porting is subject
to the licensing caveat in [base on Conversations](../decisions/base-conversations.md).

Not to be ported: monocles' post-quantum OMEMO variant (`urn:monocles:omemo-pq:1`), which
is not interoperable with OMEMO 0.8+ and would change security code.

# Full table

Values are `status version — note` exactly as declared in each DOAP file; "—" means not
declared. Plan: **Port** (stage 5), Inherited (already in Conversations), Not planned yet.

| XEP | Conversations | monocles chat | Tatrak plan |
|-----|---------------|---------------|-----------|
| [XEP-0004](https://xmpp.org/extensions/xep-0004.html) | — | partial 2.13.2 — As part of XEP-0050 and XEP-0077 | Done: forms of ad-hoc commands (issue #8) |
| [XEP-0027](https://xmpp.org/extensions/xep-0027.html) | complete 1.4 | complete 1.4 | Inherited |
| [XEP-0030](https://xmpp.org/extensions/xep-0030.html) | complete 2.5rc3 | complete 2.5.0 | Inherited |
| [XEP-0045](https://xmpp.org/extensions/xep-0045.html) | complete 1.32.0 | complete 1.35.5 | Inherited |
| [XEP-0047](https://xmpp.org/extensions/xep-0047.html) | complete 2.0.1 | complete 2.0.1 | Inherited |
| [XEP-0048](https://xmpp.org/extensions/xep-0048.html) | complete 1.1 | complete 1.2 | Inherited |
| [XEP-0049](https://xmpp.org/extensions/xep-0049.html) | complete 1.2 | complete 1.2 | Inherited |
| [XEP-0050](https://xmpp.org/extensions/xep-0050.html) | — | complete 1.3.0 | Done: Commands button, list and form screen (issue #8) |
| [XEP-0054](https://xmpp.org/extensions/xep-0054.html) | partial 1.2 — Avatars only | partial 1.3.0 — Avatars only | Inherited |
| [XEP-0059](https://xmpp.org/extensions/xep-0059.html) | — | partial 1.0 — Used for paging MAM and other queries | Not planned yet |
| [XEP-0060](https://xmpp.org/extensions/xep-0060.html) | — | partial 1.30.0 — Used for PEP, avatars, bookmarks, feeds and stories | Not planned yet |
| [XEP-0065](https://xmpp.org/extensions/xep-0065.html) | complete 1.8.2 | complete 1.8.2 | Inherited |
| [XEP-0066](https://xmpp.org/extensions/xep-0066.html) | — | partial 1.6 — Sending and receiving file links | Not planned yet |
| [XEP-0071](https://xmpp.org/extensions/xep-0071.html) | — | partial 1.5.4 — Sending and receiving formatted messages | Not planned yet |
| [XEP-0077](https://xmpp.org/extensions/xep-0077.html) | — | partial 2.4 — Account registration and password change | Not planned yet |
| [XEP-0084](https://xmpp.org/extensions/xep-0084.html) | complete 1.1.3 | complete 1.1.5 | Inherited |
| [XEP-0085](https://xmpp.org/extensions/xep-0085.html) | complete 2.1 | complete 2.1 | Inherited |
| [XEP-0086](https://xmpp.org/extensions/xep-0086.html) | complete 1.0 | complete 1.0 | Inherited |
| [XEP-0092](https://xmpp.org/extensions/xep-0092.html) | complete 1.1 | complete 1.1 | Inherited |
| [XEP-0100](https://xmpp.org/extensions/xep-0100.html) | — | partial 1.0 — jabber:iq:gateway | Not planned yet |
| [XEP-0103](https://xmpp.org/extensions/xep-0103.html) | — | partial 0.4.1 — As file source in XEP-0385 and XEP-0447 | Not planned yet |
| [XEP-0115](https://xmpp.org/extensions/xep-0115.html) | complete 1.5.1 | complete 1.6.0 | Inherited |
| [XEP-0118](https://xmpp.org/extensions/xep-0118.html) | — | complete 1.3.0 | Not planned yet |
| [XEP-0122](https://xmpp.org/extensions/xep-0122.html) | — | partial 1.0.2 — When used with XEP-0050 | Done: keyboard by datatype, `<open/>` lists (with XEP-0050) |
| [XEP-0153](https://xmpp.org/extensions/xep-0153.html) | partial 1.1 — Read only. Publication via XEP-0398 | partial 1.1.1 — Read only. Publication via XEP-0398 | Inherited |
| [XEP-0163](https://xmpp.org/extensions/xep-0163.html) | complete 1.2.1 — Avatar, Nick, OMEMO | complete 1.2.2 — Avatar, Nick, OMEMO | Inherited |
| [XEP-0166](https://xmpp.org/extensions/xep-0166.html) | complete 1.1.2 — File transfer + A/V calls | complete 1.1.2 — File transfer + A/V calls | Inherited |
| [XEP-0167](https://xmpp.org/extensions/xep-0167.html) | complete 1.2.1 | complete 1.2.3 | Inherited |
| [XEP-0172](https://xmpp.org/extensions/xep-0172.html) | complete 1.1 — read only | complete 1.1 — read only | Inherited |
| [XEP-0176](https://xmpp.org/extensions/xep-0176.html) | complete 1.1 | complete 1.1.1 | Inherited |
| [XEP-0184](https://xmpp.org/extensions/xep-0184.html) | complete 1.4.0 | complete 1.4.0 | Inherited |
| [XEP-0191](https://xmpp.org/extensions/xep-0191.html) | complete 1.3 | complete 1.3 | Inherited |
| [XEP-0198](https://xmpp.org/extensions/xep-0198.html) | complete 1.6 | complete 1.6.3 | Inherited |
| [XEP-0199](https://xmpp.org/extensions/xep-0199.html) | complete 2.0.1 | complete 2.0.1 | Inherited |
| [XEP-0202](https://xmpp.org/extensions/xep-0202.html) | complete 2.0 | complete 2.0 — Not answered when connected over Tor or to an onion service | Inherited |
| [XEP-0203](https://xmpp.org/extensions/xep-0203.html) | — | complete 2.0 | Not planned yet |
| [XEP-0215](https://xmpp.org/extensions/xep-0215.html) | complete 0.7 | complete 1.0.0 | Inherited |
| [XEP-0221](https://xmpp.org/extensions/xep-0221.html) | — | partial 1.0 — When used with XEP-0050, images over HTTPS only | Not planned yet |
| [XEP-0223](https://xmpp.org/extensions/xep-0223.html) | complete 1.1 | complete 1.1.1 | Inherited |
| [XEP-0224](https://xmpp.org/extensions/xep-0224.html) | — | partial 1.0 — For MUC members | Not planned yet |
| [XEP-0231](https://xmpp.org/extensions/xep-0231.html) | — | partial 1.1 — Receiving data and answering requests for cached data | Not planned yet |
| [XEP-0234](https://xmpp.org/extensions/xep-0234.html) | complete 0.19.1 | complete 0.19.1 | Inherited |
| [XEP-0237](https://xmpp.org/extensions/xep-0237.html) | complete 1.3 | complete 1.3 | Inherited |
| [XEP-0245](https://xmpp.org/extensions/xep-0245.html) | complete 1.0 | complete 1.0 | Inherited |
| [XEP-0249](https://xmpp.org/extensions/xep-0249.html) | complete 1.2 | complete 1.2 | Inherited |
| [XEP-0260](https://xmpp.org/extensions/xep-0260.html) | complete 1.0.3 | complete 1.0.3 | Inherited |
| [XEP-0261](https://xmpp.org/extensions/xep-0261.html) | complete 1.0 | complete 1.0 | Inherited |
| [XEP-0264](https://xmpp.org/extensions/xep-0264.html) | — | partial 0.4.1 — Thumbnails in XEP-0385 and XEP-0447 file shares | Not planned yet |
| [XEP-0272](https://xmpp.org/extensions/xep-0272.html) | — | partial 0.2.0 — Group audio and video calls, sessions addressed to real JIDs; XEP-0482 call invites not supported | Not planned yet |
| [XEP-0277](https://xmpp.org/extensions/xep-0277.html) | — | partial 0.6.5 | Not planned yet |
| [XEP-0280](https://xmpp.org/extensions/xep-0280.html) | complete 0.13.1 | complete 1.0.1 | Inherited |
| [XEP-0292](https://xmpp.org/extensions/xep-0292.html) | — | partial 0.12.0 — Publishing and fetching vCard4 via PEP | Not planned yet |
| [XEP-0293](https://xmpp.org/extensions/xep-0293.html) | complete 1.0.1 | complete 1.0.2 | Inherited |
| [XEP-0294](https://xmpp.org/extensions/xep-0294.html) | complete 1.0 | complete 1.1.2 | Inherited |
| [XEP-0297](https://xmpp.org/extensions/xep-0297.html) | — | complete 1.0 | Not planned yet |
| [XEP-0300](https://xmpp.org/extensions/xep-0300.html) | — | partial 1.0.0 — For file shares | Not planned yet |
| [XEP-0308](https://xmpp.org/extensions/xep-0308.html) | complete 1.2.0 | complete 1.2.1 | Inherited |
| [XEP-0313](https://xmpp.org/extensions/xep-0313.html) | complete 0.6.3 | complete 1.1.3 | Inherited |
| [XEP-0317](https://xmpp.org/extensions/xep-0317.html) | partial 0.3.1 — Read only. A management interface is currently not provided. | partial 0.3.1 — Read only. A management interface is currently not provided. | Inherited |
| [XEP-0319](https://xmpp.org/extensions/xep-0319.html) | complete 1.0.2 — opt-in | complete 1.0.2 — opt-in | Inherited |
| [XEP-0320](https://xmpp.org/extensions/xep-0320.html) | complete 1.0.0 | complete 1.0.0 | Inherited |
| [XEP-0333](https://xmpp.org/extensions/xep-0333.html) | complete 0.3 | complete 1.0.0 | Inherited |
| [XEP-0334](https://xmpp.org/extensions/xep-0334.html) | — | complete 1.0.0 | Not planned yet |
| [XEP-0335](https://xmpp.org/extensions/xep-0335.html) | — | partial 0.1.1 — For WebXDC | Not planned yet |
| [XEP-0338](https://xmpp.org/extensions/xep-0338.html) | complete 1.0.0 | complete 1.0.0 | Inherited |
| [XEP-0339](https://xmpp.org/extensions/xep-0339.html) | complete 1.0.0 | complete 1.0.1 | Inherited |
| [XEP-0343](https://xmpp.org/extensions/xep-0343.html) | — | partial 0.3.1 — Used as Jingle file transfer transport with the namespace urn:xmpp:jingle:transports:webrtc-datachannel:1 (as in Conversations); the published version still uses :0 | Not planned yet |
| [XEP-0352](https://xmpp.org/extensions/xep-0352.html) | complete 0.3.0 | complete 1.0.0 | Inherited |
| [XEP-0353](https://xmpp.org/extensions/xep-0353.html) | complete 0.3.0 | complete 0.8.0 | Inherited |
| [XEP-0357](https://xmpp.org/extensions/xep-0357.html) | complete 0.4.0 — Only available in the version distributed over Google Play | complete 0.4.1 — Only available in the version distributed over Google Play | Inherited |
| [XEP-0359](https://xmpp.org/extensions/xep-0359.html) | — | complete 0.7.0 | Not planned yet |
| [XEP-0363](https://xmpp.org/extensions/xep-0363.html) | complete 1.0.0 | complete 1.1.0 — Optional upload purpose (1.2.0) is not sent | Inherited |
| [XEP-0364](https://xmpp.org/extensions/xep-0364.html) | — | partial 0.3.2 — Discovery of OTR support | Not planned yet |
| [XEP-0368](https://xmpp.org/extensions/xep-0368.html) | complete 1.1.0 | complete 1.1.0 | Inherited |
| [XEP-0372](https://xmpp.org/extensions/xep-0372.html) | — | partial 0.5.0 — For file shares and story replies | Not planned yet |
| [XEP-0377](https://xmpp.org/extensions/xep-0377.html) | complete 0.3.1 | complete 0.4.1 — Spam reports with a reference to the reported message; no processing opt-in | Inherited |
| [XEP-0379](https://xmpp.org/extensions/xep-0379.html) | — | partial 0.3.3 — Pre-authenticated subscriptions from invite links | Not planned yet |
| [XEP-0380](https://xmpp.org/extensions/xep-0380.html) | — | partial 0.4.0 — Sent with OMEMO, PQ OMEMO2, OpenPGP and OTR messages; not evaluated on receipt | Not planned yet |
| [XEP-0384](https://xmpp.org/extensions/xep-0384.html) | complete 0.3.0 | complete 0.3.0 — Legacy OMEMO (eu.siacs.conversations.axolotl). The post-quantum PQ OMEMO2 uses its own namespace urn:monocles:omemo-pq:1 and is not interoperable with OMEMO 0.8+ (urn:xmpp:omemo:2) | Inherited |
| [XEP-0385](https://xmpp.org/extensions/xep-0385.html) | — | partial 0.2.1 — Receiving, hashes, thumbnails, one HTTP source | Not planned yet |
| [XEP-0386](https://xmpp.org/extensions/xep-0386.html) | complete 0.4.0 | complete 1.1.0 | Inherited |
| [XEP-0388](https://xmpp.org/extensions/xep-0388.html) | complete 0.4.0 | complete 1.0.4 | Inherited |
| [XEP-0390](https://xmpp.org/extensions/xep-0390.html) | complete 0.3.2 | complete 0.3.2 | Inherited |
| [XEP-0391](https://xmpp.org/extensions/xep-0391.html) | complete 0.1.2 | complete 0.1.2 | Inherited |
| [XEP-0392](https://xmpp.org/extensions/xep-0392.html) | complete 0.6.0 | complete 1.0.1 | Inherited |
| [XEP-0393](https://xmpp.org/extensions/xep-0393.html) | complete 0.1.4 | complete 1.1.1 | Inherited |
| [XEP-0396](https://xmpp.org/extensions/xep-0396.html) | complete 0.1 | complete 0.2.0 | Inherited |
| [XEP-0398](https://xmpp.org/extensions/xep-0398.html) | complete 0.2.1 | complete 1.0.0 | Inherited |
| [XEP-0401](https://xmpp.org/extensions/xep-0401.html) | — | partial 0.6.0 — Requesting invitations via ad-hoc command. Registering with an invite token uses the server's preauth flow (XEP-0379), not XEP-0445 | Not planned yet |
| [XEP-0402](https://xmpp.org/extensions/xep-0402.html) | complete 1.1.3 | complete 1.2.0 | Inherited |
| [XEP-0410](https://xmpp.org/extensions/xep-0410.html) | complete 1.0.1 | complete 1.1.0 | Inherited |
| [XEP-0411](https://xmpp.org/extensions/xep-0411.html) | complete 0.2.0 | complete 1.1.0 | Inherited |
| [XEP-0420](https://xmpp.org/extensions/xep-0420.html) | — | partial 0.5.0 — Used by PQ OMEMO2 (urn:monocles:omemo-pq:1) | Not planned yet |
| [XEP-0421](https://xmpp.org/extensions/xep-0421.html) | complete 0.2.0 | complete 1.0.1 | Inherited |
| [XEP-0422](https://xmpp.org/extensions/xep-0422.html) | — | partial 0.2.0 — For message moderation | Not planned yet |
| [XEP-0424](https://xmpp.org/extensions/xep-0424.html) | — | complete 0.5.0 | **Done** (stage 5): written fresh on `ModerationManager`; send/receive, author checked (direction in 1:1, occupant-id in channels and PMs), hard delete like moderation |
| [XEP-0425](https://xmpp.org/extensions/xep-0425.html) | complete 0.3.0 | complete 0.3.0 — Falls back to the pre-0.3.0 request format for rooms that only advertise urn:xmpp:message-moderate:0 | Inherited |
| [XEP-0428](https://xmpp.org/extensions/xep-0428.html) | complete 0.2.1 | partial 0.2.1 — For XEP-0033 and XEP-0066 | Inherited |
| [XEP-0440](https://xmpp.org/extensions/xep-0440.html) | complete 0.4.0 | complete 1.0.0 | Inherited |
| [XEP-0444](https://xmpp.org/extensions/xep-0444.html) | complete 0.2.1 | partial 0.2.1 — Reaction restrictions (urn:xmpp:reactions:0:restrictions, added in 0.2.0) are not honoured | Inherited |
| [XEP-0446](https://xmpp.org/extensions/xep-0446.html) | — | complete 0.2.0 | Not planned yet |
| [XEP-0447](https://xmpp.org/extensions/xep-0447.html) | — | partial 0.3.1 — Sending and receiving, several files per message, URL data sources only. Sent unencrypted or inside the PQ OMEMO2 SCE envelope; never with body-only encryption, which would expose the file keys | Not used: stickers work without it (see [chat screen](../architecture/chat-screen.md)) |
| [XEP-0454](https://xmpp.org/extensions/xep-0454.html) | complete 0.1.0 | complete 0.1.0 | Inherited |
| [XEP-0455](https://xmpp.org/extensions/xep-0455.html) | complete 0.3.0 | — | Inherited |
| [XEP-0461](https://xmpp.org/extensions/xep-0461.html) | — | complete 0.2.1 | **Done** (stage 5): written fresh; `replyTo` column (DB 56) with id, author and XEP-0428 fallback range; the body keeps the quote (OMEMO encrypts it as is), hidden on display; parent looked up in the same conversation only |
| [XEP-0466](https://xmpp.org/extensions/xep-0466.html) | — | complete 0.1.0 | Not planned yet |
| [XEP-0472](https://xmpp.org/extensions/xep-0472.html) | — | partial 0.2.1 | Not planned yet |
| [XEP-0474](https://xmpp.org/extensions/xep-0474.html) | complete 0.4.0 | complete 0.5.0 | Inherited |
| [XEP-0484](https://xmpp.org/extensions/xep-0484.html) | complete 0.1.0 | complete 0.2.0 | Inherited |
| [XEP-0486](https://xmpp.org/extensions/xep-0486.html) | complete 0.1.0 | complete 0.1.0 | Inherited |
| [XEP-0490](https://xmpp.org/extensions/xep-0490.html) | complete 0.1.0 | complete 1.1.0 — Including server assisted synchronization, except in PQ OMEMO2 chats where the displayed marker is encrypted and is published over PubSub instead | Inherited |
| [XEP-0491](https://xmpp.org/extensions/xep-0491.html) | — | partial 0.1.3 — Realtime channels are supported in Cheogram's wire format (data inside the x element), which differs from the example added in 0.1.4 | Not planned yet |
| [XEP-0501](https://xmpp.org/extensions/xep-0501.html) | — | complete 0.2.0 | Not planned yet |

[^conv-doap]: Conversations DOAP
[^monocles-doap]: monocles chat DOAP
[^monocles-parser]: monocles MessageParser
