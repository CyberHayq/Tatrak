package am.cyberhayq.chat.storage.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import eu.siacs.conversations.R
import eu.siacs.conversations.utils.MimeUtils
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only access for other apps to the attachments of the media store ("Share", "Open with",
 * notification images): `content://<package>.media/<fileId>.<ext>`, decrypted on demand. Not
 * exported: a reader needs the per-URI grant of the intent that shares the file.
 */
class MediaProvider : ContentProvider() {

    override fun onCreate() = true

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        if (info.exported || !info.grantUriPermissions) throw SecurityException("MediaProvider must be private and grant per URI")
    }

    /** The stored file of [uri]; a URI that names none, or names more than a file, has no file. */
    private fun file(uri: Uri): File? =
        try { uri.pathSegments.singleOrNull()?.let { EncryptedMediaStore.get(context!!).sharedFile(it) } } catch (e: IOException) { null }

    private fun extension(uri: Uri) = uri.lastPathSegment.orEmpty().substringAfterLast('.', "")

    override fun getType(uri: Uri): String = MimeUtils.guessMimeTypeFromExtension(extension(uri)) ?: "application/octet-stream"

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val file = file(uri) ?: return null
        val values = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).mapNotNull { column ->
            when (column) {
                // a readable name: the stored one is a random id
                OpenableColumns.DISPLAY_NAME -> column to displayName(context!!, file)
                OpenableColumns.SIZE -> column to EncryptedMediaStore.get(context!!).length(file)
                else -> null
            }
        }
        return MatrixCursor(values.map { it.first }.toTypedArray(), 1).apply { addRow(values.map { it.second }) }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("MediaProvider is read-only")
        val file = file(uri) ?: throw FileNotFoundException("No such attachment")
        try {
            return EncryptedMediaStore.get(context!!).descriptor(file)
        } catch (e: IOException) {
            throw FileNotFoundException("Attachment unavailable") // also a file the store refuses
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("read-only")

    companion object {
        @JvmStatic fun authority(context: Context): String = context.packageName + ".media"

        /** A readable name for the stored [file] (app name, date, extension): the stored one is a random id with the generation. */
        @JvmStatic fun displayName(context: Context, file: File): String {
            val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(file.lastModified()))
            val extension = file.name.substringAfterLast('.', "")
            return context.getString(R.string.app_name) + "_" + date + if (extension.isEmpty()) "" else ".$extension"
        }

        /** The URI of the store's attachment [file]; null for a file the store does not hold (shared as before). */
        @JvmStatic fun uriFor(context: Context, file: File): Uri? =
            EncryptedMediaStore.get(context).sharedName(file)?.let {
                Uri.Builder().scheme("content").authority(authority(context)).appendPath(it).build()
            }
    }
}
