# Running the Tatrak pytest smoke suite

This directory contains pytest + Appium tests for the native Android app in this repository.
Run all commands below from `tools/qa/`. Python dependencies belong to this QA environment. The Android project's Gradle
dependencies and application source are unchanged.

## Current validation status

The suite has been executed against Tatrak 0.5.0 on Android 12/API 31 x86_64 emulators.
Runtime validation corrected the main-list sentinel, the avatar Skip selector, and message matching
for Unicode/newlines. Message bodies are compared against native TextView values, excluding the
EditText composer. See [RUN_RESULTS.md](RUN_RESULTS.md) for actual results and evidence.
Other Android versions, themes, locales and the unimplemented regression catalogue remain untested.

## Re-run with prepared fixtures

From `tools/qa/`, with the existing Appium server and emulators running:

```sh
source scripts/env.sh
adb devices -l
.venv/bin/python -m pytest --run-e2e -m messaging \
  --primary-udid emulator-5554 --peer-udid emulator-5556 \
  --primary-contact friend --peer-contact tester \
  --junitxml artifacts/messaging-results.xml -v
```

This runs all eleven two-device tests. The separate login test needs an initial-state installation.
After stopping the environment, start the existing server with `podman start tatrak-qa-prosody`,
start A/B with `bash scripts/start-emulators.sh`, and run Appium in another terminal after sourcing
`scripts/env.sh`: `appium --address 127.0.0.1 --port 4723`.

Verified locally: all twelve cases collect (including nine marked `regression`), the default run skips
all twelve device tests, dependency
consistency passes `pip check`, Python files compile, and an explicit E2E run without serials fails
with `Missing --primary-udid` before opening a device session.

## Install test tools

Use Python 3.10 or newer:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-test.lock
```

`requirements-test.lock` records the installed Python dependency versions.
`requirements-test.txt` lists the direct dependency ranges for a deliberate future refresh.

Install an Appium server and its UiAutomator2 driver using the
[official quickstart](https://appium.io/docs/en/latest/quickstart/).
Match Node.js and Android SDK versions to the chosen Appium/driver requirements.
An existing Tatrak APK must be installed on each selected device.

```sh
mkdir -p .tools/appium
cp tooling/appium/package.json tooling/appium/package-lock.json .tools/appium/
npm ci --prefix .tools/appium --no-audit --no-fund
export APPIUM_HOME="$PWD/.tools/appium-home"
export PATH="$PWD/.tools/appium/node_modules/.bin:$PATH"
appium driver install uiautomator2@5.0.6
appium driver doctor uiautomator2
appium --address 127.0.0.1 --port 4723
```

Start Appium in its own terminal. The default server URL has no `/wd/hub` suffix.
Keep SDK `platform-tools` on PATH so `adb` is available. The suite uses explicit serials and
different UiAutomator2 system ports for its two simultaneous sessions. Run this suite serially;
pytest-xdist is not configured.

## Prepare the test environment

1. Start the test Prosody server using the commands below.
   The container definitions are in `../test-server/`.
2. Start two dedicated Android emulators, set their UI/app language to English, and install Tatrak.
3. Sign A and B into different disposable test accounts. The server creates
   `tester@10.0.2.2` and `friend@10.0.2.2`, both with password `secret123`, for emulators;
   physical devices need a reachable server address and corresponding server configuration.
4. Complete required permissions and trust the expected test certificate manually or use the explicit
   pinned-certificate option described below for the isolated local server. Configure
   OMEMO trust on both clients if using an encrypted chat. The suite never accepts arbitrary TLS dialogs.
5. Create a mutual one-to-one chat, exchange one setup message, and use unique display names.
6. Leave both apps on the Chats list with the target row visible or inside that mutual conversation.
7. Run `adb devices -l` and copy the exact device serials into the following command.

From `tools/qa/`, create the dedicated server once:

```sh
podman build -t tatrak-qa-prosody ../test-server
podman volume create tatrak-qa-prosody-data
podman run -d --name tatrak-qa-prosody \
  -p 127.0.0.1:5222:5222 -p 127.0.0.1:5281:5281 \
  -v tatrak-qa-prosody-data:/var/lib/prosody tatrak-qa-prosody
```

For later runs, use `podman start tatrak-qa-prosody`. The named volume retains the test
accounts, history, and self-signed certificate. These emulator-only defaults are not a production
configuration. `scripts/env.sh`, `scripts/create-avds.py` and `scripts/start-emulators.sh` record
the local SDK/AVD setup. Emulator 37.3.10 required `-gpu software -feature -Vulkan` on this host;
the initial SwiftShader/Vulkan startup crashed before Android booted.

The login case uses a separate initial-state installation, or the same dedicated emulator prepared
for that case in a separate run. It changes login state and must not share an already signed-in
installation with the messaging tests in one combined run. No automatic reset, uninstall, or `pm clear`
is performed. Android 17 LAN prompts and server-specific onboarding may require further automation;
unexpected dialogs intentionally fail for inspection.

## Collect tests and run

```sh
# Safe without devices: lists all twelve cases.
.venv/bin/python -m pytest --collect-only -q

# Safe without devices: reports E2E cases as skipped.
.venv/bin/python -m pytest -q

# Original two-device smoke checks. Replace serials and contact names with your test fixtures.
.venv/bin/python -m pytest --run-e2e -m 'messaging and not regression' \
  --primary-udid emulator-5554 --peer-udid emulator-5556 \
  --primary-contact friend --peer-contact tester -v

# Nine additional regression checks using the same two-device fixtures.
.venv/bin/python -m pytest --run-e2e -m regression \
  --primary-udid emulator-5554 --peer-udid emulator-5556 \
  --primary-contact friend --peer-contact tester -v
```

The regression run covers five message payload variants, editing on the peer, incoming history after
restart, draft preservation on navigation and an empty composer's Send action. Start draft/empty-composer
cases with an empty composer and no pending attachment, recording, or edit state. Draft cleanup runs
after failure evidence is captured and persists removal of only the generated draft. Editing requires
ordinary correctable outgoing text in a one-to-one chat; imported special message formats are excluded.
Use `-m messaging` for all eleven two-device cases. Run the initial-state login case separately.

For login, provide `TATRAK_LOGIN_JID` and `TATRAK_LOGIN_PASSWORD` as environment variables using your
shell or CI secret store. Do not put passwords in command-line arguments or committed files.

```sh
.venv/bin/python -m pytest --run-e2e -m login \
  --login-udid emulator-5558 --setup-mode easy -v
```

For the self-signed certificate of this isolated server, obtain its current pin with:

```sh
podman exec tatrak-qa-prosody openssl x509 \
  -in /var/lib/prosody/certs/10.0.2.2.crt -noout -fingerprint -sha256
```

Pass that exact value using `--test-server-sha256 <fingerprint>` for unattended login. Acceptance
requires a matching fingerprint and the local server's certificate dialog. Without the option,
unknown certificates are not automatically accepted. The successful login run used a separate
`Tatrak_QA_Login` emulator and disposable `qa-login@10.0.2.2` account. That installation is now signed
in; prepare a fresh dedicated installation before repeating the first-login test.

Device/contact/server/package options can also be set as `TATRAK_PRIMARY_UDID`, `TATRAK_PEER_UDID`,
`TATRAK_LOGIN_UDID`, `TATRAK_PRIMARY_CONTACT`, `TATRAK_PEER_CONTACT`, `TATRAK_APPIUM_URL`, and
`TATRAK_APP_PACKAGE`. Default package: `am.cyberhayq.chat`; launcher:
`eu.siacs.conversations.ui.ConversationActivity`.

Use `--ui-timeout 60` for a slower test environment. Element waits poll for state, with bounded
timeouts. Avoid fixed sleeps. Messaging fixtures preserve accounts and history and leave generated
messages in the test chat, so repeat runs should use disposable accounts only.

## Failure evidence

After a setup or test failure with a live session, `artifacts/<case>-<run>/<device-role>/` contains:

- `screenshot.png`
- `hierarchy.xml`
- `logcat.txt` (last 500 lines, collected with an explicit device serial)
- `capture-errors.txt` when an artifact could not be captured

Keep the Appium server's console log alongside the report when diagnosing session creation failures.
The suite cannot capture a UI before Appium creates a session. Artifacts may contain test account
addresses, messages, and device logs; inspect them before sharing. They are excluded from version control.

The login flow has native resource IDs. Compose chat controls use accessibility labels and a scoped
EditText rather than CSS selectors. Validate locators with a live UI hierarchy before claiming coverage.
See [TEST_CASES.md](TEST_CASES.md) for exact assertions, preconditions, and coverage limits.
The additional manual/future-automation catalogue is in [REGRESSION_CASES.md](REGRESSION_CASES.md);
those entries are not executable pytest coverage.
