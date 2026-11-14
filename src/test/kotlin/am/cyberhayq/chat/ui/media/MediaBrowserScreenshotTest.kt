package am.cyberhayq.chat.ui.media

import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import android.graphics.Bitmap
import eu.siacs.conversations.R

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MediaBrowserScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private object NoActions : MediaActions {
        override fun back() {}

        override fun open(key: String) {}

        override fun share(keys: Set<String>) {}

        override fun save(keys: Set<String>) {}

        override fun delete(keys: Set<String>) {}

        override fun canSave(keys: Set<String>) = true

        override suspend fun thumbnail(key: String, sizePx: Int): Bitmap? =
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
                eraseColor(listOf(0xFF8E7CC3.toInt(), 0xFF6FA8DC.toInt(), 0xFF93C47D.toInt())[key.hashCode().mod(3)])
            }
    }

    private val state =
        MediaState(
            items =
                (1..8).map { MediaItem("m$it", MediaTab.MEDIA, "IMG_$it.jpg", "", R.drawable.ic_image_48dp, video = it == 2, thumbnail = true) } +
                    listOf(
                        MediaItem("f1", MediaTab.FILES, "report.pdf", "1.2 MB · 30.10.26", R.drawable.ic_description_48dp),
                        MediaItem("f2", MediaTab.FILES, "photos.zip", "34 MB · 12.10.26", R.drawable.ic_archive_48dp),
                    ),
            loaded = true,
        )

    @Test
    fun grid() {
        compose.setContent { TatrakTheme(darkTheme = false) { MediaBrowserScreen(state, NoActions) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/media.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun emptyDark() {
        compose.setContent { TatrakTheme(darkTheme = true) { MediaBrowserScreen(MediaState(loaded = true), NoActions) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/media_empty_dark.png")
    }
}
