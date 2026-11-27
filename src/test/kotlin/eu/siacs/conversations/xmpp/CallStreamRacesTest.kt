package eu.siacs.conversations.xmpp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.services.CallIntegration
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.ReviewFlowsTest
import eu.siacs.conversations.ui.util.PendingItem
import eu.siacs.conversations.utils.Resolver
import eu.siacs.conversations.xml.TagWriter
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection
import eu.siacs.conversations.xmpp.jingle.Media
import eu.siacs.conversations.xmpp.manager.JingleManager
import im.conversations.android.xmpp.model.StreamElement
import im.conversations.android.xmpp.model.sm.Ack
import im.conversations.android.xmpp.model.sm.Request
import im.conversations.android.xmpp.model.sm.Resumed
import im.conversations.android.xmpp.model.stanza.Message
import im.conversations.android.xmpp.model.stanza.Presence
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Call-proposal races in the stream code: inline-bind numbering, resume replay, late rebound. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(
    application = ReviewFlowsTest.TestApp::class,
    sdk = [35],
    shadows = [ReviewFlowsTest.NoAxolotlStore::class, ReviewFlowsTest.NoPushRenewal::class, CallStreamRacesTest.NoMessageWrites::class],
)
class CallStreamRacesTest {

    /** Message status updates without SQLCipher (it does not run on the JVM). */
    @Implements(value = DatabaseBackend::class, isInAndroidSdk = false)
    class NoMessageWrites {
        @Implementation
        fun updateMessage(message: eu.siacs.conversations.entities.Message, includeBody: Boolean) = true

        @Implementation
        fun createMessage(message: eu.siacs.conversations.entities.Message) = Unit
    }

    /** Records what the connection writes instead of writing it to a socket. */
    private class Recorder : TagWriter() {
        val written: MutableList<StreamElement> = Collections.synchronizedList(ArrayList())
        @Volatile var onWrite: ((StreamElement) -> Unit)? = null

        override fun writeStanzaAsync(stanza: StreamElement): Boolean {
            written += stanza
            onWrite?.invoke(stanza)
            return true
        }
    }

    private class RecordingCall(service: XmppConnectionService, id: AbstractJingleConnection.Id) :
        JingleRtpConnection(service, id, id.account.jid, CallIntegration(service)) {
        @Volatile var rebound = false

        @Synchronized
        override fun notifyRebound() {
            rebound = true
        }
    }

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val service = ReviewFlowsTest.TestService(app)
    private val account: Account = service.a
    private val connection: XmppConnection = account.xmppConnection
    private val recorder = Recorder().also { ReflectionHelpers.setField(connection, "tagWriter", it) }
    private val peer = Jid.of("friend@example.com")

    private fun message(id: String) = Message().apply {
        setTo(peer)
        setId(id)
    }

    private fun writtenMessageIds() =
        synchronized(recorder.written) { recorder.written.filterIsInstance<Message>().map { it.id } }

    @Test
    fun anAckAfterInlineBindDoesNotAcknowledgeAProposalNeverWritten() {
        val acknowledged = Collections.synchronizedList(ArrayList<String>())
        ReflectionHelpers.setField(
            connection,
            "messageAcknowledgedProcessor",
            BiFunction<Jid, String, Boolean> { _, id -> acknowledged.add(id).let { false } },
        )
        // the call is placed after <authenticate>, before <bound>: queued, not written
        connection.sendMessagePacket(message("jm-propose-a"))
        assertTrue(writtenMessageIds().isEmpty())
        // <bound> with stream management enabled inline, then the first written stanza
        synchronized(connection) {
            ReflectionHelpers.callInstanceMethod<Any>(connection, "resetOutboundStanzaQueue")
            ReflectionHelpers.setField(connection, "isBound", true)
        }
        connection.sendPresencePacket(Presence())
        // the server counts only that one
        ReflectionHelpers.callInstanceMethod<Any>(connection, "processAck", ClassParameter.from(Ack::class.java, Ack(1)))

        assertFalse(acknowledged.contains("jm-propose-a"))
        assertEquals(0, ReflectionHelpers.getField<android.util.SparseArray<*>>(connection, "mStanzaQueue").size())
    }

    /** Waits until [thread] either waits for a monitor (the stream is closed to it) or is done. */
    private fun awaitBlockedOrDone(thread: Thread) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (thread.state != Thread.State.BLOCKED && thread.state != Thread.State.TERMINATED) {
            check(System.nanoTime() < deadline) { "${thread.name} neither blocked nor finished" }
            Thread.yield()
        }
    }

    private fun resume() {
        ReflectionHelpers.getField<PendingItem<String>>(connection, "pendingResumeId").push("resume-1")
        account.setStatus(Account.State.CONNECTING)
        val resumed = Resumed().apply {
            setAttribute("previd", "resume-1")
            setAttribute("h", "0")
        }
        ReflectionHelpers.callInstanceMethod<Any>(connection, "processResumed", ClassParameter.from(Resumed::class.java, resumed))
    }

    @Test
    fun aRetractSentDuringTheResumeReplayFollowsTheReplayedProposal() {
        // the propose was queued while unbound; the caller hangs up while the resume replays it
        connection.sendMessagePacket(message("jm-propose-a"))
        var hangup: Thread? = null
        recorder.onWrite = { element ->
            if (element is Request) {
                recorder.onWrite = null
                // the stream is open for sends from here on; the hangup is sent now, or waits for it
                val started = CountDownLatch(1)
                hangup = thread {
                    started.countDown()
                    connection.sendMessagePacket(message("jm-retract-a"))
                }
                started.await()
                awaitBlockedOrDone(hangup!!)
            }
        }

        resume()
        hangup!!.join(10_000)

        assertEquals(listOf("jm-propose-a", "jm-retract-a"), writtenMessageIds())
    }

    @Test
    fun aReplayedMessageIsMarkedUnsentBeforeItIsWritten() {
        // reconnect marked it waiting; a drain of waiting messages must not find it once replayed
        service.databaseBackend = DatabaseBackend.isolated(app, "unused", ByteArray(0))
        val chat = service.chat(account, peer)
        val waiting = eu.siacs.conversations.entities.Message(chat, "hi", eu.siacs.conversations.entities.Message.ENCRYPTION_NONE)
        waiting.setStatus(eu.siacs.conversations.entities.Message.STATUS_WAITING)
        chat.add(waiting)
        connection.sendMessagePacket(message(waiting.uuid))
        var statusWhenWritten: Int? = null
        recorder.onWrite = { element ->
            if (element is Message && element.id == waiting.uuid) statusWhenWritten = waiting.status
        }

        resume()

        assertEquals(eu.siacs.conversations.entities.Message.STATUS_UNSEND, statusWhenWritten)
    }

    @Test
    fun aLateDiscoveryEndsOnlyCallsOfAnEarlierBind() {
        val jingleManager = connection.getManager(JingleManager::class.java)
        // registered as JingleManager.register() does: with the session current then
        val carriedOver = RecordingCall(service, AbstractJingleConnection.Id.of(account, Jid.of("friend@example.com/old"), "old"))
        carriedOver.streamSession = Any()
        // a fresh bind; the call is placed and proceeds while its discovery is still pending
        ReflectionHelpers.setField(connection, "streamSession", Any())
        val current = RecordingCall(service, AbstractJingleConnection.Id.of(account, Jid.of("friend@example.com/new"), "new"))
        current.streamSession = connection.streamSession
        val connections =
            ReflectionHelpers.getField<ConcurrentHashMap<AbstractJingleConnection.Id, AbstractJingleConnection>>(jingleManager, "connections")
        connections[carriedOver.id] = carriedOver
        connections[current.id] = current

        // discovery finishes: BindProcessor
        jingleManager.notifyRebound()

        assertTrue(carriedOver.rebound)
        assertFalse(current.rebound)
    }

    @Test
    fun aStaleDiscoveryDoesNotEndACallOfANewerBind() {
        val jingleManager = connection.getManager(JingleManager::class.java)
        val sessionA = Any()
        val sessionB = Any()
        val newer = RecordingCall(service, AbstractJingleConnection.Id.of(account, Jid.of("friend@example.com/new"), "new"))
        newer.streamSession = sessionB
        ReflectionHelpers.setField(connection, "streamSession", sessionA)
        var bind: Thread? = null
        // bind B completes and its call is registered while the discovery of bind A reads the calls
        val connections =
            object : ConcurrentHashMap<AbstractJingleConnection.Id, AbstractJingleConnection>() {
                override val values: MutableCollection<AbstractJingleConnection>
                    get() {
                        if (bind == null) {
                            bind = thread {
                                synchronized(connection) { ReflectionHelpers.setField(connection, "streamSession", sessionB) }
                                put(newer.id, newer)
                            }
                            awaitBlockedOrDone(bind!!)
                        }
                        return super.values
                    }
            }
        ReflectionHelpers.setField(jingleManager, "connections", connections)

        jingleManager.notifyRebound()
        bind!!.join(10_000)

        assertTrue(connections.containsKey(newer.id))
        assertFalse(newer.rebound)
    }

    private val jingleManager get() = connection.getManager(JingleManager::class.java)

    private fun writes(proposal: JingleManager.RtpSessionProposal): Int {
        JingleManager.PROPOSAL_EXECUTOR.submit {}.get(10, TimeUnit.SECONDS)
        return writtenMessageIds().count { it == "jm-propose-" + proposal.sessionId }
    }

    private fun freshBind(streamManagement: Boolean) {
        synchronized(connection) {
            ReflectionHelpers.setField(connection, "isBound", true)
            ReflectionHelpers.setField(connection, "streamSession", Any())
            ReflectionHelpers.setField(connection, "inSmacksSession", streamManagement)
        }
    }

    private fun dropStream() {
        synchronized(connection) {
            ReflectionHelpers.setField(connection, "isBound", false)
            ReflectionHelpers.setField(connection, "inSmacksSession", false)
        }
    }

    private fun propose() = checkNotNull(jingleManager.proposeJingleRtpSession(peer, setOf(Media.AUDIO)))

    @Test
    fun aProposalWrittenToADroppedStreamIsNotResentWithoutStreamManagement() {
        freshBind(streamManagement = false)
        val proposal = propose()
        assertEquals(1, writes(proposal))

        dropStream()
        freshBind(streamManagement = false)
        jingleManager.notifyRebound()

        // it may have arrived; a second propose could ring twice
        assertEquals(1, writes(proposal))
    }

    @Test
    fun aProposalSentWhileUnboundIsSentOnceOnAFreshBindWithoutStreamManagement() {
        val proposal = propose()
        assertEquals(0, writes(proposal))

        freshBind(streamManagement = false)
        jingleManager.notifyRebound()
        jingleManager.notifyRebound()

        assertEquals(1, writes(proposal))
    }

    @Test
    fun aProposalTheResumeReplayedIsNotResentWithoutStreamManagementLater() {
        val proposal = propose()
        resume()
        assertEquals(1, writes(proposal))

        dropStream()
        freshBind(streamManagement = false)
        jingleManager.notifyRebound()

        assertEquals(1, writes(proposal))
    }

    @Test
    fun aStaleResumptionIdDoesNotMakeAClassicBindResend() {
        freshBind(streamManagement = false)
        val proposal = propose()
        assertEquals(1, writes(proposal))
        // the old stream had stream management; the new server offers none, so no <failed/> clears
        // the old resumption id
        ReflectionHelpers.setField(
            connection,
            "streamId",
            ReflectionHelpers.callConstructor(
                Class.forName("eu.siacs.conversations.xmpp.XmppConnection\$StreamId"),
                ClassParameter.from(String::class.java, "old"),
                ClassParameter.from(Resolver.Result::class.java, null),
            ),
        )

        dropStream()
        freshBind(streamManagement = false)
        jingleManager.notifyRebound()

        assertEquals(1, writes(proposal))
    }

    @Test
    fun aProposalTheWriterRefusedIsSentOnAFreshBindWithoutStreamManagement() {
        // the connection drops: the writer is finished while the stream still counts as bound
        freshBind(streamManagement = false)
        ReflectionHelpers.setField(connection, "tagWriter", TagWriter().apply { finish() })
        val proposal = propose()

        // connect() opens a new writer; the new server has no stream management
        dropStream()
        ReflectionHelpers.setField(connection, "tagWriter", recorder)
        freshBind(streamManagement = false)
        jingleManager.notifyRebound()

        assertEquals(1, writes(proposal))
    }

    @Test
    fun aDirectCallRegisteredAfterABindCarriesThatBindsSession() {
        service.databaseBackend = DatabaseBackend.isolated(app, "unused", ByteArray(0))
        val sessionA = Any()
        val sessionB = Any()
        ReflectionHelpers.setField(connection, "streamSession", sessionA)
        var registered: JingleRtpConnection? = null
        // the call is constructed in session A; bind B completes as it is published
        val connections =
            object : ConcurrentHashMap<AbstractJingleConnection.Id, AbstractJingleConnection>() {
                override fun put(key: AbstractJingleConnection.Id, value: AbstractJingleConnection): AbstractJingleConnection? {
                    if (registered == null && value is JingleRtpConnection) {
                        registered = value
                        synchronized(connection) { ReflectionHelpers.setField(connection, "streamSession", sessionB) }
                    }
                    return super.put(key, value)
                }
            }
        ReflectionHelpers.setField(jingleManager, "connections", connections)

        // a direct (full-JID) call; on the JVM WebRTC does not load, so it ends right after
        jingleManager.initializeRtpSession(Jid.of("friend@example.com/phone"), setOf(Media.AUDIO))

        // its rebound must judge it by bind B, on which it was registered and sent
        assertSame(sessionB, checkNotNull(registered).streamSession)
    }
}
