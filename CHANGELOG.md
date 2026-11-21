# Changelog

## Unreleased

### Interface
- Chats, Contacts, Calls and Profile are tabs under the bottom bar: a tap slides to the tab, the
  bar stays in place, Back returns to Chats (as in Telegram).

## 0.4.0-beta.1 (pre-release)

### Interface
- Long press on a chat's avatar in the chat list shows the chat without marking it read; a tap
  opens it, a tap outside closes it (as in Telegram).
- Frequently used reactions as a second row in the message menu.
- The block list, the profile picture, changing your or a group's picture and a message's edit
  history are drawn in the new interface.

### Chat
- Every confirmation of a destructive action (delete, clear history, leave, block, disable an
  account and others) says what is deleted where, who sees it and whether it can be undone; the
  chat menu's Archive hides the chat in the archive with Undo.
- Status rows as in the classic screen: do-not-disturb, the contact's night time, who has read up
  to a message in private groups, and a "Read by" list in the message info.
- Every notice of the classic chat (disabled account, blocked contact, add back, group errors,
  OpenPGP, message from a stranger) as a bar with its action.

### Calls
- Fixes in call setup after a reconnect and on the call screen.

### Updates
- The app checks the GitHub releases for a newer version once a day (on by default, never while
  Tor is on, off in Settings → Updates), shows the release notes, downloads the APK for the
  device and installs it only when it carries Tatrak's release signature.

## 0.3.0

### Interface
- Floating bars, as in Telegram 13: in the chat list the top bar and a new bottom tab bar (Chats,
  Contacts, Calls, Profile, search) float over the list; in the chat the header, the pinned bar and
  the composer float over the messages. A setting of its own, with any style.
- Blur under the floating bars on Android 12 and later: Frosted (checked for contrast) or Clear,
  like Telegram.
- Three new styles: Glass, Warm Glass and Tinted Glass (tinted with the accent).
- The settings pages are drawn in the new interface.
- Side menu stays open on a theme toggle; incoming and outgoing tabs in the call list.
- Archive pull as in Telegram, with a hint on the first archived chat.

### Chat
- Select several messages, as in Telegram: copy, forward, delete.
- A "Message info" sheet with send and receive times and the encryption.
- The attach menu opens in place of the keyboard and can share a contact.
- The chat slides in once its messages are laid out, and the list no longer jumps after a send.
- Delivery status icons keep one slot, so the time does not shift.

### Fixes
- OMEMO reactions sent with an encrypted fallback (Cheogram) no longer show as messages.

## 0.2.0

First public Tatrak release (0.1.0 was never published), based on Conversations 2.21.0. Changes compared to Conversations 2.21.0:

### Privacy and security
- Message history and UnifiedPush databases encrypted on the device with SQLCipher.
- Attachments, voice messages, camera captures, avatars, wallpapers and sticker packs stored
  encrypted. The key is random and wrapped by the Android Keystore; no app passcode yet.
- End-to-end and transport encryption (OMEMO, OpenPGP, TLS) unchanged from Conversations.
- The message menu shows how a message was encrypted: OMEMO with the key's trust and fingerprint
  (tap to open the key list), OpenPGP or clear text.
- Crash reports are off by default (Tatrak has no crash report address).

### Interface
- New Telegram-style interface (Jetpack Compose) for the chat list and the chat screen; the
  classic Conversations chat view remains as a fallback.
- Chat folders: All, Personal, Groups, Unread.
- Archive that keeps chats active; an unmuted chat returns to the list on a new message.
- Multi-select in the chat list: pin, mute, mark read, archive, delete.
- Swipe back from a chat to the list.
- Nine accent colours, a night background (blue-grey, graphite or AMOLED black) and chat
  wallpapers.
- Round or square avatars.
- Hold-to-record voice messages, attachment sheet and emoji panel.

### Messaging
- Replies with swipe to reply (XEP-0461), Telegram-style (swipe left) or Signal-style (swipe right).
- Delete for everyone (XEP-0424).
- Sticker packs: import, share and send.
- Last seen of offline contacts (XEP-0012).
- Sent and received times in the message menu.
- Faster message search.
- Import of monocles chat backups; Tatrak backups can include media.
- Backup restore lets you choose what to take of the OMEMO state: everything, contacts' trust
  only, or nothing.
