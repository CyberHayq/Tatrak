package eu.siacs.conversations.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;

public class ShownProposalTest {

    private static final Jid PEER = Jid.of("friend@example.com");

    @Test
    public void anUpdateOfTheShownProposalMatches() {
        assertTrue(RtpSessionActivity.isShownProposal(PEER, "b", null, PEER, "b"));
        assertTrue(
                RtpSessionActivity.isShownProposal(
                        Jid.of("friend@example.com/phone"), "b", null, PEER, "b"));
    }

    @Test
    public void aLateUpdateOfAnEarlierCallToTheSamePeerDoesNot() {
        assertFalse(RtpSessionActivity.isShownProposal(PEER, "b", null, PEER, "a"));
    }

    @Test
    public void anotherPeerDoesNot() {
        assertFalse(
                RtpSessionActivity.isShownProposal(
                        PEER, "b", null, Jid.of("other@example.com"), "b"));
    }

    @Test
    public void withoutAKnownSessionThePeerDecides() {
        assertTrue(RtpSessionActivity.isShownProposal(PEER, null, null, PEER, "a"));
    }

    @Test
    public void onceTheProposalBecameACallItsLateUpdateDoesNot() {
        // the promoted intent carries the call's session id and no proposed one
        assertFalse(
                RtpSessionActivity.isShownProposal(
                        Jid.of("friend@example.com/phone"), null, "a", PEER, "a"));
    }
}
