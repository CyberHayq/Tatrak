package eu.siacs.conversations.xmpp.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.xmpp.manager.JingleManager.DeviceDiscoveryState;
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

    @Test
    public void withStreamManagementEverySearchingProposalIsACandidate() {
        assertTrue(JingleManager.isRebindResendCandidate(DeviceDiscoveryState.SEARCHING, true, false));
        assertTrue(JingleManager.isRebindResendCandidate(DeviceDiscoveryState.SEARCHING, true, true));
    }

    @Test
    public void withoutStreamManagementOnlyOneNeverWritten() {
        // sent while unbound: nothing replays it, the fresh bind is its first send
        assertTrue(JingleManager.isRebindResendCandidate(DeviceDiscoveryState.SEARCHING, false, true));
        // written to the old stream: it may have arrived, a resend could ring twice
        assertFalse(
                JingleManager.isRebindResendCandidate(DeviceDiscoveryState.SEARCHING, false, false));
    }

    @Test
    public void anAcknowledgedOrRingingProposalIsNoCandidate() {
        assertFalse(
                JingleManager.isRebindResendCandidate(
                        DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED, true, true));
        assertFalse(
                JingleManager.isRebindResendCandidate(DeviceDiscoveryState.DISCOVERED, false, true));
    }
}
