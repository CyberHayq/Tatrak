package am.cyberhayq.chat.ui.media

import android.content.Context
import eu.siacs.conversations.R
import java.io.File

/**
 * Toast text after "Save"; [results] is `FileBackend.saveInternalToExternal`'s list, the written file or null. One file names
 * the file it was saved as: a taken name gets " (1)", which "saved" alone hid.
 */
object SaveResult {
    @JvmStatic
    fun message(context: Context, results: List<File?>): String {
        val saved = results.count { it != null }
        return when (saved) {
            results.size ->
                if (saved == 1) context.getString(R.string.tatrak_attachment_saved_as, results[0]!!.name)
                else context.resources.getQuantityString(R.plurals.attachments_saved, saved, saved)
            0 -> context.getString(R.string.could_not_save_files)
            else -> context.getString(R.string.tatrak_attachments_saved_partly, saved, results.size)
        }
    }
}
