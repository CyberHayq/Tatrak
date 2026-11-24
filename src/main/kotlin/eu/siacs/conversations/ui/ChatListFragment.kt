package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.chat.ChatPreview
import am.cyberhayq.chat.ui.chat.ScrollAnchor
import am.cyberhayq.chat.ui.chat.ChatViewModel
import am.cyberhayq.chat.ui.chat.NoChatActions
import am.cyberhayq.chat.ui.chat.currentTextPalette
import am.cyberhayq.chat.ui.accounts.AccountFilter
import am.cyberhayq.chat.ui.calls.CallsScreen
import am.cyberhayq.chat.ui.chatlist.AccountSwitcher
import am.cyberhayq.chat.ui.chatlist.LocalAccountSwitcher
import am.cyberhayq.chat.ui.theme.BarBlur
import am.cyberhayq.chat.ui.theme.LocalBarBlur
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import am.cyberhayq.chat.ui.chatlist.ChatListActions
import am.cyberhayq.chat.ui.chatlist.MainTab
import am.cyberhayq.chat.ui.chatlist.MainTabs
import am.cyberhayq.chat.ui.chatlist.TabDock
import am.cyberhayq.chat.ui.chatlist.mainTabOrder
import am.cyberhayq.chat.ui.chatlist.showArchiveUndo
import am.cyberhayq.chat.ui.newchat.NewChatScreen
import am.cyberhayq.chat.ui.settings.AccountHeader
import am.cyberhayq.chat.ui.settings.ProfileTab
import am.cyberhayq.chat.ui.settings.appLanguageContext
import am.cyberhayq.chat.ui.settings.appLanguageSummary
import am.cyberhayq.chat.ui.theme.BarBackdrop
import am.cyberhayq.chat.ui.theme.LocalFloatingBars
import am.cyberhayq.chat.ui.theme.LocalTatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import am.cyberhayq.chat.ui.chat.PreviewCovered
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
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
class ChatListFragment : XmppFragment(), ChatListActions {

    private val viewModel by lazy { ViewModelProvider(this)[ChatListViewModel::class.java] }

    /** Message results of the inline search, in the shape of [SearchActivity]'s global search. */
    private val messageResults by lazy {
        MessageSearchResults(appLanguageContext(requireContext()), lifecycleScope, { xmppConnectionService }, true)
    }
    private data class SearchRequest(
        val service: XmppConnectionService,
        val terms: List<String>,
        val accountUuid: String?,
    )
    private var searchTerms = emptyList<String>()
    private var currentSearch: SearchRequest? = null
    private var canInvite by mutableStateOf(false)
    private val snackbar = SnackbarHostState()

    /** The chat shown by [ChatPreview]; its model only reads, it never marks anything read. */
    private val previewModel by lazy { ViewModelProvider(this)["preview", ChatViewModel::class.java].also { it.preview = true } }
    private var previewing by mutableStateOf<Conversation?>(null)
    private val handOffs = PreviewHandOffs(this, R.id.main_fragment) { previewing = null }
    /** The card's tap until its pulse ends ([ChatPreview] onExpand to onOpen). */
    private var handOff: PreviewHandOff? = null
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

    /** The Profile tab's language summary, re-read on resume (the settings page may not recreate this activity). */
    private var language by mutableStateOf("")
    /** The dock's Profile item while its account switcher is open. */
    private var switcher by mutableStateOf<Rect?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activity = requireXmppActivity()
        val calls = RtpCallStarter(activity, this)
        callList = CallList(activity, calls, lifecycleScope) { selectTab(MainTab.CHATS) }
        contactsTab = ContactsTab(activity, calls)
        savedInstanceState?.getString(STATE_TAB)?.let { tab = MainTab.valueOf(it) }
        parentFragmentManager.addOnBackStackChangedListener(handOffs)
        // The chat applies the write before closing. The tab host offers Undo as soon as it is visible.
        parentFragmentManager.setFragmentResultListener(ARCHIVE_FROM_CHAT, this) { _, result ->
            val uuid = result.getString(ARCHIVE_FROM_CHAT) ?: return@setFragmentResultListener
            val context = appLanguageContext(requireContext())
            lifecycleScope.launch {
                showArchiveUndo(context, snackbar, this@ChatListFragment, listOf(uuid), showHint = tab == MainTab.CHATS)
            }
        }
    }

    override fun onDestroy() {
        parentFragmentManager.removeOnBackStackChangedListener(handOffs)
        super.onDestroy()
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
                    // ItemOptions' blur of the screen under the account switcher
                    val blur by animateDpAsState(if (switcher != null && LocalBarBlur.current != BarBlur.OFF) 12.dp else 0.dp, label = "blur")
                    CompositionLocalProvider(LocalAccountSwitcher provides { switcher = it }) {
                        PreviewCovered(previewing != null) {
                            // blur(0) would still add a clipping layer
                            Box(Modifier.fillMaxSize().then(if (blur > 0.dp) Modifier.blur(blur) else Modifier)) {
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
                                                snackbar = snackbar,
                                                onTab = ::selectTab,
                                                // as Telegram (DialogsActivity.onItemLongClick, !isTablet): no preview beside an open chat
                                                canPreview = !ConversationsActivity.isTabletView(requireActivity()),
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
                                            val sections = remember(language) { settingsSections(requireContext(), ::openSettingsPage) }
                                            ProfileTab(profile, { AccountUtils.launchManageAccounts(requireActivity()) }, sections, dock(page), scrollToTop)
                                        }
                                    }
                                }
                                if (docked && tab != MainTab.CHATS) {
                                    SnackbarHost(
                                        snackbar,
                                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp),
                                    )
                                }
                            }
                            AccountSwitcher(
                                switcher,
                                state.accounts,
                                state.selectedAccount,
                                if (docked) tab else MainTab.CHATS,
                                state.account?.avatar,
                                onDismiss = { switcher = null },
                                onAdd = ::addAccount,
                                onSelect = ::selectAccount,
                            )
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
                                onExpand = { handOff = handOffs.begin(c.uuid) },
                                onOpen = { startAt -> openFromPreview(c, startAt) },
                                onDismiss = { previewing = null },
                                draft = remember(c) { roomDraft(c) },
                            )
                        }
                    }
                }
            }
        }

    /** Under the chat: still on a tap, a quarter of the width to the left during a swipe back, as in Telegram. */
    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): Animator? =
        slideAnimator(this, nextAnim, R.animator.tatrak_list_pop_enter, R.animator.tatrak_list_exit, -0.25f)

    override fun onBackendConnected() = refresh()

    // the activity only refreshes visible fragments; coming back from the chat the snapshot is stale
    // a chat opened another way (notification, share) replaces the list: its card must not come back with the view
    override fun onDestroyView() {
        previewing = null
        handOff = null
        handOffs.reset()
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        refresh()
        Updater.checkIfDue(requireContext())
        requestContactsPermissionIfVisible()
    }

    override fun onResume() {
        super.onResume()
        language = appLanguageSummary(requireContext())
    }

    fun requestContactsPermissionIfVisible() {
        if (tab == MainTab.CONTACTS && isAdded) contactsTab.askForContactsPermissions()
    }

    override fun refresh() {
        val service = xmppConnectionService ?: return
        // ConversationsOverviewFragment.onCreateMenu's condition for "Invite to server"
        canInvite = AccountPickerDialog.EasyInvite(requireXmppActivity()).hasAnyAccounts()
        refreshMessageSearch(service)
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
            requestContactsPermissionIfVisible()
            scrollToTop++
            return
        }
        tab = selected
        // the Contacts screen filters as it comes in (its search, kept across tabs, may be open)
        if (selected == MainTab.CONTACTS) contactsTab.askForContactsPermissions() else refreshTab()
    }

    /** The account switcher's choice: every tab shows that account (null: all) from now on, also after a restart. */
    private fun selectAccount(uuid: String?) {
        AccountFilter.select(requireContext(), uuid)
        (activity as? ConversationsActivity)?.reconcileSecondaryAccount()
        refresh()
        // the tabs not shown come in with it
        contactsTab.filter()
        callList.invalidate()
        loadProfile()
    }

    /** As the Accounts screen's "Add account" (ManageAccountActivity.menuAction). */
    private fun addAccount() = startActivity(Intent(requireContext(), EditAccountActivity::class.java))

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

    /**
     * The card has become the chat ([ChatPreview]). The real chat opens as a row tap opens it, at the card's place and
     * without the push ([PreviewHandOff]); it waits for its first messages as usual and then replaces the list, card
     * included, in one step (public issue #2).
     */
    private fun openFromPreview(c: Conversation, startAt: ScrollAnchor?) {
        val request = handOff ?: return run { previewing = null }
        handOff = null
        request.startAt = startAt
        val conversation = xmppConnectionService?.findConversationByUuid(c.uuid)
        val selector = activity as? OnConversationSelected
        // closed meanwhile: nothing would replace the card
        if (conversation == null || selector == null) return handOffs.end(request)
        handOffs.open(request) { selector.onConversationSelected(conversation) }
    }

    override fun openChat(uuid: String) {
        val conversation = xmppConnectionService?.findConversationByUuid(uuid) ?: return
        (activity as? OnConversationSelected)?.onConversationSelected(conversation)
    }

    // --- message search ------------------------------------------------------------------

    override fun searchMessages(text: String) {
        val terms = FtsUtils.parse(text)
        if (terms == searchTerms) return
        searchTerms = terms
        if (terms.isEmpty()) {
            currentSearch = null
            MessageSearchTask.cancelRunningTasks()
            messageResults.clear()
        } else {
            xmppConnectionService?.let(::refreshMessageSearch)
        }
    }

    private fun refreshMessageSearch(service: XmppConnectionService) {
        if (searchTerms.isEmpty()) return
        val request = SearchRequest(service, searchTerms, AccountFilter.selected(service)?.uuid)
        if (request == currentSearch) return
        currentSearch = request
        messageResults.clear()
        MessageSearchTask.search(service, request.terms, null, request.accountUuid) { terms, messages ->
            val copy = ArrayList(messages)
            activity?.runOnUiThread {
                if (isAdded && currentSearch === request && xmppConnectionService === service &&
                    AccountFilter.selected(service)?.uuid == request.accountUuid
                ) {
                    messageResults.publish(terms, copy)
                }
            }
        }
    }

    override fun openMessage(key: String) {
        val service = xmppConnectionService ?: return
        val message = messageResults.message(key) ?: return
        val found = message.conversation
        // a closed chat's results carry a stub; opening it starts the chat again like SearchActivity, joining a group
        // only on an enabled account (a disabled one shows the history)
        val conversation =
            found as? Conversation
                ?: service.findOrCreateConversation(
                    found.account,
                    found.address,
                    found.mode == Conversational.MODE_MULTI,
                    found.account.isEnabled,
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

    override fun setArchived(uuids: List<String>, archived: Boolean) {
        val service = xmppConnectionService ?: return
        updateArchive(service, conversations(uuids), archived)
    }

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

/**
 * The list's side of a preview card becoming its chat ([PreviewHandOff]); [onCardGone] removes the card. Apart from
 * [ChatListFragment] so a FragmentManager test can drive it. It runs no pending transactions itself: that would start
 * the chat's postponed enter before its messages are laid out.
 */
internal class PreviewHandOffs(
    private val list: Fragment,
    private val containerId: Int,
    private val onCardGone: () -> Unit,
) : FragmentManager.OnBackStackChangedListener {
    private val fm get() = list.parentFragmentManager
    private val main = Handler(Looper.getMainLooper())
    /** From the card's tap until its chat is left or another fragment takes the container. */
    private var request: PreviewHandOff? = null

    /** The card was tapped; from now on any other fragment in the container ends this request. */
    fun begin(uuid: String): PreviewHandOff = PreviewHandOff(uuid).also { request = it }

    /** The pulse ended: opens the chat with [open], the row tap's call, if the request still stands. */
    fun open(handOff: PreviewHandOff, open: () -> Unit) {
        // posted: transactions already committed (a notification's chat) run first, and may end the request
        main.post {
            if (request !== handOff || !showsList()) return@post end(handOff)
            ChatFragment.previewHandOff = handOff
            try {
                open()
            } finally {
                // not taken (the classic screen opened): the next chat must not inherit it
                ChatFragment.previewHandOff = null
            }
            // posted after the open's own transaction: if nothing took the container, nothing will replace the card
            main.post { if (request === handOff && handOff.taker == null && showsList()) end(handOff) }
        }
    }

    /** The card goes, and with it the request. */
    fun end(handOff: PreviewHandOff) {
        handOff.ended = true
        if (request === handOff) request = null
        onCardGone()
    }

    /** The list's view is gone; a new one starts without a card. */
    fun reset() {
        request = null
    }

    // Another fragment took the container (a newer open, the classic screen), or the list is back after the card's
    // chat: Back while the chat waits for its messages, or during the list's exit, brings the same list view back
    // without onDestroyView, and the inert card would stay over it
    override fun onBackStackChanged() {
        val handOff = request ?: return
        val current = fm.findFragmentById(containerId)
        if (if (current === list) handOff.taker != null else current !== handOff.taker) end(handOff)
    }

    private fun showsList() = list.isAdded && fm.findFragmentById(containerId) === list
}

/** Local archive shared by the chat's menu and the list's selection actions. */
internal fun updateArchive(service: XmppConnectionService, conversations: List<Conversation>, archived: Boolean) {
    val time = if (archived) System.currentTimeMillis() else null
    for (conversation in conversations) {
        conversation.tatrakArchivedAt = time
        service.updateConversation(conversation)
    }
    service.updateConversationUi()
}

/** Walks back from the newest message, so a reply sent after it doesn't hide the new message. */
internal fun hasReceivedSince(messages: List<Message>, since: Long): Boolean {
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        if (m.timeSent <= since) return false
        if (m.status == Message.STATUS_RECEIVED) return true
    }
    return false
}
