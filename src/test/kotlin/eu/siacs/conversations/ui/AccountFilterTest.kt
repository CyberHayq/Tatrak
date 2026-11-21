package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.accounts.AccountFilter
import am.cyberhayq.chat.ui.chatlist.ChatListSnapshot
import am.cyberhayq.chat.ui.theme.FloatingBars
import am.cyberhayq.chat.ui.theme.TatrakStyle
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.android.AbstractPhoneContact
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.ListItem
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.Roster
import im.conversations.android.xmpp.model.stanza.Presence
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.xmpp.Jid
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class AccountFilterTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    /** An account without a connection: its roster and its contacts' presence are kept here. */
    private class TestAccount(jid: String) : Account(Jid.of(jid), "secret") {
        private val byJid = LinkedHashMap<Jid, Contact>()
        private val roster =
            object : Roster {
                override fun getContacts(): List<Contact> = byJid.values.toList()

                override fun getWithSystemAccounts(clazz: Class<out AbstractPhoneContact>) = emptyList<Contact>()

                override fun getContact(jid: Jid): Contact =
                    byJid.getOrPut(jid.asBareJid()) {
                        object : Contact(jid.asBareJid()) {
                            override fun getShownStatus() = Presence.Availability.OFFLINE
                        }.also { it.setAccount(this@TestAccount) }
                    }

                override fun getContactFromContactList(jid: Jid): Contact? = byJid[jid.asBareJid()]
            }

        override fun getRoster() = roster
    }

    private val a = TestAccount("alice@example.com")
    private val b = TestAccount("bob@example.com")

    private fun chat(account: Account, peer: String, unread: Int = 0, archived: Boolean = false, muted: Boolean = false) =
        Conversation(peer, peer, null, account.uuid, Jid.of("$peer@example.com"), 0, Conversation.STATUS_AVAILABLE, Conversational.MODE_SINGLE, Conversation.Attributes())
            .apply {
                this.account = account
                repeat(unread) { add(Message(this, "hi", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply { markUnread() }) }
                if (archived) tatrakArchivedAt = 1L
                if (muted) mutedTill = Instant.now().plusSeconds(3600)
            }

    private val a1 = chat(a, "a1", unread = 2)
    private val a2 = chat(a, "a2", unread = 1, archived = true)
    private val b1 = chat(b, "b1", unread = 1)
    private val b2 = chat(b, "b2", unread = 3, muted = true)

    /** The service as the screens see it: its accounts and chats, and the app's preferences. */
    private inner class Service(private val accounts: List<Account> = listOf(a, b)) : XmppConnectionService() {
        init {
            attachBaseContext(context)
        }

        private val chats = listOf(a1, a2, b1, b2)

        override fun getAccounts() = accounts

        override fun getConversations() = chats

        override fun populateWithOrderedConversations(list: MutableList<Conversation>) {
            list.addAll(chats)
        }
    }

    // the switcher lives on the floating dock; the default style (Telegram) has none
    @Before
    fun floatingDock() = FloatingBars.set(context, true)

    private fun snapshot(service: XmppConnectionService) =
        ChatListSnapshot.build(context, service, avatarSize = 48, avatarsCachedOnly = true, thumbnailSize = 20)

    @Test
    fun chatsArchiveAndCountersFollowTheChosenAccount() {
        val service = Service()
        AccountFilter.select(context, a.uuid)
        val filtered = snapshot(service)
        assertEquals(listOf("a1"), filtered.chats.map { it.uuid })
        assertEquals(listOf("a2"), filtered.archived.map { it.uuid })
        assertEquals(a.uuid, filtered.selectedAccount)
        assertEquals("alice@example.com", filtered.account?.address)
        // the switcher counts every account, as the folder tabs do: unmuted chats outside the archive
        assertEquals(mapOf(a.uuid to 1, b.uuid to 1), filtered.accounts.associate { it.uuid to it.unread })

        AccountFilter.select(context, null)
        val all = snapshot(service)
        assertEquals(listOf("a1", "b1", "b2"), all.chats.map { it.uuid })
        assertNull(all.selectedAccount)
    }

    @Test
    fun oneEnabledAccountMeansNoFilter() {
        b.setOption(Account.OPTION_DISABLED, true)
        AccountFilter.select(context, a.uuid)
        val service = Service()
        assertNull(AccountFilter.selected(service))
        assertEquals(listOf("a1", "b1", "b2"), snapshot(service).chats.map { it.uuid })
    }

    @Test
    fun aRemovedOrDisabledAccountFallsBackToAll() {
        AccountFilter.select(context, b.uuid)
        val c = TestAccount("carol@example.com")
        assertNull(AccountFilter.selected(Service(listOf(a, c))))
        b.setOption(Account.OPTION_DISABLED, true)
        assertNull(AccountFilter.selected(Service(listOf(a, b, c))))
        b.setOption(Account.OPTION_DISABLED, false)
        assertEquals(b, AccountFilter.selected(Service(listOf(a, b, c))))
    }

    @Test
    fun noFloatingDockMeansNoFilterUntilItComesBack() {
        val service = Service()
        AccountFilter.select(context, a.uuid)
        FloatingBars.set(context, false)
        assertNull(AccountFilter.selected(service))
        assertEquals(listOf("a1", "b1", "b2"), snapshot(service).chats.map { it.uuid })
        // the Glass styles have the floating dock while the switch was never touched
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().remove(FloatingBars.KEY).commit()
        TatrakStyle.set(context, TatrakStyle.GLASS)
        assertEquals(a, AccountFilter.selected(service))
        TatrakStyle.set(context, TatrakStyle.SLATE_SEA)
        assertNull(AccountFilter.selected(service))
    }

    @Test
    fun anOutsideOpenSwitchesToTheChatsAccount() {
        val service = Service()
        AccountFilter.select(context, b.uuid)
        AccountFilter.follow(service, a)
        assertEquals(a, AccountFilter.selected(service))
        // all accounts already show it
        AccountFilter.select(context, null)
        AccountFilter.follow(service, b)
        assertNull(AccountFilter.selected(service))
    }

    @Test
    fun contactsCallsSearchAndNewChatFollowTheChosenAccount() {
        val service = Service()
        for ((account, peer) in listOf(a to "ann", b to "ben")) {
            account.roster.getContact(Jid.of("$peer@example.com")).setOption(Contact.Options.IN_ROSTER)
        }
        AccountFilter.select(context, a.uuid)

        val contacts = ArrayList<ListItem>()
        ContactFlows.filterContacts(service, contacts, false, null)
        assertEquals(listOf("ann@example.com"), contacts.map { (it as Contact).address.toString() })

        assertEquals(setOf("a1", "a2"), callChats(service).keys)

        assertEquals(listOf("alice@example.com"), AccountFilter.enabledAccounts(service))

        val scope = CoroutineScope(Job())
        val search = MessageSearchResults(context, scope, { service }, true)
        val found = listOf(a1, b1).map { c -> Message(c, "hello", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply { uuid = c.uuid + "-m" } }
        search.publish(listOf("hello"), found)
        scope.cancel()
        assertEquals(listOf("a1-m"), search.state.value.results.map { it.key })

        AccountFilter.select(context, null)
        contacts.clear()
        ContactFlows.filterContacts(service, contacts, false, null)
        assertEquals(2, contacts.size)
        assertEquals(setOf("a1", "a2", "b1", "b2"), callChats(service).keys)
    }
}
