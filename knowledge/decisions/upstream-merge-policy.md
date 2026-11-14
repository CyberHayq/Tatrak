---
type: Decision
title: Upstream merge policy
description: New code goes into the Kotlin source tree and tatrak_-prefixed resources, and edits to upstream files are kept minimal, so merges from upstream Conversations stay clean.
tags: [decision, upstream, git, merge, conventions]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-28T20:29:14Z }
sources:
  - id: build-gradle
    resource: ../../build.gradle
    title: Gradle build script
  - id: worktree
    resource: git status of the repository working tree on 2026-10-29
    title: Staged and modified files observed when this concept was written
---

# Context

Tatrak must keep receiving upstream Conversations fixes, especially security and
protocol fixes (see [why Conversations](base-conversations.md)). Upstream is
the git remote `upstream` (`https://codeberg.org/iNPUTmice/Conversations.git`,
branch `upstream/master`); fork work happens on branch `main`.

# Rules

1. **New code in new places.** New Kotlin code goes under
   `src/main/kotlin/am/cyberhayq/chat/` (`ui/state`, `ui/chatlist`, `ui/theme`, and later
   `ui/chat`). Only bridge fragments that need package-private access live in
   `src/main/kotlin/eu/siacs/conversations/ui/` (see
   [UI bridge](ui-bridge-fragments.md)).
2. **Prefixed resources.** New resources use the `tatrak_` prefix in separate files:
   `values/tatrak_strings.xml`, `values-ru/tatrak_strings.xml`, `values/tatrak_dimens.xml`,
   `drawable/tatrak_ic_*.xml`. Upstream resource files are not edited for new strings.
3. **Minimal upstream edits.** Upstream Java/XML files are edited only where unavoidable,
   with a short explanatory comment (some mention "Tatrak"). Current edits: `build.gradle`,
   `Conversation.java`, `ConversationFragment.java`, `ConversationsActivity.java`, and the
   two `activity_conversations.xml` layouts.[^worktree]
4. **Namespace unchanged.** `android.namespace` stays `eu.siacs.conversations`, only
   `applicationId` changes to `am.cyberhayq.chat`, so upstream code and `R` references
   compile unchanged.[^build-gradle]
5. **Security code is never edited** to ease a merge or a feature; see
   [security stays untouched](security-untouched.md).
6. **Knowledge bundle** lives in `knowledge/`, a directory upstream does not have.

# Commits

Commits are signed with the owner's hardware key, so agents and scripts prepare changes
(staged or in the working tree) but never commit.

[^worktree]: Working tree state on 2026-10-29
[^build-gradle]: Gradle build script
