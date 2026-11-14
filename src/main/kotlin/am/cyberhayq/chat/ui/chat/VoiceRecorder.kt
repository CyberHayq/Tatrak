package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.storage.media.VoiceStream
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [startedAt] is [SystemClock.elapsedRealtime] when recording began; [plainFile]: the recording
 * goes through a plain file, the timer shows the open-lock mark.
 */
@Immutable
data class VoiceRecording(val active: Boolean = false, val startedAt: Long = 0L, val plainFile: Boolean = false)

/** [VoiceStream], the recorder RecordingActivity uses too, driven by the composer's hold-to-record gesture. */
class VoiceRecorder(private val context: Context) {

    private val mutableState = MutableStateFlow(VoiceRecording())
    val state: StateFlow<VoiceRecording> = mutableState.asStateFlow()

    private var session: VoiceStream.Session? = null

    fun start(): Boolean {
        if (session != null) return true
        val next = VoiceStream.start(context) ?: return false
        session = next
        mutableState.value = VoiceRecording(true, SystemClock.elapsedRealtime(), next.plainFile)
        return true
    }

    /**
     * Stops recording. With [keep] the published store file is handed to [onFile] on the main
     * thread, or null when the recording was lost; otherwise it is dropped.
     */
    fun stop(keep: Boolean, onFile: (File?) -> Unit = {}) {
        val current = session ?: return
        session = null
        mutableState.value = VoiceRecording()
        current.stop(keep)
        if (!keep) return
        // a thread, not the screen's scope: a note sent just before the screen closes still arrives
        thread(name = "tatrak-voice-wait") {
            val file = current.await()
            ContextCompat.getMainExecutor(context).execute { onFile(file) }
        }
    }
}
