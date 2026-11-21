---
type: UI Screen
title: Chat list
description: Compose chat list hosted by ChatListFragment that replaces ConversationsOverviewFragment in ConversationsActivity, with Telegram-style archive, folder tabs, multi-select, swipe actions and the chat preview on avatar long press.
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
  - id: preview
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/chat/ChatPreview.kt
    title: ChatPreview.kt
  - id: tabs
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/chatlist/MainTabs.kt
    title: MainTabs.kt
  - id: flows
    resource: ../../src/main/java/eu/siacs/conversations/ui/ContactFlows.java
    title: ContactFlows.java (StartConversationActivity's contact flows, moved)
  - id: tabstest
    resource: ../../src/test/kotlin/am/cyberhayq/chat/ui/chatlist/MainTabsTest.kt
    title: MainTabsTest.kt
  - id: telegram
    resource: https://github.com/DrKLO/Telegram/blob/21f7d109da6ab3506c9e9577f98c6a38771ab335/TMessagesProj/src/main/java/org/telegram/ui/MainTabsActivity.java
    title: Telegram MainTabsActivity (commit 21f7d10)
  - id: filter
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/accounts/AccountFilter.kt
    title: AccountFilter.kt
  - id: switcher
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/chatlist/AccountSwitcher.kt
    title: AccountSwitcher.kt
  - id: filtertest
    resource: ../../src/test/kotlin/eu/siacs/conversations/ui/AccountFilterTest.kt
    title: AccountFilterTest.kt
  - id: plan
    resource: ~/.claude/plans/ticklish-weaving-boole.md
    title: Approved implementation plan (local file, outside the repository)
---

# Files

| File | Role |
|------|------|
| `src/main/kotlin/eu/siacs/conversations/ui/ChatListFragment.kt` | `XmppFragment` + `ChatListActions`; hosts a `ComposeView`; forwards `refresh()` / `onBackendConnected()` to `viewModel.invalidate(service)` |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatListViewModel.kt` | Owns the [SnapshotSource](snapshot-source.md) |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatListState.kt` | `ChatListItem`, `AccountHeader`, `ChatListState`, `OutgoingStatus`, `ChatListSnapshot.build` |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatListScreen.kt` | `ChatListScreen` scaffold: top bars (normal / search / archive / selection), folder tabs + pager, drawer, mute and delete dialogs, snackbar |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatRows.kt` | `SwipeableChatRow`, `ChatRow`, `ArchiveRow`, avatars with online/selected marks, unread badges |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/MainTabs.kt` | `MainTab`, `MainTabs`: Chats, Contacts, Calls and Profile as tabs of the list pane (see "Main tabs") |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatListDock.kt` | The docks (floating bar, Slate and Sea bar), `TabDock`, the floating top bar pieces |
| `src/main/java/eu/siacs/conversations/ui/ContactFlows.java` | `StartConversationActivity`'s contact flows, shared with the Contacts tab |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatSuggestions.kt` | First non-archived chat for the tablet second pane |
| `src/main/kotlin/am/cyberhayq/chat/ui/theme/SystemBars.kt` | `StatusBarIcons`: dark/light status bar icons per screen; the last screen out restores the classic screens' value (`Activities.setStatusAndNavigationBarColors`: dark icons in light mode), a late dispose does not overwrite a newer screen's choice |
| `src/main/kotlin/am/cyberhayq/chat/ui/chatlist/ChatTime.kt` | Row timestamp formatting |
| `src/main/kotlin/am/cyberhayq/chat/ui/theme/Theme.kt` | `TatrakTheme` |

Why the fragment lives in `eu.siacs.conversations.ui`: see the
[UI bridge decision](../decisions/ui-bridge-fragments.md).

# Snapshot model

`ChatListItem` (all `@Immutable`) carries: `uuid`, `title`, `isGroup`, `preview`,
`previewSender` (first name in groups, "You" for own messages), `isDraft`,
`isServicePreview` (status/call messages, italic), `isTyping`, `timestamp`, `unreadCount`,
`styledTitle` (2026-11-19: `IrregularUnicodeDetector` marks when the name is the bare address; the chat header, profile, share picker and new-chat addresses use the same `styledAddress`), `isMuted`, `ongoingCall`, `mentionsOnly` (one icon after the title, `ConversationAdapter`'s order: call, muted, mentions only), `isPinned`, `outgoingStatus` (sending / sent / delivered / read / failed),
`isOnline`, `avatar`.[^state] `ChatListState` adds `loaded`, `account` (drawer header) and
`connecting` (title shows "Connecting…" while an enabled account is offline).

Avatars come in passes (`ChatListViewModel`): first only cached ones, with a missing avatar kept
from the previous snapshot (`keepAvatars`) so a row never flashes grey; then avatars are loaded
for the top 20 chats and published; then for the rest. `AvatarService`'s cache is bounded by
32 MiB instead of 256 entries (shared by all avatar sizes), which a long list exceeded on every
refresh. Only the folder page on screen is composed, and a row draws its swipe background only
while it is being swiped.

# UI (stage 1, implemented)

- Side menu (`DrawerHost`) with account header and items: Contacts, Archive, Calls, Accounts, Scan QR
  code, Invite to server (2026-11-19; shown when `AccountPickerDialog.EasyInvite.hasAnyAccounts()`,
  opens `EasyOnboardingInviteActivity` as the classic overview menu did), Settings (new chat is the pencil FAB only). The day / night toggle in the header recreates
  the activity; the open state is saved (`rememberDrawerController`), so the menu stays open. The
  drawer width is 80% of the window width, capped at 320 dp.[^screen]
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
- **Chat preview (2026-11-20, public issue #2).** As Telegram's `DialogsActivity.onItemLongClick`
  (`showChatPreview` only when `isPointInsideAvatar`): a long press on a row's avatar opens the
  chat in a card (92 % × 75 %, a Compose `Dialog` over the dimmed list); a long press elsewhere on
  the row still selects it. While chats are selected the avatar has no gesture of its own (its long
  press toggles the row) and the row no preview action. Otherwise the avatar has a
  nested `combinedClickable` sharing the row's `MutableInteractionSource` (the row still ripples)
  and `clearAndSetSemantics {}`; TalkBack gets a "Preview chat" custom action on the row.[^rows]
  `ChatListFragment.previewChat` binds a second `ChatViewModel` (key `"preview"`) and shows
  `ChatScreen(preview = true)`: no composer, no header buttons, no message menu or selection,
  classic layout (`LocalFloatingBars provides false`). Its actions are `NoChatActions` with only
  the thumbnails wired (`loadMessageThumbnail`, shared with `ChatFragment`), and `MessageList`
  skips `markRead` in preview mode, so the preview sends no read marker and leaves the unread
  badge; `setOpenConversation` / `messagesLoaded` are never called. Its snapshots
  (`SnapshotParams.preview`) ask nothing of the contact: no XEP-0202 entity time, no XEP-0012 last
  activity. A tap on the card opens the chat as a row tap does, which marks it read as usual: seen
  at `PointerEventPass.Initial`, it is a single finger lifted within the touch slop before the
  long-press timeout and not cancelled, and is consumed so no bubble or button acts on it; a drag
  is left to the list and a long press does nothing. TalkBack's card action is "Open chat". An
  outside tap or Back closes the card, and so does the chat being closed meanwhile.
  Not done: Telegram's menu under the preview (mark read, mute, pin, delete) and the blur behind
  it.[^preview]
- **Colours** follow Telegram's default day theme (white header, `#229AF0` unread badges,
  `#46AA36` checks); night values are approximate.

# Main tabs (2026-11-21, not yet verified on a device)

Chats, Contacts, Calls and Profile are one level, as in Telegram's `MainTabsActivity`:[^telegram]
tabs of the list pane of `ConversationsActivity` (also in two panes, where the chat stays in the
second pane). Only the dock styles have tabs (floating bars in any style: Chats, Contacts,
Calls, Profile; Slate and Sea's classic bar: Chats, Calls, Contacts, Settings); Telegram's style
without floating bars has no dock and stays on Chats.[^tabs]

- **Switch.** A tap on another tab slides the old and the new page side by side a full width,
  the new one from the side its tab lies on, 320 ms `EaseOutQuint` (Telegram's
  `ViewPagerActivity.getManualScrollDuration`). Tap only: no swipe between tabs (it would
  compete with the folder pager, row swipes and the system back edge). During the switch the
  pages' own tab bars hide and `MainTabs` draws one dock over both, in place, blurring both
  pages (`backdropSource` on the pages' box), so only the selection moves. What else the dock
  carries on Chats (the floating search button, Slate's search pill and compose button, the
  floating compose button) belongs to the Chats page and slides with it; on the other tabs the
  floating bar keeps the search button's room empty, so the pill does not move.[^tabs]
- **State.** The tab lives in `ChatListFragment` (saved in its instance state); each page keeps
  its saveable state (scroll, search, call filter) in a `SaveableStateHolder` while another tab
  shows; a page not shown is not composed, so its first frame on return composes it.
- **Back** on a tab other than Chats returns to Chats, after the page's own Back (closing the
  Contacts search). A tap on the current tab scrolls it to the top (`ScrollToTopOn`).
- **Contacts** is `NewChatScreen` in contacts-only mode driven by `ContactsTab`
  (`NewChatCompose.kt`, sharing `NewChatModel` with the "New message" screen). The flows it
  needs from `StartConversationActivity` were moved unchanged into `ContactFlows` (owner's
  decision, 2026-11-21): the contact filter, "Add contact" (`EnterJidDialog`), the delete
  dialog, "hide offline" and the contact-list permission request with its result (forwarded by
  `ConversationsActivity.onRequestPermissionsResult`); the activity's methods delegate to it.
  Details, QR, block and call call the same `XmppActivity` methods the activity's menu calls.
  Opening a chat uses `switchToConversation`, which `ConversationsActivity` overrides to open in
  place; it now also overrides `switchToConversationDoNotAppend` (the "Add contact" result),
  which `XmppActivity` would answer by restarting the activity.[^flows]
- **Calls** is `CallsScreen` over `CallList` (moved out of `CallsActivity`, which uses it too).
- **Profile** is the settings home (`ProfileTab` over `SettingsHomeScreen`, the rows of
  `settingsSections`); a row opens its page above everything: `SettingsActivity` with
  `SettingsHomeFragment.EXTRA_PAGE` (the row's title; `SettingsHomeFragment.page` maps only the
  rows' titles, since the activity is exported).
- The drawer's Contacts, Calls and Settings still open their activities (deeper screens); a long
  press on the floating bar's Profile opens the account switcher (below).

| Old action of the screen | Where it is now |
|---|---|
| Contacts: open a contact's chat, search, "done" on one result | Contacts tab, `ContactsTab` (same calls) |
| Contacts: "Add contact" row | `ContactFlows.showCreateContactDialog` |
| Contacts menu: details, call, QR, block/unblock, delete | `switchToContactDetails`, `RtpCallStarter`, `showQrCode`, `BlockContactDialog.show`, `ContactFlows.deleteContact` |
| Contacts: "Hide offline", contact-list permission | `ContactFlows.setHideOfflineContacts`, `ContactFlows.askForContactsPermissions` (tab shown, fragment start) |
| Calls: open chat, call back, filter tabs | Calls tab over `CallList` |
| Settings: account header (manage accounts), each page, About | Profile tab; pages in `SettingsActivity` |

# Account switcher and account filter (2026-11-21, not yet verified on a device)

Tatrak has no current account: every enabled account is online and their chats share one list.
The owner's decision (2026-11-21) is an app-wide filter instead of Telegram's account switch.

- **Switcher.** A long press on the floating bar's Profile item opens Telegram's account selector
  (`MainTabsActivity.openAccountSelector` with `ItemOptions` and `accountView`):[^switcher] the
  screen behind dims (`0x66` over a light window, `0x33` over a dark one) and blurs (12 dp, only when
  "Blur under bars" is on, so API 31+), the Profile item is drawn again above the dim on a plate with
  28 dp corners and a soft shadow, and a 230 dp menu grows from above it: "Add account", a gap, then
  "All accounts" (only with more than one enabled account) and every enabled account with a 32 dp
  avatar (shrunk inside a 1.33 dp accent ring when selected), its name in up to two lines and its
  unread chats. Back or a tap outside closes it. Only the floating dock has the item: Slate and
  Sea's classic bar has Settings in that slot, and Telegram's style without floating bars has no
  dock; a Glass style with "Floating bars" turned off has no dock either (not the classic one).
  The overlay lives in `ChatListFragment`'s composition; the dock item reports its bounds through
  `LocalAccountSwitcher`.
- **One place.** `AccountFilter` holds the choice in the `tatrak_account_filter` preference (the
  account's uuid; persisted, renaming the key resets existing choices) and answers `selected`
  (null: all accounts; also when the stored account was removed or disabled, only one account
  is enabled, or the style has no floating dock, where nothing would show or clear the filter; the
  stored choice comes back with the dock) and `shows`.[^filter] Every screen asks it: the chat list snapshot (chats, archive,
  folder counters, the "Connecting…" title, the header and dock avatar), message search of all
  chats (`MessageSearchResults`, also `SearchActivity`'s global search), the tablet's suggested
  chat, `ContactFlows.filterContacts` (Contacts tab and New chat), New chat's groups and the
  accounts its dialogs offer (`StartConversationActivity.mActivatedAccounts`, the Contacts tab's
  "Add contact"), the Calls list (`callChats`, and in its query before the 300-call limit), the Profile tab and the settings home header
  (`settingsAccount`), and the share picker (`ShareCompose`). Notifications ignore it.
- **Unread per account** counts unmuted chats with unread messages outside the archive, as the
  folder tabs count; "All accounts" shows the sum.
- **Choosing** writes the preference and refreshes the list and every tab's data; "Add account"
  starts `EditAccountActivity`, as the Accounts screen's row (`ManageAccountActivity.menuAction`).
- **From outside.** `ConversationsActivity.processViewIntent` (notification, shortcut, `xmpp:` link,
  the other screens' `switchToConversation`) calls `AccountFilter.follow`: a filter set to another
  account switches to the opened chat's account; "All accounts" stays.
- monocles keeps a drawer account filter in the activity's instance state and passes it to New
  chat as an intent extra; Tatrak needs one persisted value read by every screen, so nothing of it
  fits (and it is AGPL).

# Tests

`ChatListScreenshotTest` (Robolectric, `sdk = [35]`, `w411dp-h891dp-xxhdpi`,
`@ConscryptMode(OFF)`) records `chat_list_light.png`, `chat_list_dark.png` and
`chat_list_empty.png`, `chat_list_selection.png` and `chat_list_archive.png` into `build/outputs/roborazzi/`.[^test]
`avatarLongPressPreviewsTheChat` covers the avatar / row long press split and the custom action;
`ChatScreenshotTest` records `chat_preview_light.png` / `chat_preview_dark.png` (whole screen with
the dialog) and checks that a drag scrolls without opening, a tap opens, and `markRead` is never
called.
`MainTabsTest` covers the selected tab, Back to Chats, a mid-switch frame with both pages
(`main_tabs_switching.png`), the direction, a tap on the current tab and a page's kept state, and
records every tab of both docks by day and night (`main_tabs_<floating|slate>_<tab>_<light|dark>.png`).[^tabstest]
`ChatListScreenshotTest` opens the account switcher with a long press and records
`account_switcher_light.png` and `account_switcher_dark.png`, checks that a pick reports the account
and closes it, and that one account shows no "All accounts". `AccountFilterTest` (fake service and
connection-less accounts) covers chats, archive and counters, contacts, calls, search, New chat's
accounts, the fallback when the account is removed or disabled, one enabled account, and the switch
on an outside open, and the pause without the floating dock.[^filtertest] See
[build setup](../build/build-setup.md).

[^state]: ChatListState.kt
[^screen]: ChatListScreen.kt
[^fragment]: ChatListFragment.kt
[^plan]: Approved implementation plan
[^conversation]: Conversation entity
[^rows]: ChatRows.kt
[^test]: ChatListScreenshotTest.kt
[^preview]: ChatPreview.kt
[^tabs]: MainTabs.kt
[^flows]: ContactFlows.java
[^tabstest]: MainTabsTest.kt
[^telegram]: Telegram MainTabsActivity
[^filter]: AccountFilter.kt
[^switcher]: AccountSwitcher.kt
[^filtertest]: AccountFilterTest.kt
