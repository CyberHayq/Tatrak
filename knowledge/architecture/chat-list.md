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

- Side menu (`DrawerHost`) with account header and items: Contacts, Archive, Calls, Saved messages, Accounts, Scan QR
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
  chat in a card (92 % × 75 % of the safe area, over the dimmed list); a long press elsewhere on
  the row still selects it. While chats are selected the avatar has no gesture of its own (its long
  press toggles the row) and the row no preview action. Otherwise the avatar has a
  nested `combinedClickable` sharing the row's `MutableInteractionSource` (the row still ripples)
  and `clearAndSetSemantics {}`; TalkBack gets a "Preview chat" custom action on the row.[^rows]
  `ChatListFragment.previewChat` binds a second `ChatViewModel` (key `"preview"`) and draws the
  card in the list's own `ComposeView`, in the same window as the chat that replaces it:
  Telegram's `0x2e` dim (about 18 %), the card centred in the safe area, appearing in 190 ms (scale
  0.7→1, overshoot 1.02) and closing in 150 ms (scale →0.9, `EASE_OUT_QUINT`), as `ActionBarLayout`;
  Back or an outside tap closes it (twice is one close). While it shows, the list under it is out
  of TalkBack's and the keyboard's reach (`PreviewCovered`): the card takes the focus (the search
  field stops taking text) and gives it back when it closes in place; Enter or the D-pad's centre
  opens it, and nothing inside it takes focus. TalkBack gets the card's "Open chat" and "Close
  preview" (dismiss) actions and reads each message as one node built from what the row draws
  (`previewSpeech`: trust warning, sender, quoted reply, the text or the attachment's kind and
  name, a sticker pack's host instead of its link, caption, edited, time or status text, delivery,
  reactions); the bubbles, links and reactions announce no actions there, and the preview has no
  "Load more" row. Inside,
  `ChatScreen(preview = true)`: no composer, no header buttons, no message menu or selection,
  classic layout (`LocalFloatingBars provides false`). Its actions are `NoChatActions` with only
  the thumbnails wired (`loadMessageThumbnail`, shared with `ChatFragment`), and `MessageList`
  skips `markRead` in preview mode, so the preview sends no read marker and leaves the unread
  badge; `setOpenConversation` / `messagesLoaded` are never called. Its snapshots
  (`SnapshotParams.preview`) ask nothing of the contact: no XEP-0202 entity time, no XEP-0012 last
  activity. A tap on the card (a single finger lifted within the touch slop before the long-press
  timeout, not cancelled, seen at `PointerEventPass.Initial` and consumed; a drag is left to the
  list) or TalkBack's "Open chat" **expands** it as Telegram's `expandPreviewFragment`: the card
  takes the whole window at once and pulses (scale 1→1.05→1, 200 ms, ease-both, haptic
  `KEYBOARD_TAP`), showing the full chat (header buttons, the user's bar style, the draft in the
  composer) but inert (every touch eaten, `markRead` still a no-op, Back ignored). When the pulse
  ends, `openFromPreview` sets a `PreviewHandOff` (uuid and the card list's `ScrollAnchor`) and
  calls `openChat` as a row tap does; `ChatFragment.reInit` takes it, so the chat opens at the
  card's place (`InitialScroll`'s `startAt`; a read chat keeps its loaded pages for this entry
  instead of being trimmed, so an older anchor is still there) with a zero-length enter
  (`chatAnimator`; the back-stack entry keeps its pop animations), waits for `listReady` as usual
  and then replaces the list view, card included, in one step; the next ordinary open of that chat
  trims it as upstream does. `PreviewHandOffs` (the list's
  side) runs no pending transactions itself (that would start the chat's postponed enter before
  its messages): the tap begins a request (`PreviewHandOff`), and its back-stack listener ends it
  and removes the card when any other fragment takes the container (a notification's chat during
  the pulse, even one Back has left again) or when the list is back after the card's chat (Back
  before or right after the swap, where the FragmentManager may bring the same list view back
  without `onDestroyView`). At the pulse end the open is posted after the work already committed,
  runs only if the request stands, and the chat fragment made for it records itself as the taker;
  `ConversationsActivity.openConversation` gives way (`ChatFragment.previewOpenCancelled`) when the
  pending transactions it runs first were a newer navigation that ended the request (a
  notification committed behind the posted open); if nothing took the container once the open's
  own transaction has run, the card goes. Read
  markers and `setOpenConversation` come from the real chat, as after a row tap. In two panes (`ConversationsActivity.isTabletView`) there is no preview:
  `ChatListScreen(canPreview = false)` leaves the avatar without a gesture of its own, so its long
  press selects the row (Telegram: `DialogsActivity.onItemLongClick` previews only on phones).
  Not done: Telegram's menu under the preview (mark read, mute, pin, delete) and the blur behind
  it.[^preview]
- **Colours** follow Telegram's default day theme (white header, `#229AF0` unread badges,
  `#46AA36` checks); night values are approximate.

# Main tabs (2026-11-21, not yet verified on a device)

Chats, Contacts, Calls and Profile are one level, as in Telegram's `MainTabsActivity`:[^telegram]
tabs of the list pane of `ConversationsActivity` (also in two panes, where the chat stays in the
second pane). Only the dock styles have tabs (floating bars in any style: Chats, Contacts,
Calls, Profile; Slate and Sea's classic bar: Chats, Calls, Contacts, Settings); Telegram's style
without floating bars has no dock and stays on Chats.[^tabs] Sizes in the floating style follow Telegram 13
(`21f7d10`): the folder tab pill is 36 dp (`DialogsActivity.FILTER_TABS_HEIGHT`) with 14 sp titles,
the tab bar 56 dp (`MAIN_TABS_HEIGHT`: a 48 dp tab, its 12 sp label from 28 dp down as in
`GlassTabView`), the header pieces 46 dp (`ActionBar`'s glass buttons); Slate and Sea keep their
own.

- **Switch.** A tap on another tab slides the old and the new page side by side a full width,
  the new one from the side its tab lies on, 320 ms `EaseOutQuint` (Telegram's
  `ViewPagerActivity.getManualScrollDuration`). Tap only: no swipe between tabs (it would
  compete with the folder pager, row swipes and the system back edge). During the switch the
  pages' own tab bars hide and `MainTabs` draws one dock over both, in place, blurring both
  pages (`backdropSource` on the pages' box), so only the selection moves. What else the dock
  carries on Chats (the floating search button, Slate's search pill and compose button, the
  floating compose button) belongs to the Chats page and slides with it; on the other tabs the
  floating bar keeps the search button's room empty, so the pill does not move.[^tabs]
- **State.** The tab lives in `ChatListFragment` and is saved in its instance state. The host
  reuses the restored list fragment in both pane layouts. A `SaveableStateHolder` keeps each
  page's scroll, search and call filter while another tab shows.
- **Archive from a chat.** The chat applies the local archive before closing. Its result tells
  the tab host to offer Undo on the selected page, including Contacts and Calls.
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
- **Add contact** restores the dialog's inputs and binds submission to the current activity.
  The list activity serializes the Contacts and notification permission requests. An interrupted
  Contacts request can be retried by returning to or tapping Contacts.
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
| "Note to self" (search suggestion: `AccountPickerDialog.Enabled`, then `findOrCreateConversation` of the self contact) | Drawer and Profile tab "Saved messages" (`SavedMessages.open`; the account filter's writable account first) |

# Account switcher and account filter (2026-11-21, not yet verified on a device)

Tatrak has no current account: every enabled account is online and their chats share one list.
The owner's decision (2026-11-21) is an app-wide filter instead of Telegram's account switch.

- **Profile item.** The floating bar's Profile shows the filtered account's avatar; under "All
  accounts" (more than one account, no filter) it shows the switcher's "All accounts" icon
  (`AllAccountsIcon`: a group icon on the accent in the avatars' shape), not the first account's
  avatar (owner, 2026-11-28). The drawer header still names the first account.
- **Switcher.** A long press on the floating bar's Profile item opens Telegram's account selector
  (`MainTabsActivity.openAccountSelector` with `ItemOptions` and `accountView`):[^switcher] the
  screen behind dims (`0x66` over a light window, `0x33` over a dark one) and blurs (12 dp, only when
  "Blur under bars" is on, so API 31+), the Profile item is drawn again above the dim on a plate with
  28 dp corners and a soft shadow, and a 230 dp menu grows from above it: "Add account", a gap, then
  "All accounts" (only with more than one account) and every account with a 32 dp
  avatar (shrunk inside a 1.33 dp accent ring when selected), its name in up to two lines and its
  unread chats. A disabled account is listed too (owner, 2026-11-24): its name in `onSurfaceVariant`
  (at least 4.5:1 on the menu in every style, accent and night background, unit-tested), its avatar
  at alpha 0.38, "Disabled" (`tatrak_account_disabled`) under the name; TalkBack reads the row as
  name plus "Disabled". The account rows scroll inside the space above the dock item (landscape, a
  short window). With a single account its row only closes the menu and saves nothing. Telegram has no
  disabled accounts; Cheogram's drawer account header also lists every account from
  `getAccounts()`, disabled ones included. Back or a tap outside closes it. Only the floating dock has the item: Slate and
  Sea's classic bar has Settings in that slot, and Telegram's style without floating bars has no
  dock; a Glass style with "Floating bars" turned off has no dock either (not the classic one).
  The overlay lives in `ChatListFragment`'s composition; the dock item reports its bounds through
  `LocalAccountSwitcher`.
- **One place.** `AccountFilter` holds the choice in the `tatrak_account_filter` preference (the
  account's uuid; persisted, renaming the key resets existing choices) and answers `selected`
  (null: all accounts; also when the stored account was removed, only one account
  exists (the stored choice is then cleared, so a new second account does not bring it back), or
  the style has no floating dock, where nothing would show or clear the filter; the stored choice
  comes back with the dock) and `shows`.[^filter] Every screen asks it: the chat list snapshot (chats, archive,
  folder counters, the "Connecting…" title, the header and dock avatar), message search of all
  chats (the SQL applies the account before the result limit), the tablet's suggested chat, `ContactFlows.filterContacts` (Contacts tab and New chat), New chat's groups and the
  accounts its dialogs offer (`StartConversationActivity.mActivatedAccounts`, the Contacts tab's
  "Add contact"), the Calls list (`callChats`, and in its query before the 300-call limit), the Profile tab and the settings home header
  (`settingsAccount`), and the share picker (`ShareCompose`). The share picker's + passes its
  scope to the contact chooser, including the account options. Notifications ignore the filter.
- **A disabled account as the filter** (owner's option A, 2026-11-24). Viewing follows it: its
  chats, archive, search, both Contacts screens (the tab and the drawer's contacts-only
  `StartConversationActivity`; `ContactFlows.filterContacts` takes the filter from its caller and
  lists the filtered account's contacts when it is disabled; under "All accounts" a disabled
  account's contacts stay hidden, as upstream), Calls, Profile, the tablet suggestion and
  `follow`. What sends or creates asks `AccountFilter.writable` (the filter while it is enabled,
  otherwise null): New chat's contacts and groups and the accounts its dialogs offer
  (`enabledAccounts`, also both Contacts screens' "Add contact"), the share picker's + (contact
  chooser scope).
- **No new chat on a disabled account.** A contact of a disabled account
  (`ContactFlows.openDisabledContact`, both Contacts screens and their search "done") opens its
  existing chat, or the contact details when there is none; its menu has no Call. A closed group
  of a disabled account opened from the chat list's search is reopened without joining. The share
  picker lists only enabled accounts' chats (also under "All accounts"), and a Direct Share
  shortcut is checked after the service binds: a disabled account's chat falls back to the picker.
  An open chat of a disabled account keeps the classic behaviour: `ConversationFragment` lets you
  write and queues the message, with the "account disabled / Enable" snackbar; the Compose chat
  shows the same banner (`ChatBanner.ACCOUNT_DISABLED`) and its call buttons the same Enable
  dialog (`RtpCallStarter.isAccountInsufficientState`).
- **Unread per account** counts unmuted chats with unread messages outside the archive, as the
  folder tabs count; "All accounts" shows the sum.
- **Choosing** writes the preference and refreshes the list and every tab's data. On a tablet,
  a chat from another account gives way to an eligible suggestion or an empty pane. Calls clears
  rows from the previous scope and checks the current account before opening a chat or placing
  a call. Inline search reruns the same query for the new account and discards previous results.
  "Add account" opens `EditAccountActivity` via the same route as the Accounts screen.
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
and closes it, and that one account shows no "All accounts"; `account_switcher_disabled_light.png` /
`_dark.png` show one enabled and one disabled account (both rows, "All accounts", the muted row
read as name plus "Disabled", picked like any other); `accountSwitcherMutedNameContrast` and
`accountSwitcherScrollsItsRows` cover the contrast and the scrolling rows. `DisabledAccountFlowsTest`
runs the real New chat, drawer Contacts, Contacts tab and share picker (with Direct Share) under a
disabled filter. `AccountFilterTest` (fake service and
connection-less accounts) covers chats, archive and counters, contacts, calls, search, New chat's
accounts, `pick`, the fallback when the account is removed (a disabled one stays the filter), the
choice cleared at one account, a
disabled account filtering chats, contacts and calls while New chat offers the enabled one, and the switch
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

# Saved messages always reachable (2026-11-27, public issue #6, not yet verified on a device)

- A permanent "Saved messages" item in the side menu (after Calls, Telegram's order) and a first
  section in the Profile tab (only there: `ChatListFragment` prepends
  `SavedMessages.profileRow` to `settingsSections`, so the classic Settings home is unchanged).
  Both call `ChatListActions.openSavedMessages`, which `ChatListFragment` implements as upstream's
  "Note to self" (`ConversationsOverviewFragment`): `AccountFilter.writable` if the app is filtered
  to an enabled account, otherwise `AccountPickerDialog.Enabled` (it asks only with several
  enabled accounts); then `findOrCreateConversation(self.account, self.address, false, true)` and
  `OnConversationSelected`. The picker's answer opens nothing if the list fragment is no longer
  added, the service is gone, or the chosen account was removed or disabled while the dialog
  showed (its list is a snapshot); with no enabled account the picker opens nothing, as upstream.
- Pinned once per account: `BindProcessor.run()` calls `SavedMessages.pinOnFirstSignIn` inside its
  `loggedInSuccessfully` branch (one upstream line, owner-approved 2026-11-27).
  `Account.setOption(OPTION_LOGGED_IN_SUCCESSFULLY, true)` is true only when the flag was unset, so
  it runs at the account's first successful bind; the flag is saved with the account, so later
  binds, a reconnect after unpinning and a restored account that had signed in do not pin again.
  A self-chat in Tatrak's own archive folder (`tatrakArchivedAt`) is taken out of it with the pin,
  otherwise the pinned chat would stay hidden there.
- `XmppConnection.finalizeBind` runs the bind on two paths: on the connection's thread (at once
  without stream management, or when disco completes), and from the service when disco takes too
  long (its wake-up calls `XmppConnection.sendDiscoTimeout`). Upstream already calls
  `findOrCreateConversation` from the connection's thread (`MessageParser`, `BookmarkManager`); the
  method locks `conversations` itself.
- Archive query of the chat: `findOrCreateConversation(..., async = true)` queries MAM for a new
  chat in `postProcessConversation` only when `MessageArchiveManager.hasFeature()` (account disco)
  is known. With stream management the bind waits for disco (`mWaitForDisco`); without it, or after
  the disco timeout, it may not, and the chat gets no query of its own. The account catch-up does
  not make up for it: on a fresh install it has no message to start from (timestamp 0) and returns.
  So `ChatFragment.bind` calls `SavedMessages.fetchHistoryIfEmpty`: for the self-chat with no
  messages it queues on `DATABASE_READER` (behind the chat's own post-processing, so its DB load
  and query come first) and calls `MessageArchiveManager.query(c)` with the checks of
  `postProcessConversation` and `loadMoreMessages`: online, contact not blocked, MAM known, no
  query running for the chat, and `hasMessagesLeftOnServer()` (false after a clear, so the clear
  boundary holds, and after a query reached the archive's end, so it asks once). `query(c)` starts
  at `getLastMessageTransmitted()`, which includes the clear date. Every way into the chat goes
  through it (pinned row, drawer, Profile row, notification).
- Tests: `SavedMessagesTest` (pin creates and pins, pins an existing archived chat, account
  choice), `SavedMessagesFlowsTest` (the real `BindProcessor` pins at the first bind and not at
  the second; the picker with 0, 1 and 2 enabled accounts, a late answer after the list left, an
  account disabled while the picker shows; a click on the real Profile tab row; the empty
  self-chat's MAM query once MAM is known, not twice, not after a clear),
  `ChatListScreenshotTest.drawerSavedMessages`, the drawer and `MainTabsTest` Profile shots.
