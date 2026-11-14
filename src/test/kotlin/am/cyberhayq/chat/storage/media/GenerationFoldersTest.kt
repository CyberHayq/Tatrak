package am.cyberhayq.chat.storage.media

import am.cyberhayq.chat.storage.JvmDurable
import am.cyberhayq.chat.ui.chat.StickerPack
import am.cyberhayq.chat.ui.chat.StickerStore
import am.cyberhayq.chat.ui.chat.Wallpapers
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Photo wallpapers and sticker packs in per-generation folders (design item 50). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class GenerationFoldersTest {
    private val context = RuntimeEnvironment.getApplication()
    private val g0 = "9a1c0c0e-58f1-4d7a-b7c2-5f6d3e2a4b99" // an older generation, its key imported
    private val g1 = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11" // the active one
    private val g2 = "c0ffee00-1234-4abc-8def-001122334455" // its key is not in the active database
    private val keys = mapOf(g0 to ByteArray(32) { 10 }, g1 to ByteArray(32) { 11 }, g2 to ByteArray(32) { 12 })

    private fun store(active: String, held: List<String>) =
        EncryptedMediaStore(context.filesDir, context.cacheDir, object : EncryptedMediaStore.KeySource {
            override fun generation() = active
            override fun read() = MediaKeyTable.Keys(active, held.associateWith { keys.getValue(it) })
        })

    /** [name] as generation [generation] wrote it while it was active. */
    private fun write(generation: String, area: MediaArea, name: String, bytes: ByteArray) {
        store(generation, listOf(generation)).create(area, name).run { outputStream.write(bytes); commit() }
    }

    private fun activate() = EncryptedMediaStore.install(store(g1, listOf(g1, g0)))

    private fun wallpapers(generation: String) = File(context.filesDir, "wallpapers/$generation")

    private fun stickers(generation: String) = File(context.filesDir, "Stickers/$generation")

    private fun png(color: Int) =
        ByteArrayOutputStream().also {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()

    @Before fun setUp() { JvmDurable.install() }

    @After fun tearDown() {
        EncryptedMediaStore.install(null)
        JvmDurable.uninstall()
    }

    @Test fun theActiveGenerationsWallpaperFirstElseAnotherReadableOneNoneWithoutAKey() {
        write(g0, MediaArea.WALLPAPERS, "a.jpg", png(0xff336699.toInt()))
        write(g2, MediaArea.WALLPAPERS, "b.jpg", png(0xff336699.toInt()))
        activate()
        assertEquals(File(wallpapers(g0), "a.jpg"), Wallpapers.photo(context, "photo:a.jpg"))
        assertNull(Wallpapers.photo(context, "photo:b.jpg"))
        write(g1, MediaArea.WALLPAPERS, "a.jpg", png(0xff336699.toInt()))
        val photo = Wallpapers.photo(context, "photo:a.jpg")
        assertEquals(File(wallpapers(g1), "a.jpg"), photo)
        assertEquals(0xff336699.toInt(), Wallpapers.decode(photo!!)!!.getPixel(4, 4))
        // a damaged file shows nothing (the chat background), no crash
        write(g1, MediaArea.WALLPAPERS, "c.jpg", png(0xff336699.toInt()))
        File(wallpapers(g1), "c.jpg").apply { writeBytes(readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }) }
        assertNull(Wallpapers.decode(File(wallpapers(g1), "c.jpg")))
        // the names are kept in the settings without a generation
        assertNull(Wallpapers.photo(context, "photo:$g1/a.jpg"))
    }

    @Test fun unusedWallpapersAreDeletedOnlyInTheFolderGiven() {
        write(g0, MediaArea.WALLPAPERS, "old.jpg", png(1))
        write(g1, MediaArea.WALLPAPERS, "old.jpg", png(1))
        write(g1, MediaArea.WALLPAPERS, "kept.jpg", png(2))
        activate()
        Wallpapers.deleteUnused(wallpapers(g1), setOf("kept.jpg"))
        assertEquals(listOf("kept.jpg"), wallpapers(g1).list()?.toList())
        assertTrue(File(wallpapers(g0), "old.jpg").isFile)
    }

    @Test fun packsOfEveryReadableGenerationAreMergedADuplicateShownOnce() = runBlocking {
        write(g0, MediaArea.STICKERS, "aaaa.webp", byteArrayOf(1))
        write(g1, MediaArea.STICKERS, "aaaa.webp", byteArrayOf(1))
        write(g0, MediaArea.STICKERS, "bbbb.webp", byteArrayOf(2))
        for (generation in listOf(g0, g1)) {
            write(generation, MediaArea.STICKERS, "pack-p1/cccc.webp", byteArrayOf(3))
            write(generation, MediaArea.STICKERS, "pack-p1/name.txt", "Cats".toByteArray())
        }
        write(g2, MediaArea.STICKERS, "eeee.webp", byteArrayOf(5))
        write(g2, MediaArea.STICKERS, "pack-p2/dddd.webp", byteArrayOf(4))
        activate()
        StickerStore.load(context)
        val packs = StickerStore.stickers.value
        assertEquals(setOf(File(stickers(g1), "aaaa.webp"), File(stickers(g0), "bbbb.webp")), packs[0].files.toSet())
        assertEquals(listOf("pack-p1" to "Cats"), packs.drop(1).map { it.id to it.name })
        assertEquals(listOf(File(stickers(g1), "pack-p1/cccc.webp")), packs[1].files)

        // deleting removes every readable copy, so none comes back; the unreadable generation stays
        StickerStore.delete(context, File(stickers(g1), "aaaa.webp"))
        StickerStore.deletePack(context, packs[1])
        assertFalse(File(stickers(g0), "aaaa.webp").exists() || File(stickers(g1), "aaaa.webp").exists())
        assertFalse(File(stickers(g0), "pack-p1").exists() || File(stickers(g1), "pack-p1").exists())
        assertTrue(File(stickers(g2), "eeee.webp").isFile && File(stickers(g2), "pack-p2/dddd.webp").isFile)
        StickerStore.load(context)
        assertEquals(listOf(File(stickers(g0), "bbbb.webp")), StickerStore.stickers.value.single().files)
    }

    @Test fun addedStickersAndWallpapersAreEncryptedInTheActiveGenerationsFolder() = runBlocking {
        activate()
        assertEquals(1, StickerStore.addPack(context, "Dogs", listOf(png(0xff00ff00.toInt()))))
        val pack = StickerStore.stickers.value[1]
        assertEquals("Dogs", pack.name)
        assertEquals(stickers(g1), pack.files.single().parentFile?.parentFile)
        assertEquals(0xff00ff00.toInt(), EncryptedMediaStore.get().decodeBitmap(pack.files.single(), null)!!.getPixel(4, 4))
        val picked = File(context.cacheDir, "picked.png").apply { writeBytes(png(0xff0000ff.toInt())) }
        val spec = Wallpapers.importPhoto(context, Uri.fromFile(picked))!!
        assertEquals(wallpapers(g1), Wallpapers.photo(context, spec)?.parentFile)
        picked.delete()
        // nothing plain: every file in both folders starts with the store's magic
        val written = listOf(File(context.filesDir, "Stickers"), File(context.filesDir, "wallpapers")).flatMap { it.walk().filter(File::isFile).toList() }
        assertEquals(3, written.size)
        for (file in written) assertEquals("TATRKENC", String(file.readBytes(), 0, 8))
    }

    @Test fun anEmptyOrPartialActiveCopyOfAPackHidesNothingOfAnOlderFullOne() = runBlocking {
        write(g0, MediaArea.STICKERS, "pack-p1/aaaa.webp", byteArrayOf(1))
        write(g0, MediaArea.STICKERS, "pack-p1/bbbb.webp", byteArrayOf(2))
        write(g0, MediaArea.STICKERS, "pack-p1/name.txt", "Cats".toByteArray())
        File(stickers(g1), "pack-p1").mkdirs() // a re-add whose writes failed: an empty folder
        write(g1, MediaArea.STICKERS, "pack-p1/aaaa.webp", byteArrayOf(1))
        activate()
        StickerStore.load(context)
        val pack = StickerStore.stickers.value.drop(1).single()
        assertEquals("Cats", pack.name)
        assertEquals(setOf(File(stickers(g1), "pack-p1/aaaa.webp"), File(stickers(g0), "pack-p1/bbbb.webp")), pack.files.toSet())
    }

    @Test fun aLinkAboveTheStoreFoldersChangesNothing() = runBlocking {
        // on a device filesDir is /data/user/0/<pkg>/files, a link to /data/data/<pkg>/files
        val real = File(context.filesDir.parentFile, "real-files")
        val linked = File(context.filesDir.parentFile, "linked-files")
        java.nio.file.Files.createDirectories(File(real, "Stickers/$g1").toPath())
        java.nio.file.Files.createSymbolicLink(linked.toPath(), real.toPath())
        val app = object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = linked
        }
        EncryptedMediaStore.install(EncryptedMediaStore(linked, context.cacheDir, object : EncryptedMediaStore.KeySource {
            override fun generation() = g1
            override fun read() = MediaKeyTable.Keys(g1, mapOf(g1 to keys.getValue(g1)))
        }))
        assertEquals(1, StickerStore.addPack(app, "Dogs", listOf(png(0xff00ff00.toInt()))))
        assertEquals(1, StickerStore.stickers.value.drop(1).single().files.size)
        StickerStore.deletePack(app, StickerStore.stickers.value[1])
        StickerStore.load(app)
        assertEquals(1, StickerStore.stickers.value.size)
    }

    @Test fun deletingNeverReachesThroughALinkIntoAnotherFolder() = runBlocking {
        write(g2, MediaArea.STICKERS, "pack-p2/dddd.webp", byteArrayOf(4))
        write(g2, MediaArea.WALLPAPERS, "w.jpg", byteArrayOf(5))
        write(g1, MediaArea.STICKERS, "eeee.webp", byteArrayOf(6))
        java.nio.file.Files.createSymbolicLink(File(stickers(g1), "pack-p2").toPath(), File(stickers(g2), "pack-p2").toPath())
        java.nio.file.Files.createDirectories(File(context.filesDir, "wallpapers").toPath())
        java.nio.file.Files.createSymbolicLink(wallpapers(g1).toPath(), wallpapers(g2).toPath())
        activate()
        StickerStore.load(context)
        assertEquals(1, StickerStore.stickers.value.size) // the linked pack is not listed
        StickerStore.deletePack(context, StickerPack("pack-p2", "x", emptyList()))
        Wallpapers.deleteUnused(wallpapers(g1), emptySet())
        assertTrue(File(stickers(g2), "pack-p2/dddd.webp").isFile)
        assertTrue(File(wallpapers(g2), "w.jpg").isFile)
    }
}
