package am.cyberhayq.chat.ui

import am.cyberhayq.chat.ui.chat.ChatHeader
import am.cyberhayq.chat.ui.chat.ChatItem
import am.cyberhayq.chat.ui.chat.ChatScreen
import am.cyberhayq.chat.ui.chat.ChatScreenshotTest
import am.cyberhayq.chat.ui.chat.ChatState
import am.cyberhayq.chat.ui.chat.GroupPosition
import am.cyberhayq.chat.ui.chat.MessageFormatter
import am.cyberhayq.chat.ui.chat.MessageItem
import am.cyberhayq.chat.ui.chat.TextPalette
import am.cyberhayq.chat.ui.chat.VoicePlayback
import am.cyberhayq.chat.ui.chat.VoiceRecording
import am.cyberhayq.chat.ui.chat.currentTextPalette
import am.cyberhayq.chat.ui.chatlist.ChatListItem
import am.cyberhayq.chat.ui.chatlist.ChatListScreen
import am.cyberhayq.chat.ui.chatlist.ChatListState
import am.cyberhayq.chat.ui.chatlist.NoListActions
import am.cyberhayq.chat.ui.state.LocalCompositionProbe
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * The smoothness property the old ListView/notifyDataSetChanged UI lacked: when a message
 * arrives, the service snapshot is rebuilt from scratch (every item is a new, equal instance),
 * and Compose must still only recompose the rows whose content actually changed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RecompositionTest {

    @get:Rule val compose = createComposeRule()

    private val compositions = mutableMapOf<String, Int>()
    private val probe: (String) -> Unit = { compositions.merge(it, 1, Int::plus) }

    @Test
    fun chatListRecomposesOnlyTheChatThatGotAMessage() {
        val now = System.currentTimeMillis()
        val chats = (0 until 40).map { chatListItem(it, now - it * 600_000L) }
        var state by mutableStateOf(ChatListState(loaded = true, chats = chats))
        compose.setContent {
            CompositionLocalProvider(LocalCompositionProbe provides probe) {
                TatrakTheme(darkTheme = false) { ChatListScreen(state, NoListActions) }
            }
        }
        compose.waitForIdle()
        // composed is not the same as on screen: LazyColumn prefetches the next row, and the pager
        // keeps the neighbouring tab composed
        val visible = compositions.keys.filter { isDisplayed(it.replace("chat-", "Chat ")) }.toSet()
        assertTrue("rows on screen: $visible", visible.size >= 8)
        compositions.clear()

        // a new message in chat-6 (a group, so only on the All tab) moves it to the top; the rest
        // is rebuilt but unchanged
        val rebuilt = chats.map { it.copy() }.toMutableList()
        val bumped = rebuilt.removeAt(6).copy(preview = "new message", unreadCount = 1, timestamp = now)
        rebuilt.add(0, bumped)
        state = ChatListState(loaded = true, chats = rebuilt)
        compose.waitForIdle()

        // rows entering the screen or prefetched are composed for the first time; that is fine.
        // what matters is that rows already on screen were left alone unless they changed
        assertEquals(mapOf("chat-6" to 1), compositions.filterKeys { it in visible })
    }

    @Test
    fun chatFeedRecomposesOnlyTheNewMessageAndItsNeighbour() {
        var palette: TextPalette? = null
        val voice = MutableStateFlow(VoicePlayback())
        var state by mutableStateOf<ChatState?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalCompositionProbe provides probe) {
                TatrakTheme(darkTheme = false) {
                    val current = currentTextPalette()
                    SideEffect { palette = current }
                    state?.let {
                        ChatScreen(
                            state = it,
                            input = TextFieldValue(),
                            onInputChange = {},
                            reply = null,
                            actions = ChatScreenshotTest.FakeActions,
                            voice = voice,
                            recording = MutableStateFlow(VoiceRecording()),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val samples = ChatScreenshotTest.Samples(palette!!)
        // newest first, like ChatSnapshot; the peer's last message closes its group
        val history =
            (0 until 40).map { i ->
                val outgoing = (i / 3) % 2 == 0
                val position =
                    when (i % 3) {
                        0 -> GroupPosition.LAST
                        1 -> GroupPosition.MIDDLE
                        else -> GroupPosition.FIRST
                    }
                samples.text("m$i", outgoing, position, "Message number $i with *some* text", "10:${i % 60}")
            }
        state = chatState(history)
        compose.waitForIdle()
        val visible = compositions.keys.toSet()
        assertTrue(visible.size >= 8)
        compositions.clear()

        // incoming message after the peer's group: the old LAST becomes MIDDLE (loses its tail)
        val previousNewest = (history.first() as MessageItem)
        val rebuilt: List<ChatItem> =
            listOf(samples.text("new", previousNewest.isOutgoing, GroupPosition.LAST, "Fresh", "11:00")) +
                listOf(previousNewest.copy(position = GroupPosition.MIDDLE)) +
                history.drop(1).map { (it as MessageItem).copy() }
        state = chatState(rebuilt)
        compose.waitForIdle()

        assertEquals(mapOf(previousNewest.key to 1), compositions.filterKeys { it in visible })
        assertEquals(1, compositions["new"])
    }

    @Test
    fun messageFormattingCost() {
        // JVM numbers are much faster than a phone; this only shows the work is small and that
        // it runs once per message (ChatSnapshot caches it), never during scrolling
        val bodies =
            (0 until 2000).map {
                "Message $it with *bold*, _italic_, `code` and a link https://example.am/$it\n> quoted line $it"
            }
        repeat(200) { MessageFormatter.format(bodies[it], 0xFF000000.toInt(), 0xFF888888.toInt()) }
        val start = System.nanoTime()
        bodies.forEach { MessageFormatter.format(it, 0xFF000000.toInt(), 0xFF888888.toInt()) }
        val perMessageMicros = (System.nanoTime() - start) / 1000.0 / bodies.size
        println("formatting: %.1f µs per message".format(perMessageMicros))
        assertTrue("formatting took $perMessageMicros µs per message", perMessageMicros < 2000)
    }

    private fun isDisplayed(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().any { node ->
            node.layoutInfo.isPlaced && node.boundsInRoot.height > 0f
        }

    private fun chatState(items: List<ChatItem>) =
        ChatState(loaded = true, conversationUuid = "c1", header = ChatHeader("Ani"), items = items)

    private fun chatListItem(index: Int, timestamp: Long) =
        ChatListItem(
            uuid = "chat-$index",
            title = "Chat $index",
            isGroup = index % 3 == 0,
            preview = "Last message in chat $index",
            previewSender = if (index % 3 == 0) "Aram" else null,
            isDraft = false,
            isServicePreview = false,
            isTyping = false,
            timestamp = timestamp,
            unreadCount = index % 4,
            isMuted = index % 5 == 0,
            isPinned = false,
            outgoingStatus = null,
            isOnline = index % 2 == 0,
            isArchived = false,
            avatar = null,
        )
}
