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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app-wide voice player (issue #7): playback outlives the chat that started it and shows in the mini bar
 * ([VoiceBar]). Initialised in `Conversations.onCreate`, so it counts every started activity; pauses when none is
 * started. Holds only ids, the message's time, its file and the service instance: messages and conversations are
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
    private var timeSent = 0L
    private var file: File? = null
    private var sender = ""
    private var speed = 1f
    private var started = 0
    private var goneCheck: Job? = null
    private var noisyRegistered = false

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

    private val visibility =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // a rotation: the recreated activity starts at once
                if (started == 0 && !activity.isChangingConfigurations) pause()
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
     * callbacks, receiver and player go, the state starts over. [newPlayer] is replaced by a fake in tests.
     */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context, newPlayer: () -> MediaPlayer = ::MediaPlayer) {
        if (this::app.isInitialized) {
            release()
            app.unregisterActivityLifecycleCallbacks(visibility)
        }
        scope.cancel()
        app = context.applicationContext as Application
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        started = 0
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
                newPlayer = newPlayer,
            )
        next.setSpeed(speed)
        player = next
        scope.launch {
            next.state.collect {
                mutablePlayback.value = it
                publishBar()
            }
        }
        app.registerActivityLifecycleCallbacks(visibility)
    }

    /** The bubble's play button: plays, pauses or resumes [messageUuid], or switches to it. */
    fun toggle(service: XmppConnectionService, conversationUuid: String, messageUuid: String, timeSent: Long, file: File) {
        // the old instance's lists are dead
        if (this.service != null && this.service !== service) release()
        val current = player ?: return
        val switching = !mutableActive.value || this.messageUuid != messageUuid
        if (!current.toggle(messageUuid, file)) {
            release()
            return
        }
        if (!switching) return
        this.service = service
        this.conversationUuid = conversationUuid
        this.messageUuid = messageUuid
        this.timeSent = timeSent
        this.file = file
        val message = service.findConversationByUuid(conversationUuid)?.findMessageWithUuid(messageUuid)
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
    }

    /** Jumps within the loaded clip; ignored for any other message. */
    fun seek(messageUuid: String, fraction: Float) {
        player?.seek(messageUuid, fraction)
    }

    /** Recording, unplugged headphones, no screen of the app visible. */
    fun pause() {
        player?.pause()
    }

    fun close() = release()

    fun cycleSpeed() {
        speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]
        PreferenceManager.getDefaultSharedPreferences(app).edit().putFloat(SPEED_KEY, speed).apply()
        player?.setSpeed(speed)
        publishBar()
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
        timeSent = 0L
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
                val uuid = messageUuid ?: return
                val clip = file ?: return
                if (player?.toggle(uuid, clip) != true) release()
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
        if ((conversation.lastClearHistory?.timestamp() ?: 0L) > timeSent) return true
        val message = conversation.findMessageWithUuid(messageUuid)
        if (message != null && (!message.isFileOrImage || message.isDeleted)) return true
        // a message not found may only be trimmed from memory; a removed one took its file along
        return file?.exists() != true
    }

    private fun publishBar() {
        val chat = conversationUuid
        val message = messageUuid
        mutableBar.value =
            if (!mutableActive.value || chat == null || message == null) {
                null
            } else {
                val playback = mutablePlayback.value.takeIf { it.uuid == message }
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
