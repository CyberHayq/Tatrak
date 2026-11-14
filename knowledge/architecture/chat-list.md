---
type: UI Screen
title: Chat list
description: Compose chat list hosted by ChatListFragment that replaces ConversationsOverviewFragment in ConversationsActivity, with Telegram-style archive, folder tabs, multi-select and swipe actions.
resource: ../../src/main/kotlin/eu/siacs/conversations/ui/ChatListFragment.kt
tags: [ui, compose, chat-list, telegram-ux]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-28T20:35:34Z }
sources:
  - id: fragment
    resource: ../../src/main/kotlin/eu/siacs/conversations/ui/ChatListFragment.kt
    title: ChatListFragment.kt
  - id: screen
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatListScreen.kt
    title: ChatListScreen.kt
  - id: state
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatListState.kt
    title: ChatListState.kt
  - id: test
    resource: ../../src/test/kotlin/am/cyberhayq/chat/ui/chatlist/ChatListScreenshotTest.kt
    title: ChatListScreenshotTest.kt
  - id: conversation
    resource: ../../src/main/java/eu/siacs/conversations/entities/Conversation.java
    title: Conversation entity (tatrak_archived_at attribute)
  - id: rows
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatRows.kt
    title: ChatRows.kt
  - id: plan
    resource: ~/.claude/plans/ticklish-weaving-boole.md
    title: Approved implementation plan (local file, outside the repository)
---

# Files

| File | Role |
|------|------|
| `src/main/kotlin/eu/siacs/conversations/ui/ChatListFragment.kt` | `XmppFragment` + `ChatListActions`; hosts a `ComposeView`; forwards `refresh()` / `onBackendConnected()` to `viewModel.invalidate(service)` |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatListViewModel.kt` | Owns the [SnapshotSource](/architecture/snapshot-source.md) |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatListState.kt` | `ChatListItem`, `AccountHeader`, `ChatListState`, `OutgoingStatus`, `ChatListSnapshot.build` |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatListScreen.kt` | `ChatListScreen` scaffold: top bars (normal / search / archive / selection), folder tabs + pager, drawer, mute and delete dialogs, snackbar |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatRows.kt` | `SwipeableChatRow`, `ChatRow`, `ArchiveRow`, avatars with online/selected marks, unread badges |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatSuggestions.kt` | First non-archived chat for the tablet second pane |
| `src/main/kotlin/am/cyberhayq/chat/ui/theme/SystemBars.kt` | `StatusBarIcons`: dark/light status bar icons per screen; the last screen out restores the classic screens' value (`Activities.setStatusAndNavigationBarColors`: dark icons in light mode), a late dispose does not overwrite a newer screen's choice |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatTime.kt` | Row timestamp formatting |
| `src/main/kotlin/am/cyberhayq/chat/ui/theme/Theme.kt` | `TatrakTheme` |

Why the fragment lives in `eu.siacs.conversations.ui`: see the
[UI bridge decision](/decisions/ui-bridge-fragments.md).

# Snapshot model

`ChatListItem` (all `@Immutable`) carries: `uuid`, `title`, `isGroup`, `preview`,
`previewSender` (first name in groups, "You" for own messages), `isDraft`,
`isServicePreview` (status/call messages, italic), `isTyping`, `timestamp`, `unreadCount`,
`isMuted`, `isPinned`, `outgoingStatus` (sending / sent / delivered / read / failed),
`isOnline`, `avatar`.[^state] `ChatListState` adds `loaded`, `account` (drawer header) and
`connecting` (title shows "Connecting…" while an enabled account is offline).

Avatars come in passes (`ChatListViewModel`): first only cached ones, with a missing avatar kept
from the previous snapshot (`keepAvatars`) so a row never flashes grey; then avatars are loaded
for the top 20 chats and published; then for the rest. `AvatarService`'s cache is bounded by
32 MiB instead of 256 entries (shared by all avatar sizes), which a long list exceeded on every
refresh. Only the folder page on screen is composed, and a row draws its swipe background only
while it is being swiped.

# UI (stage 1, implemented)

- `ModalNavigationDrawer` with account header and items: New chat, Contacts, Accounts, Scan QR code,
  Settings. The drawer width is 80% of the window width, capped at 320 dp.[^screen]
- Top bar with menu and search; search filters the list and is closed by Back. Message search
  retains previous results while a new term is pending; the no-results text appears only when
  completed search terms match the current query, including across the input debounce.
- `LazyColumn` with `key = uuid` and a fixed `contentType`, pencil FAB for a new chat,
  empty state ("No chats yet" / "Nothing found").
- Tapping a row calls `OnConversationSelected.onConversationSelected(conversation)` on the
  activity, so the existing navigation and two-pane logic open the chat.[^fragment]

# Stage 2 (implemented, not yet verified on a device)

- **Telegram-style archive.** `Conversation.Attributes` field `tatrak_archived_at` with
  `getTatrakArchivedAt()` / `setTatrakArchivedAt(Long)`.[^conversation] `ChatListState` splits
  `chats` and `archived`; archived chats stay active (group chats are not left). An "Archive"
  row heads the All tab and opens the archive mode of the same screen. A non-muted archived
  chat with an incoming message newer than the archive time returns to the main list
  (Telegram behaviour); `hasReceivedSince` walks back from the newest message, so a reply sent
  before the list refreshes does not hide it. `ChatListFragment.refresh()` clears the flag on
  the main thread before invalidating, so the snapshot build only reads entities.
  Deleting a chat clears the flag first, so a chat started again later is not archived.[^fragment]
- **Swipe left** on a row (`SwipeToDismissBox`, 35 % threshold) archives with an Undo
  snackbar; in the archive the same gesture unarchives. The swipe state is deliberately not
  saveable and is keyed by uuid and archive flag, so a row that changes side (also in search)
  starts settled.[^rows]
- **Folder tabs.** `ChatFolder` All / Personal / Groups / Unread in a `PrimaryScrollableTabRow`
  driving a `HorizontalPager`; each tab shows the number of unread, unmuted chats.[^screen]
- **Multi-select.** Long press toggles selection (check mark on the avatar); the top bar
  offers pin/unpin, mute (durations from `R.array.mute_options_durations`, as in the classic
  screen) / unmute, archive/unarchive, and in the overflow menu mark read
  (`sendReadMarker`, which honours the read-receipts setting, as when opening the chat) and delete (confirmation; calls
  upstream `archiveConversation`, which leaves group chats).[^fragment]
- **Edge-to-edge.** `ConversationsActivity` no longer fits system windows; the Compose top
  bar extends under the status bar, lists pad for the navigation bar, and the classic
  `ConversationFragment` pads itself for system bars and the keyboard.
- **Media in previews (2026-11-13).** When the last message is a downloaded photo, video or
  image sticker, a 20 dp thumbnail sits inline before the preview text (`InlineTextContent`, row
  height unchanged). It comes from `FileBackend.getPreviewForUri`, the media browser's path, which
  decrypts through the media store and keeps the bitmap in the app's in-memory cache; it is made in
  the snapshot build with the avatars' three passes (cached only, top 20 rows, the rest). A voice
  message shows its length ("audio, 0:12", the preview text is upstream's). The self-chat ("Saved
  messages") has a bookmark on the accent colour; the chat header, search and share picker still
  draw it with the letter avatar. monocles shows a static file-type icon instead of a thumbnail.
- **Colours** follow Telegram's default day theme (white header, `#229AF0` unread badges,
  `#46AA36` checks); night values are approximate.

# Tests

`ChatListScreenshotTest` (Robolectric, `sdk = [35]`, `w411dp-h891dp-xxhdpi`,
`@ConscryptMode(OFF)`) records `chat_list_light.png`, `chat_list_dark.png` and
`chat_list_empty.png`, `chat_list_selection.png` and `chat_list_archive.png` into `build/outputs/roborazzi/`.[^test] See
[build setup](/build/build-setup.md).

[^state]: ChatListState.kt
[^screen]: ChatListScreen.kt
[^fragment]: ChatListFragment.kt
[^plan]: Approved implementation plan
[^conversation]: Conversation entity
[^rows]: ChatRows.kt
[^test]: ChatListScreenshotTest.kt
