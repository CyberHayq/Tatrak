package eu.siacs.conversations.xmpp.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PendingRetractionsTest {

    private static final long NOW = 1_000_000L;

    @Test
    public void directRetractionHitsTheLaterMessageFromTheSameSide() {
        final var pending = new PendingRetractions();
        pending.add("c1", "origin-1", false, null, true, null, false, NOW);
        // our own message with that id is not the contact's to retract
        assertNull(pending.take("c1", null, "origin-1", "u", null, false, NOW));
        assertNotNull(pending.take("c1", "stanza-9", "origin-1", "u", null, true, NOW));
        // consumed
        assertNull(pending.take("c1", "stanza-9", "origin-1", "u", null, true, NOW));
    }

    @Test
    public void groupRetractionNeedsTheStanzaIdAndTheSameOccupant() {
        final var pending = new PendingRetractions();
        pending.add("room", "stanza-1", true, "occ-a", true, null, false, NOW);
        assertNull(pending.take("room", "stanza-1", "x", "u", "occ-b", true, NOW));
        // the sender's own id equal to the retracted stanza id does not count
        assertNull(pending.take("room", "other", "stanza-1", "u", "occ-a", true, NOW));
        assertNotNull(pending.take("room", "stanza-1", "x", "u", "occ-a", true, NOW));
    }

    @Test
    public void aCorrectionSeesThePendingRetractionWithoutConsumingIt() {
        final var pending = new PendingRetractions();
        pending.add("c1", "origin-1", false, null, true, "fp-a", false, NOW);
        // not the contact's message on our side, not another conversation
        assertNull(pending.peek("c1", "origin-1", null, false, NOW));
        assertNull(pending.peek("c2", "origin-1", null, true, NOW));
        final var proof = pending.peek("c1", "origin-1", null, true, NOW);
        assertNotNull(proof);
        assertEquals("fp-a", proof.fingerprint());
        // still there for the message itself
        assertNotNull(pending.take("c1", "stanza-9", "origin-1", "u", null, true, NOW));
        // and, after the message was dropped, for a correction that comes later (R, O, C)
        assertNotNull(pending.peek("c1", "origin-1", null, true, NOW));
        assertNull(pending.take("c1", "stanza-9", "origin-1", "u", null, true, NOW));
        assertNull(pending.peek("c1", "origin-1", null, true, NOW + PendingRetractions.MAX_AGE_MILLIS + 1));
    }

    @Test
    public void aCorrectionMatchesOnlyWhatTakeWouldMatch() {
        final var pending = new PendingRetractions();
        // a private message in a room: the sender's id and the occupant id
        pending.add("room", "origin-1", false, "occ-a", true, null, false, NOW);
        assertNull(pending.peek("room", "origin-1", "occ-b", true, NOW));
        assertNull(pending.peek("room", "origin-1", null, true, NOW));
        assertNotNull(pending.peek("room", "origin-1", "occ-a", true, NOW));
        // a groupchat retraction names the room's stanza id, never the sender's id a correction uses
        pending.add("room", "stanza-1", true, "occ-a", true, null, false, NOW);
        assertNull(pending.peek("room", "stanza-1", "occ-a", true, NOW));
        assertEquals(2, pending.size());
    }

    @Test
    public void otherConversationsAreUntouched() {
        final var pending = new PendingRetractions();
        pending.add("c1", "id", false, null, true, null, false, NOW);
        assertNull(pending.take("c2", null, "id", "u", null, true, NOW));
        assertEquals(1, pending.size());
    }

    @Test
    public void oldAndExcessEntriesAreDropped() {
        final var pending = new PendingRetractions();
        pending.add("c1", "old", false, null, true, null, false, NOW);
        assertNull(pending.take(
                        "c1", null, "old", "u", null, true,
                        NOW + PendingRetractions.MAX_AGE_MILLIS + 1));
        for (int i = 0; i < PendingRetractions.MAX_ENTRIES + 10; i++) {
            pending.add("c1", "id" + i, false, null, true, null, false, NOW);
        }
        assertEquals(PendingRetractions.MAX_ENTRIES, pending.size());
        assertNull(pending.take("c1", null, "id0", "u", null, true, NOW));
    }

    @Test
    public void aRepeatedRetractionDoesNotKeepOlderOnesAlive() {
        final var pending = new PendingRetractions();
        final long hour = 60L * 60 * 1000;
        pending.add("c1", "k1", false, null, true, null, false, NOW);
        pending.add("c1", "k2", false, null, true, null, false, NOW + 1);
        // k1 again (live, then from the archive) gets a fresh time
        pending.add("c1", "k1", false, null, true, null, false, NOW + 23 * hour);
        final long later = NOW + 25 * hour;
        assertNull(pending.take("c1", null, "k2", "u", null, true, later));
        assertNotNull(pending.take("c1", null, "k1", "u", null, true, later));
    }

    @Test
    public void theRetractionsOmemoProofComesBackWithTheMessage() {
        final var pending = new PendingRetractions();
        pending.add("c1", "origin-1", false, null, true, "fp-a", false, NOW);
        final var proof = pending.take("c1", null, "origin-1", "u", null, true, NOW + 5);
        assertNotNull(proof);
        assertEquals("fp-a", proof.fingerprint());
        assertFalse(proof.ownTrustedDevice());
        assertEquals(NOW, proof.at());
    }

    @Test
    public void anEncryptedMessageOnlyYieldsToItsAuthorsKey() {
        // plain message: any retraction from the right sender
        assertTrue(ModerationManager.isVerified(null, null, false));
        // encrypted message, plain retraction (what a server can forge): kept
        assertFalse(ModerationManager.isVerified("fp-a", null, false));
        // encrypted with another key: kept
        assertFalse(ModerationManager.isVerified("fp-a", "fp-b", false));
        // encrypted with the author's key
        assertTrue(ModerationManager.isVerified("fp-a", "fp-a", false));
        // our own message deleted from another of our trusted devices
        assertTrue(ModerationManager.isVerified("fp-a", "fp-own", true));
    }
}
