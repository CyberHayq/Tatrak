package eu.siacs.conversations.xmpp.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// sessions as XmppConnection.getStreamSession() hands them out: new per fresh bind, kept by a resume
public class ProposalResendTest {

    private final Object first = new Object();
    private final Object second = new Object();

    @Test
    public void resentAfterAFreshBindWhenSentInAnEarlierSession() {
        assertTrue(JingleManager.isResendDue(second, second, first));
    }

    @Test
    public void resentWhenSentBeforeAnySession() {
        assertTrue(JingleManager.isResendDue(second, second, null));
    }

    @Test
    public void notResentInTheSessionItWasAlreadySentIn() {
        // a call placed after the bind but before notifyRebound, or a second resend task
        assertFalse(JingleManager.isResendDue(second, second, second));
    }

    @Test
    public void notResentWhenAResumeBeforeDiscoveryEndedReplayedIt() {
        // sent in session `first` (written, or queued while unbound), the connection drops, the
        // resume keeps `first` and replays it, then the pending discovery calls notifyRebound
        assertFalse(JingleManager.isResendDue(first, first, first));
    }

    @Test
    public void notResentWhenAnotherSessionIsCurrentNow() {
        // that session's own notifyRebound resends it
        assertFalse(JingleManager.isResendDue(first, second, first));
        assertFalse(JingleManager.isResendDue(null, null, null));
    }
}
