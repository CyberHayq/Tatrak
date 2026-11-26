# Tatrak regression catalogue

This catalogue complements the twelve executable pytest cases described in [TEST_CASES.md](TEST_CASES.md).
Every case below is specified for future automation or manual execution; none is currently implemented
as a pytest test. They are not counted as passing or skipped automated coverage.

P0: blocks essential messaging, access, or data integrity. P1: important release regression.
Run P0 cases before release, then the P1 cases relevant to changed functionality.

## Shared test fixtures

- Use dedicated, disposable accounts and generated media. English UI for current selectors.
- A and B are different accounts on two clients. C is a separate contact used to detect wrong-recipient actions.
- Each case gets unique message tokens and records its initial account/chat/settings state.
- Use a controlled XMPP server with the required feature advertised. Record server support for receipts,
  MAM, corrections, retractions, MUC and upload; an unsupported capability is a precondition failure,
  not a product pass.
- Restore network state, notification settings, account enablement, language and theme after each case.
- Destructive cases use their own disposable conversation/account and explicit confirmation. Never reset
  a shared installation or delete unrelated test history as cleanup.

## Authentication and connectivity

| ID | Priority | Additional preconditions | Actions | Expected result |
| --- | --- | --- | --- | --- |
| TC-AUTH-002 | P0 | Initial-state login device; a known valid account | Submit its JID with a deliberately incorrect password | Explicit authentication error; no authenticated session or access to that account's chats |
| TC-AUTH-003 | P1 | Initial-state login device | Submit a malformed address, then an empty password | Field-specific validation; no authenticated session; form remains editable |
| TC-AUTH-004 | P1 | Server with an intentionally unexpected certificate | Attempt sign-in and reject the certificate prompt | Connection is not silently trusted; no chat exchange; user can cancel or correct the server |
| TC-NET-001 | P0 | Both clients online; ability to disconnect only A while keeping Appium/ADB reachable | Disconnect A, compose a unique message, restore connectivity | B eventually receives one copy; A leaves pending state; no duplicate after another reconnect |
| TC-NET-002 | P0 | A offline; B online | Send three unique messages from B, reconnect A | A receives all three once, in the sender's order, in the correct chat |
| TC-NET-003 | P1 | Controlled server availability | Stop server, observe disconnected state, restart server | App remains usable and reconnects; a fresh round-trip succeeds without re-entering credentials |
| TC-HIST-002 | P0 | A has received a known token and fully loaded its history | Disconnect A, terminate and relaunch it, reopen the chat | Previously loaded text is readable offline without another sign-in; then restore network |

Network controls must retain the automation channel. On physical devices, switching all radios off may
also disconnect Appium; prefer emulator-specific or server-side controls with a recorded restore step.

## Messages, navigation and search

| ID | Priority | Additional preconditions | Actions | Expected result |
| --- | --- | --- | --- | --- |
| TC-MSG-002 | P1 | Two distinct originals from B visible in A's chat | Reply specifically to the first original with a unique token | B sees the reply and a quote linked to the intended original; tapping the quote navigates correctly |
| TC-MSG-004 | P0 | Disposable chat; retraction supported by both endpoints | Send and receive a token, choose Delete for everyone and confirm | That token is replaced/removed on both clients according to the deletion UI and stays deleted after restart |
| TC-MSG-005 | P1 | Dedicated conversation with known history | Open a destructive delete dialog and choose Cancel | Original message/chat remains; no peer deletion event; sending still works |
| TC-MSG-006 | P1 | Generated plain-text history | Select two intended messages, copy, then exit selection | Copied text belongs to exactly those messages in conversation order; selection controls close |
| TC-MSG-007 | P1 | A has separate chats with B and C | Forward B's unique message to C | C receives the intended content; B receives no additional forwarded copy; destination UI identifies C |
| TC-SEARCH-001 | P1 | Different unique tokens in B and C chats | Search a full token using Search this chat | Result belongs to the active chat; opening it highlights/navigates to the correct message |
| TC-SEARCH-002 | P1 | Tokens in two different chats | Search across all chats; open each result | Results identify the correct conversation; opening never enters the wrong account/chat |
| TC-SEARCH-003 | P1 | Searchable history exists | Search a unique absent token, clear it, then search a known one | Clear empty state for the absent token; results recover after editing the query |
| TC-DRAFT-002 | P1 | A has chats with B and C, both with empty composers | Type a draft in B, switch to C, return to B | C has no B draft; B restores its own draft; cleanup removes only the generated draft |
| TC-ARCHIVE-001 | P1 | Dedicated unarchived B chat | Archive B, open Archive, unarchive B | Chat moves between the intended lists; history remains; it returns to the main list |
| TC-ARCHIVE-002 | P1 | Archive undo is visible | Archive B and immediately use Undo | B returns to its original location with history intact; no duplicate row |

Message deletion is tested separately from editing. Visible deletion is not proof that copies have
been erased from another client's backups or server storage.

## Media, permissions and notifications

| ID | Priority | Additional preconditions | Actions | Expected result |
| --- | --- | --- | --- | --- |
| TC-MEDIA-001 | P1 | Generated PNG fixture; HTTP upload available | Attach PNG on A, send, download and open on B | Expected image is visible and opens successfully; dimensions/content match the fixture |
| TC-MEDIA-002 | P1 | Dedicated device; microphone permission initially denied | Attempt recording, deny permission, then grant it through the supported UI and retry | No crash or phantom recording on denial; later recording sends and plays on B |
| TC-MEDIA-003 | P1 | Large generated fixture; cancellable upload | Start upload, cancel while pending, then send another message | Transfer stops; cancellation is represented correctly; later messages remain usable |
| TC-PERM-001 | P1 | Android version with notification runtime permission | Deny notifications, receive a message in foreground, later grant permission | Foreground messaging works after denial; later background notifications follow granted permission |
| TC-NOTIFY-001 | P1 | Notifications granted; unmuted chat; A backgrounded | B sends a unique token; tap its notification on A | Notification arrives and opens the intended account and chat with that message |
| TC-NOTIFY-002 | P1 | Separate muted B and unmuted C chats | Receive messages from B and C, then unmute B | Notification behavior follows the mute setting; message history is retained; unmuting restores normal behavior |

Image rendering, microphone capture, playback and notification behavior need device-level checks;
a transport success alone does not satisfy these cases.

## Accounts, groups and presentation

| ID | Priority | Additional preconditions | Actions | Expected result |
| --- | --- | --- | --- | --- |
| TC-ACCOUNT-001 | P0 | A has two enabled accounts with distinct contacts and tokens | Switch accounts and send a different token from each | Correct account owns each chat and sends each message; no cross-account recipient/history leakage |
| TC-ACCOUNT-002 | P1 | Two test accounts; one can be disabled | Disable one account, select it, attempt New chat/share; then re-enable | Disabled account's existing history remains visible; send/share choices follow enabled accounts; re-enabling restores messaging |
| TC-GROUP-001 | P1 | Dedicated MUC; A and B members | A posts a token, B replies; restart A and reopen | Correct room and sender names; both messages remain; one-to-one chats are unaffected |
| TC-GROUP-002 | P1 | Dedicated room and unique participant nickname | Mention B using nickname completion and send | Intended participant is mentioned; displayed text and navigation identify B correctly |
| TC-UI-001 | P1 | Known chat with received/sent text | Switch light/dark theme and a supported app language, then return | Labels update, content remains readable, account/chat state persists; restore initial settings |
| TC-UI-002 | P1 | Known chat; emulator supports rotation and large text | Rotate with keyboard open, then enlarge font | Composer and actions remain reachable; no draft loss, duplicate send or overlapping critical controls |

## Encryption and backup workflows

| ID | Priority | Additional preconditions | Actions | Expected result |
| --- | --- | --- | --- | --- |
| TC-OMEMO-001 | P0 | Both clients support OMEMO and test keys are verified | Exchange unique text and generated attachment in the configured encrypted chat | Both endpoints decrypt expected content; UI reports expected encryption; no silent fallback to clear text |
| TC-OMEMO-002 | P0 | A controlled key change or untrusted-device fixture | Change B's test device/key, then attempt sending from A | Existing trust policy is respected; any required warning/verification is visible; no silent policy bypass |
| TC-BACKUP-001 | P0 | Dedicated source/restore installations and generated history/media | Export a backup, restore to the separate installation, inspect history and attachments | Expected accounts/history/media return according to preview; a fresh post-restore message round-trip succeeds |
| TC-BACKUP-002 | P0 | Valid generated backup; separate empty restore installation | Attempt restore with wrong password and then a corrupted copy | Clear error; no partially imported account/history; valid backup can still be restored afterward |

The existing plain-text server helper cannot validate OMEMO. These are behavioral checks, not a
cryptographic audit. Backup cases must use the formats and omissions advertised by the restore preview.

## Execution records

For each executed case, record the app commit/APK version, Android API, server capabilities, fixture
IDs, actual result and evidence path. Mark it passed, failed, blocked by environment, or not run.
Record a product bug only after separating fixture/setup problems from application behavior.
