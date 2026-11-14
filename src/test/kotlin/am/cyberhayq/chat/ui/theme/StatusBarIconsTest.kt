package am.cyberhayq.chat.ui.theme

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class StatusBarIconsTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun lateDisposeKeepsTheNewerScreensChoice() {
        var list by mutableStateOf(false)
        var chat by mutableStateOf(false)
        lateinit var light: () -> Boolean
        compose.setContent {
            val view = LocalView.current
            val controller = WindowCompat.getInsetsController((view.context as Activity).window, view)
            light = { controller.isAppearanceLightStatusBars }
            // the list keeps the activity's value, the chat flips it
            if (list) StatusBarIcons(dark = original)
            if (chat) StatusBarIcons(dark = !original)
        }
        compose.runOnIdle { list = true }
        compose.runOnIdle { assertEquals(original, light()) }
        // chat enters while the list is still on screen, then the list leaves late
        compose.runOnIdle { chat = true }
        compose.runOnIdle { assertEquals(!original, light()) }
        compose.runOnIdle { list = false }
        compose.runOnIdle { assertEquals(!original, light()) }
        // list back on top, then the chat leaves late
        compose.runOnIdle { list = true }
        compose.runOnIdle { chat = false }
        compose.runOnIdle { assertEquals(original, light()) }
        // last one out restores the classic screens' value
        compose.runOnIdle { list = false }
        compose.runOnIdle { assertEquals(original, light()) }
    }

    // what the classic screens use in light mode (the test runs in day mode)
    private val original = true
}
