package am.cyberhayq.chat.ui.share

import am.cyberhayq.chat.ui.theme.TatrakTheme
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.BarBlur
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.text.AnnotatedString
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
                    ShareTarget("1", "Ani", AnnotatedString("ani@tatrak.am")),
                    ShareTarget("2", "Tatrak team", AnnotatedString("team@conference.tatrak.am")),
                    ShareTarget("3", "Davit", AnnotatedString("davit@example.org")),
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

    /** Floating bars (Glass): the top bar in a capsule over the list; frosted with the rows scrolled under it. */
    @Test
    fun floatingLight() = floating(dark = false, BarBlur.OFF, "share_floating_light")

    @Test
    @Config(qualifiers = "+night")
    fun floatingDark() = floating(dark = true, BarBlur.OFF, "share_floating_dark")

    @Test
    fun floatingFrosted() = floating(dark = false, BarBlur.FROSTED, "share_floating_frosted", scrolled = true)

    private fun floating(dark: Boolean, blur: BarBlur, file: String, scrolled: Boolean = false) {
        compose.setContent {
            TatrakTheme(darkTheme = dark, style = TatrakStyle.GLASS, barBlur = blur) { SharePickerScreen(if (scrolled) state.copy(targets = (1..12).map { i -> state.targets[i % 3].copy(key = "k$i") }) else state, NoActions) }
        }
        if (scrolled) {
            compose.onRoot().performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 100f, durationMillis = 800) }
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file.png")
    }
}
