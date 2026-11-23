package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.theme.Accent
import am.cyberhayq.chat.ui.theme.BarBlur
import am.cyberhayq.chat.ui.theme.NightBackground
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.os.SystemClock
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsNotEnabled
import am.cyberhayq.chat.ui.common.MenuEntry
import androidx.compose.ui.test.click
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextRange
import eu.siacs.conversations.utils.UIHelper
import eu.siacs.conversations.entities.Message
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.input.TextFieldValue
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.swipeDown
import eu.siacs.conversations.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Renders the chat screen on the JVM; images land in build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ChatScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private var backDispatcher: OnBackPressedDispatcher? = null

    private val LINK = "https://example.am/changes"

    private val FINGERPRINT = "3f2a9c41 b7e0d58a 61c4f9e2 0d7b3a68\n9e15c2f7 a4d860b3 c7f21e95 58b0a4d6"

    private val voice = MutableStateFlow(VoicePlayback("voice-1", true, 7_000, 19_000))

    /** Slate and Sea (style tokens): Sea by default, any other accent over the same slate surfaces; three night backgrounds. */
    @Test fun chatSlateLight() = capture("chat_slate_light", darkTheme = false, accent = Accent.SEA, style = TatrakStyle.SLATE_SEA) { privateChat(it) }

    @Test @Config(qualifiers = "+night")
    fun chatSlateDark() = capture("chat_slate_dark", darkTheme = true, accent = Accent.SEA, style = TatrakStyle.SLATE_SEA) { privateChat(it) }

    @Test @Config(qualifiers = "+night")
    fun chatSlateGraphiteDark() =
        capture("chat_slate_graphite_dark", darkTheme = true, accent = Accent.SEA, style = TatrakStyle.SLATE_SEA, nightBackground = NightBackground.GRAPHITE) {
            privateChat(it)
        }

    @Test @Config(qualifiers = "+night")
    fun chatSlateAmoledDark() =
        capture("chat_slate_amoled_dark", darkTheme = true, accent = Accent.SEA, style = TatrakStyle.SLATE_SEA, nightBackground = NightBackground.AMOLED) {
            privateChat(it)
        }

    /** Glass, Warm Glass and Tinted Glass (violet): Telegram's bubbles and avatars over their own surfaces. */
    @Test fun chatGlassLight() = capture("chat_glass_light", darkTheme = false, style = TatrakStyle.GLASS) { privateChat(it) }

    @Test @Config(qualifiers = "+night")
    fun chatGlassDark() = capture("chat_glass_dark", darkTheme = true, style = TatrakStyle.GLASS) { privateChat(it) }

    @Test fun chatWarmLight() = capture("chat_warm_light", darkTheme = false, style = TatrakStyle.WARM) { privateChat(it) }

    @Test @Config(qualifiers = "+night")
    fun chatWarmDark() = capture("chat_warm_dark", darkTheme = true, style = TatrakStyle.WARM) { privateChat(it) }

    @Test fun chatTintedLight() = capture("chat_tinted_light", darkTheme = false, accent = Accent.VIOLET, style = TatrakStyle.TINTED) { privateChat(it) }

    @Test @Config(qualifiers = "+night")
    fun chatTintedDark() = capture("chat_tinted_dark", darkTheme = true, accent = Accent.VIOLET, style = TatrakStyle.TINTED) { privateChat(it) }

    @Test fun chatSlateGoldLight() =
        capture("chat_slate_gold_light", darkTheme = false, accent = Accent.GOLD, style = TatrakStyle.SLATE_SEA) { privateChat(it) }

    @Test fun chatLight() {
        capture("chat_light", darkTheme = false) { privateChat(it) }
        compose.onNodeWithContentDescription("Online").assertExists()
        compose.onNodeWithContentDescription("Read").assertExists()
        compose.onNodeWithContentDescription("Delivered").assertExists()
    }

    @Test
    @Config(qualifiers = "+night")
    fun chatDark() = capture("chat_dark", darkTheme = true) { privateChat(it) }

    @Test fun chatGroup() {
        capture("chat_group", darkTheme = false) { groupChat(it) }
        compose.onNodeWithContentDescription("Online").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "+night")
    fun chatGroupDark() = capture("chat_group_dark", darkTheme = true) { groupChat(it) }

    private val mentionActions =
        object : ChatActions by FakeActions {
            override suspend fun mentionCandidates() =
                listOf("Aram", "Ani", "Anahit", "anton", "Arpi", "Ashot", "Mom", "Narek").map { MentionCandidate(it, UIHelper.getColorForName(it)) }

            override suspend fun mentionAvatar(nick: String, sizePx: Int): Bitmap? =
                if (nick == "Aram") letterAvatar(nick, 0xFF2E8B57.toInt()) else null
        }

    /** "@a" in a group: the participants whose nick starts with "a", any case. */
    @Test fun chatMentionSuggestions() {
        capture("chat_mention_suggestions", darkTheme = false, input = TextFieldValue("Who is free, @a", TextRange(15)), actions = mentionActions) {
            groupChat(it).let { st -> st.copy(composer = st.composer.copy(mentions = true)) }
        }
        compose.onNodeWithText("anton").assertExists()
        compose.onAllNodesWithText("Mom").assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "+night")
    fun chatMentionSuggestionsSlateDark() =
        capture(
            "chat_mention_suggestions_slate_dark",
            darkTheme = true,
            accent = Accent.SEA,
            style = TatrakStyle.SLATE_SEA,
            input = TextFieldValue("@a", TextRange(2)),
            actions = mentionActions,
        ) {
            groupChat(it).let { st -> st.copy(composer = st.composer.copy(mentions = true)) }
        }

    /** The "@" button above "scroll down" while an unread mention is out of view; a tap jumps and it goes. */
    @Test fun chatMentionJump() {
        // the host side: ChatFragment keeps the seen mentions in the view model's tracker
        val tracker = MentionTracker()
        val actions = object : ChatActions by FakeActions {
            override fun mentionsSeen(uuids: Collection<String>) = tracker.markSeen(uuids)
        }
        capture("chat_mention_jump", darkTheme = false, actions = actions, mentions = tracker, act = {
            compose.onNode(hasScrollAction()).performScrollToIndex(4)
        }) { longGroupChat(it) }
        compose.onNodeWithContentDescription("Scroll to the newest message").assertExists()
        compose.onNodeWithContentDescription("Next mention").performClick()
        // the jump and its flash run in the list's scope after the click
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_mention_jumped.png")
        compose.onNodeWithText("Narek, are you coming", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Next mention").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "+night")
    fun chatMentionJumpDark() = capture("chat_mention_jump_dark", darkTheme = true) { longGroupChat(it) }

    @Test fun avatarTapOpensTheParticipantMenu() {
        val asked = mutableListOf<String>()
        var picked: String? = null
        val actions = object : ChatActions by FakeActions {
            override fun mentionSender(uuid: String) = error("a tap must not mention")
            override fun senderMenu(uuid: String): List<MenuEntry> {
                asked += uuid
                return listOf(MenuEntry("Contact details") { picked = uuid }, MenuEntry("Send private message", enabled = false) {})
            }
        }
        groupChatWith(actions)
        compose.onAllNodesWithContentDescription("Participant")[0].performClick()
        assertEquals(1, asked.size)
        assert(asked[0] in setOf("g2", "g3", "g5", "g6")) { asked }
        compose.onNodeWithText("Send private message").assertIsNotEnabled()
        compose.onNodeWithText("Contact details").performClick()
        assertEquals(asked[0], picked)
    }

    @Test fun avatarLongPressMentionsTheSender() {
        val mentioned = mutableListOf<String>()
        val actions = object : ChatActions by FakeActions {
            override fun mentionSender(uuid: String) { mentioned += uuid }
            override fun senderMenu(uuid: String): List<MenuEntry> = error("a long press must not open the menu")
        }
        groupChatWith(actions)
        compose.onAllNodesWithContentDescription("Participant")[0].performTouchInput { longClick() }
        assertEquals(1, mentioned.size)
        assert(mentioned[0] in setOf("g2", "g3", "g5", "g6")) { mentioned }
    }

    private fun groupChatWith(actions: ChatActions) =
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                ChatScreen(
                    groupChat(Samples(currentTextPalette())), TextFieldValue(), {}, null, actions, voice, MutableStateFlow(VoiceRecording()),
                )
            }
        }

    @Test fun chatMedia() = capture("chat_media", darkTheme = false) { mediaChat(it) }

    @Test
    @Config(qualifiers = "+night")
    fun chatMediaDark() = capture("chat_media_dark", darkTheme = true) { mediaChat(it) }

    /** Tatrak: captions (from monocles backups) under an image, a file and a voice message. */
    @Test fun chatCaptions() = capture("chat_captions", darkTheme = false) { captionChat(it) }

    @Test
    @Config(qualifiers = "+night")
    fun chatCaptionsDark() = capture("chat_captions_dark", darkTheme = true) { captionChat(it) }

    @Test
    fun chatReplyPanel() =
        capture(
            "chat_reply",
            darkTheme = false,
            input = TextFieldValue("Sure, 10 works"),
            reply = ReplyPreview("m4", "Ani", "See you tomorrow at 10?", 0xFFE17076.toInt()),
        ) {
            privateChat(it)
        }

    /** A group private message (text only): the bar names the occupant; no attach button, no mic, the classic hint. */
    @Test fun chatPrivateMessage() {
        capture("chat_private_message", darkTheme = false, privateTo = "Ani") { groupChat(it) }
        compose.onNodeWithText("Send private message").assertExists()
        compose.onNodeWithText("Send clear text message", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Attach").assertDoesNotExist()
        compose.onNodeWithContentDescription("Record a voice message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    /** A private reply: the reply panel over the private-message bar; floating bars, so the pill composer draws them. */
    @Test
    @Config(qualifiers = "+night")
    fun chatPrivateReplyDark() =
        capture(
            "chat_private_reply_dark",
            darkTheme = true,
            floating = true,
            reply = ReplyPreview("m4", "Ani", "See you tomorrow at 10?", 0xFFE17076.toInt()),
            privateTo = "Ani",
        ) {
            groupChat(it)
        }

    @Test fun chatReplyPhoto() = replyPhoto("chat_reply_photo", darkTheme = false)

    @Test
    @Config(qualifiers = "+night")
    fun chatReplyPhotoDark() = replyPhoto("chat_reply_photo_dark", darkTheme = true)

    @Test
    fun replyThumbnailDropsThePreviousPhotoWhileTheNewOneLoads() {
        var uuid by mutableStateOf("a")
        val second = CompletableDeferred<Bitmap>()
        fun bitmap(color: Color) = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color.toArgb()) }
        val actions = object : NoChatActions() {
            override suspend fun loadThumbnail(uuid: String, sizePx: Int) =
                if (uuid == "a") bitmap(Color.Red) else second.await()
        }
        compose.setContent {
            ReplyThumbnail(uuid, 34.dp, 4.dp, actions, Modifier.testTag("reply-thumbnail"))
        }
        fun center(): Color {
            val pixels = compose.onNodeWithTag("reply-thumbnail").captureToImage().toPixelMap()
            return pixels[pixels.width / 2, pixels.height / 2]
        }
        assertEquals(Color.Red.toArgb(), center().toArgb())
        compose.runOnIdle { uuid = "b" }
        assertFalse(center().toArgb() == Color.Red.toArgb())
        compose.runOnIdle { second.complete(bitmap(Color.Blue)) }
        assertEquals(Color.Blue.toArgb(), center().toArgb())
    }

    /** A bubble and the composer panel answering a photo: both show its thumbnail. */
    private fun replyPhoto(name: String, darkTheme: Boolean) =
        capture(
            name,
            darkTheme = darkTheme,
            input = TextFieldValue("And this one?"),
            reply = ReplyPreview("i1", "Davit", "Photo", 0xFFFAA774.toInt(), thumbnail = true),
        ) { s ->
            mediaChat(s).let { st ->
                val answer =
                    s.text("r1", true, GroupPosition.SINGLE, "Where was this taken?", "11:03", status = DeliveryStatus.READ)
                        .copy(reply = ReplyHeader("Davit", "Photo", 0xFFFAA774.toInt(), "i1", thumbnail = true))
                st.copy(items = listOf(answer) + st.items.filter { it.key == "i1" || it is DateItem })
            }
        }

    @Test
    fun chatWallpaper() = capture("chat_wallpaper", darkTheme = false) { privateChat(it).copy(wallpaper = "preset:ocean") }

    @Test
    @Config(qualifiers = "+night")
    fun chatWallpaperDark() = capture("chat_wallpaper_dark", darkTheme = true) { privateChat(it).copy(wallpaper = "preset:sunrise") }

    @Test
    fun chatWallpaperPattern() = capture("chat_wallpaper_pattern", darkTheme = false) { privateChat(it).copy(wallpaper = "preset:sky#leaves") }

    @Test
    @Config(qualifiers = "+night")
    fun chatWallpaperPatternDark() = capture("chat_wallpaper_pattern_dark", darkTheme = true) { privateChat(it).copy(wallpaper = "default#bubbles") }

    @Test
    fun chatGoldLight() = capture("chat_gold_light", darkTheme = false, accent = Accent.GOLD) { privateChat(it) }

    @Test
    fun chatGreenAccent() = capture("chat_green", darkTheme = false, accent = Accent.GREEN) { privateChat(it) }

    @Test
    fun chatRecording() {
        // the timer and the blinking dot never settle
        compose.mainClock.autoAdvance = false
        capture("chat_recording", darkTheme = false, recording = VoiceRecording(true, SystemClock.elapsedRealtime() - 12_000)) {
            privateChat(it)
        }
    }

    @Test
    fun chatRecordingPlainFile() {
        // a recording through the plain-file fallback shows the open-lock mark beside the timer
        compose.mainClock.autoAdvance = false
        capture(
            "chat_recording_plain_file",
            darkTheme = false,
            recording = VoiceRecording(true, SystemClock.elapsedRealtime() - 12_000, plainFile = true),
            act = { compose.mainClock.advanceTimeBy(400) },
        ) { privateChat(it) }
    }

    @Test
    fun chatEditing() =
        capture("chat_editing", darkTheme = false, input = TextFieldValue("Fixed the typo")) {
            privateChat(it).copy(
                composer =
                    ComposerInfo(
                        hint = "Send edited message",
                        editing = EditPreview("m6", "Fixd the typo"),
                    )
            )
        }

    /** A tap on a text bubble opens the menu (Telegram); a long press selects instead. */
    @Test
    fun chatTapMenu() {
        capture("chat_menu", darkTheme = false, tap = "Yes, an hour ago") { privateChat(it) }
        compose.onNodeWithContentDescription("More reactions").assertExists()
        compose.onNodeWithContentDescription("Cancel selection").assertDoesNotExist()
    }

    /** The user's frequently used reactions form a second row under the six suggestions; a tap reacts with one. */
    @Test
    fun chatMenuFrequentReactions() = frequentReactions("chat_menu_frequent", darkTheme = false)

    @Test
    @Config(qualifiers = "+night")
    fun chatMenuFrequentReactionsDark() = frequentReactions("chat_menu_frequent_dark", darkTheme = true)

    private fun frequentReactions(name: String, darkTheme: Boolean) {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        // 😮 is a suggestion already: it stays out of the second row (the sample chat shows 👍 and 🔥 chips, so neither is used here)
        listOf("🎉", "🎉", "🙏", "😮", "😮", "😮", "🤝").forEach { FrequentReactions.record(context, it) }
        val toggled = mutableListOf<String>()
        val actions = object : ChatActions by FakeActions {
            override fun toggleReaction(uuid: String, emoji: String) { toggled += emoji }
        }
        capture(name, darkTheme = darkTheme, actions = actions, tap = "Yes, an hour ago") { privateChat(it) }
        compose.onAllNodesWithText("😮").assertCountEquals(1)
        compose.onNodeWithText("🙏").assertExists()
        compose.onNodeWithText("🤝").assertExists()
        compose.onNodeWithText("🎉").performClick()
        assertEquals(listOf("🎉"), toggled)
    }

    /** An encrypted message kept after an unverified request to delete it. */
    @Test
    fun chatRetractUnverified() =
        capture("chat_retract_unverified", darkTheme = false) { s ->
            privateChat(s).let { st ->
                st.copy(items = st.items.map { if (it is MessageItem && it.key == "m4") it.copy(retractUnverified = true) else it })
            }
        }

    /** A retracted message stays as a placeholder (ChatSnapshot.content, TYPE_RETRACTED). */
    @Test
    fun chatRetractedPlaceholder() =
        capture("chat_retracted_placeholder", darkTheme = false) { s ->
            privateChat(s).let { st ->
                st.copy(
                    items =
                        st.items.map {
                            if (it is MessageItem && it.key == "m4")
                                it.copy(kind = MessageKind.INFO, text = AnnotatedString("Message deleted"))
                            else it
                        }
                )
            }
        }

    /** Near the top: the reactions go under the message, the message does not move. */
    @Test
    fun chatTapMenuNearTop() =
        capture("chat_menu_top", darkTheme = false, tap = "Hey! Did you push") { privateChat(it) }

    /** A received message with a known time received: "Message info" closes the menu; no times in the menu itself. */
    @Test
    fun chatTapMenuReceived() {
        capture("chat_menu_received", darkTheme = false, tap = "flag works now") { s -> withInfo(privateChat(s), key = null) }
        compose.onNodeWithText("Message info").assertExists()
        compose.onNodeWithText("Received", substring = true).assertDoesNotExist()
    }

    /** An OMEMO message: "Message info" opens the sheet with the times, trust and fingerprint; "Show this key" opens the key list. */
    @Test
    fun chatMessageInfoSheet() {
        val opened = mutableListOf<String>()
        val actions = object : ChatActions by FakeActions {
            override fun openMessageKey(uuid: String) { opened += uuid }
        }
        capture("chat_menu_key", darkTheme = false, actions = actions, tap = "flag works now") { s -> withInfo(privateChat(s), FINGERPRINT) }
        compose.onNodeWithText(FINGERPRINT).assertDoesNotExist()
        compose.onNodeWithText("Message info").performClick()
        compose.onNodeWithText("Message info").assertIsDisplayed() // now the sheet's title
        compose.onNodeWithText("Sent").assertExists()
        compose.onNodeWithText("Received").assertExists()
        compose.onAllNodesWithText("2025", substring = true).assertCountEquals(2)
        compose.onNodeWithText("OMEMO · verified ✓").assertExists()
        compose.onNodeWithText(FINGERPRINT).assertExists()
        compose.onNodeWithText("Show this key in the key list").performClick()
        assertEquals(listOf("m5"), opened)
    }

    /** The sheet's content on a 360 dp phone: a line of four fingerprint groups does not wrap. */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun messageInfoSheet() {
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                val item = withInfo(privateChat(Samples(currentTextPalette())), FINGERPRINT).items.first { it.key == "m5" } as MessageItem
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { MessageInfoSheetContent(item, onOpenKey = {}) }
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(FINGERPRINT).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_message_info_sheet.png")
    }

    /** An own message in a private group: the sheet lists who has read it. */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun groupMessageInfoSheet() {
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                val item = (groupChat(Samples(currentTextPalette())).items.first { it.key == "g4" } as MessageItem)
                    .copy(readBy = listOf("Aram", "Mom"), encryptionKey = KeyInfo(R.string.encryption_choice_unencrypted))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { MessageInfoSheetContent(item, onOpenKey = {}) }
            }
        }
        compose.onNodeWithText("Read by").assertExists()
        compose.onNodeWithText("Mom").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_group_message_info_sheet.png")
    }

    /** m5 with sent and received times in a past year (the year shows, so the text does not depend on today) and an OMEMO key. */
    private fun withInfo(st: ChatState, key: String?) =
        st.copy(
            items =
                st.items.map {
                    if (it is MessageItem && it.key == "m5")
                        it.copy(
                            sentAt = at("2025-11-14T21:03:10"),
                            receivedAt = at("2025-11-14T21:03:15"),
                            encryptionKey = key?.let { k -> KeyInfo(R.string.encryption_choice_omemo, R.string.tatrak_chat_key_verified, k) },
                        )
                    else it
                }
        )

    private fun at(time: String) =
        java.time.LocalDateTime.parse(time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** A long press on a link selects the message; the link's own tap must not eat it. */
    @Test
    fun longPressOnLinkSelects() {
        val opened = onLink("chat_menu_link") { performTouchInput { longClick() } }
        compose.onNodeWithContentDescription("Cancel selection").assertExists()
        // the release after the long press must not open the link as well
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun tapOnLinkOpensIt() = assertEquals(listOf(LINK), onLink("chat_link_tap") { performClick() })

    /** Runs [gesture] on a message that is only a link; returns the links opened. */
    private fun onLink(name: String, gesture: SemanticsNodeInteraction.() -> Unit): List<String> {
        val opened = mutableListOf<String>()
        val actions = object : ChatActions by FakeActions {
            override fun openLink(url: String) { opened += url }
        }
        capture(name, darkTheme = false, actions = actions, act = {
            compose.onNodeWithText(LINK, useUnmergedTree = true).gesture()
        }) { s ->
            privateChat(s).let { st ->
                st.copy(items = st.items.map { if (it is MessageItem && it.key == "m2") s.text("m2", false, GroupPosition.LAST, LINK, "21:41") else it })
            }
        }
        return opened
    }

    // --- selecting messages (Telegram: long press selects, a drag selects more, a tap toggles) ---

    /** Records what the selection bar and a tap on media ask for. */
    private class SelectionActions : ChatActions by FakeActions {
        val calls = mutableListOf<String>()
        var onDeleted: (() -> Unit)? = null
        override fun openMedia(uuid: String) { calls += "media $uuid" }
        override fun copyMessages(uuids: List<String>) { calls += "copy $uuids" }
        override fun forwardMessages(uuids: List<String>) { calls += "forward $uuids" }
        override fun deleteMessages(uuids: List<String>, onDeleted: () -> Unit) {
            calls += "delete $uuids"
            this.onDeleted = onDeleted
        }
    }

    private fun center(text: String): Offset = compose.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot.center

    /** Long press on [from], then the same finger over [over] in a few steps. */
    private fun longPressDrag(from: String, vararg over: String) {
        val start = center(from)
        val points = over.map(::center)
        compose.onRoot().performTouchInput {
            down(start)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            var at = start
            for (point in points) {
                val step = (point - at) / 4f
                repeat(4) { moveBy(step) }
                at = point
            }
            up()
        }
        compose.waitForIdle()
    }

    private fun selectionChat(s: Samples) =
        privateChat(s).let { st -> st.copy(items = st.items.map { if (it is MessageItem) it.copy(actions = it.actions.copy(canDelete = true)) else it }) }

    private fun assertSelected(count: Int?) {
        if (count == null) {
            compose.onNodeWithContentDescription("Cancel selection").assertDoesNotExist()
        } else {
            // the count beside the cross (message texts and reactions have digits too)
            val bar = compose.onNodeWithContentDescription("Cancel selection").fetchSemanticsNode().boundsInRoot
            assertTrue(compose.onAllNodesWithText(count.toString()).fetchSemanticsNodes().any { it.boundsInRoot.center.y in bar.top..bar.bottom })
        }
    }

    @Test
    fun longPressSelects() {
        capture("chat_selection_one", darkTheme = false, act = { compose.onNodeWithText("Yes, an hour ago", substring = true).performTouchInput { longClick() } }) { privateChat(it) }
        assertSelected(1)
        compose.onNodeWithContentDescription("More reactions").assertDoesNotExist()
    }

    /** m3 to m5 passes the "Today" chip and the unread divider, which are not selectable; the bar hands them over oldest first. */
    @Test
    fun longPressDragSelectsAndCopies() {
        val actions = SelectionActions()
        capture("chat_selection", darkTheme = false, actions = actions, act = { longPressDrag("Yes, an hour ago", "flag works now") }) { selectionChat(it) }
        assertSelected(3)
        compose.onNodeWithContentDescription("Copy").performClick()
        assertEquals(listOf("copy [m3, m4, m5]"), actions.calls)
        assertSelected(null)
    }

    /** A drag that starts on a selected message unselects (the mode set by the first row, as in Telegram). */
    @Test
    fun dragFromSelectedUnselects() {
        capture("chat_selection_unselect", darkTheme = false, act = {
            longPressDrag("flag works now", "Fixd the typo", "Sure, 10 works")
            longPressDrag("Fixd the typo", "Sure, 10 works")
        }) { selectionChat(it) }
        assertSelected(1)
    }

    @Test
    fun tapTogglesAndLastUnselectLeaves() {
        val actions = SelectionActions()
        capture("chat_selection_tap", darkTheme = false, actions = actions, act = { compose.onNodeWithText("Yes, an hour ago", substring = true).performTouchInput { longClick() } }) {
            selectionChat(it)
        }
        compose.onNodeWithText("Fixd the typo").performTouchInput { click() }
        assertSelected(2)
        compose.onNodeWithText("Fixd the typo").performTouchInput { click() }
        assertSelected(1)
        compose.onNodeWithContentDescription("More reactions").assertDoesNotExist()
        compose.onNodeWithText("Yes, an hour ago", substring = true).performTouchInput { click() }
        assertSelected(null)
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun backAndCrossLeaveSelection() {
        capture("chat_selection_back", darkTheme = false, act = { compose.onNodeWithText("Yes, an hour ago", substring = true).performTouchInput { longClick() } }) { selectionChat(it) }
        compose.runOnIdle { backDispatcher!!.onBackPressed() }
        assertSelected(null)
        compose.onNodeWithText("Yes, an hour ago", substring = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Cancel selection").performClick()
        assertSelected(null)
    }

    @Test
    fun forwardAndDeleteTheSelection() {
        val actions = SelectionActions()
        capture("chat_selection_actions", darkTheme = false, actions = actions, act = { longPressDrag("Yes, an hour ago", "flag works now") }) { selectionChat(it) }
        compose.onNodeWithContentDescription("Forward").performClick()
        longPressDrag("Fixd the typo", "Sure, 10 works")
        compose.onNodeWithContentDescription("Delete").performClick()
        // the selection stays until the dialog's delete runs
        assertSelected(2)
        compose.runOnIdle { actions.onDeleted!!() }
        assertSelected(null)
        assertEquals(listOf("forward [m3, m4, m5]", "delete [m6, m7]"), actions.calls)
    }

    /** Media keep their own tap; while selecting, the same tap toggles instead. */
    @Test
    fun tapOnPhotoOpensItOrToggles() {
        val actions = SelectionActions()
        capture("chat_selection_media", darkTheme = false, actions = actions, act = { compose.onNodeWithText("11:00").performTouchInput { click() } }) { photoChat(it) }
        assertEquals(listOf("media i1"), actions.calls)
        compose.onNodeWithContentDescription("More reactions").assertDoesNotExist()
        compose.onNodeWithText("11:00").performTouchInput { longClick() }
        compose.onNodeWithText("11:02").performTouchInput { click() }
        assertSelected(2)
        assertEquals(listOf("media i1"), actions.calls)
    }

    /** Holding the drag at the list's top edge scrolls up and selects on the way, up to the oldest message. */
    @Test
    fun dragAtTheTopEdgeScrollsAndSelects() {
        val actions = SelectionActions()
        capture("chat_selection_autoscroll", darkTheme = false, actions = actions, act = {
            val start = center("Reply number 24")
            val listTop = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot.top
            compose.onRoot().performTouchInput {
                down(start)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
                val step = (Offset(start.x, listTop + 10f) - start) / 8f
                repeat(8) { moveBy(step) }
                advanceEventTime(3_000)
                moveBy(Offset(1f, 0f))
                up()
            }
        }) { longGroupChat(it) }
        compose.onNodeWithContentDescription("Copy").performClick()
        val copied = actions.calls.single()
        assertTrue(copied, copied.startsWith("copy [") && "x1" in copied && copied.endsWith("n24]"))
    }

    /** The selection survives an activity recreation (rotation). */
    @Test
    fun selectionSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TatrakTheme(darkTheme = false) {
                ChatScreen(
                    state = privateChat(Samples(currentTextPalette())),
                    input = TextFieldValue(),
                    onInputChange = {},
                    reply = null,
                    actions = FakeActions,
                    voice = voice,
                    recording = MutableStateFlow(VoiceRecording()),
                )
            }
        }
        compose.onNodeWithText("Yes, an hour ago", substring = true).performTouchInput { longClick() }
        restoration.emulateSavedInstanceStateRestore()
        assertSelected(1)
    }

    /** A selected message that leaves the list leaves the selection: the menu works again and it does not come back selected. */
    @Test
    fun vanishedMessageLeavesTheSelection() {
        var gone by mutableStateOf(false)
        capture("chat_selection_vanished", darkTheme = false, act = { compose.onNodeWithText("Yes, an hour ago", substring = true).performTouchInput { longClick() } }) { s ->
            selectionChat(s).let { st -> if (gone) st.copy(items = st.items.filterNot { it.key == "m3" }) else st }
        }
        assertSelected(1)
        gone = true
        compose.waitForIdle()
        assertSelected(null)
        compose.onNodeWithText("Fixd the typo").performTouchInput { click() }
        compose.onNodeWithContentDescription("More reactions").assertExists()
        compose.runOnIdle { backDispatcher!!.onBackPressed() }
        gone = false
        compose.waitForIdle()
        assertSelected(null)
    }

    /** The empty part of the row beside a bubble opens the menu (the bubble keeps its own tap). */
    @Test
    fun tapBesideBubbleOpensMenu() {
        val actions = SelectionActions()
        capture("chat_menu_beside", darkTheme = false, actions = actions, act = {
            val bubble = compose.onNodeWithText("11:00").fetchSemanticsNode().boundsInRoot
            compose.onRoot().performTouchInput { click(Offset(width - 20f, bubble.center.y)) }
        }) { photoChat(it) }
        compose.onNodeWithContentDescription("More reactions").assertExists()
        assertEquals(emptyList<String>(), actions.calls)
    }

    // Telegram: a flat leftward swipe replies; rightward or about 22° off horizontal it does not (the list scrolls)
    @Test fun telegramSwipeLeftReplies() = assertEquals(listOf("m7"), swipe(SwipeMethod.TELEGRAM, dx = -10f, dy = 0f))

    @Test fun telegramSwipeRightDoesNotReply() = assertEquals(emptyList<String>(), swipe(SwipeMethod.TELEGRAM, dx = 10f, dy = 0f))

    // a system cancel (e.g. the window loses the touch) past the trigger must not send the reply
    @Test fun telegramCancelDoesNotReply() = assertEquals(emptyList<String>(), swipe(SwipeMethod.TELEGRAM, dx = -10f, dy = 0f, cancel = true))

    @Test fun telegramDiagonalDoesNotReply() = assertEquals(emptyList<String>(), swipe(SwipeMethod.TELEGRAM, dx = -10f, dy = 4f))

    // Signal: rightward replies, also about 22° off horizontal (ItemTouchHelper takes up to 45°); leftward does not
    @Test fun signalSwipeRightReplies() = assertEquals(listOf("m7"), swipe(SwipeMethod.SIGNAL, dx = 10f, dy = 0f))

    @Test fun signalDiagonalReplies() = assertEquals(listOf("m7"), swipe(SwipeMethod.SIGNAL, dx = 10f, dy = 4f))

    @Test fun signalSwipeLeftDoesNotReply() = assertEquals(emptyList<String>(), swipe(SwipeMethod.SIGNAL, dx = -10f, dy = 0f))

    /** Drags message m7 in ten steps of ([dx], [dy]) dp; returns the messages replied to. */
    private fun swipe(method: SwipeMethod, dx: Float, dy: Float, cancel: Boolean = false): List<String> {
        val replied = mutableListOf<String>()
        val actions = object : ChatActions by FakeActions {
            override fun reply(uuid: String) { replied += uuid }
        }
        capture("chat_swipe", darkTheme = false, actions = actions, swipe = method, act = {
            compose.onNodeWithText("Sure, 10 works").performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(dx.dp.toPx(), dy.dp.toPx())) }
                if (cancel) cancel() else up()
            }
        }) { privateChat(it) }
        return replied
    }

    @Test
    fun menuPlacement() {
        // screen 0..1000, reactions 60, actions 300 (gaps included)
        fun at(top: Int, height: Int = 100) = MenuPlacement.of(top, height, strip = 60, menu = 300, top = 0, bottom = 1000)
        assertEquals(MenuPlacement.AROUND, at(400))
        assertEquals(MenuPlacement.BELOW, at(20))
        assertEquals(MenuPlacement.ABOVE, at(850))
        assertEquals(MenuPlacement.SWAPPED, at(320, height = 600))
        assertEquals(null, at(100, height = 850))
    }

    @Test
    fun chatEmpty() =
        capture("chat_empty", darkTheme = false) {
            ChatState(
                loaded = true,
                conversationUuid = "c1",
                header = ChatHeader("Lilit", "last seen recently", avatar = letterAvatar("Lilit", 0xFF6EC9CB.toInt())),
                composer = ComposerInfo(hint = "Send encrypted message"),
            )
        }

    @Test
    fun chatPgpAndBanner() =
        capture("chat_pgp_banner", darkTheme = false) {
            privateChat(it).copy(
                banner = ChatBanner.PGP_PENDING,
                composer = ComposerInfo(requiresClassic = true, hint = ""),
            )
        }

    /** A group the account could not join: the classic reason and its action. */
    @Test
    fun chatGroupKickedBanner() {
        val clicked = ArrayList<ChatBanner>()
        val actions = object : ChatActions by FakeActions {
            override fun bannerAction(banner: ChatBanner) { clicked += banner }
        }
        capture("chat_banner_group_kicked", darkTheme = false, actions = actions) { groupChat(it).copy(banner = ChatBanner.KICKED) }
        compose.onNodeWithText("Join").performClick()
        assertEquals(listOf(ChatBanner.KICKED), clicked)
    }

    /** The longest classic text wraps instead of being cut. */
    @Test
    fun chatGroupNonAnonymousBanner() =
        capture("chat_banner_group_non_anonymous", darkTheme = false) { groupChat(it).copy(banner = ChatBanner.NON_ANONYMOUS) }

    /** Block opens the classic block menu. */
    @Test
    fun chatStrangerBanner() {
        var blocked: Boolean? = null
        val actions = object : ChatActions by FakeActions {
            override fun block(domain: Boolean) { blocked = domain }
        }
        capture("chat_banner_stranger_menu", darkTheme = false, actions = actions, act = {
            compose.onNodeWithText("Block").performClick()
        }) { privateChat(it).copy(banner = ChatBanner.STRANGER, blockMenu = BlockMenu(blockContact = true, reject = false)) }
        compose.onNodeWithText("Reject request").assertDoesNotExist()
        compose.onNodeWithText("Block stranger").performClick()
        assertEquals(false, blocked)
    }

    /** Add back: a tap adds back, a long press opens the classic menu with Reject first. */
    @Test
    fun chatAddBackBlockMenu() {
        var added = false
        var rejected = false
        val actions = object : ChatActions by FakeActions {
            override fun bannerAction(banner: ChatBanner) { added = banner == ChatBanner.ADD_BACK }
            override fun rejectSubscription() { rejected = true }
        }
        capture("chat_banner_add_back_menu", darkTheme = false, actions = actions, act = {
            compose.onNodeWithText("Add back").performTouchInput { longClick() }
        }) { privateChat(it).copy(banner = ChatBanner.ADD_BACK, blockMenu = BlockMenu(blockContact = true, reject = true)) }
        assertFalse(added)
        compose.onNodeWithText("Block entire domain").assertExists()
        compose.onNodeWithText("Reject request").performClick()
        assertTrue(rejected)
        compose.onNodeWithText("Add back").performClick()
        assertTrue(added)
    }

    /** The server archive row above the oldest message and the contact's DND row below the newest. */
    @Test
    fun chatLoadMoreAndDnd() {
        var loads = 0
        val actions = object : ChatActions by FakeActions {
            override fun loadHistory() { loads++ }
        }
        capture("chat_load_more_dnd", darkTheme = false, actions = actions) {
            ChatState(
                loaded = true,
                conversationUuid = "c1",
                header = ChatHeader("Ani", "last seen recently", avatar = letterAvatar("Ani", 0xFFE17076.toInt())),
                items =
                    listOf(
                        ServiceItem("contact-status", "Today", AnnotatedString("Ani has paused their notifications"), R.drawable.ic_notifications_paused_24dp),
                        it.text("m1", false, GroupPosition.SINGLE, "Talk tomorrow", "09:12"),
                        it.date("Today"),
                        LoadMoreItem("Today"),
                    ),
                composer = ComposerInfo(hint = "Send message"),
            )
        }
        compose.onNodeWithText("Load more messages").performClick()
        assertEquals(1, loads)
    }

    /** Several members typing, and a read row with the readers' avatar under the own message. */
    @Test
    fun chatGroupTyping() =
        capture("chat_group_typing", darkTheme = false) {
            val chat = groupChat(it)
            val read = ServiceItem("read-g4", "Today", AnnotatedString("Aram, Mom have read up to this point"), avatar = letterAvatar("Aram", 0xFF2E8B57.toInt()))
            val items = chat.items.toMutableList().apply { add(indexOfFirst { item -> item.key == "g4" }, read) }
            chat.copy(
                header = ChatHeader("Family", "Aram, Mom are typing…", subtitleActive = true, avatar = letterAvatar("Family", 0xFFA695E7.toInt()), isGroup = true),
                items = items,
            )
        }

    @Test
    fun chatAddBackBanner() =
        capture("chat_banner_add_back", darkTheme = false) { privateChat(it).copy(banner = ChatBanner.ADD_BACK) }

    @Test
    fun chatAllowBanner() =
        capture("chat_banner_allow", darkTheme = false) { privateChat(it).copy(banner = ChatBanner.ALLOW_SUBSCRIPTION) }

    @Test
    fun chatEncryptionMenu() =
        capture("chat_encryption_menu", darkTheme = false, act = {
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Encrypted with OMEMO").performClick()
        }) {
            privateChat(it).copy(composer = ComposerInfo(hint = "Send encrypted message", encryption = Message.ENCRYPTION_AXOLOTL))
        }

    @Test
    fun importedPqHistory() {
        capture("chat_imported_pq", darkTheme = false) { samples ->
            privateChat(samples).copy(items = listOf(
                samples.date("Today"),
                samples.text("pq", false, GroupPosition.SINGLE, "Readable history from monocles", "11:00",
                    trust = "Imported PQ OMEMO2 history").copy(security = Security.NONE),
                samples.base("unavailable", false, GroupPosition.SINGLE, "11:01").copy(
                    kind = MessageKind.INFO,
                    security = Security.NONE,
                    text = AnnotatedString("This imported PQ OMEMO2 message was not decrypted by the source app.")),
            ))
        }
        compose.onNodeWithText("Imported PQ OMEMO2 history").assertExists()
    }

    /** The clip opens its menu in place of the keyboard; the emoji button swaps it for emoji and back; a pick closes it. */
    @Test
    fun chatAttachPanel() {
        var gallery = 0
        val actions = object : ChatActions by FakeActions {
            override fun attachGallery() { gallery++ }
        }
        capture("chat_attach_panel", darkTheme = false, actions = actions, act = {
            compose.onNodeWithContentDescription("Attach").performClick()
        }) { privateChat(it).let { st -> st.copy(composer = st.composer.copy(contacts = true)) } }
        compose.onNodeWithText("Gallery").assertIsDisplayed()
        compose.onNodeWithText("Contact").assertIsDisplayed()
        compose.onNodeWithContentDescription("Emoji").performClick()
        compose.onNodeWithText("Gallery").assertDoesNotExist()
        compose.onNodeWithText("Stickers").assertIsDisplayed()
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText("Stickers").assertDoesNotExist()
        compose.onNodeWithText("Gallery").performClick()
        assertEquals(1, gallery)
        compose.onNodeWithText("Gallery").assertDoesNotExist()
    }

    /** Two picks waiting for Send (a photo and a file without a preview) replace the text field. */
    @Test
    fun chatAttachmentPreviews() {
        capture("chat_attachment_previews", darkTheme = false, previews = listOf("p1", "f1")) { privateChat(it) }
        compose.onAllNodesWithContentDescription("Remove attachment").assertCountEquals(2)
        compose.onNodeWithContentDescription("Send").assertExists()
    }

    /** A search result opened: the chat jumps to the message and flashes it. */
    @Test
    fun chatJumpToMessage() {
        var focused: MessageFocus? = null
        val focus = MessageFocus("m2")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                ChatScreen(
                    state = privateChat(Samples(currentTextPalette())),
                    input = TextFieldValue(),
                    onInputChange = {},
                    reply = null,
                    actions = FakeActions,
                    voice = voice,
                    recording = MutableStateFlow(VoiceRecording()),
                    focus = focus.takeIf { focused == null },
                    onFocused = { focused = it },
                )
            }
        }
        // the scroll, then the flash at its brightest
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_jump_to_message.png")
        assertEquals(focus, focused)
    }

    @Test
    fun chatAccentsAndNights() {
        var accent by mutableStateOf(Accent.BLUE)
        var dark by mutableStateOf(false)
        var night by mutableStateOf(NightBackground.BLUE_GREY)
        compose.setContent {
            TatrakTheme(darkTheme = dark, accent = accent, nightBackground = night) {
                val chat = privateChat(Samples(currentTextPalette()))
                ChatScreen(
                    state = chat,
                    input = TextFieldValue(),
                    onInputChange = {},
                    reply = null,
                    actions = FakeActions,
                    voice = voice,
                    recording = MutableStateFlow(VoiceRecording()),
                    previews = emptyList(),
                )
            }
        }
        // every accent by day and under each night background (Telegram style)
        for (option in Accent.entries) for (mode in listOf(null) + NightBackground.entries) {
            compose.runOnIdle { accent = option; dark = mode != null; night = mode ?: NightBackground.BLUE_GREY }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_accent_${option.key}_${mode?.key ?: "day"}.png")
        }
    }

    private fun capture(
        name: String,
        darkTheme: Boolean,
        input: TextFieldValue = TextFieldValue(),
        reply: ReplyPreview? = null,
        /** A message text to tap first (opens the menu). */
        tap: String? = null,
        recording: VoiceRecording = VoiceRecording(),
        accent: Accent = Accent.BLUE,
        previews: List<String> = emptyList(),
        style: TatrakStyle = TatrakStyle.TELEGRAM,
        nightBackground: NightBackground = NightBackground.BLUE_GREY,
        act: () -> Unit = {},
        actions: ChatActions = FakeActions,
        mentions: MentionTracker? = null,
        swipe: SwipeMethod = SwipeMethod.TELEGRAM,
        /** Off unless asked, also in the Glass styles (whose default is on): their plain chat screenshots stay as they were. */
        floating: Boolean = false,
        /** "Blur under bars" in effect; off unless asked, so the floating screenshots of step 4 stay as they were. */
        blur: BarBlur = BarBlur.OFF,
        privateTo: String? = null,
        state: @Composable (Samples) -> ChatState,
    ) {
        compose.setContent {
            TatrakTheme(darkTheme = darkTheme, style = style, accent = accent, nightBackground = nightBackground, floatingBars = floating, barBlur = blur) {
              CompositionLocalProvider(LocalSwipeMethod provides swipe) {
                backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                val samples = Samples(currentTextPalette())
                val chat = state(samples)
                ChatScreen(
                    state = chat,
                    input = input,
                    onInputChange = {},
                    reply = reply,
                    actions = actions,
                    voice = voice,
                    recording = MutableStateFlow(recording),
                    previews = previews,
                    seenMentions = mentions?.seen.orEmpty(),
                    privateTo = privateTo,
                )
              }
            }
        }
        if (tap != null) {
            // the end of the text, clear of links (a tap on a link opens it)
            compose.onNodeWithText(tap, substring = true, useUnmergedTree = true).performTouchInput { click(bottomRight - Offset(4f, 4f)) }
        }
        act()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    // ---- chat preview: long press on a chat list avatar ----

    @Test fun chatPreviewLight() = preview("chat_preview_light", darkTheme = false)

    @Test @Config(qualifiers = "+night")
    fun chatPreviewDark() = preview("chat_preview_dark", darkTheme = true)

    private class ReadCounter : NoChatActions() {
        var reads = 0

        override fun markRead(uuid: String) {
            reads++
        }
    }

    /** No composer, no header buttons, nothing marked read, nothing opened by just looking. */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun preview(name: String, darkTheme: Boolean) {
        val actions = ReadCounter()
        var opened = 0
        compose.setContent {
            TatrakTheme(darkTheme = darkTheme) {
                ChatPreview(privateChat(Samples(currentTextPalette())), actions, onOpen = { opened++ }, onDismiss = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("This one failed", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithContentDescription("More options").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
        assertEquals(0, actions.reads)
        assertEquals(0, opened)
    }

    /** A drag scrolls the chat and opens nothing; a tap, even on a bubble, opens it; neither marks anything read. */
    @Test fun chatPreviewTapOpensDragScrolls() {
        val actions = ReadCounter()
        var opened = 0
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                ChatPreview(longGroupChat(Samples(currentTextPalette())), actions, onOpen = { opened++ }, onDismiss = {})
            }
        }
        compose.waitForIdle()
        fun top() = compose.onNodeWithText("Message number 17", useUnmergedTree = true).getBoundsInRoot().top
        val before = top()
        compose.onNode(isDialog()).performTouchInput { swipeDown(startY = centerY - 150f, endY = centerY + 150f) }
        compose.waitForIdle()
        assertTrue("${top()} > $before", top() > before)
        assertEquals(0, opened)
        // held on a message and let go in place: a long press, not a tap (it would open the chat and mark it read)
        compose.onNodeWithText("Message number 17", useUnmergedTree = true).performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(0, opened)
        // a touch that stops a fling belongs to the list, it opens nothing
        compose.mainClock.autoAdvance = false
        compose.onNode(isDialog()).performTouchInput { swipeDown(startY = centerY - 200f, endY = centerY + 200f, durationMillis = 60) }
        compose.mainClock.advanceTimeBy(48)
        val flingTop = top()
        compose.onNode(isDialog()).performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, opened)
        // the list moved little after the touch: it was the fling's touch (the guard is shown by removing it, which fails above)
        assertTrue("fling stopped at $flingTop, now ${top()}", abs((top() - flingTop).value) < 150f)
        compose.onNode(isDialog()).performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(1, opened)
        assertEquals(0, actions.reads)
    }

    // ---- floating bars (step 4): header, banner and composer pills over the messages ----

    @Test fun chatFloatingGlassLight() = capture("chat_floating_glass_light", darkTheme = false, style = TatrakStyle.GLASS, floating = true) { privateChat(it) }

    @Test @Config(qualifiers = "+night")
    fun chatFloatingGlassDark() = capture("chat_floating_glass_dark", darkTheme = true, style = TatrakStyle.GLASS, floating = true) { privateChat(it) }

    /** Telegram's colours with the setting on: Slate's pill composer in the Telegram surface colours. */
    @Test fun chatFloatingTelegramLight() = capture("chat_floating_telegram_light", darkTheme = false, floating = true) { privateChat(it) }

    @Test fun chatFloatingSlateLight() =
        capture("chat_floating_slate_light", darkTheme = false, accent = Accent.SEA, style = TatrakStyle.SLATE_SEA, floating = true) { privateChat(it) }

    /** The banner is a pill of its own under the header's; its button still works. */
    @Test fun chatFloatingBanner() {
        var added = 0
        val actions = object : ChatActions by FakeActions {
            override fun bannerAction(banner: ChatBanner) { if (banner == ChatBanner.ADD_BACK) added++ }
        }
        capture("chat_floating_banner", darkTheme = false, style = TatrakStyle.GLASS, floating = true, actions = actions) {
            privateChat(it).copy(banner = ChatBanner.ADD_BACK)
        }
        val header = compose.onNodeWithContentDescription("Back").getBoundsInRoot()
        val banner = compose.onNodeWithText("Contact added you", substring = true).getBoundsInRoot()
        assertTrue("$header $banner", banner.top > header.bottom)
        compose.onNodeWithText("Add back").performClick()
        assertEquals(1, added)
    }

    @Test fun chatFloatingReply() =
        capture(
            "chat_floating_reply",
            darkTheme = false,
            style = TatrakStyle.GLASS,
            floating = true,
            input = TextFieldValue("Sure, 10 works"),
            reply = ReplyPreview("m4", "Ani", "See you tomorrow at 10?", 0xFFE17076.toInt()),
        ) { privateChat(it) }

    @Test fun chatFloatingSelection() {
        capture("chat_floating_selection", darkTheme = false, style = TatrakStyle.GLASS, floating = true, act = {
            compose.onNodeWithText("Yes, an hour ago", substring = true).performTouchInput { longClick() }
        }) { privateChat(it) }
        assertSelected(1)
    }

    @Test fun chatFloatingAttachPanel() {
        capture("chat_floating_attach_panel", darkTheme = false, style = TatrakStyle.GLASS, floating = true, act = {
            compose.onNodeWithContentDescription("Attach").performClick()
        }) { privateChat(it) }
        compose.onNodeWithText("Gallery").assertIsDisplayed()
        // the panel takes the keyboard's place under the pill; the newest message still sits above the pill
        assertTrue(compose.onNodeWithText("This one failed").getBoundsInRoot().bottom < compose.onNodeWithContentDescription("Emoji").getBoundsInRoot().top)
    }

    /** Blur under bars (API 31+): messages and wallpaper show blurred through the header and composer pills, frosted and clear. */
    @Test @Config(sdk = [35])
    fun chatFloatingFrostedLight() = blurred("chat_floating_frosted_light", BarBlur.FROSTED, dark = false)

    @Test @Config(sdk = [35], qualifiers = "+night")
    fun chatFloatingFrostedDark() = blurred("chat_floating_frosted_dark", BarBlur.FROSTED, dark = true)

    @Test @Config(sdk = [35])
    fun chatFloatingClearLight() = blurred("chat_floating_clear_light", BarBlur.CLEAR, dark = false)

    @Test @Config(sdk = [35], qualifiers = "+night")
    fun chatFloatingClearDark() = blurred("chat_floating_clear_dark", BarBlur.CLEAR, dark = true)

    private fun blurred(name: String, mode: BarBlur, dark: Boolean) =
        capture(name, darkTheme = dark, style = TatrakStyle.GLASS, floating = true, blur = mode, act = {
            compose.onNode(hasScrollAction()).performScrollToIndex(3)
        }) { longGroupChat(it) }

    /** The attach panel lifts the composer pill: its blur follows it; a reply block and the banner blur too. */
    @Test @Config(sdk = [35])
    fun chatFloatingBlurAttachPanel() {
        capture(
            "chat_floating_clear_attach_panel",
            darkTheme = false,
            style = TatrakStyle.GLASS,
            floating = true,
            blur = BarBlur.CLEAR,
            reply = ReplyPreview("m4", "Ani", "See you tomorrow at 10?", 0xFFE17076.toInt()),
            act = { compose.onNodeWithContentDescription("Attach").performClick() },
        ) { privateChat(it).copy(banner = ChatBanner.ADD_BACK) }
        compose.onNodeWithText("Gallery").assertIsDisplayed()
    }

    private fun floatingChat(
        input: () -> TextFieldValue = { TextFieldValue() },
        reply: () -> ReplyPreview? = { null },
        focus: () -> MessageFocus? = { null },
        state: @Composable (Samples) -> ChatState = { s -> privateChat(s).let { st -> st.copy(items = st.items.filterNot { it is UnreadItem }) } },
    ) {
        compose.setContent {
            TatrakTheme(darkTheme = false, style = TatrakStyle.GLASS, floatingBars = true, barBlur = BarBlur.OFF) {
                ChatScreen(
                    state = state(Samples(currentTextPalette())),
                    input = input(),
                    onInputChange = {},
                    reply = reply(),
                    actions = FakeActions,
                    voice = voice,
                    recording = MutableStateFlow(VoiceRecording()),
                    focus = focus(),
                )
            }
        }
        compose.waitForIdle()
    }

    /** The top of the composer's input pill (its emoji button spans the pill's height). */
    private fun composerTop() = compose.onNodeWithContentDescription("Emoji").getBoundsInRoot().top

    /** The header pill's bottom: the 64 dp bar centres its 48 dp back button. */
    private fun headerBottom() = compose.onNodeWithContentDescription("Back").getBoundsInRoot().bottom + 8.dp

    /** The newest message rests just above the composer pill, and moves up by what the composer grows: a reply block, more lines. */
    @Test fun floatingListPadsForTheComposer() {
        var reply by mutableStateOf<ReplyPreview?>(null)
        var input by mutableStateOf(TextFieldValue())
        floatingChat(input = { input }, reply = { reply })
        fun newest() = compose.onNodeWithText("This one failed").getBoundsInRoot().bottom
        val rest = composerTop() - newest()
        assertTrue("gap $rest", rest > 0.dp && rest < 32.dp)
        val plain = newest()
        compose.runOnIdle { reply = ReplyPreview("m4", "Ani", "See you tomorrow at 10?", 0xFFE17076.toInt()) }
        compose.waitForIdle()
        // the 52 dp reply block and its 6 dp gap above the pill
        val replied = newest()
        assertEquals(58f, (plain - replied).value, 1f)
        assertEquals(rest.value, (composerTop() - 58.dp - replied).value, 1f)
        // the emoji button keeps to the bottom of the pill; the text field's top follows the pill's
        fun fieldTop() = compose.onNode(hasSetTextAction()).getBoundsInRoot().top
        val oneLine = fieldTop()
        compose.runOnIdle { input = TextFieldValue("one\ntwo\nthree") }
        compose.waitForIdle()
        // two more lines: the message moves up as far as the pill grew
        val grown = oneLine - fieldTop()
        assertTrue("grew $grown", grown > 30.dp)
        assertEquals(grown.value, (replied - newest()).value, 1f)
    }

    /** A search result is centred between the pills; the oldest one stops below the header, not under it. */
    @Test fun floatingFocusLandsBelowTheHeader() {
        var focus by mutableStateOf(MessageFocus("n12"))
        floatingChat(focus = { focus }) { longGroupChat(it).copy(unreadCount = 0, mentions = emptyList()) }
        val middle = compose.onNodeWithText("Reply number 12").getBoundsInRoot()
        val shown = (headerBottom() + composerTop()) / 2
        assertEquals(shown.value, ((middle.top + middle.bottom) / 2).value, 16f)
        compose.runOnIdle { focus = MessageFocus("x1") }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_floating_focus_oldest.png")
        val oldest = compose.onNodeWithText("Narek, are you coming", substring = true).getBoundsInRoot()
        assertTrue("${headerBottom()} $oldest", oldest.top > headerBottom())
    }

    /** "Scroll down" and "@" stay above the composer pill; messages hidden under the pill are not marked read. */
    @Test fun floatingJumpButtonsSitAboveTheComposer() {
        val read = mutableListOf<String>()
        val actions = object : ChatActions by FakeActions {
            override fun markRead(uuid: String) { read += uuid }
        }
        capture("chat_floating_scrolled", darkTheme = false, style = TatrakStyle.GLASS, floating = true, actions = actions, act = {
            compose.onNode(hasScrollAction()).performScrollToIndex(10)
        }) { longGroupChat(it) }
        // item 10 (n14) rests on the composer padding's edge; n15 and n16 lie under the pill
        assertEquals("n14", read.last())
        for (button in listOf("Scroll to the newest message", "Next mention")) {
            val bounds = compose.onNodeWithContentDescription(button).getBoundsInRoot()
            assertTrue("$button $bounds", bounds.bottom < composerTop() && bounds.top > headerBottom())
        }
    }

    // Slate and Sea phase 2: floating composer pill and soft bubbles (design_phase2.md items 2 and 5)
    @Test
    @Config(qualifiers = "+night")
    fun chatSlateReplyDark() =
        capture(
            "chat_slate_reply_dark",
            darkTheme = true,
            accent = Accent.SEA,
            style = TatrakStyle.SLATE_SEA,
            input = TextFieldValue("Sure, 10 works"),
            reply = ReplyPreview("m4", "Ani", "See you tomorrow at 10?", 0xFFE17076.toInt()),
        ) { privateChat(it) }

    @Test
    fun chatSlateEmojiPanel() =
        capture("chat_slate_emoji", darkTheme = false, accent = Accent.SEA, style = TatrakStyle.SLATE_SEA, act = {
            compose.onNodeWithContentDescription("Emoji").performClick()
        }) { privateChat(it) }

    @Test
    fun chatEmojiPanel() =
        capture("chat_emoji", darkTheme = false, act = {
            compose.onNodeWithContentDescription("Emoji").performClick()
        }) { privateChat(it) }

    /** "More reactions": the composer's emoji picker in a sheet (the picker loads its emoji asynchronously, empty here). */
    @Test
    fun reactionSheet() = reactionSheet("chat_reaction_sheet", darkTheme = false)

    @Test
    @Config(qualifiers = "+night")
    fun reactionSheetDark() = reactionSheet("chat_reaction_sheet_dark", darkTheme = true)

    private fun reactionSheet(name: String, darkTheme: Boolean) {
        compose.setContent {
            TatrakTheme(darkTheme = darkTheme) {
                // the sheet's container and content colours
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    ReactionSheetContent(remember { EmojiPickerSlot() }) {}
                }
            }
        }
        compose.onNodeWithText("Add reaction").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun chatSlateRecording() {
        compose.mainClock.autoAdvance = false
        capture(
            "chat_slate_recording",
            darkTheme = false,
            accent = Accent.SEA,
            style = TatrakStyle.SLATE_SEA,
            recording = VoiceRecording(true, SystemClock.elapsedRealtime() - 12_000),
            act = { compose.mainClock.advanceTimeBy(400) }, // lets the row fade in
        ) { privateChat(it) }
    }

    @Test
    @Config(qualifiers = "+night")
    fun chatSlateReadOnlyDark() =
        capture("chat_slate_readonly_dark", darkTheme = true, accent = Accent.SEA, style = TatrakStyle.SLATE_SEA) {
            privateChat(it).copy(composer = ComposerInfo(canWrite = false, hint = "You cannot write in this chat"))
        }

    private fun privateChat(s: Samples): ChatState {
        val items =
            listOf(
                    s.date("Yesterday"),
                    s.text("m1", false, GroupPosition.FIRST, "Hey! Did you push the *new build*?", "21:40"),
                    s.text("m2", false, GroupPosition.LAST, "The changelog is at https://example.am/changes", "21:41"),
                    s.text("m3", true, GroupPosition.SINGLE, "Yes, an hour ago 👍", "21:55", status = DeliveryStatus.READ,
                        reactions = listOf(ReactionChip("❤️", 1, false))),
                    s.date("Today"),
                    s.unread(2),
                    s.text("m4", false, GroupPosition.FIRST, "See you tomorrow at 10?", "09:12"),
                    s.text("m5", false, GroupPosition.LAST, "> The changelog is at https://example.am/changes\nAlso: the `--release` flag works now", "09:13",
                        reactions = listOf(ReactionChip("👍", 2, true), ReactionChip("🔥", 1, false))),
                    s.text("m6", true, GroupPosition.FIRST, "Fixd the typo", "09:20", status = DeliveryStatus.DELIVERED, edited = true),
                    s.text("m7", true, GroupPosition.MIDDLE, "Sure, 10 works", "09:20", status = DeliveryStatus.SENT)
                        .copy(reply = ReplyHeader("Ani", "See you tomorrow at 10?", 0xFFE17076.toInt(), "m4")),
                    s.text("m8", true, GroupPosition.LAST, "Sending this one while the network is down, it will go out later", "09:21", status = DeliveryStatus.SENDING),
                    s.emoji("m9", false, "😂", "09:30"),
                    s.text("m10", true, GroupPosition.SINGLE, "This one failed", "09:31", status = DeliveryStatus.FAILED,
                        actions = MessageActions(canRetry = true)),
                )
                .reversed()
        return ChatState(
            loaded = true,
            conversationUuid = "c1",
            header = ChatHeader("Ani", "online", subtitleActive = true, avatar = letterAvatar("Ani", 0xFFE17076.toInt()), isOnline = true),
            items = items,
            unreadCount = 2,
            composer = ComposerInfo(hint = "Send encrypted message"),
        )
    }

    private fun groupChat(s: Samples): ChatState {
        val aram = 0xFF2E8B57.toInt()
        val mom = 0xFFC2185B.toInt()
        val items =
            listOf(
                    s.date("Today"),
                    s.service("Narek joined the group"),
                    s.text("g1", false, GroupPosition.FIRST, "Who is coming on Saturday?", "10:01", sender = "Aram", color = aram),
                    s.text("g2", false, GroupPosition.LAST, "We need a headcount for the table", "10:01", sender = "Aram", color = aram, avatar = true),
                    s.text("g3", false, GroupPosition.SINGLE, "Me and dad 🙂", "10:05", sender = "Mom", color = mom, avatar = true,
                        reactions = listOf(ReactionChip("❤️", 3, true))),
                    s.text("g4", true, GroupPosition.SINGLE, "Count me in", "10:07", status = DeliveryStatus.READ),
                    s.text("g5", false, GroupPosition.SINGLE, "This key has not been verified yet", "10:09", sender = "Narek", color = 0xFF1565C0.toInt(), avatar = true,
                        trust = "Untrusted"),
                    s.info("g6", false, "Message was not encrypted for this device.", "10:10", sender = "Narek", color = 0xFF1565C0.toInt()),
                )
                .reversed()
        return ChatState(
            loaded = true,
            conversationUuid = "c2",
            header = ChatHeader("Family", "5 participants", avatar = letterAvatar("Family", 0xFFA695E7.toInt()), isGroup = true),
            items = items,
            composer = ComposerInfo(hint = "Send encrypted message"),
        )
    }

    /** A group with an unread mention of "Narek" far above the newest messages. */
    private fun longGroupChat(s: Samples): ChatState {
        val aram = 0xFF2E8B57.toInt()
        val older =
            listOf(
                s.date("Today"),
                s.text("x1", false, GroupPosition.SINGLE, "Narek, are you coming on Saturday?", "09:12", sender = "Aram", color = aram, avatar = true),
            )
        val newer =
            (1..24).map { i ->
                if (i % 3 == 0) s.text("n$i", true, GroupPosition.SINGLE, "Reply number $i", "10:%02d".format(i), status = DeliveryStatus.READ)
                else s.text("n$i", false, GroupPosition.SINGLE, "Message number $i", "10:%02d".format(i), sender = "Aram", color = aram, avatar = true)
            }
        return groupChat(s).copy(items = (older + newer).reversed(), unreadCount = 25, mentions = listOf("x1"))
    }

    private fun mediaChat(s: Samples): ChatState {
        val items =
            listOf(
                    s.date("Today"),
                    s.base("i1", false, GroupPosition.SINGLE, "11:00").copy(kind = MessageKind.IMAGE, media = MediaInfo(1600, 1200, false)),
                    s.base("i2", true, GroupPosition.SINGLE, "11:02", DeliveryStatus.DELIVERED).copy(kind = MessageKind.IMAGE, media = MediaInfo(720, 1280, true)),
                    s.base("f1", false, GroupPosition.FIRST, "11:05").copy(kind = MessageKind.FILE, file = FileInfo("PDF document", "2.4 MB", FileState.REMOTE)),
                    s.base("f2", false, GroupPosition.LAST, "11:05").copy(kind = MessageKind.FILE, file = FileInfo("Word document", "Downloading (40%)", FileState.DOWNLOADING, 40), actions = MessageActions(canCancel = true)),
                    s.base("f3", true, GroupPosition.SINGLE, "11:06", DeliveryStatus.SENT).copy(kind = MessageKind.FILE, file = FileInfo("Audiobook", "121 MB", FileState.LOCAL)),
                    s.base("voice-1", false, GroupPosition.SINGLE, "11:08").copy(kind = MessageKind.AUDIO, audioDurationMs = 19_000),
                    s.base("voice-2", true, GroupPosition.SINGLE, "11:09", DeliveryStatus.READ).copy(kind = MessageKind.AUDIO, audioDurationMs = 4_000),
                    s.base("l1", false, GroupPosition.SINGLE, "11:10").copy(kind = MessageKind.LOCATION),
                    s.base("c1", false, GroupPosition.SINGLE, "11:20").copy(kind = MessageKind.CALL, call = CallInfo("Missed call", null, true, R.drawable.ic_call_missed_24db)),
                    s.base("c2", true, GroupPosition.SINGLE, "11:25").copy(kind = MessageKind.CALL, call = CallInfo("Outgoing call", "3 minutes", false, R.drawable.ic_call_made_24dp)),
                )
                .reversed()
        return ChatState(
            loaded = true,
            conversationUuid = "c3",
            header = ChatHeader("Davit", "typing…", subtitleActive = true, avatar = letterAvatar("Davit", 0xFFFAA774.toInt())),
            items = items,
            composer = ComposerInfo(hint = "Send encrypted message"),
        )
    }

    /** The two photos of [mediaChat] alone, so both are on screen. */
    private fun photoChat(s: Samples): ChatState = mediaChat(s).let { st -> st.copy(items = st.items.filter { it.key in setOf("i1", "i2") }) }

    private fun captionChat(s: Samples): ChatState {
        // the caption is set apart from the text, as ChatSnapshot does
        fun MessageItem.captioned(body: String, outgoing: Boolean) = copy(caption = s.text("x", outgoing, GroupPosition.SINGLE, body, "").text)
        val items =
            listOf(
                    s.date("Today"),
                    s.base("i1", false, GroupPosition.SINGLE, "11:00")
                        .copy(kind = MessageKind.IMAGE, media = MediaInfo(1600, 1200, false))
                        .captioned("Lake Sevan this morning, more at https://example.com/sevan", false),
                    s.base("i2", true, GroupPosition.SINGLE, "11:02", DeliveryStatus.DELIVERED)
                        .copy(kind = MessageKind.IMAGE, media = MediaInfo(720, 1280, false))
                        .captioned("Mine", true),
                    s.base("f1", false, GroupPosition.SINGLE, "11:05")
                        .copy(kind = MessageKind.FILE, file = FileInfo("PDF document", "2.4 MB", FileState.LOCAL))
                        .captioned("The contract, page 3 needs a signature", false),
                    // an attachment whose file is gone keeps its caption
                    s.base("f2", false, GroupPosition.SINGLE, "11:07")
                        .copy(kind = MessageKind.INFO, text = AnnotatedString("File deleted"))
                        .captioned("Old scan of the passport", false),
                    s.base("voice-1", true, GroupPosition.SINGLE, "11:09", DeliveryStatus.READ)
                        .copy(kind = MessageKind.AUDIO, audioDurationMs = 19_000)
                        .captioned("Listen to the end", true),
                )
                .reversed()
        return ChatState(
            loaded = true,
            conversationUuid = "c3",
            header = ChatHeader("Davit", "online", avatar = letterAvatar("Davit", 0xFFFAA774.toInt())),
            items = items,
            composer = ComposerInfo(hint = "Send encrypted message"),
        )
    }

    class Samples(private val palette: TextPalette) {
        private val links = TextLinkStyles(SpanStyle(color = Color(palette.link)))

        fun date(day: String) = DateItem("date-$day", day)

        fun unread(count: Int) = UnreadItem("unread", "Today", count)

        fun service(text: String) = ServiceItem("s-$text", "Today", text)

        fun base(
            key: String,
            outgoing: Boolean,
            position: GroupPosition,
            time: String,
            status: DeliveryStatus = DeliveryStatus.NONE,
        ) =
            MessageItem(
                key = key,
                day = "Today",
                isOutgoing = outgoing,
                position = position,
                kind = MessageKind.TEXT,
                text = AnnotatedString(""),
                time = time,
                status = status,
                security = Security.ENCRYPTED,
                actions = MessageActions(canReply = true, canCopy = true, canEdit = outgoing, canShare = true, canReact = true, canRemoveReaction = true),
            )

        fun text(
            key: String,
            outgoing: Boolean,
            position: GroupPosition,
            body: String,
            time: String,
            status: DeliveryStatus = DeliveryStatus.NONE,
            edited: Boolean = false,
            reactions: List<ReactionChip> = emptyList(),
            sender: String? = null,
            color: Int = 0,
            avatar: Boolean = false,
            trust: String? = null,
            actions: MessageActions? = null,
        ): MessageItem {
            val formatted =
                MessageFormatter.format(
                        body,
                        if (outgoing) palette.textOutgoing else palette.textIncoming,
                        if (outgoing) palette.secondaryOutgoing else palette.secondaryIncoming,
                    )
                    .toAnnotatedString(links)
            val item = base(key, outgoing, position, time, status)
            return item.copy(
                text = formatted,
                edited = edited,
                reactions = reactions,
                senderName = if (position.isStart) sender else null,
                senderColor = color,
                showAvatar = avatar,
                avatar = if (avatar && sender != null) letterAvatar(sender, color) else null,
                avatarLetter = sender?.take(1) ?: "",
                trustWarning = trust,
                actions = actions ?: item.actions,
            )
        }

        fun emoji(key: String, outgoing: Boolean, emoji: String, time: String) =
            base(key, outgoing, GroupPosition.SINGLE, time).copy(kind = MessageKind.EMOJI, text = AnnotatedString(emoji))

        fun info(key: String, outgoing: Boolean, text: String, time: String, sender: String, color: Int) =
            base(key, outgoing, GroupPosition.SINGLE, time).copy(
                kind = MessageKind.INFO,
                text = AnnotatedString(text),
                senderName = sender,
                senderColor = color,
                showAvatar = true,
                avatar = letterAvatar(sender, color),
                actions = MessageActions.NONE,
            )
    }

    internal object FakeActions : NoChatActions() {
        override suspend fun loadThumbnail(uuid: String, sizePx: Int): Bitmap =
            gradient(sizePx, if (uuid == "i1") 0xFF4A90C2.toInt() else 0xFFC27A4A.toInt())

        override suspend fun previewThumbnail(key: String, sizePx: Int): Bitmap? =
            if (key == "p1") gradient(sizePx, 0xFF4A90C2.toInt()) else null
    }

    companion object {
        fun gradient(size: Int, color: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val paint = Paint()
            paint.shader =
                LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), color, 0xFF1B2B3A.toInt(), Shader.TileMode.CLAMP)
            Canvas(bitmap).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            return bitmap
        }

        fun letterAvatar(name: String, color: Int): Bitmap {
            val size = 120
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(color)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.White.toArgb()
                    textSize = size * 0.42f
                    textAlign = Paint.Align.CENTER
                }
            val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(name.take(1).uppercase(), size / 2f, y, paint)
            return bitmap
        }
    }
}
