import pytest


@pytest.mark.e2e
@pytest.mark.login
def test_login_existing_account(login_app, request):
    """TC-AUTH-001: an existing account gets from welcome to the chat interface."""
    from appium.webdriver.common.appiumby import AppiumBy
    from selenium.common.exceptions import StaleElementReferenceException
    from selenium.webdriver.support.ui import WebDriverWait
    from tests.support import text_locator
    import re

    app, jid, password = login_app
    # Fresh installation security preferences are changed only by an explicit test option.
    start = (AppiumBy.ID, f"{app.package}:id/account_jid")
    welcome = (AppiumBy.ID, f"{app.package}:id/use_existing")
    mode_title = text_locator("How would you like to start?")
    WebDriverWait(app.driver, app.timeout).until(
        lambda _: app.has(mode_title) or app.has(welcome) or app.has(start),
        message="Expected the setup, welcome, or login screen on the dedicated login device",
    )
    if app.has(mode_title):
        mode = request.config.getoption("--setup-mode")
        assert mode, "Fresh install: pass --setup-mode easy or --setup-mode advanced explicitly"
        app.tap_text(mode.capitalize())
        WebDriverWait(app.driver, app.timeout).until(
            lambda _: app.has(welcome) or app.has(start),
            message="Security mode selection did not reach welcome or login",
        )
    if app.has(welcome):
        app.visible(welcome).click()

    # Fails on a signed-in device instead of treating an existing session as a login pass.
    address = app.resource("account_jid")
    address.clear()
    address.send_keys(jid)
    secret = app.resource("account_password")
    secret.clear()
    secret.send_keys(password)
    app.resource("save_button").click()

    def logged_in(driver):
        activity = driver.current_activity
        pin = request.config.getoption("--test-server-sha256")
        if pin and app.has(text_locator("Accept Unknown Certificate?")):
            details = app.visible((AppiumBy.ID, "android:id/message")).text
            actual = re.search(r"SHA-256:\s*([0-9a-fA-F:]+)", details)
            assert actual and actual.group(1).replace(":", "").lower() == pin.replace(":", "").lower(), "Unexpected test server certificate"
            assert "CN=10.0.2.2" in details, "Certificate dialog is not for the local QA server"
            app.visible((AppiumBy.ID, "android:id/button1")).click()
            return False
        if activity.endswith("PublishProfilePictureActivity"):
            skip = text_locator("Skip")
            for element in driver.find_elements(*skip):
                if element.is_displayed() and element.text == "Skip":
                    element.click()
                    return False
        chat_activity = activity.endswith(("ConversationActivity", "ConversationsActivity", "StartConversationActivity"))
        return chat_activity and (app.has((AppiumBy.ACCESSIBILITY_ID, "New chat")) or app.has(text_locator("New message")))

    WebDriverWait(app.driver, app.timeout, poll_frequency=0.3,
                  ignored_exceptions=(StaleElementReferenceException,)).until(
        logged_in,
        message="Login did not reach the chat interface; inspect artifacts for credentials, TLS, or permission prompts",
    )
