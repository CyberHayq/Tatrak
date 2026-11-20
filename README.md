<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" width="112" alt="Tatrak icon" />

# Tatrak

**An Android messenger for the open XMPP network that keeps your chats encrypted on the phone,
not only in transit.**

[![Latest release](https://img.shields.io/github/v/release/CyberHayq/Tatrak?label=release)](https://github.com/CyberHayq/Tatrak/releases/latest)
[![Licence: GPL v3](https://img.shields.io/badge/licence-GPL%20v3-blue)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84)

[Download](#download) · [Security](#security) · [Features](#features) · [Roadmap](#roadmap) · [Building](#building)

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01.png" width="190" alt="Conversation list in dark theme, with built-in chat folders" />&nbsp;
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02.png" width="190" alt="Conversation with messages and composer in dark theme" />&nbsp;
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03.png" width="190" alt="A reply in a conversation" />&nbsp;
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04.png" width="190" alt="Conversation list with a custom accent palette" />

</div>

## Why Tatrak

- 🔒 **Encrypted on the device.** The message history, attachments, voice messages and avatars
  are stored encrypted, so reading the phone's storage does not reveal your chats.
- 🛡️ **Proven end-to-end encryption.** OMEMO, OpenPGP and TLS come unchanged from
  [Conversations](https://codeberg.org/iNPUTmice/Conversations); Tatrak builds a new interface on
  top of them.
- 💬 **A familiar interface.** Chat folders, an archive, replies, reactions, stickers and
  wallpapers in a Telegram-like design, in light and dark themes.
- 🌐 **No central server.** Use any standard XMPP server, a provider or your own, and talk to people
  on other servers. No phone number and no Google account needed.

## Download

Get the APK for your phone from the [latest release](https://github.com/CyberHayq/Tatrak/releases/latest).
If you are unsure which file to take, `arm64-v8a` fits almost every current phone and `universal`
fits all of them.

Release APKs are reproducible and signed with one key. To check a download, run
`apksigner verify --print-certs <apk>`; the certificate's SHA-256 digest must be:

```
3bae017d66664f88502ab51a6868621c1f93fd47004b3666796d3cd9a6946d9b
```

## Security

| Layer | What protects it |
|---|---|
| **In transit** | End-to-end encryption with OMEMO and OpenPGP; TLS, SASL and DNSSEC for the connection. All from Conversations, unchanged. |
| **On the device** | The message database is encrypted with SQLCipher. Attachments, voice messages, camera captures, avatars, wallpapers and sticker packs are stored encrypted. The key is random and wrapped by the Android Keystore. |

> [!IMPORTANT]
> There is no app passcode yet, so the on-device encryption protects against reading the storage,
> not against someone who holds your unlocked phone. The encryption has not been audited by a
> third party.

Details: [database encryption](knowledge/build/local-db-encryption.md) ·
[attachment encryption](knowledge/protocol/attachment-encryption.md)

## Features

✅ Done: built and checked on emulators · 🧪 In testing: built, with checks still open

| Feature | Status |
|---|---|
| Encrypted message database on the device (SQLCipher) | ✅ |
| Encrypted storage for attachments, voice messages, avatars, wallpapers and sticker packs | ✅ |
| Voice messages encrypted while you record, never written to disk in the clear (Android 10+) | ✅ |
| Chat folders (All, Personal, Groups, Unread), archive, pin, mute, select several chats | ✅ |
| Chat screen, light and dark themes, accent colours and chat wallpapers | ✅ |
| Hold-to-record voice messages, attachment sheet, emoji panel | ✅ |
| Replies, reactions, editing, delete for everyone, sticker packs, search across conversations | 🧪 |
| Select several messages in a chat; chat preview on a long press of its avatar | 🧪 |
| Encrypted audio and video calls, call list | 🧪 |
| Backups, including import of monocles chat backups | 🧪 |

Several accounts, group chats and push notifications through UnifiedPush come from Conversations.
Available features depend on the server. Nothing is tested on a wide range of real phones yet.

## Roadmap

🗓️ Planned: no dates promised · ❓ Under assessment: not yet decided

| Feature | Status |
|---|---|
| App lock (PIN or biometric) | 🗓️ |
| Password mode for local encryption | 🗓️ |
| Camera button during a voice call | 🗓️ |
| OMEMO 2 | 🗓️ |
| Group calls | ❓ |
| Post-quantum OMEMO | ❓ |

The full plan is in the [roadmap](knowledge/project/roadmap.md).

## Building

Requires JDK 25 and the Android SDK (compile SDK 37).

```sh
./gradlew assembleConversationsFreeRelease   # unsigned unless signing.properties exists
./gradlew testConversationsFreeDebugUnitTest # unit and screenshot tests
```

APKs land in `build/outputs/apk/conversationsFree/`.

<details>
<summary>About the build flavours</summary>

`conversationsFree` is the Tatrak build. The `quicksy` and `playstore` flavours are kept from
upstream so that upstream merges stay clean; they are not shipped. More in the
[build setup](knowledge/build/build-setup.md).

</details>

## Documentation

- Design notes, decisions and the roadmap live in [`knowledge/`](knowledge/); start with the
  [roadmap](knowledge/project/roadmap.md).
- A source-based [comparison with other messengers](docs/messenger-comparison.md).
- What changed in each version: [CHANGELOG](CHANGELOG.md).

## Credits and licence

Tatrak is a fork of [Conversations](https://codeberg.org/iNPUTmice/Conversations) by Daniel Gultsch
and contributors. Parts of the chat list animations are ported from
[Telegram for Android](https://github.com/DrKLO/Telegram) (GNU GPL v2 or later).

Licensed under the [GNU GPL v3](LICENSE).
