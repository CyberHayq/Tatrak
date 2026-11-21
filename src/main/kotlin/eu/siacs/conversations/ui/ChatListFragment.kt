package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.chat.ChatPreview
import am.cyberhayq.chat.ui.chat.ChatViewModel
import am.cyberhayq.chat.ui.chat.NoChatActions
import am.cyberhayq.chat.ui.chat.currentTextPalette
import am.cyberhayq.chat.ui.calls.CallsScreen
import am.cyberhayq.chat.ui.chatlist.ChatListActions
import am.cyberhayq.chat.ui.chatlist.MainTab
import am.cyberhayq.chat.ui.chatlist.MainTabs
import am.cyberhayq.chat.ui.chatlist.TabDock
import am.cyberhayq.chat.ui.chatlist.mainTabOrder
import am.cyberhayq.chat.ui.newchat.NewChatScreen
import am.cyberhayq.chat.ui.settings.AccountHeader
import am.cyberhayq.chat.ui.settings.ProfileTab
import am.cyberhayq.chat.ui.theme.BarBackdrop
import am.cyberhayq.chat.ui.theme.LocalFloatingBars
import am.cyberhayq.chat.ui.theme.LocalTatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.siacs.conversations.ui.fragment.settings.SettingsHomeFragment
import eu.siacs.conversations.ui.fragment.settings.settingsAccount
import eu.siacs.conversations.ui.fragment.settings.settingsSections
import kotlinx.coroutines.launch
import am.cyberhayq.chat.ui.chatlist.ChatListScreen
import am.cyberhayq.chat.ui.chatlist.ChatListViewModel
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.update.Updater
import android.animation.Animator
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.services.MessageSearchTask
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.activity.SettingsActivity
import eu.siacs.conversations.ui.interfaces.OnConversationArchived
import eu.siacs.conversations.ui.interfaces.OnConversationSelected
import eu.siacs.conversations.ui.interfaces.OnSearchResultsAvailable
import eu.siacs.conversations.ui.util.ChangeWatcher
import eu.siacs.conversations.ui.widget.AccountPickerDialog
import eu.siacs.conversations.utils.FtsUtils
import eu.siacs.conversations.utils.AccountUtils
import java.time.Instant

/**
 * Hosts the Compose chat list inside [ConversationsActivity] in place of
 * [ConversationsOverviewFragment], so navigation, two-pane layout and intents keep working.
 *
 * Lives in this package because [XmppFragment.refresh] is package-private.
 */
class ChatListFragment : XmppFragment(), ChatListActions, OnSearchResultsAvailable {

    private val viewModel by lazy { ViewModelProvider(this)[ChatListViewModel::class.java] }

    /** Message results of the inline search, in the shape of [SearchActivity]'s global search. */
    private val messageResults by lazy {
        MessageSearchResults(requireContext().applicationContext, lifecycleScope, { xmppConnectionService }, true)
    }
    private val currentSearch = ChangeWatcher<List<String>>()
    private var canInvite by mutableStateOf(false)
    /** Typed before the service was bound. */
    private var pendingSearch: List<String>? = null
    /** A chat archived from its own menu ([ChatFragment.archive]); the list archives it and offers Undo. */
    private var archiveFromChat by mutableStateOf<String?>(null)

    /** The chat shown by [ChatPreview]; its model only reads, it never marks anything read. */
    private val previewModel by lazy { ViewModelProvider(this)["preview", ChatViewModel::class.java].also { it.preview = true } }
    private var previewing by mutableStateOf<Conversation?>(null)
    private val previewActions =
        object : NoChatActions() {
            override suspend fun loadThumbnail(uuid: String, sizePx: Int): Bitmap? {
                val service = xmppConnectionService ?: return null
                val c = previewModel.conversation ?: return null
                return loadMessageThumbnail(service, c, uuid, sizePx)
            }

            override fun cachedThumbnail(uuid: String): Bitmap? = xmppConnectionService?.bitmapCache?.get(uuid)
        }

    /** The dock's tab ([MainTabs]); the dock styles only, Telegram's style has none and stays on Chats. */
    private var tab by mutableStateOf(MainTab.CHATS)
    /** A tap on the current tab other than Chats: its list scrolls to the top. */
    private var scrollToTop by mutableIntStateOf(0)
    // the other tabs: made in onCreate, where a fragment may register the call permission request
    private lateinit var callList: CallList
    private lateinit var contactsTab: ContactsTab
    private var profile by mutableStateOf<AccountHeader?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activity = requireXmppActivity()
        val calls = RtpCallStarter(activity, this)
        callList = CallList(activity, calls, lifecycleScope) { selectTab(MainTab.CHATS) }
        contactsTab = ContactsTab(activity, calls)
        savedInstanceState?.getString(STATE_TAB)?.let { tab = MainTab.valueOf(it) }
        // delivered once this list is started again, after the chat has gone back to it
        parentFragmentManager.setFragmentResultListener(ARCHIVE_FROM_CHAT, this) { _, result ->
            archiveFromChat = result.getString(ARCHIVE_FROM_CHAT)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, tab.name)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            // the first chat opened would otherwise wait for the emoji table
            am.cyberhayq.chat.ui.chat.EmojiWarmup.start()
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TatrakTheme {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val messages by messageResults.state.collectAsStateWithLifecycle()
                    val floating = LocalFloatingBars.current
                    // Telegram's style without floating bars has no dock, so no tabs
                    val docked = floating || LocalTatrakStyle.current == TatrakStyle.SLATE_SEA
                    val dock = { selected: MainTab -> @Composable { backdrop: BarBackdrop? -> TabDock(selected, state.account, ::selectTab, backdrop) } }
                    MainTabs(
                        if (docked) tab else MainTab.CHATS,
                        mainTabOrder(floating),
                        onBack = { selectTab(MainTab.CHATS) },
                        dock = { selected, backdrop -> TabDock(selected, state.account, ::selectTab, backdrop) },
                    ) { page ->
                        when (page) {
                            MainTab.CHATS ->
                                ChatListScreen(
                                    state,
                                    this@ChatListFragment,
                                    messages = messages,
                                    canInvite = canInvite,
                                    archiveRequest = archiveFromChat,
                                    onArchiveRequestDone = { archiveFromChat = null },
                                    onTab = ::selectTab,
                                )
                            MainTab.CONTACTS -> {
                                val contacts by contactsTab.state.collectAsStateWithLifecycle()
                                NewChatScreen(contacts, contactsTab, contactsOnly = true, dock = dock(page), scrollToTop = scrollToTop)
                            }
                            MainTab.CALLS -> {
                                val calls by callList.state.collectAsStateWithLifecycle()
                                CallsScreen(calls, callList, dock = dock(page), scrollToTop = scrollToTop)
                            }
                            MainTab.PROFILE -> {
                                val sections = remember { settingsSections(requireContext(), ::openSettingsPage) }
                                ProfileTab(profile, { AccountUtils.launchManageAccounts(requireActivity()) }, sections, dock(page), scrollToTop)
                            }
                        }
                    }
                    previewing?.let { c ->
                        val palette = currentTextPalette()
                        LaunchedEffect(palette) { previewModel.setPalette(palette) }
                        val preview by previewModel.state.collectAsStateWithLifecycle()
                        // an empty state or the previously previewed chat never shows
                        if (preview.conversationUuid == c.uuid) {
                            ChatPreview(
                                preview,
                                previewActions,
                                onOpen = {
                                    previewing = null
                                    openChat(c.uuid)
                                },
                                onDismiss = { previewing = null },
                            )
                        }
                    }
                }
            }
        }

    /** Parallax under the chat: a quarter of the width to the left, as in Telegram. */
    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): Animator? =
        slideAnimator(this, nextAnim, R.animator.tatrak_list_pop_enter, R.animator.tatrak_list_exit, -0.25f)

    override fun onBackendConnected() {
        refresh()
        pendingSearch?.let { term ->
            pendingSearch = null
            xmppConnectionService?.search(term, null, this)
        }
    }

    // the activity only refreshes visible fragments; coming back from the chat the snapshot is stale
    // a chat opened another way (notification, share) replaces the list: its card must not come back with the view
    override fun onDestroyView() {
        previewing = null
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        refresh()
        Updater.checkIfDue(requireContext())
        if (tab == MainTab.CONTACTS) contactsTab.askForContactsPermissions()
    }

    override fun refresh() {
        val service = xmppConnectionService ?: return
        // ConversationsOverviewFragment.onCreateMenu's condition for "Invite to server"
        canInvite = AccountPickerDialog.EasyInvite(requireXmppActivity()).hasAnyAccounts()
        unarchiveOnNewMessage(service)
        viewModel.invalidate(service)
        // a chat closed meanwhile (e.g. a bookmark changed on another client) closes its preview, as ChatFragment.refresh does
        previewing?.let { if (service.isConversationStillOpen(it)) previewModel.invalidate(service) else previewing = null }
        refreshTab()
    }

    /** The shown tab's data, as its own screen refreshed it (roster, block list and conversation updates). */
    private fun refreshTab() {
        when (tab) {
            MainTab.CHATS -> Unit
            MainTab.CONTACTS -> contactsTab.filter()
            MainTab.CALLS -> callList.invalidate()
            MainTab.PROFILE -> loadProfile()
        }
    }

    /** A dock tap: another tab slides in ([MainTabs]); the current one scrolls to the top. */
    private fun selectTab(selected: MainTab) {
        if (selected == tab) {
            scrollToTop++
            return
        }
        tab = selected
        // the Contacts screen filters as it comes in (its search, kept across tabs, may be open)
        if (selected == MainTab.CONTACTS) contactsTab.askForContactsPermissions() else refreshTab()
    }

    private fun loadProfile() {
        val service = xmppConnectionService ?: return
        lifecycleScope.launch { profile = settingsAccount(service, resources) ?: return@launch }
    }

    /** A settings row of the Profile tab: its page opens above everything, in the settings screen. */
    private fun openSettingsPage(title: Int) {
        startActivity(Intent(requireContext(), SettingsActivity::class.java).putExtra(SettingsHomeFragment.EXTRA_PAGE, title))
    }

    /**
     * Telegram: an archived chat that is not muted comes back with a new incoming message. Runs on
     * the main thread like the other entity writes, so the snapshot build stays read-only.
     */
    private fun unarchiveOnNewMessage(service: XmppConnectionService) {
        val messages = ArrayList<Message>()
        for (c in service.conversations) {
            val archivedAt = c.tatrakArchivedAt ?: continue
            // nothing newer than the archive time, skip the copy below
            if (c.isMuted || c.latestMessage.timeSent <= archivedAt) continue
            c.populateWithMessages(messages)
            if (hasReceivedSince(messages, archivedAt)) {
                c.tatrakArchivedAt = null
                service.updateConversation(c)
            }
        }
    }

    override fun previewChat(uuid: String) {
        val service = xmppConnectionService ?: return
        val c = service.findConversationByUuid(uuid) ?: return
        previewModel.bind(c, service, fresh = true)
        previewing = c
    }

    override fun openChat(uuid: String) {
        val conversation = xmppConnectionService?.findConversationByUuid(uuid) ?: return
        (activity as? OnConversationSelected)?.onConversationSelected(conversation)
    }

    // --- message search ------------------------------------------------------------------

    override fun searchMessages(text: String) {
        val term = FtsUtils.parse(text)
        if (!currentSearch.watch(term)) return
        if (term.isEmpty()) {
            pendingSearch = null
            MessageSearchTask.cancelRunningTasks()
            messageResults.clear()
            return
        }
        messageResults.pending()
        val service = xmppConnectionService
        if (service == null) {
            pendingSearch = term
        } else {
            service.search(term, null, this)
        }
    }

    /** Called on the search thread. */
    override fun onSearchResultsAvailable(term: List<String>, messages: List<Message>) {
        val copy = ArrayList(messages)
        activity?.runOnUiThread {
            // an answer for a text the user has typed past (or closed) is dropped
            if (term == currentSearch.get() && isAdded) messageResults.publish(term, copy)
        }
    }

    override fun openMessage(key: String) {
        val service = xmppConnectionService ?: return
        val message = messageResults.message(key) ?: return
        val found = message.conversation
        // a closed chat's results carry a stub; opening it starts the chat again like SearchActivity
        val conversation =
            found as? Conversation
                ?: service.findOrCreateConversation(
                    found.account,
                    found.address,
                    found.mode == Conversational.MODE_MULTI,
                    true,
                    true,
                )
        MessageSearchResults.openAt(requireContext(), conversation, message.uuid)
    }

    override fun newChat() = StartConversationActivity.launch(requireContext())

    override fun openContacts() {
        startActivity(Intent(requireContext(), StartConversationActivity::class.java).putExtra(NewChatCompose.EXTRA_CONTACTS, true))
    }

    /** ConversationsOverviewFragment.selectAccountToStartEasyInvite. */
    override fun inviteToServer() {
        AccountPickerDialog.EasyInvite(requireXmppActivity()).pick { account -> EasyOnboardingInviteActivity.launch(account, requireContext()) }
    }

    override fun manageAccounts() = AccountUtils.launchManageAccounts(requireActivity())

    override fun scanQrCode() {
        (activity as? QrCodeScanningActivity)?.requestPermissionAndScanQrCode()
    }

    override fun openSettings() = startActivity(Intent(requireContext(), SettingsActivity::class.java))

    /** Same two calls as the Appearance screen's theme choice. */
    override fun toggleNightMode() {
        val settings = AppSettings(requireContext())
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        settings.setTheme(if (dark) "light" else "dark")
        (activity as? BaseActivity)?.setDesiredNightMode(settings.desiredNightMode)
    }

    override fun openCalls() = startActivity(Intent(requireContext(), CallsActivity::class.java))

    override fun setArchived(uuids: List<String>, archived: Boolean) =
        update(uuids) { it.tatrakArchivedAt = if (archived) System.currentTimeMillis() else null }

    override fun setPinned(uuids: List<String>, pinned: Boolean) =
        update(uuids) { it.isPinnedOnTop = pinned }

    override fun setMuted(uuids: List<String>, till: Instant?) = update(uuids) { it.mutedTill = till }

    override fun markRead(uuids: List<String>) {
        val service = xmppConnectionService ?: return
        // honours the read-receipts setting, like opening the chat
        conversations(uuids).forEach { service.sendReadMarker(it, null) }
    }

    override fun delete(uuids: List<String>) {
        val service = xmppConnectionService ?: return
        conversations(uuids).forEach {
            // a chat started again later must not come back into the archive
            it.tatrakArchivedAt = null
            service.archiveConversation(it)
            (activity as? OnConversationArchived)?.onConversationArchived(it)
        }
    }

    companion object {
        const val ARCHIVE_FROM_CHAT = "tatrak_archive_from_chat"
    }

    private fun conversations(uuids: List<String>): List<Conversation> {
        val service = xmppConnectionService ?: return emptyList()
        return uuids.mapNotNull { service.findConversationByUuid(it) }
    }

    private fun update(uuids: List<String>, change: (Conversation) -> Unit) {
        val service = xmppConnectionService ?: return
        conversations(uuids).forEach {
            change(it)
            service.updateConversation(it)
        }
        // updateConversation only persists; refreshes the list and an open chat in the second pane
        service.updateConversationUi()
    }
}

private const val STATE_TAB = "tatrak_main_tab"

/** Walks back from the newest message, so a reply sent after it doesn't hide the new message. */
internal fun hasReceivedSince(messages: List<Message>, since: Long): Boolean {
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        if (m.timeSent <= since) return false
        if (m.status == Message.STATUS_RECEIVED) return true
    }
    return false
}
