package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.chatlist.styledAddress
import am.cyberhayq.chat.ui.newchat.NewChatActions
import am.cyberhayq.chat.ui.newchat.NewChatEntry
import am.cyberhayq.chat.ui.newchat.NewChatMenu
import am.cyberhayq.chat.ui.newchat.NewChatRow
import am.cyberhayq.chat.ui.newchat.NewChatScreen
import am.cyberhayq.chat.ui.newchat.NewChatState
import android.view.View
import androidx.preference.PreferenceManager
import de.gultsch.common.MiniUri
import eu.siacs.conversations.ui.util.SoftKeyboardUtils
import eu.siacs.conversations.utils.AccountUtils
import eu.siacs.conversations.xmpp.Jid
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.R
import im.conversations.android.model.Bookmark
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.ListItem
import im.conversations.android.model.DynamicTag
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
 * Tatrak: the lists of the Compose "New message" screen ([NewChatScreen]) and its contact menus, shared by
 * [NewChatCompose] (over [StartConversationActivity]) and [ContactsTab] (the chat list's Contacts tab). Draws
 * only what the host's legacy `filter()` produced; the actions are the host's.
 */
internal abstract class NewChatModel(
    protected val activity: XmppActivity,
    protected val contactsOnly: Boolean,
    protected val calls: RtpCallStarter,
    hideOffline: Boolean,
) : NewChatActions {

    val state =
        MutableStateFlow(
            NewChatState(
                rows =
                    NewChatRow.entries.filter {
                        it != NewChatRow.DISCOVER || !QuickConversationsService.isPlayStoreFlavor()
                    },
                hideOffline = hideOffline,
            )
        )

    /** Read by the activity's `searchNeedle()`, so every legacy re-filter keeps the search. */
    @Volatile
    var needle: String = ""
        protected set

    // what the rows stand for, by key; replaced on the main thread with each publish
    protected var contacts: Map<String, Contact> = emptyMap()
    protected var groups: Map<String, Bookmark> = emptyMap()
    private var avatars: Job? = null

    protected abstract fun hidingOffline(): Boolean

    /** Called on the main thread at the end of the activity's `filter()`. */
    fun publish(contactItems: List<ListItem>, groupItems: List<ListItem>) {
        val service = activity.xmppConnectionService ?: return
        // ListItemAdapter.refreshSettings / getView: "Blocked" always, the other tags with the setting on
        val showDynamicTags = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(AppSettings.SHOW_DYNAMIC_TAGS, false)
        // Blocked first: the subtitle line is ellipsized, a long roster group must not hide it
        fun tags(item: ListItem) =
            item.tags.takeIf { Contact.isNoteworthy(it) || showDynamicTags }.orEmpty()
                .sortedByDescending { it is DynamicTag.Blocked }.map { tagLabel(activity, it) }
        val contactsByKey = LinkedHashMap<String, Contact>()
        val contactEntries =
            contactItems.filterIsInstance<Contact>().map { contact ->
                val key = key(contact.account.uuid, contact.address.toString())
                contactsByKey[key] = contact
                val interaction = contact.lastUserInteraction
                val online = interaction is LastUserInteraction.Online
                val address = styledAddress(activity, contact.address.asBareJid())
                NewChatEntry(
                    key = key,
                    name = contact.displayName,
                    subtitle =
                        // an address with marked look-alike characters is shown even for an online contact
                        if (online && address.spanStyles.isEmpty()) AnnotatedString(UIHelper.lastUserInteraction(activity, interaction))
                        else address,
                    tags = tags(contact),
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
                    subtitle = styledAddress(activity, bookmark.address.asBareJid()),
                    tags = tags(bookmark),
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
                hideOffline = hidingOffline(),
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

    companion object {
        fun key(account: String, address: String) = "$account/$address"
    }
}

/**
 * Tatrak: the Compose "New message" screen of [StartConversationActivity]. The activity keeps all
 * its logic and its classic layout (inflated, hidden), so view intents, invites, dialogs,
 * permissions and the context-menu actions run unchanged; this class only draws the lists the
 * activity's `filter()` produced and calls the activity's methods back.
 */
internal class NewChatCompose(private val host: StartConversationActivity) :
    NewChatModel(host, host.intent.getBooleanExtra(EXTRA_CONTACTS, false), RtpCallStarter(host), host.isHidingOfflineContacts) {

    private val searchRequest = MutableStateFlow<Pair<Int, String?>?>(null)
    private var requestId = 0

    fun openSearch(value: String?) {
        searchRequest.value = ++requestId to value
    }

    override fun hidingOffline() = host.isHidingOfflineContacts

    fun attach(classic: View) {
        host.composeOver(classic) {
            val current by state.collectAsStateWithLifecycle()
            val request by searchRequest.collectAsStateWithLifecycle()
            NewChatScreen(current, this, contactsOnly = contactsOnly, searchRequest = request)
        }
    }

    // --- actions ------------------------------------------------------------------------------

    override fun back() = host.finish()

    override fun search(needle: String) {
        this.needle = needle
        host.filter(needle)
    }

    override fun searchDone() {
        // as the classic search's "done" on the contacts tab
        val contact = contacts.values.singleOrNull()
        val group = groups.values.singleOrNull()
        when {
            contact != null -> openContact(key(contact.account.uuid, contact.address.toString()))
            !contactsOnly && contacts.isEmpty() && group != null -> host.openConversationsForBookmark(group)
        }
    }

    override fun row(row: NewChatRow) {
        host.onFabAction(
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
        host.openConversationForContact(contacts[key] ?: return)
    }

    override fun openGroup(key: String) {
        host.openConversationsForBookmark(groups[key] ?: return)
    }

    override fun contactMenu(key: String, item: NewChatMenu) {
        val contact = contacts[key] ?: return
        // the legacy actions read the position from contact_context_id
        val position = host.contacts.indexOf(contact)
        if (position < 0) return
        host.contact_context_id = position
        when (item) {
            NewChatMenu.DETAILS -> host.openDetailsForContact()
            NewChatMenu.CALL -> {
                val service = host.xmppConnectionService ?: return
                calls.call(service.findOrCreateConversation(contact.account, contact.address, false, true), false)
            }
            NewChatMenu.QR -> host.showQrForContact()
            NewChatMenu.BLOCK, NewChatMenu.UNBLOCK -> host.toggleContactBlock()
            NewChatMenu.DELETE_CONTACT -> host.deleteContact()
            else -> Unit
        }
    }

    override fun groupMenu(key: String, item: NewChatMenu) {
        val bookmark = groups[key] ?: return
        val position = host.conferences.indexOf(bookmark)
        if (position < 0) return
        host.conference_context_id = position
        when (item) {
            NewChatMenu.SHARE -> host.shareBookmarkUri()
            NewChatMenu.DELETE_BOOKMARK, NewChatMenu.DELETE_AND_CLOSE -> host.deleteConference()
            else -> Unit
        }
    }

    override fun toggleHideOffline() {
        host.setHideOfflineContacts(!host.isHidingOfflineContacts)
    }

    companion object {
        const val EXTRA_CONTACTS = "tatrak_contacts_only"
    }
}

/**
 * Tatrak: the chat list's Contacts tab: [NewChatScreen] in contacts-only mode inside
 * [ConversationsActivity]. Runs [StartConversationActivity]'s contact flows, moved unchanged into
 * [ContactFlows], and the same activity methods its context menu calls, in the same order.
 */
internal class ContactsTab(activity: XmppActivity, calls: RtpCallStarter) :
    NewChatModel(activity, true, calls, ContactFlows.isHidingOfflineContacts(preferences(activity))) {

    // the activity's fields of the same names
    private val list = ArrayList<ListItem>()
    private var hideOffline = ContactFlows.isHidingOfflineContacts(preferences(activity))
    private val requestedContactsPermission = AtomicBoolean(false)

    override fun hidingOffline() = hideOffline

    /** The activity's `filter()` for the contacts it shows; also on roster, presence and block list updates. */
    fun filter() {
        if (!activity.xmppConnectionServiceBound) return
        ContactFlows.filterContacts(activity.xmppConnectionService, list, hideOffline, needle)
        publish(list, emptyList())
    }

    /** As the activity's onStart: the contact list integration asks for its permission. */
    fun askForContactsPermissions() {
        ContactFlows.askForContactsPermissions(activity, requestedContactsPermission)
    }

    // a tab has no back button (Back goes to Chats); the bar's back arrow only closes the search
    override fun back() = Unit

    override fun search(needle: String) {
        this.needle = needle
        filter()
    }

    override fun searchDone() {
        val contact = contacts.values.singleOrNull() ?: return
        openContact(key(contact.account.uuid, contact.address.toString()))
    }

    /** Only "Add contact" shows in contacts-only mode; as the activity's `onFabAction(R.id.create_contact)`. */
    override fun row(row: NewChatRow) {
        if (row != NewChatRow.ADD_CONTACT) return
        val service = activity.xmppConnectionService ?: return
        val prefilled = if (StartConversationActivity.isValidJid(needle)) Jid.of(needle).toString() else null
        // the activity's mActivatedAccounts, as its onBackendConnected fills it
        ContactFlows.showCreateContactDialog(activity, ArrayList(AccountUtils.getEnabledAccounts(service)), prefilled, null)
    }

    /** As the activity's `openConversationForContact(Contact)`. */
    override fun openContact(key: String) {
        val contact = contacts[key] ?: return
        val conversation = activity.xmppConnectionService?.findOrCreateConversation(contact.account, contact.address, false, true) ?: return
        SoftKeyboardUtils.hideSoftKeyboard(activity)
        activity.switchToConversation(conversation)
    }

    override fun openGroup(key: String) = Unit

    /** The bodies of the activity's `openDetailsForContact`, `showQrForContact`, `toggleContactBlock` and `deleteContact`. */
    override fun contactMenu(key: String, item: NewChatMenu) {
        val contact = contacts[key] ?: return
        when (item) {
            NewChatMenu.DETAILS -> activity.switchToContactDetails(contact)
            NewChatMenu.CALL -> {
                val service = activity.xmppConnectionService ?: return
                calls.call(service.findOrCreateConversation(contact.account, contact.address, false, true), false)
            }
            NewChatMenu.QR -> activity.showQrCode(MiniUri.Xmpp(contact.address))
            NewChatMenu.BLOCK, NewChatMenu.UNBLOCK -> BlockContactDialog.show(activity, contact)
            NewChatMenu.DELETE_CONTACT -> ContactFlows.deleteContact(activity, contact) { filter() }
            else -> Unit
        }
    }

    override fun groupMenu(key: String, item: NewChatMenu) = Unit

    /** As the activity's `setHideOfflineContacts`. */
    override fun toggleHideOffline() {
        hideOffline = !hideOffline
        ContactFlows.setHideOfflineContacts(preferences(activity), hideOffline)
        filter()
    }

    private companion object {
        /** XmppActivity's `getPreferences()`. */
        fun preferences(activity: XmppActivity) = PreferenceManager.getDefaultSharedPreferences(activity.applicationContext)
    }
}
