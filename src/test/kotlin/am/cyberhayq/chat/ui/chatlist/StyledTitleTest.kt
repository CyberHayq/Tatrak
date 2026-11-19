package am.cyberhayq.chat.ui.chatlist

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Tatrak: a stranger's address in the chat list title gets IrregularUnicodeDetector's marks, as in ConversationAdapter. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class StyledTitleTest {

    // the snapshot builders get the application context, as here
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun chat(address: Jid, name: CharSequence) =
        object : Conversation(
            "c1", "", null, "a1", address, 0, Conversation.STATUS_AVAILABLE,
            Conversational.MODE_SINGLE, Conversation.Attributes.parse(null),
        ) {
            // the stranger check and the roster need a connection
            override fun getName() = name
            override fun getContact(): Contact { val own = account; return Contact(address).also { it.setAccount(own) } }
        }.apply { account = Account(Jid.of("tester@example.com"), "secret") }

    @Test
    fun lookAlikeLetterOfAStrangerIsMarked() {
        // Cyrillic "а" in front of Latin "lice"
        val address = Jid.of("аlice@example.com")
        val styled = styledChatTitle(context, chat(address, address))!!
        assertEquals(address.toString(), styled.text)
        val span = styled.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(1, span.end)
        assertNotEquals(Color.Unspecified, span.item.color)
    }

    @Test
    fun cleanAddressHasNoMarksAndANameIsNotStyled() {
        val address = Jid.of("alice@example.com")
        assertEquals(emptyList<Any>(), styledChatTitle(context, chat(address, address))!!.spanStyles)
        assertNull(styledChatTitle(context, chat(address, "Alice")))
    }
}
