package eu.siacs.conversations.xmpp.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tatrak: the retracted id repeated inside an encrypted retraction's body. */
public class RetractionInnerIdTest {

    @Test
    public void theIdComesBackFromTheBody() {
        assertEquals("abc-123", ModerationManager.innerId(ModerationManager.encryptedBody("abc-123")));
        // ids are opaque: brackets inside still round-trip
        assertEquals("a]b", ModerationManager.innerId(ModerationManager.encryptedBody("a]b")));
    }

    @Test
    public void otherClientsBodiesCarryNoId() {
        assertNull(
                ModerationManager.innerId(
                        "This person attempted to retract a previous message, but it's unsupported"
                                + " by your client."));
        assertNull(ModerationManager.innerId(null));
        assertNull(ModerationManager.innerId("[link]"));
    }

    @Test
    public void onlyRetractionNoticesMakeAnEncryptedRetract() {
        assertTrue(ModerationManager.isRetractionNotice(ModerationManager.encryptedBody("abc")));
        assertTrue(
                ModerationManager.isRetractionNotice(
                        "This person attempted to retract a previous message, but it's unsupported"
                                + " by your client."));
        assertTrue(
                ModerationManager.isRetractionNotice(
                        "This message has been retracted by the sender."));
        assertTrue(
                ModerationManager.isRetractionNotice(
                        "/me retracted a previous message, but it's unsupported by your client."));
        // an ordinary message with a <retract> added in transit
        assertFalse(ModerationManager.isRetractionNotice("hello"));
        assertFalse(ModerationManager.isRetractionNotice("see /me retracted a previous message"));
        assertFalse(ModerationManager.isRetractionNotice(""));
        assertFalse(ModerationManager.isRetractionNotice(null));
    }
}
