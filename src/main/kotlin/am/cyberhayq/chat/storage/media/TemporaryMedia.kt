package am.cyberhayq.chat.storage.media

import android.content.Context
import android.net.Uri
import android.util.Log
import eu.siacs.conversations.Config
import eu.siacs.conversations.persistance.FileBackend
import eu.siacs.conversations.ui.util.Attachment
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The plain files the camera and the voice file fallback leave in the cache (design "Temporary
 * plaintext"), and the camera drafts encrypted from them.
 */
object TemporaryMedia {
    // FileBackend.Cache's folders
    private const val CAMERA = "Camera"
    private const val RECORDINGS = "Recordings"
    private val ABANDONED = TimeUnit.HOURS.toMillis(24)

    /** Where the HEIF and AVIF encoders write the own avatar, which they need a file for; deleted after each encode. */
    @JvmStatic fun avatarDirectory(context: Context) = File(context.cacheDir, "tatrak_avatar_tmp")

    /**
     * Where the image cropper writes a picked avatar (it would otherwise leave a plain PNG in
     * `cache/` for good): a file in the encoder folder, deleted by [deleteCrop] and emptied at
     * start. A FileProvider URI: the cropper refuses `file://` output.
     */
    @JvmStatic
    fun cropOutput(context: Context): Uri =
        FileBackend.getUriForFile(context, File(avatarDirectory(context).apply { mkdirs() }, "crop-${UUID.randomUUID()}.png"))

    /** Deletes a cropped avatar the screen is done with; only a file of [cropOutput]. */
    @JvmStatic
    fun deleteCrop(context: Context, uri: Uri?) {
        // the FileProvider's authority, as cropOutput's URIs carry it
        val authority = FileBackend.getUriForFile(context, File(avatarDirectory(context), "crop")).authority
        if (uri == null || uri.scheme != "content" || uri.authority != authority) return
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "tatrak_avatar_tmp" || !CROP.matches(segments[1])) return
        File(avatarDirectory(context), segments[1]).delete()
    }

    private val CROP = Regex("crop-[0-9a-f-]{36}\\.png")

    /**
     * At start: no recording or avatar encode runs before the process does, so `cache/Recordings`
     * and [avatarDirectory] are emptied; a capture in `cache/Camera` younger than 24 hours may still
     * be awaited by a chat restored after a process death, so only older ones go.
     */
    @JvmStatic @JvmOverloads
    fun deleteLeftovers(context: Context, now: Long = System.currentTimeMillis()) {
        File(context.cacheDir, RECORDINGS).listFiles()?.forEach { it.delete() }
        avatarDirectory(context).listFiles()?.forEach { it.delete() }
        File(context.cacheDir, CAMERA).listFiles()?.forEach { if (now - it.lastModified() > ABANDONED) it.delete() }
    }

    /** Encrypts [plain] into a new attachment of the store, under its extension; the plain file stays. */
    @Throws(IOException::class)
    internal fun copyIntoStore(store: EncryptedMediaStore, plain: File): File {
        val writer = store.createAttachment(store.attachmentFile(plain.name))
        try {
            plain.inputStream().use { it.copyTo(writer.outputStream) }
            return writer.commit()
        } finally {
            writer.abort() // nothing after a commit
        }
    }

    /**
     * Encrypts the camera's capture [plain] into the store, then deletes it. The store file, or null
     * when that failed: the plain file then stays as the draft, the send encrypts it and the 24-hour
     * cleanup bounds it.
     */
    @JvmStatic
    fun encryptCapture(context: Context, plain: File): File? {
        val stored =
            try {
                copyIntoStore(EncryptedMediaStore.get(context), plain)
            } catch (e: Exception) {
                Log.w(Config.LOGTAG, "camera: the capture stays plain until it is sent: ${e.javaClass.simpleName}")
                return null
            }
        plain.delete()
        return stored
    }

    /**
     * Deletes the store files of the camera drafts ([drafts], preview UUIDs) among the [dropped]
     * previews and forgets them. Only for previews discarded unsent: a draft sent without
     * compression is the message's own file.
     */
    @JvmStatic
    fun deleteDrafts(drafts: MutableSet<UUID>, dropped: Collection<Attachment>) {
        for (attachment in dropped) {
            if (drafts.remove(attachment.uuid)) attachment.uri.takeIf { it.scheme == "file" }?.path?.let { File(it).delete() }
        }
    }
}
