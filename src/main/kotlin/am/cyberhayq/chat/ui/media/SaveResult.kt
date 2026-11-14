package am.cyberhayq.chat.ui.media

import android.content.Context
import eu.siacs.conversations.R

/** Toast text after "Save" of several files; [results] is `FileBackend.saveInternalToExternal`'s list, true = saved. */
object SaveResult {
    @JvmStatic
    fun message(context: Context, results: List<Boolean?>): String {
        val saved = results.count { it == true }
        return when (saved) {
            results.size -> context.resources.getQuantityString(R.plurals.attachments_saved, saved, saved)
            0 -> context.getString(R.string.could_not_save_files)
            else -> context.getString(R.string.tatrak_attachments_saved_partly, saved, results.size)
        }
    }
}
