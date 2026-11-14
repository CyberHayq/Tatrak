---
type: Roadmap
title: Tatrak roadmap
description: Current implementation status, validation priorities and exploratory ideas for Tatrak.
tags: [roadmap, planning]
status: stable
generated: { by: codex, at: 2026-11-02T00:00:00Z }
sources:
  - id: plan
    resource: ~/.claude/plans/ticklish-weaving-boole.md
    title: Approved implementation plan (local file, outside the repository)
  - id: worktree
    resource: git status of the repository working tree on 2026-10-29
    title: Staged and modified files observed when this concept was written
---

# Stages

| # | Stage | Status | Scope |
|---|-------|--------|-------|
| 0 | Setup | Done | Rebrand (`am.cyberhayq.chat`, "Tatrak"), Kotlin + Compose in Gradle, Robolectric/Roborazzi screenshot tests. See [build setup](/build/build-setup.md). |
| 1 | State layer + chat list | Done | [SnapshotSource](/architecture/snapshot-source.md); Compose [chat list](/architecture/chat-list.md) (`ChatListFragment`) replaces `ConversationsOverviewFragment`. |
| 2 | Chat list extras | Done (screenshot-verified) | Telegram-style archive (`tatrak_archived_at`), swipe to archive, built-in folder tabs (All / Personal / Groups / Unread), multi-select actions, edge-to-edge status bar, two-pane chat suggestion fix (`ChatSuggestions`). |
| 3 | Chat screen | Done v1 (screenshot-verified) | Compose [chat screen](/architecture/chat-screen.md) (`ChatFragment`) with "classic view" fallback to `ConversationFragment`; verbatim extraction of the OMEMO trust check into `TrustKeysGate`. |
| 4 | Input | Done (emulator-verified) | Hold-to-record voice messages, attachment sheet, emoji panel. |
| 5 | Protocol | Replies, retractions, stickers and sticker-pack import/sharing implemented; validation remains | XEP-0461, XEP-0424 and XEP-0447 support. See [XEP support](/protocol/xep-support.md); real third-party pack import remains unchecked. |
| 6 | Polish | In progress: 6a accents, 6b wallpapers, 6c settings, 6d profile done; 6e swipe-back working on API 31 and 37, gating checks left | Profiles, settings, swipe-back navigation, themes, chat wallpapers. |
| 7 | Remaining screens and fixes | Implemented; full end-to-end validation pending | Historical scope in backlog; current checks and priorities below. |

Implementation status describes the current working tree; validation status is listed separately below. Commits are signed by the owner with a hardware key.

# Stage 2 details

- **Archive.** New `Conversation.Attributes` field `tatrak_archived_at` (epoch millis)
  with `getTatrakArchivedAt()` / `setTatrakArchivedAt()`. Unlike upstream
  `XmppConnectionService.archiveConversation`, which ends the chat and leaves MUCs,
  archived chats stay active. Per the plan, an unmuted chat with a new incoming message
  newer than `archived_at` returns to the main list, as in Telegram.[^plan]
- **Tabs.** Built-in folders only; user-defined folders are deferred.[^plan]
- **Multi-select.** Pin/unpin, mute, mark read, archive, delete (delete = upstream
  `archiveConversation` behind a confirmation). "Mark as unread" is not offered because
  the core has no such operation.[^plan]
- **Edge-to-edge.** `ConversationsActivity` calls
  `WindowCompat.setDecorFitsSystemWindows(window, false)`; Compose screens consume insets
  themselves and the legacy `ConversationFragment` pads its root by system bars, cutout and IME.

# Stage 3 details

See [chat screen](/architecture/chat-screen.md).

# Current status and priorities

Implementation is largely complete; successful builds and Roborazzi checks do not mean every flow is verified. Available API 31 and API 37 emulators support end-to-end checks, and real-device performance still needs measurement.

Current priorities:

- Finish remaining UI and gesture polish, including scoped gaps in risks.
- Complete end-to-end checks on both available emulators, including XMPP flows and two-client cases.
- Measure performance on real hardware with the benchmark build.

Exploratory ideas, not accepted implementation commitments:

- Local encryption of media at rest (phase 3) is implemented and checked on the emulators (2026-11-13): attachments, voice and camera captures, wallpapers, sticker packs and avatars are stored in the encrypted format (design, review 2); the speed budgets still need the real-phone benchmark run. Next encryption step: the password mode (phase 2), which must also wrap the media keys carried in Tatrak backups. Passive encryption of history and UnifiedPush databases is implemented in the working tree with SQLCipher and an Android Keystore-wrapped random key; see [implementation and verification details](/build/local-db-encryption.md). This is not a released or hardware-certified claim.
- Offline last-seen behavior; see backlog.
- Standard OMEMO 2 is planned (owner, 2026-11-13: listed as "Planned" in the README); a post-quantum OMEMO profile (such as monocles' custom PQ profile) remains under assessment; no post-quantum session implementation is included. See backlog and [OMEMO 2 feasibility](/protocol/omemo2-feasibility.md).

# Compose coverage: 14/53 screens

Owner, 2026-11-13: track the move to Compose as X/Y, X screens drawn in Compose out of Y in
total; update the count whenever a screen moves. A screen is an activity, a main fragment of
`ConversationsActivity`, or a settings page that the user sees in the `conversations` /
`conversationsFree` flavours. Not counted: dialogs, the Quicksy-only and benchmark activities,
the third-party `CropImageActivity`, activities without UI (`ConversationActivity`,
`YuriLauncherActivity`), the replaced `MainSettingsFragment`, and the legacy `ConversationFragment`
kept as the chat's "classic view" fallback. An XML-only restyle (call screen, onboarding
screens) does not count as Compose.

In Compose (14): chat list, chat, settings home, Appearance, profile (`ProfileActivity`), new chat
(`StartConversationActivity`), message search, shared media (`MediaBrowserActivity`), media viewer,
accounts (`ManageAccountActivity`), share target (`ShareWithActivity`), call list, first-run
security choice (`SetupModeActivity`), group participants (`MucUsersActivity`, 2026-11-13).

Classic (39):
- `AddReactionActivity`: the Compose chat no longer opens it or the classic reaction dialog
  (an emoji sheet since 2026-11-13); only the classic chat view still does.
- Onboarding, Compose redesign postponed, XML restyle done 2026-11-13: `WelcomeActivity`,
  `MagicCreateActivity`, `PickServerActivity`, `EasyOnboardingInviteActivity`,
  `EditAccountActivity`, `ImportBackupActivity`.
- Kept classic on purpose (security): `TrustKeysActivity`, `MemorizingActivity`.
- Others: `RtpSessionActivity` (XML restyled), `ContactDetailsActivity`,
  `ConferenceDetailsActivity`, `BlocklistActivity`, `ChannelDiscoveryActivity`,
  `ChooseContactActivity`, `ViewProfilePictureActivity`, `ShowLocationActivity`,
  `ShareLocationActivity`, `EditHistoryActivity`, `PublishProfilePictureActivity`,
  `PublishGroupChatProfilePictureActivity`, `ChooseAccountForProfilePictureActivity`,
  `ScanQrCodeActivity`, `AboutActivity`, `ChangePasswordActivity`, `RecordingActivity`,
  `ShareViaAccountActivity`, `ShortcutActivity`, `DatabaseStorageActivity` (views built in code).
- Settings pages (10): Attachments, Availability, Backup, Connection, Interface, Interface
  bubbles, Notifications, Privacy, Security, UnifiedPush.

[^plan]: Approved implementation plan
