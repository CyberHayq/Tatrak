# Changelog

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
