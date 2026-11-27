package eu.siacs.conversations.ui

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.services.CallIntegration
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.xmpp.CallStreamRacesTest
import eu.siacs.conversations.xmpp.Jid
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection
import eu.siacs.conversations.xmpp.jingle.Media
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState
import eu.siacs.conversations.xmpp.manager.JingleManager
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** The call screen of a proposal in the windows of its races with proceed and the end of the call. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(
    application = ReviewFlowsTest.TestApp::class,
    sdk = [35],
    shadows = [ReviewFlowsTest.NoAxolotlStore::class, ReviewFlowsTest.NoPushRenewal::class, CallStreamRacesTest.NoMessageWrites::class],
)
class ProposalScreenRacesTest {

    /** Not connected: the screen has not taken over the call yet (no connection reference). */
    class Screen : RtpSessionActivity() {
        override fun connectToBackend() = Unit
    }

    private class RecordingCall(service: XmppConnectionService, id: AbstractJingleConnection.Id) :
        JingleRtpConnection(service, id, id.account.jid, CallIntegration(service)) {
        @Volatile var ended = false
        @Volatile var fired = false

        @Synchronized
        override fun endCall() {
            ended = true
        }

        override fun fireStateUpdate() {
            fired = true
        }
    }

    private val service = ReviewFlowsTest.TestService(ApplicationProvider.getApplicationContext<Context>())
    private val account = service.a

    private val jingleManager get() = account.xmppConnection.getManager(JingleManager::class.java)

    private fun register(call: JingleRtpConnection) {
        ReflectionHelpers.getField<ConcurrentHashMap<AbstractJingleConnection.Id, AbstractJingleConnection>>(jingleManager, "connections")[call.id] =
            call
    }

    private fun screen(proposedSessionId: String = "shown"): Screen {
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), Screen::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(XmppActivity.EXTRA_ACCOUNT, account.jid.asBareJid().toString())
                .putExtra(RtpSessionActivity.EXTRA_WITH, "friend@example.com")
                .putExtra(RtpSessionActivity.EXTRA_PROPOSED_SESSION_ID, proposedSessionId)
                .putExtra(RtpSessionActivity.EXTRA_LAST_ACTION, RtpSessionActivity.ACTION_MAKE_VOICE_CALL)
        return Robolectric.buildActivity(Screen::class.java, intent).create().get().also {
            it.xmppConnectionService = service
        }
    }

    @Test
    fun hangUpEndsTheCallTheShownProposalBecame() {
        val screen = screen()
        // proceed removed the proposal and registered its call
        val call = RecordingCall(service, AbstractJingleConnection.Id.of(account, Jid.of("friend@example.com/phone"), "shown"))
        register(call)

        ReflectionHelpers.callInstanceMethod<Any>(screen, "endCall")

        assertTrue(call.ended)
        assertTrue(screen.isFinishing)
    }

    @Test
    fun aRestoredEndCardLetsTheScreenTurnOff() {
        // the proposal timed out before the screen opened; onCreate() kept the screen on
        jingleManager.setTerminalSessionState(
                AbstractJingleConnection.Id.of(account, Jid.of("friend@example.com"), "shown"),
                RtpEndUserState.CONNECTIVITY_ERROR,
                setOf(Media.AUDIO),
            )
        val screen = screen()
        val keepOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        assertEquals(keepOn, screen.window.attributes.flags and keepOn)

        ReflectionHelpers.callInstanceMethod<Any>(screen, "onBackendConnected")

        assertEquals(0, screen.window.attributes.flags and keepOn)
    }

    @Test
    fun aRestoredProposalScreenDoesNotTakeAnotherCall() {
        val proposal = JingleManager.RtpSessionProposal.of(Jid.of("friend@example.com"), setOf(Media.AUDIO), null)
        ReflectionHelpers.getField<HashMap<JingleManager.RtpSessionProposal, JingleManager.DeviceDiscoveryState>>(
            jingleManager,
            "rtpSessionProposals",
        )[proposal] = JingleManager.DeviceDiscoveryState.SEARCHING
        val other = RecordingCall(service, AbstractJingleConnection.Id.of(account, Jid.of("other@example.com/phone"), "other"))
        register(other)
        val screen = screen(proposal.sessionId)

        ReflectionHelpers.callInstanceMethod<Any>(screen, "onBackendConnected")

        assertFalse(other.fired)
        assertFalse(screen.isFinishing)
    }

    private fun takeOver(screen: Screen, with: String, sessionId: String) =
        ReflectionHelpers.callInstanceMethod<Any>(
            screen,
            "reInitializeActivityWithRunningRtpSession",
            ClassParameter.from(Account::class.java, account),
            ClassParameter.from(Jid::class.java, Jid.of(with)),
            ClassParameter.from(String::class.java, sessionId),
        )

    @Test
    fun theEndOfTheCallReplacesTheIntentOfItsTakeOver() {
        // proceed made the shown proposal a call, which failed before the screen took it over
        jingleManager.setTerminalSessionState(
            AbstractJingleConnection.Id.of(account, Jid.of("friend@example.com/phone"), "shown"),
            RtpEndUserState.CONNECTIVITY_ERROR,
            setOf(Media.AUDIO),
        )
        val screen = screen()

        takeOver(screen, "friend@example.com/phone", "shown")

        // reopened later, the screen shows the end card instead of looking for a call that is gone
        assertNull(screen.intent.getStringExtra(RtpSessionActivity.EXTRA_SESSION_ID))
        assertEquals(
            RtpEndUserState.CONNECTIVITY_ERROR.toString(),
            screen.intent.getStringExtra(RtpSessionActivity.EXTRA_LAST_REPORTED_STATE),
        )
    }

    @Test
    fun anotherPeersCallDoesNotTakeOverTheProposalScreen() {
        register(RecordingCall(service, AbstractJingleConnection.Id.of(account, Jid.of("other@example.com/phone"), "other")))
        val screen = screen()

        takeOver(screen, "other@example.com/phone", "other")

        assertNull(screen.intent.getStringExtra(RtpSessionActivity.EXTRA_SESSION_ID))
        assertEquals("shown", screen.intent.getStringExtra(RtpSessionActivity.EXTRA_PROPOSED_SESSION_ID))
    }

    private fun keptOn(screen: Screen) =
        screen.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test
    fun theEndOfAnotherCallKeepsTheScreenOn() {
        val screen = screen()

        screen.onJingleRtpConnectionUpdate(account, Jid.of("other@example.com/phone"), "other", RtpEndUserState.CONNECTIVITY_ERROR)

        assertTrue(keptOn(screen))
    }

    @Test
    fun theEndOfTheCallTheShownProposalBecameLetsTheScreenTurnOff() {
        // proceed made it a call, which failed before the screen took it over
        val screen = screen()

        screen.onJingleRtpConnectionUpdate(account, Jid.of("friend@example.com/phone"), "shown", RtpEndUserState.CONNECTIVITY_ERROR)

        assertFalse(keptOn(screen))
    }

    /** Our proposal's call after processProceed published PROPOSED, before it delivered proceed. */
    private fun proposedCall(): JingleRtpConnection {
        service.databaseBackend = DatabaseBackend.isolated(ApplicationProvider.getApplicationContext(), "unused", ByteArray(0))
        val call = JingleRtpConnection(service, AbstractJingleConnection.Id.of(account, Jid.of("friend@example.com/phone"), "shown"), account.jid, CallIntegration(service))
        call.setProposedMedia(setOf(Media.AUDIO))
        register(call)
        call.transitionOrThrow(AbstractJingleConnection.State.PROPOSED)
        return call
    }

    @Test
    fun hangUpOnTheTakenOverCallBeforeProceedRetractsIt() {
        val call = proposedCall()
        val screen = screen()
        takeOver(screen, "friend@example.com/phone", "shown")
        assertEquals("shown", screen.intent.getStringExtra(RtpSessionActivity.EXTRA_SESSION_ID))

        ReflectionHelpers.callInstanceMethod<Any>(screen, "endCall")

        assertEquals(AbstractJingleConnection.State.RETRACTED, call.state)
    }

    @Test
    fun hangUpOnTheScreenOfTheOngoingCallNotificationBeforeProceedRetractsIt() {
        val call = proposedCall()
        // the ongoing-call notification opens the screen with the call's session id
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), Screen::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(XmppActivity.EXTRA_ACCOUNT, account.jid.asBareJid().toString())
                .putExtra(RtpSessionActivity.EXTRA_WITH, "friend@example.com/phone")
                .putExtra(RtpSessionActivity.EXTRA_SESSION_ID, "shown")
        val screen = Robolectric.buildActivity(Screen::class.java, intent).create().get().also { it.xmppConnectionService = service }
        ReflectionHelpers.callInstanceMethod<Any>(screen, "onBackendConnected")

        ReflectionHelpers.callInstanceMethod<Any>(screen, "endCall")

        assertEquals(AbstractJingleConnection.State.RETRACTED, call.state)
    }

    @Test
    fun theNotificationsHangUpBeforeProceedRetractsTheCall() {
        val call = proposedCall()

        jingleManager.endRtpSession("shown")

        assertEquals(AbstractJingleConnection.State.RETRACTED, call.state)
    }

    @Test
    fun aRestoredEndCardLetsTheScreenTurnOffWhileAnotherCallRuns() {
        // the end-card intent of a promoted call carries no proposed session id
        register(RecordingCall(service, AbstractJingleConnection.Id.of(account, Jid.of("other@example.com/phone"), "other")))
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), Screen::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(XmppActivity.EXTRA_ACCOUNT, account.jid.asBareJid().toString())
                .putExtra(RtpSessionActivity.EXTRA_WITH, "friend@example.com")
                .putExtra(RtpSessionActivity.EXTRA_LAST_REPORTED_STATE, RtpEndUserState.CONNECTIVITY_ERROR.toString())
                .putExtra(RtpSessionActivity.EXTRA_LAST_ACTION, RtpSessionActivity.ACTION_MAKE_VOICE_CALL)
        val screen = Robolectric.buildActivity(Screen::class.java, intent).create().get().also { it.xmppConnectionService = service }
        assertTrue(keptOn(screen))

        ReflectionHelpers.callInstanceMethod<Any>(screen, "onBackendConnected")

        assertFalse(keptOn(screen))
    }
}
