package eu.siacs.conversations.ui

import android.app.Application
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class ArchiveReturnTest {

    private val conversation =
        Conversation("c", "c", null, "a", Jid.of("peer@example.com"), 0, 0, Conversational.MODE_SINGLE, Conversation.Attributes())

    private fun message(time: Long, status: Int) =
        Message(conversation, "x", Message.ENCRYPTION_NONE, status).apply { setTime(time) }

    private val received = Message.STATUS_RECEIVED
    private val sent = Message.STATUS_SEND

    @Test
    fun receivedBeforeOwnReplyStillCounts() {
        assertTrue(hasReceivedSince(listOf(message(50, received), message(150, received), message(160, sent)), 100))
    }

    @Test
    fun onlyOwnMessagesOrOlderOnesDoNot() {
        assertFalse(hasReceivedSince(listOf(message(50, received), message(150, sent)), 100))
        assertFalse(hasReceivedSince(listOf(message(100, received)), 100))
        assertFalse(hasReceivedSince(emptyList(), 100))
    }
}
