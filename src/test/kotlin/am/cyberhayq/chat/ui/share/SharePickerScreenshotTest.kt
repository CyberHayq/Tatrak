package am.cyberhayq.chat.ui.share

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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SharePickerScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private object NoActions : ShareActions {
        override fun back() {}

        override fun pick(key: String) {}

        override fun newChat() {}
    }

    private val state =
        ShareState(
            targets =
                listOf(
                    ShareTarget("1", "Ani", "ani@tatrak.am"),
                    ShareTarget("2", "Tatrak team", "team@conference.tatrak.am"),
                    ShareTarget("3", "Davit", "davit@example.org"),
                ),
            loaded = true,
        )

    @Test
    fun picker() {
        compose.setContent { TatrakTheme(darkTheme = false) { SharePickerScreen(state, NoActions) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/share.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun emptyDark() {
        compose.setContent { TatrakTheme(darkTheme = true) { SharePickerScreen(ShareState(loaded = true), NoActions) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/share_empty_dark.png")
    }
}
