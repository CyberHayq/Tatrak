package am.cyberhayq.chat.ui.chat

import android.app.Application
import eu.siacs.conversations.ui.isStickerPack
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class StickerPackTest {

    @Test
    fun ourPackJsonReadsBack() {
        val json = StickerStore.packJson("Ani's stickers", listOf("https://up.example/a.webp", "https://up.example/b.webp"))
        assertEquals("Ani's stickers" to listOf("https://up.example/a.webp", "https://up.example/b.webp"), StickerStore.parsePack(json))
    }

    @Test
    fun onlyHttpUrlsAreKept() {
        val json = """[{"pack":"../../x","name":"../evil","url":"file:///data/x"},{"pack":"P","url":"https://h/1.png"}]"""
        // the traversal-looking name is only a label; paths never come from the JSON
        assertEquals("P" to listOf("https://h/1.png"), StickerStore.parsePack(json))
        assertNull(StickerStore.parsePack("""[{"url":"javascript:1"}]"""))
        assertNull(StickerStore.parsePack("not json"))
    }

    @Test
    fun recognisesPackLinks() {
        assertTrue(isStickerPack("https://up.example/x/y/tatrak-stickers.json".toHttpUrl()))
        assertTrue(isStickerPack("https://stickers.cheogram.com/index.json".toHttpUrl()))
        assertFalse(isStickerPack("https://up.example/x/report.json".toHttpUrl()))
    }

    @Test
    fun onlyABareLinkBecomesAPackCard() {
        assertEquals(
            "https://up.example/x/tatrak-stickers.json",
            StickerStore.packLink("  https://up.example/x/tatrak-stickers.json\n").toString(),
        )
        assertNull(StickerStore.packLink("look: https://up.example/x/tatrak-stickers.json"))
        assertNull(StickerStore.packLink("https://up.example/x/report.json"))
        assertNull(StickerStore.packLink(null))
    }
}
