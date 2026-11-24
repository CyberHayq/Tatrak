package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.theme.LocalFloatingBars
import android.view.HapticFeedbackConstants
import android.view.animation.OvershootInterpolator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.dismiss
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import eu.siacs.conversations.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

private val OpenKeys = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)

/** No focus enters this subtree (keys, D-pad, Tab): what is inside the chat preview's card. */
private fun Modifier.blockFocus(): Modifier = focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()

/**
 * What the chat preview's card covers (the chat list): out of TalkBack's and the keyboard's reach while [covered]. The
 * focus it had (its search field) is kept and comes back when the card closes in place.
 */
@Composable
fun PreviewCovered(covered: Boolean, content: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    var saved by remember { mutableStateOf(false) }
    // at once, before the card's own effect takes the focus
    DisposableEffect(covered) {
        if (covered) saved = focus.saveFocusedChild()
        onDispose {}
    }
    LaunchedEffect(covered) {
        if (!covered && saved) {
            saved = false
            focus.restoreFocusedChild()
        }
    }
    Box(
        Modifier.fillMaxSize()
            .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
            .focusRequester(focus)
            .focusProperties { if (covered) onEnter = { cancelFocusChange() } }
            .focusGroup()
    ) {
        content()
    }
}

/** Where a message list stands: the first (newest) shown item and how far it is scrolled past. */
data class ScrollAnchor(val key: String, val offset: Int)

/** The list's position as [InitialScroll] restores it; null before anything is shown. */
internal fun LazyListState.anchor(): ScrollAnchor? =
    (layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstVisibleItemIndex }?.key as? String)
        ?.let { ScrollAnchor(it, firstVisibleItemScrollOffset) }

const val CHAT_PREVIEW_TAG = "tatrak_chat_preview"

// Telegram's ActionBarLayout (DrKLO/Telegram 21f7d10): the preview's dim, its open and close curves
// (startLayoutAnimation) and the pulse of expandPreviewFragment
private val Dim = Color(0x2e000000)
private const val OPEN_MS = 190
private const val CLOSE_MS = 150
private const val PULSE_MS = 200
private val Overshoot = OvershootInterpolator(1.02f).let { Easing(it::getInterpolation) }
private val EaseOutQuint = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private val EaseBoth = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * Telegram's chat preview (long press on a chat list avatar): the chat in a card over the dimmed
 * list, drawn in the list's own window so it can become the chat. A tap makes the card the full
 * chat at once and pulses it, as Telegram's expandPreviewFragment; when the pulse ends [onOpen]
 * gets the list's position and opens the real chat, which replaces the list and this card together.
 * An outside tap or Back closes it. [actions] must not send read markers. [draft]: the composer's
 * text once expanded, as the chat will show it.
 */
@Composable
fun ChatPreview(
    state: ChatState,
    actions: ChatActions,
    onOpen: (ScrollAnchor?) -> Unit,
    onDismiss: () -> Unit,
    draft: String = "",
    /** The tap, before the pulse: the hand-off starts here, so a navigation during the pulse can end it. */
    onExpand: () -> Unit = {},
) {
    val open by rememberUpdatedState(onOpen)
    val expanding by rememberUpdatedState(onExpand)
    val dismiss by rememberUpdatedState(onDismiss)
    val openLabel = stringResource(R.string.tatrak_chat_open)
    val closeLabel = stringResource(R.string.tatrak_chat_preview_close)
    val focus = remember { FocusRequester() }
    val voice = remember { MutableStateFlow(VoicePlayback()) }
    val recording = remember { MutableStateFlow(VoiceRecording()) }
    val list = rememberLazyListState()
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // 0..1 (with overshoot): the card coming in; closing runs it back with Telegram's close curve
    val shown = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    // set once by the tap: the card is the chat from then on; the anchor keeps its place through the relayout
    var expanded by remember { mutableStateOf(false) }
    var startAt by remember { mutableStateOf<ScrollAnchor?>(null) }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // the keys come to the card, not to what had focus under it (the list's search field stops taking text)
        focus.requestFocus()
        shown.animateTo(1f, tween(OPEN_MS, easing = Overshoot))
    }
    // expanded, the list is no preview any more and reports what it shows: still nothing may be marked read
    val inert = remember(actions) { object : ChatActions by actions { override fun markRead(uuid: String) = Unit } }

    fun close() {
        // a second close (Back and an outside tap together) must not close twice
        if (closing || expanded) return
        closing = true
        scope.launch {
            shown.animateTo(0f, tween(CLOSE_MS, easing = EaseOutQuint))
            dismiss()
        }
    }

    fun expand() {
        if (closing || expanded) return
        expanded = true
        expanding()
        startAt = list.anchor()
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        scope.launch {
            pulse.animateTo(1f, tween(PULSE_MS, easing = EaseBoth))
            open(list.anchor() ?: startAt)
        }
    }

    // Back while expanding does nothing: the chat is on its way
    BackHandler { close() }
    Box(Modifier.fillMaxSize()) {
        if (!expanded) {
            val p = shown.value
            // closing runs Telegram's close: fade out, scale down to 0.9 (opening overshoots instead)
            val dimAlpha = p.coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = dimAlpha }
                    .background(Dim)
                    .clearAndSetSemantics {}
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); close() } }
            )
        }
        val cardModifier =
            if (expanded) {
                // Telegram's pulse: scale 1 -> 1.05 -> 1 over the eased fraction
                Modifier.fillMaxSize().graphicsLayer {
                    val s = 1f + 0.05f * (1f - abs(2f * pulse.value - 1f))
                    scaleX = s
                    scaleY = s
                }
                    // inert until the chat replaces it: every touch is eaten, TalkBack sees nothing to act on
                    .clearAndSetSemantics {}
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
            } else {
                Modifier.fillMaxWidth(0.92f)
                    .fillMaxHeight(0.75f)
                    .graphicsLayer {
                        val p = shown.value
                        alpha = p.coerceIn(0f, 1f)
                        val s = if (closing) 0.9f + 0.1f * p else 0.7f + 0.3f * p
                        scaleX = s
                        scaleY = s
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .semantics {
                        onClick(openLabel) { expand(); true }
                        dismiss(closeLabel) { close(); true }
                    }
                    // Initial pass, before the bubbles: a short tap that never left the slop expands and is consumed,
                    // so no bubble, voice or jump button acts on it; a drag is left to the list, a long press does nothing
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            // a touch that stops a fling belongs to the list (as selectionGestures does)
                            var tap = !list.isScrollInProgress
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                // a second finger, a lost pointer or a cancel (it arrives already consumed) is no tap
                                if (change == null || event.changes.size > 1 || change.isConsumed) {
                                    tap = false
                                    break
                                }
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false
                                if (!change.pressed) {
                                    if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) tap = false
                                    if (tap) change.consume()
                                    break
                                }
                            }
                            if (tap) expand()
                        }
                    }
            }
        // the card is centred in the safe area; expanded, the chat takes the whole window and pads itself
        Box(
            Modifier.fillMaxSize().then(if (expanded) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.testTag(CHAT_PREVIEW_TAG)
                    .focusRequester(focus)
                    // Enter or the D-pad's centre opens the card; expanded, it takes every key until the chat replaces it
                    .onKeyEvent { event ->
                        when {
                            expanded -> true
                            event.key !in OpenKeys -> false
                            else -> {
                                if (event.type == KeyEventType.KeyUp) expand()
                                true
                            }
                        }
                    }
                    .focusable()
                    .then(cardModifier)
                    // nothing inside takes focus: the preview's bubbles have no actions, the expanded chrome is inert
                    .blockFocus()
            ) {
                if (expanded) {
                    // the chat as it will open: header buttons, composer with the draft, the user's bar style
                    ChatScreen(
                        state = state,
                        input = remember(draft) { TextFieldValue(draft, TextRange(draft.length)) },
                        onInputChange = {},
                        reply = null,
                        actions = inert,
                        voice = voice,
                        recording = recording,
                        listState = list,
                        startAt = startAt,
                    )
                } else {
                    // the classic layout: the wallpaper behind the list, no floating pills over it
                    CompositionLocalProvider(LocalFloatingBars provides false) {
                        ChatScreen(
                            state = state,
                            input = TextFieldValue(),
                            onInputChange = {},
                            reply = null,
                            actions = actions,
                            voice = voice,
                            recording = recording,
                            preview = true,
                            listState = list,
                        )
                    }
                }
            }
        }
    }
}
