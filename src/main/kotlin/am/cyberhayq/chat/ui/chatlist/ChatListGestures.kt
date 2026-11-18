package am.cyberhayq.chat.ui.chatlist

import am.cyberhayq.chat.ui.theme.LocalAvatarShape
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.contentOn
import am.cyberhayq.chat.ui.theme.rememberPreference
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import eu.siacs.conversations.R
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- pure decisions (unit-tested) ----

private const val OPEN_FRACTION = 1f / 3f
private const val FLING_DP_PER_SECOND = 500f

/** Release of a drawer drag: a fling decides, else a third of the way opens and two thirds keep it open. */
internal fun drawerOpensOnRelease(progress: Float, velocityDpPerSecond: Float, wasOpen: Boolean): Boolean =
    when {
        velocityDpPerSecond >= FLING_DP_PER_SECOND -> true
        velocityDpPerSecond <= -FLING_DP_PER_SECOND -> false
        wasOpen -> progress > 1f - OPEN_FRACTION
        else -> progress > OPEN_FRACTION
    }

/**
 * Share of the hidden archive row in view when the finger lifts: from [ARCHIVE_SHOW_FRACTION] the row stays
 * in view if it has been coming in for [ARCHIVE_MIN_PULL_MS] (Telegram's PullForegroundDrawable SNAP_HEIGHT
 * and minPullingTime), less slides it back (an accidental pull at the top of the list).
 */
private const val ARCHIVE_SHOW_FRACTION = 0.85f
internal const val ARCHIVE_MIN_PULL_MS = 200L

internal fun archiveShowsOnRelease(shown: Int, rowHeight: Int): Boolean =
    rowHeight > 0 && shown >= rowHeight * ARCHIVE_SHOW_FRACTION

/** Share of the finger's movement the row follows while pulled in: Telegram's pull parallax, 0.45 down to 0.2. */
internal fun archivePullShare(shown: Int, rowHeight: Int): Float =
    0.45f - 0.25f * (if (rowHeight > 0) (shown.toFloat() / rowHeight).coerceIn(0f, 1f) else 0f)

// ---- hidden / pinned archive row ----

const val ARCHIVE_HIDDEN_KEY = "tatrak_archive_hidden"
/** Set once the "pull down to see the archive" hint has been shown with the first archiving. */
const val ARCHIVE_HINT_SHOWN_KEY = "tatrak_archive_hint_shown"

/** Telegram's "hide above list" (the default) or "pin in chat list", updated live. */
@Composable
fun rememberArchiveHidden(): Boolean =
    rememberPreference(ARCHIVE_HIDDEN_KEY) { it.getBoolean(ARCHIVE_HIDDEN_KEY, true) }

fun setArchiveHidden(context: Context, hidden: Boolean) =
    PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(ARCHIVE_HIDDEN_KEY, hidden).apply()

/**
 * Telegram's hidden archive. Pulling the list down brings the row into view under a foreground ("Swipe
 * down to see archive", "Release to see archive"). A release with the row pulled in far enough and long
 * enough leaves the real row in view, until it scrolls out of view; any other release hides it again. A
 * tap on the row opens the archive.
 */
@Stable
internal class ArchiveReveal(private val list: LazyListState) : NestedScrollConnection {
    var revealed by mutableStateOf(false)

    private val rowHeight get() = if (list.firstVisibleItemIndex == 0) list.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0 else 0

    /** Pixels of the hidden row in view. */
    private val shown get() = if (rowHeight == 0) 0 else rowHeight - list.firstVisibleItemScrollOffset

    /** Share of the hidden row in view, 0..1. */
    val pullProgress get() = if (rowHeight == 0) 0f else shown.toFloat() / rowHeight

    /** Time of the latest touch event, set by [archivePull]: the pull is timed in event time. */
    internal var eventTime = 0L

    /** Event time when the row started coming into view; null while it is out. */
    private var pullStart: Long? = null

    /** A release now would leave the row in view. */
    val reached get() =
        !revealed && archiveShowsOnRelease(shown, rowHeight) && eventTime - (pullStart ?: eventTime) >= ARCHIVE_MIN_PULL_MS

    /** Whether the current drag began with the first chat at the top; null between drags. */
    private var dragFromTop: Boolean? = null

    // a fling stops at the first chat instead of uncovering the row, as in Telegram; so does a drag that
    // began further down and scrolled up to the top, so the pull needs a fresh drag from the top
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && dragFromTop == null) {
            dragFromTop = list.firstVisibleItemIndex == 0 || (list.firstVisibleItemIndex == 1 && list.firstVisibleItemScrollOffset == 0)
        }
        if (revealed || available.y <= 0f) return Offset.Zero
        if (source == NestedScrollSource.UserInput && dragFromTop == true) {
            val atRow = list.firstVisibleItemIndex == 0 || (list.firstVisibleItemIndex == 1 && list.firstVisibleItemScrollOffset == 0)
            // the row resists: the finger travels about 2.5 rows to pull it in far enough to stay
            return if (atRow) Offset(0f, available.y * (1f - archivePullShare(shown, rowHeight))) else Offset.Zero
        }
        return when (list.firstVisibleItemIndex) {
            0 -> available
            1 -> Offset(0f, (available.y - list.firstVisibleItemScrollOffset).coerceAtLeast(0f))
            else -> Offset.Zero
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (shown == 0) pullStart = null else if (pullStart == null) pullStart = eventTime
        return Offset.Zero
    }

    // called when the finger lifts, also without velocity
    override suspend fun onPreFling(available: Velocity): Velocity {
        dragFromTop = null
        if (revealed || rowHeight == 0) return Velocity.Zero
        if (reached) {
            revealed = true
            list.animateScrollToItem(0)
        } else if (list.canScrollForward) {
            list.animateScrollToItem(1)
        }
        return available
    }
}

/** Hooks [reveal] to the list: its scrolling, and the touch times the pull is measured by. */
internal fun Modifier.archivePull(reveal: ArchiveReveal): Modifier =
    nestedScroll(reveal).pointerInput(reveal) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()?.let { reveal.eventTime = it.uptimeMillis }
        }
    }

private val PullMargin = 8.dp
private val PullRadius = 9.dp
/** Small circle plus its margins: below this much row in view the foreground fades in. */
private val PullStart = 34.dp
private val PullAccelerate = Easing { it * it }
private val PullEaseOutQuint = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private val White = ColorFilter.tint(Color.White)

/**
 * Over the hidden row while it is pulled into view, after Telegram's PullForegroundDrawable (21f7d10): a pill
 * growing with the pull up to a small circle with an arrow under the avatar, and the hint text; past the
 * threshold the arrow turns and the accent spreads from the circle. A release that leaves the row in view
 * shrinks the foreground onto the avatar ("out" with a bounce), then the real row shows.
 */
@Composable
internal fun ArchivePullForeground(reveal: ArchiveReveal, modifier: Modifier = Modifier) {
    val revealed = reveal.revealed
    // a fresh composition of an already revealed row shows the real row at once
    var gone by remember { mutableStateOf(revealed) }
    val out = remember { Animatable(0f) }
    val bounce = remember { Animatable(0f) }
    LaunchedEffect(revealed) {
        if (!revealed) {
            gone = false
            out.snapTo(0f)
            bounce.snapTo(0f)
            return@LaunchedEffect
        }
        if (gone) return@LaunchedEffect
        bounce.snapTo(-0.05f)
        coroutineScope {
            launch {
                delay(180)
                bounce.animateTo(0.02f, tween(150, easing = EaseInOut))
                bounce.animateTo(0f, tween(135, easing = EaseInOut))
            }
            out.animateTo(1f, tween(250, easing = PullEaseOutQuint))
        }
        gone = true
    }
    if (gone) return
    val reached by remember(reveal) { derivedStateOf { reveal.reached } }
    val haptic = LocalHapticFeedback.current
    // text and arrow follow the pulled share alone, the colour also waits for the minimum pull time (Telegram)
    val endText by remember(reveal) { derivedStateOf { reveal.pullProgress > ARCHIVE_SHOW_FRACTION } }
    val swap = animateFloatAsState(if (endText) 0f else 1f, tween(170, easing = LinearEasing), label = "archive text")
    val arrow = animateFloatAsState(if (endText) 0f else 1f, tween(250, easing = EaseInOut), label = "archive arrow")
    // also on a successful release: eventTime is not observed, so a pull held still past 200 ms never sets reached
    val colorize = reached || revealed
    LaunchedEffect(colorize) { if (colorize) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val accentIn = remember { Animatable(0f) }
    val accentOut = remember { Animatable(1f) }
    val lastColorize = remember { booleanArrayOf(colorize) }
    LaunchedEffect(colorize) {
        if (lastColorize[0] == colorize) return@LaunchedEffect
        lastColorize[0] = colorize
        // the new colour spreads from the small circle over the old one
        (if (colorize) accentIn else accentOut).run { snapTo(0f); animateTo(1f, tween(230, easing = PullAccelerate)) }
    }
    val grey = TatrakTheme.chatColors.archiveAvatar
    val active = MaterialTheme.colorScheme.primary
    val shape = LocalAvatarShape.current
    val icon = painterResource(R.drawable.ic_archive_24dp)
    val arrowPath = remember { Path() }
    val clipPath = remember { Path() }
    Box(
        modifier.drawWithContent {
            val w = size.width
            val h = size.height
            val margin = PullMargin.toPx()
            val radius = PullRadius.toPx()
            val visible = h * reveal.pullProgress
            val startPull = (visible / PullStart.toPx()).coerceAtMost(1f)
            val o = out.value
            val b = bounce.value
            val avatarRadius = AvatarSize.toPx() / 2
            // the small circle sits under the avatar's centre, which the out phase ends on
            val cx = (RowHorizontalPadding.toPx() + avatarRadius).let { if (layoutDirection == LayoutDirection.Rtl) w - it else it }
            val cy = h / 2
            val sy = h - margin - radius
            val accent = lerp(grey, active, 1f - o)
            val clip = if (o == 0f) null else {
                val r = avatarRadius + avatarRadius * b + (w - avatarRadius) * (1f - o)
                clipPath.apply {
                    reset()
                    addOutline(shape.createOutline(Size(2 * r, 2 * r), layoutDirection, this@drawWithContent))
                    translate(Offset(cx - r, cy - r))
                }
            }
            withTransform({ if (clip != null) clipPath(clip) }) {
                drawRect(grey)
                val revealCentre = Offset(cx, sy + (cy - sy) * o)
                val inRadius = w * accentIn.value
                val outRadius = w * accentOut.value
                if (colorize) {
                    if (outRadius > inRadius) drawCircle(grey, outRadius, revealCentre)
                    if (inRadius > 0f) drawCircle(accent, inRadius, revealCentre)
                } else {
                    if (inRadius > outRadius) drawCircle(accent, inRadius, revealCentre)
                    if (outRadius > 0f) drawCircle(grey, outRadius, revealCentre)
                }
                if (visible > PullStart.toPx()) {
                    drawRoundRect(
                        Color.White.copy(alpha = (1f - (o * 2f).coerceAtMost(1f)) * 0.4f * startPull),
                        Offset(cx - radius, h - visible + margin),
                        Size(2 * radius, visible - 2 * margin),
                        CornerRadius(radius),
                    )
                }
                if (o == 0f) {
                    drawCircle(Color.White.copy(alpha = startPull), radius, Offset(cx, sy))
                    val arrowColor = if (colorize) accent else grey
                    rotate(180f * arrow.value, Offset(cx, sy)) {
                        translate(cx - radius, sy - radius + 1.dp.toPx()) {
                            // Telegram's ArrowDrawable: a triangle and a stem in an 18dp box, filled and stroked
                            arrowPath.reset()
                            arrowPath.moveTo(radius, 4.98.dp.toPx())
                            arrowPath.lineTo(4.95.dp.toPx(), 9.dp.toPx())
                            arrowPath.lineTo(2 * radius - 4.95.dp.toPx(), 9.dp.toPx())
                            arrowPath.close()
                            drawPath(arrowPath, arrowColor)
                            drawPath(arrowPath, arrowColor, style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))
                            drawRect(arrowColor, Offset(7.06.dp.toPx(), 7.5.dp.toPx()), Size(3.88.dp.toPx(), 4.1.dp.toPx()))
                        }
                    }
                }
                this@drawWithContent.drawContent()
            }
            if (o > 0f) {
                val box = ArchiveIconSize.toPx()
                val start = 24.dp.toPx() / box
                translate(0f, (sy - cy) * (1f - o)) {
                    scale(start + (1f - start) * o + b, Offset(cx, cy)) {
                        translate(cx - box / 2, cy - box / 2) { with(icon) { draw(Size(box, box), colorFilter = White) } }
                    }
                }
            }
        }
    ) {
        val pullText by remember { derivedStateOf { swap.value > 0f } }
        val releaseText by remember { derivedStateOf { swap.value < 1f } }
        val style = TextStyle(color = if (colorize) contentOn(active) else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        val place = Modifier.align(Alignment.BottomCenter).padding(start = 64.dp, end = 64.dp, bottom = 11.dp)
        // scale pivots as in Telegram: the outgoing text shrinks towards below, the incoming grows from above
        if (pullText) {
            Text(
                stringResource(R.string.tatrak_archive_pull),
                style = style,
                modifier = place.then(if (endText || revealed) Modifier.clearAndSetSemantics {} else Modifier).graphicsLayer {
                    val s = swap.value
                    alpha = s * (reveal.pullProgress * RowHeight.toPx() / PullStart.toPx()).coerceAtMost(1f)
                    scaleX = 0.8f + 0.2f * s
                    scaleY = scaleX
                    translationY = 8.dp.toPx() * (1f - s)
                    transformOrigin = TransformOrigin(0.5f, 1f + 8.dp.toPx() * (1f - s) / size.height)
                },
            )
        }
        if (releaseText) {
            Text(
                stringResource(R.string.tatrak_archive_release),
                style = style,
                modifier = place.then(if (!endText || revealed) Modifier.clearAndSetSemantics {} else Modifier).graphicsLayer {
                    val s = swap.value
                    alpha = (1f - s) * (reveal.pullProgress * RowHeight.toPx() / PullStart.toPx()).coerceAtMost(1f)
                    scaleX = 0.9f + 0.1f * (1f - s)
                    scaleY = scaleX
                    translationY = 8.dp.toPx() * s
                    transformOrigin = TransformOrigin(0.5f, 1f - 16.dp.toPx() * s / size.height)
                },
            )
        }
    }
}

// ---- drawer by swipe ----

/** The side menu's open fraction (0..1): plain state, so it follows the finger and animates alike. */
@Stable
class DrawerController(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set

    /** True while any part of the menu shows: Back closes it. */
    val isOpen get() = progress > 0f

    private var job: Job? = null

    internal fun dragTo(value: Float) {
        job?.cancel()
        progress = value.coerceIn(0f, 1f)
    }

    /** [velocity]: the finger's speed on release, in menu widths per second, so the menu carries on from it. */
    fun open(velocity: Float = 0f) = animateTo(1f, velocity)

    fun close(velocity: Float = 0f) = animateTo(0f, velocity)

    private fun animateTo(target: Float, velocity: Float) {
        job?.cancel()
        job = scope.launch {
            animate(progress, target, velocity, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { value, _ ->
                progress = value.coerceIn(0f, 1f)
            }
        }
    }
}

@Composable
fun rememberDrawerController(): DrawerController {
    val scope = rememberCoroutineScope()
    // saved, so the menu stays open when the theme toggle recreates the activity
    return rememberSaveable(saver = Saver({ it.isOpen }, { open -> DrawerController(scope).apply { if (open) dragTo(1f) } })) {
        DrawerController(scope)
    }
}

internal fun drawerWidth(screen: Dp): Dp = (screen * 0.8f).coerceAtMost(320.dp)

/**
 * A modal side menu that follows a left-to-right swipe from anywhere, like old Telegram. The drag is
 * claimed in the Initial pass, so the folder pager never gets it.
 * shortcut: no predictive back; add if users ask for it.
 */
@Composable
internal fun DrawerHost(
    controller: DrawerController,
    canOpenByDrag: () -> Boolean,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val density = LocalDensity.current.density
    val widthPx = with(LocalDensity.current) { drawerWidth(LocalConfiguration.current.screenWidthDp.dp).toPx() }
    val visible by remember(controller) { derivedStateOf { controller.isOpen } }
    // survives the recomposition the first drag pixel causes (the menu becomes visible)
    val startedOpen = remember { booleanArrayOf(false) }
    Box(
        Modifier.fillMaxSize()
            .claimHorizontalDrag(
                canClaim = { dx -> controller.isOpen || (dx * sign > 0f && canOpenByDrag()) },
                onStart = { startedOpen[0] = controller.progress > 0.5f },
                onDrag = { dx -> controller.dragTo(controller.progress + dx * sign / widthPx) },
                onEnd = { velocity, _ ->
                    val perWidth = velocity * sign * density / widthPx
                    if (drawerOpensOnRelease(controller.progress, velocity * sign, startedOpen[0])) controller.open(perWidth) else controller.close(perWidth)
                },
            )
    ) {
        content()
        if (visible) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = controller.progress }
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = stringResource(R.string.tatrak_drawer_close),
                    ) { controller.close() }
            )
            Box(
                Modifier.fillMaxHeight().graphicsLayer { translationX = (controller.progress - 1f) * widthPx * sign }
            ) {
                drawerContent()
            }
        }
    }
}

/**
 * Claims a mostly horizontal drag once it passes the touch slop, if [canClaim] agrees with its direction
 * (total movement so far); vertical drags and unclaimed ones are left to the children untouched.
 * [onEnd] gets the velocity (dp per second) and the total horizontal movement.
 */
@Composable
internal fun Modifier.claimHorizontalDrag(
    canClaim: (dx: Float) -> Boolean,
    onStart: () -> Unit = {},
    onDrag: (dx: Float) -> Unit = {},
    onEnd: (velocityDpPerSecond: Float, totalDx: Float) -> Unit,
): Modifier {
    val claim by rememberUpdatedState(canClaim)
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val tracker = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
            var total = Offset.Zero
            var claimed = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.positionChange()
                total += delta
                if (!claimed) {
                    if (!change.pressed) break
                    if (abs(total.y) > viewConfiguration.touchSlop && abs(total.y) > abs(total.x)) break
                    if (abs(total.x) <= viewConfiguration.touchSlop) continue
                    if (!claim(total.x)) break
                    claimed = true
                    start()
                    drag(total.x - viewConfiguration.touchSlop * Math.signum(total.x))
                } else {
                    drag(delta.x)
                }
                change.consume()
                if (!change.pressed) break
            }
            if (claimed) end(tracker.calculateVelocity().x / density, total.x)
        }
    }
}
