---
type: Project
title: Tatrak
description: Tatrak is a fork of the Android XMPP client Conversations that adds Telegram-like UI and smoothness and keeps Conversations' protocol and encryption code unchanged.
tags: [project, android, xmpp, conversations-fork, compose]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-28T20:29:14Z }
sources:
  - id: build-gradle
    resource: ../../build.gradle
    title: Gradle build script (applicationId, app name, Kotlin/Compose setup)
  - id: doap
    resource: ../../conversations.doap
    title: Upstream DOAP project description
  - id: upstream
    resource: https://codeberg.org/iNPUTmice/Conversations
    title: Upstream Conversations repository
  - id: plan
    resource: ~/.claude/plans/ticklish-weaving-boole.md
    title: Approved implementation plan (local file, outside the repository)
---

# Summary

| Field | Value |
|-------|-------|
| Display name | Tatrak (`appName` in `build.gradle`)[^build-gradle] |
| applicationId | `am.cyberhayq.chat` (was `eu.siacs.conversations`)[^build-gradle] |
| Gradle `namespace` / `R` package | `eu.siacs.conversations` (unchanged, keeps upstream code compiling)[^build-gradle] |
| Base | Conversations 2.21.0 (`versionName`), upstream remote `upstream` → `https://codeberg.org/iNPUTmice/Conversations.git`[^upstream] |
| Working branch | `main` |
| Languages | Java (upstream), Kotlin 2.4.21 + Jetpack Compose (BOM `2026.09.00`) for new UI[^build-gradle] |
| SDK levels | `minSdk` 26 (raised from upstream's 23 on 2026-11-09), `compileSdk` 37 |
| License | GPLv3 (inherited `LICENSE`) |

# Goal

- Telegram-like UI/UX and scrolling smoothness for the owner's circle of users.
- Remain a standard XMPP client usable with **any** XMPP server; no proprietary
  server components.
- Keep the end-to-end encryption and the rest of the security logic exactly as
  audited upstream. See [security stays untouched](/decisions/security-untouched.md).

# Key decisions

* [Base on Conversations, not monocles chat](/decisions/base-conversations.md)
* [Security code is not modified](/decisions/security-untouched.md)
* [Compose screens as fragments inside ConversationsActivity](/decisions/ui-bridge-fragments.md)
* [Upstream merge policy](/decisions/upstream-merge-policy.md)

# Why the old UI is not smooth

The legacy chat screen uses a `ListView` with `MessageAdapter extends ArrayAdapter<Message>`;
each service event leads to `notifyDataSetChanged()` and a full rebind, message text is
formatted in `getView` on the main thread, and images are laid out without a known size,
so rows jump while thumbnails load. The new UI addresses this with background-built
immutable snapshots ([SnapshotSource](/architecture/snapshot-source.md)) rendered by
Compose lazy lists with stable keys.

# Current state

Work is tracked in the [roadmap](/project/roadmap.md). As of 2026-10-29 none of the
fork's changes are committed: they are staged or modified in the working tree on top of
upstream commit `a4c980825`, because commits are signed with the owner's hardware key
and tooling cannot commit.

# Components

* [SnapshotSource](/architecture/snapshot-source.md) - background snapshot state layer
* [Chat list](/architecture/chat-list.md) - Compose replacement for the conversations overview
* [Chat screen](/architecture/chat-screen.md) - Compose chat screen (in progress)
* [XEP support](/protocol/xep-support.md) - protocol coverage vs. monocles chat
* [Build setup](/build/build-setup.md) - toolchain and commands

[^build-gradle]: Gradle build script
[^upstream]: Upstream Conversations repository
