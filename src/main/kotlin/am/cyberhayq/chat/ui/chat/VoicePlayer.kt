package am.cyberhayq.chat.ui.chat

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import am.cyberhayq.chat.storage.media.EncryptedMediaStore
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
 * [unavailable] is called when a file cannot be played or a read fails while playing.
 */
class VoicePlayer(
    private val scope: CoroutineScope,
    private val audioManager: AudioManager,
    private val unavailable: () -> Unit,
) {

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
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                pause()
            }
        }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()

    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun toggle(uuid: String, file: File) {
        val current = player
        if (current != null && mutableState.value.uuid == uuid) {
            if (current.isPlaying) {
                pause()
            } else if (requestFocus()) {
                current.start()
                startTicker(current)
            }
            return
        }
        release()
        if (!requestFocus()) return
        val next = MediaPlayer().apply { setAudioAttributes(attributes) }
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
            return
        } catch (e: IllegalStateException) {
            Log.w(Config.LOGTAG, "unable to play a voice message: ${e.javaClass.simpleName}") // the message names the stored file
            next.release()
            opened?.close()
            abandonFocus()
            unavailable()
            return
        }
        next.setOnCompletionListener {
            // the platform may report a file damaged in the middle as a normal end
            if (opened.failed) { release(); unavailable(); return@setOnCompletionListener }
            ticker?.cancel()
            abandonFocus()
            mutableState.value = VoicePlayback(uuid, false, 0, it.duration)
        }
        next.setOnErrorListener { _, _, _ -> release(); unavailable(); true } // a read that failed after prepare(): stop, do not go on polling
        media = opened
        player = next
        mutableState.value = VoicePlayback(uuid, true, 0, next.duration)
        next.start()
        startTicker(next)
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
