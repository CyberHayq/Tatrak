package am.cyberhayq.chat.storage.media

import am.cyberhayq.chat.storage.JvmDurable
import am.cyberhayq.chat.storage.media.MediaCipher.FIRST_PLAIN_SEGMENT as P0
import am.cyberhayq.chat.storage.media.MediaCipher.PLAIN_SEGMENT as P
import am.cyberhayq.chat.storage.media.MediaCipher.SEGMENT as C
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.io.CipherOutputStream
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class EncryptedMediaStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var durable: JvmDurable
    private lateinit var files: File
    private lateinit var cache: File

    private val g0 = "9a1c0c0e-58f1-4d7a-b7c2-5f6d3e2a4b99" // an older generation
    private val g1 = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11" // the active one
    private val g2 = "c0ffee00-1234-4abc-8def-001122334455"
    private val k0 = ByteArray(32) { 10 }
    private val k1 = ByteArray(32) { 11 }
    private val k2 = ByteArray(32) { 12 }

    private class Keys(var generation: String?, var own: String?, val keys: MutableMap<String, ByteArray>) : EncryptedMediaStore.KeySource {
        var reads = 0
        var afterSnapshot: (() -> Unit)? = null
        override fun generation() = generation
        override fun read(): MediaKeyTable.Keys {
            reads++
            return MediaKeyTable.Keys(own, HashMap(keys)).also { afterSnapshot?.invoke() }
        }
    }

    @Before fun setUp() {
        durable = JvmDurable.install()
        files = temporary.newFolder("files")
        cache = temporary.newFolder("cache")
    }

    @After fun tearDown() = JvmDurable.uninstall()

    /** A store as after a start: generation [generation] is active, with its own key and [imported] ones. */
    private fun store(generation: String? = g1, own: String? = generation, vararg imported: Pair<String, ByteArray>): EncryptedMediaStore {
        val keys = HashMap<String, ByteArray>(imported.toMap())
        if (own != null) keys[own] = keyOf(own)
        return EncryptedMediaStore(files, cache, Keys(generation, own, keys))
    }

    private fun keyOf(generation: String) = when (generation) { g0 -> k0; g1 -> k1; else -> k2 }
    private fun pattern(length: Int, shift: Int = 0) = ByteArray(length) { ((it + shift) % 251).toByte() }
    private fun attachments() = File(files, "attachments")
    private fun leftovers() = File(files, "tatrak_tmp").list().orEmpty().toList()

    private fun writeAttachment(store: EncryptedMediaStore, data: ByteArray, filename: String = "a.jpg"): File =
        store.createAttachment(store.attachmentFile(filename)).run { outputStream.write(data); commit() }

    private fun writeNamed(store: EncryptedMediaStore, area: MediaArea, name: String, data: ByteArray): File =
        store.create(area, name).run { outputStream.write(data); commit() }

    private fun read(store: EncryptedMediaStore, file: File): ByteArray = store.open(file).inputStream().use { it.readBytes() }

    private fun refusal(block: () -> Unit): MediaReject? = try { block(); null } catch (e: MediaRejectedException) { e.reason }

    // ---- publishing ----------------------------------------------------------------------

    @Test fun anAttachmentAppearsUnderItsNameOnlyAtCommit() {
        val store = store()
        val data = pattern(P0 + 100)
        val target = store.attachmentFile("3d1f.jpg")
        assertTrue(target.name, Regex("tatrak_${g1}_[0-9a-f]{32}\\.jpg").matches(target.name))
        assertEquals(attachments(), target.parentFile)
        val writer = store.createAttachment(target)
        writer.outputStream.use { it.write(data) } // closing the stream does not publish
        assertFalse(target.exists())
        assertEquals(1, leftovers().size)
        durable.steps.clear()
        assertEquals(target, writer.commit())
        assertEquals(listOf("sync ", "rename ${target.name}", "fsync attachments"), durable.steps)
        assertTrue(leftovers().isEmpty())
        assertEquals("TATRKENC", String(target.readBytes(), 0, 8, Charsets.US_ASCII))
        assertArrayEquals(data, read(store, target))
        assertEquals(data.size.toLong(), store.length(target))
        assertTrue(store.isEncrypted(target))
        try { writer.commit(); fail() } catch (e: IllegalStateException) { }
        writer.abort() // after a commit: nothing happens
        assertTrue(target.exists())
    }

    @Test fun anAbortedWriterLeavesNothing() {
        val store = store()
        val target = store.attachmentFile("a.mp4")
        val writer = store.createAttachment(target)
        writer.outputStream.write(pattern(3 * C))
        writer.abort()
        writer.abort()
        assertFalse(target.exists())
        assertTrue(leftovers().isEmpty())
        try { writer.commit(); fail() } catch (e: IllegalStateException) { }
        assertFalse(target.exists())
    }

    @Test fun anExistingAttachmentIsNeverOverwritten() {
        val store = store()
        val target = writeAttachment(store, pattern(500))
        val second = store.createAttachment(target)
        second.outputStream.write(pattern(900, shift = 5))
        try { second.commit(); fail() } catch (e: FileAlreadyExistsException) { }
        assertArrayEquals(pattern(500), read(store, target))
        assertTrue(leftovers().isEmpty())
        // OpenPGP decrypts a file again under its name after a process death between the commit
        // and the message update: the file published first stays, the new copy is dropped
        val again = store.createAttachment(target)
        again.outputStream.write(pattern(900, shift = 5))
        durable.steps.clear()
        assertEquals(target, again.commit(target, keepExisting = true))
        assertEquals(listOf("sync ", "fsync attachments"), durable.steps) // no rename; the kept file's folder entry is synced
        assertArrayEquals(pattern(500), read(store, target))
        assertTrue(leftovers().isEmpty())
        again.abort() // after a commit: nothing happens
        assertTrue(target.exists())
    }

    @Test fun aDamagedFileIsNotKeptOverAVerifiedCopy() {
        val store = store()
        val target = writeAttachment(store, pattern(500))
        RandomAccessFile(target, "rw").use { it.seek(target.length() - 1); val b = it.read(); it.seek(target.length() - 1); it.write(b xor 1) }
        val again = store.createAttachment(target)
        again.outputStream.write(pattern(500))
        assertEquals(target, again.commit(target, keepExisting = true))
        assertArrayEquals(pattern(500), read(store, target)) // the damaged file was replaced
        assertTrue(leftovers().isEmpty())
    }

    @Test fun aLinkInsideTheStoreNeverLeadsToAnotherFileOrOut() {
        val store = store()
        val a = writeAttachment(store, pattern(500), "a.jpg")
        val b = writeAttachment(store, pattern(700, shift = 3), "b.jpg")
        // a's name, b's content: the reader would show another message's file
        a.delete()
        Files.createSymbolicLink(a.toPath(), b.toPath())
        assertEquals(MediaReject.NOT_ENCRYPTED, refusal { store.open(a) })
        assertTrue(store.isEncrypted(a))
        // a store name leading out of the store: never read as plain
        val outside = File(files, "outside.jpg").apply { writeBytes(pattern(10)) }
        val c = store.attachmentFile("c.jpg")
        Files.createSymbolicLink(c.toPath(), outside.toPath())
        assertEquals(MediaReject.NOT_ENCRYPTED, refusal { store.open(c) })
        assertTrue(store.isEncrypted(c))
        assertEquals(0L, store.length(c))
        // the files themselves still open
        assertArrayEquals(pattern(700, shift = 3), read(store, b))
    }

    @Test fun readingADamagedFileMarksItFailed() {
        val store = store()
        val file = writeAttachment(store, pattern(3 * C))
        RandomAccessFile(file, "rw").use { it.seek(C + 500L); val x = it.read(); it.seek(C + 500L); it.write(x xor 1) }
        val opened = store.open(file)
        try { opened.inputStream().use { it.readBytes() }; fail() } catch (e: MediaRejectedException) { }
        assertTrue(opened.failed) // what decodeBitmap checks to drop a partly decoded image
        opened.close()
    }

    @Test fun aNamedFileIsReplacedByItsNewVersion() {
        val store = store()
        val title = writeNamed(store, MediaArea.STICKERS, "pack-ab12/name.txt", "old title".toByteArray())
        assertEquals(File(files, "Stickers/$g1/pack-ab12/name.txt"), title)
        assertEquals(title, writeNamed(store, MediaArea.STICKERS, "pack-ab12/name.txt", "new title".toByteArray()))
        assertEquals("new title", String(read(store, title)))
        assertTrue(leftovers().isEmpty())
    }

    @Test fun aCrashBeforeTheRenamePublishesNothing() {
        val store = store()
        writeAttachment(store, pattern(10)) // the folders exist from here on
        for (step in listOf("sync", "rename")) {
            val target = store.attachmentFile("a.jpg")
            val writer = store.createAttachment(target)
            writer.outputStream.write(pattern(2 * C))
            durable.stopBefore = step
            try { writer.commit(); fail(step) } catch (e: IOException) { }
            durable.stopBefore = null
            assertFalse(step, target.exists())
            assertTrue(step, leftovers().isEmpty())
        }
    }

    @Test fun anErrorAfterTheRenameIsReportedAndTheCompleteFileStays() {
        val store = store()
        writeAttachment(store, pattern(10))
        val target = store.attachmentFile("a.jpg")
        val writer = store.createAttachment(target)
        writer.outputStream.write(pattern(2 * C))
        durable.stopBefore = "fsync"
        try { writer.commit(); fail() } catch (e: IOException) { }
        durable.stopBefore = null
        assertArrayEquals(pattern(2 * C), read(store, target))
        // a retry gets a new name, so it cannot collide with that file
        assertNotEquals(target, store.attachmentFile("a.jpg"))
    }

    @Test fun aRestartEmptiesTheTemporaryFolderAndKeepsEveryPublishedFile() {
        val before = store()
        val published = writeAttachment(before, pattern(700))
        val sticker = writeNamed(before, MediaArea.STICKERS, "pack-ab12/1a2b.webp", pattern(300))
        // the process dies with one writer open and one stray file
        before.createAttachment(before.attachmentFile("b.jpg")).outputStream.write(pattern(C))
        File(files, "tatrak_tmp/stray").writeText("x")
        assertEquals(2, leftovers().size)
        val after = store()
        assertTrue(leftovers().isEmpty())
        assertEquals(listOf(published.name), attachments().list()!!.toList())
        assertArrayEquals(pattern(700), read(after, published))
        assertArrayEquals(pattern(300), read(after, sticker))
    }

    // ---- names ---------------------------------------------------------------------------

    @Test fun attachmentNamesCarryTheGenerationAFreshIdAndTheExtension() {
        val store = store()
        val first = store.attachmentFile("3d1f.jpg")
        assertNotEquals(first, store.attachmentFile("3d1f.jpg"))
        assertTrue(Regex("tatrak_${g1}_[0-9a-f]{32}").matches(store.attachmentFile("no-extension").name))
        assertTrue(store.attachmentFile("archive.tar.gz").name.endsWith(".gz"))
        assertTrue(Regex("tatrak_${g1}_[0-9a-f]{32}").matches(store.attachmentFile("odd.ext/../x!").name))
        // a name of the active generation that only gains its extension keeps its id (OpenPGP)
        val id = "00112233445566778899aabbccddeeff"
        assertEquals("tatrak_${g1}_$id.pdf", store.attachmentFile("tatrak_${g1}_$id.pdf").name)
        // an extension the store does not take (an OpenPGP sender's file name) is dropped, the id kept
        assertEquals("tatrak_${g1}_$id", store.attachmentFile("tatrak_${g1}_$id.jpeg-large").name)
        // another generation's name is not kept: this database cannot write under it
        assertTrue(Regex("tatrak_${g1}_(?!$id)[0-9a-f]{32}\\.pdf").matches(store.attachmentFile("tatrak_${g0}_$id.pdf").name))
        // OpenPGP finishing a message of an older generation (a recovery import) writes under a new active name
        val own = store.attachmentFile("a.pdf")
        assertEquals(own, store.writableTarget(own))
        val older = File(attachments(), "tatrak_${g0}_$id.pdf")
        assertTrue(Regex("tatrak_${g1}_(?!$id)[0-9a-f]{32}\\.pdf").matches(store.writableTarget(older).name))
        val shared = File("/storage/emulated/0/Download/a.pdf")
        assertTrue(Regex("tatrak_${g1}_[0-9a-f]{32}\\.pdf").matches(store.writableTarget(shared).name))
        assertEquals(g1, EncryptedMediaStore.generationOf(first.name))
        for (other in listOf("photo.jpg", "tatrak_$g1", "tatrak_${g1}_$id.jpg.pgp", "")) assertEquals(other, null, EncryptedMediaStore.generationOf(other))
    }

    @Test fun anAttachmentIsCommittedUnderItsNameOrThatNamePlusAnExtension() {
        val store = store()
        // OpenPGP learns the type only from the decrypted result: the name gains an extension at commit
        val reserved = store.attachmentFile("no-extension")
        val writer = store.createAttachment(reserved)
        writer.outputStream.write(pattern(500))
        assertEquals(reserved, writer.target)
        val typed = store.attachmentFile(reserved.name + ".pdf")
        assertEquals(reserved.name + ".pdf", typed.name)
        assertEquals(typed, writer.commit(typed))
        assertFalse(reserved.exists())
        assertArrayEquals(pattern(500), read(store, typed))
        // any other name is refused as a file error (the name comes from the sender's file name, so
        // it must not crash the OpenPGP thread) and nothing is published
        val another = store.createAttachment(store.attachmentFile("b"))
        another.outputStream.write(pattern(5))
        for (bad in listOf(store.attachmentFile("c.jpg"), File(files, "sub/" + another.target.name + ".jpg"), File(attachments(), another.target.name + "x.jpg"),
            File(temporary.newFolder("shared"), another.target.name + ".jpg"),
            File(attachments(), another.target.name + ".pdf.pgp"))) { // a name the store would never open
            assertEquals(bad.path, MediaReject.NAME, refusal { another.commit(bad) })
        }
        another.abort()
        assertTrue(leftovers().isEmpty())
        assertEquals(listOf(typed.name), attachments().list()!!.toList())
    }

    @Test fun aTargetOutsideTheStoreIsRefusedAndNothingPlainIsWritten() {
        val store = store()
        // a shared folder (the removed "store in shared folders" setting, or an old message row)
        val shared = Files.createTempDirectory("tatrak-shared").toFile() // not under the app's private folders
        try {
            for (target in listOf(File(shared, "Tatrak/a.jpg"), File(shared, "Download/" + store.attachmentFile("b.jpg").name))) {
                assertEquals(target.path, MediaReject.NOT_ENCRYPTED, refusal { store.createAttachment(target) })
            }
            assertTrue(shared.list()!!.isEmpty())
        } finally { shared.deleteRecursively() }
        // nor inside the private folders
        for (private in listOf(File(files, "other.jpg"), File(cache, "x.jpg"), File(temporary.root, "y.jpg"))) {
            assertEquals(private.path, MediaReject.NOT_ENCRYPTED, refusal { store.createAttachment(private) })
            assertFalse(private.exists())
        }
        // nor through another path to them (/data/data/<pkg> is /data/user/0/<pkg>)
        val alias = Files.createTempDirectory("tatrak-alias").toFile()
        try {
            val link = Files.createSymbolicLink(File(alias, "app").toPath(), files.parentFile!!.toPath()).toFile()
            val private = File(link, "files/other.jpg")
            assertEquals(MediaReject.NOT_ENCRYPTED, refusal { store.createAttachment(private) })
            assertFalse(private.exists())
        } finally { File(alias, "app").delete(); alias.delete() } // not recursive: it would follow the link
    }

    // ---- reading rule --------------------------------------------------------------------

    @Test fun aFileOpensOnlyWithTheKeyOfTheGenerationInItsName() {
        val old = writeAttachment(store(generation = g0), pattern(1234))
        assertTrue(old.name.startsWith("tatrak_${g0}_"))
        // after a recovery import: a new own key, the old one brought by the backup
        assertArrayEquals(pattern(1234), read(store(g1, g1, g0 to k0), old))
        // without that key the file is unavailable
        assertEquals(MediaReject.NO_KEY, refusal { store(g1).open(old) })
        // and the old database's key under the new generation's name does not help
        val renamed = File(attachments(), old.name.replace(g0, g1)).also { old.copyTo(it) }
        assertEquals(MediaReject.LAST_SEGMENT_TAG, refusal { store(g1, g1, g0 to k0).open(renamed) })
    }

    @Test fun aFileUnderAnotherFilesNameIsRefused() {
        val store = store()
        val one = writeAttachment(store, pattern(100))
        val other = store.attachmentFile("b.jpg")
        one.copyTo(other)
        assertEquals(MediaReject.FILE_ID, refusal { store.open(other) })
    }

    @Test fun aPlainFileInTheAttachmentFolderIsUnavailable() {
        val store = store()
        attachments().mkdirs()
        val plain = File(attachments(), "photo.jpg").apply { writeBytes(pattern(5000)) }
        assertEquals(MediaReject.NOT_ENCRYPTED, refusal { store.open(plain) })
        assertEquals(0L, store.length(plain))
        assertTrue(store.isEncrypted(plain))
        // a plain file that took an encrypted file's name is not read as plain either
        val disguised = store.attachmentFile("x.jpg").apply { writeBytes(pattern(5000)) }
        assertEquals(MediaReject.MAGIC, refusal { store.open(disguised) })
        val short = store.attachmentFile("y.jpg").apply { writeBytes(pattern(10)) }
        assertEquals(MediaReject.TOO_SHORT, refusal { store.open(short) })
        assertEquals(0L, store.length(short))
        val nested = File(attachments(), "sub/" + store.attachmentFile("z.jpg").name).apply { parentFile!!.mkdirs(); writeBytes(pattern(100)) }
        assertEquals(MediaReject.NOT_ENCRYPTED, refusal { store.open(nested) })
    }

    @Test fun aFileOutsideTheStoreIsPlainAndAMissingFileIsMissing() {
        val store = store()
        val shared = temporary.newFile("shared.jpg").apply { writeBytes(pattern(70_000)) }
        assertFalse(store.isEncrypted(shared))
        assertEquals(70_000L, store.length(shared))
        assertArrayEquals(pattern(70_000), read(store, shared))
        store.open(shared).use { file ->
            val buffer = ByteArray(10)
            assertEquals(5, file.readAt(69_995, buffer, 0, 10)) // a short read at the end of a plain file
        }
        // an OpenPGP intermediate in the cache carries an attachment's name and is read raw
        val pgp = File(cache, store.attachmentFile("a.jpg").name + ".pgp").apply { writeBytes(pattern(300)) }
        assertArrayEquals(pattern(300), read(store, pgp))
        try { store.open(store.attachmentFile("gone.jpg")); fail() } catch (e: FileNotFoundException) { }
        try { store.open(File(temporary.root, "gone")); fail() } catch (e: FileNotFoundException) { }
    }

    @Test fun nothingIsReadOrWrittenWhileStorageIsNotReady() {
        val ready = store()
        val file = writeAttachment(ready, pattern(10))
        val waiting = store(generation = null, own = null)
        assertEquals(MediaReject.NO_KEY, refusal { waiting.open(file) })
        try { waiting.attachmentFile("a.jpg"); fail() } catch (e: IllegalStateException) { }
    }

    // ---- avatars, wallpapers, stickers ---------------------------------------------------

    @Test fun namedFilesAreBoundToTheirStoreFolderAndName() {
        val store = store()
        val wallpaper = writeNamed(store, MediaArea.WALLPAPERS, "9f86d081884c7d65.jpg", pattern(4000))
        assertEquals(File(files, "wallpapers/$g1/9f86d081884c7d65.jpg"), wallpaper)
        val sticker = writeNamed(store, MediaArea.STICKERS, "pack-aa/1a2b.webp", pattern(900))
        val avatar = writeNamed(store, MediaArea.AVATARS, "3a7bd3e2360a3d29eea436fcfb7e44c735d117c4", pattern(2000))
        assertEquals(File(cache, "avatars/3a7bd3e2360a3d29eea436fcfb7e44c735d117c4"), avatar)
        assertArrayEquals(pattern(4000), read(store, wallpaper))
        assertArrayEquals(pattern(900), read(store, sticker))
        assertArrayEquals(pattern(2000), read(store, avatar))
        assertEquals(900L, store.length(sticker))
        // a sticker moved to another pack, and a wallpaper put among the stickers
        val moved = File(files, "Stickers/$g1/pack-bb/1a2b.webp").also { sticker.copyTo(it) }
        assertEquals(MediaReject.LAST_SEGMENT_TAG, refusal { store.open(moved) })
        val misplaced = File(files, "Stickers/$g1/9f86d081884c7d65.jpg").also { wallpaper.copyTo(it) }
        assertEquals(MediaReject.LAST_SEGMENT_TAG, refusal { store.open(misplaced) })
        // a file left from the layout without generation folders
        val legacy = File(files, "wallpapers/old.jpg").apply { writeBytes(pattern(100)) }
        assertEquals(MediaReject.NOT_ENCRYPTED, refusal { store.open(legacy) })
        assertEquals(MediaReject.NAME, refusal { store.create(MediaArea.STICKERS, "Pack/A.webp") })
        try { store.create(MediaArea.ATTACHMENTS, ""); fail() } catch (e: IllegalArgumentException) { }
        assertTrue(leftovers().isEmpty())
    }

    @Test fun wallpapersAndStickersOfAnOlderGenerationOpenWithItsKeyAvatarsDoNot() {
        val before = store(generation = g0)
        val wallpaper = writeNamed(before, MediaArea.WALLPAPERS, "9f86d081884c7d65.jpg", pattern(4000))
        val avatar = writeNamed(before, MediaArea.AVATARS, "3a7bd3e2360a3d29eea436fcfb7e44c735d117c4", pattern(2000))
        val after = store(g1, g1, g0 to k0)
        assertArrayEquals(pattern(4000), read(after, wallpaper))
        assertEquals(MediaReject.NO_KEY, refusal { store(g1).open(wallpaper) })
        // the avatar cache has no generation in its path: only the own key is tried, the caller fetches again
        assertEquals(MediaReject.LAST_SEGMENT_TAG, refusal { after.open(avatar) })
    }

    // ---- no own key ----------------------------------------------------------------------

    @Test fun aDatabaseWithoutItsOwnKeyReservesNamesButWritesNothing() {
        for (store in listOf(store(g1, own = null), store(g1, own = g0))) { // lost, or bound to another generation
            val target = store.attachmentFile("a.jpg")
            assertTrue(target.name.startsWith("tatrak_${g1}_"))
            assertEquals(MediaReject.NO_KEY, refusal { store.createAttachment(target) })
            assertEquals(MediaReject.NO_KEY, refusal { store.create(MediaArea.STICKERS, "pack-aa/1a2b.webp") })
            assertEquals(MediaReject.NO_KEY, refusal { store.create(MediaArea.AVATARS, "aa") })
            assertFalse(target.exists())
            assertTrue(leftovers().isEmpty())
        }
    }

    @Test fun onlyAReservedNameOfTheActiveGenerationCanBeWritten() {
        val store = store(g1, g1, g0 to k0)
        assertEquals(MediaReject.NOT_ENCRYPTED, refusal { store.createAttachment(File(attachments(), "photo.jpg")) })
        assertEquals(MediaReject.NOT_ENCRYPTED, refusal { store.createAttachment(temporary.newFile()) })
        val foreign = File(attachments(), "tatrak_${g0}_00112233445566778899aabbccddeeff.jpg")
        assertEquals(MediaReject.NO_KEY, refusal { store.createAttachment(foreign) })
        assertTrue(leftovers().isEmpty())
    }

    @Test fun keysAreReadOnceAndAgainAfterAnImport() {
        val old = writeAttachment(store(generation = g0), pattern(50))
        val keys = Keys(g1, g1, hashMapOf(g1 to k1))
        val store = EncryptedMediaStore(files, cache, keys)
        val own = writeAttachment(store, pattern(60))
        repeat(5) { assertArrayEquals(pattern(60), read(store, own)) }
        assertEquals(1, keys.reads)
        assertEquals(MediaReject.NO_KEY, refusal { store.open(old) })
        keys.keys[g0] = k0 // the importer inserted the backup's key
        assertEquals(MediaReject.NO_KEY, refusal { store.open(old) })
        store.keysChanged()
        assertArrayEquals(pattern(50), read(store, old))
        assertEquals(2, keys.reads)
    }

    @Test fun anImportDuringAKeyLoadIsNotLost() {
        val old = writeAttachment(store(generation = g0), pattern(50))
        val keys = Keys(g1, g1, hashMapOf(g1 to k1))
        val store = EncryptedMediaStore(files, cache, keys)
        val loading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        keys.afterSnapshot = { keys.afterSnapshot = null; loading.countDown(); resume.await() }
        var readerSaw: Any? = "nothing"
        val reader = thread { readerSaw = try { refusal { store.open(old) } } catch (t: Throwable) { t } } // loads the keys, holds the old snapshot
        assertTrue(loading.await(10, TimeUnit.SECONDS))
        keys.keys[g0] = k0 // the importer commits the backup's key...
        val importer = thread { store.keysChanged() } // ...and says so while the load is in flight
        // the invalidation has either finished (no lock) or waits for the load (the fix): never "not started yet"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (importer.isAlive && importer.state != Thread.State.BLOCKED) {
            assertTrue("the importer neither finished nor blocked", System.nanoTime() < deadline)
            Thread.sleep(1)
        }
        resume.countDown()
        reader.join(10_000); importer.join(10_000)
        assertEquals("the reader had the old keys", MediaReject.NO_KEY, readerSaw)
        assertArrayEquals(pattern(50), read(store, old))
    }

    // ---- links ---------------------------------------------------------------------------

    @Test fun anAliasOfAStoreFolderDoesNotMakeItsFilesPlain() {
        val store = store()
        val encrypted = writeAttachment(store, pattern(800))
        val plain = File(attachments(), "photo.jpg").apply { writeBytes(pattern(100)) }
        File(attachments(), "sub").mkdir()
        val alias = Files.createSymbolicLink(File(temporary.root, "alias").toPath(), attachments().toPath()).toFile()
        val inner = Files.createSymbolicLink(File(temporary.root, "inner").toPath(), File(attachments(), "sub").toPath()).toFile()
        for (path in listOf(File(alias, plain.name), File(inner, "../" + plain.name))) { // a link, and a link with ".."
            assertTrue(path.path, store.isEncrypted(path))
            assertEquals(path.path, 0L, store.length(path))
            assertEquals(path.path, MediaReject.NOT_ENCRYPTED, refusal { store.open(path) })
        }
        // an encrypted file reached through the alias is decrypted and checked, not handed out raw
        assertArrayEquals(pattern(800), read(store, File(alias, encrypted.name)))
        assertEquals(800L, store.length(File(alias, encrypted.name)))
    }

    @Test fun anAreaFolderThatIsItselfALinkStillHoldsOnlyEncryptedFiles() {
        val elsewhere = temporary.newFolder("elsewhere")
        Files.createSymbolicLink(attachments().toPath(), elsewhere.toPath())
        val store = store()
        val plain = File(elsewhere, "photo.jpg").apply { writeBytes(pattern(100)) }
        for (path in listOf(File(attachments(), "photo.jpg"), plain)) { // by the store's name and by the real one
            assertTrue(path.path, store.isEncrypted(path))
            assertEquals(path.path, MediaReject.NOT_ENCRYPTED, refusal { store.open(path) })
        }
        val written = writeAttachment(store, pattern(300)) // writing and reading through the link still work
        assertEquals(attachments(), written.parentFile)
        assertArrayEquals(pattern(300), read(store, written))
        assertArrayEquals(pattern(300), read(store, File(elsewhere, written.name)))
    }

    @Test fun anAttachmentIsWrittenToThePathThatWasChecked() {
        val store = store()
        writeAttachment(store, pattern(10))
        File(attachments(), "sub").mkdir()
        val alias = Files.createSymbolicLink(File(temporary.root, "alias").toPath(), File(attachments(), "sub").toPath()).toFile()
        val name = store.attachmentFile("a.jpg").name
        // really files/attachments/<name>, but spelled so that it normalizes to a file beside the alias
        assertEquals(MediaReject.NOT_ENCRYPTED, refusal { store.createAttachment(File(alias, "../$name")) })
        assertFalse(File(temporary.root, name).exists())
        assertFalse(File(attachments(), name).exists())
        assertTrue(leftovers().isEmpty())
    }

    @Test fun aRemovedFolderIsMadeAgain() {
        val store = store()
        val sticker = writeNamed(store, MediaArea.STICKERS, "pack-aa/1a2b.webp", pattern(30))
        val avatar = writeNamed(store, MediaArea.AVATARS, "aa", pattern(30))
        assertTrue(sticker.parentFile!!.deleteRecursively()) // the pack is deleted
        assertTrue(avatar.parentFile!!.deleteRecursively())  // the system clears the cache
        durable.steps.clear()
        assertArrayEquals(pattern(40), read(store, writeNamed(store, MediaArea.STICKERS, "pack-aa/1a2b.webp", pattern(40))))
        assertArrayEquals(pattern(40), read(store, writeNamed(store, MediaArea.AVATARS, "aa", pattern(40))))
        for (parent in listOf(g1, "cache")) assertTrue("$parent in ${durable.steps}", "fsync $parent" in durable.steps) // made durable again
        // removed again, and this time the parent sync fails after the folder is made: the retry must sync it
        assertTrue(sticker.parentFile!!.deleteRecursively())
        durable.stopBefore = "fsync $g1"
        try { store.create(MediaArea.STICKERS, "pack-aa/1a2b.webp"); fail() } catch (e: IOException) { }
        assertTrue(sticker.parentFile!!.isDirectory)
        durable.stopBefore = null
        durable.steps.clear()
        writeNamed(store, MediaArea.STICKERS, "pack-aa/1a2b.webp", pattern(50))
        assertTrue(durable.steps.toString(), "fsync $g1" in durable.steps)
    }

    @Test fun startupCleanupNeverFollowsALink() {
        val published = writeAttachment(store(), pattern(700))
        val tmp = File(files, "tatrak_tmp")
        Files.createSymbolicLink(File(tmp, "leftover").toPath(), attachments().toPath())
        File(tmp, "stray.part").writeText("x")
        var store = store()
        assertTrue(leftovers().isEmpty())
        assertArrayEquals(pattern(700), read(store, published))
        // the temporary folder itself replaced by a link to the attachments
        assertTrue(tmp.delete())
        Files.createSymbolicLink(tmp.toPath(), attachments().toPath())
        store = store()
        assertFalse(Files.isSymbolicLink(tmp.toPath()))
        assertArrayEquals(pattern(700), read(store, published))
        writeAttachment(store, pattern(10)) // and writing works again, in a real folder
        assertTrue(tmp.isDirectory && !Files.isSymbolicLink(tmp.toPath()))
        assertEquals(2, attachments().list()!!.size)
    }

    @Test fun aFolderWhoseParentSyncFailedIsSyncedOnTheRetry() {
        val store = store()
        val pack = File(files, "Stickers/$g1/pack-aa")
        durable.stopBefore = "fsync $g1" // the pack folder is made, the sync of its parent fails
        try { store.create(MediaArea.STICKERS, "pack-aa/1a2b.webp"); fail() } catch (e: IOException) { }
        assertTrue(pack.isDirectory)
        durable.stopBefore = null
        durable.steps.clear()
        writeNamed(store, MediaArea.STICKERS, "pack-aa/1a2b.webp", pattern(30))
        assertTrue("the existing folder is still made durable in its parent: " + durable.steps, "fsync $g1" in durable.steps)
        // and the same in a new process, which does not remember what was synced
        durable.steps.clear()
        writeNamed(store(), MediaArea.STICKERS, "pack-aa/5e6f.webp", pattern(30))
        assertTrue(durable.steps.toString(), "fsync $g1" in durable.steps)
        durable.steps.clear()
        writeNamed(store, MediaArea.STICKERS, "pack-aa/3c4d.webp", pattern(30))
        assertEquals("known folders are not synced again", listOf("sync ", "rename 3c4d.webp", "fsync pack-aa"), durable.steps)
    }

    // ---- bridge for the upstream readers -------------------------------------------------

    @Test fun aSharedNameHidesTheGenerationAndFindsTheFileInAnyGenerationWithAKey() {
        val store = store(g1, g1, g0 to k0)
        val own = writeAttachment(store, pattern(10), "a.jpg")
        val name = checkNotNull(store.sharedName(own))
        assertTrue(name, Regex("[0-9a-f]{32}\\.jpg").matches(name))
        assertEquals(own, store.sharedFile(name))
        // a file of an imported generation, and one whose generation has no key here
        val imported = File(attachments(), "tatrak_${g0}_${"ab".repeat(16)}.pdf").apply { writeBytes(ByteArray(1)) }
        val foreign = File(attachments(), "tatrak_${g2}_${"cd".repeat(16)}.pdf").apply { writeBytes(ByteArray(1)) }
        assertEquals(imported, store.sharedFile("ab".repeat(16) + ".pdf"))
        assertEquals(null, store.sharedFile(checkNotNull(store.sharedName(foreign))))
        // nothing else has a shared name or is found by one
        val plain = File(attachments(), "5a5a.jpg").apply { writeBytes(ByteArray(1)) }
        val outside = File(files, "other.jpg").apply { writeBytes(ByteArray(1)) }
        assertEquals(null, store.sharedName(plain))
        assertEquals(null, store.sharedName(outside))
        File(attachments(), "sub").mkdirs()
        File(attachments(), "sub/n.jpg").writeBytes(ByteArray(1))
        Files.createSymbolicLink(File(attachments(), "link.jpg").toPath(), outside.toPath())
        assertEquals(null, store.sharedName(File(attachments(), "sub/n.jpg")))
        for (bad in listOf("5a5a.jpg", own.name, "../other.jpg", "..", ".", "", "ab".repeat(16) + ".pdf/x", "sub/n.jpg", "sub", "link.jpg", "missing.jpg")) assertEquals(bad, null, store.sharedFile(bad))
        // an attachment replaced by a link after its URI was handed out leads nowhere
        own.delete()
        Files.createSymbolicLink(own.toPath(), outside.toPath())
        assertEquals(null, store.sharedFile(name))
    }

    @Test fun decodesAnImageAndNeverShowsADamagedOneInPart() {
        val store = store()
        val picture = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff336699.toInt()) }
        val png = ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val file = writeAttachment(store, png, "p.png")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        store.decodeBitmap(file, bounds)
        assertEquals(300 to 200, bounds.outWidth to bounds.outHeight)
        val decoded = checkNotNull(store.decodeBitmap(file, null))
        assertEquals(300 to 200, decoded.width to decoded.height)
        assertEquals(0xff336699.toInt(), decoded.getPixel(150, 100))
        // as BitmapFactory.decodeFile: no exception for a missing or unavailable file
        assertEquals(null, store.decodeBitmap(store.attachmentFile("gone.png"), null))
        assertEquals(null, store.decodeBitmap(File(attachments(), "plain.png").apply { writeBytes(png) }, null))
        // a large noisy image damaged in a middle segment decodes to nothing, not to its upper part
        val noise = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(7)
        for (x in 0 until 400) for (y in 0 until 400) noise.setPixel(x, y, random.nextInt() or 0xff000000.toInt())
        val big = writeAttachment(store, ByteArrayOutputStream().also { noise.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(), "n.png")
        assertTrue("needs three segments", big.length() > 2L * C + 100)
        checkNotNull(store.decodeBitmap(big, null))
        RandomAccessFile(big, "rw").use { it.seek(C + 500L); val b = it.read(); it.seek(C + 500L); it.write(b xor 1) }
        // Robolectric's decoder throws on a failing stream; a device hands back what it decoded so
        // far, which decodeBitmap drops (checked on the emulator by StorageInstrumentation)
        val shown = try { store.decodeBitmap(big, null) } catch (e: RuntimeException) { null }
        assertEquals(null, shown)
    }

    // ---- the transport ciphers of a download and a Jingle receive --------------------------

    @Test fun aTransportCipherStreamWithABadTagPublishesNothing() {
        // BouncyCastle's CipherOutputStream hands the plaintext on and checks the GCM tag in close():
        // the receivers commit only after close() returned, so a failed tag leaves nothing
        val store = store()
        val key = ByteArray(32) { 3 }
        val iv = ByteArray(12) { 4 }
        val plain = pattern(P0 + 100)
        val sealed = GCMBlockCipher.newInstance(AESEngine.newInstance()).let { cipher ->
            cipher.init(true, AEADParameters(KeyParameter(key), 128, iv))
            val out = ByteArray(cipher.getOutputSize(plain.size))
            cipher.doFinal(out, cipher.processBytes(plain, 0, plain.size, out, 0))
            out
        }
        fun receive(bytes: ByteArray): File? {
            val writer = store.createAttachment(store.attachmentFile("r.bin"))
            try {
                val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance()).apply { init(false, AEADParameters(KeyParameter(key), 128, iv)) }
                CipherOutputStream(writer.outputStream, cipher).use { it.write(bytes) }
                return writer.commit()
            } catch (e: IOException) { return null } finally { writer.abort() }
        }
        val good = checkNotNull(receive(sealed))
        assertArrayEquals(plain, read(store, good))
        val bad = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals(null, receive(bad))
        assertEquals(listOf(good.name), attachments().list()!!.toList())
        assertTrue(leftovers().isEmpty())
    }

    // ---- import stage --------------------------------------------------------------------

    @Test fun aStagedFileIsWrittenInItsFolderUnderItsFinalName() {
        val stage = temporary.newFolder("stage")
        val store = store()
        val writer = store.createStaged(stage, g2, k2, "5a5a.png")
        writer.outputStream.write(pattern(P0 + 1))
        assertEquals(1, stage.list()!!.count { it.endsWith(".part") })
        val staged = writer.commit()
        assertEquals(stage, staged.parentFile)
        assertTrue(staged.name, Regex("tatrak_${g2}_[0-9a-f]{32}\\.png").matches(staged.name))
        assertEquals(listOf(staged.name), stage.list()!!.toList())
        assertTrue(leftovers().isEmpty()) // never through the temporary folder: a stage survives a restart
        // moved into the attachment folder at the import's commit, it opens with the stage's key
        val published = File(attachments().apply { mkdirs() }, staged.name)
        assertTrue(staged.renameTo(published))
        assertArrayEquals(pattern(P0 + 1), read(store(g2), published))
    }

    // ---- reading -------------------------------------------------------------------------

    private val fourSegments = P0 + 2 * P + 1

    @Test fun readsAnyRangeAcrossSegments() {
        val store = store()
        val data = pattern(fourSegments)
        val file = writeAttachment(store, data)
        store.open(file).use { opened ->
            assertEquals(data.size.toLong(), opened.length)
            for ((position, size) in listOf(0 to 10, P0 - 5 to 10, P0 to 1, P0 + P - 3 to 60_000, 0 to data.size, 5 to 0, P0 + 2 * P to 1, 100 to 200, P0 + 7 to 9, 0 to 3)) {
                val buffer = ByteArray(size + 4)
                assertEquals("$position+$size", size, opened.readAt(position.toLong(), buffer, 2, size))
                assertArrayEquals("$position+$size", data.copyOfRange(position, position + size), buffer.copyOfRange(2, 2 + size))
            }
            val tail = ByteArray(100)
            assertEquals(5, opened.readAt(data.size - 5L, tail, 0, 100)) // a short read at the end
            assertEquals(-1, opened.readAt(data.size.toLong(), tail, 0, 100))
            assertEquals(-1, opened.readAt(data.size + 9L, tail, 0, 100))
            try { opened.readAt(0, tail, 90, 20); fail() } catch (e: IndexOutOfBoundsException) { }
        }
        store.open(file).inputStream().use { stream ->
            assertEquals(data.size, stream.available())
            assertEquals(data[0].toInt() and 255, stream.read())
            assertEquals(P0.toLong(), stream.skip(P0.toLong()))
            assertArrayEquals(data.copyOfRange(P0 + 1, data.size), stream.readBytes())
            assertEquals(-1, stream.read())
        }
        val closed = store.open(file).also { it.close(); it.close() }
        try { closed.readAt(0, ByteArray(1), 0, 1); fail() } catch (e: IOException) { }
    }

    @Test fun aDamagedSegmentIsAnErrorEveryTimeAndNoByteOfItIsReturned() {
        val store = store()
        val data = pattern(fourSegments)
        val file = writeAttachment(store, data)
        RandomAccessFile(file, "rw").use { it.seek(C + 500L); val b = it.read(); it.seek(C + 500L); it.write(b xor 1) } // inside segment 1
        store.open(file).use { opened ->
            val buffer = ByteArray(P0 + 100)
            assertFalse(opened.failed)
            assertEquals(100, opened.readAt(0, buffer, 0, 100)) // segment 0 is fine
            buffer.fill(0x55)
            repeat(2) {
                // a read that starts in a good segment and runs into the damaged one
                assertEquals(MediaReject.SEGMENT_TAG, refusal { opened.readAt(P0 - 50L, buffer, 0, 100) })
            }
            assertTrue(opened.failed)
            assertTrue("nothing of segment 1 reached the caller", buffer.copyOfRange(50, 100).all { it == 0x55.toByte() })
            // segments after the damage stay readable
            assertEquals(10, opened.readAt(P0 + P + 5L, buffer, 0, 10))
            assertArrayEquals(data.copyOfRange(P0 + P + 5, P0 + P + 15), buffer.copyOfRange(0, 10))
        }
        // a stream hands out the good bytes, then fails: it never reports an end
        store.open(file).inputStream().use { stream ->
            val head = ByteArray(P0)
            var read = 0
            while (read < P0) read += stream.read(head, read, P0 - read)
            assertArrayEquals(data.copyOfRange(0, P0), head)
            assertEquals(MediaReject.SEGMENT_TAG, refusal { stream.read(ByteArray(10), 0, 10) })
        }
    }

    @Test fun aDataSourceReportsDamageAsAnErrorAndRemembersIt() {
        val store = store()
        val data = pattern(fourSegments)
        val file = writeAttachment(store, data)
        val good = store.open(file)
        val source = good.dataSource()
        assertEquals(data.size.toLong(), source.size)
        val buffer = ByteArray(2048)
        assertEquals(2048, source.readAt(P0 - 1000L, buffer, 0, 2048)) // the platform's read size, across a boundary
        assertArrayEquals(data.copyOfRange(P0 - 1000, P0 + 1048), buffer)
        assertEquals(-1, source.readAt(data.size.toLong(), buffer, 0, 2048))
        source.close()
        source.close() // the platform closes twice
        assertFalse(good.failed)
        RandomAccessFile(file, "rw").use { it.seek(2L * C + 9); val b = it.read(); it.seek(2L * C + 9); it.write(b xor 1) } // inside segment 2
        val damaged = store.open(file)
        val damagedSource = damaged.dataSource()
        assertEquals(2048, damagedSource.readAt(0, buffer, 0, 2048))
        try { damagedSource.readAt(P0 + P + 10L, buffer, 0, 2048); fail() } catch (e: IOException) { }
        assertTrue(damaged.failed)
        damagedSource.close()
    }
}
