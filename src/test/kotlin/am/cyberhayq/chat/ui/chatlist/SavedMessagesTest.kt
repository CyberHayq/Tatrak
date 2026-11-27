package am.cyberhayq.chat.ui.chatlist

import am.cyberhayq.chat.ui.accounts.AccountFilter
import am.cyberhayq.chat.ui.theme.FloatingBars
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.ReviewFlowsTest.TestAccount
import eu.siacs.conversations.xmpp.Jid
import java.util.function.Consumer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class SavedMessagesTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val a = TestAccount("alice@example.com")
    private val b = TestAccount("bob@example.com")

    /** The chats the service finds or creates, and what it persisted. */
    private inner class Service(private val accounts: List<Account> = listOf(a, b)) : XmppConnectionService() {
        init {
            attachBaseContext(context)
        }

        val chats = mutableListOf<Conversation>()
        val persisted = mutableListOf<Conversation>()

        override fun getAccounts() = accounts

        override fun findOrCreateConversation(account: Account, jid: Jid, muc: Boolean, async: Boolean): Conversation {
            assertFalse(muc)
            return chats.firstOrNull { it.account == account && it.address == jid }
                ?: Conversation(jid.local, account, jid, Conversational.MODE_SINGLE).also { chats += it }
        }

        override fun updateConversation(conversation: Conversation) {
            persisted += conversation
        }

        override fun updateConversationUi() {}
    }

    @Before
    fun floatingDock() = FloatingBars.set(context, true)

    @Test
    fun firstSignInCreatesAndPinsTheSelfChat() {
        val service = Service()
        SavedMessages.pinOnFirstSignIn(service, a)
        val chat = service.chats.single()
        assertEquals(a.jid.asBareJid(), chat.address)
        assertTrue(chat.isPinnedOnTop)
        assertEquals(listOf(chat), service.persisted)
    }

    @Test
    fun firstSignInPinsAnExistingSelfChatAndTakesItOutOfTheArchive() {
        val service = Service()
        val existing = SavedMessages.conversation(service, a)
        // in Tatrak's own archive folder a pinned chat would stay hidden
        existing.tatrakArchivedAt = 1L
        assertFalse(existing.isPinnedOnTop)
        SavedMessages.pinOnFirstSignIn(service, a)
        assertEquals(listOf(existing), service.chats)
        assertTrue(existing.isPinnedOnTop)
        assertNull(existing.tatrakArchivedAt)
        assertEquals(listOf(existing), service.persisted)
    }

    @Test
    fun theFilteredAccountOpensDirectlyOtherwiseThePickerAsks() {
        val service = Service()
        val opened = mutableListOf<Conversation>()
        var asked = 0
        val picker: (Consumer<Account>) -> Unit = { asked++; it.accept(b) }

        AccountFilter.select(context, a.uuid)
        SavedMessages.open(service, picker, { true }) { opened += it }
        assertEquals(0, asked)
        assertEquals(a.jid.asBareJid(), opened.last().address)

        AccountFilter.select(context, null)
        SavedMessages.open(service, picker, { true }) { opened += it }
        assertEquals(1, asked)
        assertEquals(b.jid.asBareJid(), opened.last().address)

        // a disabled filtered account cannot write: the picker of enabled accounts asks
        AccountFilter.select(context, a.uuid)
        a.setOption(Account.OPTION_DISABLED, true)
        SavedMessages.open(service, picker, { true }) { opened += it }
        assertEquals(2, asked)
        // opening never pins
        assertTrue(opened.none { it.isPinnedOnTop })
    }
}
