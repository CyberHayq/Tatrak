package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.storage.JvmDurable
import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import am.cyberhayq.chat.storage.media.MediaKeyTable
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.MessageSearchResults
import eu.siacs.conversations.ui.ReviewFlowsTest
import eu.siacs.conversations.ui.ChatFragment
import eu.siacs.conversations.xmpp.Jid
import java.io.File
import java.io.IOException
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The app-wide voice player (issue #7, step 1), driven through a fake MediaPlayer. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(
    application = ReviewFlowsTest.TestApp::class,
    sdk = [35],
    shadows = [ReviewFlowsTest.NoAxolotlStore::class, ReviewFlowsTest.NoPushRenewal::class],
)
class VoiceSessionTest {

    /**
     * Prepares with a set length; plays from `setPlaybackParams` (as `VoicePlayer.begin`); ends on [finish]. [awake]
     * follows the platform's wake lock of [setWakeMode]: taken in start(), given up on pause, completion and release.
     */
    class FakePlayer(private val length: Int = 19_000, private val damaged: Boolean = false) : MediaPlayer() {
        var playing = false
        var wakeMode = 0
        var awake = false
        /** setPlaybackParams throws, as a player in a wrong state or without the parameters. */
        var refuseParams = false
        var position = 0
        var released = false
        private var params = PlaybackParams()
        private var completion: OnCompletionListener? = null
        val speed get() = params.speed

        override fun setDataSource(dataSource: MediaDataSource) = Unit

        override fun prepare() {
            if (damaged) throw IOException("damaged")
        }

        override fun isPlaying() = playing

        override fun getDuration() = length

        override fun getCurrentPosition() = position

        /** A seek lands only through [landSeek], as the platform's asynchronous one. */
        var deferSeek = false
        private var seekDone: OnSeekCompleteListener? = null

        override fun seekTo(msec: Int) {
            if (deferSeek) return
            landSeek(msec)
        }

        fun landSeek(at: Int) {
            position = at
            seekDone?.onSeekComplete(this)
        }

        override fun setOnSeekCompleteListener(listener: OnSeekCompleteListener?) {
            seekDone = listener
        }

        override fun start() {
            playing = true
            awake = wakeMode != 0
        }

        override fun setWakeMode(context: Context, mode: Int) {
            wakeMode = mode
        }

        override fun pause() {
            playing = false
            awake = false
        }

        override fun release() {
            playing = false
            awake = false
            released = true
        }

        override fun getPlaybackParams() = params

        override fun setPlaybackParams(params: PlaybackParams) {
            if (refuseParams) throw IllegalArgumentException("refused")
            this.params = params
            if (params.speed > 0f) playing = true
        }

        override fun setOnCompletionListener(listener: OnCompletionListener?) {
            completion = listener
        }

        override fun setOnErrorListener(listener: OnErrorListener?) = Unit

        override fun setAudioAttributes(attributes: android.media.AudioAttributes) = Unit

        fun finish() {
            playing = false
            awake = false
            position = length
            completion?.onCompletion(this)
        }
    }

    private val app get() = ApplicationProvider.getApplicationContext<ReviewFlowsTest.TestApp>()
    private lateinit var service: ReviewFlowsTest.TestService
    private lateinit var chat: Conversation
    private lateinit var store: EncryptedMediaStore
    private val players = ArrayList<FakePlayer>()
    private var damaged = false
    private var refuse = false
    private val player get() = players.last()

    @Before
    fun setUp() {
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
        service = ReviewFlowsTest.TestService(app)
        chat = service.chat(service.a, Jid.of("friend@example.com"))
        VoiceSession.init(app) { FakePlayer(damaged = damaged).also { it.refuseParams = refuse; players.add(it) } }
    }

    @After
    fun tearDown() {
        VoiceSession.release()
        EncryptedMediaStore.install(null)
        JvmDurable.uninstall()
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** A received voice message whose file is in the media store. */
    private fun voice(name: String = "v.m4a", conversation: Conversation = chat): Pair<Message, File> {
        val file = store.createAttachment(store.attachmentFile(name)).run {
            outputStream.write(ByteArray(64) { it.toByte() })
            commit()
        }
        val message = Message(conversation, "https://up.example.com/$name|64|0|0|19000", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED)
        message.type = Message.TYPE_FILE
        conversation.add(message)
        return message to file
    }

    private fun play(voice: Pair<Message, File>) =
        VoiceSession.toggle(service, voice.first.conversation.uuid, voice.first.uuid, voice.second)

    /** Whether the 100 ms ticker publishes: the position moves and shows within two ticks. */
    private fun ticking(): Boolean {
        player.position += 1_234
        idle(250)
        return VoiceSession.playback.value.positionMs == player.position
    }

    private fun released() = !VoiceSession.active.value && VoiceSession.bar.value == null && VoiceSession.playback.value.uuid == null

    @Test
    fun playPauseAndSeekFromTheBubble() {
        val v = voice()
        play(v)
        assertTrue(player.playing)
        assertTrue(VoiceSession.active.value)
        assertEquals(v.first.uuid, VoiceSession.bar.value?.messageUuid)
        assertEquals(chat.uuid, VoiceSession.bar.value?.conversationUuid)

        VoiceSession.seek(v.first.uuid, 0.5f)
        assertEquals(9_500, player.position)
        // another bubble's seek does nothing
        VoiceSession.seek("other", 0.1f)
        assertEquals(9_500, player.position)

        play(v)
        assertFalse(player.playing)
        assertTrue(VoiceSession.active.value)
        assertFalse(VoiceSession.bar.value!!.playing)
        play(v)
        assertTrue(player.playing)
        assertEquals(1, players.size)
    }

    @Test
    fun anotherMessageSwitches() {
        val first = voice("a.m4a")
        val second = voice("b.m4a")
        play(first)
        play(second)
        assertTrue(players[0].released)
        assertTrue(players[1].playing)
        assertEquals(second.first.uuid, VoiceSession.bar.value?.messageUuid)
    }

    @Test
    fun theLastStoppedActivityStopsOnlyTheTicker() {
        val one = Robolectric.buildActivity(Activity::class.java).setup()
        val two = Robolectric.buildActivity(Activity::class.java).setup()
        // the first play happens with both already started
        play(voice())
        two.pause().stop()
        assertTrue("one activity still started", ticking())
        one.pause().stop()
        assertTrue("plays on in the background (step 2)", player.playing)
        assertTrue(VoiceSession.active.value)
        assertFalse("no ticker while nothing is visible", ticking())
        one.start().resume()
        assertTrue("ticks again when a screen is visible", ticking())
        one.pause().stop().destroy()
        two.destroy()
    }

    @Test
    fun aRotationThenHomePlaysOn() {
        val one = Robolectric.buildActivity(Activity::class.java).setup()
        play(voice())
        val rotated = one.recreate()
        assertTrue(player.playing)
        assertTrue("a rotation keeps the ticker", ticking())
        rotated.pause().stop()
        assertTrue(player.playing)
        assertTrue(VoiceSession.active.value)
        assertFalse(ticking())
        rotated.destroy()
    }

    @Test
    fun speedCyclesPersistsAndAPausedChangeDoesNotStart() {
        val v = voice()
        play(v)
        VoiceSession.cycleSpeed()
        assertEquals(1.5f, player.speed)
        assertEquals(1.5f, VoiceSession.bar.value?.speed)
        play(v) // pause
        VoiceSession.cycleSpeed()
        assertFalse(player.playing)
        assertEquals(2f, VoiceSession.bar.value?.speed)
        assertEquals(2f, PreferenceManager.getDefaultSharedPreferences(app).getFloat("tatrak_voice_speed", 0f))
        play(v) // resume at the stored speed
        assertTrue(player.playing)
        assertEquals(2f, player.speed)
        VoiceSession.cycleSpeed()
        assertEquals(1f, player.speed)

        VoiceSession.cycleSpeed()
        VoiceSession.init(app) { FakePlayer().also(players::add) }
        play(voice("c.m4a"))
        assertEquals("kept across a restart", 1.5f, player.speed)
    }

    @Test
    fun theChatArchivedReleases() = goneAfter { chat.status = Conversation.STATUS_ARCHIVED }

    @Test
    fun theAccountDisabledReleases() = goneAfter { service.a.setOption(Account.OPTION_DISABLED, true) }

    @Test
    fun aDeleteForMeOfASentMessageReleases() = goneAfter { message, _ ->
        message.eraseContent()
        message.type = Message.TYPE_HIDDEN
    }

    @Test
    fun aDeleteForMeOfAnUnsentMessageReleases() = goneAfter { message, file ->
        chat.clearMessages()
        assertNull(chat.findMessageWithUuid(message.uuid))
        file.delete()
    }

    @Test
    fun aDeletedFileReleases() = goneAfter { message, _ -> message.isDeleted = true }

    @Test
    fun theChatGoneReleases() = goneAfter { service.chats.remove(chat) }

    @Test
    fun aClearedChatWithANewMessageReleases() = goneAfter { message, _ ->
        chat.clearMessages()
        chat.setLastClearHistory(message.timeSent + 1, null)
        // a new message arrives at once: the window is not empty
        voice("new.m4a")
    }

    @Test
    fun aNoteJustAfterAClearKeepsPlaying() {
        val v = voice()
        // the service puts the marker a second past the last message at the clear: this note arrived inside it
        chat.setLastClearHistory(v.first.timeSent + 1_000, null)
        play(v)
        idle(2_500)
        assertTrue(VoiceSession.active.value)
        assertTrue(player.playing)
    }

    @Test
    fun aMessageOnlyTrimmedFromMemoryKeepsPlaying() {
        val v = voice()
        play(v)
        chat.clearMessages() // as trim(): out of the window, its file still there
        idle(2_500)
        assertTrue(VoiceSession.active.value)
        assertTrue(player.playing)
    }

    @Test
    fun anotherServiceInstanceReleases() {
        play(voice())
        val first = player
        val next = ReviewFlowsTest.TestService(app)
        val other = next.chat(next.a, Jid.of("friend@example.com"))
        val v = voice("n.m4a", other)
        VoiceSession.toggle(next, other.uuid, v.first.uuid, v.second)
        assertTrue(first.released)
        assertTrue(player.playing)
    }

    @Test
    fun anotherServiceInstanceBoundReleases() {
        play(voice())
        VoiceSession.bound(service)
        assertTrue("the same instance keeps playing", player.playing)
        VoiceSession.bound(ReviewFlowsTest.TestService(app))
        assertTrue(released())
        assertTrue(player.released)
    }

    @Test
    fun refusedPlaybackParamsRelease() {
        refuse = true
        play(voice())
        assertTrue("a start the player refuses", released())
        assertTrue(player.released)
        refuse = false
        val v = voice("b.m4a")
        play(v)
        player.refuseParams = true
        VoiceSession.cycleSpeed()
        assertTrue("a refused speed keeps playing", player.playing)
        assertTrue(VoiceSession.active.value)
        play(v) // pause
        play(v) // a resume the player refuses
        assertTrue(released())
        assertTrue(player.released)
    }

    @Test
    fun aDuckRequestPauses() {
        play(voice())
        val request = shadowOf(app.getSystemService(AudioManager::class.java)).lastAudioFocusRequest
        assertTrue(request.audioFocusRequest.willPauseWhenDucked())
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertFalse(player.playing)
        assertTrue(VoiceSession.active.value)
    }

    @Test
    fun completionReleasesAndTheBubbleShowsItsLength() {
        val v = voice()
        play(v)
        player.finish()
        assertFalse(VoiceSession.active.value)
        assertNull(VoiceSession.bar.value)
        val after = VoiceSession.playback.value
        // the bubble falls back to the message's own length, and a seek has nothing to move
        assertNull(after.uuid)
        VoiceSession.seek(v.first.uuid, 0.5f)
        assertNull(VoiceSession.playback.value.uuid)
    }

    @Test
    fun focusDeniedReleases() {
        play(voice())
        play(voice("b.m4a")) // switch: the player is replaced
        val audio = app.getSystemService(AudioManager::class.java)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        play(voice("c.m4a"))
        assertTrue(released())
    }

    @Test
    fun aDamagedFileReleases() {
        damaged = true
        play(voice())
        assertTrue(released())
    }

    @Test
    fun initTwiceKeepsOneSetOfCallbacksAndResets() {
        val one = Robolectric.buildActivity(Activity::class.java).setup()
        play(voice())
        VoiceSession.setChatShown(chat.uuid)
        VoiceSession.init(app) { FakePlayer().also(players::add) }
        assertTrue(released())
        assertNull(VoiceSession.chatShown.value)
        // counted from zero by one callback: one stop after a start stops the ticker
        val two = Robolectric.buildActivity(Activity::class.java).setup()
        play(voice("b.m4a"))
        assertTrue(ticking())
        two.pause().stop()
        assertFalse(ticking())
        assertTrue(player.playing)
        one.pause().stop().destroy()
        two.destroy()
    }

    @Test
    fun unpluggingPauses() {
        play(voice())
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertFalse(player.playing)
        assertTrue(VoiceSession.active.value)
    }

    @Test
    fun theBarPlaysPausesClosesAndOpensTheMessage() {
        val v = voice()
        play(v)
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val actions = VoiceSession.barActions(host.get())
        actions.toggle()
        assertFalse(player.playing)
        actions.toggle()
        assertTrue(player.playing)
        actions.open()
        val started = shadowOf(host.get()).nextStartedActivity
        assertEquals(MessageSearchResults.openAtIntent(host.get(), chat.uuid, v.first.uuid).toUri(0), started.toUri(0))
        assertEquals(v.first.uuid, started.getStringExtra(ChatFragment.EXTRA_MESSAGE_UUID))
        actions.close()
        assertTrue(released())
        assertTrue(player.released)
        host.pause().stop().destroy()
    }

    @Test
    fun theBarNamesTheSender() {
        play(voice())
        assertNotNull(VoiceSession.bar.value)
        assertTrue(VoiceSession.bar.value!!.sender.isNotEmpty())
    }

    @Test
    fun aStoppingFragmentDoesNotClearANewerChat() {
        VoiceSession.setChatShown("a")
        VoiceSession.setChatShown("b")
        VoiceSession.clearChatShown("a")
        assertEquals("b", VoiceSession.chatShown.value)
        VoiceSession.clearChatShown("b")
        assertNull(VoiceSession.chatShown.value)
    }

    private fun goneAfter(change: () -> Unit) = goneAfter { _, _ -> change() }

    private fun goneAfter(change: (Message, File) -> Unit) {
        val v = voice()
        play(v)
        idle(1_500)
        assertTrue("plays before the change", VoiceSession.active.value)
        change(v.first, v.second)
        idle(1_100)
        assertTrue(released())
        assertTrue(player.released)
    }

    private companion object {
        const val GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11"
    }
}
