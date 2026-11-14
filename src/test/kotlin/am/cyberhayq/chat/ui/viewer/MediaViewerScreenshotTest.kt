package am.cyberhayq.chat.ui.viewer

import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import eu.siacs.conversations.ui.MediaViewerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MediaViewerScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private object Fake : ViewerActions {
        override fun close() {}

        override fun share(key: String) {}

        override fun save(key: String) {}

        override fun openWith(key: String) {}

        override fun canSave(key: String) = true

        override suspend fun info(key: String) = ViewerInfo("Juliet Capulet", "30 Oct 2026, 18:42")

        override suspend fun image(key: String, maxPx: Int): ViewerImage? =
            if (key == "gone") null
            else ViewerImage.Still(
                Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(if (key == "v") 0xFF6FA8DC.toInt() else 0xFF93C47D.toInt())
                    Canvas(this).drawCircle(200f, 150f, 90f, Paint().apply { color = 0xFFFFD966.toInt() })
                }
            )

        override fun videoFile(key: String): java.io.File? = null
    }

    private val items = listOf(ViewerItem("a"), ViewerItem("b"), ViewerItem("v", video = true))

    @Test
    fun photo() {
        compose.setContent { TatrakTheme(darkTheme = true) { MediaViewerScreen(ViewerState(items, start = 1), Fake) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/viewer_photo.png")
    }

    @Test
    fun failed() {
        compose.setContent {
            TatrakTheme(darkTheme = true) { MediaViewerScreen(ViewerState(listOf(ViewerItem("gone")), start = 0), Fake) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/viewer_failed.png")
    }

    @Test
    fun video() {
        compose.setContent { TatrakTheme(darkTheme = true) { MediaViewerScreen(ViewerState(items, start = 2), Fake) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/viewer_video.png")
    }

    @Test
    fun sampleSizeFitsTheLargerSide() {
        assertEquals(1, ViewerImages.sampleSize(1000, 800, 2048))
        assertEquals(2, ViewerImages.sampleSize(4000, 3000, 2048))
        assertEquals(4, ViewerImages.sampleSize(3000, 9000, 2400))
    }

    @Test
    fun onlyPhotosAndVideosOpenInside() {
        assertTrue(MediaViewerActivity.canShow("image/jpeg"))
        assertTrue(MediaViewerActivity.canShow("image/gif"))
        assertTrue(MediaViewerActivity.canShow("video/mp4"))
        assertFalse(MediaViewerActivity.canShow("image/svg+xml"))
        assertFalse(MediaViewerActivity.canShow("application/pdf"))
        assertFalse(MediaViewerActivity.canShow("audio/ogg"))
        assertFalse(MediaViewerActivity.canShow(null))
    }
}
