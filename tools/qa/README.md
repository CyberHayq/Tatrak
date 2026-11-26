# Tatrak Android QA

Python/pytest + Appium tests for the native Tatrak Android application.
The latest recorded emulator run passed all **12 implemented cases** on Tatrak 0.5.0,
Android 12/API 31. See [run results and scope](RUN_RESULTS.md).

- [Test cases](TEST_CASES.md): implemented smoke and regression assertions.
- [Regression catalogue](REGRESSION_CASES.md): additional specified cases awaiting automation.
- [Setup and execution](TESTING.md): SDK, emulators, accounts, Appium and run commands.
- [Appium environment](setup/appium-environment.md) and [test chat fixtures](setup/test-chat-fixtures.md).

## Python setup

Requires Python 3.10 or newer. From `tools/qa/`:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-test.lock
.venv/bin/python -m pytest --collect-only -q
```

Collection lists twelve cases. A plain `pytest` run skips device tests; passing `--run-e2e`
and configuring devices is required for real application checks.

## Application and device setup

The Android source is the containing repository (`../..`); the test-server definitions are in
`../test-server/`. No separate Android checkout is needed.

Install a compatible Tatrak APK on the test devices. Two signed-in devices with distinct disposable
accounts and visible mutual chats are required for messaging. First-login validation uses a separate
initial-state installation. Full fixture instructions are in [TESTING.md](TESTING.md).

For the locally installed tooling, source `scripts/env.sh`. On another machine, configure
`ANDROID_HOME` and `JAVA_HOME` to your SDK and JDK before sourcing it; local `.tools/` installations
take precedence when present. Android Emulator hardware acceleration must be available.

## Appium setup

The tested server version and its npm dependency tree are recorded under `tooling/appium/`.
Node.js must satisfy the Appium 3.1.0 engine requirement; Node 22.22.0 was used in the recorded run.

```sh
mkdir -p .tools/appium
cp tooling/appium/package.json tooling/appium/package-lock.json .tools/appium/
npm ci --prefix .tools/appium --no-audit --no-fund
source scripts/env.sh
appium driver install uiautomator2@5.0.6
appium driver doctor uiautomator2
appium --address 127.0.0.1 --port 4723
```

## Run the two-device suite

With the server, Appium and configured devices running:

```sh
source scripts/env.sh
.venv/bin/python -m pytest --run-e2e -m messaging \
  --primary-udid emulator-5554 --peer-udid emulator-5556 \
  --primary-contact friend --peer-contact tester -v
```

This runs eleven messaging/regression cases. Run the login case separately as described in
[TESTING.md](TESTING.md). Tests preserve application data and leave generated messages in test chats.
Use dedicated test accounts only; credentials for login come from environment variables.

## Repository contents

`tests/` contains the tests, fixtures and UI helpers. `scripts/` contains emulator, fixture,
download and reporting helpers. Python dependency versions are in `requirements-test.lock`;
`requirements-test.txt` records direct dependency ranges for deliberate refreshes.

SDKs, emulators, APKs, virtual environments, Orca and raw artifacts are excluded
by `.gitignore`. `RUN_RESULTS.md` records the observed result; its evidence files remain local or can
be shared separately as CI artifacts. Logs and screenshots can contain test messages and account details.

## Source and validation

Imported from `mmanesyan/tatrak-qa` commit `e08f29e`. The recorded device results describe
the 2026-11-25 run against Tatrak 0.5.0, not a run against the current development branch.
