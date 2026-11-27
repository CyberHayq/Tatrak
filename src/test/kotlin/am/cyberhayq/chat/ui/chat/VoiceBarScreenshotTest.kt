package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.chatlist.ChatListScreen
import am.cyberhayq.chat.ui.chatlist.ChatListState
import am.cyberhayq.chat.ui.chatlist.NoListActions
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** The voice mini bar (issue #7) under the chat list's and the chat's top bars; images in build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class VoiceBarScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val playing = VoiceBarState("chat-1", "voice-1", "Alice Wonderland", true, 7_000, 19_000, 1.5f)

    @Test fun listPlainLight() = list("voice_bar_list_plain_light", dark = false, floating = false)

    @Test @Config(qualifiers = "+night")
    fun listPlainDark() = list("voice_bar_list_plain_dark", dark = true, floating = false)

    @Test fun listFloatingLight() = list("voice_bar_list_floating_light", dark = false, floating = true)

    @Test @Config(qualifiers = "+night")
    fun listFloatingDark() = list("voice_bar_list_floating_dark", dark = true, floating = true)

    @Test fun chatPlainLight() = chat("voice_bar_chat_plain_light", dark = false, floating = false)

    @Test @Config(qualifiers = "+night")
    fun chatFloatingDark() = chat("voice_bar_chat_floating_dark", dark = true, floating = true)

    /** Paused, no duration known yet: "--:--". */
    @Test fun chatPausedNoDuration() =
        chat("voice_bar_chat_paused_no_duration", dark = false, floating = false, bar = playing.copy(playing = false, positionMs = 0, durationMs = 0, speed = 1f)) {
            compose.onNodeWithText("0:00 / --:--").assertExists()
        }

    @Test @Config(qualifiers = "+ar")
    fun listRtl() =
        list("voice_bar_list_rtl", dark = false, floating = false) { content ->
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
        }

    @Test fun listLargeFont() =
        list("voice_bar_list_large_font", dark = false, floating = true) { content ->
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) { content() }
        }

    private fun list(name: String, dark: Boolean, floating: Boolean, wrap: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
        compose.setContent {
            TatrakTheme(darkTheme = dark, floatingBars = floating) {
                wrap { ChatListScreen(ChatListState(loaded = true), NoListActions, voiceBar = MutableStateFlow(playing), voiceBarActions = NoVoiceBarActions) }
            }
        }
        compose.onNodeWithText("Voice message · Alice Wonderland").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun chat(name: String, dark: Boolean, floating: Boolean, bar: VoiceBarState = playing, check: () -> Unit = {}) {
        compose.setContent {
            TatrakTheme(darkTheme = dark, floatingBars = floating) {
                ChatScreen(
                    state = ChatState(loaded = true, conversationUuid = "chat-1", header = ChatHeader(title = "Alice Wonderland")),
                    input = TextFieldValue(),
                    onInputChange = {},
                    reply = null,
                    actions = NoChatActions(),
                    voice = MutableStateFlow(VoicePlayback()),
                    recording = MutableStateFlow(VoiceRecording()),
                    voiceBar = MutableStateFlow(bar),
                    voiceBarActions = NoVoiceBarActions,
                )
            }
        }
        check()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
