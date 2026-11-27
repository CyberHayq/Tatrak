package am.cyberhayq.chat.ui.chat

import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Defects found in the 2026-11-07 interop run: retracted placeholders, stickers, the subscription banner. */
class ChatDefectsTest {
    private val conversation =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Conversational::class.java)) { _, _, _ -> null }
            as Conversational

    private fun image(url: String, size: Int): Pair<Message, Message.FileParams> {
        val message = Message(conversation, url, Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED)
        return message to Message.FileParams().also { it.width = size; it.height = size }
    }

    @Test
    fun retractedPlaceholderIsNotEdited() {
        val message = Message(conversation, "text", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED)
        message.putEdited(Message.BodyVersion("older", Message.ENCRYPTION_NONE, null))
        assertTrue(showsEdited(message))
        message.eraseContent()
        assertTrue(message.edited())
        assertFalse(showsEdited(message))
    }

    @Test
    fun onlyWebpIsASticker() {
        val (webp, webpParams) = image("https://up.example/a.webp", 64)
        val (png, pngParams) = image("https://up.example/a.png", 64)
        val (big, bigParams) = image("https://up.example/b.webp", StickerMaxPx + 1)
        assertTrue(isSticker(webp, webpParams))
        assertFalse(isSticker(png, pngParams))
        assertFalse(isSticker(big, bigParams))
    }

    @Test
    fun subscriptionBannerFollowsTheClassicChain() {
        assertEquals(ChatBanner.ADD_BACK, subscriptionBanner(blocked = false, pendingRequest = true, inRoster = false))
        assertEquals(ChatBanner.ALLOW_SUBSCRIPTION, subscriptionBanner(false, true, true))
        assertEquals(null, subscriptionBanner(false, false, false))
        assertEquals(ChatBanner.BLOCKED, subscriptionBanner(true, true, false))
    }
}
