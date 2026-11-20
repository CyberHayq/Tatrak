package am.cyberhayq.chat.ui.edithistory

import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
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
class EditHistoryScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val start = 1_763_600_000_000L // 2025-11-20

    private val versions =
        listOf(
            EditVersion("Meet at 6 near the Cascade", info = false, timestamp = start),
            EditVersion("Meet at *7* near the Cascade\n> bring the tickets\nok?", info = false, timestamp = start + 60_000),
            EditVersion("/me will be late, start without me", info = false, meNick = "Ani", timestamp = start + 300_000),
            EditVersion("Unable to decrypt OMEMO message", info = true, timestamp = start + 600_000),
        )

    private fun show(dark: Boolean = false, style: TatrakStyle = TatrakStyle.TELEGRAM, onBack: () -> Unit = {}) =
        compose.setContent {
            TatrakTheme(darkTheme = dark, style = style, accent = TatrakStyle.effectiveAccent(style, null)) { EditHistoryScreen(versions, onBack) }
        }

    @Test
    fun history() {
        show()
        compose.onNodeWithText("Ani will be late, start without me").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/edit_history.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun historyDark() {
        show(dark = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/edit_history_dark.png")
    }

    @Test
    fun historySlate() {
        show(style = TatrakStyle.SLATE_SEA)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/edit_history_slate.png")
    }

    @Test
    fun backCloses() {
        var back = 0
        show(onBack = { back++ })
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }
}
