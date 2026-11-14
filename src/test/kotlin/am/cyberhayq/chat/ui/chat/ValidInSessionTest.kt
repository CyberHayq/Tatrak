package am.cyberhayq.chat.ui.chat

import android.app.Application
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xmpp.Jid
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The one-pass [ChatSnapshot.validInSession] must agree with [Message.isValidInSession]. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class ValidInSessionTest {

    private val encryptions =
        intArrayOf(
            Message.ENCRYPTION_NONE,
            Message.ENCRYPTION_AXOLOTL,
            Message.ENCRYPTION_AXOLOTL_FAILED,
            Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE,
            Message.ENCRYPTION_PGP,
            Message.ENCRYPTION_DECRYPTED,
        )
    private val statuses = intArrayOf(Message.STATUS_RECEIVED, Message.STATUS_SEND, Message.STATUS_SEND_RECEIVED)

    /** The real next encryption needs an account and settings; only its value matters here. */
    private class Chat(uuid: String, private val next: Int) :
        Conversation(uuid, "c", null, "a", Jid.of("peer@example.com"), 0, 0, Conversational.MODE_SINGLE, Conversation.Attributes()) {
        override fun getNextEncryption() = next
    }

    @Test
    fun importedPqHistoryDoesNotConsultLegacyTrust() {
        // No legacy account/service exists: historical PQ flags must never reach that lookup.
        val chat = Chat("imported", Message.ENCRYPTION_AXOLOTL)
        for (encryption in 8..10) {
            val message = Message(chat, "history", encryption, Message.STATUS_RECEIVED)
            assertEquals(false, message.isTrusted)
            assertEquals(false, message.acceptMessageCorrection())
        }
    }

    @Test
    fun matchesThePerMessageCheck() {
        val random = Random(42)
        repeat(300) { run ->
            val conversation = Chat("c$run", encryptions.random(random))
            repeat(random.nextInt(0, 25)) {
                val message = Message(conversation, "x", encryptions.random(random), statuses.random(random))
                message.setCarbon(random.nextInt(5) == 0)
                conversation.add(message)
            }
            val messages = ArrayList<Message>().also { conversation.populateWithMessages(it) }
            val expected = messages.map { it.isValidInSession() }
            val actual = ChatSnapshot.validInSession(messages, conversation.nextEncryption).toList()
            assertEquals("run $run", expected, actual)
        }
    }
}
