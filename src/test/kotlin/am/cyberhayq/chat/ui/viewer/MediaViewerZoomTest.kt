package am.cyberhayq.chat.ui.viewer

import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** A pan of a zoomed photo that reaches the side edge midway stops there; a new drag from the edge turns the page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MediaViewerZoomTest {
    @get:Rule val compose = createComposeRule()

    private object Fake : ViewerActions {
        override fun close() {}

        override fun share(key: String) {}

        override fun save(key: String) {}

        override fun openWith(key: String) {}

        override fun canSave(key: String) = true

        override suspend fun info(key: String) = ViewerInfo("title-$key", "")

        override suspend fun image(key: String, maxPx: Int): ViewerImage? =
            ViewerImage.Still(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888))

        override fun videoFile(key: String): java.io.File? = null
    }

    private fun panLeft(steps: Int) {
        compose.onRoot().performTouchInput {
            down(center)
            repeat(steps) {
                moveBy(Offset(-60f, 0f))
                advanceEventTime(16)
            }
            up()
        }
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
    }

    @Test
    fun panReachingTheEdgeKeepsThePage() {
        val items = listOf(ViewerItem("a"), ViewerItem("b"), ViewerItem("c"))
        compose.setContent { TatrakTheme(darkTheme = true) { MediaViewerScreen(ViewerState(items, start = 1), Fake) } }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        // 2.5x on a 1233 px wide page leaves 925 px of pan to the edge; 1500 px overshoots it midway
        panLeft(25)
        compose.onNodeWithText("title-b").assertExists()
        panLeft(10)
        compose.onNodeWithText("title-c").assertExists()
    }
}
