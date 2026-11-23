package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.accounts.AccountFilter
import am.cyberhayq.chat.ui.chat.ChatSnapshot
import am.cyberhayq.chat.ui.chat.FormattedTextCache
import am.cyberhayq.chat.ui.chat.MentionTracker
import am.cyberhayq.chat.ui.chat.SnapshotParams
import am.cyberhayq.chat.ui.chat.TextPalette
import am.cyberhayq.chat.ui.calls.CallsState
import am.cyberhayq.chat.ui.chatlist.MainTab
import am.cyberhayq.chat.ui.theme.FloatingBars
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.Conversations
import eu.siacs.conversations.R
import eu.siacs.conversations.android.AbstractPhoneContact
import eu.siacs.conversations.crypto.axolotl.AxolotlService
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.MucOptions
import im.conversations.android.xmpp.model.muc.Affiliation
import im.conversations.android.xmpp.model.muc.Role
import eu.siacs.conversations.entities.Roster
import eu.siacs.conversations.entities.Transferable
import eu.siacs.conversations.entities.TransferablePlaceholder
import eu.siacs.conversations.services.MessageSearchTask
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.interfaces.OnSearchResultsAvailable
import eu.siacs.conversations.xmpp.Jid
import eu.siacs.conversations.xmpp.XmppConnection
import im.conversations.android.xmpp.model.stanza.Presence
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(
    application = ReviewFlowsTest.TestApp::class,
    sdk = [35],
    qualifiers = "w411dp-h891dp",
    shadows = [ReviewFlowsTest.NoAxolotlStore::class],
)
class ReviewFlowsTest {
    class TestApp : Conversations() {
        override fun onCreate() = Unit
    }

    /** The unstarted connections exercise UI managers without opening SQLCipher or a socket. */
    @Implements(value = AxolotlService::class, isInAndroidSdk = false)
    class NoAxolotlStore {
        @Implementation
        fun __constructor__(account: Account, service: XmppConnectionService) = Unit
    }

    class Host : ConversationsActivity() {
        override fun connectToBackend() {
            xmppConnectionService = service
            xmppConnectionServiceBound = true
        }

        override fun isOptimizingBattery() = false

        fun deliver(intent: Intent) = onNewIntent(intent)

        override fun onStop() {
            // The fake backend is injected directly. No Android ServiceConnection was registered.
            xmppConnectionServiceBound = false
            super.onStop()
        }

        companion object {
            lateinit var service: TestService
        }
    }

    class TestAccount(jid: String) : Account(Jid.of(jid), "fixture") {
        private val byAddress = LinkedHashMap<Jid, Contact>()
        private val fakeRoster = object : Roster {
            override fun getContacts() = byAddress.values.toList()

            override fun getWithSystemAccounts(clazz: Class<out AbstractPhoneContact>) = emptyList<Contact>()

            override fun getContact(jid: Jid): Contact =
                byAddress.getOrPut(jid.asBareJid()) {
                    object : Contact(jid.asBareJid()) {
                        override fun getShownStatus() = Presence.Availability.OFFLINE
                    }.also { it.setAccount(this@TestAccount) }
                }

            override fun getContactFromContactList(jid: Jid): Contact? = byAddress[jid.asBareJid()]
        }

        override fun getRoster() = fakeRoster
    }

    class TestService(context: Context) : XmppConnectionService() {
        val a = TestAccount("alice@example.com")
        val b = TestAccount("bob@example.com")
        val chats = ArrayList<Conversation>()
        val created = ArrayList<Jid>()
        val saved = ArrayList<String>()

        init {
            attachBaseContext(context)
            for (account in accounts) account.xmppConnection = XmppConnection(account, this)
        }

        override fun getAccounts(): List<Account> = listOf(a, b)

        override fun getConversations(): List<Conversation> = chats

        override fun populateWithOrderedConversations(list: MutableList<Conversation>) {
            list.addAll(chats)
        }

        override fun findAccountByJid(jid: Jid): Account? = accounts.firstOrNull { it.jid.asBareJid() == jid.asBareJid() }

        override fun findOrCreateConversation(account: Account, jid: Jid, muc: Boolean, async: Boolean): Conversation =
            chat(account, jid, muc)

        override fun createContact(contact: Contact, preAuth: String?) {
            created += contact.address
        }

        override fun isConversationStillOpen(conversation: Conversation) = conversation in chats

        override fun updateConversation(conversation: Conversation) {
            saved += conversation.uuid
        }

        fun chat(account: Account, jid: Jid, muc: Boolean = false): Conversation =
            object : Conversation(jid.toString(), account, jid, if (muc) Conversation.MODE_MULTI else Conversation.MODE_SINGLE) {
                override fun getNextEncryption() = Message.ENCRYPTION_NONE
            }.also(chats::add)
    }

    private lateinit var controller: ActivityController<Host>
    private val activity get() = controller.get()
    private val fm get() = activity.supportFragmentManager
    private val service get() = Host.service
    private val main get() = fm.findFragmentById(R.id.main_fragment)
    private val secondary get() = fm.findFragmentById(R.id.secondary_fragment)
    private val peer = Jid.of("friend@example.com")

    @Before
    fun start() {
        val app = ApplicationProvider.getApplicationContext<TestApp>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS)
        FloatingBars.set(app, true)
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(AppSettings.SEND_CHAT_STATES, false).apply()
        Host.service = TestService(app)
        controller = Robolectric.buildActivity(Host::class.java).setup()
        fm.executePendingTransactions()
    }

    @After
    fun stop() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    @Test
    fun addingContactKeepsTheNewChatOnThePhoneBackStack() = addContact(tablet = false)

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun addingContactOpensTheNewChatInTheTabletPane() = addContact(tablet = true)

    @Test
    fun restoredAddContactSubmitsToTheCurrentHost() {
        showAddContact(prefilled = null)
        val input = addDialog().findViewById<EditText>(R.id.jid)!!
        input.setText(peer.toString())
        val old = activity
        controller.recreate()
        fm.executePendingTransactions()
        assertFalse(old.xmppConnectionServiceBound)
        assertEquals(peer.toString(), addDialog().findViewById<EditText>(R.id.jid)!!.text.toString())
        submitContact()
        assertEquals(listOf(peer), service.created)
        assertEquals(peer, (main as ChatFragment).getConversation()?.address)
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun tabletRestoresSelectedTab() {
        assertTrue(ConversationsActivity.isTabletView(activity))
        selectTab(MainTab.CALLS)
        controller.recreate()
        fm.executePendingTransactions()
        assertEquals(MainTab.CALLS, ReflectionHelpers.callInstanceMethod<MainTab>(main, "getTab"))
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun tabletReconcilesTheOpenChatOnAccountSwitch() {
        val a = service.chat(service.a, peer)
        val b = service.chat(service.b, Jid.of("other@example.com"))
        AccountFilter.select(activity, service.a.uuid)
        activity.switchToConversation(a)
        fm.executePendingTransactions()
        assertSame(a, (secondary as ChatFragment).getConversation())
        selectAccount(service.b.uuid)
        fm.executePendingTransactions()
        assertSame(b, (secondary as ChatFragment).getConversation())

        b.tatrakArchivedAt = 1L
        selectAccount(service.a.uuid)
        selectAccount(service.b.uuid)
        fm.executePendingTransactions()
        assertNull(secondary)
        selectAccount(service.a.uuid)
        fm.executePendingTransactions()
        assertSame(a, (secondary as ChatFragment).getConversation())
    }

    @Test
    fun archivesTwiceFromContactsWithoutVisitingChats() {
        selectTab(MainTab.CONTACTS)
        val list = main as ChatListFragment
        val chats = listOf(service.chat(service.a, peer), service.chat(service.b, Jid.of("other@example.com")))
        for (chat in chats) {
            activity.switchToConversation(chat)
            fm.executePendingTransactions()
            (main as ChatFragment).archive()
            fm.executePendingTransactions()
            assertSame(list, main)
            assertNotNull(chat.tatrakArchivedAt)
        }
        assertEquals(chats.map { it.uuid }, service.saved)
        val snackbar = ReflectionHelpers.getField<androidx.compose.material3.SnackbarHostState>(list, "snackbar")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertNotNull(snackbar.currentSnackbarData)
        snackbar.currentSnackbarData!!.performAction()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertNull(chats[0].tatrakArchivedAt)
        assertNotNull(chats[1].tatrakArchivedAt)
    }

    @Test
    fun serializedPermissionsReleaseAndRetryAfterAnEmptyResult() {
        val notifications = arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        val contacts = arrayOf(Manifest.permission.READ_CONTACTS)
        val notificationRequest = 0x50ff
        assertTrue(activity.requestMainPermissions(notifications, notificationRequest))
        assertFalse(activity.requestMainPermissions(contacts, ContactFlows.REQUEST_SYNC_CONTACTS))
        ReflectionHelpers.setField(activity, "mHasCurrentPermissionsRequest", false)
        activity.onRequestPermissionsResult(notificationRequest, notifications, intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertTrue(activity.requestMainPermissions(contacts, ContactFlows.REQUEST_SYNC_CONTACTS))
        assertFalse(activity.requestMainPermissions(notifications, notificationRequest))
        activity.contactsPermissionRequested.set(true)
        ReflectionHelpers.setField(activity, "mHasCurrentPermissionsRequest", false)
        activity.onRequestPermissionsResult(ContactFlows.REQUEST_SYNC_CONTACTS, contacts, intArrayOf())
        assertFalse(activity.contactsPermissionRequested.get())
        assertTrue(activity.requestMainPermissions(contacts, ContactFlows.REQUEST_SYNC_CONTACTS))
    }

    @Test
    fun detachedCallActionsRecheckTheirAccount() {
        val a = service.chat(service.a, peer)
        val list = main as ChatListFragment
        val calls = ReflectionHelpers.getField<CallList>(list, "callList")
        AccountFilter.select(activity, service.a.uuid)
        calls.call(a.uuid, video = false)
        val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
        AccountFilter.select(activity, service.b.uuid)
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        calls.openChat(a.uuid)
        calls.call(a.uuid, video = false)
        fm.executePendingTransactions()
        assertSame(list, main)
        assertSame(confirmation, ShadowDialog.getLatestDialog())
    }

    @Test
    fun callsClearThePreviousAccountsCachedSnapshotImmediately() {
        val list = main as ChatListFragment
        val calls = ReflectionHelpers.getField<CallList>(list, "callList")
        val cached = CallsState(loaded = true)
        val snapshotClass = CallList::class.java.declaredClasses.single { it.simpleName == "ScopedCalls" }
        val snapshot = snapshotClass.getDeclaredConstructor(String::class.java, CallsState::class.java)
            .apply { isAccessible = true }
            .newInstance(service.a.uuid, cached)
        ReflectionHelpers.setStaticField(CallList::class.java, "cache", snapshot)
        try {
            AccountFilter.select(activity, service.a.uuid)
            calls.invalidate()
            assertSame(cached, calls.state.value)
            AccountFilter.select(activity, service.b.uuid)
            calls.invalidate()
            assertEquals(CallsState(), calls.state.value)
            val starter = ReflectionHelpers.getField<RtpCallStarter>(calls, "calls")
            val newCalls = CallList(activity, starter, list.lifecycleScope) {}
            newCalls.invalidate()
            assertEquals(CallsState(), newCalls.state.value)
        } finally {
            ReflectionHelpers.setStaticField(CallList::class.java, "cache", null)
        }
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp", shadows = [SearchTaskShadow::class])
    fun unchangedInlineQueryFollowsANotificationToAnotherAccount() {
        val a = service.chat(service.a, peer)
        val b = service.chat(service.b, Jid.of("other@example.com"))
        val list = main as ChatListFragment
        AccountFilter.select(activity, service.a.uuid)
        SearchTaskShadow.requests.clear()
        list.searchMessages("needle")
        val first = SearchTaskShadow.requests.single()
        assertEquals(service.a.uuid, first.accountUuid)
        val results = ReflectionHelpers.callInstanceMethod<MessageSearchResults>(list, "getMessageResults")
        val foundA = Message(a, "needle", Message.ENCRYPTION_NONE, Message.STATUS_SEND)
        first.reply(foundA)
        assertEquals(listOf(foundA.uuid), results.state.value.results.map { it.key })

        activity.deliver(Intent(ConversationsActivity.ACTION_VIEW_CONVERSATION).putExtra(ConversationsActivity.EXTRA_CONVERSATION, b.uuid))
        fm.executePendingTransactions()
        val second = SearchTaskShadow.requests.last()
        assertEquals(first.terms, second.terms)
        assertEquals(service.b.uuid, second.accountUuid)
        assertTrue(results.state.value.results.isEmpty())
        first.reply(foundA)
        assertTrue(results.state.value.results.isEmpty())
        val foundB = Message(b, "needle", Message.ENCRYPTION_NONE, Message.STATUS_SEND)
        second.reply(foundB)
        assertEquals(listOf(foundB.uuid), results.state.value.results.map { it.key })
    }

    @Implements(value = MessageSearchTask::class, isInAndroidSdk = false)
    class SearchTaskShadow {
        data class Request(val terms: List<String>, val accountUuid: String?, val callback: OnSearchResultsAvailable) {
            fun reply(message: Message) = callback.onSearchResultsAvailable(terms, listOf(message))
        }

        companion object {
            val requests = ArrayList<Request>()

            @Implementation
            @JvmStatic
            fun search(
                service: XmppConnectionService,
                terms: List<String>,
                conversation: String?,
                accountUuid: String?,
                callback: OnSearchResultsAvailable,
            ) {
                requests += Request(terms, accountUuid, callback)
            }
        }
    }

    @Test
    fun sharePlusScopesTheChooserAndKeepsTheParticipantRequestsExplicitAccount() {
        val share = Robolectric.buildActivity(ShareWithActivity::class.java).create()
        try {
            AccountFilter.select(activity, service.a.uuid)
            for ((account, jid) in listOf(service.a to peer, service.b to Jid.of("other@example.com"))) {
                account.roster.getContact(jid).setOption(Contact.Options.IN_ROSTER)
            }
            share.get().xmppConnectionService = service
            share.get().startNewChat()
            val request = shadowOf(share.get()).nextStartedActivityForResult.intent
            AccountFilter.select(activity, service.b.uuid)
            val chooser = Robolectric.buildActivity(ChooseContactActivity::class.java, request).create()
            try {
                chooser.get().xmppConnectionService = service
                chooser.get().onBackendConnected()
                assertEquals(listOf(peer), chooser.get().listItems.map { it.address })
                val options = ReflectionHelpers.getField<List<String>>(chooser.get(), "mActivatedAccounts")
                assertEquals(listOf(service.a.jid.asBareJid().toString()), options)
            } finally {
                chooser.destroy()
            }
            val room = service.chat(service.b, Jid.of("room@conference.example.com"), muc = true)
            AccountFilter.select(activity, service.a.uuid)
            val participants = ChooseContactActivity.create(activity, room)
            assertEquals(service.b.jid.asBareJid().toString(), participants.getStringExtra(XmppActivity.EXTRA_ACCOUNT))
            assertFalse(participants.hasExtra(ChooseContactActivity.EXTRA_SHARE_ACCOUNT))
        } finally {
            share.destroy()
        }
    }

    @Test
    fun openReplySnapshotTracksDownloadAndRetraction() {
        val chat = service.chat(service.a, peer)
        val photo = Message(chat, "https://up.example/photo.jpg|1000|640|480", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply {
            type = Message.TYPE_TEXT
            setOob(true)
            transferable = TransferablePlaceholder(Transferable.STATUS_DOWNLOADING)
        }
        chat.add(photo)
        val params = SnapshotParams(
            TextPalette(0, 0, 0, 0, 0), null, 48, true, 0, false, MentionTracker(), preview = true, replyUuid = photo.uuid,
        )
        val cache = FormattedTextCache()
        fun shown() = ChatSnapshot.build(activity, service, chat, params, cache).replyThumbnailUuid
        assertNull(shown())
        photo.type = Message.TYPE_IMAGE
        photo.setRelativeFilePath(Message.StorageLocation(File("photo.jpg"), false))
        photo.transferable = null
        assertEquals(photo.uuid, shown())
        photo.eraseContent()
        assertNull(shown())
    }

    @Test
    fun privateMessageOpensTheClassicComposerInPlace() {
        val room = service.chat(service.a, Jid.of("room@conference.example.com"), muc = true)
        activity.switchToConversation(room)
        fm.executePendingTransactions()
        val guest = MucOptions.User(room.mucOptions, room.address.withResource("guest"), null, null, Role.PARTICIPANT, Affiliation.NONE, emptySet())
        val menu = android.widget.PopupMenu(activity, activity.window.decorView).menu
        activity.menuInflater.inflate(R.menu.muc_details_context, menu)
        eu.siacs.conversations.ui.util.MucDetailsContextMenuHelper.onContextItemSelected(
            menu.findItem(R.id.send_private_message), guest, activity,
        )
        fm.executePendingTransactions()
        assertFalse(activity.isFinishing)
        assertTrue(main is ConversationFragment)
        assertEquals(guest.fullJid, room.nextCounterpart)
    }

    @Test
    fun membershipConfirmationRechecksTheCurrentParticipant() {
        val users = Robolectric.buildActivity(MucUsersActivity::class.java).create().get()
        try {
            val room = service.chat(service.a, Jid.of("room@conference.example.com"), muc = true)
            room.setMucAffiliation(Affiliation.OWNER)
            val options = room.mucOptions
            val member = MucOptions.User(options, room.address.withResource("guest"), peer, null, Role.PARTICIPANT, Affiliation.MEMBER, emptySet())
            options.updateUser(member)
            val selected = mutableListOf<MucOptions.User>()
            fun open(): AlertDialog {
                participantMenu(users, member) { item, target ->
                    assertEquals(R.id.remove_membership, item.itemId)
                    selected += target
                }.single { it.label == users.getString(R.string.remove_membership) }.onClick()
                return ShadowDialog.getLatestDialog() as AlertDialog
            }
            open().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(selected.isEmpty())
            open().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(member), selected)
            val stale = open()
            options.updateUser(member.withAffiliation(Affiliation.ADMIN))
            stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(member), selected)
        } finally {
            users.finish()
        }
    }

    private fun selectTab(tab: MainTab) {
        ReflectionHelpers.callInstanceMethod<Unit>(main, "selectTab", ClassParameter.from(MainTab::class.java, tab))
    }

    private fun selectAccount(uuid: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(main, "selectAccount", ClassParameter.from(String::class.java, uuid))
    }

    private fun showAddContact(prefilled: String?) {
        selectTab(MainTab.CONTACTS)
        ContactFlows.showCreateContactDialog(activity, arrayListOf(service.a.jid.asBareJid().toString()), prefilled, null)
        fm.executePendingTransactions()
        assertTrue(addDialog().isShowing)
    }

    private fun addDialog(): AlertDialog =
        (fm.findFragmentByTag("dialog") as EnterJidDialog).requireDialog() as AlertDialog

    private fun submitContact() {
        addDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        fm.executePendingTransactions()
        assertNull(fm.findFragmentByTag("dialog"))
    }

    private fun addContact(tablet: Boolean) {
        assertEquals(tablet, ConversationsActivity.isTabletView(activity))
        showAddContact(prefilled = peer.toString())
        submitContact()
        assertEquals(listOf(peer), service.created)
        assertEquals(peer, ((if (tablet) secondary else main) as ChatFragment).getConversation()?.address)
        assertEquals(if (tablet) 0 else 1, fm.backStackEntryCount)
    }
}
