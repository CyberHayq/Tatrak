package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.storage.JvmDurable
import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import am.cyberhayq.chat.storage.media.MediaKeyTable
import am.cyberhayq.chat.ui.chat.VoiceSessionTest.FakePlayer
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.session.PlaybackState
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.MessageSearchResults
import eu.siacs.conversations.ui.ReviewFlowsTest
import eu.siacs.conversations.xmpp.Jid
import java.io.File
import java.time.Duration
import android.app.Service
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.util.ReflectionHelpers

/** The background part of the voice player (issue #7, step 2a): the service, its media session and notification. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(
    application = ReviewFlowsTest.TestApp::class,
    sdk = [35],
    shadows = [ReviewFlowsTest.NoAxolotlStore::class, ReviewFlowsTest.NoPushRenewal::class, VoicePlaybackServiceTest.QueuedStarts::class],
)
class VoicePlaybackServiceTest {
    /** `stopSelfResult` as Android answers it: false while a start newer than the id is queued ([newest]). */
    @Implements(Service::class)
    class QueuedStarts : ShadowService() {
        @Implementation
        override fun stopSelfResult(startId: Int): Boolean {
            super.stopSelfResult(startId)
            return startId >= newest
        }

        companion object {
            var newest = 0
        }
    }

    private val app get() = ApplicationProvider.getApplicationContext<ReviewFlowsTest.TestApp>()
    private lateinit var service: ReviewFlowsTest.TestService
    private lateinit var chat: Conversation
    private lateinit var store: EncryptedMediaStore
    private val players = ArrayList<FakePlayer>()
    private var damaged = false
    /** Android refuses the foreground start (ForegroundServiceStartNotAllowedException is an IllegalStateException). */
    private var refuse = false
    private var startId = 0
    private val player get() = players.last()
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val notification: Notification? get() = notifications.getNotification(VoicePlaybackService.NOTIFICATION_ID)

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
        QueuedStarts.newest = 0
        VoiceSession.init(
            app,
            { context, intent -> if (refuse) throw IllegalStateException("refused") else ContextCompat.startForegroundService(context, intent) },
        ) {
            FakePlayer(damaged = damaged).also(players::add)
        }
    }

    @After
    fun tearDown() {
        VoiceSession.release()
        EncryptedMediaStore.install(null)
        JvmDurable.uninstall()
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** A received voice message whose file is in the media store. */
    private fun voice(name: String = "v.m4a"): Pair<Message, File> {
        val file = store.createAttachment(store.attachmentFile(name)).run {
            outputStream.write(ByteArray(64) { it.toByte() })
            commit()
        }
        val message = Message(chat, "https://up.example.com/$name|64|0|0|19000", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED)
        message.type = Message.TYPE_FILE
        chat.add(message)
        return message to file
    }

    private fun play(voice: Pair<Message, File>) = VoiceSession.toggle(service, chat.uuid, voice.first.uuid, voice.second)

    private fun released() = !VoiceSession.active.value && VoiceSession.bar.value == null

    /** The start the session asked for, delivered as Android would. */
    private fun startRequested(): Intent? =
        generateSequence { shadowOf(app).nextStartedService }.lastOrNull { it.component?.className == VoicePlaybackService::class.java.name }

    private fun deliver(intent: Intent, controller: ServiceController<VoicePlaybackService>? = null): ServiceController<VoicePlaybackService> =
        (controller ?: Robolectric.buildService(VoicePlaybackService::class.java, intent).create()).also {
            it.withIntent(intent).startCommand(0, ++startId)
        }

    /** Plays [v] and starts the service from the session's request. */
    private fun playInService(v: Pair<Message, File> = voice()): ServiceController<VoicePlaybackService> {
        play(v)
        val intent = startRequested()
        assertNotNull("the session asked for the service", intent)
        return deliver(intent!!)
    }

    private fun state(controller: ServiceController<VoicePlaybackService>) =
        ReflectionHelpers.getField<PlaybackState>(controller.get().session, "mPlaybackState")

    private fun denyFocus() =
        shadowOf(app.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

    private fun command(controller: ServiceController<VoicePlaybackService>, action: String) =
        deliver(Intent(app, VoicePlaybackService::class.java).setAction(action), controller)

    @Test
    fun aFileThatCannotPlayStartsNoService() {
        damaged = true
        play(voice())
        assertTrue(released())
        assertNull(startRequested())
        damaged = false
        val v = voice("b.m4a")
        play(v)
        assertNotNull(startRequested())
        play(v) // a pause starts nothing
        assertNull(startRequested())
    }

    /** Inside a running event loop the mirrored [VoiceSession.playback] lags: the start follows the player itself. */
    @Test
    fun aPlayInsideARunningEventLoopAsksForTheService() {
        val v = voice()
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        scope.launch { play(v) }
        assertNotNull("play", startRequested())
        scope.launch { play(v) }
        assertFalse(player.playing)
        assertNull("a pause asks for nothing", startRequested())
        scope.launch { play(v) }
        assertTrue(player.playing)
        assertNotNull("resume", startRequested())
        scope.cancel()
    }

    @Test
    fun aSessionReleasedBeforeTheStartGoesForegroundThenStops() {
        play(voice())
        val intent = startRequested()!!
        VoiceSession.release()
        val controller = deliver(intent)
        val shadow = shadowOf(controller.get())
        assertEquals("startForeground was called", VoicePlaybackService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, controller.get().foregroundServiceType)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(startId, shadow.stopSelfResultId)
    }

    @Test
    fun aStopWithNoNewerStartLeavesTheForegroundAndTheNextPlayStartsAgain() {
        val controller = playInService()
        val shadow = shadowOf(controller.get())
        VoiceSession.close()
        assertTrue(shadow.isForegroundStopped)
        assertEquals(1, shadow.stopSelfResultId)
        assertFalse(VoiceSession.serviceRunning)
        controller.destroy()
        // the next play starts a new instance
        play(voice("b.m4a"))
        assertTrue(VoiceSession.serviceRunning)
        val next = deliver(startRequested()!!)
        assertFalse(shadowOf(next.get()).isForegroundStopped)
        assertNotNull(notification)
        next.destroy()
    }

    @Test
    fun aStartQueuedBeforeTheStopKeepsTheForeground() {
        val v = voice()
        val controller = playInService(v)
        val shadow = shadowOf(controller.get())
        play(v)
        play(v) // a resume: asks for start 2, still queued when the close comes
        val queued = startRequested()!!
        QueuedStarts.newest = 2
        VoiceSession.close()
        assertEquals(1, shadow.stopSelfResultId)
        assertFalse("start 2 keeps it in the foreground", shadow.isForegroundStopped)
        assertTrue(VoiceSession.serviceRunning)
        // start 2 arrives: nothing to play, so it goes foreground and stops with its own id
        deliver(queued, controller)
        assertTrue(shadow.isForegroundStopped)
        assertEquals(2, shadow.stopSelfResultId)
        assertFalse(VoiceSession.serviceRunning)
    }

    @Test
    fun theContentIntentPointsAtThePlayingMessage() {
        val first = voice("a.m4a")
        val controller = playInService(first)
        fun opened() = shadowOf(notification!!.contentIntent).savedIntent
        assertEquals(MessageSearchResults.openAtIntent(app, chat.uuid, first.first.uuid).toUri(0), opened().toUri(0))
        val second = voice("b.m4a")
        play(second)
        assertEquals(MessageSearchResults.openAtIntent(app, chat.uuid, second.first.uuid).toUri(0), opened().toUri(0))
        // only "Voice message": no sender, no chat
        assertEquals(app.getString(R.string.tatrak_voice_message), notification!!.extras.getString(Notification.EXTRA_TITLE))
        assertNull(notification!!.extras.getCharSequence(Notification.EXTRA_TEXT))
        controller.destroy()
    }

    @Test
    fun theNotificationIsNotRebuiltPerTick() {
        val screen = Robolectric.buildActivity(Activity::class.java).setup()
        playInService()
        val shown = notification
        assertEquals(app.getString(R.string.pause_audio), shown!!.actions[0].title)
        repeat(5) {
            player.position += 300
            idle(100)
        }
        assertEquals("the bar ticks", player.position, VoiceSession.bar.value?.positionMs)
        assertSame("the notification does not", shown, notification)
        VoiceSession.pause()
        assertNotSame(shown, notification)
        assertEquals(app.getString(R.string.play_audio), notification!!.actions[0].title)
        screen.pause().stop().destroy()
    }

    @Test
    fun aSeekUpdatesTheSessionWhenItLandsAndDoesNotRepostTheNotification() {
        val controller = playInService()
        player.deferSeek = true
        val shown = notification
        controller.get().callback.onSeekTo(9_000)
        assertEquals("not landed yet", 0, player.position)
        assertEquals(0L, state(controller).position)
        player.landSeek(8_500) // the sync point before
        assertEquals(8_500L, state(controller).position)
        assertEquals(8_500, VoiceSession.bar.value?.positionMs)
        assertSame("the notification shows no position", shown, notification)
        VoiceSession.cycleSpeed()
        assertEquals(1.5f, state(controller).playbackSpeed)
        assertSame(shown, notification)
        controller.destroy()
    }

    @Test
    fun everyStopPathReleasesTheWakeLock() {
        val paths =
            listOf<Pair<String, (ServiceController<VoicePlaybackService>, Pair<Message, File>) -> Unit>>(
                "bubble pause" to { _, v -> play(v) },
                "close" to { _, _ -> VoiceSession.close() },
                "completion" to { _, _ -> player.finish() },
                "focus lost" to { _, _ ->
                    shadowOf(app.getSystemService(AudioManager::class.java)).lastAudioFocusRequest.listener
                        .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
                },
                "unplugged" to { _, _ ->
                    app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                    idle()
                },
                "message gone" to { _, _ ->
                    chat.status = Conversation.STATUS_ARCHIVED
                    idle(1_100)
                },
                "session pause" to { c, _ -> c.get().callback.onPause() },
                "session stop" to { c, _ -> c.get().callback.onStop() },
                "notification toggle" to { c, _ -> command(c, VoicePlaybackService.ACTION_TOGGLE) },
                "notification close" to { c, _ -> command(c, VoicePlaybackService.ACTION_CLOSE) },
                "swiped away while paused" to { c, _ ->
                    VoiceSession.pause()
                    c.get().onTaskRemoved(null)
                },
            )
        for ((name, stop) in paths) {
            chat.status = Conversation.STATUS_AVAILABLE
            val v = voice("${name.replace(' ', '-')}.m4a")
            val controller = playInService(v)
            assertEquals(name, PowerManager.PARTIAL_WAKE_LOCK, player.wakeMode)
            assertTrue(name, player.awake)
            stop(controller, v)
            assertFalse(name, player.awake)
            VoiceSession.release()
            controller.destroy()
        }
    }

    @Test
    fun theSessionCallbackSeeksWithinTheClipAndItsCloseActionReleases() {
        val controller = playInService()
        val callback = controller.get().callback
        callback.onSeekTo(5_000)
        assertEquals(5_000, player.position)
        callback.onSeekTo(-5)
        assertEquals(0, player.position)
        callback.onSeekTo(99_999)
        assertEquals(19_000, player.position)
        callback.onCustomAction("other", null)
        assertTrue(VoiceSession.active.value)
        callback.onCustomAction(VoicePlaybackService.ACTION_CLOSE, null)
        assertTrue(released())
        assertTrue(player.released)
        assertTrue(shadowOf(controller.get()).isForegroundStopped)
    }

    /**
     * The framework's `Callback.onMediaButtonEvent` turns the headset's PLAY_PAUSE into onPause / onPlay by the state and
     * ACTION_PLAY_PAUSE checked here; the key's delivery through the system is not (Robolectric's `MediaController` has
     * no session behind it). onPlay goes through a foreground start first.
     */
    @Test
    fun theSessionCallbackPausesAndPlaysThroughAForegroundStart() {
        val controller = playInService()
        val service = controller.get()
        assertTrue(state(controller).actions and PlaybackState.ACTION_PLAY_PAUSE != 0L)
        assertEquals(PlaybackState.STATE_PLAYING, state(controller).state)
        assertEquals(listOf(VoicePlaybackService.ACTION_CLOSE), state(controller).customActions.map { it.action })
        service.callback.onPause()
        assertFalse(player.playing)
        assertTrue(VoiceSession.active.value)
        assertEquals(PlaybackState.STATE_PAUSED, state(controller).state)
        player.position = 4_000
        service.callback.onPlay()
        assertFalse("not before the service is in the foreground", player.playing)
        val start = startRequested()!!
        assertEquals(VoicePlaybackService.ACTION_PLAY, start.action)
        deliver(start, controller)
        assertTrue(player.playing)
        assertEquals(PlaybackState.STATE_PLAYING, state(controller).state)
        assertEquals("the position at the change", 4_000L, state(controller).position)
        service.callback.onPlay()
        assertNull("already playing", startRequested())
        controller.destroy()
    }

    @Test
    fun anOutsidePlayThatIsRefusedOrDeniedFocusStaysPaused() {
        val controller = playInService()
        VoiceSession.pause()
        refuse = true
        controller.get().callback.onPlay()
        assertFalse(player.playing)
        assertTrue("kept", VoiceSession.active.value)
        refuse = false
        for (action in listOf(VoicePlaybackService.ACTION_PLAY, VoicePlaybackService.ACTION_TOGGLE)) {
            denyFocus()
            command(controller, action)
            assertFalse(action, player.playing)
            assertTrue(action, VoiceSession.active.value)
            assertFalse(action, player.released)
        }
        // the bar's own tap keeps releasing, as before
        denyFocus()
        VoiceSession.barActions(app).toggle()
        assertTrue(released())
        controller.destroy()
    }

    @Test
    fun aRefusedServicePausesWhenTheLastScreenStops() {
        refuse = true
        val screen = Robolectric.buildActivity(Activity::class.java).setup()
        play(voice())
        assertTrue(player.playing)
        assertFalse(VoiceSession.serviceRunning)
        screen.pause().stop()
        assertFalse("nothing keeps it in the background", player.playing)
        assertTrue(VoiceSession.active.value)
        screen.destroy()
    }

    @Test
    fun onStartCommandTogglesAndCloses() {
        val controller = playInService()
        command(controller, VoicePlaybackService.ACTION_TOGGLE)
        assertFalse(player.playing)
        assertTrue("paused stays in the foreground", !shadowOf(controller.get()).isForegroundStopped)
        command(controller, VoicePlaybackService.ACTION_TOGGLE)
        assertTrue(player.playing)
        command(controller, VoicePlaybackService.ACTION_CLOSE)
        assertTrue(released())
        assertTrue(player.released)
        assertTrue(shadowOf(controller.get()).isForegroundStopped)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun onTaskRemovedReleasesOnlyAPausedMessage() {
        val controller = playInService()
        controller.get().onTaskRemoved(null)
        assertTrue("playing goes on", player.playing)
        assertTrue(VoiceSession.active.value)
        VoiceSession.pause()
        controller.get().onTaskRemoved(null)
        assertTrue(released())
        assertTrue(player.released)
        assertTrue(shadowOf(controller.get()).isForegroundStopped)
    }

    private companion object {
        const val GENERATION = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11"
    }
}
