# Tatrak Android smoke test cases

Status: emulator execution completed on Tatrak 0.5.0, Android 12/API 31. See
[RUN_RESULTS.md](RUN_RESULTS.md) for the final per-case evidence.
Target source: `Tatrak-dev`, commit `16a1fb69d09f1236deec2e1672c0eb5f9c9ceb3b`.

All cases use dedicated test devices, accounts, and generated message tokens. The UI must be English.
See [TESTING.md](TESTING.md) for setup and execution. Tests preserve app data and leave generated messages
in the dedicated test conversations. Never run them against personal accounts.

## TC-AUTH-001 — Sign in with an existing account

Priority: P0. Automated: `tests/test_login.py::test_login_existing_account`.

Preconditions:

- A dedicated device with Tatrak installed, no signed-in account, and the initial setup or welcome screen.
- A valid disposable XMPP account and a reachable server.
- The server certificate is trusted by the device; any required network permissions are provisioned.
- If the security-mode screen appears, choose the mode explicitly using `--setup-mode`.

Steps:

1. Choose the explicitly configured setup mode if needed.
2. Select “I already have an account”.
3. Enter the account address and password; select “Next”.
4. Skip avatar publishing if that specific screen appears.

Expected: the app reaches its conversations/new-message activity and displays the corresponding
navigation. An already signed-in app is not accepted as a successful login test. Unexpected permission,
certificate, and account-error dialogs cause failure and artifact collection.

Limitation: this case asserts successful onboarding navigation; message exchange is checked separately.
Prepare the dedicated login installation again before repeating this case. The suite never clears data.

## TC-MSG-001 — Send a message and receive a reply

Priority: P0. Automated: `tests/test_messaging.py::test_message_round_trip`.

Preconditions:

- Two different devices running Tatrak, signed into different test accounts on the test server.
- Each account has a visible, existing, unarchived one-to-one chat with the other account.
- Each chat has a unique contact display name supplied in the test configuration.
- Each device starts at the Chats list or the corresponding conversation. The target row must be visible.
- First-contact prompts, permissions, and any OMEMO trust setup are already completed.

Steps:

1. Open the configured chat on both devices.
2. Send a generated unique message from device A.
3. Wait for that message bubble on device B.
4. Send a different generated reply from device B.
5. Wait for that reply bubble on device A.

Expected: each token appears as a message on the other device within the configured timeout.
Sender-side composer text alone cannot satisfy the assertion.

Limitations: does not yet assert duplicate counts, receipt states, or encryption properties.
Messages use the existing test chat's encryption configuration; passing does not prove OMEMO security.

## TC-HIST-001 — History survives an application process restart

Priority: P0. Automated: `tests/test_messaging.py::test_history_survives_app_restart`.

Preconditions: same as TC-MSG-001.

Steps:

1. Send a generated unique message from A and confirm it appears on B.
2. Terminate A's application process and launch the app again, preserving app data.
3. Reopen the configured conversation on A.

Expected: the same message bubble is present after restart without signing in again.

Limitation: the network remains available. The test proves history is visible after restart; it does
not distinguish local database recovery from archive re-download. Offline persistence needs its own case.

## Automated regression additions

All additions are defined in `tests/test_regression.py`, marked `regression` and `messaging`.
They use the same two-device preconditions as TC-MSG-001 and have been exercised on API 31 emulators.

| ID | Priority | Steps | Expected result |
| --- | --- | --- | --- |
| TC-TEXT-001 | P1 | Send a unique token with quotes, apostrophes, angle brackets, ampersand and backslash | Peer displays the complete exact message |
| TC-TEXT-002 | P1 | Send Armenian, Cyrillic and Latin text with a unique token | All characters arrive unchanged |
| TC-TEXT-003 | P1 | Send emoji with skin tone, variation selector and family ZWJ sequence | Peer exposes the exact original Unicode sequence |
| TC-TEXT-004 | P1 | Send a unique token with three explicit lines | Line breaks and text survive transport |
| TC-TEXT-005 | P1 | Send a unique token plus 2,048 generated characters | Peer exposes the complete exact text, without truncation |
| TC-HIST-003 | P0 | B sends a unique message to A; A receives it, restarts and reopens the chat | Incoming message remains visible without another sign-in |
| TC-MSG-003 | P1 | Send a unique text, receive it on B, tap A's bubble, choose Edit, verify the original composer value, replace and submit | B displays the updated text; the original bubble text is no longer visible |
| TC-DRAFT-001 | P1 | Starting with an empty composer, type a unique draft, leave the conversation and reopen it | Composer restores the exact draft; cleanup clears only that generated draft and persists the empty state |
| TC-COMPOSER-001 | P1 | Open a chat with an empty composer and no attachment/edit/recording state | Send is not exposed after animations settle |

Text assertions exercise accessibility content, not font/glyph rendering. History checks keep the
network available and do not prove offline database recovery. Editing checks the visible conversation,
not removal from edit history or server storage. The draft case requires an initially empty composer
and preserves any pre-existing draft by failing that precondition before typing.

For the broader prioritized catalogue, including cases not yet automated, see
[REGRESSION_CASES.md](REGRESSION_CASES.md).
