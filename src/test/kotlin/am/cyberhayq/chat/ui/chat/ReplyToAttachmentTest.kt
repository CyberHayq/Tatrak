package am.cyberhayq.chat.ui.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Replies to attachments quote the kind of file, never its URL (for OMEMO the URL holds the key). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class ReplyToAttachmentTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val conversation =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Conversational::class.java)) { _, _, _ -> null }
            as Conversational
    private val key = "ab".repeat(44)
    private val url = "aesgcm://up.example/photo.jpg#$key"

    private fun message(body: String, status: Int = Message.STATUS_RECEIVED, type: Int = Message.TYPE_TEXT) =
        Message(conversation, body, Message.ENCRYPTION_AXOLOTL, status).also { it.type = type }

    @Test
    fun downloadedImageQuotesItsKind() {
        val image = message(url, type = Message.TYPE_IMAGE)
        assertTrue(isAttachment(image))
        val quote = replyQuote(context, image)
        assertEquals(context.getString(R.string.image), quote)
        assertFalse(quote.contains(key))
    }

    @Test
    fun fileNotYetDownloadedIsAnAttachmentToo() {
        val offered = message(url)
        assertTrue(isAttachment(offered))
        assertFalse(replyQuote(context, offered).contains("aesgcm"))
    }

    @Test
    fun textQuotesItsBody() {
        val text = message("hello\nworld")
        assertFalse(isAttachment(text))
        assertEquals("hello\nworld", replyQuote(context, text))
    }

    @Test
    fun replyIdIsTheStanzaIdInChannelsAndTheMessageIdOtherwise() {
        val received = message(url, type = Message.TYPE_IMAGE).also { it.remoteMsgId = "remote" }
        assertEquals("remote", replyId(received, group = false))
        // channel: no stanza-id yet, so canReply stays off for the attachment
        assertNull(replyId(received, group = true))
        received.serverMsgId = "stanza"
        assertEquals("stanza", replyId(received, group = true))
        val sent = message(url, Message.STATUS_UNSEND, Message.TYPE_IMAGE)
        assertEquals(sent.uuid, replyId(sent, group = false))
    }
}
