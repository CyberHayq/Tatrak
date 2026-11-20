package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.blocklist.BlockedItem
import am.cyberhayq.chat.ui.blocklist.BlocklistActions
import am.cyberhayq.chat.ui.blocklist.BlocklistScreen
import am.cyberhayq.chat.ui.blocklist.BlocklistState
import am.cyberhayq.chat.ui.chatlist.styledAddress
import android.graphics.Bitmap
import android.view.View
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.siacs.conversations.entities.Blockable
import eu.siacs.conversations.entities.ListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Tatrak: the Compose block list of [BlocklistActivity]. The activity keeps its logic (the list
 * from BlockingManager, its search filter and order in `filterContacts`, the updates through
 * `OnUpdateBlocklist`) and its classic layout (inflated, hidden); this class draws the list the
 * activity publishes and runs the classic FAB's and long press's dialogs.
 */
internal class BlocklistCompose(private val activity: BlocklistActivity) : BlocklistActions {

    private val state = MutableStateFlow(BlocklistState())

    /** Read by the activity on each update, so the list stays filtered. */
    var needle: String = ""
        private set

    // what the rows stand for, by key; replaced with each publish
    private var items: Map<String, ListItem> = emptyMap()

    fun attach(classic: View) {
        activity.composeOver(classic) {
            val current by state.collectAsStateWithLifecycle()
            BlocklistScreen(current, this)
        }
    }

    /** The list `filterContacts` hands the classic adapter; called on the main thread. */
    fun publish(list: List<ListItem>) {
        val byKey = LinkedHashMap<String, ListItem>()
        for (item in list) byKey[item.address.toString()] = item
        items = byKey
        state.value =
            BlocklistState(byKey.map { (key, item) -> BlockedItem(key, item.displayName, styledAddress(activity, item.address)) }, loaded = true)
    }

    // --- actions ------------------------------------------------------------------------------

    override fun back() = activity.finish()

    override fun search(needle: String) {
        this.needle = needle
        activity.filterContacts(needle)
    }

    override fun block() = activity.showEnterJidDialog()

    override fun unblock(key: String) {
        val item = items[key] ?: return
        BlockContactDialog.show(activity, item as Blockable)
    }

    override fun cachedAvatar(key: String, sizePx: Int): Bitmap? {
        val item = items[key] ?: return null
        return activity.xmppConnectionService?.avatarService?.get(item, sizePx, true)
    }

    /** As the classic row's AvatarWorkerTask: loaded per visible row, off the main thread. */
    override suspend fun avatar(key: String, sizePx: Int): Bitmap? {
        val item = items[key] ?: return null
        val service = activity.xmppConnectionService ?: return null
        return withContext(Dispatchers.IO) { service.avatarService.get(item, sizePx, false) }
    }
}
