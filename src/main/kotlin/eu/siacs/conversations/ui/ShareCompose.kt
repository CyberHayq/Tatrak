package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.common.AVATAR_SIZE
import am.cyberhayq.chat.ui.share.ShareActions
import am.cyberhayq.chat.ui.share.SharePickerScreen
import am.cyberhayq.chat.ui.share.ShareState
import am.cyberhayq.chat.ui.share.ShareTarget
import android.view.View
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.ui.util.SoftKeyboardUtils
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tatrak: the Compose chat picker of [ShareWithActivity]. The activity keeps reading the shared
 * intent, asking for storage permission, the "+" contact chooser and handing the share to the
 * chat; this class only lists the chats the activity ordered and reports the pick.
 */
internal class ShareCompose(private val activity: ShareWithActivity) : ShareActions {

    private val state = MutableStateFlow(ShareState())
    private var conversations: Map<String, Conversation> = emptyMap()
    private var avatars: Job? = null

    fun attach(classic: View) {
        activity.composeOver(classic) {
            val current by state.collectAsStateWithLifecycle()
            SharePickerScreen(current, this)
        }
    }

    /** Called on the main thread with the activity's ordered conversation list. */
    fun publish(list: List<Conversation>) {
        conversations = list.associateBy { it.uuid }
        val previous = state.value.targets.associate { it.key to it.avatar }
        state.value =
            ShareState(
                targets =
                    list.map {
                        ShareTarget(
                            key = it.uuid,
                            name = it.name.toString(),
                            address = it.address.asBareJid().toString(),
                            avatar = previous[it.uuid],
                        )
                    },
                loaded = true,
            )
        loadAvatars(list)
    }

    private fun loadAvatars(list: List<Conversation>) {
        val service = activity.xmppConnectionService ?: return
        val size = (AVATAR_SIZE * activity.resources.displayMetrics.density).roundToInt()
        avatars?.cancel()
        avatars =
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val loaded = list.associate { it.uuid to service.avatarService.get(it, size, false) }
                state.update { s ->
                    s.copy(targets = s.targets.map { t -> if (t.key in loaded) t.copy(avatar = loaded[t.key]) else t })
                }
            }
    }

    // --- actions ------------------------------------------------------------------------------

    override fun back() {
        SoftKeyboardUtils.hideSoftKeyboard(activity)
        activity.finish()
    }

    override fun pick(key: String) {
        SoftKeyboardUtils.hideSoftKeyboard(activity)
        activity.shareTo(conversations[key] ?: return)
    }

    override fun newChat() = activity.startNewChat()
}
