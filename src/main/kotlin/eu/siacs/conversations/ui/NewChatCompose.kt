package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.newchat.NewChatActions
import am.cyberhayq.chat.ui.newchat.NewChatEntry
import am.cyberhayq.chat.ui.newchat.NewChatMenu
import am.cyberhayq.chat.ui.newchat.NewChatRow
import am.cyberhayq.chat.ui.newchat.NewChatScreen
import am.cyberhayq.chat.ui.newchat.NewChatState
import android.view.View
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import eu.siacs.conversations.R
import im.conversations.android.model.Bookmark
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.ListItem
import im.conversations.android.xmpp.model.idle.LastUserInteraction
import eu.siacs.conversations.services.QuickConversationsService
import eu.siacs.conversations.utils.UIHelper
import eu.siacs.conversations.xmpp.manager.BlockingManager
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tatrak: the Compose "New message" screen of [StartConversationActivity]. The activity keeps all
 * its logic and its classic layout (inflated, hidden), so view intents, invites, dialogs,
 * permissions and the context-menu actions run unchanged; this class only draws the lists the
 * activity's `filter()` produced and calls the activity's methods back.
 */
internal class NewChatCompose(private val activity: StartConversationActivity) : NewChatActions {

    private val contactsOnly = activity.intent.getBooleanExtra(EXTRA_CONTACTS, false)
    private val calls = RtpCallStarter(activity)

    private val state =
        MutableStateFlow(
            NewChatState(
                rows =
                    NewChatRow.entries.filter {
                        it != NewChatRow.DISCOVER || !QuickConversationsService.isPlayStoreFlavor()
                    },
                hideOffline = activity.isHidingOfflineContacts,
            )
        )

    /** Read by the activity's `searchNeedle()`, so every legacy re-filter keeps the search. */
    @Volatile
    var needle: String = ""
        private set

    private val searchRequest = MutableStateFlow<Pair<Int, String?>?>(null)
    private var requestId = 0

    fun openSearch(value: String?) {
        searchRequest.value = ++requestId to value
    }

    // what the rows stand for, by key; replaced on the main thread with each publish
    private var contacts: Map<String, Contact> = emptyMap()
    private var groups: Map<String, Bookmark> = emptyMap()
    private var avatars: Job? = null

    fun attach(classic: View) {
        activity.composeOver(classic) {
            val current by state.collectAsStateWithLifecycle()
            val request by searchRequest.collectAsStateWithLifecycle()
            NewChatScreen(current, this, contactsOnly = contactsOnly, searchRequest = request)
        }
    }

    /** Called on the main thread at the end of the activity's `filter()`. */
    fun publish(contactItems: List<ListItem>, groupItems: List<ListItem>) {
        val service = activity.xmppConnectionService ?: return
        val contactsByKey = LinkedHashMap<String, Contact>()
        val contactEntries =
            contactItems.filterIsInstance<Contact>().map { contact ->
                val key = key(contact.account.uuid, contact.address.toString())
                contactsByKey[key] = contact
                val interaction = contact.lastUserInteraction
                val online = interaction is LastUserInteraction.Online
                NewChatEntry(
                    key = key,
                    name = contact.displayName,
                    subtitle =
                        if (online) UIHelper.lastUserInteraction(activity, interaction)
                        else contact.address.asBareJid().toString(),
                    online = online,
                    menu = contactMenu(contact),
                )
            }
        val groupsByKey = LinkedHashMap<String, Bookmark>()
        val groupEntries =
            groupItems.filterIsInstance<Bookmark>().map { bookmark ->
                val key = key(bookmark.account.uuid, bookmark.address.toString())
                groupsByKey[key] = bookmark
                val conversation = service.find(bookmark)
                NewChatEntry(
                    key = key,
                    name = bookmark.displayName,
                    subtitle = bookmark.address.asBareJid().toString(),
                    menu =
                        buildList {
                            // the classic menu hides sharing for private rooms
                            if (conversation == null || !conversation.isPrivateAndNonAnonymous) add(NewChatMenu.SHARE)
                            add(if (conversation != null) NewChatMenu.DELETE_AND_CLOSE else NewChatMenu.DELETE_BOOKMARK)
                        },
                )
            }
        contacts = contactsByKey
        groups = groupsByKey
        // keep the avatars already loaded, so rows don't blink while the new ones load
        val previous = (state.value.contacts + state.value.groups).associate { it.key to it.avatar }
        state.update {
            it.copy(
                contacts = contactEntries.map { e -> e.copy(avatar = previous[e.key]) },
                groups = groupEntries.map { e -> e.copy(avatar = previous[e.key]) },
                hideOffline = activity.isHidingOfflineContacts,
                loaded = true,
            )
        }
        // only the rows without an avatar yet: each keystroke publishes again
        loadAvatars(
            contactsByKey.filterKeys { previous[it] == null },
            groupsByKey.filterKeys { previous[it] == null },
        )
    }

    private fun loadAvatars(contacts: Map<String, Contact>, groups: Map<String, Bookmark>) {
        val service = activity.xmppConnectionService ?: return
        val size = (44 * activity.resources.displayMetrics.density).roundToInt()
        if (contacts.isEmpty() && groups.isEmpty()) return
        avatars?.cancel()
        avatars =
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val loaded = HashMap<String, android.graphics.Bitmap?>()
                contacts.forEach { (key, item) -> loaded[key] = service.avatarService.get(item, size, false) }
                groups.forEach { (key, item) -> loaded[key] = service.avatarService.get(item, size, false) }
                state.update {
                    it.copy(
                        contacts = it.contacts.map { e -> if (e.key in loaded) e.copy(avatar = loaded[e.key]) else e },
                        groups = it.groups.map { e -> if (e.key in loaded) e.copy(avatar = loaded[e.key]) else e },
                    )
                }
            }
    }

    /** Same visibility rules as `MyListFragment.onCreateContextMenu` for `contact_context`. */
    private fun contactMenu(contact: Contact): List<NewChatMenu> = buildList {
        if (!contact.isSelf) add(NewChatMenu.DETAILS)
        if (contactsOnly && !contact.isSelf) add(NewChatMenu.CALL)
        add(NewChatMenu.QR)
        val connection = contact.account.xmppConnection
        if (connection != null && connection.getManager(BlockingManager::class.java).hasFeature() && !contact.isSelf) {
            add(if (contact.isBlocked) NewChatMenu.UNBLOCK else NewChatMenu.BLOCK)
        }
        if (contact.showInRoster() && !contact.getOption(Contact.Options.SYNCED_VIA_OTHER)) {
            add(NewChatMenu.DELETE_CONTACT)
        }
    }

    // --- actions ------------------------------------------------------------------------------

    override fun back() = activity.finish()

    override fun search(needle: String) {
        this.needle = needle
        activity.filter(needle)
    }

    override fun searchDone() {
        // as the classic search's "done" on the contacts tab
        val contact = contacts.values.singleOrNull()
        val group = groups.values.singleOrNull()
        when {
            contact != null -> openContact(key(contact.account.uuid, contact.address.toString()))
            !contactsOnly && contacts.isEmpty() && group != null -> activity.openConversationsForBookmark(group)
        }
    }

    override fun row(row: NewChatRow) {
        activity.onFabAction(
            when (row) {
                NewChatRow.NEW_GROUP -> R.id.create_private_group_chat
                NewChatRow.NEW_CHANNEL -> R.id.create_public_channel
                NewChatRow.ADD_CONTACT -> R.id.create_contact
                NewChatRow.JOIN -> R.id.join_public_channel
                NewChatRow.DISCOVER -> R.id.discover_public_channels
            }
        )
    }

    // the Contacts screen too opens the chat, as Telegram does; the profile is in the long-press menu
    override fun openContact(key: String) {
        activity.openConversationForContact(contacts[key] ?: return)
    }

    override fun openGroup(key: String) {
        activity.openConversationsForBookmark(groups[key] ?: return)
    }

    override fun contactMenu(key: String, item: NewChatMenu) {
        val contact = contacts[key] ?: return
        // the legacy actions read the position from contact_context_id
        val position = activity.contacts.indexOf(contact)
        if (position < 0) return
        activity.contact_context_id = position
        when (item) {
            NewChatMenu.DETAILS -> activity.openDetailsForContact()
            NewChatMenu.CALL -> {
                val service = activity.xmppConnectionService ?: return
                calls.call(service.findOrCreateConversation(contact.account, contact.address, false, true), false)
            }
            NewChatMenu.QR -> activity.showQrForContact()
            NewChatMenu.BLOCK, NewChatMenu.UNBLOCK -> activity.toggleContactBlock()
            NewChatMenu.DELETE_CONTACT -> activity.deleteContact()
            else -> Unit
        }
    }

    override fun groupMenu(key: String, item: NewChatMenu) {
        val bookmark = groups[key] ?: return
        val position = activity.conferences.indexOf(bookmark)
        if (position < 0) return
        activity.conference_context_id = position
        when (item) {
            NewChatMenu.SHARE -> activity.shareBookmarkUri()
            NewChatMenu.DELETE_BOOKMARK, NewChatMenu.DELETE_AND_CLOSE -> activity.deleteConference()
            else -> Unit
        }
    }

    override fun toggleHideOffline() {
        activity.setHideOfflineContacts(!activity.isHidingOfflineContacts)
    }

    companion object {
        const val EXTRA_CONTACTS = "tatrak_contacts_only"
        private fun key(account: String, address: String) = "$account/$address"
    }
}
