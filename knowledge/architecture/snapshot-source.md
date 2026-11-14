---
type: Architecture Component
title: SnapshotSource
description: SnapshotSource turns the XMPP service's coarse "something changed" callbacks into immutable UI snapshots that are built on a background thread with conflated invalidations.
resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/state/SnapshotSource.kt
tags: [architecture, state, kotlin, coroutines, compose, performance]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-28T20:29:14Z }
sources:
  - id: snapshot-source
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/state/SnapshotSource.kt
    title: SnapshotSource.kt
  - id: chat-list-vm
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatListViewModel.kt
    title: ChatListViewModel.kt (first user)
  - id: chat-list-state
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatListState.kt
    title: ChatListState.kt (snapshot models and builder)
---

# Problem

`XmppConnectionService` notifies the UI with coarse callbacks (for example
`onConversationUpdate`), and the entities in `eu.siacs.conversations.entities` are mutable
and shared with service threads. The legacy UI re-reads those entities on the main thread
and rebinds whole lists on each event.

# Design

```kotlin
class SnapshotSource<T>(
    scope: CoroutineScope,
    initial: T,
    private val build: suspend (service: XmppConnectionService, publish: (T) -> Unit) -> Unit,
)
```

- `state: StateFlow<T>` holds the latest immutable snapshot.[^snapshot-source]
- `invalidate(service)` sends into a `Channel(Channel.CONFLATED)`; a single coroutine on
  `Dispatchers.IO` (builds read avatars and thumbnails from disk) loops over the channel and calls `build`. Invalidations that
  arrive while a build runs collapse into one follow-up build.
- `build` may call `publish` more than once (for example a fast pass, then a complete pass).
- A `RuntimeException` during a build (entities changing underneath) is logged with
  `Config.LOGTAG` and retried once after 500 ms; a second failure in a row waits for the next
  invalidation, so a real bug cannot loop. `CancellationException`
  is rethrown.
- Snapshots are `@Immutable` data classes, so Compose (and DiffUtil, if used) only
  redraws what changed. UI code never keeps references to service entities, only to
  identifiers such as the conversation `uuid`.

# Usage: chat list

`ChatListViewModel` owns a `SnapshotSource(viewModelScope, ChatListState())`. Its build
lambda publishes a snapshot with cached avatars only first, then, if any avatar was
missing, a second snapshot that loads avatars from disk.[^chat-list-vm]
`ChatListSnapshot.build` calls `service.populateWithOrderedConversations(...)` and maps
each `Conversation` to a `ChatListItem`.[^chat-list-state] See
[chat list](chat-list.md).

# Usage: chat screen (planned)

The chat screen's `ChatViewModel` / `ChatSnapshot.build()` follows the same pattern,
with `conversation.populateWithMessages(copy)` as the thread-safe copy and message text
formatted in the background. See [chat screen](chat-screen.md).

# Invariants

- Builds run off the main thread; `publish` only assigns `MutableStateFlow.value`.
- At most one build runs at a time per source.
- Snapshot types must stay stable for Compose (immutable fields, `@Immutable`).

[^snapshot-source]: SnapshotSource.kt
[^chat-list-vm]: ChatListViewModel.kt
[^chat-list-state]: ChatListState.kt
