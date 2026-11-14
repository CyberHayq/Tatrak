package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.chatlist.ChatListActions
import am.cyberhayq.chat.ui.chatlist.ChatListScreen
import am.cyberhayq.chat.ui.chatlist.ChatListViewModel
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.animation.Animator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
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
    /** Typed before the service was bound. */
    private var pendingSearch: List<String>? = null

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
                    ChatListScreen(state, this@ChatListFragment, messages = messages)
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
    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun refresh() {
        val service = xmppConnectionService ?: return
        unarchiveOnNewMessage(service)
        viewModel.invalidate(service)
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

/** Walks back from the newest message, so a reply sent after it doesn't hide the new message. */
internal fun hasReceivedSince(messages: List<Message>, since: Long): Boolean {
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        if (m.timeSent <= since) return false
        if (m.status == Message.STATUS_RECEIVED) return true
    }
    return false
}
