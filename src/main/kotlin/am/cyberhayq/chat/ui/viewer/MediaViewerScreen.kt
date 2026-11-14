package am.cyberhayq.chat.ui.viewer

import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.view.Surface
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import am.cyberhayq.chat.storage.media.MediaFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import eu.siacs.conversations.R
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Immutable data class ViewerItem(
    /** Message (attachment) uuid. */
    val key: String,
    val video: Boolean = false,
)

/** Who sent the item and when, for the top bar. */
@Immutable data class ViewerInfo(val title: String, val subtitle: String)

@Immutable
data class ViewerState(
    /** Photos and videos of the chat, oldest first. */
    val items: List<ViewerItem> = emptyList(),
    /** The page to open on. */
    val start: Int = 0,
)

/** A decoded picture: a still bitmap, or an animated GIF / WebP drawable (API 28+). */
sealed interface ViewerImage {
    class Still(val bitmap: Bitmap) : ViewerImage

    class Animated(val drawable: Drawable) : ViewerImage
}

interface ViewerActions {
    fun close()

    fun share(key: String)

    fun save(key: String)

    fun openWith(key: String)

    /** The file lives in the app's private storage, so "save" can copy it out. */
    fun canSave(key: String): Boolean

    suspend fun info(key: String): ViewerInfo?

    /** The picture fitted into about [maxPx]; for a video its first frame. Called off the main thread. */
    suspend fun image(key: String, maxPx: Int): ViewerImage?

    /** A small picture already in memory (the chat's thumbnail) to show while [image] decodes. */
    fun preview(key: String): Bitmap? = null

    /** The video's file in the app's storage; played through the media store. */
    fun videoFile(key: String): File?
}

/**
 * Tatrak: the full-screen photo and video viewer. Swipe sideways through the chat's media, pinch or
 * double-tap to zoom, swipe down to close, tap to show or hide the bars.
 */
@Composable
fun MediaViewerScreen(state: ViewerState, actions: ViewerActions, modifier: Modifier = Modifier) {
    if (state.items.isEmpty()) {
        Box(modifier.fillMaxSize().background(Color.Black))
        return
    }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    // the list grows from the tapped item to the whole chat once loaded: start over on that page
    val pager =
        key(state.items.size, state.start) {
            rememberPagerState(initialPage = state.start.coerceIn(0, state.items.lastIndex)) { state.items.size }
        }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val dismiss =
            remember(height) {
                Dismiss(
                    drag = { dy -> dragY += dy },
                    release = {
                        if (abs(dragY) > height * DISMISS_FRACTION) {
                            actions.close()
                        } else {
                            scope.launch { animate(dragY, 0f) { v, _ -> dragY = v } }
                        }
                    },
                )
            }
        val fade = (1f - abs(dragY) / (height * 0.6f)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade)))
        HorizontalPager(
            state = pager,
            key = { state.items[it].key },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = dragY },
        ) { page ->
            val item = state.items[page]
            val current = pager.settledPage == page
            if (item.video) {
                VideoPage(item, current, chrome, actions, { chrome = !chrome }, dismiss)
            } else {
                ImagePage(item, current, actions, { chrome = !chrome }, dismiss)
            }
        }
        val currentKey = state.items[pager.currentPage.coerceIn(0, state.items.lastIndex)].key
        AnimatedVisibility(chrome && dragY == 0f, enter = fadeIn(), exit = fadeOut()) {
            Chrome(currentKey, pager.currentPage + 1, state.items.size, actions)
        }
    }
}

private class Dismiss(val drag: (Float) -> Unit, val release: () -> Unit)

@Composable
private fun Chrome(key: String, position: Int, count: Int, actions: ViewerActions) {
    val info by produceState<ViewerInfo?>(null, key) { value = actions.info(key) }
    val canSave = remember(key) { actions.canSave(key) }
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = actions::close) {
                Icon(painterResource(R.drawable.ic_arrow_back_24dp), stringResource(R.string.tatrak_chat_back), tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    info?.title ?: stringResource(R.string.tatrak_viewer_counter, position, count),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = info?.subtitle
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.tatrak_ic_more_vert_24dp), stringResource(R.string.tatrak_more), tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_with)) },
                        onClick = {
                            menu = false
                            actions.openWith(key)
                        },
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = { actions.share(key) }) {
                Icon(painterResource(R.drawable.ic_share_24dp), stringResource(R.string.share), tint = Color.White)
            }
            if (count > 1) {
                Text(stringResource(R.string.tatrak_viewer_counter, position, count), color = Color.White, fontSize = 15.sp)
            }
            if (canSave) {
                IconButton(onClick = { actions.save(key) }) {
                    Icon(painterResource(R.drawable.ic_save_24dp), stringResource(R.string.save), tint = Color.White)
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

// --- pages ------------------------------------------------------------------------------------

/** A missing or undecodable file: say so and offer another app instead of spinning forever. */
@Composable
private fun BoxScope.LoadFailed(openWith: () -> Unit) {
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_error_24dp), null, tint = Color.White, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.tatrak_viewer_load_failed), color = Color.White, modifier = Modifier.padding(top = 12.dp))
        TextButton(onClick = openWith) { Text(stringResource(R.string.open_with), color = Color.White) }
    }
}

@Composable
private fun ImagePage(item: ViewerItem, current: Boolean, actions: ViewerActions, onTap: () -> Unit, dismiss: Dismiss) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // the page on screen gets twice the screen (sharp when zoomed); the preloaded neighbours
        // only the screen, a quarter of the pixels; the state keeps the smaller one meanwhile
        val screenPx = maxOf(constraints.maxWidth, constraints.maxHeight)
        val maxPx = (if (current) screenPx * 2 else screenPx).coerceAtMost(MAX_DECODE_PX)
        var failed by remember(item.key) { mutableStateOf(false) }
        val preview = remember(item.key) { actions.preview(item.key) }
        val image by produceState<ViewerImage?>(null, item.key, maxPx) {
            val decoded = actions.image(item.key, maxPx)
            if (decoded != null || value == null) value = decoded
            failed = value == null
        }
        val loaded = image
        Gestures(zoomable = loaded != null, current = current, onTap = onTap, dismiss = dismiss) {
            when (loaded) {
                null ->
                    when {
                        failed -> LoadFailed { actions.openWith(item.key) }
                        preview != null ->
                            Image(
                                remember(preview) { preview.asImageBitmap() },
                                null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        else -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                is ViewerImage.Still ->
                    Image(
                        remember(loaded) { loaded.bitmap.asImageBitmap() },
                        null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                is ViewerImage.Animated ->
                    AndroidView(
                        factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                        update = { view ->
                            view.setImageDrawable(loaded.drawable)
                            // preloaded neighbours stay still
                            (loaded.drawable as? Animatable)?.let { if (current) it.start() else it.stop() }
                        },
                        onRelease = { view -> ((view.drawable) as? Animatable)?.stop() },
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        }
    }
}

@Composable
private fun VideoPage(
    item: ViewerItem,
    current: Boolean,
    chrome: Boolean,
    actions: ViewerActions,
    onTap: () -> Unit,
    dismiss: Dismiss,
) {
    var started by remember { mutableStateOf(false) }
    // leaving the page stops playback
    LaunchedEffect(current) { if (!current) started = false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxPx = maxOf(constraints.maxWidth, constraints.maxHeight).coerceAtMost(MAX_DECODE_PX)
        Gestures(zoomable = false, current = current, onTap = onTap, dismiss = dismiss) {
            if (started) {
                VideoPlayer(item, chrome, actions)
            } else {
                val poster by produceState<ViewerImage?>(null, item.key) { value = actions.image(item.key, maxPx) }
                (poster as? ViewerImage.Still)?.let {
                    Image(
                        remember(it) { it.bitmap.asImageBitmap() },
                        null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                PlayButton(Modifier.align(Alignment.Center)) { started = true }
            }
        }
    }
}

@Composable
private fun PlayButton(modifier: Modifier, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Icon(
            painterResource(R.drawable.ic_play_arrow_24dp),
            stringResource(R.string.tatrak_viewer_play),
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

/** A failed read while playing (a damaged part of the file) replaces the player with [LoadFailed]. */
@Composable
private fun VideoPlayer(item: ViewerItem, chrome: Boolean, actions: ViewerActions) {
    var failed by remember(item.key) { mutableStateOf(false) }
    if (failed) {
        Box(Modifier.fillMaxSize()) { LoadFailed { actions.openWith(item.key) } }
    } else {
        VideoSurface(item, chrome, actions) { failed = true }
    }
}

/**
 * One player, its store reader and audio focus (as `VideoView` had); [release] runs when the page
 * leaves or the read fails.
 */
private class VideoSession(context: Context) {
    val player = MediaPlayer()
    var media: MediaFile? = null
    var released = false
    private val audio = context.getSystemService(AudioManager::class.java)
    // called on the main looper: a call or another player takes over, the video pauses
    private val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener { change ->
                if ((change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) && !released) player.pause()
            }
            .build()

    /** Keeps [opened] for the player, or closes it when the page has already left. */
    @Synchronized fun take(opened: MediaFile): Boolean {
        if (released) opened.close() else media = opened
        return !released
    }

    fun start() {
        audio.requestAudioFocus(focus) // like VideoView, plays even when focus is refused
        player.start()
    }

    @Synchronized fun release() {
        released = true
        player.release()
        audio.abandonAudioFocusRequest(focus)
        media?.close()
    }
}

@Composable
private fun VideoSurface(item: ViewerItem, chrome: Boolean, actions: ViewerActions, onFailed: () -> Unit) {
    val context = LocalContext.current
    val session = remember(item.key) { VideoSession(context) }
    var prepared by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(session) { onDispose { session.release() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && prepared && !session.released) session.player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(session) {
        val file = actions.videoFile(item.key)
        // the session takes the reader on the I/O thread, so one opened while the page leaves is closed there
        val taken =
            withContext(Dispatchers.IO) {
                try { file?.let { session.take(EncryptedMediaStore.get().open(it)) } } catch (e: IOException) { null }
            }
        if (taken == false || session.released) return@LaunchedEffect
        val opened = session.media
        if (opened == null) { onFailed(); return@LaunchedEffect }
        session.player.apply {
            setDataSource(opened.dataSource())
            setOnPreparedListener {
                duration = it.duration
                prepared = true
                // not when the app went to the background while the video was opening
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.start()
            }
            setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h }
            // the platform may report a file damaged in the middle as a normal end
            setOnCompletionListener { if (opened.failed) onFailed() else playing = false }
            setOnErrorListener { _, _, _ -> onFailed(); true }
            prepareAsync()
        }
    }
    LaunchedEffect(prepared) {
        if (!prepared) return@LaunchedEffect
        val player = session.player
        while (!session.released) {
            if (seeking == null) position = player.currentPosition
            playing = player.isPlaying
            delay(200)
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener =
                        object : TextureView.SurfaceTextureListener {
                            var surface: Surface? = null

                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                surface = Surface(texture).also { if (!session.released) session.player.setSurface(it) }
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                if (!session.released) session.player.setSurface(null)
                                surface?.release()
                                surface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                        }
                }
            },
            // as VideoView did, the screen stays on while the video plays
            update = { it.keepScreenOn = playing },
            // the video's aspect ratio, as large as the page allows
            modifier = Modifier.fillMaxSize().wrapContentSize().let { if (aspect > 0f) it.aspectRatio(aspect) else it },
        )
        AnimatedVisibility(
            chrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 60.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                IconButton(onClick = {
                    if (!prepared || session.released) return@IconButton
                    val player = session.player
                    if (player.isPlaying) player.pause() else session.start()
                    playing = player.isPlaying
                }) {
                    Icon(
                        painterResource(if (playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp),
                        stringResource(if (playing) R.string.tatrak_viewer_pause else R.string.tatrak_viewer_play),
                        tint = Color.White,
                    )
                }
                Text(time(position), color = Color.White, fontSize = 13.sp)
                Slider(
                    value = seeking ?: if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        val target = seeking
                        if (target != null && duration > 0 && prepared && !session.released) {
                            position = (target * duration).toInt()
                            session.player.seekTo(position)
                        }
                        seeking = null
                    },
                    colors =
                        SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(time(duration), color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

private fun time(ms: Int): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// --- gestures ---------------------------------------------------------------------------------

/**
 * Pinch and double-tap zoom with panning, swipe down to close, tap. A one-finger sideways drag on
 * an unzoomed page, or one that pushes a zoomed photo past its side edge, is left alone, so the
 * pager turns the page.
 */
@Composable
private fun Gestures(
    zoomable: Boolean,
    current: Boolean,
    onTap: () -> Unit,
    dismiss: Dismiss,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val slop = LocalViewConfiguration.current.touchSlop
    LaunchedEffect(current) {
        if (!current) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(zoomable) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap =
                        if (!zoomable) null
                        else { p ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val fromScale = scale
                            val fromOffset = offset
                            val toScale = if (scale > 1.05f) 1f else DOUBLE_TAP_SCALE
                            // keep the tapped point under the finger
                            val q = (p - center - fromOffset) / fromScale
                            val toOffset = clamp(p - center - q * toScale, toScale)
                            scope.launch {
                                animate(0f, 1f) { f, _ ->
                                    scale = lerp(fromScale, toScale, f)
                                    offset = lerp(fromOffset, toOffset, f)
                                }
                            }
                        },
                )
            }
            .pointerInput(zoomable) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var mode = Mode.UNDECIDED
                    var travel = Offset.Zero
                    var handoff = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (mode == Mode.UNDECIDED && event.changes.any { it.isConsumed }) mode = Mode.PASS
                        if (mode == Mode.PASS) continue
                        if (zoomable && (pressed >= 2 || scale > 1.01f) && mode != Mode.DISMISS) mode = Mode.TRANSFORM
                        when (mode) {
                            Mode.TRANSFORM -> {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                // one finger pushing a zoomed photo past its side edge turns the page: leave the
                                // events unconsumed for the pager until the finger lifts
                                if (pressed > 1) handoff = false
                                else if (!handoff) {
                                    val maxX = size.width * (scale - 1f) / 2f
                                    val atEdge = (pan.x > 0 && offset.x >= maxX - 0.5f) || (pan.x < 0 && offset.x <= -maxX + 0.5f)
                                    handoff = atEdge && abs(pan.x) > abs(pan.y)
                                }
                                if (handoff) continue
                                if (zoom != 1f || pan != Offset.Zero) {
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                    val q = (centroid - center - offset) / scale
                                    offset = clamp(centroid - center - q * newScale + pan, newScale)
                                    scale = newScale
                                    event.changes.forEach { it.consume() }
                                }
                            }
                            Mode.DISMISS -> {
                                event.changes.firstOrNull()?.let {
                                    dismiss.drag(it.positionChange().y)
                                    it.consume()
                                }
                            }
                            else -> {
                                travel += event.changes.first().positionChange()
                                if (travel.getDistance() > slop) {
                                    mode = if (abs(travel.y) > abs(travel.x)) Mode.DISMISS else Mode.PASS
                                }
                            }
                        }
                    }
                    if (mode == Mode.DISMISS) dismiss.release()
                    if (scale < 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            }
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
            content = content,
        )
    }
}

private enum class Mode {
    UNDECIDED,
    TRANSFORM,
    DISMISS,
    PASS,
}

private const val DISMISS_FRACTION = 0.18f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val MAX_SCALE = 5f
private const val MAX_DECODE_PX = 4096
