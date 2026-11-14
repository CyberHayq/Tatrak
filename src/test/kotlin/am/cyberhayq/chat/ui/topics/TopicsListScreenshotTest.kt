package am.cyberhayq.chat.ui.topics

import am.cyberhayq.chat.ui.theme.Accent
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
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

/** The topics list has no data source yet; this is its only user. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TopicsListScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val topics =
        listOf(
            Topic("general", "General", "Ani", "See you tomorrow at 10?", "09:12", 3, pinned = true),
            Topic("release", "Release notes", "Davit", "The changelog is at https://example.am/changes", "Yesterday", 0, pinned = true),
            Topic("random", "Random", "Mariam", "Ha, that is a very long message that must be cut with an ellipsis at the end of the line", "Mon", 120, pinned = false),
        )

    @Test
    fun topicsSlateLight() = shot("topics_slate_light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun topicsSlateDark() = shot("topics_slate_dark", dark = true)

    private fun shot(name: String, dark: Boolean) {
        var opened: String? = null
        compose.setContent {
            TatrakTheme(darkTheme = dark, style = TatrakStyle.SLATE_SEA, accent = Accent.SEA) {
                TopicsList(topics, onOpen = { opened = it }, modifier = Modifier.background(MaterialTheme.colorScheme.background))
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        compose.onNodeWithText("Release notes").performClick()
        assertEquals("release", opened)
    }
}
