package am.cyberhayq.chat.ui.chat

import android.app.Application
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xmpp.Jid
import kotlin.random.Random
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** [ChatSnapshot.MessageIndex] must find what the conversation's own lookups find. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class MessageIndexTest {

    @Test
    fun matchesTheConversationLookups() {
        val random = Random(7)
        // a small id pool so uuids, remote ids and server ids collide across messages
        val ids = List(12) { "id$it" }
        repeat(200) { run ->
            val conversation =
                Conversation("c$run", "c", null, "a", Jid.of("peer@example.com"), 0, 0, Conversational.MODE_SINGLE, Conversation.Attributes())
            repeat(random.nextInt(0, 20)) {
                val message = Message(conversation, "x", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED)
                if (random.nextInt(3) == 0) message.uuid = ids.random(random)
                if (random.nextBoolean()) message.remoteMsgId = ids.random(random)
                if (random.nextBoolean()) message.serverMsgId = ids.random(random)
                conversation.add(message)
            }
            val messages = ArrayList<Message>().also { conversation.populateWithMessages(it) }
            val index = ChatSnapshot.MessageIndex(messages)
            for (id in ids) {
                assertSame("run $run server $id", conversation.findMessageWithServerMsgId(id), index.withServerMsgId(id))
                assertSame("run $run id $id", conversation.findMessageWithUuidOrRemoteId(id, null, null), index.withUuidOrRemoteId(id))
            }
        }
    }
}
