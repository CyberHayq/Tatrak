# Local Appium environment

Appium is installed inside this QA workspace, independently of global npm packages.

## Version selection

- Node.js detected: `22.22.0`.
- npm detected: `10.9.4`.
- Selected Appium: `3.1.0`.
- Selected UiAutomator2 driver: `5.0.6`.
- Both packages declare Node support `^20.19.0 || ^22.12.0 || >=24.0.0` and npm `>=10`.
- Driver `5.0.6` declares Appium peer support `^3.0.0-rc.2`.

These values were checked with `npm view` against package metadata on 2026-11-25.
Installation completed successfully. `appium --version` returns `3.1.0` and
`appium driver list --installed --json` reports installed UiAutomator2 `5.0.6`.
The installed-driver result is saved in `artifacts/emulator-run/appium-drivers.json`.
Android doctor reports zero required fixes. Device sessions have been exercised on API 31;
see [RUN_RESULTS.md](../RUN_RESULTS.md) for the completed run.

## Installation

Run from `tools/qa/`:

```sh
timeout 480 npm install --prefix .tools/appium --save-exact --no-audit --no-fund appium@3.1.0
APPIUM_HOME="$PWD/.tools/appium-home" timeout 480 \
  .tools/appium/node_modules/.bin/appium driver install uiautomator2@5.0.6
```

The npm installation creates a local `package-lock.json`. Appium's driver state is under
`.tools/appium-home/`, not the user's default global Appium directory.

## Validation and server startup

```sh
APPIUM_HOME="$PWD/.tools/appium-home" .tools/appium/node_modules/.bin/appium --version
APPIUM_HOME="$PWD/.tools/appium-home" .tools/appium/node_modules/.bin/appium driver list --installed
```

After the Android SDK and Java are configured:

```sh
# Set JAVA_HOME to your installed JDK before sourcing the environment.
source scripts/env.sh
APPIUM_HOME="$PWD/.tools/appium-home" ANDROID_HOME="$PWD/.tools/android-sdk" \
  .tools/appium/node_modules/.bin/appium driver doctor uiautomator2
APPIUM_HOME="$PWD/.tools/appium-home" ANDROID_HOME="$PWD/.tools/android-sdk" \
  .tools/appium/node_modules/.bin/appium --address 127.0.0.1 --port 4723
```

Set `JAVA_HOME` to a valid JDK before driver validation and startup. The default Appium URL is
`http://127.0.0.1:4723` without `/wd/hub`.

Logs are kept under `artifacts/emulator-run/`. No server was started by the tooling setup task.
