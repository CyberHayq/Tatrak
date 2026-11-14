# Tatrak messenger comparison

This document retains the source-based feature comparison and its evidence limits, split out from the [product README](../README.md). The tables are snapshots of inspected Android source and project documentation, not a hands-on audit.

## How it compares

✅ yes · ❌ no · ◐ partial or limited · ❔ not verified · N/A not applicable.
“Not found” and “unresolved” entries describe limits of source inspection, not tested absence. Similar features on different networks do not
imply interoperability. Both tables describe the inspected Android versions.

### General messenger comparison

Tatrak represents XMPP here alongside Signal, Molly, SimpleX and Element X. This table compares
features that can be understood independently of a particular messaging protocol.

|  | **Tatrak** | **Signal** | **Molly** | **SimpleX Chat** | **Element X** |
| --- | :---: | :---: | :---: | :---: | :---: |
| **Base** |  |  |  |  |  |
| Based on | Conversations 2.21 | original | Signal | original | Matrix Rust SDK |
| Focus | Telegram-style look and smoothness | private messaging | hardened Signal client | connections without user IDs | Matrix messaging |
| Network | XMPP | Signal | Signal | SimpleX | Matrix |
| Licence | GPLv3 | AGPL-3.0 | AGPL-3.0 | AGPL-3.0 | AGPL / commercial |
| **Interface** |  |  |  |  |  |
| UI toolkit | Jetpack Compose, Telegram-style | Views / Compose | Views / Compose | Compose Multiplatform (Android/desktop) | Jetpack Compose |
| Chat list folders (All / Personal / Groups / Unread) | ✅ | ✅ custom folders / filters | ✅ custom folders / filters | ◐ tags / preset filters | ◐ people, rooms, unread, favourites, spaces |
| Archive that keeps the chat active | ✅ | ✅ | ✅ | not found in chat-list UI | not found; leaving a room is different |
| Multi-select actions in the chat list | ✅ | ✅ | ✅ | not found in chat-list UI | not found for room list; message selection exists |
| Themes and accent colours | own themes, accent picker, house palettes | light/dark; chat colours | light/dark; chat colours | custom themes, colours and wallpapers | system/light/dark/black; no accent picker found |
| Chat wallpapers | ✅ | ✅ | ✅ | ✅ | not found in appearance settings |
| Swipe back to the chat list | ✅ | system Back; custom full-screen swipe not established | system Back; custom full-screen swipe not established | system Back; custom full-screen swipe not found | system Back; custom full-screen swipe not established |
| **Messaging** |  |  |  |  |  |
| Replies | ✅ swipe to reply | ✅ | ✅ | ✅ quoted replies | ✅ |
| Reactions | ✅ | ✅ | ✅ | ✅ | ✅ |
| Edit messages | ✅ | ✅ time-limited | ✅ time-limited | ✅ | ✅ subject to room permissions |
| Delete for everyone | ✅ | ✅ time-limited | ✅ time-limited | ◐ recipient opt-in | ✅ redaction; room permissions apply |
| Stickers | ✅ (sticker packs: import and sharing implemented) | ✅ packs | ✅ packs | no native pack picker found; images supported | ◐ renders incoming stickers; no sending picker found |
| Voice messages | ✅ hold to record | ✅ | ✅ | ✅ | ✅ |
| Location sharing | ✅ | ✅ static location | ✅ static location | no native location composer found | ✅ location feature |
| Text styling | ✅ XEP-0393, formatting from the selection menu | ✅ formatting / spoilers | ✅ formatting / spoilers | ✅ markdown-style formatting | ✅ rich text / Markdown |
| Stories / status posts | ❌ | ✅ stories | ✅ stories | no stories publisher found | ◐ profile status; no story feed found |
| **Calls** |  |  |  |  |  |
| Encrypted audio / video calls | ✅ | ✅ | ✅ | ✅ | ✅ Element Call |
| **Encryption and security** |  |  |  |  |  |
| Encrypted local database | ✅ SQLCipher | ✅ SQLCipher | ✅ SQLCipher | ✅ SQLCipher | ✅ inspected module |
| Encrypted local attachments | ✅ incl. voice, avatars, wallpapers, stickers | ✅ incl. thumbnail paths | ✅ inspected paths | ✅ default; optional | ◐ SDK cache encryption unresolved |
| Separate database password | planned | ❌ inspected mechanism | ✅ optional | ✅ optional startup entry | no user-set DB password flow found; recovery key is separate |
| Database key protection | random key wrapped by the Android Keystore | Keystore AES-GCM | Argon2id + Keystore HMAC when configured | stored passphrase or manual entry | Tink + Keystore (inspected module) |
| Database in Android cloud backup / device transfer | disabled / disabled; own encrypted backup | custom backup system | custom Android backup agent; not a raw DB copy | disabled / disabled | disabled / disabled |
| External service integrations (conditional) | fewer than Conversations: its help, bug-report and sign-up endpoints are off | Signal service/CDN; Google push depends on build | Signal service; push depends on flavour/configuration | preset SMP/XFTP servers; configurable | homeserver; push, maps and analytics depend on configuration |
| **Accounts and groups** |  |  |  |  |  |
| Multiple accounts | ✅ | ❌ one account per app instance | ❌ one account per app instance | multiple profiles | ◐ experimental flag; off by default |
| Group chats / channels | ✅ | ✅ groups | ✅ groups | ✅ groups | ✅ rooms / spaces |


### XMPP client comparison

All compared XMPP clients are included here. The shared feature rows are followed by each
client's XMPP mechanisms and extensions; Axichat also supports SMTP/IMAP email.

|  | **Tatrak** | **Conversations** | **Cheogram** | **monocles chat** | **Prav** | **Axichat** |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Base** |  |  |  |  |  |  |
| Based on | Conversations 2.21 | original | fork of Conversations | Conversations → Cheogram → monocles (about 2.18) | Quicksy / Conversations | Flutter / moxxmpp / DeltaChat Core |
| Focus | Telegram-style look and smoothness | simple, reliable, battery-friendly | SMS / voice via JMP gateways | many extra features | accessible XMPP messaging | XMPP, email and calendar |
| Network | XMPP | XMPP | XMPP | XMPP | XMPP | XMPP + SMTP/IMAP |
| Licence | GPLv3 | GPLv3 | GPLv3 | AGPL-3.0 | GPLv3 | AGPL-3.0 |
| **Interface** |  |  |  |  |  |  |
| UI toolkit | Jetpack Compose, Telegram-style | Views, Material 3 | Views, Material | Views, Material | Views | Flutter |
| Chat list folders (All / Personal / Groups / Unread) | ✅ | ❌ | ❌ | ❌ | not found in overview UI | ◐ custom folders and filters |
| Archive that keeps the chat active | ✅ | ❌ | ❌ | ❌ | ❌ closes/removes conversation; no archive folder | ✅ archived chat flag; no room leave |
| Multi-select actions in the chat list | ✅ | ◐ | not found in overview UI | ◐ | not found in overview UI | ✅ selectedJids / bulk actions |
| Themes and accent colours | own themes, accent picker, house palettes | dynamic colours | dynamic colours | dynamic colours | system/light/dark themes; no accent picker found | system/light/dark; themed colour schemes |
| Chat wallpapers | ✅ | ❌ | not found in interface settings | ✅ | not found in interface settings | no wallpaper option found |
| Swipe back to the chat list | ✅ | ❌ | ❌ | ❌ | not found; list swipe archives a chat | ✅ adaptive swipe-back transition |
| **Messaging** |  |  |  |  |  |  |
| Replies (XMPP: XEP-0461) | ✅ swipe to reply | ❌ | ✅ | ✅ | ❌ no reply-reference implementation found | ✅ |
| Reactions | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Edit messages | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ correction flow |
| Delete for everyone (XMPP: XEP-0424) | ✅ | ❌ | ✅ | ✅ | ✅ retraction | ◐ receives retractions; outgoing UI not established |
| Stickers | ✅ (sticker packs: import and sharing implemented) | ❌ | ✅ | ✅ | no native sticker feature found | ◐ metadata exists; native pack picker not found |
| Voice messages | ✅ hold to record | ✅ | ✅ | ✅ | ✅ | no built-in voice recorder found; audio files supported |
| Location sharing | ✅ | ✅ | ✅ | ✅ | ✅ static location | no XMPP location composer found |
| Text styling (XMPP: XEP-0393) | ✅ plus a formatting menu | ✅ StylingHelper | ✅ plus incoming rich text | ✅ | ✅ StylingHelper | no XEP-0393 formatter found |
| Stories / status posts | ❌ | ❌ | ❌ | ✅ | no story publisher found | no story publisher found |
| Search in one chat | ✅ | ✅ | ✅ | ✅ | ✅ conversation-scoped SearchActivity | ✅ ChatSearchCubit |
| Search across all chats | ✅ | ✅ | ✅ | ✅ | ✅ SearchActivity | ✅ chats, email, calendar |
| **Calls** |  |  |  |  |  |  |
| Encrypted audio / video calls | ✅ | ✅ | ✅ | ✅ | ✅ Jingle / DTLS-SRTP | planned |
| Tatrak call list / screen redesign | ✅ | ❌ | ❌ | ❌ | N/A Tatrak-specific design | N/A Tatrak-specific design |
| SMS / phone calls through a gateway | ◐ (via commands, legacy screen) | ❌ | ✅ | ◐ (via commands) | no dedicated gateway UI found | no dedicated telephony gateway flow found |
| **Encryption and security** |  |  |  |  |  |  |
| OMEMO | ✅ | ✅ | ✅ | ✅ (legacy plus custom `urn:monocles:omemo-pq:1`; not interoperable standard OMEMO 2) | ✅ | ✅ moxxmpp / omemo_dart |
| OpenPGP | ✅ | ✅ | ✅ | ✅ | ✅ legacy OpenKeychain flow | ◐ email encryption; no XMPP OpenPGP flow found |
| OTR | ❌ | ❌ | ❌ | ✅ | ❌ no OTR implementation found | ❌ no OTR implementation found |
| Post-quantum OMEMO (experimental) | ❌ | ❌ | ❌ | ✅ (not interoperable) | ❌ no implementation found | ❌ no implementation found |
| Channel binding required by default | ✅ | ✅ | ❌ default false | ❌ | ❌ default false | ❌ no PLUS/channel-binding mechanism in pinned moxxmpp |
| DANE / DNSSEC certificate checks | ❌ | ❌ | ❌ | ✅ | ❌ no TLSA certificate verifier found | ❌ no TLSA verifier found |
| Encrypted local database | ✅ SQLCipher | ❌ SQLite | ❌ platform SQLite | ✅ SQLCipher | ❌ SQLite | ✅ SQLCipher |
| Encrypted local attachments | ✅ incl. voice, avatars, wallpapers, stickers | ❌ inspected path | ❌ | ❌ | ❌ inspected path | ❌ |
| Separate database password | planned | ❌ | ❌ no encrypted DB key | ✅ optional startup entry | ❌ | no user-set DB password flow found; random stored secret |
| Database key protection | random key wrapped by the Android Keystore | no DB key | no DB key | Tink + Keystore; HMAC binding; optional Argon2id | no DB key | secure storage; plugin internals unchecked |
| Database in Android cloud backup / device transfer | disabled / disabled | ✅ / ✅ inspected rules | ✅ / ✅ inspected rules | disabled / disabled | ✅ / ✅ | disabled / disabled |
| External service integrations (conditional) | fewer than Conversations | few | server; discovery service and media integrations on use | more (stickers, maps, account service) | prav.app registration; discovery service on use | configured XMPP/email; first-party push; integrations on use |
| Protocol and crypto code changed vs. Conversations | yes; storage encryption I/O, backup, retraction and setup changes | – | yes | yes | yes; Prav/Quicksy setup and retraction changes | N/A |
| **Protocol** |  |  |  |  |  |  |
| Advertised XEPs | 72 + replies, retraction, stickers | 72 | 63 in DOAP (not runtime capabilities) | 112 | not enumerated in prav.doap | no DOAP XEP inventory found |
| Multiple accounts | ✅ | ✅ | ✅ | ✅ | ◐ flavour-dependent; Conversations flavour has account management | ◐ registered-account switching; concurrency not established |
| Group chats / channels | ✅ | ✅ | ✅ | ✅ | ✅ MUC | ✅ groups |
| UnifiedPush | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ no UnifiedPush integration found; own push |
| Works with any XMPP server | ✅ | ✅ | ✅ | ✅ | XMPP federation | XMPP federation |

The comparison describes inspected Android source snapshots and project READMEs, not a
complete hands-on feature audit. Versions and repository links are listed in
the project's reference-source notes.
Cheogram and the other reference snapshots were inspected directly for the previously unknown
cells. “Not found” means no implementation was located in the scoped source/UI review; it is
not a device-tested absence. Conditional integrations do not imply traffic on every launch.
The evidence and limits record source paths,
feature flags and unresolved boundaries. Gesture smoothness was not measured.
SimpleX feature claims are also listed in its inspected README; Axichat lists calls as planned.
See the
[XEP comparison](../knowledge/protocol/xep-support.md) for the original XMPP review.

Local database encryption protects text, metadata and keys stored **inside the database**.
It does not automatically encrypt separate photos, videos, documents or thumbnails. The
negative attachment entries mean that no at-rest encryption layer for attachments was found in
the inspected source. Signal and Molly
have a separate attachment-encryption layer; SimpleX uses per-file keys stored in its database
and supports local video encryption. SimpleX documents its
[local file encryption](https://simplex.chat/privacy/).

Element X's database result covers its Android encrypted-db module, not all Matrix Rust SDK
storage. Temporary files, exported gallery copies, recovery, and complete cache coverage have
not been tested for the other apps. Android's own device storage encryption is a separate layer.
Tatrak's database and attachment encryption are described in
[database encryption](../knowledge/protocol/database-encryption.md) and
[attachment encryption](../knowledge/protocol/attachment-encryption.md).


