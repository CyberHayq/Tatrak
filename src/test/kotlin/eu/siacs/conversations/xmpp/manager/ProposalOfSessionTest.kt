package eu.siacs.conversations.xmpp.manager

import eu.siacs.conversations.ui.ReviewFlowsTest
import eu.siacs.conversations.xmpp.Jid
import eu.siacs.conversations.xmpp.jingle.Media
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The proposal a call screen retracts (Hang up) or waits for after it was restored. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = ReviewFlowsTest.TestApp::class, sdk = [35])
class ProposalOfSessionTest {

    private val peer = Jid.of("friend@example.com")
    private val shown = JingleManager.RtpSessionProposal.of(peer, setOf(Media.AUDIO), null)

    @Test
    fun theShownSessionMatches() {
        assertTrue(JingleManager.isProposal(shown, peer, shown.sessionId))
        assertTrue(JingleManager.isProposal(shown, Jid.of("friend@example.com/phone"), shown.sessionId))
    }

    @Test
    fun aNewerProposalToTheSamePeerDoesNot() {
        // an old screen's Hang up must not retract a redial, nor take it for its own proposal
        assertFalse(JingleManager.isProposal(shown, peer, "an-earlier-session"))
    }

    @Test
    fun withoutAKnownSessionThePeerDecides() {
        assertTrue(JingleManager.isProposal(shown, peer, null))
        assertFalse(JingleManager.isProposal(shown, Jid.of("other@example.com"), null))
    }
}
