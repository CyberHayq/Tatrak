# Changelog

## Unreleased

### Interface
- "Saved messages" is always reachable: a permanent entry in the side menu (after Calls) and at
  the top of the Profile tab opens the chat with yourself, created if needed; with several
  accounts under "All accounts" it asks which account. Each newly added account gets this chat
  pinned once, at its first sign-in; unpinned or deleted, it is not brought back (public issue
  #6).

### Upstream
- Tatrak now includes the fixes of Conversations 2.21.1-2.21.5 (OMEMO, TLS trust, Jingle file
  transfer and SOCKS5, group chat join, backup import, partial downloads in the cache).
- Login with OAuth: on a server that asks for it, the account screen opens the server's login page
  in the browser and comes back to Tatrak.
- New opt-in setting "Use Google push as fallback" (Settings → Privacy), off by default and
  available only with Google Play services: wakes the app through Google's push service if
  battery saving stops the background connection.
- An "always" decision to trust a server certificate is now kept on the first connection too.

### Developer tooling
- Add the Appium/pytest QA suite under `tools/qa/`: nine regression cases, three smoke cases,
  fixture helpers, pinned test-tool dependencies, run instructions and recorded emulator results.
- QA suite: Appium 3.1.0 -> 3.9.1 for the security fixes in its dependency tree.

### Fixes
- Calls: fixes for hanging up while the other side answers, for calls placed or cancelled during
  a reconnect, and for the call screen keeping the display on.
- Photo viewer: panning a zoomed photo no longer jumps to the next or previous photo when the
  pan reaches the photo's edge; the photo stops there, and a new swipe from the edge turns the
  page.
- Message previews in the chat list and notifications no longer fail on some sender names.
- In-app update: if the system refuses to start the installation (a device policy, a revoked
  permission to install apps), Tatrak says the update failed instead of closing.
- Backup import refuses a backup file with anything after its contents.
- In-app update on Android 9: a genuine update was always refused as "wrong signature", because
  Android 9 reads a downloaded file's signature only when asked the old way; it is now asked both
  ways.
- In-app update on Android 15 and later: if you left Tatrak while the update was being prepared,
  the system's confirmation never appeared and the dialog kept waiting. The installer's answer now
  comes to Tatrak in the background and the confirmation opens when you return.
- In-app update: a failed installation no longer leaves an unfinished installer session behind
  (enough of them made later attempts fail), and more installer and connection errors, a missing
  system screen for allowing installs, or a missing confirmation now show "Update failed" instead
  of closing Tatrak or waiting forever.
- Work that finishes after a delay (a picked photo, file, contact or location, a camera shot, the
  "Send N files?" question of a share, a send waiting for the key-trust screen) could go to another
  chat if one opened meanwhile, for example from a notification in the two-pane layout. It now
  belongs to the chat it started in; when another chat is open by then, it is not sent and a
  notice says so. The same holds for files waiting in the composer when Android restarts the app,
  and for a camera, video or contact request answered after a permission question. Tapping the
  same picker again, or sending another message that needs the key check, while the earlier one
  has not answered yet now says so instead of replacing it.
- "Clear chat history" names the chat, closes when a notification opens another chat in its
  place, and never clears a chat other than the one on screen, on both chat screens (the classic
  one shows OpenPGP chats).
- Rotating the media browser while it counts the files to delete no longer crashes it.
- Deleting an account also deletes the files of its closed chats; before, only the files of the
  chats still in the list went.

## 0.5.1

### Fixes
- Restoring a backup opened from another app no longer reads a file that belongs to Tatrak
  itself: a link into Tatrak's own storage or file providers is refused as "not a backup file".
  Backups opened from a file manager or chosen in Tatrak are unaffected.
- A long press on a chat's avatar opened the chat preview, and moving the same finger to the right
  then pulled out the side menu (in the archive: went back to the chat list). A finger held still
  until the long press now belongs to the preview or the selection, not to the swipe.
- Dynamic colors: if the phone's wallpaper palette pairs a light outgoing bubble with light text,
  or a dark one with dark text (seen on some phones, issue #4), the text, time and icons in your
  own messages switch to a colour that can be read. Palettes that were already readable are
  unchanged.

### Interface
- Dark theme: in Settings, the Settings tab, contact and group profiles, the gaps between the groups
  of rows and the empty space below them are now darker than the rows, as in Telegram, instead of
  lighter; the screen reads as one piece.

## 0.5.0

### Fixes
- A file shared into Tatrak while the app had to redraw itself (for example right after a change of
  the style or accent) opened the chat without asking to send the file; the question now comes back.
- After a full restore of a monocles chat backup, encrypted messages to a contact whose OMEMO key
  the restore left out could not be sent ("unable to send encrypted messages"). The restore now
  leaves out all old OMEMO sessions of such a contact, and unreadable or oversized ones (the
  contact's other keys and their trust stay); Tatrak sets up new sessions when the contact's
  devices and their keys are available. Opening the chat fetches the device list of a contact
  that has trusted keys but no known device. A key the restore left out has to be verified anew,
  as the restore preview says.

### Interface
- Floating bars: the folder tabs and the bottom tab bar are smaller, in Telegram 13's sizes (a 36 dp
  folder bar with 14 sp titles, a 56 dp tab bar with 12 sp labels).
- The chat preview (long press on a chat's avatar) opens into the chat as in Telegram: a tap makes
  the card the full chat at once with a short bounce, and the chat stays where the card was
  scrolled. The dim behind the card is Telegram's lighter one. With the list and a chat side by
  side (large screens) there is no preview: a long press on the avatar selects the chat, as in
  Telegram on tablets.
- Account switcher: a disabled account stays in the list, greyed out with "Disabled" under its
  name, and "All accounts" shows whenever there is more than one account. Choosing a disabled
  account shows its chats, contacts, calls and profile; New chat, adding a contact and sharing
  then offer the enabled accounts, since a disabled account cannot send. A contact of a disabled
  account opens its existing chat or its details, and sharing never offers a disabled account's
  chat. The switcher's list scrolls when it does not fit above the bar.

### Translations
- Armenian: contacts, contact list, contact details, address book and server use native
  terms (կապի անձ, Կապեր, Կապերի ցանկ, Կապի տվյալներ, Հասցեագիրք, սպասարկիչ), as do interface,
  automatic and airplane mode (Միջերես, ինքնաշխատ, Թռիչքային դրություն), in both orthographies;
  the traditional orthography no longer mixes in reformed spellings.

## 0.4.0

Everything in 0.4.0-beta.1 below, and:

### Translations
- Added Eastern Armenian in Soviet and traditional orthographies.

### Interface
- Settings > Language chooses the app language apart from the system one, on every Android
  version (Android 13+ keeps it in sync with the system's per-app language). The XMPP service's
  notifications (messages, calls, failed deliveries, the foreground notification) follow it too;
  their channel names change at once on Android 8-12 and at the next start of the service on
  Android 13+ or after a change of the system language. On Android 8-12 a few other texts (backup import progress, storage
  recovery, toasts from background work) stay in the system language.
- Chats, Contacts, Calls and Profile are tabs under the bottom bar: a tap slides to the tab, the
  bar stays in place, Back returns to Chats (as in Telegram).
- A chat opens and closes as in Telegram: a short fade with a small shift; the list stays still.
  The swipe back still drags the whole chat under the finger.
- Account switcher: a long press on Profile in the floating bar shows the accounts with their
  unread chats; choosing one shows only that account's chats, contacts, calls and profile, until
  "All accounts" is chosen. Notifications still come from every account.
- A tap inside the chat preview (long press on a chat's avatar) opens the chat without the list
  flashing between the two.

### Chat
- A reply to a photo or video shows its thumbnail, in the quote and in the reply bar above the
  text field.
- A tap on a participant's avatar in a group, or on a row of the participants list, opens the
  participant menu (profile, write to them); a long press mentions them.
- "Send private message" from the participant menu stays in the chat: a bar over the text field
  names the participant; its X goes back to writing to the group and discards what was written for
  the participant. A private message is text only (with a reply); files, stickers and voice
  messages go to the group. Replying to a private message answers it privately. Leaving the chat
  ends the private message.

### Updates
- "Beta versions" in Settings → Updates (off by default): the update check also offers GitHub
  pre-releases, and a newer beta is offered to a beta install (0.4.0-beta.2 after 0.4.0-beta.1).

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
