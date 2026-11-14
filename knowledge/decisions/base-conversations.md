---
type: Decision
title: Base the fork on Conversations, not monocles chat
description: Tatrak forks upstream Conversations instead of monocles chat because monocles adds three layers of divergence and a heavy UI that would be rewritten anyway.
tags: [decision, upstream, monocles, architecture]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-28T20:29:14Z }
sources:
  - id: conv-doap
    resource: ../../conversations.doap
    title: Conversations DOAP (supported XEPs)
  - id: monocles-doap
    resource: ../../../monocles-ref/monocles_chat.doap
    title: monocles chat DOAP (reference clone, outside the repository)
  - id: monocles-readme
    resource: ../../../monocles-ref/README.md
    title: monocles chat README (lineage and license)
  - id: conv-layout
    resource: ../../src/main/res/layout/fragment_conversation.xml
    title: Conversations chat screen layout
  - id: monocles-layout
    resource: ../../../monocles-ref/src/main/res/layout/fragment_conversation.xml
    title: monocles chat screen layout
---

# Context

Two candidate bases were considered: upstream
[Conversations](https://codeberg.org/iNPUTmice/Conversations) and monocles chat (a
reference clone lives next to this repository at `../monocles-ref`). GrapheneOS Messaging
(AOSP SMS app, Apache-2.0) is cloned read-only at `../grapheneos-messaging-ref` as a source of
UI ideas only.

# Decision

Fork **Conversations**.

# Rationale

- **Divergence.** monocles is Conversations → Cheogram → monocles, and its README also
  credits features taken from blabber.im.[^monocles-readme] Three layers of divergence
  make tracking upstream security and protocol fixes much harder. See the
  [upstream merge policy](/decisions/upstream-merge-policy.md).
- **UI weight.** The chat screen layout `fragment_conversation.xml` has 921 non-empty
  lines in monocles vs. 209 in Conversations.[^monocles-layout][^conv-layout] The UI is
  being rewritten in Compose anyway, so monocles' UI work would be discarded.
- **Security baseline.** Conversations' crypto passed a good audit and is kept as is
  (see [security stays untouched](/decisions/security-untouched.md)). monocles adds its
  own post-quantum OMEMO variant (`urn:monocles:omemo-pq:1`) that is not interoperable
  with OMEMO 0.8+.[^monocles-doap]
- **Protocol gap is small and portable.** monocles advertises 112 XEPs vs. 72 for
  Conversations.[^monocles-doap][^conv-doap] The features that matter for a
  Telegram-like UX (XEP-0461 replies, XEP-0424 retraction, XEP-0447 file sharing used for
  stickers) can be ported later; see [XEP support](/protocol/xep-support.md).

# Consequences

- Stage 5 of the [roadmap](/project/roadmap.md) ports selected protocol features from
  monocles.
- **Licensing caveat (open question).** monocles as a whole is distributed under
  AGPL-3.0-only because it bundles libsignal; code taken from Conversations, blabber.im
  and Cheogram, and contributions made while the repository was GPLv3, remain
  GPLv3.[^monocles-readme] Before porting, check the provenance of each piece: GPLv3 code
  can be ported directly, AGPLv3-only code would change the license terms of the
  combined work.

[^monocles-readme]: monocles chat README
[^monocles-layout]: monocles chat screen layout
[^conv-layout]: Conversations chat screen layout
[^monocles-doap]: monocles chat DOAP
[^conv-doap]: Conversations DOAP
