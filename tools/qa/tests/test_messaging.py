import pytest


pytestmark = [pytest.mark.e2e, pytest.mark.messaging]


def test_message_round_trip(chat_pair):
    """TC-MSG-001: a unique message reaches the peer, and its reply reaches the sender."""
    from tests.support import unique_message

    primary, peer, _ = chat_pair
    outgoing = unique_message("outgoing")
    reply = unique_message("reply")

    primary.send(outgoing)
    peer.message(outgoing)
    peer.send(reply)
    primary.message(reply)


def test_history_survives_app_restart(chat_pair):
    """TC-HIST-001: a delivered message stays visible after the sender process restarts."""
    from tests.support import unique_message

    primary, peer, contact = chat_pair
    message = unique_message("history")
    primary.send(message)
    peer.message(message)

    primary.restart()
    primary.open_chat(contact)

    primary.message(message)
