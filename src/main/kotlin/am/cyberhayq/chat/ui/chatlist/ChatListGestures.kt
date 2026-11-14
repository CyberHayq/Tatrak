package am.cyberhayq.chat.ui.chatlist

import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.contentOn
import am.cyberhayq.chat.ui.theme.rememberPreference
import android.content.Context
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
 * Share of the hidden archive row in view when the finger lifts: from [ARCHIVE_OPEN_FRACTION] the archive
 * opens if the row has been coming in for [ARCHIVE_MIN_PULL_MS] (Telegram's PullForegroundDrawable
 * SNAP_HEIGHT and minPullingTime), from [ARCHIVE_SHOW_FRACTION] the row stays in view, less slides it back
 * (an accidental pull at the top of the list).
 */
private const val ARCHIVE_OPEN_FRACTION = 0.85f
private const val ARCHIVE_SHOW_FRACTION = 1f / 3f
internal const val ARCHIVE_MIN_PULL_MS = 200L

internal fun archiveOpensOnRelease(shown: Int, rowHeight: Int): Boolean =
    rowHeight > 0 && shown >= rowHeight * ARCHIVE_OPEN_FRACTION

/** Share of the finger's movement the row follows while pulled in: Telegram's pull parallax, 0.45 down to 0.2. */
internal fun archivePullShare(shown: Int, rowHeight: Int): Float =
    0.45f - 0.25f * (if (rowHeight > 0) (shown.toFloat() / rowHeight).coerceIn(0f, 1f) else 0f)

internal fun archiveShowsOnRelease(shown: Int, rowHeight: Int): Boolean =
    rowHeight > 0 && shown >= rowHeight * ARCHIVE_SHOW_FRACTION

// ---- hidden / pinned archive row ----

const val ARCHIVE_HIDDEN_KEY = "tatrak_archive_hidden"

/** Telegram's "hide above list" (the default) or "pin in chat list", updated live. */
@Composable
fun rememberArchiveHidden(): Boolean =
    rememberPreference(ARCHIVE_HIDDEN_KEY) { it.getBoolean(ARCHIVE_HIDDEN_KEY, true) }

fun setArchiveHidden(context: Context, hidden: Boolean) =
    PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(ARCHIVE_HIDDEN_KEY, hidden).apply()

/**
 * Telegram's hidden archive. Pulling the list down brings the row into view under a foreground ("Swipe
 * down to see archive", "Release to see archive"). A release with the whole row pulled in opens the
 * archive; with part of it the real row stays, until it scrolls out of view; a short pull hides it again.
 */
@Stable
internal class ArchiveReveal(private val list: LazyListState, private val onOpen: () -> Unit) : NestedScrollConnection {
    var revealed by mutableStateOf(false)

    private val rowHeight get() = if (list.firstVisibleItemIndex == 0) list.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0 else 0

    /** Pixels of the hidden row in view. */
    private val shown get() = if (rowHeight == 0) 0 else rowHeight - list.firstVisibleItemScrollOffset

    /** Time of the latest touch event, set by [archivePull]: the pull is timed in event time. */
    internal var eventTime = 0L

    /** Event time when the row started coming into view; null while it is out. */
    private var pullStart: Long? = null

    /** A release now would open the archive. */
    val reached get() =
        !revealed && archiveOpensOnRelease(shown, rowHeight) && eventTime - (pullStart ?: eventTime) >= ARCHIVE_MIN_PULL_MS

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
            // the row resists: the finger travels about 2.5 rows to pull it in far enough to open
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
        when {
            reached -> {
                onOpen()
                // back in the list the row is hidden again
                if (list.canScrollForward) list.scrollToItem(1)
            }
            archiveShowsOnRelease(shown, rowHeight) -> {
                revealed = true
                list.animateScrollToItem(0)
            }
            list.canScrollForward -> list.animateScrollToItem(1)
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

/** Over the hidden row while it is pulled into view; fades out when a release leaves the row in view. */
@Composable
internal fun ArchivePullForeground(reveal: ArchiveReveal, modifier: Modifier = Modifier) {
    val alpha = animateFloatAsState(if (reveal.revealed) 0f else 1f, tween(250), label = "archive foreground")
    if (alpha.value == 0f) return
    val reached by remember(reveal) { derivedStateOf { reveal.reached } }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(reached) { if (reached) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val fill = if (reached) MaterialTheme.colorScheme.primary else TatrakTheme.chatColors.archiveAvatar
    val content = if (reached) contentOn(fill) else Color.White
    // at the bottom: the row slides in from above, so its lower edge shows first
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier.graphicsLayer { this.alpha = alpha.value }.background(fill).padding(bottom = 14.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_keyboard_double_arrow_down_24dp),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (reached) 180f else 0f },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (reached) R.string.tatrak_archive_release else R.string.tatrak_archive_pull),
            color = content,
            fontSize = 14.sp,
        )
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
    return remember { DrawerController(scope) }
}

internal fun drawerWidth(screen: Dp): Dp = (screen * 0.8f).coerceAtMost(320.dp)

/**
 * A modal side menu that follows a left-to-right swipe from anywhere, like old Telegram. The drag is
 * claimed in the Initial pass, so the folder pager never gets it.
 * shortcut: not saved over rotation, and no predictive back; add if the menu is open on rotate often.
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
