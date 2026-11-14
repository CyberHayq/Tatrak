package am.cyberhayq.chat.ui.chat

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class WallpapersTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun patterns() {
        assertEquals("preset:sky#leaves", Wallpapers.withPattern("preset:sky", WallpaperPattern.LEAVES))
        assertEquals("preset:sky#bubbles", Wallpapers.withPattern("preset:sky#leaves", WallpaperPattern.BUBBLES))
        assertEquals("preset:sky", Wallpapers.withPattern("preset:sky#leaves", WallpaperPattern.NONE))
        assertEquals("default#sparkles", Wallpapers.withPattern(null, WallpaperPattern.SPARKLES))
        assertEquals("sky", Wallpapers.preset("preset:sky#leaves")?.id)
        assertEquals(WallpaperPattern.LEAVES, Wallpapers.pattern("preset:sky#leaves"))
        assertEquals(WallpaperPattern.NONE, Wallpapers.pattern("preset:sky"))
        assertEquals(WallpaperPattern.NONE, Wallpapers.pattern("preset:sky#gone"))
        assertEquals(WallpaperPattern.NONE, Wallpapers.pattern("photo:a.jpg"))
        for (pattern in WallpaperPattern.entries) assertEquals(pattern, Wallpapers.pattern(Wallpapers.withPattern("preset:ocean", pattern)))
    }

    @Test
    fun presetsRoundTrip() {
        for (preset in Wallpapers.presets) {
            assertEquals(preset, Wallpapers.preset(Wallpapers.presetSpec(preset)))
        }
        // unknown or missing specs fall back to the classic background
        assertEquals("default", Wallpapers.preset(null)?.id)
        assertEquals("default", Wallpapers.preset("preset:gone")?.id)
        assertNull(Wallpapers.preset("photo:abc.jpg"))
    }

    @Test
    fun photosStayInTheWallpaperFolder() {
        // found ones: GenerationFoldersTest
        assertNull(Wallpapers.photo(context, "photo:../databases/history"))
        assertNull(Wallpapers.photo(context, "photo:.hidden"))
        assertNull(Wallpapers.photo(context, "preset:sky"))
    }

    @Test
    fun unusedPhotosAreDeleted() {
        val folder = File(context.cacheDir, "wallpaper-test").apply { mkdirs() }
        File(folder, "kept.jpg").writeText("a")
        File(folder, "old.jpg").writeText("b")
        Wallpapers.deleteUnused(folder, setOf("kept.jpg"))
        assertEquals(listOf("kept.jpg"), folder.list()?.toList())
    }
}
