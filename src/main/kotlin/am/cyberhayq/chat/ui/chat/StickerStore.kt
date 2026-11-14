package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import am.cyberhayq.chat.storage.media.MediaArea
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import eu.siacs.conversations.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** [id] is the folder under `files/Stickers/<generation>`; empty for the user's own set. */
data class StickerPack(val id: String, val name: String, val files: List<File>)

/**
 * The user's stickers: webp files of at most [StickerMaxPx], the shape the receiving side
 * recognises (see [isSticker]). The own set lives in `files/Stickers/<generation>`, packs added
 * from a link in sub-folders, every file encrypted by the media store; the folders of every
 * generation whose key the database holds are shown. Packs use the Cheogram / monocles JSON
 * format, so their packs import here and ours import there.
 */
object StickerStore {

    private val packs = MutableStateFlow<List<StickerPack>>(emptyList())
    val stickers: StateFlow<List<StickerPack>> = packs.asStateFlow()

    /** Upload filename of a shared set; links ending in it are offered as sticker packs. */
    const val PACK_FILE = "tatrak-stickers.json"
    private const val NAME_FILE = "name.txt"
    const val MAX_PACK_SIZE = 120
    const val MAX_STICKER_BYTES = 1024 * 1024

    private fun directory(context: Context) = File(context.filesDir, "Stickers")

    /** The readable generation folders, the active one first. */
    private fun folders(context: Context) =
        EncryptedMediaStore.get(context).readableGenerations().map { File(directory(context), it) }.filter(::real)

    /** Our shared sets end in [PACK_FILE]; Cheogram serves its packs as JSON. */
    fun isPack(url: HttpUrl): Boolean =
        url.pathSegments.lastOrNull() == PACK_FILE ||
            (url.host == "stickers.cheogram.com" && url.encodedPath.endsWith(".json"))

    /** A message that is nothing but a pack link; shown as a card instead of the raw URL. */
    fun packLink(body: String?): HttpUrl? {
        val text = body?.trim() ?: return null
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        return text.toHttpUrlOrNull()?.takeIf(::isPack)
    }

    private fun webps(directory: File) =
        directory.listFiles { file -> file.isFile && file.extension == "webp" }.orEmpty().toList()

    /**
     * Names are content hashes: a sticker found in several generations is shown once, the active
     * one's; a pack's copies are merged file by file, so an empty or partial copy hides nothing.
     */
    suspend fun load(context: Context) =
        withContext(Dispatchers.IO) {
            val folders = folders(context)
            val own = folders.flatMap(::webps).distinctBy { it.name }.sortedByDescending { it.lastModified() }
            val added =
                folders.flatMap { it.listFiles { file -> file.isDirectory && real(file) }.orEmpty().toList() }
                    .groupBy { it.name }
                    .map { (id, copies) ->
                        val name =
                            copies.firstNotNullOfOrNull { copy ->
                                try {
                                    EncryptedMediaStore.get(context).inputStream(File(copy, NAME_FILE)).use { s -> String(s.readBytes()) }.trim()
                                        .takeUnless(String::isEmpty)
                                } catch (e: IOException) {
                                    null // no name or an unreadable one: the next copy's, else the folder's
                                }
                            }
                        val files = copies.flatMap(::webps).distinctBy { it.name }.sortedByDescending { f -> f.lastModified() }
                        copies.minOf { it.lastModified() } to StickerPack(id, name ?: id, files)
                    }
                    .sortedBy { it.first }
                    .map { it.second }
            packs.value = listOf(StickerPack("", "", own)) + added.filter { it.files.isNotEmpty() }
        }

    /** Scales each picture into the own set; returns how many could be read. */
    suspend fun import(context: Context, uris: List<Uri>): Int =
        withContext(Dispatchers.IO) {
            val bytes = uris.mapNotNull { uri -> encode { context.contentResolver.openInputStream(uri) } }
            val stored = store(context, "", bytes)
            load(context)
            stored
        }

    /** Re-encodes downloaded images into a new pack folder named after [name]. */
    suspend fun addPack(context: Context, name: String, images: List<ByteArray>): Int =
        withContext(Dispatchers.IO) {
            val bytes = images.mapNotNull { image -> encode { image.inputStream() } }
            if (bytes.isEmpty()) return@withContext 0
            // the folder name never comes from the pack, only a hash of its content
            val digest = MessageDigest.getInstance("SHA-256").apply { bytes.forEach(::update) }.digest()
            val folder = "pack-" + hex(digest) + "/"
            val stored = store(context, folder, bytes)
            try {
                if (stored > 0) write(context, folder + NAME_FILE, name.take(64).toByteArray())
            } catch (e: IOException) {
                Log.d(Config.LOGTAG, "could not store a pack name", e) // the pack shows its folder name
            }
            load(context)
            stored
        }

    /**
     * Deletes the sticker in every readable generation folder, or another one's copy would come
     * back on the next load; only inside `files/Stickers/<generation>/`, never through a link.
     */
    suspend fun delete(context: Context, file: File) {
        val root = directory(context)
        val name =
            when (root) {
                file.parentFile?.parentFile -> file.name
                file.parentFile?.parentFile?.parentFile -> file.parentFile!!.name + "/" + file.name
                else -> null
            }
        withContext(Dispatchers.IO) {
            if (name != null) for (folder in folders(context)) File(folder, name).takeIf { real(it.parentFile!!) }?.delete()
        }
        packs.value = packs.value.map { it.copy(files = it.files - file) }
    }

    /** As [delete], for a whole pack: its files one by one, a link is removed as a link. */
    suspend fun deletePack(context: Context, pack: StickerPack) {
        if (pack.id.isEmpty()) return
        withContext(Dispatchers.IO) {
            for (folder in folders(context)) {
                val copy = File(folder, pack.id)
                if (real(folder) && real(copy)) copy.listFiles()?.forEach { it.delete() }
                copy.delete()
            }
        }
        packs.value = packs.value - pack
    }

    /**
     * True when [file] itself is not a link. Only its last part is checked: the path above it has
     * links of its own on a device (`/data/user/0` -> `/data/data`).
     */
    private fun real(file: File): Boolean =
        try { file.canonicalFile == File(checkNotNull(file.absoluteFile.parentFile).canonicalFile, file.name) } catch (e: IOException) { false }

    /** The pack JSON (Cheogram / monocles format) for stickers already uploaded to [urls]. */
    fun packJson(name: String, urls: List<String>): String =
        JSONArray(
                urls.mapIndexed { index, url ->
                    JSONObject()
                        .put("pack", name)
                        .put("name", "sticker${index + 1}")
                        .put("url", url)
                        .put("copyright", "")
                        // monocles reads this key unconditionally
                        .put("cids", JSONArray())
                }
            )
            .toString()

    /** Image URLs and the pack name from a pack JSON; null when it isn't one. */
    fun parsePack(json: String): Pair<String, List<String>>? =
        try {
            val array = JSONArray(json)
            var name = ""
            val urls = mutableListOf<String>()
            for (i in 0 until minOf(array.length(), MAX_PACK_SIZE)) {
                val entry = array.optJSONObject(i) ?: continue
                val url = entry.optString("url")
                if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                if (name.isEmpty()) name = entry.optString("pack").filter { !it.isISOControl() }.trim()
                urls += url
            }
            if (urls.isEmpty()) null else name to urls
        } catch (e: JSONException) {
            null
        }

    /** Writes each sticker as `[folder]<hash>.webp` through the media store; returns how many were written. */
    private fun store(context: Context, folder: String, bytes: List<ByteArray>): Int =
        // named by content, so the same picture twice keeps one file
        bytes.count { sticker ->
            try {
                write(context, folder + hash(sticker) + ".webp", sticker)
                true
            } catch (e: IOException) {
                Log.d(Config.LOGTAG, "could not store a sticker", e)
                false
            }
        }

    @Throws(IOException::class)
    private fun write(context: Context, name: String, bytes: ByteArray) {
        EncryptedMediaStore.get(context).create(MediaArea.STICKERS, name).run {
            try { outputStream.write(bytes); commit() } finally { abort() }
        }
    }

    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(digest: ByteArray) = digest.take(8).joinToString("") { "%02x".format(it) }

    private fun encode(open: () -> InputStream?): ByteArray? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= StickerMaxPx) sample *= 2
            val decoded =
                open()?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return null
            val scale = minOf(1f, StickerMaxPx.toFloat() / maxOf(decoded.width, decoded.height))
            val bitmap =
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    decoded
                }
            val format =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                }
            return ByteArrayOutputStream().use {
                bitmap.compress(format, 90, it)
                it.toByteArray()
            }
        } catch (e: IOException) {
            Log.d(Config.LOGTAG, "could not read sticker", e)
            return null
        } catch (e: SecurityException) {
            Log.d(Config.LOGTAG, "could not read sticker", e)
            return null
        }
    }
}
