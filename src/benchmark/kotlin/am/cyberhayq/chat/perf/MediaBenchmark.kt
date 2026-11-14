package am.cyberhayq.chat.perf

import am.cyberhayq.chat.storage.DatabaseStorage
import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import am.cyberhayq.chat.ui.chat.ChatHeader
import am.cyberhayq.chat.ui.chat.ChatItem
import am.cyberhayq.chat.ui.chat.ChatScreen
import am.cyberhayq.chat.ui.chat.ChatState
import am.cyberhayq.chat.ui.chat.ComposerInfo
import am.cyberhayq.chat.ui.chat.GroupPosition
import am.cyberhayq.chat.ui.chat.MediaInfo
import am.cyberhayq.chat.ui.chat.MessageItem
import am.cyberhayq.chat.ui.chat.MessageKind
import am.cyberhayq.chat.ui.chat.VoicePlayback
import am.cyberhayq.chat.ui.chat.VoiceRecording
import am.cyberhayq.chat.ui.media.mediaDecodeDispatcher
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.viewer.ViewerImage
import am.cyberhayq.chat.ui.viewer.ViewerImages
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.xmpp.Jid
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Benchmark build only: the speed budgets of attachment encryption (design "Speed budgets"),
 * the same generated media read plain and through the media store. Results go to logcat under
 * [TAG] and on screen. Runbook: knowledge/build/media-benchmark.md.
 *
 * `-e step prepare` writes the media and stops (reboot or drop caches next, for a cold read);
 * `-e step run` (default) measures, preparing first when the media are missing;
 * `-e step clean` deletes the media.
 */
class MediaBenchmark : ComponentActivity() {

    private val lines = mutableStateListOf<String>()
    private val chat = mutableStateOf<List<ChatItem>?>(null)
    private val surface = CompletableDeferred<Surface>()
    private val store by lazy { EncryptedMediaStore.get() }
    private val plainDir by lazy { File(filesDir, "media_benchmark") }
    private lateinit var service: XmppConnectionService
    private val messages = HashMap<String, Message>()

    // janky frames while a scroll run counts them; FrameMetrics arrive on their own thread
    @Volatile private var counting = false
    private val frames = AtomicInteger()
    private val janky = AtomicInteger()
    private var frameBudgetNs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        frameBudgetNs = (1e9 / windowManager.defaultDisplay.refreshRate).toLong()
        window.addOnFrameMetricsAvailableListener(
            Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                if (counting) {
                    frames.incrementAndGet()
                    if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > frameBudgetNs) janky.incrementAndGet()
                }
            },
            Handler(HandlerThread("bench-frames").apply { start() }.looper),
        )
        setContent {
            TatrakTheme {
                val items = chat.value
                if (items != null) {
                    ChatScreen(
                        state = ChatState(loaded = true, conversationUuid = "bench", header = ChatHeader("Media benchmark", null), items = items, composer = ComposerInfo(hint = "Message")),
                        input = TextFieldValue(),
                        onInputChange = {},
                        reply = null,
                        actions = actions,
                        voice = MutableStateFlow(VoicePlayback()),
                        recording = MutableStateFlow(VoiceRecording()),
                    )
                } else {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
                        AndroidView({ context ->
                            TextureView(context).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { surface.complete(Surface(texture)) }
                                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = false // kept for the next player
                                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                                }
                            }
                        }, Modifier.size(320.dp, 180.dp))
                        Text(lines.joinToString("\n"), Modifier.verticalScroll(rememberScrollState()), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        lifecycleScope.launch {
            try {
                when (intent.getStringExtra("step") ?: "run") {
                    "prepare" -> { awaitStorage(); prepare(); report("Prepared. Reboot (or drop caches), then start with -e step run.") }
                    "clean" -> { awaitStorage(); clean(); report("Cleaned.") }
                    else -> run()
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed", e)
                report("FAILED: $e")
            }
            report("DONE")
        }
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        lines += line
    }

    private suspend fun awaitStorage() {
        while (!DatabaseStorage.isReady()) {
            check(DatabaseStorage.state() == DatabaseStorage.State.STARTING || DatabaseStorage.state() == DatabaseStorage.State.WORKING) {
                "local storage is ${DatabaseStorage.state()}"
            }
            delay(50)
        }
    }

    // ---- media -------------------------------------------------------------------------------

    private val index by lazy { File(plainDir, "index.txt") }

    /** Plain file name to its store copy, as written by [prepare]. */
    private fun stored(): Map<String, File> =
        if (!index.isFile) emptyMap() else index.readLines().filter { it.isNotBlank() }.associate { it.substringBefore('\t') to File(it.substringAfter('\t')) }

    private suspend fun prepare() = withContext(Dispatchers.IO) {
        clean()
        plainDir.mkdirs()
        val written = StringBuilder()
        fun add(name: String, write: (java.io.OutputStream) -> Unit) {
            val plain = File(plainDir, name)
            if (!plain.exists()) FileOutputStream(plain).use { out -> write(out); out.fd.sync() }
            val writer = store.createAttachment(store.attachmentFile(name))
            try { FileInputStream(plain).use { it.copyTo(writer.outputStream, BUFFER) } } catch (e: Throwable) { writer.abort(); throw e }
            written.append(name).append('\t').append(writer.commit().path).append('\n')
        }
        report("Writing ${BIG_MB} MB of random bytes")
        add(BIG) { out -> val chunk = ByteArray(1 shl 20); repeat(BIG_MB) { Random.nextBytes(chunk); out.write(chunk) } }
        report("Encoding the test video")
        makeVideo(File(plainDir, VIDEO))
        add(VIDEO) {}
        add(PHOTO) { out -> out.write(photo(4032, 3024, 0)) }
        for (i in 0 until CHAT_PHOTOS) add(chatPhoto(i)) { out -> out.write(photo(1920, 1440, i + 1)) }
        index.writeText(written.toString())
    }

    private fun clean() {
        stored().values.forEach { it.delete() }
        plainDir.deleteRecursively()
    }

    /** A JPEG with gradients, shapes and fine grain, so it compresses like a photo. */
    private fun photo(width: Int, height: Int, seed: Int): ByteArray {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun color() = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), color(), color(), Shader.TileMode.CLAMP)
        canvas.drawPaint(paint)
        paint.shader = null
        repeat(40) {
            paint.color = color()
            paint.alpha = 120
            canvas.drawCircle(random.nextFloat() * width, random.nextFloat() * height, random.nextFloat() * height / 4, paint)
        }
        val grain = Bitmap.createBitmap(IntArray(256 * 256) { random.nextInt() or 0xFF000000.toInt() }, 256, 256, Bitmap.Config.ARGB_8888)
        paint.shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        paint.alpha = 40
        canvas.drawPaint(paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray().also { bitmap.recycle() }
    }

    /** 20 s of moving grain, H.264 1280x720 at 30 fps, a key frame each second. */
    private fun makeVideo(out: File) {
        val width = 1280
        val height = 720
        val fps = 30
        val count = 20 * fps
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val grain = Random(7).nextBytes(width * height + width * 64)
        val gray = ByteArray(width * height) { 128.toByte() }
        val info = MediaCodec.BufferInfo()
        var queued = 0
        var track = -1
        try {
            while (true) {
                if (queued <= count) {
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val time = queued * 1_000_000L / fps
                        if (queued == count) {
                            codec.queueInputBuffer(input, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val image = checkNotNull(codec.getInputImage(input))
                            val luma = image.planes[0]
                            val buffer = luma.buffer
                            val shift = queued * 37 % (width * 63) // the grain moves from frame to frame
                            for (row in 0 until height) {
                                buffer.position(row * luma.rowStride)
                                buffer.put(grain, row * width + shift, width)
                            }
                            for (plane in 1..2) image.planes[plane].buffer.let { it.rewind(); it.put(gray, 0, it.remaining()) }
                            codec.queueInputBuffer(input, 0, width * height * 3 / 2, time, 0)
                        }
                        queued++
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (output >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        muxer.writeSampleData(track, checkNotNull(codec.getOutputBuffer(output)), info)
                    }
                    codec.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            muxer.stop()
        } finally {
            codec.release()
            muxer.release()
        }
    }

    // ---- measurements ------------------------------------------------------------------------

    private suspend fun run() {
        awaitStorage()
        var files = stored()
        val fresh = files.isEmpty() || files.values.any { !it.isFile }
        if (fresh) {
            report("Media missing: preparing now, so the first read below is NOT cold")
            prepare()
            files = stored()
        }
        fun plain(name: String) = File(plainDir, name)
        val big = files.getValue(BIG)
        report("Device ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.SUPPORTED_ABIS.first()}")

        // 1. sequential read; the first read of each file after a reboot is the cold one
        val coldLabel = if (fresh) "first (NOT cold)" else "cold"
        val plainCold = read { FileInputStream(plain(BIG)) }
        val storeCold = read { store.inputStream(big) }
        report("read $coldLabel: plain %.0f MB/s, encrypted %.0f MB/s (%.0f%% of plain)".format(plainCold, storeCold, 100 * storeCold / plainCold))
        val warm = (1..3).map { read { FileInputStream(plain(BIG)) } to read { store.inputStream(big) } }
        report("read warm (median of 3): plain %.0f MB/s, encrypted %.0f MB/s".format(median(warm.map { it.first }), median(warm.map { it.second })))

        // 2. photo opened in the viewer (ViewerImages: what MediaViewerActivity calls), screen x 2 as for the page on screen
        val maxPx = (2 * maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)).coerceAtMost(4096)
        compare("viewer photo 4032x3024", 5) { encrypted ->
            val file = if (encrypted) files.getValue(PHOTO) else plain(PHOTO)
            withContext(Dispatchers.IO) {
                var image: ViewerImage? = null
                timed { image = checkNotNull(ViewerImages.decode(file, "image/jpeg", maxPx)) }.also { (image as? ViewerImage.Still)?.bitmap?.recycle() }
            }
        }

        // 3. first video frame and seek: MediaPlayer on the store's MediaDataSource against a plain path
        val target = surface.await()
        play(plain(VIDEO), false, target) // warm-up: the first player pays for codec start-up
        val video = (0 until 10).map { (it % 2 == 1).let { encrypted -> encrypted to play(if (encrypted) files.getValue(VIDEO) else plain(VIDEO), encrypted, target) } }
        val plainVideo = video.filter { !it.first }.map { it.second }
        val storeVideo = video.filter { it.first }.map { it.second }
        report("video first frame (median of 5): plain %.1f ms, encrypted %.1f ms".format(median(plainVideo.map { it.first }), median(storeVideo.map { it.first })))
        report("video seek to the middle (median of 5): plain %.1f ms, encrypted %.1f ms".format(median(plainVideo.map { it.second }), median(storeVideo.map { it.second })))

        // 4. chat thumbnails through FileBackend.getThumbnail; a new message each time, so the bitmap cache never hits
        service = bindService()
        val sizePx = (270 * resources.displayMetrics.density).toInt() // MessageBubble's MediaMaxWidth
        compare("getThumbnail, mean of $CHAT_PHOTOS photos 1920x1440", 3) { encrypted ->
            (0 until CHAT_PHOTOS).sumOf { i ->
                val message = message(if (encrypted) files.getValue(chatPhoto(i)) else plain(chatPhoto(i)))
                withContext(mediaDecodeDispatcher) { timed { service.fileBackend.getThumbnail(message, sizePx, false) } }
            } / CHAT_PHOTOS
        }

        // 5. chat scroll with photos, three runs each, alternating
        val results = HashMap<Boolean, MutableList<Pair<Int, Int>>>()
        for (round in 0 until 6) {
            val encrypted = round % 2 == 1
            val counts = scroll { i -> if (encrypted) files.getValue(chatPhoto(i % CHAT_PHOTOS)) else plain(chatPhoto(i % CHAT_PHOTOS)) }
            results.getOrPut(encrypted) { ArrayList() } += counts
            report("scroll ${if (encrypted) "encrypted" else "plain"} run ${round / 2 + 1}: ${counts.second} janky of ${counts.first} frames (> %.1f ms)".format(frameBudgetNs / 1e6))
        }
        for (encrypted in listOf(false, true)) {
            val runs = results.getValue(encrypted)
            report("scroll ${if (encrypted) "encrypted" else "plain"} total: ${runs.sumOf { it.second }} janky of ${runs.sumOf { it.first }} frames")
        }
    }

    /** MB/s (10^6 bytes) of reading [open] to the end. */
    private suspend fun read(open: () -> InputStream): Double = withContext(Dispatchers.IO) {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        val start = System.nanoTime()
        open().use { while (true) { val n = it.read(buffer); if (n < 0) break; total += n } }
        total / ((System.nanoTime() - start) / 1e9) / 1e6
    }

    /** One warm-up, then [rounds] measurements alternating plain and encrypted; medians in ms. */
    private suspend fun compare(label: String, rounds: Int, measure: suspend (encrypted: Boolean) -> Double) {
        measure(false)
        val times = (0 until 2 * rounds).map { (it % 2 == 1) to measure(it % 2 == 1) }
        val plain = median(times.filter { !it.first }.map { it.second })
        val encrypted = median(times.filter { it.first }.map { it.second })
        report("$label: plain %.1f ms, encrypted %.1f ms (+%.1f ms)".format(plain, encrypted, encrypted - plain))
    }

    private inline fun timed(block: () -> Any?): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1e6
    }

    /** (first frame, seek) in ms; the viewer opens the store file and hands its MediaDataSource to the player. */
    private suspend fun play(file: File, encrypted: Boolean, target: Surface): Pair<Double, Double> {
        val player = MediaPlayer()
        try {
            val rendered = CompletableDeferred<Long>()
            val sought = CompletableDeferred<Long>()
            val start = System.nanoTime()
            if (encrypted) player.setDataSource(withContext(Dispatchers.IO) { store.open(file) }.dataSource()) // closed by release
            else player.setDataSource(file.path)
            player.setSurface(target)
            player.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendered.complete(System.nanoTime())
                false
            }
            player.setOnErrorListener { _, what, extra -> rendered.completeExceptionally(IllegalStateException("player error $what/$extra")); true }
            player.setOnSeekCompleteListener { sought.complete(System.nanoTime()) }
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
            val first = (withTimeout(10_000) { rendered.await() } - start) / 1e6
            val seekStart = System.nanoTime()
            player.seekTo(player.duration / 2) // as the viewer's slider: the previous key frame
            val seek = (withTimeout(10_000) { sought.await() } - seekStart) / 1e6
            return first to seek
        } finally {
            player.release()
        }
    }

    private suspend fun bindService(): XmppConnectionService {
        val bound = CompletableDeferred<XmppConnectionService>()
        bindService(Intent(this, XmppConnectionService::class.java), object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.complete((binder as XmppConnectionService.XmppConnectionBinder).service)
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }, BIND_AUTO_CREATE)
        return withTimeout(10_000) { bound.await() }
    }

    private fun message(file: File): Message =
        Message(BenchConversation, "", Message.ENCRYPTION_NONE).apply {
            setRelativeFilePath(Message.StorageLocation(file, false))
            messages[uuid] = this
        }

    /** (frames, janky frames) of [FLINGS] flings up a chat of photo messages, the bitmap cache cleared first. */
    private suspend fun scroll(file: (Int) -> File): Pair<Int, Int> {
        service.bitmapCache.evictAll()
        messages.clear()
        chat.value = (0 until CHAT_MESSAGES).map { i ->
            MessageItem(
                key = message(file(i)).uuid,
                day = "Today",
                isOutgoing = i % 3 == 0,
                position = GroupPosition.SINGLE,
                kind = MessageKind.IMAGE,
                text = AnnotatedString(""),
                time = "12:%02d".format(i % 60),
                media = MediaInfo(1920, 1440, isVideo = false),
            )
        }
        delay(2_000) // first screen of thumbnails, the same for both
        frames.set(0)
        janky.set(0)
        counting = true
        repeat(FLINGS) { fling(); delay(600) }
        counting = false
        chat.value = null
        delay(500)
        return frames.get() to janky.get()
    }

    /** A finger flick downwards: older messages of the reversed chat come in. */
    private suspend fun fling() {
        val view = window.decorView
        val x = view.width / 2f
        val from = view.height * 0.3f
        val to = view.height * 0.8f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, from)
        for (step in 1..8) { delay(12); send(MotionEvent.ACTION_MOVE, from + (to - from) * step / 8) }
        send(MotionEvent.ACTION_UP, to)
    }

    private val actions = object : PerfDemoActivity.DemoChatActions() {
        // as ChatFragment: the bounded decode pool, FileBackend.getThumbnail, the service's cache
        override suspend fun loadThumbnail(uuid: String, sizePx: Int): Bitmap? {
            val message = messages[uuid] ?: return null
            return withContext(mediaDecodeDispatcher) { runCatching { service.fileBackend.getThumbnail(message, sizePx, false) }.getOrNull() }
        }

        override fun cachedThumbnail(uuid: String): Bitmap? = service.bitmapCache.get(uuid)
    }

    /** The conversation a [Message] needs; getThumbnail reads none of it. */
    private object BenchConversation : Conversational {
        override fun getAccount(): Account? = null
        override fun getContact(): Contact? = null
        override fun getAddress(): Jid? = null
        override fun getMode() = Conversational.MODE_SINGLE
        override fun getUuid() = "media-benchmark"
    }

    private companion object {
        const val TAG = "TatrakMediaBench"
        const val BUFFER = 256 * 1024
        const val BIG = "big.bin"
        const val BIG_MB = 100
        const val VIDEO = "video.mp4"
        const val PHOTO = "photo.jpg"
        const val CHAT_PHOTOS = 24
        const val CHAT_MESSAGES = 150
        const val FLINGS = 15

        fun chatPhoto(i: Int) = "chat_%02d.jpg".format(i)

        fun median(values: List<Double>) = values.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
    }
}
