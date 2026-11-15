package am.cyberhayq.chat.ui.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.xmpp.Jid
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Tatrak: the menu's "Sent / Received" lines come only for received messages with a known time. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class ReceivedAtSnapshotTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // without a connection there is no roster, so the chat's encryption is given here
    private val conversation = object : Conversation(
        "c1", "Alice", null, "a1", Jid.of("alice@example.com"), 0, Conversation.STATUS_AVAILABLE,
        Conversational.MODE_SINGLE, Conversation.Attributes.parse(null),
    ) {
        override fun getNextEncryption() = Message.ENCRYPTION_NONE
    }.apply { account = Account(Jid.of("tester@example.com"), "secret") }

    private fun message(uuid: String, status: Int, received: Long?): Message =
        Message(conversation, "hello", Message.ENCRYPTION_NONE, status).apply {
            this.uuid = uuid
            setTime(1_000L)
            timeReceived = received
        }

    private fun items(vararg messages: Message): Map<String, MessageItem> {
        val params = SnapshotParams(TextPalette(0, 0, 0, 0, 0), null, 48, true, 0, false, MentionTracker())
        return ChatSnapshot.items(context, XmppConnectionService(), conversation, params, messages.toList())
            .filterIsInstance<MessageItem>().associateBy { it.key }
    }

    @Test
    fun onlyReceivedMessagesWithAKnownTimeGetTheLines() {
        val items =
            items(
                message("in", Message.STATUS_RECEIVED, 5_000L),
                message("unknown", Message.STATUS_RECEIVED, null),
                message("own", Message.STATUS_SEND_DISPLAYED, 5_000L),
            )
        assertEquals(5_000L, items.getValue("in").receivedAt)
        assertEquals(1_000L, items.getValue("in").sentAt)
        assertNull(items.getValue("unknown").receivedAt)
        assertNull(items.getValue("own").receivedAt)
    }

    @Test
    fun theYearShowsOnlyWhenNotTheCurrentOne() {
        fun at(time: LocalDateTime) = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = LocalDateTime.now().withNano(0)
        val thisYear = receiptTime(context, Locale.US, at(now))
        val lastYear = receiptTime(context, Locale.US, at(now.minusYears(1)))
        assertFalse(thisYear, thisYear.contains(now.year.toString()))
        assertTrue(lastYear, lastYear.contains(now.minusYears(1).year.toString()))
        // seconds are shown
        assertTrue(thisYear, thisYear.contains(":%02d".format(now.second)))
    }
}
