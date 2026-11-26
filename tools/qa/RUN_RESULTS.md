# Tatrak emulator test results

Recorded: 2026-11-25 11:46 UTC.

**Final result: 12 passed, 0 failed, 0 errors, 0 skipped.**

The result combines one clean-install login case, two messaging smoke cases and nine regression cases.
Combined pytest suite time: 173.52 seconds; SDK downloads and environment setup are excluded.

## Environment

- Tatrak release APK: 0.5.0, versionCode 5000503, x86_64; minimum API 26, target API 37.
- APK SHA-256: `0027694ee8d041a07c6b91dc995e4dd8ef66df8d79fd0b3d92546b85d9e63907`.
- Release signing certificate SHA-256: `3bae017d66664f88502ab51a6868621c1f93fd47004b3666796d3cd9a6946d9b`.
- Android 12/API 31 default x86_64 image; Emulator 37.3.10 with KVM and `-gpu software -feature -Vulkan`.
- Messaging devices: `Tatrak_QA_A` / `emulator-5554`, `Tatrak_QA_B` / `emulator-5556`.
- First-login device: separate fresh `Tatrak_QA_Login` / `emulator-5558`; stopped after login validation.
- English UI, Easy first-run mode, window animations disabled for messaging fixtures.
- Appium 3.1.0, UiAutomator2 5.0.6, Python 3.13.13, pytest 9.2.0.
- Prosody 13.0.1 in dedicated `tatrak-qa-prosody`; disposable tester, friend and qa-login accounts.

## Executed cases

| Test | Result |
| --- | --- |
| `tests.test_login::test_login_existing_account` | Passed |
| `tests.test_messaging::test_message_round_trip` | Passed |
| `tests.test_messaging::test_history_survives_app_restart` | Passed |
| `tests.test_regression::test_message_content_preserved[punctuation]` | Passed |
| `tests.test_regression::test_message_content_preserved[armenian-cyrillic]` | Passed |
| `tests.test_regression::test_message_content_preserved[emoji-sequences]` | Passed |
| `tests.test_regression::test_message_content_preserved[multiline]` | Passed |
| `tests.test_regression::test_message_content_preserved[long-text-2048]` | Passed |
| `tests.test_regression::test_received_history_survives_app_restart` | Passed |
| `tests.test_regression::test_edit_message_updates_peer` | Passed |
| `tests.test_regression::test_draft_survives_leaving_conversation` | Passed |
| `tests.test_regression::test_empty_composer_has_no_send_action` | Passed |

## Evidence

Evidence paths below refer to local files or separately shared CI artifacts; raw artifacts are excluded from Git.

- Combined JUnit XML: `artifacts/emulator-run/final-results.xml`
- Clean login log: `artifacts/emulator-run/login-clean.log`
- Final smoke log: `artifacts/emulator-run/smoke-final.log`
- Final regression log: `artifacts/emulator-run/regression-final.log`
- Primary emulator screenshot: `artifacts/emulator-run/chat-A.png`

## Setup and harness corrections

Initial SwiftShader/Vulkan emulator startup crashed before Android booted; software rendering with Vulkan disabled booted successfully.
Early login attempts reached the app but failed the test's avatar/main-screen selectors. The harness now scopes Skip to the avatar activity and uses the New chat accessibility action as the list sentinel.
Escaped UiSelector string searches failed for newlines/Unicode while the actual native text remained intact. Message assertions now compare exact TextView values, excluding unsent composer text.
Local certificate trust during clean login was bootstrapped using an explicit SHA-256 pin checked against the certificate dialog. Unknown certificates are not accepted by default.
Initial failure logs and artifacts remain available; only the three final XML files named above contribute to the final result.

## Scope and limits

This validates the twelve implemented cases on API 31, the release APK and the recorded fixtures. It is not a complete release certification.
Other Android versions, physical devices, alternate themes/locales, offline recovery, media, notification flows, multi-account isolation, backup restore and the remaining regression catalogue were not exercised.
History checks kept the network available. Text checks compare content, not font/glyph rendering. Encryption properties and receipt/duplicate guarantees are not asserted by this suite.
The Android application source was not modified. Test harness changes were checked by a read-only review and real emulator reruns.

## Repeat the two-device suite

```sh
source scripts/env.sh
.venv/bin/python -m pytest --run-e2e -m messaging \
  --primary-udid emulator-5554 --peer-udid emulator-5556 \
  --primary-contact friend --peer-contact tester -v
```

This repeats eleven messaging/regression cases. Login requires a fresh dedicated installation and is run separately; see [TESTING.md](TESTING.md).
