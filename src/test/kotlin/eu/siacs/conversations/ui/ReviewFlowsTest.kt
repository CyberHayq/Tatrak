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
import eu.siacs.conversations.services.UnifiedPushBroker
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.interfaces.OnSearchResultsAvailable
import eu.siacs.conversations.xmpp.Jid
import eu.siacs.conversations.xmpp.XmppConnection
import eu.siacs.conversations.ui.util.Attachment
import eu.siacs.conversations.ui.util.PresenceSelector
import im.conversations.android.xmpp.model.stanza.Presence
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
    // Keep the same instrumentation configuration for every test in this class.
    shadows = [ReviewFlowsTest.NoAxolotlStore::class, ReviewFlowsTest.NoPushRenewal::class, ReviewFlowsTest.SearchTaskShadow::class],
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

    /** The unstarted service does not need a periodic push renewal that outlives the fixture. */
    @Implements(value = UnifiedPushBroker::class, isInAndroidSdk = false)
    class NoPushRenewal {
        @Implementation
        fun __constructor__(service: XmppConnectionService) = Unit
    }

    class Host : ConversationsActivity() {
        override fun connectToBackend() {
            xmppConnectionService = checkNotNull(service)
            xmppConnectionServiceBound = true
        }

        override fun isOptimizingBattery() = false

        fun deliver(intent: Intent) = onNewIntent(intent)

        /** The device chooser's answer, held for the test to give instead of the dialog. */
        var chosen: PresenceSelector.OnPresenceSelected? = null

        override fun selectPresence(conversation: Conversation, listener: PresenceSelector.OnPresenceSelected) {
            chosen = listener
        }

        override fun onStop() {
            // The fake backend is injected directly. No Android ServiceConnection was registered.
            xmppConnectionServiceBound = false
            super.onStop()
        }

        companion object {
            var service: TestService? = null
        }
    }

    class TestAccount(jid: String) : Account(Jid.of(jid), "fixture") {
        var online = false
        var axolotl: AxolotlService? = null

        override fun isOnlineAndConnected() = online

        override fun getAxolotlService(): AxolotlService = axolotl ?: super.getAxolotlService()

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

        val cleared = ArrayList<String>()

        override fun clearConversationHistory(conversation: Conversation) {
            cleared += conversation.uuid
        }

        val sent = ArrayList<Message>()
        val attached = ArrayList<android.net.Uri>()
        val resent = ArrayList<Message>()

        override fun resendFailedMessages(message: Message, forceP2P: Boolean) {
            resent += message
        }

        override fun sendMessage(message: Message) {
            sent += message
        }

        /** The message part of the real method, without sending it. */
        override fun attachLocationToConversation(conversation: Conversation, uri: android.net.Uri): com.google.common.util.concurrent.ListenableFuture<Void> {
            val message = Message(conversation, uri.toString(), conversation.nextEncryption)
            Message.configurePrivateMessage(message)
            sent += message
            @Suppress("UNCHECKED_CAST")
            return com.google.common.util.concurrent.Futures.immediateVoidFuture() as com.google.common.util.concurrent.ListenableFuture<Void>
        }

        /** The message part of the real method (its first lines), without copying the file. */
        override fun attachFileToConversation(conversation: Conversation, uri: android.net.Uri, type: String?): com.google.common.util.concurrent.ListenableFuture<Void> {
            attached += uri
            val message = Message(conversation, "", conversation.nextEncryption)
            if (!Message.configurePrivateFileMessage(message)) {
                message.counterpart = conversation.nextCounterpart
                message.type = Message.TYPE_FILE
            }
            sent += message
            @Suppress("UNCHECKED_CAST")
            return com.google.common.util.concurrent.Futures.immediateVoidFuture() as com.google.common.util.concurrent.ListenableFuture<Void>
        }

        fun chat(account: Account, jid: Jid, muc: Boolean = false, encryption: Int = Message.ENCRYPTION_NONE): Conversation =
            object : Conversation(jid.toString(), account, jid, if (muc) Conversation.MODE_MULTI else Conversation.MODE_SINGLE) {
                override fun getNextEncryption() = encryption
            }.also(chats::add)
    }

    private lateinit var controller: ActivityController<Host>
    private val activity get() = controller.get()
    private val fm get() = activity.supportFragmentManager
    private val service get() = checkNotNull(Host.service)
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
        controller = Robolectric.buildActivity(Host::class.java)
        controller.setup()
        fm.executePendingTransactions()
    }

    @After
    fun stop() {
        try {
            if (::controller.isInitialized) {
                // Posts of the screens (the classic composer's postEditUiModification) run while they are attached.
                shadowOf(Looper.getMainLooper()).idle()
                controller.close()
                // Let the window recomposer finish cancellation before Robolectric resets the looper.
                shadowOf(Looper.getMainLooper()).idle()
            }
        } finally {
            SearchTaskShadow.requests.clear()
            Host.service = null
        }
    }

    @Test
    fun addingContactKeepsTheNewChatOnThePhoneBackStack() = addContact(tablet = false)

    /**
     * A 1:1 OMEMO chat whose contact has a trusted key but no session and no known device (as after a backup
     * import) fetches the contact's device list when the chat opens; nothing else does before a send fails.
     */
    @Test
    fun omemoChatWithTrustedKeysButNoKnownDeviceFetchesTheDeviceList() {
        val fetched = ArrayList<Jid>()
        var trusted = 1L
        var empty = true
        service.a.axolotl = object : AxolotlService(service.a, service) {
            override fun hasEmptyDeviceList(jid: Jid) = empty

            override fun getNumTrustedKeys(jid: Jid) = trusted

            override fun fetchDeviceIds(jid: Jid) {
                fetched += jid
            }
        }
        fun open(chat: Conversation): List<Jid> {
            fetched.clear()
            activity.switchToConversation(chat)
            fm.executePendingTransactions()
            return fetched.toList()
        }
        service.a.online = true
        assertEquals(listOf(peer), open(service.chat(service.a, peer, encryption = Message.ENCRYPTION_AXOLOTL)))
        // no IQ: a plain chat, the self-chat, a group, a contact with known devices or sessions, one without a
        // trusted key (the send check fetches it), an offline account
        val other = Jid.of("other@example.com")
        assertEquals(emptyList<Jid>(), open(service.chat(service.a, other)))
        assertEquals(emptyList<Jid>(), open(service.chat(service.a, service.a.jid.asBareJid(), encryption = Message.ENCRYPTION_AXOLOTL)))
        assertEquals(emptyList<Jid>(), open(service.chat(service.a, Jid.of("room@conference.example.com"), muc = true,
            encryption = Message.ENCRYPTION_AXOLOTL)))
        val omemo = service.chat(service.a, other, encryption = Message.ENCRYPTION_AXOLOTL)
        empty = false
        assertEquals(emptyList<Jid>(), open(omemo))
        empty = true
        trusted = 0
        assertEquals(emptyList<Jid>(), open(service.chat(service.a, Jid.of("third@example.com"), encryption = Message.ENCRYPTION_AXOLOTL)))
        trusted = 1
        service.a.online = false
        assertEquals(emptyList<Jid>(), open(service.chat(service.a, Jid.of("fourth@example.com"), encryption = Message.ENCRYPTION_AXOLOTL)))
    }

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

    class NamedShare : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: android.net.Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?) =
            android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("screenshot.png")) }
        override fun getType(uri: android.net.Uri) = "image/png"
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, selection: String?, args: Array<String>?) = 0
        override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<String>?) = 0
    }

    /** A share's "Send N files?" outlives a recreation (owner's phone, 2026-11-24: a changed style relaunched the activity). */
    @Test
    fun sharedFilesQuestionComesBackAfterARecreation() {
        val chat = service.chat(service.a, peer)
        // a named content URI: MimeUtils' name lookup throws for a nameless one under the JVM (not on a device)
        Robolectric.setupContentProvider(NamedShare::class.java, "test.share")
        val uri = android.net.Uri.parse("content://test.share/screenshot.png")
        fun share() =
            controller.newIntent(
                Intent(activity, ConversationsActivity::class.java)
                    .setAction(Intent.ACTION_SEND_MULTIPLE)
                    .putExtra(ConversationsActivity.EXTRA_CONVERSATION, chat.uuid)
                    .putExtra(ConversationsActivity.EXTRA_TYPE, "image/png")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
            )
        // the question, not a dialog of the attach path that may follow it
        fun question() =
            ShadowDialog.getShownDialogs().filterIsInstance<AlertDialog>().last {
                it.isShowing && it.findViewById<android.widget.TextView>(android.R.id.message)?.text?.contains("1") == true
            }
        share()
        fm.executePendingTransactions()
        val first = question()
        assertTrue(first.isShowing)
        controller.recreate()
        fm.executePendingTransactions()
        // the fake Host binds without the ServiceConnection callback a real one gets
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onBackendConnected")
        shadowOf(Looper.getMainLooper()).idle()
        val again = question()
        assertNotSame(first, again)
        assertTrue(again.isShowing)
        assertEquals("1 file", ReflectionHelpers.getField<List<Attachment>?>(main, "confirming")?.size?.let { "$it file" })
        // Send hands the files to the attach path (its permission, trust and upload gates are other tests' business)
        again.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ReflectionHelpers.getField<List<Attachment>?>(main, "confirming"))

        // an answered question stays answered
        controller.recreate()
        fm.executePendingTransactions()
        // the fake Host binds without the ServiceConnection callback a real one gets
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onBackendConnected")
        shadowOf(Looper.getMainLooper()).idle()
        // (the attach path may show its own dialogs; the question itself is gone)
        assertNull(ReflectionHelpers.getField<List<Attachment>?>(main, "confirming"))
    }

    /** "Clear history" of chat A, then a notification opens B in the same fragment: A's question goes, and clears nothing. */
    @Test
    fun clearHistoryQuestionGoesWhenAnotherChatOpens() {
        val a = service.chat(service.a, peer)
        val b = service.chat(service.a, Jid.of("other@example.com"))
        activity.switchToConversation(a)
        fm.executePendingTransactions()
        val chat = main as ChatFragment
        chat.clearHistory()
        shadowOf(Looper.getMainLooper()).idle()
        val question = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("Clear the history of ${a.name}?", question.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString())
        activity.deliver(Intent(ConversationsActivity.ACTION_VIEW_CONVERSATION).putExtra(ConversationsActivity.EXTRA_CONVERSATION, b.uuid))
        fm.executePendingTransactions()
        assertSame(chat, main)
        assertSame(b, chat.getConversation())
        assertFalse(question.isShowing)
        // a tap that still reaches the old question
        question.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), service.cleared)
        chat.clearHistory()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(b.uuid), service.cleared)
    }

    /** The same on the classic screen (OpenPGP chats): ConversationFragment.clearHistoryDialog. */
    @Test
    fun classicClearHistoryQuestionGoesWhenAnotherChatOpens() {
        val a = service.chat(service.a, peer, encryption = Message.ENCRYPTION_PGP)
        val b = service.chat(service.a, Jid.of("other@example.com"), encryption = Message.ENCRYPTION_PGP)
        activity.switchToConversation(a)
        fm.executePendingTransactions()
        val classic = main as ConversationFragment
        classic.clearHistoryDialog(a)
        shadowOf(Looper.getMainLooper()).idle()
        val question = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("Clear the history of ${a.name}?", question.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString())
        activity.deliver(Intent(ConversationsActivity.ACTION_VIEW_CONVERSATION).putExtra(ConversationsActivity.EXTRA_CONVERSATION, b.uuid))
        fm.executePendingTransactions()
        assertSame(classic, main)
        assertSame(b, classic.conversation)
        assertFalse(question.isShowing)
        question.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), service.cleared)
        classic.clearHistoryDialog(b)
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(b.uuid), service.cleared)
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
    @Config(qualifiers = "w1100dp-h760dp")
    fun unchangedInlineQueryFollowsANotificationToAnotherAccount() {
        val a = service.chat(service.a, peer)
        val b = service.chat(service.b, Jid.of("other@example.com"))
        val list = main as ChatListFragment
        AccountFilter.select(activity, service.a.uuid)
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
    fun privateMessageIsComposedInTheChatScreen() {
        val (room, guest) = roomWithGuest()
        val chat = main as ChatFragment
        val model = model(chat)
        typeInto(chat, "for the room")
        model.previews = generatedFile("room.txt")
        sendPrivateMessageFromTheMenu(guest)
        assertFalse(activity.isFinishing)
        assertSame(chat, main)
        assertNull(ConversationFragment.get(activity))
        assertEquals(guest.fullJid, room.nextCounterpart)
        assertEquals(guest.fullJid, model.privateTo)
        // content written for the room does not follow the new target
        assertEquals("", model.input.text)
        assertTrue(model.previews.isEmpty())

        typeInto(chat, "psst")
        chat.send()
        val pm = service.sent.single()
        assertEquals("psst", pm.body)
        assertEquals(guest.fullJid, pm.counterpart)
        assertEquals(Message.TYPE_PRIVATE, pm.type)
        // the mode stays for the next message, as in the classic composer
        assertEquals(guest.fullJid, room.nextCounterpart)

        // a configuration change of the open chat keeps it
        controller.recreate()
        fm.executePendingTransactions()
        // the fake Host binds without the ServiceConnection callback a real one gets
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onBackendConnected")
        assertSame(room, (main as ChatFragment).getConversation())
        assertEquals(guest.fullJid, room.nextCounterpart)
        assertEquals(guest.fullJid, model(main as ChatFragment).privateTo)
    }

    @Test
    fun privateMessagesAreTextOnly() {
        val (_, guest) = roomWithGuest()
        val chat = main as ChatFragment
        sendPrivateMessageFromTheMenu(guest)
        // what the fixture's start opened (the storage gate)
        drainStartedActivities()
        chat.attachGallery()
        chat.attachFile()
        chat.attachLocation()
        chat.attachContact()
        chat.takePhoto()
        chat.recordVideo()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(chat.startRecording())
    }

    @Test
    fun aFileArrivingInPrivateModeGoesToTheRoom() {
        val (room, guest) = roomWithGuest()
        val chat = main as ChatFragment
        sendPrivateMessageFromTheMenu(guest)
        typeInto(chat, "psst")
        // a picker result (a gallery or file pick lands in the preview strip)
        ReflectionHelpers.callInstanceMethod<Unit>(chat, "addPreviews", ClassParameter.from(List::class.java, generatedFile("photo.txt")), ClassParameter.from(String::class.java, room.uuid))
        assertNull(room.nextCounterpart)
        assertNull(model(chat).privateTo)
        assertEquals("", model(chat).input.text)
        assertEquals(activity.getString(R.string.tatrak_chat_private_cancelled), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        chat.send()
        val file = service.sent.single()
        assertNull(file.counterpart)
        assertEquals(Message.TYPE_FILE, file.type)
    }

    @Test
    fun aStickerInPrivateModeGoesToTheRoom() {
        val (room, guest) = roomWithGuest()
        val chat = main as ChatFragment
        sendPrivateMessageFromTheMenu(guest)
        chat.sendSticker(File(activity.cacheDir, "sticker.webp").apply { writeBytes(ByteArray(8)) })
        assertNull(room.nextCounterpart)
        val sticker = service.sent.single()
        assertNull(sticker.counterpart)
        assertEquals(Message.TYPE_FILE, sticker.type)
    }

    @Test
    fun aShareIntoTheRoomEndsThePrivateMessage() {
        val (room, guest) = roomWithGuest()
        val chat = main as ChatFragment
        sendPrivateMessageFromTheMenu(guest)
        typeInto(chat, "psst")
        activity.deliver(view(room).putExtra(Intent.EXTRA_TEXT, "shared link"))
        fm.executePendingTransactions()
        assertNull(room.nextCounterpart)
        assertEquals(activity.getString(R.string.tatrak_chat_private_cancelled), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertEquals("shared link", model(chat).input.text)
        chat.send()
        val shared = service.sent.single()
        assertEquals("shared link", shared.body)
        assertEquals(room.address, shared.counterpart)
        assertEquals(Message.TYPE_TEXT, shared.type)
    }

    @Test
    fun entriesFromOutsideAndTheClassicHandoffEndThePrivateMessage() {
        val (room, guest) = roomWithGuest()
        sendPrivateMessageFromTheMenu(guest)
        // a reopen (the list, a notification)
        activity.switchToConversation(room)
        fm.executePendingTransactions()
        assertNull(room.nextCounterpart)
        assertNull(model(main as ChatFragment).privateTo)
        // the classic mention route
        sendPrivateMessageFromTheMenu(guest)
        activity.deliver(view(room).putExtra(ConversationsActivity.EXTRA_NICK, "guest"))
        fm.executePendingTransactions()
        assertTrue(main is ConversationFragment)
        assertNull(room.nextCounterpart)
    }

    @Test
    fun openClassicEndsThePrivateMessage() {
        val (room, guest) = roomWithGuest()
        sendPrivateMessageFromTheMenu(guest)
        (main as ChatFragment).openClassic()
        fm.executePendingTransactions()
        assertTrue(main is ConversationFragment)
        assertNull(room.nextCounterpart)
    }

    @Test
    fun theXEndsThePrivateMessageAndASendGoesToTheRoom() {
        val (room, guest) = roomWithGuest()
        val parent = Message(room, "lunch?", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply { counterpart = guest.fullJid }
        room.add(parent)
        val chat = main as ChatFragment
        val model = model(chat)
        sendPrivateMessageFromTheMenu(guest)
        typeInto(chat, "just between us")
        chat.reply(parent.uuid)
        assertNotNull(model.reply)
        chat.cancelPrivateMessage()
        assertNull(room.nextCounterpart)
        assertNull(model.privateTo)
        assertEquals("", model.input.text)
        assertNull(model.reply)
        // no stale bar: what is typed now goes to the room, with nothing of the private message
        typeInto(chat, "hello all")
        chat.send()
        val sent = service.sent.single()
        assertEquals("hello all", sent.body)
        assertEquals(room.address, sent.counterpart)
        assertEquals(Message.TYPE_TEXT, sent.type)
        assertNull(sent.replyTo)
    }

    @Test
    fun replyToAPrivateMessageAnswersItPrivately() {
        val (room, guest) = roomWithGuest()
        val other = MucOptions.User(room.mucOptions, room.address.withResource("other"), null, null, Role.PARTICIPANT, Affiliation.NONE, emptySet())
        room.mucOptions.updateUser(other)
        val received = privateMessage(room, "psst", Message.STATUS_RECEIVED, guest.fullJid)
        val sentPm = privateMessage(room, "see you", Message.STATUS_SEND_RECEIVED, other.fullJid)
        val anonymous = privateMessage(room, "who am I", Message.STATUS_RECEIVED, room.address)
        val roomMessage = Message(room, "lunch?", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply { counterpart = other.fullJid }
        listOf(received, sentPm, anonymous, roomMessage).forEach(room::add)
        val chat = main as ChatFragment
        val model = model(chat)

        chat.reply(received.uuid)
        assertEquals(guest.fullJid, room.nextCounterpart)
        typeInto(chat, "to the sender")
        chat.send()
        assertEquals(guest.fullJid, service.sent.last().counterpart)
        assertEquals(Message.TYPE_PRIVATE, service.sent.last().type)

        chat.reply(sentPm.uuid)
        assertEquals(other.fullJid, room.nextCounterpart)
        typeInto(chat, "to the recipient")
        chat.send()
        assertEquals(other.fullJid, service.sent.last().counterpart)
        assertEquals(Message.TYPE_PRIVATE, service.sent.last().type)

        // a room message answered in private mode stays a private reply; a new target drops it
        chat.reply(roomMessage.uuid)
        assertEquals(other.fullJid, room.nextCounterpart)
        assertEquals(roomMessage.uuid, model.reply?.uuid)
        chat.reply(received.uuid)
        assertEquals(guest.fullJid, room.nextCounterpart)
        assertEquals(received.uuid, model.reply?.uuid)
        sendPrivateMessageFromTheMenu(other)
        assertNull(model.reply)

        val items = snapshot(room).items.filterIsInstance<am.cyberhayq.chat.ui.chat.MessageItem>().associateBy { it.key }
        assertTrue(items.getValue(received.uuid).actions.canReply)
        assertTrue(items.getValue(sentPm.uuid).actions.canReply)
        // no full JID to answer: Reply is not offered
        assertFalse(items.getValue(anonymous.uuid).actions.canReply)
    }

    @Test
    fun startingAnEditEndsThePrivateMessage() {
        val (room, guest) = roomWithGuest()
        val own = Message(room, "typo", Message.ENCRYPTION_NONE, Message.STATUS_SEND_RECEIVED)
        room.add(own)
        val chat = main as ChatFragment
        sendPrivateMessageFromTheMenu(guest)
        typeInto(chat, "psst")
        chat.edit(own.uuid)
        assertNull(room.nextCounterpart)
        assertSame(own, room.correctingMessage)
        assertEquals("typo", model(chat).input.text)
        // the text kept aside for after the edit is the room's, not the private one
        assertTrue(room.draftMessage.isNullOrEmpty())
    }

    @Test
    fun processDeathDropsThePrivateMessageAndKeepsTheRoomDraft() {
        val (room, guest) = roomWithGuest()
        val chat = main as ChatFragment
        typeInto(chat, "room draft")
        // stored as the classic screen does when the chat is left
        ReflectionHelpers.callInstanceMethod<Unit>(chat, "saveDraft", ClassParameter.from(Conversation::class.java, room))
        assertEquals("room draft", room.draft?.message())
        sendPrivateMessageFromTheMenu(guest)
        typeInto(chat, "psst")
        val state = android.os.Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        // stopping saved the composer: the private text never reached the room's draft
        assertEquals("room draft", room.draft?.message())
        // a new process: a fresh room object from the database, without the in-memory target
        service.chats.clear()
        val restored =
            object : Conversation(room.uuid, room.name.toString(), null, service.a.uuid, room.address, 0L, Conversation.STATUS_AVAILABLE, Conversation.MODE_MULTI, Conversation.Attributes()) {
                override fun getNextEncryption() = Message.ENCRYPTION_NONE
            }.also {
                it.setAccount(service.a)
                it.setNextMessage(room.draft?.message())
                service.chats += it
            }
        controller = Robolectric.buildActivity(Host::class.java).setup(state)
        fm.executePendingTransactions()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onBackendConnected")
        val again = main as ChatFragment
        assertSame(restored, again.getConversation())
        assertNull(restored.nextCounterpart)
        assertNull(model(again).privateTo)
        assertEquals("room draft", model(again).input.text)
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun switchingChatsInTheTabletPaneEndsThePrivateMessage() {
        val (room, guest) = roomWithGuest()
        assertTrue(ConversationsActivity.isTabletView(activity))
        val chat = secondary as ChatFragment
        sendPrivateMessageFromTheMenu(guest)
        assertSame(chat, secondary)
        assertNull(ConversationFragment.get(activity))
        assertEquals(guest.fullJid, room.nextCounterpart)
        typeInto(chat, "psst")
        chat.send()
        assertEquals(guest.fullJid, service.sent.single().counterpart)
        activity.switchToConversation(service.chat(service.a, peer))
        fm.executePendingTransactions()
        assertNull(room.nextCounterpart)
        activity.switchToConversation(room)
        fm.executePendingTransactions()
        assertNull(model(secondary as ChatFragment).privateTo)
    }

    private fun privateMessage(room: Conversation, body: String, status: Int, counterpart: Jid) =
        Message(room, body, Message.ENCRYPTION_NONE, status).apply {
            type = Message.TYPE_PRIVATE
            this.counterpart = counterpart
        }

    private fun view(room: Conversation) =
        Intent(activity, Host::class.java)
            .setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION)
            .putExtra(ConversationsActivity.EXTRA_CONVERSATION, room.uuid)

    @Test
    fun aLateVoiceNoteOfAnOlderFragmentLeavesNothingOfThePrivateMessage() {
        val (room, guest) = roomWithGuest()
        val parent = Message(room, "lunch?", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply { counterpart = guest.fullJid }
        room.add(parent)
        val old = main as ChatFragment
        sendPrivateMessageFromTheMenu(guest)
        typeInto(old, "psst")
        old.reply(parent.uuid)
        controller.recreate()
        fm.executePendingTransactions()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onBackendConnected")
        val live = main as ChatFragment
        assertNotSame(old, live)
        assertEquals(guest.fullJid, model(live).privateTo)
        // the recording the old fragment started ends after the recreation: its callback runs on the detached instance
        val recording = File(activity.cacheDir, "note.m4a").apply { writeBytes(ByteArray(16)) }
        ReflectionHelpers.callInstanceMethod<Unit>(
            old, "sendDetached",
            ClassParameter.from(XmppActivity::class.java, activity),
            ClassParameter.from(Conversation::class.java, room),
            ClassParameter.from(List::class.java, listOf(recordingOf(recording))),
        )
        assertNull(room.nextCounterpart)
        assertNull(model(live).privateTo)
        assertNull(model(live).reply)
        assertEquals("", model(live).input.text)
        live.send()
        val voice = service.sent.single()
        assertEquals(Message.TYPE_FILE, voice.type)
        assertTrue(service.sent.none { it.body == "psst" || it.isPrivateMessage })
    }

    @Test
    fun aSendRefusesWhenTheTargetChangedBehindTheComposer() {
        val (room, guest) = roomWithGuest()
        val chat = main as ChatFragment
        sendPrivateMessageFromTheMenu(guest)
        typeInto(chat, "psst")
        // ended outside this composer, its view model not told
        room.nextCounterpart = null
        chat.send()
        assertTrue(service.sent.isEmpty())
        assertNull(model(chat).privateTo)
        assertEquals("", model(chat).input.text)
        assertEquals(activity.getString(R.string.tatrak_chat_recipient_changed), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun enteringPrivateModeClearsACorrectionOutsideTheLoadedHistory() {
        val (room, guest) = roomWithGuest()
        val own = Message(room, "old text", Message.ENCRYPTION_NONE, Message.STATUS_SEND_RECEIVED)
        // a correction restored from the stored attribute whose message is not loaded
        room.setCorrectingMessage(own)
        assertNull(room.correctingMessage)
        val chat = main as ChatFragment
        sendPrivateMessageFromTheMenu(guest)
        room.add(own)
        assertNull(room.correctingMessage)
        typeInto(chat, "psst")
        chat.send()
        val pm = service.sent.single()
        assertNotSame(own, pm)
        assertEquals(Message.TYPE_PRIVATE, pm.type)
        assertEquals("old text", own.body)
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun selectingTheOpenRoomInTheTabletListEndsThePrivateMessage() {
        val (room, guest) = roomWithGuest()
        sendPrivateMessageFromTheMenu(guest)
        typeInto(secondary as ChatFragment, "psst")
        activity.onConversationSelected(room)
        assertNull(room.nextCounterpart)
        assertNull(model(secondary as ChatFragment).privateTo)
        assertEquals("", model(secondary as ChatFragment).input.text)
    }

    @Test
    fun aPermissionGrantedAfterThePrivateMessageStartedLaunchesNothing() {
        val (_, guest) = roomWithGuest()
        val chat = main as ChatFragment
        chat.takePhoto()
        val request = shadowOf(activity).lastRequestedPermission
        assertNotNull(request)
        sendPrivateMessageFromTheMenu(guest)
        drainStartedActivities()
        shadowOf(activity.application).grantPermissions(*request.requestedPermissions)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, IntArray(request.requestedPermissions.size))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test
    fun aLocationPickedBeforeThePrivateMessageGoesToTheRoom() {
        val (room, guest) = roomWithGuest()
        val chat = main as ChatFragment
        drainStartedActivities()
        chat.attachLocation()
        val picker = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(picker)
        sendPrivateMessageFromTheMenu(guest)
        typeInto(chat, "psst")
        shadowOf(activity).receiveResult(picker.intent, android.app.Activity.RESULT_OK, Intent().putExtra("latitude", 40.18).putExtra("longitude", 44.51))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(room.nextCounterpart)
        assertEquals("", model(chat).input.text)
        val location = service.sent.single()
        assertTrue(location.body.startsWith("geo:"))
        assertEquals(room.address, location.counterpart)
        assertFalse(location.isPrivateMessage)
    }

    /** Two-pane: the open pane's fragment shows [first]; [second] replaces it in the same fragment later. */
    private fun twoChats(
        encryption: Int = Message.ENCRYPTION_NONE,
        secondEncryption: Int = Message.ENCRYPTION_NONE,
    ): Triple<ChatFragment, Conversation, Conversation> {
        val first = service.chat(service.a, peer, encryption = encryption)
        val second = service.chat(service.a, Jid.of("other@example.com"), encryption = secondEncryption)
        activity.switchToConversation(first)
        fm.executePendingTransactions()
        drainStartedActivities()
        return Triple(secondary as ChatFragment, first, second)
    }

    private fun switchTo(chat: ChatFragment, c: Conversation) {
        activity.switchToConversation(c)
        fm.executePendingTransactions()
        assertSame(chat, secondary)
        assertSame(c, chat.getConversation())
    }

    private fun pickLocation(chat: ChatFragment): Intent {
        chat.attachLocation()
        return checkNotNull(shadowOf(activity).nextStartedActivityForResult).intent
    }

    private fun location() = Intent().putExtra("latitude", 40.18).putExtra("longitude", 44.51)

    /** An OMEMO account whose only contact asks for a trust decision until [trusted]. */
    private fun keysToTrust(): () -> Unit {
        var trusted = false
        service.a.axolotl = object : AxolotlService(service.a, service) {
            override fun getCryptoTargets(conversation: Conversation) = com.google.common.collect.ImmutableSet.of(conversation.address.asBareJid())

            override fun getKeysWithTrust(status: eu.siacs.conversations.crypto.axolotl.FingerprintStatus) =
                emptySet<org.whispersystems.libsignal.IdentityKey>()

            override fun getKeysWithTrust(status: eu.siacs.conversations.crypto.axolotl.FingerprintStatus, jids: Collection<Jid>) =
                emptySet<org.whispersystems.libsignal.IdentityKey>()

            override fun findDevicesWithoutSession(conversation: Conversation) =
                emptySet<org.whispersystems.libsignal.SignalProtocolAddress>()

            override fun anyTargetHasNoTrustedKeys(jids: Collection<Jid>) = !trusted

            override fun hasPendingKeyFetches(jids: Collection<Jid>) = false

            override fun createSessionsIfNeeded(conversation: Conversation) = false
        }
        return { trusted = true }
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun aLocationPickedInOneChatIsNotSentToTheChatOpenedMeanwhile() {
        val (chat, first, second) = twoChats()
        val picker = pickLocation(chat)
        switchTo(chat, second)
        shadowOf(activity).receiveResult(picker, android.app.Activity.RESULT_OK, location())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<Message>(), service.sent)
        // the chat it was picked in still takes it
        switchTo(chat, first)
        shadowOf(activity).receiveResult(pickLocation(chat), android.app.Activity.RESULT_OK, location())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(first), service.sent.map { it.conversation })
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun attachmentsWaitingForKeyTrustAreNotSentToTheChatOpenedMeanwhile() {
        val trust = keysToTrust()
        val (chat, first, second) = twoChats(Message.ENCRYPTION_AXOLOTL)
        fun trustScreenAfterLocation(): Intent {
            shadowOf(activity).receiveResult(pickLocation(chat), android.app.Activity.RESULT_OK, location())
            shadowOf(Looper.getMainLooper()).idle()
            val screen = checkNotNull(shadowOf(activity).nextStartedActivityForResult).intent
            assertEquals(TrustKeysActivity::class.java.name, screen.component?.className)
            return screen
        }
        val screen = trustScreenAfterLocation()
        switchTo(chat, second)
        trust()
        shadowOf(activity).receiveResult(screen, android.app.Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<Message>(), service.sent)
        // without a switch the retry sends to the chat it started in
        switchTo(chat, first)
        val trustAgain = keysToTrust()
        val again = trustScreenAfterLocation()
        trustAgain()
        shadowOf(activity).receiveResult(again, android.app.Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(first), service.sent.map { it.conversation })
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun textWaitingForKeyTrustDoesNotSendTheDraftOfTheChatOpenedMeanwhile() {
        val trust = keysToTrust()
        val (chat, _, second) = twoChats(Message.ENCRYPTION_AXOLOTL)
        // the same text in both: only the chat tells them apart
        second.setNextMessage("same text")
        typeInto(chat, "same text")
        val screen = trustScreenAfterSend(chat)
        switchTo(chat, second)
        trust()
        shadowOf(activity).receiveResult(screen, android.app.Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<Message>(), service.sent)
        assertEquals("same text", model(chat).input.text)
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun textChangedWhileTheKeyTrustScreenWasOpenIsNotSent() {
        val trust = keysToTrust()
        val (chat, _, _) = twoChats(Message.ENCRYPTION_AXOLOTL)
        typeInto(chat, "one")
        val screen = trustScreenAfterSend(chat)
        typeInto(chat, "two")
        trust()
        shadowOf(activity).receiveResult(screen, android.app.Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<Message>(), service.sent)
        assertEquals("two", model(chat).input.text)
    }

    private fun trustScreenAfterSend(chat: ChatFragment): Intent {
        chat.send()
        val screen = checkNotNull(shadowOf(activity).nextStartedActivityForResult).intent
        assertEquals(TrustKeysActivity::class.java.name, screen.component?.className)
        return screen
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun aCameraPermissionGrantedAfterAChatSwitchOpensNoCamera() {
        am.cyberhayq.chat.testing.FileProviderCache.clear()
        val (chat, _, second) = twoChats()
        chat.takePhoto()
        val request = checkNotNull(shadowOf(activity).lastRequestedPermission)
        switchTo(chat, second)
        drainStartedActivities()
        shadowOf(activity.application).grantPermissions(*request.requestedPermissions)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, IntArray(request.requestedPermissions.size))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun aPickerOpenedInTheNextChatDoesNotTakeOverAnOpenLocationRequest() {
        val (chat, _, second) = twoChats()
        val location = pickLocation(chat)
        switchTo(chat, second)
        chat.attachFile()
        assertNotNull(shadowOf(activity).nextStartedActivityForResult)
        shadowOf(activity).receiveResult(location, android.app.Activity.RESULT_OK, location())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<Message>(), service.sent)
    }

    /** The same launcher tapped in the next chat while the first chat's request is open: it keeps the first one. */
    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun anOpenLocationRequestKeepsItsChatWhenTheNextChatTapsTheSamePicker() {
        val (chat, _, second) = twoChats()
        val location = pickLocation(chat)
        switchTo(chat, second)
        repeat(2) {
            chat.attachLocation()
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertEquals(activity.getString(R.string.tatrak_chat_picker_busy), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        }
        shadowOf(activity).receiveResult(location, android.app.Activity.RESULT_OK, location())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<Message>(), service.sent)
        // answered: the picker opens again, now for the chat shown
        shadowOf(activity).receiveResult(pickLocation(chat), android.app.Activity.RESULT_OK, location())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(second), service.sent.map { it.conversation })
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun anOpenCameraKeepsItsPhotoPathWhenTheNextChatTapsTheCamera() {
        am.cyberhayq.chat.testing.FileProviderCache.clear()
        shadowOf(activity.application).grantPermissions(Manifest.permission.CAMERA)
        val (chat, _, second) = twoChats()
        chat.takePhoto()
        assertNotNull(shadowOf(activity).nextStartedActivityForResult)
        val photo = checkNotNull(ReflectionHelpers.getField<android.net.Uri?>(chat, "pendingPhotoUri"))
        switchTo(chat, second)
        chat.takePhoto()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals(photo, ReflectionHelpers.getField<android.net.Uri?>(chat, "pendingPhotoUri"))
    }

    /** A trust-gated send in the first chat; the next chat's trust-gated send waits until that one is answered. */
    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun aSecondKeyTrustRequestWaitsForTheFirstOneToBeAnswered() {
        val trust = keysToTrust()
        val (chat, _, second) = twoChats(Message.ENCRYPTION_AXOLOTL, Message.ENCRYPTION_AXOLOTL)
        typeInto(chat, "for the first")
        val first = trustScreenAfterSend(chat)
        switchTo(chat, second)
        typeInto(chat, "for the second")
        chat.send()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals(activity.getString(R.string.tatrak_chat_trust_busy), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertEquals("for the second", model(chat).input.text)
        // the first one's cancellation does not take the second one's retry
        shadowOf(activity).receiveResult(first, android.app.Activity.RESULT_CANCELED, null)
        shadowOf(Looper.getMainLooper()).idle()
        val again = trustScreenAfterSend(chat)
        trust()
        shadowOf(activity).receiveResult(again, android.app.Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(second to "for the second"), service.sent.map { it.conversation to it.body })
    }

    /** A failed file's resend asks for the device; the answer comes after another chat opened. */
    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun aResendDeviceChoiceAnsweredAfterAChatSwitchResendsNothing() {
        val (chat, first, second) = twoChats()
        val failed = Message(first, "9", Message.ENCRYPTION_NONE, Message.STATUS_SEND_FAILED).apply {
            type = Message.TYPE_FILE
            setRelativeFilePath(Message.StorageLocation(File(activity.cacheDir, "failed.txt").apply { writeText("generated") }, false))
        }
        first.add(failed)
        fun chooserAfterRetry(): PresenceSelector.OnPresenceSelected {
            activity.chosen = null
            chat.retry(failed.uuid)
            return checkNotNull(activity.chosen)
        }
        val chooser = chooserAfterRetry()
        switchTo(chat, second)
        chooser.onPresenceSelected()
        assertEquals(emptyList<Message>(), service.resent)
        assertEquals(activity.getString(R.string.tatrak_chat_changed_not_sent), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        // answered in its own chat it resends
        switchTo(chat, first)
        chooserAfterRetry().onPresenceSelected()
        assertEquals(listOf(failed), service.resent)
    }

    /**
     * A draft in the first chat waits for the key-trust screen; the activity is recreated (a configuration change, or
     * process death) while it is open. Returns the request code the restored launcher answers to.
     */
    private fun trustScreenThenRecreate(processDeath: Boolean, otherDraft: String? = null): Triple<Int, Conversation, Conversation> {
        val (chat, first, second) = twoChats(Message.ENCRYPTION_AXOLOTL)
        otherDraft?.let(second::setNextMessage)
        typeInto(chat, "same text")
        chat.send()
        val started = checkNotNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals(TrustKeysActivity::class.java.name, started.intent.component?.className)
        if (processDeath) {
            restoreProcess()
        } else {
            controller.recreate()
            fm.executePendingTransactions()
        }
        return Triple(started.requestCode, first, second)
    }

    /** The answer as the restored activity gets it: its registry routes the request code to the fragment's launcher. */
    private fun approve(requestCode: Int, data: Intent? = null) {
        activity.activityResultRegistry.dispatchResult(requestCode, android.app.Activity.RESULT_OK, data)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun backendConnected() {
        // the fake Host binds without the ServiceConnection callback a real one gets
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onBackendConnected")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun trustRetrySentInItsChat(processDeath: Boolean) {
        val trust = keysToTrust()
        val (requestCode, first, _) = trustScreenThenRecreate(processDeath)
        backendConnected()
        trust()
        approve(requestCode)
        assertEquals(listOf(first to "same text"), service.sent.map { it.conversation to it.body })
    }

    private fun trustRetryNotSentToTheOtherChat(processDeath: Boolean) {
        val trust = keysToTrust()
        // the same draft in both: only the chat tells them apart
        val (requestCode, _, second) = trustScreenThenRecreate(processDeath, otherDraft = "same text")
        switchTo(secondary as ChatFragment, second)
        trust()
        approve(requestCode)
        assertEquals(emptyList<Message>(), service.sent)
        assertEquals(activity.getString(R.string.tatrak_chat_changed_not_sent), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    private fun trustRetryApprovedBeforeTheChatIsBound(processDeath: Boolean) {
        val trust = keysToTrust()
        val (requestCode, first, _) = trustScreenThenRecreate(processDeath)
        trust()
        approve(requestCode)
        assertEquals(emptyList<Message>(), service.sent)
        backendConnected()
        assertEquals(listOf(first to "same text"), service.sent.map { it.conversation to it.body })
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun textTrustApprovedAfterARotationIsSentInItsChat() = trustRetrySentInItsChat(processDeath = false)

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun textTrustApprovedAfterProcessDeathIsSentInItsChat() = trustRetrySentInItsChat(processDeath = true)

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun textTrustApprovedAfterARotationIsNotSentToTheChatOpenedMeanwhile() = trustRetryNotSentToTheOtherChat(processDeath = false)

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun textTrustApprovedAfterProcessDeathIsNotSentToTheChatOpenedMeanwhile() = trustRetryNotSentToTheOtherChat(processDeath = true)

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun textTrustApprovedBeforeTheRotatedChatIsBoundWaitsForIt() = trustRetryApprovedBeforeTheChatIsBound(processDeath = false)

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun textTrustApprovedBeforeTheRestoredChatIsBoundWaitsForIt() = trustRetryApprovedBeforeTheChatIsBound(processDeath = true)

    /**
     * A location picked in the first chat comes back after process death, before the service found that chat: the
     * send is queued for it. A notification binds the other chat first: the queue is dropped there with the notice
     * (cleared before anything is sent), so binding the first chat later sends nothing either.
     */
    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun aSendQueuedForTheRestoredChatIsDroppedWhenAnotherChatBindsFirst() {
        val (chat, first, second) = twoChats()
        chat.attachLocation()
        val requestCode = checkNotNull(shadowOf(activity).nextStartedActivityForResult).requestCode
        restoreProcess()
        approve(requestCode, location())
        assertEquals(emptyList<Message>(), service.sent)
        // queued for the restored chat, not dropped on arrival: the notice comes with the other chat's bind
        assertNotEquals(activity.getString(R.string.tatrak_chat_changed_not_sent), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        switchTo(secondary as ChatFragment, second)
        assertEquals(emptyList<Message>(), service.sent)
        assertEquals(activity.getString(R.string.tatrak_chat_changed_not_sent), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        switchTo(secondary as ChatFragment, first)
        assertEquals(emptyList<Message>(), service.sent)
    }

    /** As after process death: a new activity, view model and fragments from the saved state, the service not bound yet. */
    private fun restoreProcess() {
        val state = android.os.Bundle()
        controller.saveInstanceState(state)
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(Host::class.java).setup(state)
        fm.executePendingTransactions()
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun restoredPreviewsStayWithTheirChat() {
        val (chat, first, second) = twoChats()
        model(chat).previews = generatedFile("a.txt")
        // its own chat gets them back
        restoreProcess()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onBackendConnected")
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(first, (secondary as ChatFragment).getConversation())
        assertEquals(1, model(secondary as ChatFragment).previews.size)
        // another chat bound first (an intent processed before the restore) does not
        restoreProcess()
        switchTo(secondary as ChatFragment, second)
        assertEquals(emptyList<Attachment>(), model(secondary as ChatFragment).previews)
        // a configuration change keeps the view model, and with it the composer's files
        switchTo(secondary as ChatFragment, first)
        model(secondary as ChatFragment).previews = generatedFile("b.txt")
        controller.recreate()
        fm.executePendingTransactions()
        switchTo(secondary as ChatFragment, second)
        assertEquals(emptyList<Attachment>(), model(secondary as ChatFragment).previews)
    }

    private fun drainStartedActivities() {
        while (shadowOf(activity).nextStartedActivityForResult != null) Unit
        while (shadowOf(activity).nextStartedActivity != null) Unit
    }

    /** What stopRecording builds; its MIME guess for a file: URI fails on the JVM's List.of, so built directly. */
    private fun recordingOf(file: File): Attachment =
        ReflectionHelpers.callConstructor(
            Attachment::class.java,
            ClassParameter.from(android.net.Uri::class.java, android.net.Uri.fromFile(file)),
            ClassParameter.from(Attachment.Type::class.java, Attachment.Type.RECORDING),
            ClassParameter.from(String::class.java, "audio/mp4"),
        )

    /**
     * Codex's first late-callback order: a callback that could change only the room's own fields (its model showed
     * another chat) ended the target behind the composer; then Edit and cancel: the PM text never becomes room text.
     */
    @Test
    fun aTargetEndedBehindTheComposerNeverBecomesTheEditDraft() {
        val (room, guest) = roomWithGuest()
        val own = Message(room, "typo", Message.ENCRYPTION_NONE, Message.STATUS_SEND_RECEIVED)
        room.add(own)
        val chat = main as ChatFragment
        sendPrivateMessageFromTheMenu(guest)
        typeInto(chat, "psst")
        room.nextCounterpart = null
        chat.edit(own.uuid)
        assertEquals("typo", model(chat).input.text)
        chat.cancelEdit()
        assertEquals("", model(chat).input.text)
        assertNull(model(chat).privateTo)
        assertTrue(room.draft?.message().isNullOrEmpty())
    }

    /** Codex's second order: a detached fragment's callback for chat A while the shared model shows chat B. */
    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun aLateCallbackForAnotherChatLeavesTheShownComposerAlone() {
        val (room, guest) = roomWithGuest()
        val old = secondary as ChatFragment
        sendPrivateMessageFromTheMenu(guest)
        controller.recreate()
        fm.executePendingTransactions()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onBackendConnected")
        val other = service.chat(service.a, peer)
        activity.switchToConversation(other)
        fm.executePendingTransactions()
        val live = secondary as ChatFragment
        assertSame(other, model(live).conversation)
        typeInto(live, "for the other chat")
        // the room has a target again (the classic screen, say) when the old fragment's voice note ends
        room.nextCounterpart = guest.fullJid
        val recording = File(activity.cacheDir, "note.m4a").apply { writeBytes(ByteArray(16)) }
        ReflectionHelpers.callInstanceMethod<Unit>(
            old, "sendDetached",
            ClassParameter.from(XmppActivity::class.java, activity),
            ClassParameter.from(Conversation::class.java, room),
            ClassParameter.from(List::class.java, listOf(recordingOf(recording))),
        )
        assertNull(room.nextCounterpart)
        assertEquals("for the other chat", model(live).input.text)
        assertSame(other, model(live).conversation)
    }

    @Test
    fun enteringPrivateModeDuringAnEditBringsBackTheRoomDraft() {
        val (room, guest) = roomWithGuest()
        val own = Message(room, "typo", Message.ENCRYPTION_NONE, Message.STATUS_SEND_RECEIVED)
        room.add(own)
        val chat = main as ChatFragment
        typeInto(chat, "room draft")
        chat.edit(own.uuid)
        // the app goes to the background during the edit: the composer (the edit buffer) is stored
        ReflectionHelpers.callInstanceMethod<Unit>(chat, "saveDraft", ClassParameter.from(Conversation::class.java, room))
        sendPrivateMessageFromTheMenu(guest)
        assertNull(room.correctingMessage)
        assertEquals("room draft", room.draft?.message())
        chat.cancelPrivateMessage()
        assertEquals("room draft", model(chat).input.text)
    }

    @Test
    fun roomTextTypedBeforeAPrivateMessageComesBackAfterAnEdit() {
        // the emulator check of 2026-11-23: unsaved room text was lost on entering PM mode
        val (room, guest) = roomWithGuest()
        val own = Message(room, "typo", Message.ENCRYPTION_NONE, Message.STATUS_SEND_RECEIVED)
        room.add(own)
        val chat = main as ChatFragment
        typeInto(chat, "draft six")
        sendPrivateMessageFromTheMenu(guest)
        assertEquals("draft six", room.draft?.message())
        typeInto(chat, "PM text")
        chat.edit(own.uuid)
        chat.cancelEdit()
        assertEquals("draft six", model(chat).input.text)
    }

    @Test
    fun aMentionFromTheParticipantsListStillOpensTheClassicScreen() {
        val room = service.chat(service.a, Jid.of("room@conference.example.com"), muc = true)
        activity.deliver(
            Intent(activity, Host::class.java)
                .setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION)
                .putExtra(ConversationsActivity.EXTRA_CONVERSATION, room.uuid)
                .putExtra(ConversationsActivity.EXTRA_NICK, "guest")
                .putExtra(ConversationsActivity.EXTRA_IS_PRIVATE_MESSAGE, false),
        )
        fm.executePendingTransactions()
        assertTrue(main is ConversationFragment)
        assertNull(room.nextCounterpart)
    }

    private fun roomWithGuest(): Pair<Conversation, MucOptions.User> {
        val room = service.chat(service.a, Jid.of("room@conference.example.com"), muc = true)
        activity.switchToConversation(room)
        fm.executePendingTransactions()
        val guest = MucOptions.User(room.mucOptions, room.address.withResource("guest"), null, null, Role.PARTICIPANT, Affiliation.NONE, emptySet())
        room.mucOptions.updateUser(guest)
        return room to guest
    }

    private fun sendPrivateMessageFromTheMenu(guest: MucOptions.User) {
        val menu = android.widget.PopupMenu(activity, activity.window.decorView).menu
        activity.menuInflater.inflate(R.menu.muc_details_context, menu)
        eu.siacs.conversations.ui.util.MucDetailsContextMenuHelper.onContextItemSelected(
            menu.findItem(R.id.send_private_message), guest, activity,
        )
        fm.executePendingTransactions()
    }

    private fun model(chat: ChatFragment) = androidx.lifecycle.ViewModelProvider(chat)[am.cyberhayq.chat.ui.chat.ChatViewModel::class.java]

    private fun generatedFile(name: String) =
        Attachment.of(android.net.Uri.fromFile(File(activity.cacheDir, name).apply { writeText("generated") }), "text/plain")

    private fun typeInto(chat: ChatFragment, text: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            chat, "onInputChange",
            ClassParameter.from(androidx.compose.ui.text.input.TextFieldValue::class.java, androidx.compose.ui.text.input.TextFieldValue(text)),
        )
    }

    private fun composer(room: Conversation) = snapshot(room).composer

    private fun snapshot(room: Conversation) =
        ChatSnapshot.build(
            activity, service, room,
            SnapshotParams(TextPalette(0, 0, 0, 0, 0), null, 48, true, 0, false, MentionTracker(), preview = true),
            FormattedTextCache(),
        )

    @Test
    fun membershipConfirmationRechecksTheCurrentParticipant() {
        Robolectric.buildActivity(MucUsersActivity::class.java).use { controller ->
            val users = controller.create().get()
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
