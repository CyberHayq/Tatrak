package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.accounts.AccountFilter
import am.cyberhayq.chat.ui.chatlist.MainTab
import am.cyberhayq.chat.ui.theme.FloatingBars
import android.Manifest
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.xmpp.Jid
import eu.siacs.conversations.services.UnifiedPushBroker
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.xml.Namespace
import eu.siacs.conversations.xml.TagWriter
import eu.siacs.conversations.xmpp.manager.DiscoManager
import eu.siacs.conversations.xmpp.manager.MessageArchiveManager
import im.conversations.android.xmpp.model.StreamElement
import im.conversations.android.xmpp.model.disco.info.InfoQuery
import im.conversations.android.xmpp.model.stanza.Iq
import im.conversations.android.xmpp.processor.BindProcessor
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Issue #6 through the real callers: BindProcessor's first-login branch, the chat list's picker, the Profile row. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(
    application = ReviewFlowsTest.TestApp::class,
    sdk = [35],
    qualifiers = "w411dp-h891dp",
    shadows = [ReviewFlowsTest.NoAxolotlStore::class, SavedMessagesFlowsTest.NoPush::class, SavedMessagesFlowsTest.NoAccountWrites::class],
)
class SavedMessagesFlowsTest {

    /** BindProcessor saves the account; SQLCipher does not run on the JVM. */
    @Implements(value = DatabaseBackend::class, isInAndroidSdk = false)
    class NoAccountWrites {
        @Implementation
        fun updateAccount(account: Account) = true
    }

    /** ReviewFlowsTest.NoPushRenewal, plus the bind's last step (BindProcessor runs to its end here). */
    @Implements(value = UnifiedPushBroker::class, isInAndroidSdk = false)
    class NoPush {
        @Implementation
        fun __constructor__(service: XmppConnectionService) = Unit

        @Implementation
        fun renewUnifiedPushEndpointsOnBind(account: Account) = Unit
    }

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var controller: ActivityController<ReviewFlowsTest.Host>
    private val activity get() = controller.get()
    private val fm get() = activity.supportFragmentManager
    private val service get() = checkNotNull(ReviewFlowsTest.Host.service)
    private val main get() = fm.findFragmentById(R.id.main_fragment)

    @Before
    fun start() {
        val app = ApplicationProvider.getApplicationContext<ReviewFlowsTest.TestApp>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS)
        FloatingBars.set(app, true)
        AccountFilter.select(app, null)
        ReviewFlowsTest.Host.service = ReviewFlowsTest.TestService(app)
        controller = Robolectric.buildActivity(ReviewFlowsTest.Host::class.java).setup()
        fm.executePendingTransactions()
    }

    @After
    fun stop() {
        try {
            shadowOf(Looper.getMainLooper()).idle()
            controller.close()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            ReviewFlowsTest.Host.service = null
        }
    }

    private fun selfChats() = service.chats.filter { it.contact.isSelf }

    private fun openedChat(): Conversation? = (main as? ChatFragment)?.getConversation()

    private fun openFromList() {
        (main as ChatListFragment).openSavedMessages()
        fm.executePendingTransactions()
    }

    private fun picker(): AlertDialog? = ShadowDialog.getLatestDialog() as AlertDialog?

    @Test
    fun onlyTheFirstBindPinsTheSelfChat() {
        service.databaseBackend = DatabaseBackend.isolated(activity, "unused", ByteArray(0))
        val account = service.a
        val bind = ReflectionHelpers.getField<BindProcessor>(account.xmppConnection, "bindProcessor")
        bind.run()
        val chat = selfChats().single()
        assertEquals(account, chat.account)
        assertTrue(chat.isPinnedOnTop)

        // the owner unpins it (or deletes it); a reconnect binds again and leaves it so
        chat.isPinnedOnTop = false
        service.chats.remove(chat)
        bind.run()
        assertTrue(selfChats().isEmpty())
    }

    @Test
    fun noEnabledAccountOpensNothing() {
        service.a.setOption(Account.OPTION_DISABLED, true)
        service.b.setOption(Account.OPTION_DISABLED, true)
        openFromList()
        assertNull(picker())
        assertTrue(selfChats().isEmpty())
        assertTrue(main is ChatListFragment)
    }

    @Test
    fun oneEnabledAccountOpensWithoutAsking() {
        service.b.setOption(Account.OPTION_DISABLED, true)
        openFromList()
        assertNull(picker())
        assertEquals(service.a.jid.asBareJid(), openedChat()?.address)
        assertFalse(openedChat()!!.isPinnedOnTop)
    }

    @Test
    fun severalAccountsAskAndOpenTheChosenOne() {
        openFromList()
        val dialog = checkNotNull(picker())
        assertTrue(selfChats().isEmpty())
        dialog.listView.performItemClick(null, 1, 1)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        fm.executePendingTransactions()
        assertEquals(service.b.jid.asBareJid(), openedChat()?.address)
    }

    @Test
    fun anAnswerAfterTheListLeftOpensNothing() {
        openFromList()
        val dialog = checkNotNull(picker())
        val list = main as ChatListFragment
        // phone: a chat opened meanwhile replaces the list
        val other = service.chat(service.a, Jid.of("friend@example.com"))
        activity.switchToConversation(other)
        fm.executePendingTransactions()
        assertFalse(list.isAdded)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        fm.executePendingTransactions()
        assertTrue(selfChats().isEmpty())
        assertSame(other, openedChat())
    }

    @Test
    fun anAccountDisabledWhileThePickerShowsOpensNothing() {
        openFromList()
        val dialog = checkNotNull(picker())
        // the dialog's default choice is the first account
        service.a.setOption(Account.OPTION_DISABLED, true)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        fm.executePendingTransactions()
        assertTrue(selfChats().isEmpty())
        assertTrue(main is ChatListFragment)
    }

    @Test
    fun theProfileTabRowOpensTheSelfChat() {
        AccountFilter.select(activity, service.b.uuid)
        ReflectionHelpers.callInstanceMethod<Unit>(main, "selectTab", ClassParameter.from(MainTab::class.java, MainTab.PROFILE))
        compose.onNodeWithText("Saved messages").performClick()
        fm.executePendingTransactions()
        assertEquals(service.b.jid.asBareJid(), openedChat()?.address)
    }

    /** Waits for the work queued on DATABASE_READER before now (a serial executor). */
    private fun drainDatabaseReader() {
        val done = CountDownLatch(1)
        XmppConnectionService.DATABASE_READER.execute { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
    }

    /**
     * Review finding: a first bind after the disco timeout makes the self-chat before MAM is known, so its
     * post-processing asks nothing. Opening it empty asks for the first page once MAM is known, once.
     */
    @Test
    fun theEmptySelfChatAsksTheServerArchiveOnceWhenOpened() {
        val account = service.a
        val connection = account.xmppConnection
        val iqs = Collections.synchronizedList(ArrayList<StreamElement>())
        ReflectionHelpers.setField(connection, "tagWriter", object : TagWriter() {
            override fun writeStanzaAsync(stanza: StreamElement): Boolean = true.also { if (stanza is Iq) iqs += stanza }
        })
        ReflectionHelpers.setField(connection, "isBound", true)
        account.online = true
        AccountFilter.select(activity, account.uuid)
        val chat = service.chat(account, account.jid.asBareJid())
        val mam = connection.getManager(MessageArchiveManager::class.java)
        fun open() {
            activity.switchToConversation(chat)
            fm.executePendingTransactions()
            drainDatabaseReader()
        }

        // MAM not known yet (disco timed out): nothing to ask
        open()
        assertFalse(mam.queryInProgress(chat))

        ReflectionHelpers.callInstanceMethod<Unit>(
            connection.getManager(DiscoManager::class.java),
            "put",
            ClassParameter.from(Jid::class.java, account.jid.asBareJid()),
            ClassParameter.from(InfoQuery::class.java, InfoQuery(emptyList(), listOf(Namespace.MESSAGE_ARCHIVE_MANAGEMENT))),
        )
        open()
        assertTrue(mam.queryInProgress(chat))
        val sent = iqs.size
        assertEquals(1, sent)
        // still running: no second query
        open()
        assertEquals(sent, iqs.size)

        // cleared (or the archive reached its end): not asked again
        // (MessageArchiveManager.kill needs the real AxolotlService)
        ReflectionHelpers.getField<MutableCollection<*>>(mam, "queries").clear()
        chat.setHasMessagesLeftOnServer(false)
        open()
        assertFalse(mam.queryInProgress(chat))
        assertEquals(sent, iqs.size)
    }
}
