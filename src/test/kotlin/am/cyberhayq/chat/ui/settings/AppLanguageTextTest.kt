package am.cyberhayq.chat.ui.settings

import am.cyberhayq.chat.ui.chat.ChatSnapshot
import am.cyberhayq.chat.ui.chat.ChatViewModel
import am.cyberhayq.chat.ui.chat.DateItem
import am.cyberhayq.chat.ui.chat.FormattedTextCache
import am.cyberhayq.chat.ui.chat.MentionTracker
import am.cyberhayq.chat.ui.chat.MessageItem
import am.cyberhayq.chat.ui.chat.SnapshotParams
import am.cyberhayq.chat.ui.chat.TextPalette
import am.cyberhayq.chat.ui.chatlist.ChatListViewModel
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ReviewFlowsTest
import eu.siacs.conversations.xmpp.Jid
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Snapshot text in the in-app language with an English system on API 30, where the application context the view
 * models hold keeps the system language. The models are made before the choice, as one kept across the recreation.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(
    application = ReviewFlowsTest.TestApp::class,
    sdk = [30],
    qualifiers = "en-rUS",
    shadows = [ReviewFlowsTest.NoAxolotlStore::class],
)
class AppLanguageTextTest {

    private val app get() = ApplicationProvider.getApplicationContext<ReviewFlowsTest.TestApp>()

    @After fun reset() = setAppLanguage(null)

    /** The snapshot thread is a real IO thread: waits for a published value. */
    private fun <T> await(read: () -> T?, done: (T) -> Boolean): T {
        val until = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            read()?.let { if (done(it)) return it }
            if (System.currentTimeMillis() > until) return read() ?: error("nothing built")
            Thread.sleep(20)
        }
    }

    @Test
    fun chatListAndChatFollowTheChosenLanguage() {
        val service = ReviewFlowsTest.TestService(app)
        val group = service.chat(service.a, Jid.of("room@conference.example.com"), muc = true)
        group.add(Message(group, "hi all", Message.ENCRYPTION_NONE, Message.STATUS_SEND))
        val list = ChatListViewModel(app)
        val chat = ChatViewModel(app).apply { setPalette(TextPalette(0, 0, 0, 0, 0)) }
        chat.bind(group, service)
        try {
            fun sender(expected: String): String? {
                list.invalidate(service)
                return await({ list.state.value.chats.firstOrNull()?.previewSender }) { it == expected }
            }
            fun day(expected: String): String? {
                chat.invalidate(service)
                return await({ chat.state.value.items.filterIsInstance<DateItem>().firstOrNull()?.day }) { it == expected }
            }
            assertEquals("You", sender("You"))
            assertEquals("Today", day("Today"))
            setAppLanguage("ru")
            assertEquals("Вы", sender("Вы"))
            assertEquals("Сегодня", day("Сегодня"))
        } finally {
            list.viewModelScope.cancel()
            chat.viewModelScope.cancel()
        }
    }

    /** The formatted-text cache outlives the choice in the chat's view model: the private marker must not stay English. */
    @Test
    fun cachedPrivateMarkerFollowsTheLanguage() {
        val service = ReviewFlowsTest.TestService(app)
        val group = service.chat(service.a, Jid.of("room@conference.example.com"), muc = true)
        group.add(
            Message(group, "psst", Message.ENCRYPTION_NONE, Message.STATUS_SEND).apply {
                type = Message.TYPE_PRIVATE
                counterpart = Jid.of("room@conference.example.com/bob")
            }
        )
        val params = SnapshotParams(TextPalette(0, 0, 0, 0, 0), null, 48, true, 0, false, MentionTracker())
        val cache = FormattedTextCache()
        fun text() =
            ChatSnapshot.build(appLanguageContext(app), service, group, params, cache).items.filterIsInstance<MessageItem>().single().text.text
        assertTrue(text(), text().contains("to bob"))
        setAppLanguage("ru")
        assertTrue(text(), text().contains("Лично bob"))
    }
}
