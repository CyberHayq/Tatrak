package am.cyberhayq.chat.ui.newchat

import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.hasSetTextAction
import org.junit.Assert.assertEquals
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
class NewChatScreenshotTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun restoresSearchToActions() {
        var query = ""
        val actions = object : NewChatActions by NoActions {
            override fun search(needle: String) { query = needle }
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { NewChatScreen(state, actions) }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("missing")
        compose.runOnIdle { query = "" }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals("missing", query) }
    }

    private object NoActions : NewChatActions {
        override fun back() {}

        override fun search(needle: String) {}

        override fun searchDone() {}

        override fun row(row: NewChatRow) {}

        override fun openContact(key: String) {}

        override fun openGroup(key: String) {}

        override fun contactMenu(key: String, item: NewChatMenu) {}

        override fun groupMenu(key: String, item: NewChatMenu) {}

        override fun toggleHideOffline() {}
    }

    private val state =
        NewChatState(
            contacts =
                listOf(
                    NewChatEntry("a", "Ani", "online", online = true),
                    NewChatEntry("b", "Bob", "bob@tatrak.am"),
                    NewChatEntry("d", "Davit", "davit@example.org"),
                ),
            groups = listOf(NewChatEntry("t", "Tatrak team", "team@conference.tatrak.am")),
            loaded = true,
        )

    @Test
    fun list() {
        compose.setContent { TatrakTheme(darkTheme = false) { NewChatScreen(state, NoActions) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/new_chat.png")
    }

    @Test
    fun contactsOnly() {
        var call: String? = null
        val actions = object : NewChatActions by NoActions {
            override fun contactMenu(key: String, item: NewChatMenu) {
                if (item == NewChatMenu.CALL) call = key
            }
        }
        val contacts = state.copy(contacts = listOf(state.contacts.first().copy(menu = listOf(NewChatMenu.CALL))))
        compose.setContent { TatrakTheme(darkTheme = false) { NewChatScreen(contacts, actions, contactsOnly = true) } }
        compose.onNodeWithText("Tatrak team").assertDoesNotExist()
        compose.onNodeWithContentDescription("Audio call").performClick()
        assertEquals("a", call)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/contacts.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun searchingDark() {
        compose.setContent {
            TatrakTheme(darkTheme = true) {
                NewChatScreen(state.copy(groups = emptyList()), NoActions, initialSearch = "a")
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/new_chat_search_dark.png")
    }

    @Test
    fun nothingFound() {
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                NewChatScreen(NewChatState(loaded = true), NoActions, initialSearch = "zzz")
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/new_chat_empty.png")
    }
}
