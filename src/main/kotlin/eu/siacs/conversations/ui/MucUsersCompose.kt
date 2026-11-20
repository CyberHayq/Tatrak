package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.common.MenuEntry
import am.cyberhayq.chat.ui.common.confirmAction
import am.cyberhayq.chat.ui.participants.Participant
import am.cyberhayq.chat.ui.participants.ParticipantsActions
import am.cyberhayq.chat.ui.participants.ParticipantsScreen
import am.cyberhayq.chat.ui.participants.ParticipantsState
import am.cyberhayq.chat.ui.participants.attributeLabel
import android.content.Context
import android.graphics.Bitmap
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.MucOptions
import eu.siacs.conversations.ui.adapter.UserAdapter
import eu.siacs.conversations.ui.util.MucDetailsContextMenuHelper
import im.conversations.android.model.DynamicTag
import im.conversations.android.xmpp.model.stanza.Presence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.openintents.openpgp.util.OpenPgpUtils

/**
 * Tatrak: the Compose participant list of [MucUsersActivity]. The activity keeps its logic (the
 * roster, its order and search filter in `submitFilteredList`, `onMucRosterUpdate`) and its
 * classic layout (inflated, hidden); this class draws the list the activity publishes and runs
 * UserAdapter's tap and MucDetailsContextMenuHelper's menu for a row.
 */
internal class MucUsersCompose(private val activity: MucUsersActivity) : ParticipantsActions {

    private val state = MutableStateFlow(ParticipantsState())

    /** Read by the activity on each roster update, so the list stays filtered. */
    @Volatile
    var needle: String = ""
        private set

    // what the rows stand for, by key; replaced on the main thread with each publish
    private var users: Map<String, MucOptions.User> = emptyMap()

    fun attach(classic: View) {
        activity.composeOver(classic) {
            val current by state.collectAsStateWithLifecycle()
            ParticipantsScreen(current, this)
        }
    }

    /** The list `submitFilteredList` hands the classic adapter; it may come from a service thread. */
    fun publish(list: List<MucOptions.User>) {
        activity.runOnUiThread {
            val byKey = LinkedHashMap<String, MucOptions.User>()
            val rows =
                list.mapIndexed { index, user ->
                    val id = user.fullJid?.toString() ?: user.realJid?.toString() ?: "#$index"
                    val key = if (id in byKey) "$id#$index" else id
                    byKey[key] = user
                    participant(key, user)
                }
            users = byKey
            state.value = ParticipantsState(rows, loaded = true)
        }
    }

    /** UserAdapter's tags: the affiliation / role on the right, the others with the real JID below. */
    private fun participant(key: String, user: MucOptions.User): Participant {
        var role = 0
        val details = ArrayList<String>()
        for (tag in user.dynamicTags) {
            if (tag is DynamicTag.Attributes) role = attributeLabel(tag.affiliation(), tag.role())
            else details += tagLabel(activity, tag)
        }
        user.realJid?.let { details += it.toString() }
        // UserAdapter's key id condition
        val pgpKey =
            if (user.mucOptions.isPrivateAndNonAnonymous && user.pgpKeyId != 0L) OpenPgpUtils.convertKeyIdToHex(user.pgpKeyId)
            else null
        return Participant(key, user.displayName, details.joinToString(" · "), role, pgpKey, user.avatar)
    }

    // --- actions ------------------------------------------------------------------------------

    override fun back() = activity.finish()

    override fun search(needle: String) {
        this.needle = needle
        activity.submitFilteredList(needle)
    }

    /** UserAdapter's row click. */
    override fun open(key: String) {
        val user = users[key] ?: return
        if (user.resource() == null) {
            Toast.makeText(
                    activity,
                    activity.getString(R.string.user_has_left_conference, user.displayName),
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
        activity.highlightInMuc(user.conversation, user.resource())
    }

    /**
     * The classic context menu, inflated and configured by MucDetailsContextMenuHelper as for the
     * hidden list; its "Manage permissions" submenu is flattened into the one Compose menu.
     */
    override fun menu(key: String): List<MenuEntry> {
        val user = users[key] ?: return emptyList()
        val menu = PopupMenu(activity, activity.window.decorView).menu
        activity.menuInflater.inflate(R.menu.muc_details_context, menu)
        MucDetailsContextMenuHelper.configureMucDetailsContextMenu(activity, menu, user)
        return visible(menu).map { item ->
            MenuEntry(item.title.toString(), enabled = item.isEnabled) {
                val select = Runnable { MucDetailsContextMenuHelper.onContextItemSelected(item, user, activity) }
                if (item.itemId == R.id.remove_membership) confirmRemoveMembership(user, select) else select.run()
            }
        }
    }

    /** "Remove membership" changes the affiliation at once; a members-only room also drops the user. */
    private fun confirmRemoveMembership(user: MucOptions.User, remove: Runnable) {
        val name = user.displayName
        val text =
            if (user.mucOptions.membersOnly()) R.string.tatrak_remove_membership_members_only
            else R.string.tatrak_remove_membership_open
        confirmAction(
            activity,
            activity.getString(R.string.tatrak_remove_membership_title, name),
            activity.getString(text, name),
            R.string.tatrak_remove,
            remove,
        )
    }

    private fun visible(menu: Menu): List<android.view.MenuItem> =
        (0 until menu.size()).map(menu::getItem).filter { it.isVisible }.flatMap {
            if (it.hasSubMenu()) visible(it.subMenu!!) else listOf(it)
        }

    override fun openPgpKey(key: String) {
        val user = users[key] ?: return
        UserAdapter.openPgpKey(activity, user)
    }

    override fun cachedAvatar(key: String, sizePx: Int): Bitmap? {
        val user = users[key] ?: return null
        return activity.xmppConnectionService?.avatarService?.get(user, sizePx, true)
    }

    /** As the classic row's AvatarWorkerTask: loaded per visible row, off the main thread. */
    override suspend fun avatar(key: String, sizePx: Int): Bitmap? {
        val user = users[key] ?: return null
        val service = activity.xmppConnectionService ?: return null
        return withContext(Dispatchers.IO) { service.avatarService.get(user, sizePx, false) }
    }
}

/** UserAdapter.setHats' text of a tag other than [DynamicTag.Attributes] (drawn as the role); also the new-chat rows' tags. */
internal fun tagLabel(context: Context, tag: DynamicTag): String =
    when (tag) {
        is DynamicTag.Hat -> tag.title()
        is DynamicTag.Status -> context.getString(presence(tag.availability()))
        is DynamicTag.RosterGroup -> tag.name()
        is DynamicTag.Blocked -> context.getString(R.string.blocked)
        is DynamicTag.Attributes -> error("drawn as the role")
    }

private fun presence(availability: Presence.Availability): Int =
    when (availability) {
        Presence.Availability.CHAT -> R.string.presence_chat
        Presence.Availability.ONLINE -> R.string.presence_online
        Presence.Availability.AWAY -> R.string.presence_away
        Presence.Availability.XA -> R.string.presence_xa
        Presence.Availability.DND -> R.string.presence_dnd
        Presence.Availability.OFFLINE -> R.string.presence_offline
    }
