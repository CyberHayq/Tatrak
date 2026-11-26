import pytest


pytestmark = [pytest.mark.e2e, pytest.mark.messaging, pytest.mark.regression]


@pytest.mark.parametrize("payload", [
    pytest.param('Quotes: "hello"; apostrophe: \'test\'; symbols: <>& / \\', id="punctuation"),
    pytest.param("Բարեւ աշխարհ — Привет мир — Hello", id="armenian-cyrillic"),
    pytest.param("Hello 👋🏽 ❤️ 👨‍👩‍👧‍👦", id="emoji-sequences"),
    pytest.param("First line\nSecond line\nThird line", id="multiline"),
    pytest.param("0123456789abcdef" * 128, id="long-text-2048"),
])
def test_message_content_preserved(chat_pair, payload):
    """TC-TEXT-001..005: message content reaches the other client unchanged."""
    from tests.support import unique_message

    primary, peer, _ = chat_pair
    message = unique_message("content") + "\n" + payload
    primary.send(message)
    peer.message(message)


def test_received_history_survives_app_restart(chat_pair):
    """TC-HIST-003: the recipient retains an incoming message across restart."""
    from tests.support import unique_message

    primary, peer, contact = chat_pair
    message = unique_message("incoming-history")
    peer.send(message)
    primary.message(message)

    primary.restart()
    primary.open_chat(contact)

    primary.message(message)


def test_edit_message_updates_peer(chat_pair):
    """TC-MSG-003: editing an outgoing plain-text message updates the recipient's bubble."""
    from selenium.webdriver.support.ui import WebDriverWait
    from tests.support import text_locator, unique_message

    primary, peer, _ = chat_pair
    original = unique_message("before-edit")
    edited = unique_message("after-edit")
    primary.send(original)
    peer.message(original)

    # MessageBubble.kt: single tap opens actions; long press enters selection mode.
    primary.message(original).click()
    primary.tap_text("Edit")
    primary.text("Edit message")
    WebDriverWait(primary.driver, primary.timeout).until(
        lambda _: primary.composer().text == original,
        message="Edit action did not load the intended original message",
    )
    primary.send(edited)

    peer.message(edited)
    WebDriverWait(peer.driver, peer.timeout).until_not(
        lambda _: peer.has(text_locator(original)),
        message="The original bubble is still visible after the edit arrived",
    )


def test_draft_survives_leaving_conversation(chat_pair, request):
    """TC-DRAFT-001: an unsent draft survives navigation away and back."""
    from selenium.webdriver.support.ui import WebDriverWait
    from tests.support import unique_message

    primary, _, contact = chat_pair
    assert primary.composer().text == "", "Precondition: the test chat must have an empty composer"
    draft = unique_message("unsent-draft")

    def remove_generated_draft():
        # Runs after failure evidence is captured and before the Appium sessions close.
        primary.open_chat(contact)
        field = primary.composer()
        if field.text == draft:
            field.clear()
            primary.open_chat(contact)  # persist the cleared draft

    request.addfinalizer(remove_generated_draft)
    primary.composer().send_keys(draft)
    primary.open_chat(contact)

    WebDriverWait(primary.driver, primary.timeout).until(
        lambda _: primary.composer().text == draft,
        message="The unsent draft was lost or changed after reopening the conversation",
    )


def test_empty_composer_has_no_send_action(chat_pair):
    """TC-COMPOSER-001: an empty text composer does not expose Send."""
    from appium.webdriver.common.appiumby import AppiumBy
    from selenium.webdriver.support.ui import WebDriverWait

    primary, _, _ = chat_pair
    assert primary.composer().text == "", "Precondition: no draft or pending attachment"
    WebDriverWait(primary.driver, primary.timeout).until_not(
        lambda _: primary.has((AppiumBy.ACCESSIBILITY_ID, "Send")),
        message="Send remains available for an empty text composer",
    )
