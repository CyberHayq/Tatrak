package am.cyberhayq.chat.ui.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.Transferable
import eu.siacs.conversations.entities.TransferablePlaceholder
import eu.siacs.conversations.services.XmppConnectionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Tatrak: a caption shows under its attachment in every state of the attachment. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class CaptionSnapshotTest {

    // without a connection there is no roster, so the chat's encryption is given here
    private val conversation = object : Conversation(
        "c1", "Alice", null, "a1", eu.siacs.conversations.xmpp.Jid.of("alice@example.com"), 0, Conversation.STATUS_AVAILABLE,
        Conversational.MODE_SINGLE, Conversation.Attributes.parse(null),
    ) {
        override fun getNextEncryption() = Message.ENCRYPTION_NONE
    }.apply { account = eu.siacs.conversations.entities.Account(eu.siacs.conversations.xmpp.Jid.of("tester@example.com"), "secret") }

    private fun attachment(uuid: String, caption: String? = "look at this"): Message =
        Message(conversation, "https://up.example.com/$uuid.jpg|1000|640|480", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply {
            this.uuid = uuid
            type = Message.TYPE_IMAGE
            this.caption = caption
        }

    private fun items(vararg messages: Message): Map<String, MessageItem> {
        val params = SnapshotParams(TextPalette(0, 0, 0, 0, 0), null, 48, true, 0, false, MentionTracker())
        return ChatSnapshot.items(ApplicationProvider.getApplicationContext(), XmppConnectionService(), conversation, params, messages.toList())
            .filterIsInstance<MessageItem>().associateBy { it.key }
    }

    @Test
    fun captionShowsInEveryAttachmentState() {
        val shown = attachment("shown")
        // the local file is gone
        val missing = attachment("missing").apply { isDeleted = true }
        val downloading = attachment("downloading").apply {
            type = Message.TYPE_TEXT // HttpDownloadConnection.init, until the download ends
            setOob(true)
            transferable = TransferablePlaceholder(Transferable.STATUS_DOWNLOADING)
        }
        val cancelled = attachment("cancelled").apply {
            type = Message.TYPE_TEXT
            setOob(true)
            transferable = TransferablePlaceholder(Transferable.STATUS_CANCELLED)
        }
        val failed = attachment("failed").apply {
            type = Message.TYPE_TEXT
            setOob(true)
            transferable = TransferablePlaceholder(Transferable.STATUS_FAILED)
        }
        val plain = attachment("plain", caption = null)
        val items = items(shown, missing, downloading, cancelled, failed, plain)
        for (key in listOf("shown", "missing", "downloading", "cancelled", "failed")) {
            assertEquals(key, "look at this", items.getValue(key).caption?.text)
        }
        assertEquals(MessageKind.IMAGE, items.getValue("shown").kind)
        assertNull(items.getValue("plain").caption)
    }

    @Test
    fun anErasedAttachmentShowsNoCaption() {
        val retracted = attachment("retracted").apply { eraseContent() }
        assertNull(items(retracted).getValue("retracted").caption)
    }
}
