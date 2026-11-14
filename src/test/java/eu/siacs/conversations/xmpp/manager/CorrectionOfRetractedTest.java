package eu.siacs.conversations.xmpp.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import org.junit.Test;

/** Tatrak: a XEP-0308 correction must not bring back a retracted or hidden message. */
public class CorrectionOfRetractedTest {

    private static final long NOW = 1_000_000L;

    private final Conversation conversation =
            new Conversation(
                    "c1", "Alice", null, "a1", null, 0, Conversation.STATUS_AVAILABLE,
                    Conversational.MODE_SINGLE, Conversation.Attributes.parse(null));

    private Message received(final String body, final String remoteId) {
        final var message =
                new Message(conversation, body, Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setRemoteMsgId(remoteId);
        return message;
    }

    /** The parser's lookup for a received correction in a 1:1 chat. */
    private boolean drops(final PendingRetractions pending, final Message correction) {
        return ModerationManager.isCorrectionOfRetracted(
                pending,
                conversation,
                correction,
                "origin-1",
                conversation.findMessageWithUuidOrRemoteId("origin-1", null, true),
                NOW);
    }

    @Test
    public void aCorrectionOfARetractedMessageIsDropped() {
        final var original = received("secret", "origin-1");
        conversation.add(original);
        assertFalse(drops(new PendingRetractions(), received("secret, edited", "c-1")));
        original.eraseContent();
        assertTrue(drops(new PendingRetractions(), received("secret, edited", "c-2")));
    }

    @Test
    public void aCorrectionOfAMessageDeletedForMeIsDropped() {
        final var original = received("secret", "origin-1");
        original.eraseContent();
        original.setType(Message.TYPE_HIDDEN);
        conversation.add(original);
        assertTrue(drops(new PendingRetractions(), received("secret, edited", "c-1")));
    }

    @Test
    public void aCorrectionAheadOfItsRetractedMessageIsDropped() {
        // archive paged newest-first: retraction, then the correction, then the message
        final var pending = new PendingRetractions();
        pending.add("c1", "origin-1", false, null, true, null, false, NOW);
        assertTrue(drops(pending, received("secret, edited", "c-1")));
        // the message itself is still dropped when it comes
        assertEquals(1, pending.size());
    }

    @Test
    public void anEncryptedCorrectionYieldsOnlyToItsAuthorsKey() {
        final var pending = new PendingRetractions();
        // a plain retraction (what a server can forge) against an encrypted correction
        pending.add("c1", "origin-1", false, null, true, null, false, NOW);
        final var correction = received("secret, edited", "c-1");
        correction.setFingerprint("fp-a");
        assertFalse(drops(pending, correction));
        // kept, and marked like the message itself would be
        assertEquals(NOW, correction.getRetractAttempt());
        pending.add("c1", "origin-1", false, null, true, "fp-a", false, NOW);
        assertTrue(drops(pending, received("secret, edited", "c-2")));
        final var signed = received("secret, edited", "c-3");
        signed.setFingerprint("fp-a");
        assertTrue(drops(pending, signed));
    }
}
