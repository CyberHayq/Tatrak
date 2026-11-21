package am.cyberhayq.chat.ui.search

import am.cyberhayq.chat.ui.theme.TatrakTheme
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.BarBlur
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
class MessageSearchScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private object NoActions : SearchActions {
        override fun back() {}

        override fun search(text: String) {}

        override fun open(key: String) {}

        override fun menu(key: String, item: SearchMenu) {}
    }

    private val now = System.currentTimeMillis()

    private val state =
        SearchState(
            results =
                listOf(
                    SearchResult("1", "Ani", "", "Let's meet at the cafe on Abovyan street", now),
                    SearchResult("2", "Tatrak team", "Bob: ", "The cafe opens at nine, see you there", now - 86_400_000L * 2),
                    SearchResult("3", "Davit", "", "Which CAFE did you mean?", now - 86_400_000L * 40),
                ),
            terms = listOf("cafe"),
            searched = true,
        )

    @Test
    fun results() {
        compose.setContent { TatrakTheme(darkTheme = false) { MessageSearchScreen(state, NoActions, initialText = "cafe") } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/search.png")
    }

    /** Opened from a chat: the hint names it, the caption says only it is searched. */
    @Test
    fun inOneChatEmpty() {
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                MessageSearchScreen(SearchState(inChat = true, chatName = "Ani"), NoActions)
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/search_in_chat.png")
    }

    @Test
    fun allChatsEmpty() {
        compose.setContent { TatrakTheme(darkTheme = false) { MessageSearchScreen(SearchState(), NoActions) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/search_all_chats.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun nothingFoundDark() {
        compose.setContent {
            TatrakTheme(darkTheme = true) {
                MessageSearchScreen(SearchState(searched = true), NoActions, initialText = "zzz")
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/search_empty_dark.png")
    }

    /** Floating bars (Glass): the top bar in a capsule over the list; frosted with the rows scrolled under it. */
    @Test
    fun floatingLight() = floating(dark = false, BarBlur.OFF, "search_floating_light")

    @Test
    @Config(qualifiers = "+night")
    fun floatingDark() = floating(dark = true, BarBlur.OFF, "search_floating_dark")

    @Test
    fun floatingFrosted() = floating(dark = false, BarBlur.FROSTED, "search_floating_frosted", scrolled = true)

    private fun floating(dark: Boolean, blur: BarBlur, file: String, scrolled: Boolean = false) {
        compose.setContent {
            TatrakTheme(darkTheme = dark, style = TatrakStyle.GLASS, barBlur = blur) { MessageSearchScreen(if (scrolled) state.copy(results = (1..12).map { i -> state.results[i % 3].copy(key = "k$i") }) else state, NoActions, initialText = "cafe") }
        }
        if (scrolled) {
            compose.onRoot().performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 100f, durationMillis = 800) }
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file.png")
    }
}
