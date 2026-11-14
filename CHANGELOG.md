# Changelog

## 0.1.0 (unreleased)

First Tatrak release, based on Conversations 2.21.0. Changes compared to Conversations 2.21.0:

### Privacy and security
- Message history and UnifiedPush databases encrypted on the device with SQLCipher.
- Attachments, voice messages, camera captures, avatars, wallpapers and sticker packs stored
  encrypted. The key is random and wrapped by the Android Keystore; no app passcode yet.
- End-to-end and transport encryption (OMEMO, OpenPGP, TLS) unchanged from Conversations.

### Interface
- New Telegram-style interface (Jetpack Compose) for the chat list and the chat screen; the
  classic Conversations chat view remains as a fallback.
- Chat folders: All, Personal, Groups, Unread.
- Archive that keeps chats active; an unmuted chat returns to the list on a new message.
- Multi-select in the chat list: pin, mute, mark read, archive, delete.
- Swipe back from a chat to the list.
- Accent colours and chat wallpapers.
- Hold-to-record voice messages, attachment sheet and emoji panel.

### Messaging
- Replies with swipe to reply (XEP-0461).
- Delete for everyone (XEP-0424).
- Sticker packs: import, share and send.
- Last seen of offline contacts (XEP-0012).
- Import of monocles chat backups; Tatrak backups can include media.
