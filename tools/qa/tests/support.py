"""Black-box helpers for a dedicated, English-language Tatrak test installation."""

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import uuid

from appium.webdriver.common.appiumby import AppiumBy
from selenium.common.exceptions import NoSuchElementException, StaleElementReferenceException
from selenium.webdriver.support.ui import WebDriverWait


def text_locator(value: str) -> tuple[str, str]:
    # JSON string escaping also produces a valid Java string for UiSelector.
    return (AppiumBy.ANDROID_UIAUTOMATOR, f"new UiSelector().text({json.dumps(value, ensure_ascii=True)})")


def unique_message(label: str) -> str:
    return f"tatrak-qa-{label}-{uuid.uuid4().hex}"


class Tatrak:
    def __init__(self, driver, package: str, timeout: float):
        self.driver = driver
        self.package = package
        self.timeout = timeout

    def visible(self, locator):
        def find(driver):
            for element in driver.find_elements(*locator):
                if element.is_displayed():
                    return element
            return False

        return WebDriverWait(
            self.driver, self.timeout, poll_frequency=0.3,
            ignored_exceptions=(NoSuchElementException, StaleElementReferenceException),
        ).until(find, message=f"Visible element not found: {locator}")

    def has(self, locator) -> bool:
        try:
            return any(e.is_displayed() for e in self.driver.find_elements(*locator))
        except StaleElementReferenceException:
            return False

    def text(self, value: str):
        return self.visible(text_locator(value))

    def tap_text(self, value: str):
        self.text(value).click()

    def resource(self, name: str):
        return self.visible((AppiumBy.ID, f"{self.package}:id/{name}"))

    def composer(self):
        # Composer.kt uses one BasicTextField, exposed by Android as EditText.
        return self.visible((AppiumBy.CLASS_NAME, "android.widget.EditText"))

    def open_chat(self, contact_label: str):
        # Return from an already open conversation using its explicit Back action.
        # The known contact is then selected from the visible chat list, never by coordinates.
        back = (AppiumBy.ACCESSIBILITY_ID, "Back")
        new_chat = (AppiumBy.ACCESSIBILITY_ID, "New chat")
        WebDriverWait(self.driver, self.timeout, poll_frequency=0.3).until(
            lambda _: self.has(back) or self.has(new_chat),
            message="Expected the Chats list or an open conversation",
        )
        if self.has(back):
            self.visible(back).click()
        self.visible(new_chat)
        self.tap_text(contact_label)
        self.composer()

    def send(self, message: str):
        field = self.composer()
        field.clear()
        field.send_keys(message)
        self.visible((AppiumBy.ACCESSIBILITY_ID, "Send")).click()
        # The assertion is scoped to a message node, excluding an unsent composer value.
        self.message(message)

    def message(self, message: str):
        def find(driver):
            # Read the native text value: UiSelector's string parser does not
            # reliably round-trip escaped newlines and Unicode escape sequences.
            # TextView excludes the EditText composer, so an unsent draft cannot pass.
            for element in driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.TextView"):
                if element.text == message and element.is_displayed():
                    return element
            return False

        return WebDriverWait(
            self.driver, self.timeout, poll_frequency=0.3,
            ignored_exceptions=(NoSuchElementException, StaleElementReferenceException),
        ).until(find, message=f"Message bubble not found: {message}")

    def restart(self):
        self.driver.terminate_app(self.package)
        self.driver.activate_app(self.package)


def capture_failure(driver, udid: str, directory: Path):
    """Keep collection failures from hiding the original assertion failure."""
    directory.mkdir(parents=True, exist_ok=True)
    errors = []
    for name, action in (
        ("screenshot.png", lambda: driver.save_screenshot(str(directory / "screenshot.png"))),
        ("hierarchy.xml", lambda: (directory / "hierarchy.xml").write_text(driver.page_source, encoding="utf-8")),
    ):
        try:
            action()
        except Exception as exc:
            errors.append(f"{name}: {type(exc).__name__}: {exc}")
    try:
        result = subprocess.run(
            ["adb", "-s", udid, "logcat", "-d", "-t", "500"],
            capture_output=True, text=True, timeout=15, check=False,
        )
        (directory / "logcat.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
        if result.returncode:
            errors.append(f"adb logcat exited {result.returncode}")
    except Exception as exc:
        errors.append(f"logcat: {type(exc).__name__}: {exc}")
    if errors:
        (directory / "capture-errors.txt").write_text("\n".join(errors), encoding="utf-8")
