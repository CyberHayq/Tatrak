package am.cyberhayq.chat.ui.chat

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import android.content.Context
import am.cyberhayq.chat.storage.media.MediaFile
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.Immutable
import eu.siacs.conversations.Config
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class VoicePlayback(
    val uuid: String? = null,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)

/**
 * Plays one audio attachment at a time; the bubble of [VoicePlayback.uuid] shows the progress.
 * [unavailable] is called when a file cannot be played or a read fails while playing; [onCompletion]
 * with the uuid of a message that played to its end. [newPlayer] is replaced by a fake in tests.
 */
class VoicePlayer(
    context: Context,
    private val scope: CoroutineScope,
    private val onCompletion: (String) -> Unit,
    private val unavailable: () -> Unit,
    private val newPlayer: () -> MediaPlayer = ::MediaPlayer,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private var speed = 1f

    private val mutableState = MutableStateFlow(VoicePlayback())
    val state: StateFlow<VoicePlayback> = mutableState.asStateFlow()

    private var player: MediaPlayer? = null

    private var media: MediaFile? = null
    private var ticker: Job? = null

    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    // called on the main looper; a call or another player takes over, we stop
    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            // speech is not ducked by the system (setWillPauseWhenDucked): a duck request pauses too
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause()
            }
        }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(true)
            .build()

    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    /**
     * Plays, pauses or resumes [uuid], or switches to it. False when it could not start: focus denied,
     * or the file could not be opened (then [unavailable] was called too).
     */
    fun toggle(uuid: String, file: File): Boolean {
        val current = player
        if (current != null && mutableState.value.uuid == uuid) {
            if (current.isPlaying) {
                pause()
                return true
            }
            if (!requestFocus()) return false
            if (!begin(current)) return false
            startTicker(current)
            return true
        }
        release()
        if (!requestFocus()) return false
        val next = newPlayer().apply { setAudioAttributes(attributes) }
        var opened: MediaFile? = null
        try {
            opened = EncryptedMediaStore.get().open(file) // through the media store, as every reader of attachments
            next.setDataSource(opened.dataSource())
            next.prepare()
        } catch (e: IOException) {
            Log.w(Config.LOGTAG, "unable to play a voice message: ${e.javaClass.simpleName}") // the message names the stored file
            next.release()
            opened?.close()
            abandonFocus()
            unavailable()
            return false
        } catch (e: IllegalStateException) {
            Log.w(Config.LOGTAG, "unable to play a voice message: ${e.javaClass.simpleName}") // the message names the stored file
            next.release()
            opened?.close()
            abandonFocus()
            unavailable()
            return false
        }
        next.setOnCompletionListener {
            // the platform may report a file damaged in the middle as a normal end
            if (opened.failed) { release(); unavailable(); return@setOnCompletionListener }
            ticker?.cancel()
            abandonFocus()
            mutableState.value = VoicePlayback(uuid, false, 0, it.duration)
            onCompletion(uuid)
        }
        next.setOnErrorListener { _, _, _ -> release(); unavailable(); true } // a read that failed after prepare(): stop, do not go on polling
        media = opened
        player = next
        if (!begin(next)) return false
        mutableState.value = VoicePlayback(uuid, true, 0, next.duration)
        startTicker(next)
        return true
    }

    /** Applied at once while playing; otherwise kept for the next resume or start. */
    fun setSpeed(speed: Float) {
        this.speed = speed
        val current = player ?: return
        // a non-zero speed starts a paused, prepared or completed player: only touch a playing one
        if (!current.isPlaying) return
        // a refused speed keeps playing at the old one
        try {
            current.playbackParams = current.playbackParams.setSpeed(speed)
        } catch (e: IllegalStateException) {
            Log.w(Config.LOGTAG, "voice speed refused: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            Log.w(Config.LOGTAG, "voice speed refused: ${e.javaClass.simpleName}")
        }
    }

    // in place of start(): setPlaybackParams with a non-zero speed starts the player at that speed. False when the
    // player refused (a state or parameters it does not take): then released and [unavailable] called
    private fun begin(current: MediaPlayer): Boolean {
        try {
            current.playbackParams = current.playbackParams.setSpeed(speed)
            return true
        } catch (e: IllegalStateException) {
            Log.w(Config.LOGTAG, "unable to play a voice message: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            Log.w(Config.LOGTAG, "unable to play a voice message: ${e.javaClass.simpleName}")
        }
        release()
        unavailable()
        return false
    }

    fun pause() {
        val current = player ?: return
        if (current.isPlaying) {
            current.pause()
            ticker?.cancel()
            publish(current, playing = false)
            abandonFocus()
        }
    }

    /** Jumps within the loaded clip; ignored for any other bubble. */
    fun seek(uuid: String, fraction: Float) {
        val current = player ?: return
        if (mutableState.value.uuid != uuid) return
        current.seekTo((fraction.coerceIn(0f, 1f) * current.duration).toInt())
        publish(current, playing = current.isPlaying)
    }

    fun release() {
        ticker?.cancel()
        if (player != null) abandonFocus() // also after an error, when the player no longer counts as playing
        player?.release()
        player = null
        media?.close()
        media = null
        mutableState.value = VoicePlayback()
    }

    private fun startTicker(current: MediaPlayer) {
        ticker?.cancel()
        ticker =
            scope.launch {
                while (isActive && player === current) {
                    publish(current, playing = current.isPlaying)
                    delay(100)
                }
            }
    }

    private fun publish(current: MediaPlayer, playing: Boolean) {
        mutableState.value =
            mutableState.value.copy(
                playing = playing,
                positionMs = current.currentPosition,
                durationMs = current.duration,
            )
    }
}
