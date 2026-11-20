package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.edithistory.EditHistoryScreen
import am.cyberhayq.chat.ui.edithistory.EditVersion
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.utils.UIHelper

/**
 * Tatrak: the Compose screen of [EditHistoryActivity]. The activity still loads the message and
 * its versions; this class shows the versions it hands the classic adapter.
 */
internal class EditHistoryCompose(private val activity: EditHistoryActivity) {

    private var versions by mutableStateOf(emptyList<EditVersion>())

    fun attach(classic: View) {
        activity.composeOver(classic) { EditHistoryScreen(versions, activity::finish) }
    }

    /** The versions oldest first, as `Message.getVersionsAsMessages` gives them; on the main thread. */
    fun publish(messages: List<Message>) {
        versions =
            messages.map { message ->
                // the chat list's preview words for what is not plain text (encrypted, a file, deleted)
                val preview = UIHelper.getMessagePreview(activity, message, '\n')
                EditVersion(
                    text = if (preview.second) preview.first.toString() else message.body,
                    info = preview.second,
                    meNick = if (!preview.second && message.hasMeCommand()) UIHelper.getMessageDisplayName(message) else null,
                    timestamp = message.timeSent,
                )
            }
    }
}
