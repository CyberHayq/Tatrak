package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.accounts.AccountFilter
import am.cyberhayq.chat.ui.newchat.NewChatMenu
import am.cyberhayq.chat.ui.share.ShareState
import am.cyberhayq.chat.ui.theme.FloatingBars
import android.content.Intent
import android.os.Looper
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.xmpp.Jid
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.util.ReflectionHelpers

/**
 * The owner's option A (2026-11-24) through the real screens: with disabled B as the dock's filter, the Contacts screens
 * list B's contacts but start no chat on it, while New chat and the share picker offer the enabled account.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(
    application = ReviewFlowsTest.TestApp::class,
    sdk = [35],
    qualifiers = "w411dp-h891dp",
    shadows = [ReviewFlowsTest.NoAxolotlStore::class, ReviewFlowsTest.NoPushRenewal::class, ReviewFlowsTest.SearchTaskShadow::class],
)
class DisabledAccountFlowsTest {

    class NewChatHost : StartConversationActivity() {
        override fun connectToBackend() {
            xmppConnectionService = checkNotNull(ReviewFlowsTest.Host.service)
            xmppConnectionServiceBound = true
            onBackendConnected()
        }

        override fun onStop() {
            xmppConnectionServiceBound = false
            super.onStop()
        }
    }

    class ShareHost : ShareWithActivity() {
        override fun connectToBackend() {
            xmppConnectionService = checkNotNull(ReviewFlowsTest.Host.service)
            xmppConnectionServiceBound = true
            onBackendConnected()
        }

        override fun onStop() {
            xmppConnectionServiceBound = false
            super.onStop()
        }
    }

    private val app = ApplicationProvider.getApplicationContext<ReviewFlowsTest.TestApp>()
    private lateinit var service: ReviewFlowsTest.TestService
    private val controllers = ArrayList<ActivityController<*>>()
    private val ann = Jid.of("ann@example.com")
    private val ben = Jid.of("ben@example.com")

    @Before
    fun start() {
        FloatingBars.set(app, true)
        service = ReviewFlowsTest.TestService(app)
        ReviewFlowsTest.Host.service = service
        service.a.roster.getContact(ann).setOption(Contact.Options.IN_ROSTER)
        service.b.roster.getContact(ben).setOption(Contact.Options.IN_ROSTER)
        service.b.setOption(Account.OPTION_DISABLED, true)
        AccountFilter.select(app, service.b.uuid)
    }

    @After
    fun stop() {
        shadowOf(Looper.getMainLooper()).idle()
        controllers.forEach { it.close() }
        shadowOf(Looper.getMainLooper()).idle()
        ReviewFlowsTest.Host.service = null
    }

    private fun <T : android.app.Activity> launch(type: Class<T>, intent: Intent): T {
        val controller = Robolectric.buildActivity(type, intent.setClass(app, type))
        controllers += controller
        return controller.setup().get()
    }

    private fun addresses(contacts: List<*>) = contacts.map { (it as Contact).address }

    @Test
    fun newChatOffersTheEnabledAccount() {
        val newChat = launch(NewChatHost::class.java, Intent())
        assertEquals(listOf(ann), addresses(newChat.contacts))
        assertEquals(listOf("alice@example.com"), ReflectionHelpers.getField<List<String>>(newChat, "mActivatedAccounts"))
    }

    @Test
    fun drawerContactsShowTheDisabledAccountAndOpenNoNewChat() {
        val contacts = launch(NewChatHost::class.java, Intent().putExtra(NewChatCompose.EXTRA_CONTACTS, true))
        assertEquals(listOf(ben), addresses(contacts.contacts))
        val contact = contacts.contacts[0] as Contact
        contacts.openConversationForContact(contact)
        assertEquals(ContactDetailsActivity::class.java.name, shadowOf(contacts).nextStartedActivity.component?.className)
        assertTrue(service.chats.isEmpty())

        val chat = service.chat(service.b, ben)
        contacts.openConversationForContact(contact)
        assertEquals(chat.uuid, shadowOf(contacts).nextStartedActivity.getStringExtra(ConversationsActivity.EXTRA_CONVERSATION))
        assertEquals(1, service.chats.size)
    }

    @Test
    fun contactsTabShowsTheDisabledAccountWithoutCallOrNewChat() {
        val host = launch(ReviewFlowsTest.Host::class.java, Intent())
        host.supportFragmentManager.executePendingTransactions()
        val list = host.supportFragmentManager.findFragmentById(eu.siacs.conversations.R.id.main_fragment) as ChatListFragment
        val tab = ReflectionHelpers.getField<ContactsTab>(list, "contactsTab")
        tab.filter()
        val entry = tab.state.value.contacts.single()
        assertEquals(ben.toString(), entry.key.substringAfter('/'))
        assertFalse(NewChatMenu.CALL in entry.menu)
        tab.openContact(entry.key)
        assertEquals(ContactDetailsActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
        assertTrue(service.chats.isEmpty())
    }

    private fun shareTargets(share: ShareWithActivity) =
        ReflectionHelpers.getField<MutableStateFlow<ShareState>>(ReflectionHelpers.getField(share, "compose"), "state").value.targets.map { it.key }

    private fun textShare() = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")

    @Test
    fun sharePickerOffersOnlyEnabledAccountsChats() {
        val a = service.chat(service.a, ann)
        service.chat(service.b, ben)
        assertEquals(listOf(a.uuid), shareTargets(launch(ShareHost::class.java, textShare())))
        // all accounts too: a disabled account's chat is no target
        AccountFilter.select(app, null)
        assertEquals(listOf(a.uuid), shareTargets(launch(ShareHost::class.java, textShare())))
    }

    @Test
    fun directShareToADisabledAccountsChatFallsBackToThePicker() {
        val a = service.chat(service.a, ann)
        val b = service.chat(service.b, ben)
        for (chat in listOf(a, b)) {
            val extras = android.os.PersistableBundle().apply { putString(ConversationsActivity.EXTRA_CONVERSATION, chat.uuid) }
            ShortcutManagerCompat.addDynamicShortcuts(
                app,
                listOf(ShortcutInfoCompat.Builder(app, chat.uuid).setShortLabel(chat.uuid).setIntent(Intent(Intent.ACTION_VIEW)).setExtras(extras).build()),
            )
        }
        val toB = launch(ShareHost::class.java, textShare().putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, b.uuid))
        assertFalse(toB.isFinishing)
        assertNull(opened(toB))
        assertEquals(listOf(a.uuid), shareTargets(toB))

        val toA = launch(ShareHost::class.java, textShare().putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, a.uuid))
        assertTrue(toA.isFinishing)
        assertEquals(a.uuid, opened(toA))
    }

    /** The chat a started ConversationsActivity opens (the storage gate's start in a test is skipped). */
    private fun opened(activity: android.app.Activity): String? =
        generateSequence { shadowOf(activity).nextStartedActivity }
            .firstOrNull { it.component?.className == ConversationsActivity::class.java.name }
            ?.getStringExtra(ConversationsActivity.EXTRA_CONVERSATION)
}
