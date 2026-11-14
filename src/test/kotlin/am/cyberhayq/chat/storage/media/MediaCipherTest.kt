package am.cyberhayq.chat.storage.media

import am.cyberhayq.chat.storage.media.MediaCipher.FIRST_PLAIN_SEGMENT as P0
import am.cyberhayq.chat.storage.media.MediaCipher.PLAIN_SEGMENT as P
import am.cyberhayq.chat.storage.media.MediaCipher.SEGMENT as C
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The format specification's test plan: Tink-made vectors and the known answers of
 * `tools/media-vectors`, negative cases derived here from valid files, writer rules, sizes.
 */
class MediaCipherTest {
    @get:Rule val temporary = TemporaryFolder()

    private val key = ByteArray(32) { (it * 7 + 3).toByte() } // test key
    private val attachmentId = ByteArray(16) { (0x10 + it).toByte() }
    private val otherId = ByteArray(16) { (0x70 + it).toByte() }
    private val stickerName = "pack-ab12cd34ef567890/1a2b3c4d5e6f7081.webp"

    // ---- helpers -------------------------------------------------------------------------

    private class Vector(val json: JsonObject, val bytes: ByteArray) {
        fun text(field: String): String = json[field].asString
        val file get() = text("file")
        val area get() = MediaArea.values().single { it.id == text("store") }
        val name get() = text("name")
        val fileId get() = unhex(text("fileId"))
        val masterKey get() = unhex(text("masterKey"))
        val length get() = json["length"].asInt
    }

    private fun resource(name: String) = File(checkNotNull(javaClass.getResource("/media-vectors/$name")) { name }.toURI())

    private fun vectors(index: String): List<Vector> =
        JsonParser.parseString(resource(index).readText()).asJsonArray.map { it.asJsonObject }
            .map { Vector(it, resource(it["file"].asString).readBytes()) }

    private fun allVectors() = vectors("kat.json") + vectors("tink.json")

    /** Opens [bytes] as a file and runs [block]; the open-time rules run before [block]. */
    private fun <T> opened(
        bytes: ByteArray, area: MediaArea = MediaArea.ATTACHMENTS, name: String = "",
        fileId: ByteArray? = attachmentId, masterKey: ByteArray = key, block: (MediaCipher.SegmentReader) -> T,
    ): T {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        return FileChannel.open(file.toPath()).use { block(MediaCipher.open(masterKey, area, name, if (area == MediaArea.ATTACHMENTS) fileId else null, it)) }
    }

    private fun plaintext(reader: MediaCipher.SegmentReader): ByteArray =
        ByteArrayOutputStream().also { out -> repeat(reader.segments) { out.write(reader.segment(it)) } }.toByteArray()

    /** The rule that refuses [bytes] at open, or null when it opens. */
    private fun openRefusal(
        bytes: ByteArray, area: MediaArea = MediaArea.ATTACHMENTS, name: String = "",
        fileId: ByteArray? = attachmentId, masterKey: ByteArray = key,
    ): MediaReject? = try { opened(bytes, area, name, fileId, masterKey) { null } } catch (e: MediaRejectedException) { e.reason }

    /** The rule that refuses reading segment [index] of a file that opens. */
    private fun readRefusal(bytes: ByteArray, index: Int): MediaReject? =
        opened(bytes) { reader -> try { reader.segment(index); null } catch (e: MediaRejectedException) { e.reason } }

    private fun encrypt(
        length: Int, area: MediaArea = MediaArea.ATTACHMENTS, name: String = "", fileId: ByteArray = attachmentId,
        random: SecureRandom = SecureRandom(), write: (OutputStream, ByteArray) -> Unit = { out, data -> out.write(data) },
    ): ByteArray {
        val out = ByteArrayOutputStream()
        MediaCipher.SegmentWriter(key, fileId, area, name, out, random, MediaCipher.MAX_PLAINTEXT).use { write(it, pattern(length)) }
        return out.toByteArray()
    }

    /** A random source that hands out [bytes] in order: the salt, then the nonce prefix. */
    private class Fixed(private val bytes: ByteArray) : SecureRandom() {
        private var used = 0
        override fun nextBytes(target: ByteArray) {
            System.arraycopy(bytes, used, target, 0, target.size)
            used += target.size
        }
    }

    private fun flip(bytes: ByteArray, offset: Int) = bytes.clone().also { it[offset] = (it[offset].toInt() xor 1).toByte() }

    /** Ciphertext bytes of segment [index] of a file. */
    private fun segmentRange(bytes: ByteArray, index: Int) = (if (index == 0) 65 else index * C) until minOf((index + 1) * C, bytes.size)

    // An independent encryption on plain javax.crypto, for files the writer refuses to make.
    private fun hkdf(masterKey: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(masterKey)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        return mac.doFinal(byteArrayOf(1))
    }

    private fun seal(subkey: ByteArray, noncePrefix: ByteArray, index: Int, last: Boolean, data: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).put(noncePrefix).putInt(index).put((if (last) 1 else 0).toByte()).array()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(subkey, "AES"), GCMParameterSpec(128, nonce))
            doFinal(data)
        }
    }

    private val craftSalt = ByteArray(32) { (0x21 + it).toByte() }
    private val craftPrefix = ByteArray(7) { (0x51 + it).toByte() }

    private fun craftHeader(version: Int = 1) =
        "TATRKENC".toByteArray() + version.toByte() + attachmentId + 40.toByte() + craftSalt + craftPrefix

    private fun craftKey(version: Int = 1) = hkdf(
        key, craftSalt,
        // byte 13 of the associated data is the version: length byte, 12 bytes of domain, version
        MediaCipher.associatedData(attachmentId, MediaArea.ATTACHMENTS, "").also { it[13] = version.toByte() },
    )

    private fun pattern(length: Int) = ByteArray(length) { (it % 251).toByte() }

    private fun assertRefused(expected: MediaReject, block: () -> Unit) {
        try { block(); fail("expected $expected") } catch (e: MediaRejectedException) { assertEquals(expected, e.reason) }
    }

    // ---- associated data and sizes -------------------------------------------------------

    @Test fun associatedDataMatchesTheSpecification() {
        val id = ByteArray(16) { it.toByte() }
        val start = "0c74617472616b2d6d6564696101000102030405060708090a0b0c0d0e0f"
        assertEquals(start + "0b6174746163686d656e74730000", hex(MediaCipher.associatedData(id, MediaArea.ATTACHMENTS, "")))
        assertEquals(
            start + "08737469636b6572730011" + "7061636b2d616231322f73742e77656270",
            hex(MediaCipher.associatedData(id, MediaArea.STICKERS, "pack-ab12/st.webp")),
        )
        // the shortest name whose length needs the high byte
        assertEquals(start + "08737469636b657273" + "0100" + "61".repeat(256), hex(MediaCipher.associatedData(id, MediaArea.STICKERS, "a".repeat(256))))
        assertEquals(39 + 2 + 1024, MediaCipher.associatedData(id, MediaArea.STICKERS, "a".repeat(1024)).size) // 39 bytes, the length, the name
        for (bad in listOf("", "a".repeat(1025), "A.jpg", "ünï/кот", "a//b", "/a", "a/", "../a", "a/./b", "a b", "a\u0000")) {
            assertRefused(MediaReject.NAME) { MediaCipher.associatedData(id, MediaArea.STICKERS, bad) }
        }
        assertRefused(MediaReject.NAME) { MediaCipher.associatedData(id, MediaArea.ATTACHMENTS, "x") }
    }

    @Test fun sizeArithmeticOfTheSpecification() {
        val table = mapOf(
            0L to 81L, 1L to 82L, P0 - 1L to 65535L, P0.toLong() to 65536L, P0 + 1L to 65553L,
            P0 + P.toLong() to 131072L, P0 + P + 1L to 131089L, (1L shl 34) to 17_184_064_593L,
        )
        for ((length, size) in table) {
            assertEquals(size, MediaCipher.ciphertextSize(length))
            assertEquals(length, MediaCipher.plaintextLength(size))
        }
        assertRefused(MediaReject.TOO_LONG) { MediaCipher.plaintextLength(17_184_064_594L) } // 2^34 + 1 bytes
        for (size in listOf(0L, 25L, 64L, 65L, 80L)) assertRefused(MediaReject.TOO_SHORT) { MediaCipher.plaintextLength(size) }
        // after a full segment, a last segment of a tag or less is never written
        for (extra in 1L..16L) assertRefused(MediaReject.LAST_SEGMENT_SIZE) { MediaCipher.plaintextLength(C + extra) }
        assertEquals(P0 + 1L, MediaCipher.plaintextLength(C + 17L))
    }

    // ---- vectors -------------------------------------------------------------------------

    @Test fun readsEveryCommittedVector() {
        val all = allVectors()
        assertEquals(15, all.size)
        for (v in all) {
            assertEquals(v.file, v.text("sha256"), hex(MessageDigest.getInstance("SHA-256").digest(v.bytes)))
            // the production encoder builds the associated data from the fields; the index has the bytes
            assertEquals(v.file, v.text("ad"), hex(MediaCipher.associatedData(v.fileId, v.area, v.name)))
            opened(v.bytes, v.area, v.name, v.fileId, v.masterKey) { reader ->
                assertEquals(v.file, v.length.toLong(), reader.length)
                assertArrayEquals(v.file, pattern(v.length), plaintext(reader))
                // random access at every segment boundary
                for (position in listOf(0, P0 - 1, P0, P0 + P - 1, P0 + P, v.length - 1).filter { it in 0 until v.length }) {
                    val index = reader.segmentOf(position.toLong())
                    val inSegment = (position - reader.segmentStart(index)).toInt()
                    assertEquals("${v.file} at $position", (position % 251).toByte(), reader.segment(index)[inSegment])
                }
            }
        }
    }

    @Test fun writerReproducesTheKnownAnswersByteForByte() {
        val known = vectors("kat.json")
        assertEquals(3, known.size)
        for (v in known) {
            val fixed = { Fixed(unhex(v.text("salt")) + unhex(v.text("noncePrefix"))) }
            val single = ByteArrayOutputStream()
            MediaCipher.SegmentWriter(v.masterKey, v.fileId, v.area, v.name, single, fixed(), MediaCipher.MAX_PLAINTEXT).use { it.write(pattern(v.length)) }
            assertArrayEquals(v.file, v.bytes, single.toByteArray())
            // odd chunks and a flush in the middle of a segment change nothing
            val chunked = ByteArrayOutputStream()
            MediaCipher.SegmentWriter(v.masterKey, v.fileId, v.area, v.name, chunked, fixed(), MediaCipher.MAX_PLAINTEXT).use { writer ->
                val data = pattern(v.length)
                var position = 0
                while (position < data.size) {
                    val count = minOf(7777, data.size - position)
                    writer.write(data, position, count)
                    writer.flush()
                    position += count
                }
            }
            assertArrayEquals(v.file, v.bytes, chunked.toByteArray())
        }
    }

    /** The Tatrak to Tink direction: `tools/media-vectors` decrypts this folder with Tink and with kat.py. */
    @Test fun writesFilesForTinkToDecrypt() {
        val folder = File("build/media-vectors-tatrak").apply { deleteRecursively(); mkdirs() }
        val cases = listOf(0, 1, P0 - 1, P0, P0 + 1, P0 + P - 1, P0 + P, P0 + P + 1).map { Triple(MediaArea.ATTACHMENTS, "", it) } +
            listOf(
                Triple(MediaArea.AVATARS, "3a7bd3e2360a3d29eea436fcfb7e44c735d117c4", 1000),
                Triple(MediaArea.WALLPAPERS, "9f86d081884c7d65.jpg", 1000),
                Triple(MediaArea.STICKERS, stickerName, 1000),
            )
        val lines = cases.map { (area, name, length) ->
            val fileId = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val bytes = encrypt(length, area, name, fileId)
            assertEquals(MediaCipher.ciphertextSize(length.toLong()), bytes.size.toLong())
            opened(bytes, area, name, fileId) { assertArrayEquals(pattern(length), plaintext(it)) }
            val file = "tatrak-${area.id}-$length.bin"
            File(folder, file).writeBytes(bytes)
            """{"file": "$file", "source": "tatrak", "store": "${area.id}", "name": "$name", "fileId": "${hex(fileId)}", """ +
                """"ad": "${hex(MediaCipher.associatedData(fileId, area, name))}", "masterKey": "${hex(key)}", """ +
                """"length": $length, "sha256": "${hex(MessageDigest.getInstance("SHA-256").digest(bytes))}"}"""
        }
        File(folder, "tatrak.json").writeText("[\n" + lines.joinToString(",\n") + "\n]\n")
    }

    // ---- writer rules --------------------------------------------------------------------

    @Test fun writerKeepsAFullSegmentUntilItKnowsWhetherItIsTheLast() {
        // exactly one segment of plaintext: one segment, no empty second one
        assertEquals(C, encrypt(P0).size)
        assertEquals(C, encrypt(P0) { out, data -> out.write(data); out.write(ByteArray(0)) }.size)
        assertEquals(2 * C, encrypt(P0 + P).size)
        // single bytes across the first boundary
        val bytewise = encrypt(P0 + 2) { out, data -> data.forEach { out.write(it.toInt()) } }
        assertEquals(C + 2 + 16, bytewise.size)
        opened(bytewise) { assertArrayEquals(pattern(P0 + 2), plaintext(it)) }
        // the empty file is one empty segment
        assertEquals(81, encrypt(0).size)
        opened(encrypt(0)) { assertEquals(0L, it.length); assertEquals(1, it.segments); assertEquals(0, it.segment(0).size) }
    }

    @Test fun writerRefusesABadNameBeforeWritingAnything() {
        val out = ByteArrayOutputStream()
        assertRefused(MediaReject.NAME) { MediaCipher.encrypt(key, attachmentId, MediaArea.STICKERS, "Pack/A.webp", out) }
        assertRefused(MediaReject.NAME) { MediaCipher.encrypt(key, attachmentId, MediaArea.ATTACHMENTS, "name", out) }
        assertEquals(0, out.size())
    }

    @Test fun writerStopsAtItsLimitAndNeverCompletesAFailedFile() {
        val out = ByteArrayOutputStream()
        val writer = MediaCipher.SegmentWriter(key, attachmentId, MediaArea.ATTACHMENTS, "", out, SecureRandom(), limit = 10)
        writer.write(ByteArray(10)) // exactly the limit is allowed
        assertRefused(MediaReject.TOO_LARGE) { writer.write(1) }
        try { writer.write(1); fail() } catch (e: IOException) { assertFalse(e is MediaRejectedException) }
        writer.close()
        assertEquals("only the header: no last segment was written", 65, out.size())
        assertEquals(MediaReject.TOO_SHORT, openRefusal(out.toByteArray()))
    }

    @Test fun writerNeverEncryptsASegmentTwiceAfterAFailedWrite() {
        var writes = 0
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw AssertionError()
            override fun write(b: ByteArray, off: Int, len: Int) { if (++writes == 2) throw IOException("disk full") }
        }
        val writer = MediaCipher.SegmentWriter(key, attachmentId, MediaArea.ATTACHMENTS, "", failing, SecureRandom(), MediaCipher.MAX_PLAINTEXT)
        try { writer.write(ByteArray(P0 + 1)); fail() } catch (e: IOException) { assertEquals("disk full", e.message) }
        try { writer.write(1); fail() } catch (e: IOException) { }
        try { writer.finish(); fail() } catch (e: IOException) { }
        writer.close()
        assertEquals("the header and the one failed segment", 2, writes)
    }

    // ---- reader rules: refused at open ---------------------------------------------------

    private val segments4 = P0 + 2 * P + 1 // four segments, the last holds one byte

    @Test fun refusesAtOpen() {
        val file = encrypt(segments4)
        assertNull(openRefusal(file))
        val named = encrypt(1000, MediaArea.STICKERS, stickerName)
        assertNull(openRefusal(named, MediaArea.STICKERS, stickerName))

        // rule 1
        for (size in listOf(0, 25, 64, 65, 80)) assertEquals(MediaReject.TOO_SHORT, openRefusal(file.copyOf(size)))
        // rule 2: prefix and header fields
        assertEquals(MediaReject.MAGIC, openRefusal(flip(file, 0)))
        assertEquals(MediaReject.VERSION, openRefusal(flip(file, 8)))
        assertEquals(MediaReject.FILE_ID, openRefusal(flip(file, 9)))
        assertEquals(MediaReject.FILE_ID, openRefusal(file, fileId = otherId))
        assertEquals(MediaReject.HEADER_LENGTH, openRefusal(flip(file, 25)))
        // rule 3: a last segment of a tag or less after a full one
        for (extra in 1..16) assertEquals(MediaReject.LAST_SEGMENT_SIZE, openRefusal(file.copyOf(C + extra)))
        // rule 5: everything the key derivation or the last tag covers
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(flip(file, 26)))           // salt
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(flip(file, 58)))           // nonce prefix
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(flip(file, 3 * C)))        // the last segment's byte
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(flip(file, file.size - 1))) // its tag
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(file, masterKey = ByteArray(32)))
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(file, MediaArea.AVATARS, "00"))
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(named, MediaArea.STICKERS, stickerName + "x"))
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(named, MediaArea.WALLPAPERS, stickerName)) // only the store differs
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(flip(named, 9), MediaArea.STICKERS, stickerName)) // header fileId feeds the AD
        assertEquals(MediaReject.NAME, openRefusal(named, MediaArea.STICKERS, "Pack/A.webp"))                 // rule 0
        // a header fileId overwritten to the expected one passes rule 2 and fails rule 5
        val relabelled = file.clone().also { System.arraycopy(otherId, 0, it, 9, 16) }
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(relabelled, fileId = otherId))
        // the header of another file of the same key
        val other = encrypt(segments4, fileId = otherId)
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(other.copyOf(65) + file.copyOfRange(65, file.size), fileId = otherId))
        // truncation: the new last segment was not written as the last
        for (size in listOf(3 * C, 2 * C, C, 2 * C + 100, 2 * C + 17)) assertEquals("cut to $size", MediaReject.LAST_SEGMENT_TAG, openRefusal(file.copyOf(size)))
        // extension, a duplicated and a removed segment: the last segment is no longer where its nonce says
        val segment1 = file.copyOfRange(C, 2 * C)
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(file + file.copyOfRange(3 * C, file.size)))
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(file + ByteArray(C)))
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(file.copyOf(2 * C) + segment1 + file.copyOfRange(2 * C, file.size)))
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(file.copyOf(C) + file.copyOfRange(2 * C, file.size)))
    }

    @Test fun refusesValidlyEncryptedFilesThatBreakAFormatRule() {
        val full = pattern(P0)
        // sanity: the crafting code agrees with the reader on a normal file
        val normal = craftHeader() + seal(craftKey(), craftPrefix, 0, true, full)
        opened(normal) { assertArrayEquals(full, plaintext(it)) }
        // an empty last segment after a full one: Tink reads it, this reader does not (rule 3)
        val emptyLast = craftHeader() + seal(craftKey(), craftPrefix, 0, false, full) + seal(craftKey(), craftPrefix, 1, true, ByteArray(0))
        assertEquals(C + 16, emptyLast.size)
        assertEquals(MediaReject.LAST_SEGMENT_SIZE, openRefusal(emptyLast))
        // version 2 in the prefix and in the associated data: the version check refuses it, not a tag
        val version2 = craftHeader(version = 2) + seal(craftKey(version = 2), craftPrefix, 0, true, full)
        assertEquals(MediaReject.VERSION, openRefusal(version2))
        // the last segment encrypted without the "last" flag
        val unflagged = craftHeader() + seal(craftKey(), craftPrefix, 0, false, full)
        assertEquals(MediaReject.LAST_SEGMENT_TAG, openRefusal(unflagged))
    }

    // ---- reader rules: refused at the read of the damaged segment ------------------------

    @Test fun refusesADamagedSegmentOnlyWhenItIsRead() {
        val file = encrypt(segments4)
        val other = encrypt(segments4, fileId = otherId)
        val swapped = file.copyOf(C) + file.copyOfRange(2 * C, 3 * C) + file.copyOfRange(C, 2 * C) + file.copyOfRange(3 * C, file.size)
        val foreign = file.copyOf(C) + other.copyOfRange(C, 2 * C) + file.copyOfRange(2 * C, file.size)

        assertEquals(MediaReject.SEGMENT_TAG, readRefusal(flip(file, 100), 0))        // inside segment 0
        assertNull(readRefusal(flip(file, 100), 1))                                    // the others still read
        assertEquals(MediaReject.SEGMENT_TAG, readRefusal(flip(file, 2 * C - 1), 1))   // the tag of segment 1
        assertEquals(MediaReject.SEGMENT_TAG, readRefusal(swapped, 1))
        assertEquals(MediaReject.SEGMENT_TAG, readRefusal(swapped, 2))
        assertNull(readRefusal(swapped, 0))
        assertEquals(MediaReject.SEGMENT_TAG, readRefusal(foreign, 1))                 // a segment of another file at the same index
        opened(flip(file, 100)) { reader ->
            // a failed tag is an error every time, and the bytes after it stay readable
            repeat(2) { assertRefused(MediaReject.SEGMENT_TAG) { reader.segment(0) } }
            assertArrayEquals(pattern(segments4).copyOfRange(P0, P0 + P), reader.segment(1))
            try { reader.segment(4); fail() } catch (e: IndexOutOfBoundsException) { }
        }
    }

    @Test fun aFileThatShrinksAfterOpenIsAnErrorNotAnEnd() {
        val file = temporary.newFile().apply { writeBytes(encrypt(segments4)) }
        RandomAccessFile(file, "rw").use { raf ->
            val reader = MediaCipher.open(key, MediaArea.ATTACHMENTS, "", attachmentId, raf.channel)
            raf.setLength(C + 10L)
            assertRefused(MediaReject.SHORT_READ) { reader.segment(1) }
            assertRefused(MediaReject.SHORT_READ) { reader.segment(2) }
            assertEquals(1, reader.segment(3).size) // the last segment was verified and kept at open
        }
    }

    // ---- offsets above 2 GiB -------------------------------------------------------------

    @Test fun handlesTheLargestFileWith64BitOffsets() {
        assumeTrue("needs a sparse file", System.getProperty("os.name") == "Linux")
        val length = 1L shl 34
        val size = MediaCipher.ciphertextSize(length)
        val lastIndex = 262_208
        val lastPlain = pattern(1089)
        val file = temporary.newFile()
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(craftHeader())
            raf.seek(lastIndex.toLong() * C)
            raf.write(seal(craftKey(), craftPrefix, lastIndex, true, lastPlain))
            assertEquals(size, raf.length())
            val reader = MediaCipher.open(key, MediaArea.ATTACHMENTS, "", attachmentId, raf.channel)
            assertEquals(length, reader.length)
            assertEquals(lastIndex + 1, reader.segments)
            assertEquals(lastIndex, reader.segmentOf(length - 1))
            assertEquals(length - 1089, reader.segmentStart(lastIndex))
            assertEquals(40_000, reader.segmentOf(P0 + 39_999L * P))
            assertArrayEquals(lastPlain, reader.segment(lastIndex))
            assertRefused(MediaReject.SEGMENT_TAG) { reader.segment(40_000) } // a hole of zeros above 2 GiB: read, not skipped
            // one more byte of plaintext than the format allows
            raf.setLength(size + 1)
            assertRefused(MediaReject.TOO_LONG) { MediaCipher.open(key, MediaArea.ATTACHMENTS, "", attachmentId, raf.channel) }
        }
    }

    private companion object {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
