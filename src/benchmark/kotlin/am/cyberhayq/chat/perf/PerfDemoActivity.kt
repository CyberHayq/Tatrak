package am.cyberhayq.chat.perf

import am.cyberhayq.chat.ui.chat.ChatActions
import am.cyberhayq.chat.ui.chat.ChatBanner
import am.cyberhayq.chat.ui.chat.ChatHeader
import am.cyberhayq.chat.ui.chat.ChatItem
import am.cyberhayq.chat.ui.chat.ChatScreen
import am.cyberhayq.chat.ui.chat.ChatState
import am.cyberhayq.chat.ui.chat.ComposerInfo
import am.cyberhayq.chat.ui.chat.DateItem
import am.cyberhayq.chat.ui.chat.DeliveryStatus
import am.cyberhayq.chat.ui.chat.GroupPosition
import am.cyberhayq.chat.ui.chat.MediaInfo
import am.cyberhayq.chat.ui.chat.MessageActions
import am.cyberhayq.chat.ui.chat.MessageFormatter
import am.cyberhayq.chat.ui.chat.MessageItem
import am.cyberhayq.chat.ui.chat.MessageKind
import am.cyberhayq.chat.ui.chat.ReactionChip
import am.cyberhayq.chat.ui.chat.Security
import am.cyberhayq.chat.ui.chat.TextPalette
import am.cyberhayq.chat.ui.chat.VoicePlayback
import am.cyberhayq.chat.ui.chat.VoiceRecording
import am.cyberhayq.chat.ui.chat.currentTextPalette
import am.cyberhayq.chat.ui.chat.toAnnotatedString
import am.cyberhayq.chat.ui.chatlist.ChatListActions
import am.cyberhayq.chat.ui.chatlist.ChatListItem
import am.cyberhayq.chat.ui.chatlist.ChatListScreen
import am.cyberhayq.chat.ui.chatlist.ChatListState
import am.cyberhayq.chat.ui.chatlist.OutgoingStatus
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.input.TextFieldValue
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Benchmark build only: the real chat list and chat screen fed with generated data, so frame
 * times can be measured without an XMPP account (`-e screen list|chat`).
 */
class PerfDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "list"
        setContent {
            TatrakTheme {
                if (screen == "chat") DemoChat() else DemoList()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun DemoList() {
        val state = remember { ChatListState(loaded = true, chats = demoChats(300)) }
        ChatListScreen(state, NoListActions)
    }

    @androidx.compose.runtime.Composable
    private fun DemoChat() {
        val palette = currentTextPalette()
        var state by remember { mutableStateOf(ChatState()) }
        LaunchedEffect(palette) {
            // formatted off the main thread, as ChatSnapshot does
            val items = withContext(Dispatchers.Default) { demoMessages(2000, palette) }
            state =
                ChatState(
                    loaded = true,
                    conversationUuid = "demo",
                    header = ChatHeader("Dev team", "12 participants", isGroup = true),
                    items = items,
                    composer = ComposerInfo(hint = "Message"),
                )
        }
        ChatScreen(
            state = state,
            input = TextFieldValue(),
            onInputChange = {},
            reply = null,
            actions = demoActions,
            voice = voice,
            recording = MutableStateFlow(VoiceRecording()),
        )
    }

    private val voice = MutableStateFlow(VoicePlayback())
    private val demoActions = DemoChatActions()

    private fun demoChats(count: Int): List<ChatListItem> {
        val now = System.currentTimeMillis()
        return (0 until count).map { i ->
            ChatListItem(
                uuid = "chat-$i",
                title = NAMES[i % NAMES.size] + if (i >= NAMES.size) " ${i / NAMES.size}" else "",
                isGroup = i % 4 == 0,
                preview = TEXTS[i % TEXTS.size],
                previewSender = if (i % 4 == 0) NAMES[(i + 3) % NAMES.size] else null,
                isDraft = i % 17 == 0,
                isServicePreview = false,
                isTyping = i % 23 == 0,
                timestamp = now - i * 2_400_000L,
                unreadCount = if (i % 3 == 0) i % 40 else 0,
                isMuted = i % 7 == 0,
                isPinned = i < 3,
                outgoingStatus = if (i % 3 == 1) OutgoingStatus.entries[i % OutgoingStatus.entries.size] else null,
                isOnline = i % 5 == 0,
                isArchived = false,
                avatar = letterAvatar(i),
            )
        }
    }

    private fun demoMessages(count: Int, palette: TextPalette): List<ChatItem> {
        val links = TextLinkStyles(SpanStyle(color = Color(palette.link)))
        val items = ArrayList<ChatItem>(count + count / 40)
        // newest first, as in ChatSnapshot
        for (i in 0 until count) {
            val outgoing = (i / 3) % 2 == 0
            val position =
                when (i % 3) {
                    0 -> GroupPosition.LAST
                    1 -> GroupPosition.MIDDLE
                    else -> GroupPosition.FIRST
                }
            val body = TEXTS[i % TEXTS.size] + if (i % 5 == 0) "\n" + TEXTS[(i + 1) % TEXTS.size] else ""
            val formatted =
                MessageFormatter.format(
                        body,
                        if (outgoing) palette.textOutgoing else palette.textIncoming,
                        if (outgoing) palette.secondaryOutgoing else palette.secondaryIncoming,
                    )
                    .toAnnotatedString(links)
            val image = i % 11 == 0
            items +=
                MessageItem(
                    key = "m$i",
                    day = "Day ${i / 40}",
                    isOutgoing = outgoing,
                    position = position,
                    kind = if (image) MessageKind.IMAGE else MessageKind.TEXT,
                    text = if (image) formatted.subSequence(0, 0) else formatted,
                    time = "%02d:%02d".format((i / 60) % 24, i % 60),
                    status = if (outgoing) DeliveryStatus.READ else DeliveryStatus.NONE,
                    security = Security.ENCRYPTED,
                    senderName = if (!outgoing && position.isStart) NAMES[i % NAMES.size] else null,
                    senderColor = COLORS[i % COLORS.size],
                    showAvatar = !outgoing && position.isEnd,
                    avatarLetter = NAMES[i % NAMES.size].take(1),
                    reactions = if (i % 9 == 0) listOf(ReactionChip("👍", 2, i % 2 == 0), ReactionChip("🔥", 1, false)) else emptyList(),
                    media = if (image) MediaInfo(1280, if (i % 2 == 0) 960 else 1600, isVideo = false) else null,
                    actions = MessageActions(canReply = true, canCopy = true, canReact = true),
                )
            // newest first: a day's chip comes after (above) its oldest message
            if (i % 40 == 39) items += DateItem("date-$i", "Day ${i / 40}")
        }
        return items
    }

    private object NoListActions : ChatListActions {
        override fun openChat(uuid: String) = Unit

        override fun newChat() = Unit

        override fun openContacts() = Unit

        override fun manageAccounts() = Unit

        override fun scanQrCode() = Unit

        override fun openSettings() = Unit

        override fun setArchived(uuids: List<String>, archived: Boolean) = Unit

        override fun setPinned(uuids: List<String>, pinned: Boolean) = Unit

        override fun setMuted(uuids: List<String>, till: Instant?) = Unit

        override fun markRead(uuids: List<String>) = Unit

        override fun delete(uuids: List<String>) = Unit
    }

    // open: MediaBenchmark reads real files in loadThumbnail
    internal open class DemoChatActions : ChatActions {
        override fun back() = Unit

        override fun openDetails() = Unit

        override fun openSearch() = Unit

        override fun toggleMute() = Unit

        override fun togglePin() = Unit

        override fun openClassic() = Unit
        override fun bannerAction(banner: ChatBanner) = Unit
        override fun selectEncryption(itemId: Int) = Unit

        override fun send() = Unit

        override fun attachGallery() = Unit

        override fun attachFile() = Unit

        override fun attachLocation() = Unit
        override fun attachContact() = Unit

        override fun takePhoto() = Unit

        override fun recordVideo() = Unit

        override fun startRecording() = false
        override fun stopRecording(send: Boolean) = Unit

        override fun loadMore() = Unit

        override fun markRead(uuid: String) = Unit

        override fun reply(uuid: String) = Unit

        override fun cancelReply() = Unit

        override fun edit(uuid: String) = Unit

        override fun cancelEdit() = Unit

        override fun copy(uuid: String) = Unit

        override fun retry(uuid: String) = Unit

        override fun share(uuid: String) = Unit

        override fun toggleReaction(uuid: String, emoji: String) = Unit

        override fun addReaction(uuid: String, emoji: String) = Unit

        override fun openMedia(uuid: String) = Unit

        override fun download(uuid: String) = Unit
        override fun saveFile(uuid: String) = Unit
        override fun cancelTransfer(uuid: String) = Unit
        override fun delete(uuid: String) = Unit
        override fun setWallpaper(spec: String?) = Unit
        override fun pickWallpaperPhoto() = Unit
        override fun sendSticker(file: java.io.File) = Unit
        override fun importStickers() = Unit
        override fun shareStickers() = Unit
        override fun addSticker(uuid: String) = Unit
        override fun seekVoice(uuid: String, fraction: Float) = Unit

        override fun openLocation(uuid: String) = Unit

        override fun toggleVoice(uuid: String) = Unit

        override fun openLink(url: String) = Unit

        // like FileBackend.getThumbnail: a little disk latency, off the main thread
        override suspend fun loadThumbnail(uuid: String, sizePx: Int): Bitmap? =
            withContext(Dispatchers.IO) {
                delay(8)
                solid(sizePx, COLORS[uuid.hashCode().mod(COLORS.size)])
            }
    }

    private companion object {
        val NAMES = listOf("Ani", "Aram", "Davit", "Lilit", "Karen", "Narek", "Sona", "Gor", "Mariam", "Test", "Hasmik", "Armen")
        val TEXTS =
            listOf(
                "See you tomorrow at 10?",
                "Pushed the *new build*, changelog at https://example.am/changes",
                "> did you check the logs?\nYes, nothing suspicious",
                "Ok 👍",
                "The `--release` flag works now, _finally_",
                "Can somebody review the PR before lunch? It is the one with the archive tabs and the new chat list",
                "😂😂",
                "Meeting moved to 15:30",
            )
        val COLORS = listOf(0xFFE17076, 0xFF7BC862, 0xFF65AADD, 0xFFA695E7, 0xFFEE7AAE, 0xFF6EC9CB, 0xFFFAA774).map { it.toInt() }

        fun letterAvatar(index: Int): Bitmap = solid(162, COLORS[index % COLORS.size])

        fun solid(size: Int, color: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(color)
            return bitmap
        }
    }
}
