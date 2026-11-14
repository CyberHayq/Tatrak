package am.cyberhayq.chat.storage.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.FileObserver
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceManager
import eu.siacs.conversations.Config
import eu.siacs.conversations.persistance.FileBackend
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The voice recorder of both recording screens. A recording goes into the media store while it is
 * recorded: AAC in ADTS into a pipe that an encrypting writer reads, so no plain file exists. On a
 * device whose pipe start failed once it goes, as before, as MPEG-4 into a plain file in
 * `cache/Recordings`, encrypted right after the recording and then deleted; the screens warn then.
 */
object VoiceStream {
    private const val PIPE_FAILED = "tatrak_voice_pipe_failed"
    private const val CLOSE_WAIT_SECONDS = 8L // stop() can return before a recorded file is complete
    private const val AWAIT_SECONDS = 20L // that wait plus the encryption of the file

    /** True when this device records through a plain file: its pipe start failed once (kept for good). */
    @JvmStatic fun usesFile(context: Context): Boolean = prefs(context).getBoolean(PIPE_FAILED, false)

    /** Starts a recording; null when the recorder did not start. A failed pipe start also switches [usesFile] on. */
    @JvmStatic fun start(context: Context): Session? = start(context, MediaRecorder::start)

    @VisibleForTesting
    internal fun start(context: Context, begin: (MediaRecorder) -> Unit): Session? =
        if (usesFile(context)) startFile(context, begin) else startPipe(context, begin)

    private fun startPipe(context: Context, begin: (MediaRecorder) -> Unit): Session? {
        // HE-AAC in ADTS writes implicit SBR under an AAC-LC header: every parser then shows half the duration
        val recorder = recorder(context, MediaRecorder.OutputFormat.AAC_ADTS, MediaRecorder.AudioEncoder.AAC) ?: return null
        val writer: MediaWriter
        try {
            val store = EncryptedMediaStore.get(context)
            writer = store.createAttachment(store.attachmentFile("voice.aac"))
        } catch (e: Exception) {
            Log.w(Config.LOGTAG, "voice: no encrypted file can be written: ${e.javaClass.simpleName}")
            recorder.release()
            return null
        }
        val pipe =
            try {
                ParcelFileDescriptor.createPipe()
            } catch (e: Exception) {
                Log.e(Config.LOGTAG, "voice: no pipe", e)
                recorder.release()
                writer.abort()
                return null
            }
        try {
            recorder.setOutputFile(pipe[1].fileDescriptor)
            recorder.prepare()
        } catch (e: Exception) {
            Log.e(Config.LOGTAG, "prepare() failed ", e)
            recorder.release()
            pipe.forEach { runCatching { it.close() } }
            writer.abort()
            return null
        }
        try {
            begin(recorder)
        } catch (e: Exception) {
            // MPEG-4 fails here because it needs a seekable file; some devices may do so for ADTS
            Log.e(Config.LOGTAG, "voice: recording into a pipe did not start, this device records into a file from now on", e)
            recorder.release()
            pipe.forEach { runCatching { it.close() } }
            writer.abort()
            prefs(context).edit().putBoolean(PIPE_FAILED, true).apply()
            return null
        }
        pipe[1].close() // our copy: the stream ends only when every copy of the write end is closed
        val session = Session(recorder, plainFile = false)
        val input = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
        session.finishWith { pump(input, writer, session::keep) }
        return session
    }

    private fun startFile(context: Context, begin: (MediaRecorder) -> Unit): Session? {
        val store = EncryptedMediaStore.get(context) // not the screen's context in the background
        val recorder = recorder(context, MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.HE_AAC) ?: return null
        val plain = FileBackend.Cache(context).recording(MediaRecorder.OutputFormat.MPEG_4)
        val closed = CountDownLatch(1)
        @Suppress("DEPRECATION")
        val observer =
            object : FileObserver(checkNotNull(plain.parentFile).absolutePath) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == plain.name && event == CLOSE_WRITE) closed.countDown()
                }
            }
        observer.startWatching()
        try {
            recorder.setOutputFile(plain.absolutePath)
            recorder.prepare()
            begin(recorder)
        } catch (e: Exception) {
            Log.e(Config.LOGTAG, "prepare() failed ", e)
            recorder.release()
            observer.stopWatching()
            plain.delete()
            return null
        }
        val session = Session(recorder, plainFile = true)
        session.finishWith {
            try {
                val keep = session.keep()
                if (keep && !closed.await(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    Log.d(Config.LOGTAG, "time out waiting for output file to be written")
                }
                publish(store, plain, keep && session.keep())
            } finally {
                observer.stopWatching()
                plain.delete() // also when publish was never reached
            }
        }
        return session
    }

    /** The setup both screens had: microphone, privacy flag, 24 kHz, 28 kbit/s. */
    private fun recorder(context: Context, format: Int, encoder: Int): MediaRecorder? {
        val recorder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
            else @Suppress("DEPRECATION") MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) recorder.setPrivacySensitive(true)
            recorder.setOutputFormat(format)
            // Changing these three settings for AAC sensitive devices for Android<=13 might
            // lead to sporadically truncated (cut-off) voice messages.
            recorder.setAudioEncoder(encoder)
            recorder.setAudioSamplingRate(24_000)
            recorder.setAudioEncodingBitRate(28_000)
        } catch (e: RuntimeException) {
            Log.e(Config.LOGTAG, "could not set audio source", e)
            recorder.release()
            return null
        }
        return recorder
    }

    /**
     * Copies [input] into [writer] to its end, then publishes the file when [keep] (which may wait
     * for the decision) says so, else drops it; null when nothing was published. A failing writer
     * drops the file but the stream is still read to its end, so the recorder never blocks on a full pipe.
     */
    @VisibleForTesting
    internal fun pump(input: InputStream, writer: MediaWriter, keep: () -> Boolean): File? {
        var failed = false
        try {
            input.use {
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (failed) continue
                    try {
                        writer.outputStream.write(buffer, 0, count)
                    } catch (e: Exception) {
                        Log.w(Config.LOGTAG, "voice: could not encrypt the recording: ${e.javaClass.simpleName}")
                        failed = true
                        writer.abort()
                    }
                }
            }
            return if (!failed && keep()) writer.commit() else null
        } catch (e: Exception) {
            Log.w(Config.LOGTAG, "voice: the recording was not published: ${e.javaClass.simpleName}")
            return null
        } finally {
            writer.abort() // nothing after a commit
        }
    }

    /** Encrypts the recorded [plain] file into the store when [keep]; the plain file is deleted in every case. */
    @VisibleForTesting
    internal fun publish(store: EncryptedMediaStore, plain: File, keep: Boolean): File? =
        try {
            if (keep) TemporaryMedia.copyIntoStore(store, plain) else null
        } catch (e: Exception) {
            Log.w(Config.LOGTAG, "voice: could not encrypt the recording: ${e.javaClass.simpleName}")
            null
        } finally {
            plain.delete()
        }

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * One recording. [stop] is called on the main thread (it only stops the recorder); the stream is
     * finished on a background thread, whose result [await] waits for.
     */
    class Session internal constructor(private val recorder: MediaRecorder, val plainFile: Boolean) {
        private val decision = CompletableFuture<Boolean>()
        private val result = CompletableFuture<File?>()
        @Volatile private var abandoned = false

        /** Waits for [stop]; false when the recording is dropped, also when [await] gave up on it. */
        internal fun keep(): Boolean = decision.get() && !abandoned

        internal fun finishWith(work: () -> File?) {
            thread(name = "tatrak-voice") {
                result.complete(
                    try {
                        work()
                    } catch (e: Exception) {
                        Log.w(Config.LOGTAG, "voice: the recording was not finished: ${e.javaClass.simpleName}")
                        null
                    }
                )
            }
        }

        fun pause() = recorder.pause()

        fun resume() = recorder.resume()

        /** Stops and releases the recorder; the recording is then published when [keep], else dropped. */
        fun stop(keep: Boolean) {
            if (decision.isDone) return
            val stopped =
                try {
                    recorder.stop()
                    true
                } catch (e: RuntimeException) {
                    Log.d(Config.LOGTAG, "could not save recording", e)
                    false
                } finally {
                    recorder.release()
                }
            decision.complete(keep && stopped)
        }

        /** After [stop], never on the main thread: the published store file, or null when there is none. */
        fun await(): File? =
            try {
                result.get(AWAIT_SECONDS, TimeUnit.SECONDS)
            } catch (e: Exception) {
                abandoned = true // a commit not reached yet is dropped; one that is under way leaves a file without a message
                Log.d(Config.LOGTAG, "time out waiting for the recording to be written")
                null
            }
    }
}
