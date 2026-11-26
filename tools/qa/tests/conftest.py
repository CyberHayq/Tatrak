from __future__ import annotations

import os
from pathlib import Path
import re
import uuid

import pytest


def pytest_addoption(parser):
    group = parser.getgroup("tatrak")
    group.addoption("--run-e2e", action="store_true", help="Run against explicitly configured Android test devices")
    for name, default in (
        ("appium-url", "http://127.0.0.1:4723"),
        ("app-package", "am.cyberhayq.chat"),
        ("primary-udid", None), ("peer-udid", None), ("login-udid", None),
        ("primary-contact", None), ("peer-contact", None),
    ):
        group.addoption(f"--{name}", default=os.getenv("TATRAK_" + name.upper().replace("-", "_"), default))
    group.addoption("--ui-timeout", type=float, default=30)
    group.addoption("--test-server-sha256", default=None,
                    help="Explicit SHA-256 pin for the isolated local QA server certificate")
    group.addoption("--setup-mode", choices=("easy", "advanced"), default=None,
                    help="Explicit security mode for a fresh login test installation")
    group.addoption("--artifacts-dir", default="artifacts")


def pytest_collection_modifyitems(config, items):
    if not config.getoption("--run-e2e"):
        skip = pytest.mark.skip(reason="Device tests are opt-in; configure test devices and pass --run-e2e")
        for item in items:
            if "e2e" in item.keywords:
                item.add_marker(skip)


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    report = outcome.get_result()
    setattr(item, f"report_{report.when}", report)
    if report.failed and getattr(item, "tatrak_sessions", None):
        from tests.support import capture_failure
        name = re.sub(r"[^a-zA-Z0-9_.-]", "_", item.nodeid)
        root = Path(item.config.getoption("--artifacts-dir")) / f"{name}-{uuid.uuid4().hex[:8]}"
        for role, udid, driver in item.tatrak_sessions:
            try:
                capture_failure(driver, udid, root / role)
            except Exception as exc:
                report.sections.append((f"Tatrak artifact capture failed ({role})",
                                        f"{type(exc).__name__}: {exc}"))
        report.sections.append(("Tatrak failure artifacts", str(root.resolve())))


def required(config, option: str) -> str:
    value = config.getoption(option)
    if not value:
        pytest.fail(f"Missing {option}; see TESTING.md for device and account setup", pytrace=False)
    return value


@pytest.fixture
def session_factory(request):
    # Lazy imports allow collection and skipped runs without a running Appium service.
    from appium import webdriver
    from appium.options.android import UiAutomator2Options
    from tests.support import Tatrak

    request.node.tatrak_sessions = []

    def create(role: str, udid: str, port: int):
        import subprocess
        state = subprocess.run(["adb", "-s", udid, "get-state"], capture_output=True,
                               text=True, timeout=15, check=False)
        if state.returncode or state.stdout.strip() != "device":
            pytest.fail(f"Android test device {udid!r} is unavailable: {state.stderr.strip()}", pytrace=False)
        package = request.config.getoption("--app-package")
        options = UiAutomator2Options().load_capabilities({
            "platformName": "Android", "appium:automationName": "UiAutomator2",
            "appium:udid": udid, "appium:deviceName": udid,
            "appium:appPackage": package,
            "appium:appActivity": "eu.siacs.conversations.ui.ConversationActivity",
            "appium:appWaitActivity": "eu.siacs.conversations.ui.*",
            "appium:noReset": True, "appium:fullReset": False,
            "appium:forceAppLaunch": True, "appium:systemPort": port,
            "appium:newCommandTimeout": 180,
            "appium:printPageSourceOnFindFailure": False,
        })
        driver = webdriver.Remote(request.config.getoption("--appium-url"), options=options)
        request.node.tatrak_sessions.append((role, udid, driver))
        driver.implicitly_wait(0)
        return Tatrak(driver, package, request.config.getoption("--ui-timeout"))

    yield create
    for _, _, driver in reversed(request.node.tatrak_sessions):
        try:
            driver.quit()
        except Exception as exc:
            request.node.add_report_section("teardown", "Appium cleanup", str(exc))


@pytest.fixture
def chat_pair(request, session_factory):
    primary = required(request.config, "--primary-udid")
    peer = required(request.config, "--peer-udid")
    if primary == peer:
        pytest.fail("Primary and peer must be different Android devices", pytrace=False)
    primary_contact = required(request.config, "--primary-contact")
    peer_contact = required(request.config, "--peer-contact")
    first = session_factory("primary", primary, 8200)
    second = session_factory("peer", peer, 8201)
    first.open_chat(primary_contact)
    second.open_chat(peer_contact)
    return first, second, primary_contact


@pytest.fixture
def login_app(request, session_factory):
    udid = required(request.config, "--login-udid")
    jid, password = os.getenv("TATRAK_LOGIN_JID"), os.getenv("TATRAK_LOGIN_PASSWORD")
    if not jid or not password:
        pytest.fail("Set TATRAK_LOGIN_JID and TATRAK_LOGIN_PASSWORD for a disposable test account", pytrace=False)
    return session_factory("login", udid, 8202), jid, password
