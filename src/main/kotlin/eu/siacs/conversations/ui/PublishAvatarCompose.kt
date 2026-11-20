package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.avatar.ContactsOnly
import am.cyberhayq.chat.ui.avatar.PublishAvatarActions
import am.cyberhayq.chat.ui.avatar.PublishAvatarScreen
import am.cyberhayq.chat.ui.avatar.PublishAvatarState
import am.cyberhayq.chat.ui.common.MenuEntry
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.ActionBar
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.siacs.conversations.databinding.ActivityPublishProfilePictureBinding
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tatrak: the Compose screen of [PublishProfilePictureActivity] and
 * [PublishGroupChatProfilePictureActivity]. Both keep their whole flow (crop, preview, PEP access
 * model, publish, delete) on the classic views of `activity_publish_profile_picture`, inflated and
 * hidden; this class draws what those views show, read after each change the activity reports
 * through [refresh], and runs the views' own click handlers, so every action is the classic one.
 */
internal class PublishAvatarCompose(
    private val activity: XmppActivity,
    private val binding: ActivityPublishProfilePictureBinding,
) : PublishAvatarActions {

    private val state = MutableStateFlow(PublishAvatarState())
    private val handler = Handler(Looper.getMainLooper())
    private val read = Runnable { state.value = read() }

    // the classic options menu, as the hidden toolbar has it
    private val menu: List<MenuEntry> by lazy {
        val menu = PopupMenu(activity, activity.window.decorView).menu
        activity.onCreateOptionsMenu(menu)
        (0 until menu.size()).map(menu::getItem).filter { it.isVisible }.map { item ->
            MenuEntry(item.title.toString(), enabled = item.isEnabled) { activity.onOptionsItemSelected(item) }
        }
    }

    fun attach() {
        activity.composeOver(binding.root) {
            val current by state.collectAsStateWithLifecycle()
            PublishAvatarScreen(current, this)
        }
        refresh()
    }

    /** The activity changed its views; they are read once the current main-thread work is done. */
    fun refresh() {
        handler.removeCallbacks(read)
        handler.post(read)
    }

    private fun read(): PublishAvatarState {
        val switch = binding.contactOnly
        val warning = binding.hintOrWarning
        return PublishAvatarState(
            title = activity.title?.toString().orEmpty(),
            showBack = ((activity.supportActionBar?.displayOptions ?: 0) and ActionBar.DISPLAY_HOME_AS_UP) != 0,
            avatar = (binding.accountImage.drawable as? BitmapDrawable)?.bitmap,
            backToDefault = binding.secondaryHint.visibility == View.VISIBLE,
            contactsOnly =
                if (switch.visibility == View.GONE) null
                else ContactsOnly(switch.isChecked, switch.isEnabled, loading = switch.visibility != View.VISIBLE),
            warning = warning.text?.toString()?.takeIf { warning.visibility == View.VISIBLE && it.isNotEmpty() },
            publishLabel = binding.publishButton.text.toString(),
            publishEnabled = binding.publishButton.isEnabled,
            cancelLabel = binding.cancelButton.text.toString(),
            menu = menu,
        )
    }

    // --- actions ------------------------------------------------------------------------------

    override fun back() = activity.finish()

    override fun pick() {
        binding.accountImage.callOnClick()
    }

    override fun backToDefault() {
        binding.accountImage.performLongClick()
    }

    override fun contactsOnly(checked: Boolean) {
        binding.contactOnly.isChecked = checked
        refresh()
    }

    override fun publish() {
        if (binding.publishButton.isEnabled) binding.publishButton.callOnClick()
    }

    override fun cancel() {
        binding.cancelButton.callOnClick()
    }
}
