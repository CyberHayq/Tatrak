---
type: UI Screen
title: Chat screen
description: Compose chat screen ChatFragment that renders background-built message snapshots in a lazy list and falls back to the legacy ConversationFragment as a classic view.
tags: [ui, compose, chat-screen, performance, telegram-ux]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-28T21:03:16Z }
sources:
  - id: plan
    resource: ~/.claude/plans/ticklish-weaving-boole.md
    title: Approved implementation plan (local file, outside the repository)
  - id: conversation-fragment
    resource: ../../src/main/java/eu/siacs/conversations/ui/ConversationFragment.java
    title: Legacy ConversationFragment
  - id: message-adapter
    resource: ../../src/main/java/eu/siacs/conversations/ui/adapter/MessageAdapter.java
    title: Legacy MessageAdapter
---

# Status

Stage 3 of the [roadmap](../project/roadmap.md): implemented and wired into
`ConversationsActivity`, verified by screenshot tests only (no device yet).[^plan]

- Code: `src/main/kotlin/am/cyberhayq/chat/ui/chat/` (`ChatSnapshot`, `ChatViewModel`,
  `ChatScreen`, `MessageBubble`, `MessageText`, `Composer`, `AttachPanel`, `MessageMenu`,
  `VoicePlayer`, `ChatPalette`) and the host `src/main/kotlin/eu/siacs/conversations/ui/ChatFragment.kt`.
- `ConversationsActivity.showConversation` opens `ChatFragment` by default and the classic
  `ConversationFragment` when PGP is the next encryption, when the intent carries
  `EXTRA_NICK` / `EXTRA_IS_PRIVATE_MESSAGE` / `EXTRA_POST_INIT_ACTION`, or after the user
  chose "Classic view" for that chat in this session. Both fragments implement
  `ConversationHost`, which replaces the `instanceof ConversationFragment` checks used for
  navigation, archiving, rotation and the two-pane layout.
- Opening a chat slides it in from the right while the list shifts left (`res/anim/tatrak_*`).
- The slide waits for the first messages (2026-11-19), as Telegram delays its chat open. Before,
  the slide started on an empty screen and the messages were composed mid-slide, which the owner
  saw as jerks on some phones. `showConversation` sets `ChatFragment.slideAfterLoad` only when it
  sets the slide animators (not in two panes, not classic to Compose); the fragment then postpones
  its enter transition, capped at 300 ms, until `ChatActions.listReady` (the list's initial scroll
  is done) plus two frames at alpha 0. A postponed fragment still reaches `onStart`, so `bind` and
  the snapshot build run, but it stays below RESUMED: `markRead` keeps the position and `onResume`
  sends it, so an unshown chat sends no read marker. `SwipeBackLayout` at alpha 0 passes touches to
  the list below.
- The phone layout uses `FragmentContainerView` so the outgoing chat list is drawn behind the
  entering chat. A plain `FrameLayout` drew the list on top until the end of the transition,
  hiding most of the chat and making it jump into view on API 37.
- The header uses the same Telegram-style colours as the chat list.
- A private chat's avatar shows the same online dot as the chat list, based on the contact's
  presence independently of the last-seen setting and typing subtitle; groups have no dot.
  The dot means the contact's shown presence is `CHAT` or `ONLINE`; it does not establish that
  a person is looking at the app or this conversation. Presence may be unavailable or withheld.
  The UI also displays typing from XEP-0085 `composing` when the other client sends it. Do not
  label XEP-0085 `active` as "in this chat": clients may not send it and the state can be stale.
  XEP-0352 client state is sent to the user's server and is not a general signal to contacts.
  Decision with the owner: keep the online and typing indicators for now; do not add an
  app-open or chat-open indicator. References: [XEP-0085](https://xmpp.org/extensions/xep-0085.html)
  and [XEP-0352](https://xmpp.org/extensions/xep-0352.html).
  The existing last-seen subtitle uses `Contact.lastUserInteraction` and the owner's
  `last_activity` setting. It can display an idle time reported by a present resource, but the
  existing API returns no interaction when there are no presences; offline last seen is not
  guaranteed. Typing takes precedence over the subtitle.
- Outgoing checks use Conversations' existing status: one check for sent, two normal checks
  for a delivery receipt and two blue checks for a displayed marker. The icons have accessible
  descriptions and the read colour applies on media. Group echoes and self-chat reflections
  show as sent, not proof of delivery to another participant. Receipts and markers depend on
  the other client and its privacy settings.
- The drawer's Contacts screen reuses the new-chat roster and search but shows contacts only.
  Tapping a contact opens the chat, as in New message and in Telegram (until 2026-11-16 it opened
  the details screen, which has no way to the chat); the details screen is in the long-press menu;
  the call button uses `RtpCallStarter` and creates a conversation if necessary, as the existing
  call flow requires.
- All feed models are stable according to the Compose compiler report; every restartable
  composable in the app's Kotlin code is skippable (build with `-PcomposeReports`).
- Robustness (review of 2026-10-30): read markers are ignored while a snapshot of the
  previous chat is still on screen after `reInit` (an unknown uuid would make
  `Conversation.markRead` mark the whole chat), are held back until the initial scroll to the
  unread divider has finished, and the last one is sent again in `onResume` because the
  activity ignores reads while paused. Extras given to `reInit` before the fragment is added
  are processed in `onStart`; picker results that arrive before the service is bound (process
  death) are kept until `onBackendConnected`; a chat that no longer exists after a restore
  closes the screen like `ConversationFragment` does.
- File actions follow `ConversationFragment`: "Save" (`saveInternalToExternal`) for local
  files outside shared storage, "Cancel transmission" in the menu and on the file bubble's
  close icon while a transfer is cancelable.
- Voice: `VoicePlayer` holds transient audio focus while playing (pauses on loss); the
  progress bar of the loaded clip seeks on tap or drag and exposes `setProgress` to
  accessibility services.
- Voice input: `VoiceRecorder` reuses `RecordingActivity`'s `MediaRecorder` setup. The mic
  button records while held (250 ms threshold, a tap shows a hint), release sends, slide left
  cancels, slide up locks (Cancel / Send). Recordings under 1 s are discarded; TalkBack's click
  starts a locked recording. The file goes through `commitAttachments` (trust check included).
  Leaving the screen discards a running recording, like `RecordingActivity.onStop`.
  While the mic is held a padlock pill floats above it (`LockHint`, a `Popup`), rising with the
  finger and closing at the lock distance. The text field stays composed at zero width under the
  recording row (2026-11-16), so an open keyboard stays open: before, removing the field dropped
  the focus and the keyboard, the composer fell under the held finger, and the finger's position
  relative to the moved mic read as a slide up to the lock. Typing into the hidden field is
  ignored, since it would turn the mic into Send and end the recording.
- Replies (XEP-0461): sending keeps the XEP-0393 quote in the body as the XEP-0428 fallback
  and adds `<reply>` (stanza-id in channels, message id otherwise, the `ReactionManager` rule).
  Bubbles show a header with the answered message's sender and first line instead of the quote;
  the parent is looked up only in the same conversation, and tapping the header scrolls to it.
  Without a parent the header shows the quote's first line. Edits keep the quote; copy and edit
  use `Message.getBodyWithoutReplyFallback`.
- Delete for everyone (XEP-0424): menu entry with a confirmation for our own sent messages
  (channels need occupant-ids and a stanza-id). `ModerationManager.retract` sends the retraction
  with a fallback body and deletes the local copy like a moderation; incoming retractions are
  only applied for the same author (`handleUserRetraction`), also when the fallback body was
  OMEMO-encrypted (decrypted first so the session stays in step, never shown). A retraction
  whose message is not there yet (MAM paged newest-first) waits in `PendingRetractions` (memory,
  200 entries, 24 h, same sender rules); `MessageParser` asks `ModerationManager.isRetracted`
  before storing a new message and drops it on a match (stage 7a).
- Stickers: received webp/png files of at most 512 px render without a bubble
  (`isSticker`, a shape heuristic since nothing in the stanza says "sticker"). The emoji
  panel has a Stickers tab backed by `StickerStore` (`files/Stickers`): "+" imports pictures
  from the photo picker as webp ≤ 512 px with transparency, tap sends as a plain file (an image
  attachment would be re-encoded as JPEG), long press deletes. A received sticker's menu has
  "Add to my stickers". "Share my stickers" uploads the own set and a pack JSON
  (`tatrak-stickers.json`, the Cheogram / monocles format) over HTTP upload and puts the link into
  the composer; tapping such a link (or a `stickers.cheogram.com/*.json` one) asks and then adds
  the pack as its own section. Paths never come from the JSON (content hashes), at most 120
  stickers of 1 MiB, http(s) image URLs only, every image re-encoded. A message whose whole
  body is such a link renders as a pack card (`MessageKind.STICKER_PACK`, "View and add").
- Emoji panel: the composer's emoji button swaps the keyboard for `emoji2`'s
  `EmojiPickerView`, sized to the last keyboard height; picks insert at the cursor.
- Left to the classic view: PGP sending, choosing the encryption, calls, block/report,
  invites, moderation, edit history, retry decryption, camera capture, contact cards.

# Why the legacy screen janks

- `ConversationFragment` renders messages in a `ListView` backed by
  `MessageAdapter extends ArrayAdapter<Message>`.[^message-adapter]
- Service events (up to about twice per second) rebuild the message list and call
  `notifyDataSetChanged()`, rebinding every visible row.[^conversation-fragment]
- Message text (styling, links, quotes, emoticons) is formatted in `getView` on the main
  thread.
- Images have no reserved size, so rows jump when thumbnails arrive.

# Design

## Data

- `ChatViewModel` + `ChatSnapshot.build()` on the [SnapshotSource](snapshot-source.md)
  pattern; `conversation.populateWithMessages(copy)` is the thread-safe copy.
- Immutable `MessageItem` models: key, direction, group position (first/middle/last),
  sender name/colour, `AnnotatedString` text, kind (text, emoji, image, video, audio,
  file, location, call, unsupported), media size, file/transfer state, time, delivery
  status, edited flag, reactions, trust-warning flag; plus date chips and an unread divider.
- Text formatted in the background with the existing helpers (`StylingHelper`,
  `Linkify`, `QuoteHelper`, `Emoticons`) and converted from `Spanned` to
  `AnnotatedString`; results cached in an LRU keyed by uuid and the body. Its capacity is
  resized to at least the loaded message count before each snapshot, so a full sequential
  pass does not evict the entries needed by the next pass.
- History paging via `XmppConnectionService.loadMoreMessages`: database restoration starts with
  50 messages, paging prepends 50 at a time, and live messages append. The Compose screen does not
  trim on exit, so 150 (`PAGE_SIZE * MAX_NUM_PAGES`) is not a continuous cap. The classic fragment
  calls `trim()` on reinitialization only when the conversation is read and it has extras.
- The trust warning uses `ChatSnapshot.validInSession`, the `Message.isValidInSession` predicate
  computed for the whole list in two passes; calling it per message walked neighbours each time
  and was the dominant cost in long chats (538 -> 55 ms per build at 3,050 messages); see
  risks.

## Rendering

- `LazyColumn(reverseLayout = true)` with `key = uuid`, `contentType` per bubble kind and
  `animateItem()`; Compose compiler stability reports must show all models stable.
- Telegram-style bubbles with tails on the last message of a group (grouping copies the
  legacy merge rule and `Config.MESSAGE_MERGE_WINDOW`), time and ticks inside the bubble.
- Images in boxes sized from the file parameters before the thumbnail loads.
- Floating date, scroll-to-bottom button with unread count, open at first unread.
- A new message while the list is at the very bottom is placed there in the same frame
  (`requestScrollToItem(0)` from a `SideEffect` in `FollowNewMessages`), so `animateItem` slides the
  older ones up; anchoring by key would put it below the screen and scroll after. The composer's
  text field animates its height with the same default spring, and after Send it keeps the sent
  text's height (`newest` key, 500 ms fallback for edits) until the message is in the list, so the
  field shrinks in the same frame the list starts moving.

## Actions

- Text send follows the legacy sequence exactly: trust check →
  `new Message(conversation, body, conversation.getNextEncryption())` →
  `Message.configurePrivateMessage` → `xmppConnectionService.sendMessage`. See
  [security stays untouched](../decisions/security-untouched.md).
- Attachments through the service's existing `attach*ToConversation` methods
  (encryption stays inside the service).
- Gallery, file and camera picks wait in a preview strip that replaces the text field until Send
  (classic `MediaPreviewAdapter` behaviour, no caption); each has a remove button.
- Header: a call button for one-to-one chats (`RtpCallStarter`) and a menu with search, mute, pin,
  wallpaper, archive, clear history, block / unblock and the classic view. Long-press menu extras
  (edit history, error text, open with, delete file, copy link / URL, Forward) copy the classic
  `populateContextMenu` rules into `ChatSnapshot.actions`. Hardware keyboard: Enter sends when
  "Enter is send" is on, Ctrl+Enter always, Shift+Enter is a new line, Ctrl+Up edits the last own
  message.
- Tap and long press (2026-11-17, owner, Telegram model; plan):
  a tap on a text bubble (not on a link) or on the row beside any bubble opens the message menu;
  media bubbles keep their tap (`primaryAction`); a long press selects the message and the same
  finger dragging over other rows selects them (`MessageSelection.kt`: `selectionGestures` in the
  list's Initial pass, `dragSelection`, `indexAt` for the reversed list, auto-scroll 56 dp / 12 dp
  per frame, cap 100, after Telegram's `RecyclerListView` / `ChatActivity` at `21f7d10`). While
  selecting a tap toggles, a check circle and a highlight mark the rows, swipe-to-reply and
  swipe-back (`BlockSwipeBack`, re-checked on each move by `SwipeBackLayout`) are off, and
  `SelectionBar` replaces the top bar: Copy (`selectionCopyText`, Telegram's format), Forward
  (one in-app picker; `ACTION_SEND_MULTIPLE` with the files plus the texts as a quote and
  `EXTRA_AS_QUOTE`, which `ShareWithActivity` passes on and `ChatFragment.processExtras` turns into
  a quote plus composer previews; one message still goes through `ShareUtil.forward`), Delete (one dialog, then the legacy `deleteLocal` /
  `retract` per message, oldest first; the selection stays until the dialog confirms).
- The menu (opened by a tap since 2026-11-17): reactions (existing `sendReactions` and restrictions) and a menu with
  reply, copy, edit (existing correction flow), retry, save/share. The menu (`MessageMenu`)
  draws the bubble exactly where it was in the list and hides the original (`LocalMenuMessage`,
  read in a graphics layer, so no row recomposes); the reactions and actions go around it where
  there is room (`MenuPlacement`: reactions above and actions below, both below, both above, or
  swapped). Only when no side has room does the message move, animated, scrolling inside if still
  too tall. Before 2026-10-31 a copy was centred while the original stayed visible, which looked
  like a duplicate. One vibration only: `combinedClickable` vibrates on long press by itself
  (Compose foundation 1.7+), the extra manual one is gone.
- Message info (2026-11-17, owner): the long-press menu no longer starts with info lines; its last
  entry "Message info" (`tatrak_chat_message_info`, Material "info outline" icon, Apache-2.0) is shown
  when the time received is known or the message has an encryption line, and opens
  `MessageInfoSheet` (a `ModalBottomSheet` built like `WallpaperSheet`, its message key kept in
  `ChatScreen` with `rememberSaveable` like `reactingTo`). The menu is back to 200-260 dp wide.
- Group read markers (2026-11-19, owner chose both forms): in a private, non-anonymous group
  (`MucOptions.isPrivateAndNonAnonymous`, the only rooms where `DisplayedManager` stores
  `Message.readByMarkers`) `ChatSnapshot.Builder.readMarks` repeats the walk of
  `ConversationFragment.updateStatusMessages`, newest first: a `ServiceItem` ("Anya, Boris have read
  up to this point", upstream strings, "Everyone…" / "+N others" past four names) under the message
  where a member's marker first shows, members who wrote later left out, and the walk stops when
  every member is represented. The row splits the bubble run. Members typing are not left out as
  in the classic screen: typing shows in the header, not as a row. `MessageItem.readBy` lists, for
  each own message, every member whose marker is on it or a later message (markers only, not later
  senders); the info sheet shows them under "Read by" (`tatrak_chat_info_read_by`). Blue checks are
  unchanged: in a group they still mean every member has read (`DisplayedManager`).
- Sent / received times (2026-11-15, owner; in the sheet since 2026-11-17): for a received message whose time received is known
  (`Message.getTimeReceived()`, column `tatrak_time_received`), the sheet shows "Sent <date, time>"
  (`getTimeSent`) and "Received <date, time>" rows. `MessageItem.receivedAt` / `sentAt` carry the raw
  epoch ms (null / 0 for own messages and unknown times), so only the open sheet formats them
  (`receiptTime`: ICU skeleton `dMMM` + seconds via `DateFormat.getBestDateTimePattern`, the
  device's 12/24-hour setting, the year only when not the current one).
- Message key (2026-11-16, owner, option A; in the sheet since 2026-11-17): under the times (or first, when there are none) the
  sheet names the encryption, `MessageItem.encryptionKey` (`KeyInfo`): the method from
  `CryptoHelper.encryptionTypeToText` ("Clear text", "OpenPGP", "OMEMO", imported PQ history) and,
  for an OMEMO message with `Message.getFingerprint()`, the key's trust ("this device" for the own
  fingerprint, else from `AxolotlService.getFingerprintTrust`: verified ✓, trusted not verified, not
  trusted, compromised, unknown key) and the fingerprint without the `05` type byte as two lines of
  four 8-digit groups in monospace (14sp, selectable). The trust lookup is done once per message in the snapshot and
  also feeds the bubble's lock (`security`). The sheet's "Show this key in the key list" button calls
  `ChatActions.openMessageKey`: `ChatFragment` repeats `ConversationFragment.onContactPictureLongClicked`
  ("Contact details" / "Account details"; in a group the sender's real JID, as
  `MucDetailsContextMenuHelper`), so `ContactDetailsActivity` or `EditAccountActivity` opens with
  the key highlighted. PGP shows only the method, not tappable: the classic code keeps no
  per-message PGP key, and the contact's current one could be taken for it. `keyTrustLabel` and
  `fingerprintLines` are top-level functions so they are unit-tested without an account connection.
  Call records get no key line. `ContactDetailsActivity` lists the message's key even when it is
  inactive and inactive devices are hidden (Tatrak edit).
- Retractions (XEP-0424) of OMEMO messages need proof: the retraction must be encrypted with the
  message's key (or come from one of our own trusted devices), else the message stays and the
  bubble shows "Unverified request to delete this message" (`MessageItem.retractUnverified`, from
  `Message.retractAttempt`).
- First chat opened after a start: the snapshot waited for the emoji library's table
  (`Message.bodyIsOnlyEmojis` → `Emoticons` → `EmojiManager`, loaded on first use), measured on
  2026-10-31 at 275 ms in a debug build on the x86 emulator and 80 ms in the optimised `benchmark`
  build; the whole first build of 50 messages was 315 ms against 95 ms. `EmojiWarmup` loads it on a
  background thread when the chat list is created. Debug builds are 3–4× slower than release, so
  judge smoothness on the `benchmark` build.
- Swipe to reply sends an XEP-0393 quote for now; XEP-0461 replies come in stage 5
  ([XEP support](../protocol/xep-support.md)).
- Mentions in groups (2026-11-13, `Mentions.kt`): a tap on a sender's avatar runs the group
  branch of the classic `onContactPictureClicked` (participation check, "has left" toast) and
  inserts the nick with a pure port of `highlightInConference` ("nick: " at the start, ", nick"
  merged into an existing "a, b: " prefix, "nick " elsewhere; checked against the legacy method on
  3,000 random cases). Typing "@" at a word start offers the participants
  (`ChatActions.mentionCandidates`, built off the main thread from `mucOptions.users` without our
  own nick, as the classic Tab completion); a pick replaces "@query" with the same text, without
  the "@" (XMPP clients highlight the bare nick). An "@" button above "scroll down" jumps to the
  oldest unread message that matches our nick pattern (`NotificationService`'s highlight pattern)
  through the search-result jump. monocles' "@" completion needs a third-party library, not used.
- `centerOn` (jump to a message) centres on where the item actually landed; the fixed
  half-viewport step pushed a message near the oldest loaded one off screen (fixed 2026-11-13,
  affected search results too).
- Selection formatting (2026-11-13, `TextStyling.kt`): Bold, Italic, Monospace and Strikethrough
  in the text field's system selection menu (`appendTextContextMenuComponents`, on by default in
  this Compose version) wrap the selection in XEP-0393 markers, per line, whitespace kept outside.
- Keyboard and panels (2026-11-13, `KeyboardSpace.kt`): the composer's bottom space is
  `max(ime, nav + panel) - panel`, read at layout time, so the keyboard slides under the
  panel instead of adding to it, and switching back keeps the panel until the keyboard has
  settled (`imeAnimationTarget` reached). Two panels take the keyboard's place (`KeyboardPanel`):
  emoji/stickers and, since 2026-11-19 (owner), the attach menu (`AttachPanel`, 3 + 3 grid, one
  row under a keyboard shorter than 200 dp; Contact added the same day, the classic
  `ATTACHMENT_CHOICE_CONTACT` flow through the shared `ConversationFragment.contactPickIntent`) instead of the modal attach sheet and its held
  keyboard space. The clip and the emoji button switch between them; tapped again, or the field
  tapped, the keyboard comes back; Back or a pick closes the panel without the keyboard. Both take
  the last settled keyboard height (280 dp before the first keyboard).
- "More reactions" (2026-11-13, `ReactionSheet.kt`) opens a bottom sheet with the composer's
  emoji2 picker instead of the classic `AddReactionDialog`; a pick runs
  `AddReactionActivity.addReaction`'s rule (an emoji already ours re-sends the same set) and
  `sendReactions`. The room's reaction allow-list is not applied to the picker; a disallowed pick
  is refused by `sendReactions` with its toast.

## Profile (stage 6d)

Tapping the header opens `ProfileActivity` (`src/main/kotlin/eu/siacs/conversations/ui/`,
Compose `ui/profile/ProfileScreen.kt`); the own chat still opens `EditAccountActivity`.
State is rebuilt on a `SnapshotSource` from the service's conversation / roster / MUC /
blocklist callbacks; the header reuses `ChatSnapshot.header`. Actions copy the classic code
1:1: mute (`toggleMute`, shared with `ChatFragment`), audio / video call and return to an
ongoing call (`ConversationFragment` call checks → `CallIntegrationConnectionService.placeCall`),
search, QR / share link (`getShareableUri` of the details screens), media browser, members
(first 20, all → `MucUsersActivity`), block (`BlockContactDialog`), clear history, delete /
leave (like the chat list's delete). Above the media row a strip shows the 12 newest photos and
videos (`getAttachments`, loaded once per opening; tap opens the file with `ViewUtil.view`). Keys, contact editing and room configuration are links to
the unchanged `ContactDetailsActivity` / `ConferenceDetailsActivity`.

## Swipe-back (stage 6e)

`ChatFragment` wraps its view in `SwipeBackLayout` (`ui/chat/SwipeBack.kt`). A rightward drag
feeds predictive back (`dispatchOnBackStarted / Progressed / Cancelled`, `onBackPressed`) to the
activity's dispatcher; finishing past a third of the width or on a fling. The slide itself is
the fragments' `onCreateAnimator` (`slideAnimator` in `ChatFragment.kt`: chat slides out,
the list comes in from −25 %; 300 ms, `DecelerateInterpolator(1.5f)` = 1 − (1 − x)³, Telegram's chat curve).

- **API 34+**: the FragmentManager seeks those animators with the back progress. They use a
  `DecelerateInterpolator(1.5f)`, so the layout sends `seekProgress(p) = 1 − ∛(1 − p)`, its inverse,
  which keeps the chat edge under the finger.
- **Below API 34**: back-started already pops the fragment, but nothing seeks the animators.
  The layout moves the chat (`translationX = dx`) and the list below it (parallax) by hand.
  On finish `slideAnimator` starts from the current `translationX`; on cancel the layout
  animates both back to 0 and then dispatches back-cancelled.

**System edge back.** With gesture navigation the left system back zone (`systemGestures` inset)
is left to the system: our drag does not start there, so both gestures never run at once.
Before 2026-11-06 the system's predictive back (manifest `enableOnBackInvokedCallback`) reached the
same dispatcher, and on API 34+ the FragmentManager seeked the same full slide by the system's
progress: the system arrow plus the whole chat sliding with the finger, which looked like two
gestures at once. Now `SwipeBackLayout`, while attached and `swipeEnabled`, registers a plain
`OnBackInvokedCallback` on the window at priority 1 (above the dispatcher's input at
`PRIORITY_DEFAULT`, below `PRIORITY_OVERLAY`) that only calls `dispatcher.onBackPressed()` on
release. So during the edge gesture the system shows its arrow and the chat does not move; on
release the normal 300 ms pop plays; a cancelled gesture changes nothing. Compose BackHandlers
(emoji panel, recording discard, message menu) keep priority because the release still goes
through the dispatcher. The keyboard's own back callback sits at `PRIORITY_DEFAULT`, below ours,
so before 2026-11-16 a back gesture with the keyboard up closed the chat; the callback now hides
the keyboard first when the `ime` inset is visible. This is the owner's choice (as in Forkgram classic and monocles); a
Telegram-style 56 dp peek (capping the progress passed on to the FragmentManager) was built,
worked on the emulator and was dropped for it. Below API 34 the code path is off and system back
stays the plain dispatcher back.

Areas with their own horizontal drags opt out with `Modifier.swipeBackExclusion()`; the emoji
panel and recording block it entirely with `BlockSwipeBack(active)`.

## Swipe to reply: Telegram or Signal method

`MessageRow` (`ui/chat/MessageBubble.kt`) runs its own pointer loop instead of
`detectHorizontalDragGestures`, which claimed any drag whose horizontal part passed the touch
slop (up to about 45°) and so turned slightly diagonal scrolls into replies. The rule comes from
`SwipeMethod` (`ui/chat/SwipeMethod.kt`, preference `tatrak_swipe_method` in Interface settings),
read once per chat in `ChatFragment` and passed down as `LocalSwipeMethod`:

| | Telegram (default) | Signal |
|---|---|---|
| Source | `ChatActivity.java` (`21f7d10`) | `ConversationItemSwipeCallback`, `ConversationSwipeAnimationHelper` (`515db93`) |
| Direction | left | right |
| Starts after | 0.4 cm (25 dp) | touch slop (`ItemTouchHelper`) |
| Angle | `abs(dx) / 3 > abs(dy)` (about 18°) | `abs(dx) > abs(dy)` (45°) |
| Reply on release past / bubble max | 56 / 80 dp (Telegram: 50 / 80) | 64 / 96 dp |
| Swipe-back drag | on | off (`SwipeBackLayout.dragEnabled`); only the system back gesture |

Both give the gesture up once the vertical movement passes the touch slop, where the list claims
it (it sees the events after the row in the Main pass), and both leave it to a child that consumed
it first (voice seek bar). In the Signal method the system back callback stays registered, so the
edge gesture still plays the normal pop on release. Simplified: Signal's resistance curve past the
trigger (`BubblePositionInterpolator`) is linear here, and neither method checks that the list is
not still flinging (Telegram requires `SCROLL_STATE_IDLE`). Tests: `ChatScreenshotTest`
`telegram*` / `signal*` swipe cases.

## Banners and status rows (2026-11-19)

Ported from the classic screen after a parity check against `ConversationFragment` /
`MessageAdapter` (owner: "fix all"; Claude Opus 5.5 subagent from the main session's plan):

- `ChatBanner` is the classic snackbar: each case carries its upstream text and button, chosen
  by `ChatSnapshot.banner()` in `updateSnackBar`'s order (archived chat: none; disabled /
  logged out → Enable / Log in; blocked → Unblock; add back; allow; `CONNECTING` (Tatrak);
  a group not joined → the `MucOptions.Error` text and action (`mucBanner`); pending OpenPGP →
  Decrypt; message from a stranger → Block, a menu with "block entire domain" / "block
  stranger" as `showBlockSubmenu`). `ChatFragment.bannerAction` runs the classic listener of
  each case; the PGP result goes through `ConversationsActivity.onActivityResult` as before.
  The old generic "not connected to the group" + Classic view banner is gone. `BlockMenu`
  (`showBlockSubmenu`): the stranger's Block button and a long press on Add back / Allow open
  "Reject request" (not for a stranger, `stopPresenceUpdatesTo`), "Block entire domain" and
  "Block stranger" (address with a local part); the long-pressable button is a `Text` styled as a
  `TextButton`, which takes no long press.
- Group typing: the header subtitle shows `contact_is_typing` / `contacts_are_typing` from
  `MucOptions.getUsersWithChatState(5)` (Composing only); `ChatStateManager` redraws the chat when a member's chat state changes
  (Tatrak change in `xmpp/`, owner-approved 2026-11-19).
- `LoadMoreItem` ("Load more messages") above the oldest item under `showLoadMoreMessages`'s
  condition; `ChatFragment.loadHistory` is a port of `MessageAdapter.loadMoreMessages`.
- One-to-one, not typing: a `ServiceItem` under the newest message for DND
  (`contact_has_paused_their_notifications`) or the contact's night time (`its_time_for_contact`),
  same conditions as the classic screen; the time zone answer is used only if already there.

## Classic view fallback

A "Classic view" menu entry opens the legacy `ConversationFragment` for everything not
ported (calls, encryption selection, blocking, invites, moderation, edit history, PGP).
PGP conversations always open in the classic view. Hosting follows the
[UI bridge decision](../decisions/ui-bridge-fragments.md) via the planned
`ConversationHost` interface.

## Trust check

Both screens use `ui/util/TrustKeysGate.java`, a verbatim extraction of
`ConversationFragment.trustKeysIfNeeded`; `ChatFragment` launches the returned intent with
the Activity Result API and retries the send on `RESULT_OK`.

# Frame times (emulator)

Benchmark build (`assembleConversationsFreeBenchmark`: R8, baseline profile, debug-signed,
profileable) with `PerfDemoActivity` (300 generated chats / 2000 messages), Pixel 6 AVD,
API 35, host GPU, 48 flings per screen, `dumpsys gfxinfo`:

| Screen | Mode | Janky frames | Slow UI thread | p50 / p90 / p99 |
|---|---|---|---|---|
| Chat list | no AOT | 0.92 % | 2 | 16 / 24 / 32 ms |
| Chat feed | no AOT | 0.77 % | 3 | 16 / 17 / 31 ms |
| Chat list | baseline profile | 1.09 % | 0 | 16 / 23 / 27 ms |
| Chat feed | baseline profile | 0.66 % | 1 | 16 / 21 / 28 ms |

On the device the keyboard exposed a double IME inset (window panned and `imePadding`);
fixed with `adjustResize` on `ConversationsActivity`. Emulator numbers are indicative only.

# Tests

`ChatScreenshotTest` (12 screenshots: light/dark, group, media, reply, editing, menu,
empty, PGP banner, attach sheet) and `MessageTextTest` (7 span-conversion tests). `RecompositionTest`
proves that after a message arrives (snapshot rebuilt from scratch) only the new message and
its neighbour recompose in the feed, and only the bumped chat in the list; formatting costs
~0.13 ms per message on the JVM, once per message thanks to the cache. The whole
unit-test suite (127 tests) passes; Robolectric is pinned to SDK 35 in
`src/test/resources/robolectric.properties` because `includeAndroidResources` would otherwise
select SDK 37, which fails on JDK 25.

[^plan]: Approved implementation plan
[^message-adapter]: Legacy MessageAdapter
[^conversation-fragment]: Legacy ConversationFragment
