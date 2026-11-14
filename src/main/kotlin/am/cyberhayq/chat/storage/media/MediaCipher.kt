package am.cyberhayq.chat.storage.media

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/** The four stores of encrypted media; [id] is the `store` field of the associated data. */
enum class MediaArea(val id: String) {
    ATTACHMENTS("attachments"), AVATARS("avatars"), WALLPAPERS("wallpapers"), STICKERS("stickers")
}

/** Why a file was refused; rules as numbered in the format specification. */
enum class MediaReject {
    NAME, TOO_SHORT, MAGIC, VERSION, FILE_ID, HEADER_LENGTH, LAST_SEGMENT_SIZE, TOO_LONG,
    /** Rule 5 at open: truncated, extended, or a wrong key, store or name. */
    LAST_SEGMENT_TAG,
    /** Rule 6 at a read: a changed, swapped or foreign segment. */
    SEGMENT_TAG,
    /** The file shrank after it was opened. */
    SHORT_READ,
    /** Writer only: more than 16 GiB of plaintext. */
    TOO_LARGE,
    /** Store: a file in a store folder that does not have an encrypted file's name. */
    NOT_ENCRYPTED,
    /** Store: the database holds no key for the file's generation, or none of its own to write with. */
    NO_KEY,
}

/** A file that is not valid encrypted media; never a reason to read it as plain. */
class MediaRejectedException(val reason: MediaReject, cause: Throwable? = null) : IOException("Encrypted media refused: $reason", cause)

/**
 * Encrypted media file format v1: Tink's AES-GCM-HKDF streaming AEAD behind a 25-byte Tatrak prefix.
 * Every rule here is from `knowledge/egm/attachment-encryption/format-spec.md`; no I/O policy.
 */
object MediaCipher {
    const val SEGMENT = 65536           // C, ciphertext segment
    const val TAG = 16                  // T
    const val PREFIX = 25               // F: magic, version, fileId
    const val HEADER = 40               // H: length byte, salt, nonce prefix
    const val DATA_OFFSET = PREFIX + HEADER             // D = 65
    const val PLAIN_SEGMENT = SEGMENT - TAG             // P = 65 520
    const val FIRST_PLAIN_SEGMENT = PLAIN_SEGMENT - DATA_OFFSET // P0 = 65 455
    const val MAX_PLAINTEXT = 1L shl 34                 // 16 GiB
    const val FILE_ID_SIZE = 16
    const val KEY_SIZE = 32
    private const val VERSION: Byte = 1
    private const val MAX_NAME = 1024
    private val MAGIC = "TATRKENC".toByteArray(Charsets.US_ASCII)
    private val DOMAIN = "tatrak-media".toByteArray(Charsets.US_ASCII)
    private val NAME = Regex("[0-9a-z._-]+(/[0-9a-z._-]+)*")

    /** Whether [name] is a valid name in [area]: empty for attachments, else `[0-9a-z._-]` components. */
    @JvmStatic fun validName(area: MediaArea, name: String): Boolean =
        if (area == MediaArea.ATTACHMENTS) name.isEmpty()
        else name.length <= MAX_NAME && NAME.matches(name) && name.split('/').none { it == "." || it == ".." }

    /** The associated data that binds a file to its id, store and name; an invalid name is refused, never cut. */
    @JvmStatic fun associatedData(fileId: ByteArray, area: MediaArea, name: String): ByteArray {
        require(fileId.size == FILE_ID_SIZE) { "fileId must be 16 bytes" }
        if (!validName(area, name)) throw MediaRejectedException(MediaReject.NAME)
        val store = area.id.toByteArray(Charsets.US_ASCII)
        val bytes = name.toByteArray(Charsets.US_ASCII) // one byte per character: the alphabet is ASCII
        return ByteBuffer.allocate(1 + DOMAIN.size + 1 + FILE_ID_SIZE + 1 + store.size + 2 + bytes.size)
            .put(DOMAIN.size.toByte()).put(DOMAIN).put(VERSION).put(fileId)
            .put(store.size.toByte()).put(store).putShort(bytes.size.toShort()).put(bytes).array()
    }

    /** File size for [length] plaintext bytes. */
    @JvmStatic fun ciphertextSize(length: Long): Long {
        require(length in 0..MAX_PLAINTEXT)
        return DATA_OFFSET + length + TAG.toLong() * segmentCount(length)
    }

    private fun segmentCount(length: Long): Long =
        if (length <= FIRST_PLAIN_SEGMENT) 1 else 1 + (length - FIRST_PLAIN_SEGMENT + PLAIN_SEGMENT - 1) / PLAIN_SEGMENT

    /** Plaintext length of a file of [size] bytes by reader rules 1 and 3; not authenticated until the file is opened. */
    @JvmStatic fun plaintextLength(size: Long): Long {
        if (size < DATA_OFFSET + TAG) throw MediaRejectedException(MediaReject.TOO_SHORT)
        val remainder = size % SEGMENT
        val segments = size / SEGMENT + if (remainder == 0L) 0 else 1
        if (segments > 1 && remainder in 1..TAG.toLong()) throw MediaRejectedException(MediaReject.LAST_SEGMENT_SIZE)
        val length = size - DATA_OFFSET - TAG * segments
        if (length > MAX_PLAINTEXT) throw MediaRejectedException(MediaReject.TOO_LONG)
        return length
    }

    /**
     * Encrypts into [out], which gets the prefix and header at once. [fileId] is the caller's (for
     * attachments the one in the file name). A failed or abandoned write leaves a file to delete.
     */
    @JvmStatic fun encrypt(masterKey: ByteArray, fileId: ByteArray, area: MediaArea, name: String, out: OutputStream): SegmentWriter =
        SegmentWriter(masterKey, fileId, area, name, out, SecureRandom(), MAX_PLAINTEXT)

    /**
     * Opens [channel] as the file of [area] and [name]; for attachments [expectedFileId] is the id
     * in the file name. Throws [MediaRejectedException] unless every open-time rule passes.
     */
    @JvmStatic fun open(masterKey: ByteArray, area: MediaArea, name: String, expectedFileId: ByteArray?, channel: FileChannel): SegmentReader =
        SegmentReader(masterKey, area, name, expectedFileId, channel)

    private fun subkey(masterKey: ByteArray, salt: ByteArray, associatedData: ByteArray): SecretKeySpec {
        require(masterKey.size == KEY_SIZE) { "master key must be 32 bytes" }
        val key = ByteArray(KEY_SIZE)
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(masterKey, salt, associatedData)) }.generateBytes(key, 0, KEY_SIZE)
        return SecretKeySpec(key, "AES").also { key.fill(0) }
    }

    private fun nonce(prefix: ByteArray, segment: Int, last: Boolean) =
        GCMParameterSpec(TAG * 8, ByteBuffer.allocate(12).put(prefix).putInt(segment).put((if (last) 1 else 0).toByte()).array())

    /**
     * Not thread-safe. [close] writes the last segment, which makes the file valid: to abandon a
     * write, close the underlying stream instead and delete the file.
     */
    class SegmentWriter internal constructor(
        masterKey: ByteArray, fileId: ByteArray, area: MediaArea, name: String,
        private val out: OutputStream,
        random: SecureRandom, // injected only by the known-answer test: a fixed salt reused under one AD breaks GCM
        private val limit: Long,
    ) : OutputStream() {
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val key: SecretKeySpec
        private val noncePrefix = ByteArray(7)
        private val buffer = ByteArray(PLAIN_SEGMENT)
        private var capacity = FIRST_PLAIN_SEGMENT
        private var filled = 0
        private var segment = 0
        private var written = 0L
        private var failed = false
        private var finished = false

        init {
            val associatedData = associatedData(fileId, area, name)
            val salt = ByteArray(KEY_SIZE)
            random.nextBytes(salt)
            random.nextBytes(noncePrefix)
            key = subkey(masterKey, salt, associatedData)
            out.write(ByteBuffer.allocate(DATA_OFFSET).put(MAGIC).put(VERSION).put(fileId).put(HEADER.toByte()).put(salt).put(noncePrefix).array())
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
            if (failed || finished) throw IOException("Encrypted media writer is closed")
            if (written + len > limit) { failed = true; throw MediaRejectedException(MediaReject.TOO_LARGE) }
            var offset = off
            var remaining = len
            // A segment is "not last" only once more plaintext than fits has arrived (Tink's rule),
            // so a full buffer waits: it may still be the last segment.
            while (remaining > capacity - filled) {
                val part = capacity - filled
                System.arraycopy(b, offset, buffer, filled, part)
                filled += part; offset += part; remaining -= part
                emit(last = false)
            }
            System.arraycopy(b, offset, buffer, filled, remaining)
            filled += remaining
            written += len
        }

        /** Flushes what is already encrypted; never encrypts a partial segment. */
        override fun flush() = out.flush()

        /** Writes the last segment and flushes, leaving [out] open so the caller can sync it. */
        fun finish() {
            if (finished) return
            if (failed) throw IOException("Encrypted media writer failed")
            emit(last = true)
            finished = true
            buffer.fill(0)
            out.flush()
        }

        override fun close() {
            try { if (!failed) finish() } finally { out.close() }
        }

        private fun emit(last: Boolean) {
            try {
                // one init and one doFinal per segment: a nonce is never used twice
                cipher.init(Cipher.ENCRYPT_MODE, key, nonce(noncePrefix, segment, last))
                out.write(cipher.doFinal(buffer, 0, filled))
            } catch (e: GeneralSecurityException) {
                failed = true; throw IOException(e)
            } catch (e: IOException) {
                failed = true; throw e // the segment may be half written: never encrypt this index again
            }
            segment++
            filled = 0
            capacity = PLAIN_SEGMENT
        }
    }

    /** A verified open file. [segment] is thread-safe; the returned arrays must not be changed. */
    class SegmentReader internal constructor(masterKey: ByteArray, area: MediaArea, name: String, expectedFileId: ByteArray?, private val channel: FileChannel) {
        /** Plaintext length, authenticated by the last segment's tag. */
        val length: Long
        val segments: Int
        private val size: Long
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val key: SecretKeySpec
        private val noncePrefix = ByteArray(7)
        private val lastSegment: ByteArray

        init {
            if (!validName(area, name)) throw MediaRejectedException(MediaReject.NAME)       // rule 0
            require(area != MediaArea.ATTACHMENTS || expectedFileId?.size == FILE_ID_SIZE) { "an attachment needs the fileId of its name" }
            size = channel.size()
            if (size < DATA_OFFSET + TAG) throw MediaRejectedException(MediaReject.TOO_SHORT) // rule 1
            val head = ByteBuffer.wrap(read(0, DATA_OFFSET))                                  // rule 2
            val magic = ByteArray(MAGIC.size).also { head.get(it) }
            if (!magic.contentEquals(MAGIC)) throw MediaRejectedException(MediaReject.MAGIC)
            if (head.get() != VERSION) throw MediaRejectedException(MediaReject.VERSION)
            val fileId = ByteArray(FILE_ID_SIZE).also { head.get(it) }
            if (area == MediaArea.ATTACHMENTS && !fileId.contentEquals(expectedFileId)) throw MediaRejectedException(MediaReject.FILE_ID)
            if (head.get() != HEADER.toByte()) throw MediaRejectedException(MediaReject.HEADER_LENGTH)
            val salt = ByteArray(KEY_SIZE).also { head.get(it) }
            head.get(noncePrefix)
            length = plaintextLength(size)                                                    // rule 3
            segments = ((size + SEGMENT - 1) / SEGMENT).toInt() // fits: length <= 16 GiB
            key = subkey(masterKey, salt, associatedData(fileId, area, name))                 // rule 4
            lastSegment = try { decrypt(segments - 1) }                                       // rule 5
            catch (e: MediaRejectedException) { throw if (e.reason == MediaReject.SEGMENT_TAG) MediaRejectedException(MediaReject.LAST_SEGMENT_TAG, e) else e }
        }

        /** Index of the segment that holds plaintext byte [position], for `0 <= position < length`. */
        fun segmentOf(position: Long): Int {
            require(position in 0 until length)
            return if (position < FIRST_PLAIN_SEGMENT) 0 else (1 + (position - FIRST_PLAIN_SEGMENT) / PLAIN_SEGMENT).toInt()
        }

        /** Plaintext offset of the first byte of segment [index]. */
        fun segmentStart(index: Int): Long = if (index == 0) 0 else FIRST_PLAIN_SEGMENT + (index - 1L) * PLAIN_SEGMENT

        /** The whole plaintext of segment [index], returned only after its tag passed (rule 6). */
        @Synchronized fun segment(index: Int): ByteArray {
            if (index !in 0 until segments) throw IndexOutOfBoundsException("segment $index of $segments")
            return if (index == segments - 1) lastSegment else decrypt(index)
        }

        private fun decrypt(index: Int): ByteArray {
            val start = if (index == 0) DATA_OFFSET.toLong() else index.toLong() * SEGMENT
            val end = minOf((index + 1L) * SEGMENT, size)
            val ciphertext = read(start, (end - start).toInt())
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, nonce(noncePrefix, index, index == segments - 1))
                return cipher.doFinal(ciphertext) // array-returning: Conscrypt needs spare room in a caller's buffer
            } catch (e: GeneralSecurityException) {
                throw MediaRejectedException(MediaReject.SEGMENT_TAG, e) // an error, never end of data
            }
        }

        private fun read(position: Long, count: Int): ByteArray {
            val buffer = ByteBuffer.allocate(count)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, position + buffer.position()) < 0) throw MediaRejectedException(MediaReject.SHORT_READ)
            }
            return buffer.array()
        }
    }
}
