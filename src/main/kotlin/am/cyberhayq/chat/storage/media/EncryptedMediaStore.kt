package am.cyberhayq.chat.storage.media

import am.cyberhayq.chat.storage.DatabaseStorage
import am.cyberhayq.chat.storage.Durable
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.annotation.VisibleForTesting
import eu.siacs.conversations.Config
import eu.siacs.conversations.persistance.DatabaseBackend
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.SecureRandom

/**
 * The only way to media at rest: attachments, avatars, photo wallpapers and sticker packs.
 * Owns the file names, the master keys in memory, publication and the temporary folder.
 *
 * Reading rule: a file in one of the store's folders is encrypted or unavailable, never read as
 * plain; it opens only with the key of the generation in its name (attachments) or folder
 * (wallpapers, stickers), avatars only with the database's own key. Any other file is plain.
 */
class EncryptedMediaStore internal constructor(
    filesDir: File, cacheDir: File, private val keySource: KeySource,
    private val context: Context? = null, // for proxy descriptors; unit tests have none
) {

    /** Where the keys come from: the active history database, or a fake in unit tests. */
    internal interface KeySource {
        /** Id of the active database generation; null while local storage is not ready. */
        fun generation(): String?
        fun read(): MediaKeyTable.Keys
    }

    private val files = filesDir.absoluteFile.normalize()
    private val attachments = File(files, "attachments")
    private val temporary = File(files, "tatrak_tmp")
    private val roots = mapOf(
        MediaArea.ATTACHMENTS to attachments,
        MediaArea.WALLPAPERS to File(files, "wallpapers"),
        MediaArea.STICKERS to File(files, "Stickers"),
        MediaArea.AVATARS to File(cacheDir.absoluteFile.normalize(), "avatars"),
    )
    // A path is classified by where it really is (symbolic links resolved), so an alias of a
    // store folder cannot make a store file look plain. Names handed out keep the caller's spelling.
    // The area folder is resolved on every use, so a folder that is itself a link is followed too.
    private fun realRoot(area: MediaArea) = roots.getValue(area).canonicalFile
    private val syncedDirectories = HashSet<File>()
    private val publishLock = Any()
    private val random = SecureRandom()
    @Volatile private var cached: MediaKeyTable.Keys? = null

    init {
        // Only this folder, and before any writer exists: what is in it was never published.
        // Never recursive and never through a link: writers make flat files here, and a link
        // (the folder itself or an entry) is removed as a link, whatever it points at.
        if (Files.isSymbolicLink(temporary.toPath())) temporary.delete()
        else temporary.listFiles()?.forEach { it.delete() }
    }

    // ---- names ---------------------------------------------------------------------------

    private class Stored(val area: MediaArea, val generation: String?, val name: String, val fileId: ByteArray?)

    /**
     * null: outside the store (plain). [NOT_STORED]: inside a store folder without a valid name,
     * or a link inside a store folder that leads to another store file or out of the store: the
     * name a reader asked for is what the file's key and associated data are bound to.
     */
    private fun place(file: File): Any? {
        val real = classify(file.canonicalFile, ::realRoot)
        val spelled = classify(file.absoluteFile.normalize()) { roots.getValue(it) } // the caller's spelling of the roots
        return if (spelled == null || same(spelled, real)) real else NOT_STORED
    }

    private fun same(a: Any?, b: Any?): Boolean =
        if (a is Stored && b is Stored) a.area == b.area && a.generation == b.generation && a.name == b.name &&
            (a.fileId?.contentEquals(b.fileId) ?: (b.fileId == null))
        else a === b

    private fun classify(path: File, root: (MediaArea) -> File): Any? {
        relative(path, root(MediaArea.ATTACHMENTS))?.let { name ->
            val match = ATTACHMENT.matchEntire(name) ?: return NOT_STORED
            return Stored(MediaArea.ATTACHMENTS, match.groupValues[1], "", unhex(match.groupValues[2]))
        }
        for (area in listOf(MediaArea.WALLPAPERS, MediaArea.STICKERS)) relative(path, root(area))?.let { inside ->
            val generation = inside.substringBefore('/')
            val name = inside.substringAfter('/', "")
            return if (GENERATION.matches(generation) && MediaCipher.validName(area, name)) Stored(area, generation, name, null) else NOT_STORED
        }
        relative(path, root(MediaArea.AVATARS))?.let { name ->
            return if (MediaCipher.validName(MediaArea.AVATARS, name)) Stored(MediaArea.AVATARS, null, name, null) else NOT_STORED
        }
        return null
    }

    private fun relative(path: File, root: File): String? =
        if (path != root && path.startsWith(root)) path.relativeTo(root).invariantSeparatorsPath else null

    /** True when [file] lies in one of the store's folders: it is encrypted or unavailable, never plain. */
    fun isEncrypted(file: File): Boolean = place(file) != null

    /**
     * The private file an attachment named [filename] is stored in: `tatrak_<generation>_<fileId>`
     * plus [filename]'s extension, with a new random `fileId`. A [filename] that already is such
     * a name of the active generation is kept (a file that only learned its extension).
     * Reserving never fails for lack of a key; writing does.
     */
    fun attachmentFile(filename: String): File {
        val generation = checkNotNull(keySource.generation()) { "Local storage is not ready" }
        // a name of this generation that only gains an extension keeps its id (OpenPGP's final name);
        // an extension the store does not accept is dropped, so the commit stays under the same name
        val stem = filename.substringBefore('.')
        if (ATTACHMENT.matchEntire(stem)?.groupValues?.get(1) == generation) return File(attachments, stem + extension(filename))
        return File(attachments, "tatrak_${generation}_${hex(newFileId())}${extension(filename)}")
    }

    private fun extension(filename: String) = filename.substringAfterLast('.', "").let { if (EXTENSION.matches(it)) ".$it" else "" }

    private fun newFileId() = ByteArray(MediaCipher.FILE_ID_SIZE).also(random::nextBytes)

    // ---- keys ----------------------------------------------------------------------------

    private fun keys(): MediaKeyTable.Keys {
        val generation = keySource.generation() ?: throw MediaRejectedException(MediaReject.NO_KEY) // storage not ready
        return cached ?: synchronized(this) {
            cached ?: keySource.read().also {
                if (it.own != generation) Log.w(Config.LOGTAG, "encrypted media: this database has no key of its own, nothing can be written")
                cached = it
            }
        }
    }

    /** The importer added keys to the active database: read them again on the next use. */
    @Synchronized fun keysChanged() { cached = null } // same lock as the load: a load in flight cannot put back old keys

    private fun ownKey(): Pair<String, ByteArray> {
        val keys = keys()
        val generation = keySource.generation()
        val key = keys.byGeneration[generation].takeIf { keys.own == generation } ?: throw MediaRejectedException(MediaReject.NO_KEY)
        return generation!! to key
    }

    // ---- writing -------------------------------------------------------------------------

    /**
     * [file], or for an attachment named for another generation (a message brought by a recovery
     * import) a new name of the active one with the same extension: only the active one is written.
     */
    fun writableTarget(file: File): File {
        // a path outside the store (an old shared-storage row) gets a new store name: nothing plain is written
        val place = place(file) as? Stored ?: return attachmentFile(file.name)
        return if (place.area != MediaArea.ATTACHMENTS || place.generation == keySource.generation()) file else attachmentFile(file.name)
    }

    /**
     * A writer for the attachment [target], a name from [attachmentFile]. Nothing appears under
     * that name until [MediaWriter.commit], and an existing file is never overwritten. Any other
     * target, a shared folder included, is refused: nothing plain is written.
     */
    @Throws(IOException::class)
    fun createAttachment(target: File): MediaWriter {
        val file = target.absoluteFile.normalize() // the path that is written is the path that is checked
        val place = place(file)
        if ((place as? Stored)?.area != MediaArea.ATTACHMENTS) throw MediaRejectedException(MediaReject.NOT_ENCRYPTED)
        val (generation, key) = ownKey()
        if (place.generation != generation) throw MediaRejectedException(MediaReject.NO_KEY) // only the own generation is written
        return writer(key, place.fileId!!, MediaArea.ATTACHMENTS, "", file, newTemporary(), replace = false)
    }

    /**
     * A writer for an avatar, wallpaper or sticker file called [name] inside its store (for a
     * sticker `pack-<hash>/<file>`). Committing replaces a file of the same name: names are
     * content hashes, or a pack's `name.txt`.
     */
    @Throws(IOException::class)
    fun create(area: MediaArea, name: String): MediaWriter {
        require(area != MediaArea.ATTACHMENTS) { "attachments are written with createAttachment" }
        if (!MediaCipher.validName(area, name)) throw MediaRejectedException(MediaReject.NAME)
        val (generation, key) = ownKey()
        val root = roots.getValue(area).let { if (area == MediaArea.AVATARS) it else File(it, generation) }
        return writer(key, newFileId(), area, name, File(root, name), newTemporary(), replace = true)
    }

    /**
     * A writer for an import: the file is made in [folder], which survives a restart (unlike the
     * temporary folder), under the final attachment name of [generation], encrypted with [key].
     * The importer moves the committed file into the attachment folder when the import is committed.
     */
    @Throws(IOException::class)
    fun createStaged(folder: File, generation: String, key: ByteArray, filename: String): MediaWriter {
        require(GENERATION.matches(generation))
        val fileId = newFileId()
        val target = File(folder, "tatrak_${generation}_${hex(fileId)}${extension(filename)}")
        return writer(key, fileId, MediaArea.ATTACHMENTS, "", target, File(folder, target.name + ".part"), replace = false)
    }

    private fun newTemporary() = File(temporary, hex(newFileId()) + ".part")

    private fun writer(key: ByteArray, fileId: ByteArray, area: MediaArea, name: String, target: File, temp: File, replace: Boolean): MediaWriter {
        ensureDirectory(checkNotNull(target.parentFile))
        ensureDirectory(checkNotNull(temp.parentFile))
        val file = FileOutputStream(temp)
        try {
            return MediaWriter(file, temp, target, replace, MediaCipher.encrypt(key, fileId, area, name, file), publishLock, ::intact)
        } catch (e: Throwable) {
            runCatching { file.close() }
            temp.delete()
            throw e
        }
    }

    /**
     * Makes [directory] and syncs its parent. A folder that merely exists may come from an attempt
     * whose parent sync failed, so the parent is synced once per process for every folder used.
     */
    private fun ensureDirectory(directory: File): Unit = synchronized(syncedDirectories) {
        if (directory in syncedDirectories && directory.isDirectory) return // a removed folder (a deleted pack, a cleared cache) is made again
        syncedDirectories -= directory // remembered again only after its parent sync succeeds
        val parent = checkNotNull(directory.parentFile)
        if (!directory.isDirectory) {
            ensureDirectory(parent)
            if (!directory.mkdir() && !directory.isDirectory) throw IOException("Cannot create a store folder") // no name: it can be a generation id
        }
        syncDirectory(parent)
        syncedDirectories += directory
    }

    // ---- reading -------------------------------------------------------------------------

    /**
     * Opens [file] by the reading rule. A missing file is a `FileNotFoundException`, as for a plain
     * file; a file that is there but must not or cannot be read is a [MediaRejectedException].
     */
    /** True when every segment of [file] passes its tag with the key and name the store reads it with. */
    internal fun intact(file: File): Boolean =
        try {
            open(file).use { opened -> opened.inputStream().use { s -> val buffer = ByteArray(64 * 1024); while (s.read(buffer) >= 0) Unit } }
            true
        }
        catch (e: IOException) { false }

    @Throws(IOException::class)
    fun open(file: File): MediaFile {
        val real = file.canonicalFile
        val place = place(file)
        val stream =
            try { FileInputStream(real) } // the file that was classified, not another spelling of it
            catch (e: java.io.FileNotFoundException) { throw java.io.FileNotFoundException("no such store file") } // its message is the path, with the generation
        try {
            val reader = when (place) {
                null -> null
                is Stored -> {
                    val key = if (place.generation == null) ownKey().second
                    else keys().byGeneration[place.generation] ?: throw MediaRejectedException(MediaReject.NO_KEY)
                    MediaCipher.open(key, place.area, place.name, place.fileId, stream.channel)
                }
                else -> throw MediaRejectedException(MediaReject.NOT_ENCRYPTED)
            }
            return MediaFile(stream, reader)
        } catch (e: Throwable) {
            runCatching { stream.close() }
            throw e
        }
    }

    /**
     * The generations whose key the database holds, the active one first: the wallpaper and
     * sticker folders (`<area>/<generation>/`) that can be read. Empty while storage is not ready.
     */
    fun readableGenerations(): List<String> {
        val keys = try { keys() } catch (e: MediaRejectedException) { return emptyList() }
        val active = keySource.generation()
        return keys.byGeneration.keys.sortedBy { it != active }
    }

    /**
     * Plaintext length for display: the file size for a plain file; for a store file computed
     * from its size without opening it (not authenticated; open the file for a length to rely on),
     * 0 when the size cannot be an encrypted file's.
     */
    fun length(file: File): Long = when (place(file)) {
        null -> file.length()
        is Stored -> try { MediaCipher.plaintextLength(file.length()) } catch (e: MediaRejectedException) { 0 }
        else -> 0
    }

    // ---- one-line replacements for the plain-file calls of the upstream readers --------------

    /** In place of `new FileInputStream(file)`. */
    @Throws(IOException::class)
    fun inputStream(file: File): InputStream = open(file).inputStream()

    /** In place of `setDataSource(path)` of a player or a metadata retriever, which closes it on release. */
    @Throws(IOException::class)
    fun dataSource(file: File): MediaDataSource = open(file).dataSource()

    /**
     * In place of `BitmapFactory.decodeFile`: null when the file is missing, unavailable or not an
     * image, and also when a segment failed its tag, so a damaged image is never shown in part.
     */
    fun decodeBitmap(file: File, options: BitmapFactory.Options?): Bitmap? =
        try { open(file).use { opened -> BitmapFactory.decodeStream(opened.inputStream(), null, options).takeUnless { opened.failed } } }
        catch (e: IOException) { null }

    /**
     * In place of `ParcelFileDescriptor.open(file, MODE_READ_ONLY)`, for readers that need a
     * seekable descriptor (`PdfRenderer`, another app): a plain file's own descriptor, or a proxy
     * that decrypts on demand. A proxy descriptor cannot be memory-mapped.
     */
    @Throws(IOException::class)
    fun descriptor(file: File): ParcelFileDescriptor {
        if (place(file) == null) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val opened = open(file)
        try {
            return checkNotNull(context).getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = opened.length
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int =
                        try { maxOf(0, opened.readAt(offset, data, 0, size)) } // 0 is the end of the file here
                        catch (e: IOException) { throw ErrnoException("read", OsConstants.EIO, e) } // a failed tag is an I/O error to the reader
                    override fun onRelease() = opened.close()
                },
                proxyHandler)
        } catch (e: Throwable) {
            opened.close()
            throw e
        }
    }

    // ---- sharing with other apps (MediaProvider) ---------------------------------------------

    /**
     * The name a `MediaProvider` URI carries for the attachment [file]: its `<fileId>.<ext>`,
     * never the generation, which would tell every app a file is shared with which install this
     * is. Null when [file] is not an attachment of the store.
     */
    fun sharedName(file: File): String? {
        val name = try { relative(file.canonicalFile, realRoot(MediaArea.ATTACHMENTS)) } catch (e: IOException) { null } ?: return null
        return if (ATTACHMENT.matches(name)) name.substring(SHARED_AT) else null
    }

    /**
     * The attachment a `MediaProvider` URI's [name] stands for, looked up in every generation
     * whose key the database holds; null when there is none. The name cannot lead out of the folder.
     */
    @Throws(IOException::class)
    fun sharedFile(name: String): File? {
        val root = realRoot(MediaArea.ATTACHMENTS)
        // a link under an attachment's name must not lead to another file
        fun inside(stored: String) = File(root, stored).takeIf { it.isFile && relative(it.canonicalFile, root) == stored }
        if (SHARED.matches(name)) {
            for (generation in keys().byGeneration.keys) inside("tatrak_${generation}_$name")?.let { return it }
        }
        return null
    }

    // one background thread serves every proxy descriptor's reads
    private val proxyHandler by lazy { Handler(HandlerThread("tatrak-media-proxy").apply { start() }.looper) }

    companion object {
        private val NOT_STORED = Any()
        private val GENERATION = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val ATTACHMENT = Regex("tatrak_(${GENERATION.pattern})_([0-9a-f]{32})(?:\\.[A-Za-z0-9]{1,10})?")
        private val EXTENSION = Regex("[A-Za-z0-9]{1,10}")
        private val SHARED = Regex("[0-9a-f]{32}(?:\\.[A-Za-z0-9]{1,10})?")
        private const val SHARED_AT = 7 + 36 + 1 // "tatrak_", the generation, "_"
        @Volatile private var instance: EncryptedMediaStore? = null

        // made again when the files folder is another one: Robolectric gives every test its own
        @JvmStatic fun get(context: Context): EncryptedMediaStore = current(context) ?: synchronized(this) {
            current(context) ?: context.applicationContext.let { app ->
                EncryptedMediaStore(app.filesDir, app.cacheDir, object : KeySource {
                    override fun generation() = DatabaseStorage.activeGenerationId()
                    override fun read() = MediaKeyTable.read(DatabaseBackend.getInstance(app).readableDatabase)
                }, app)
            }.also { instance = it }
        }

        private fun current(context: Context) = instance?.takeIf { it.files == context.filesDir.absoluteFile.normalize() }

        /** For code without a `Context`; the store is made when the process starts (`DatabaseStorage.start`). */
        @JvmStatic fun get(): EncryptedMediaStore = checkNotNull(instance) { "The media store is not made yet" }

        /** JVM tests of upstream code that reads through [get]: a store with a fake key source in its place. */
        @VisibleForTesting @JvmStatic fun install(store: EncryptedMediaStore?) { instance = store }

        /** The generation in an attachment's file [name]; null for any other name, which no key can open. */
        @JvmStatic fun generationOf(name: String): String? = ATTACHMENT.matchEntire(name)?.groupValues?.get(1)

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        internal fun syncDirectory(directory: File) {
            try { Durable.syncDirectory(directory) } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException(e) }
        }
    }
}

/**
 * One file being written. Plaintext goes into [outputStream]; nothing is published until
 * [commit], and [abort] leaves no trace. Not thread-safe.
 */
class MediaWriter internal constructor(
    private val file: FileOutputStream, private val temp: File,
    /** Where [commit] publishes the file, unless it is given a name that only adds an extension. */
    val target: File,
    private val replace: Boolean,
    private val segments: MediaCipher.SegmentWriter,
    private val publishLock: Any,
    private val intact: (File) -> Boolean,
) {
    private enum class State { OPEN, COMMITTED, ABORTED }
    private var state = State.OPEN

    /** Closing this stream neither completes nor publishes the file: only [commit] does. */
    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = segments.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = segments.write(b, off, len)
        override fun flush() = segments.flush()
        override fun close() {}
    }

    /**
     * Completes the file, syncs it and publishes it under its name by a rename, then syncs the
     * folder. On an error before the rename nothing is published and the temporary file is gone.
     * An error after the rename (the folder sync) is reported too, but the complete file stays.
     *
     * [finalTarget] may add an extension to [target]'s name (an OpenPGP file whose type is known
     * only after decryption): the `fileId` the name and the header carry stays the same.
     * With [keepExisting] an attachment already published under that name stays and this copy of
     * it is dropped: OpenPGP decrypts a file again, under the same name, when the process died
     * between the commit and the update of its message.
     */
    @JvmOverloads @Throws(IOException::class)
    fun commit(finalTarget: File = target, keepExisting: Boolean = false): File {
        check(state == State.OPEN) { "The writer is already $state" }
        // refused as a file error, not a programming error: OpenPGP's final name depends on the
        // sender's file name
        // only an attachment's name may gain an extension, and only to a name the store opens again
        if (finalTarget != target &&
            !(finalTarget.parentFile == target.parentFile && finalTarget.name.startsWith(target.name + ".") &&
                EncryptedMediaStore.generationOf(target.name.substringBefore('.')) != null &&
                EncryptedMediaStore.generationOf(finalTarget.name) != null)) {
            throw MediaRejectedException(MediaReject.NAME)
        }
        try {
            segments.finish()
            Durable.sync(file)
            file.close()
            synchronized(publishLock) {
                // attachments have random names: an existing one is never overwritten
                // a kept file must be whole: a damaged one is replaced by this verified copy
                if (replace || !finalTarget.exists() || (keepExisting && !intact(finalTarget))) Durable.rename(temp, finalTarget)
                else if (keepExisting) temp.delete()
                else throw FileAlreadyExistsException("an attachment of that name exists") // not the name: it carries the generation
            }
        } catch (e: Throwable) {
            abort()
            throw e
        }
        state = State.COMMITTED
        EncryptedMediaStore.syncDirectory(checkNotNull(finalTarget.parentFile))
        return finalTarget
    }

    /** Drops the file; after [commit] it does nothing. */
    fun abort() {
        if (state != State.OPEN) return
        state = State.ABORTED
        runCatching { file.close() }
        temp.delete()
    }
}

/**
 * An open file of the store, or a plain file outside it. Every byte handed out of an encrypted
 * file has passed its segment's tag; a failed tag is a [MediaRejectedException], never an end of data.
 * Thread-safe.
 */
class MediaFile internal constructor(private val stream: FileInputStream, private val reader: MediaCipher.SegmentReader?) : Closeable {
    /** Plaintext length; for an encrypted file authenticated when it was opened. */
    val length: Long = reader?.length ?: stream.channel.size()

    /**
     * True once a read failed its tag. A player asks this when playback ends or fails: the
     * platform may report damaged media as a normal end.
     */
    @Volatile var failed = false
        private set

    private var closed = false
    private val recent = arrayOfNulls<Pair<Int, ByteArray>>(2) // verified segments, newest first; the last one is kept by the reader

    /** Reads up to [size] bytes at [position]; -1 at or after the end. */
    @Synchronized @Throws(IOException::class)
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (offset < 0 || size < 0 || size > buffer.size - offset) throw IndexOutOfBoundsException()
        if (position < 0) throw IOException("negative position")
        if (closed) throw IOException("closed")
        if (size == 0) return 0
        var done = 0
        while (done < size && position + done < length) {
            val at = position + done
            done += if (reader == null) {
                val count = stream.channel.read(ByteBuffer.wrap(buffer, offset + done, size - done), at)
                if (count < 0) break
                count
            } else {
                val index = reader.segmentOf(at)
                val segment = segment(index)
                val inSegment = (at - reader.segmentStart(index)).toInt()
                minOf(size - done, segment.size - inSegment).also { System.arraycopy(segment, inSegment, buffer, offset + done, it) }
            }
        }
        return if (done == 0) -1 else done
    }

    private fun segment(index: Int): ByteArray {
        val reader = reader!!
        if (index == reader.segments - 1) return reader.segment(index)
        recent.firstOrNull { it?.first == index }?.let { hit ->
            if (recent[0] !== hit) { recent[1] = recent[0]; recent[0] = hit }
            return hit.second
        }
        // only a segment whose tag passed gets here or into the cache
        val data = try { reader.segment(index) } catch (e: MediaRejectedException) { failed = true; throw e }
        recent[1] = recent[0]
        recent[0] = index to data
        return data
    }

    /** A stream from the first byte; closing it closes this file. */
    fun inputStream(): InputStream = object : InputStream() {
        private var position = 0L
        private val one = ByteArray(1)
        override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        override fun read(b: ByteArray, off: Int, len: Int): Int = readAt(position, b, off, len).also { if (it > 0) position += it }
        override fun skip(n: Long): Long = n.coerceIn(0, maxOf(0, length - position)).also { position += it }
        override fun available(): Int = (length - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        override fun close() = this@MediaFile.close()
    }

    /** For `MediaPlayer` and `MediaMetadataRetriever`; closing it closes this file. */
    fun dataSource(): MediaDataSource = object : MediaDataSource() {
        override fun getSize(): Long = length
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = this@MediaFile.readAt(position, buffer, offset, size)
        override fun close() = this@MediaFile.close()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        stream.close()
    }
}
