package eu.siacs.conversations.ui

import am.cyberhayq.chat.storage.JvmDurable
import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import am.cyberhayq.chat.storage.media.MediaKeyTable
import am.cyberhayq.chat.ui.accounts.AccountFilter
import am.cyberhayq.chat.ui.chat.VoiceSession
import am.cyberhayq.chat.ui.chat.VoiceSessionTest.FakePlayer
import am.cyberhayq.chat.ui.theme.FloatingBars
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xmpp.Jid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Issue #7, step 1, through the real screens: playback outlives the chat that started it. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(
    application = ReviewFlowsTest.TestApp::class,
    sdk = [35],
    qualifiers = "w411dp-h891dp",
    shadows = [ReviewFlowsTest.NoAxolotlStore::class, ReviewFlowsTest.NoPushRenewal::class],
)
class VoiceFlowsTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var controller: ActivityController<ReviewFlowsTest.Host>
    private val activity get() = controller.get()
    private val fm get() = activity.supportFragmentManager
    private val service get() = checkNotNull(ReviewFlowsTest.Host.service)
    private val main get() = fm.findFragmentById(R.id.main_fragment)
    private val secondary get() = fm.findFragmentById(R.id.secondary_fragment)
    private val players = ArrayList<FakePlayer>()
    private val player get() = players.last()
    private lateinit var store: EncryptedMediaStore
    private lateinit var a: Conversation
    private lateinit var b: Conversation

    @Before
    fun start() {
        val app = ApplicationProvider.getApplicationContext<ReviewFlowsTest.TestApp>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS)
        FloatingBars.set(app, true)
        AccountFilter.select(app, null)
        JvmDurable.install()
        store =
            EncryptedMediaStore(
                app.filesDir,
                app.cacheDir,
                object : EncryptedMediaStore.KeySource {
                    override fun generation() = GENERATION

                    override fun read() = MediaKeyTable.Keys(GENERATION, mapOf(GENERATION to ByteArray(32)))
                },
            )
        EncryptedMediaStore.install(store)
        // TestApp skips Conversations.onCreate, where the app calls it
        VoiceSession.init(app) { FakePlayer().also(players::add) }
        ReviewFlowsTest.Host.service = ReviewFlowsTest.TestService(app)
        a = service.chat(service.a, Jid.of("friend@example.com"))
        b = service.chat(service.a, Jid.of("other@example.com"))
        controller = Robolectric.buildActivity(ReviewFlowsTest.Host::class.java).setup()
        fm.executePendingTransactions()
    }

    @After
    fun stop() {
        try {
            shadowOf(Looper.getMainLooper()).idle()
            controller.close()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            VoiceSession.release()
            ReviewFlowsTest.Host.service = null
            EncryptedMediaStore.install(null)
            JvmDurable.uninstall()
        }
    }

    /** A received voice message in [chat] with its file in the media store. */
    private fun voice(chat: Conversation): Message {
        val file = store.createAttachment(store.attachmentFile("${chat.uuid.take(8)}.m4a")).run {
            outputStream.write(ByteArray(64) { it.toByte() })
            commit()
        }
        return Message(chat, "https://up.example.com/v.m4a|64|0|0|19000", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply {
            type = Message.TYPE_FILE
            relativeFilePath = Message.StorageLocation(file, false)
            chat.add(this)
        }
    }

    private fun open(chat: Conversation) {
        activity.switchToConversation(chat)
        fm.executePendingTransactions()
    }

    private fun chatFragment() = (secondary ?: main) as ChatFragment

    /** Opens [chat] and plays its voice message from the bubble. */
    private fun playIn(chat: Conversation): Message {
        val message = voice(chat)
        open(chat)
        chatFragment().toggleVoice(message.uuid)
        assertTrue(player.playing)
        return message
    }

    @Test
    fun playbackOutlivesTheChatFragment() {
        val message = playIn(a)
        assertEquals(a.uuid, VoiceSession.chatShown.value)
        activity.onBackPressedDispatcher.onBackPressed()
        fm.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(main is ChatListFragment)
        assertTrue(player.playing)
        assertNull("the chat stopped", VoiceSession.chatShown.value)
        assertEquals(message.uuid, VoiceSession.bar.value?.messageUuid)
        // the list shows the bar now
        compose.onAllNodesWithText("Voice message ·", substring = true).assertCountEquals(1)
    }

    @Test
    fun playbackGoesOnWhenAnotherChatOpensInTheSameFragment() {
        playIn(a)
        val chat = chatFragment()
        activity.deliver(Intent(ConversationsActivity.ACTION_VIEW_CONVERSATION).putExtra(ConversationsActivity.EXTRA_CONVERSATION, b.uuid))
        fm.executePendingTransactions()
        assertSame(chat, chatFragment())
        assertSame(b, chat.getConversation())
        assertTrue(player.playing)
        assertEquals(b.uuid, VoiceSession.chatShown.value)
    }

    @Test
    fun aRotationKeepsPlaybackAndTheShownChat() {
        playIn(a)
        controller.recreate()
        fm.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(player.playing)
        assertEquals(1, players.size)
        assertEquals(a.uuid, VoiceSession.chatShown.value)
        // then Home: the last started activity stops
        controller.pause().stop()
        assertFalse(player.playing)
        assertTrue(VoiceSession.active.value)
        controller.start().resume()
        assertFalse("no auto-resume", player.playing)
    }

    @Test
    fun anotherScreenOverTheChatKeepsPlaying() {
        playIn(a)
        // search or edit history: a new activity starts before the chat's stops
        val over = Robolectric.buildActivity(Activity::class.java).setup()
        controller.pause().stop()
        assertTrue(player.playing)
        assertNull(VoiceSession.chatShown.value)
        over.pause().stop().destroy()
        assertFalse(player.playing)
        controller.start().resume()
        assertEquals(a.uuid, VoiceSession.chatShown.value)
    }

    @Test
    fun recordingPausesPlayback() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        playIn(a)
        chatFragment().startRecording()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(player.playing)
        assertTrue(VoiceSession.active.value)
    }

    @Test
    fun theClassicViewReleases() {
        playIn(a)
        val pgp = service.chat(service.a, Jid.of("pgp@example.com"), encryption = Message.ENCRYPTION_PGP)
        open(pgp)
        assertTrue(main is ConversationFragment)
        assertFalse(VoiceSession.active.value)
        assertTrue(player.released)
    }

    @Test
    @Config(qualifiers = "w1100dp-h760dp")
    fun theTabletListHidesItsBarWhileAChatIsShown() {
        assertTrue(ConversationsActivity.isTabletView(activity))
        playIn(a)
        assertEquals(a.uuid, VoiceSession.chatShown.value)
        // the chat pane shows the bar, the list beside it does not
        compose.onAllNodesWithText("Voice message ·", substring = true).assertCountEquals(1)
    }

    private companion object {
        const val GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11"
    }
}
