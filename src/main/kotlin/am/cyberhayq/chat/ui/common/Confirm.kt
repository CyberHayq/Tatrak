@file:JvmName("Confirm")

package am.cyberhayq.chat.ui.common

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.CheckBox
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.services.XmppConnectionService
import java.io.File
import java.util.concurrent.Callable

/** A destructive action's question: [text] says what will happen; [action] runs only on [button]. */
fun confirmAction(
    context: Context,
    title: CharSequence?,
    text: CharSequence,
    @StringRes button: Int,
    action: Runnable,
): AlertDialog =
    MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(text)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(button) { _, _ -> action.run() }
        .show()

/**
 * ConversationFragment.clearHistoryDialog for the chat and the profile: the same calls in the same
 * order; [onDone] gets whether the chat was closed (left, for a group) as well.
 */
fun confirmClearHistory(
    activity: Activity,
    c: Conversation,
    service: () -> XmppConnectionService?,
    onDone: (closed: Boolean) -> Unit,
) {
    val content = activity.layoutInflater.inflate(R.layout.tatrak_dialog_checkbox, null)
    val close = content.findViewById<CheckBox>(R.id.tatrak_checkbox)
    close.setText(
        if (c.mode == Conversation.MODE_MULTI) R.string.tatrak_clear_history_leave_group
        else R.string.tatrak_clear_history_close_chat
    )
    MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.clear_conversation_history)
        .setMessage(R.string.tatrak_clear_history_explained)
        .setView(content)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.tatrak_delete) { _, _ ->
            val s = service() ?: return@setPositiveButton
            s.clearConversationHistory(c)
            if (close.isChecked) s.archiveConversation(c)
            onDone(close.isChecked)
        }
        .show()
}

/** The text of the chat list's and the profile's "Delete chat" / "Leave group" (archiveConversation). */
fun deleteChatTitle(context: Context, name: String, isGroup: Boolean): String =
    context.getString(if (isGroup) R.string.tatrak_leave_group_title else R.string.tatrak_delete_chat_with, name)

fun deleteChatText(context: Context, name: String, isGroup: Boolean): String =
    if (isGroup) context.getString(R.string.tatrak_leave_group_explained)
    else context.getString(R.string.tatrak_delete_chat_explained, name)

/** "Delete file" for [files] files that [others] more messages show: FileBackend deletes them there too. */
fun deleteFilesText(context: Context, files: Int, others: Int): String {
    val r = context.resources
    val also =
        if (others == 0) null
        else r.getQuantityString(if (files == 1) R.plurals.tatrak_delete_file_also else R.plurals.tatrak_delete_files_also, others, others)
    return listOfNotNull(
            r.getQuantityString(R.plurals.tatrak_delete_files_local, files, files),
            also,
            context.getString(R.string.tatrak_delete_file_copies),
        )
        .joinToString(" ")
}

/** MediaBrowserActivity's delete: counts the other messages that show these files first, like ChatFragment.deleteFile. */
fun confirmDeleteFiles(activity: Activity, files: Collection<File>, delete: Runnable) {
    val db = DatabaseBackend.getInstance(activity)
    val others =
        Futures.submit(
            Callable { files.sumOf { (db.getMessagesWithFile(it).size - 1).coerceAtLeast(0) } },
            XmppConnectionService.DATABASE_READER,
        )
    fun ask(count: Int) {
        if (activity.isFinishing) return
        val title = activity.resources.getQuantityString(R.plurals.delete_files_dialog, files.size, files.size)
        confirmAction(activity, title, deleteFilesText(activity, files.size, count), R.string.tatrak_delete, delete)
    }
    Futures.addCallback(
        others,
        object : FutureCallback<Int> {
            override fun onSuccess(result: Int) = ask(result)

            override fun onFailure(t: Throwable) {
                Log.e(Config.LOGTAG, "could not count the messages of the files", t)
                ask(0)
            }
        },
        ContextCompat.getMainExecutor(activity),
    )
}
