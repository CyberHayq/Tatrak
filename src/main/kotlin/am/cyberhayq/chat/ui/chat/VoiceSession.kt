package am.cyberhayq.chat.ui.chat

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.MessageSearchResults
import eu.siacs.conversations.utils.UIHelper
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app-wide voice player (issue #7): playback outlives the chat that started it and shows in the mini bar
 * ([VoiceBar]), and in the background through [VoicePlaybackService]. Initialised in `Conversations.onCreate`, so it
 * counts every started activity; the progress ticker stops while none is started. Holds only ids, the chat's clear marker, the message's file and the service instance: messages and conversations are
 * resolved again on each check. Main thread only.
 */
object VoiceSession {
    private const val SPEED_KEY = "tatrak_voice_speed"
    private val SPEEDS = listOf(1f, 1.5f, 2f)

    private lateinit var app: Application
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: VoicePlayer? = null

    private var service: XmppConnectionService? = null
    private var conversationUuid: String? = null
    private var messageUuid: String? = null
    /** The chat's "Clear history" marker when the session started; a change means the chat was cleared. */
    private var clearMarker = 0L
    private var file: File? = null
    private var sender = ""
    private var speed = 1f
    private var started = 0
    private var goneCheck: Job? = null
    private var noisyRegistered = false
    private var startForegroundService: (Context, Intent) -> Unit = ContextCompat::startForegroundService

    /** A start of [VoicePlaybackService] was accepted and it has not stopped: playback may go on with no screen. */
    internal var serviceRunning = false

    private val mutablePlayback = MutableStateFlow(VoicePlayback())
    /** The player's state, for the bubbles. */
    val playback: StateFlow<VoicePlayback> = mutablePlayback.asStateFlow()

    private val mutableActive = MutableStateFlow(false)
    /** Set by a successful start, cleared only by [release]. */
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    private val mutableBar = MutableStateFlow<VoiceBarState?>(null)
    /** The mini bar's state; null while not [active]. */
    val bar: StateFlow<VoiceBarState?> = mutableBar.asStateFlow()

    private val mutableChatShown = MutableStateFlow<String?>(null)
    /** The conversation a started `ChatFragment` shows, else null. */
    val chatShown: StateFlow<String?> = mutableChatShown.asStateFlow()

    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Play, pause, seek, speed or another message: what the notification and the media session show; never a tick. */
    val changes: SharedFlow<Unit> = mutableChanges.asSharedFlow()

    private val visibility =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) player?.setTicking(true)
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // a rotation: the recreated activity starts at once; otherwise playback goes on in the service
                if (started == 0 && !activity.isChangingConfigurations) {
                    player?.setTicking(false)
                    if (!serviceRunning) pause() // Android refused the service: no playing on unseen, as step 1
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {}
        }

    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
            }
        }

    /**
     * Called from `Application.onCreate`; may run again (Robolectric makes an `Application` per test): the previous
     * callbacks, receiver and player go, the state starts over. [startForegroundService] and [newPlayer] are replaced
     * in tests.
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        context: Context,
        startForegroundService: (Context, Intent) -> Unit = ContextCompat::startForegroundService,
        newPlayer: () -> MediaPlayer = ::MediaPlayer,
    ) {
        if (this::app.isInitialized) {
            release()
            app.unregisterActivityLifecycleCallbacks(visibility)
        }
        scope.cancel()
        app = context.applicationContext as Application
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        started = 0
        serviceRunning = false
        this.startForegroundService = startForegroundService
        mutableChatShown.value = null
        speed = PreferenceManager.getDefaultSharedPreferences(app).getFloat(SPEED_KEY, 1f).takeIf { it in SPEEDS } ?: 1f
        val next =
            VoicePlayer(
                app,
                scope,
                onCompletion = { release() }, // no next message in step 1
                unavailable = {
                    release()
                    Toast.makeText(app, R.string.tatrak_viewer_load_failed, Toast.LENGTH_SHORT).show()
                },
                onSeeked = ::changed,
                newPlayer = newPlayer,
            )
        next.setSpeed(speed)
        next.setTicking(false) // no activity started yet
        player = next
        scope.launch {
            next.state.collect {
                val before = mutablePlayback.value
                mutablePlayback.value = it
                publishBar()
                if (it.playing != before.playing || it.uuid != before.uuid) changed()
            }
        }
        app.registerActivityLifecycleCallbacks(visibility)
    }

    /** The bubble's play button: plays, pauses or resumes [messageUuid], or switches to it. */
    fun toggle(service: XmppConnectionService, conversationUuid: String, messageUuid: String, file: File) {
        bound(service)
        val current = player ?: return
        val switching = !mutableActive.value || this.messageUuid != messageUuid
        if (!current.toggle(messageUuid, file)) {
            release()
            return
        }
        if (!switching) {
            startService()
            return
        }
        this.service = service
        this.conversationUuid = conversationUuid
        this.messageUuid = messageUuid
        this.file = file
        val conversation = service.findConversationByUuid(conversationUuid)
        // not compared with the message's time: the service sets the marker a second past the last message
        clearMarker = conversation?.lastClearHistory?.timestamp() ?: 0L
        val message = conversation?.findMessageWithUuid(messageUuid)
        sender =
            when {
                message == null -> ""
                message.status == Message.STATUS_RECEIVED -> UIHelper.getMessageDisplayName(message)
                else -> app.getString(R.string.tatrak_you)
            }
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                app,
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
        goneCheck?.cancel()
        goneCheck =
            scope.launch {
                while (true) {
                    delay(1000)
                    if (gone()) {
                        release()
                        return@launch
                    }
                }
            }
        mutableActive.value = true
        publishBar()
        changed() // another message: the notification's content intent follows it
        startService()
    }

    // after the player started, from the user's tap on a visible screen: Android 12+ forbids the start from the background
    private fun startService() {
        if (playing()) requestService(null)
    }

    /** Starts [VoicePlaybackService] in the foreground with [action]; false when Android refused. */
    internal fun requestService(action: String?): Boolean =
        try {
            startForegroundService(app, Intent(app, VoicePlaybackService::class.java).setAction(action))
            serviceRunning = true
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException among others; playback pauses when no screen is left
            Log.w(Config.LOGTAG, "voice playback service not started: ${e.javaClass.simpleName}")
            false
        }

    /** The player's own state: [playback] is mirrored by a coroutine and lags inside a running event loop. */
    internal fun playing() = player?.state?.value?.playing == true

    /**
     * The fragments' onBackendConnected: a session of another service instance (the service was stopped and made
     * again in the same process, e.g. after Log out) would play against a destroyed one's lists, so it goes.
     */
    fun bound(service: XmppConnectionService) {
        if (this.service != null && this.service !== service) release()
    }

    /** Jumps within the loaded clip; ignored for any other message. [changes] emits when the seek lands. */
    fun seek(messageUuid: String, fraction: Float) {
        player?.seek(messageUuid, fraction)
    }

    /** The media session's seek, clamped to the clip. */
    fun seekTo(ms: Long) {
        player?.seekTo(messageUuid ?: return, ms)
    }

    /** Recording, unplugged headphones, the media session. */
    fun pause() {
        player?.pause()
    }

    /**
     * A play from outside the app (the media session, the notification), with the service in the foreground: focus
     * denied leaves the message paused instead of releasing it; a file that cannot play is still released.
     */
    fun resume() {
        if (playing()) return
        player?.toggle(messageUuid ?: return, file ?: return)
    }

    /** The bar's play/pause of the current message; released when it cannot play. */
    fun togglePlaying() {
        val uuid = messageUuid ?: return
        val clip = file ?: return
        if (player?.toggle(uuid, clip) != true) release()
    }

    fun close() = release()

    fun cycleSpeed() {
        speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]
        PreferenceManager.getDefaultSharedPreferences(app).edit().putFloat(SPEED_KEY, speed).apply()
        player?.setSpeed(speed)
        publishBar()
        changed()
    }

    /** Stops playback and forgets the message: the bar goes, the bubble shows play. */
    @JvmStatic
    fun release() {
        goneCheck?.cancel()
        goneCheck = null
        player?.release()
        if (noisyRegistered) {
            app.unregisterReceiver(noisy)
            noisyRegistered = false
        }
        service = null
        conversationUuid = null
        messageUuid = null
        clearMarker = 0L
        file = null
        sender = ""
        mutableActive.value = false
        publishBar()
    }

    fun setChatShown(uuid: String?) {
        mutableChatShown.value = uuid
    }

    /** Clears only while [uuid] is still the shown chat: a fragment stopping after the next chat started keeps it. */
    fun clearChatShown(uuid: String?) {
        if (mutableChatShown.value == uuid) mutableChatShown.value = null
    }

    /** The bar's actions; [context] starts the chat on a tap. */
    fun barActions(context: Context): VoiceBarActions =
        object : VoiceBarActions {
            override fun toggle() {
                togglePlaying()
                startService() // a tap on a visible screen: brings the service back if Android stopped it
            }

            override fun cycleSpeed() = this@VoiceSession.cycleSpeed()

            override fun close() = release()

            override fun open() {
                val chat = conversationUuid ?: return
                val message = messageUuid ?: return
                context.startActivity(MessageSearchResults.openAtIntent(context, chat, message))
            }
        }

    /** The message, its file, its chat or its account is gone (see the design's "Message gone" check). */
    private fun gone(): Boolean {
        val service = service ?: return true
        val conversation = service.findConversationByUuid(conversationUuid ?: return true) ?: return true
        if (conversation.status == Conversation.STATUS_ARCHIVED) return true
        val account = conversation.account
        if (!account.isEnabled || account !in service.accounts) return true
        if ((conversation.lastClearHistory?.timestamp() ?: 0L) != clearMarker) return true
        val message = conversation.findMessageWithUuid(messageUuid)
        if (message != null && (!message.isFileOrImage || message.isDeleted)) return true
        // a message not found may only be trimmed from memory; a removed one took its file along
        return file?.exists() != true
    }

    private fun changed() {
        publishBar() // the collector that mirrors the player may not have run yet
        if (mutableActive.value) mutableChanges.tryEmit(Unit)
    }

    private fun publishBar() {
        val chat = conversationUuid
        val message = messageUuid
        mutableBar.value =
            if (!mutableActive.value || chat == null || message == null) {
                null
            } else {
                val playback = (player?.state?.value ?: mutablePlayback.value).takeIf { it.uuid == message }
                VoiceBarState(
                    conversationUuid = chat,
                    messageUuid = message,
                    sender = sender,
                    playing = playback?.playing == true,
                    positionMs = playback?.positionMs ?: 0,
                    durationMs = playback?.durationMs ?: 0,
                    speed = speed,
                )
            }
    }
}
