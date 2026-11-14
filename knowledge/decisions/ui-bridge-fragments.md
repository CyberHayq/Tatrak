---
type: Decision
title: Compose screens as fragments inside ConversationsActivity
description: New Compose screens are hosted as XmppFragment subclasses inside the existing ConversationsActivity so navigation, two-pane layout and intents keep working.
tags: [decision, compose, fragments, navigation, architecture]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-28T20:29:14Z }
sources:
  - id: chat-list-fragment
    resource: ../../src/main/kotlin/eu/siacs/conversations/ui/ChatListFragment.kt
    title: ChatListFragment
  - id: xmpp-fragment
    resource: ../../src/main/java/eu/siacs/conversations/ui/XmppFragment.java
    title: XmppFragment (package-private refresh)
  - id: activity
    resource: ../../src/main/java/eu/siacs/conversations/ui/ConversationsActivity.java
    title: ConversationsActivity
  - id: plan
    resource: ~/.claude/plans/ticklish-weaving-boole.md
    title: Approved implementation plan (local file, outside the repository)
---

# Context

`ConversationsActivity` owns navigation between the conversation list and a conversation,
the two-pane layout on large screens (`layout-w1024dp-h640dp`), back-stack handling and
all incoming intents (share, open conversation, notifications). Rewriting that in a new
single-activity Compose app would touch a lot of upstream code.

# Decision

Keep `ConversationsActivity` and swap only the fragments it hosts:

- `main_fragment` hosts [`ChatListFragment`](/architecture/chat-list.md) instead of
  `ConversationsOverviewFragment`; the activity's `instanceof` checks were switched to
  `ChatListFragment`.[^activity]
- The conversation pane will host the Compose [`ChatFragment`](/architecture/chat-screen.md),
  with the legacy `ConversationFragment` kept as a "classic view" fallback.
- Each new fragment extends `XmppFragment` and wraps a `ComposeView`
  (`ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed`).[^chat-list-fragment]

# Why the `eu.siacs.conversations.ui` package

`XmppFragment.refresh()` is declared `abstract void refresh();` (package-private), and the
activity calls it on the fragment it hosts. Bridge fragments therefore live in
`eu.siacs.conversations.ui`, although their source file sits in the Kotlin source tree
(`src/main/kotlin/eu/siacs/conversations/ui/`).[^xmpp-fragment] All other new code lives in
`am.cyberhayq.chat.*`.

# `ConversationHost` (in progress)

A small Java interface `ConversationHost { @Nullable Conversation getConversation(); }`,
implemented by both `ConversationFragment` and `ChatFragment`, replaces the activity's
`instanceof ConversationFragment` checks used for navigation, archive, rotation and two-pane
handling. PGP and permission results remain bound to `ConversationFragment`.[^plan]
The interface file `src/main/java/eu/siacs/conversations/ui/ConversationHost.java` was added
to the working tree on 2026-10-29 while this concept was written; its wiring into
`ConversationsActivity` is not yet described here.

# Consequences

- Existing deep links, share targets and notifications keep working without changes.
- The activity is edge-to-edge (`WindowCompat.setDecorFitsSystemWindows(window, false)`),
  and `android:fitsSystemWindows` was removed from `main_fragment` in
  `activity_conversations.xml`; every hosted fragment must handle insets itself.
- The bridge keeps the upstream diff small; see the
  [upstream merge policy](/decisions/upstream-merge-policy.md).

[^activity]: ConversationsActivity
[^chat-list-fragment]: ChatListFragment
[^xmpp-fragment]: XmppFragment
[^plan]: Approved implementation plan
