package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.theme.contentOn
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.graphicsLayer
import am.cyberhayq.chat.ui.common.EntryMenuBox
import am.cyberhayq.chat.ui.state.ReportComposition
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.LocalAvatarShape
import am.cyberhayq.chat.ui.theme.LocalTatrakFont
import am.cyberhayq.chat.ui.theme.LocalTatrakStyle
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal val BubbleRadius = 17.dp
internal val BubbleRadiusSmall = 6.dp
internal val TailWidth = 6.dp
private val AvatarSize = 34.dp
// Telegram: 0.4 cm before a swipe-to-reply starts
private val SwipeStartSlop = 25.dp
private val MediaMaxWidth = 270.dp
// the family is null (system font) outside Slate and Sea, which leaves the style as it was
private val BodyStyle: TextStyle
    @Composable get() = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontFamily = LocalTatrakFont.current)
private val MetaStyle: TextStyle
    @Composable get() = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontFamily = LocalTatrakFont.current)

/** Big and small corner radii; [soft] is the Slate and Sea bubble. */
internal fun bubbleRadii(soft: Boolean): Pair<Dp, Dp> = (if (soft) SoftBubbleRadius else BubbleRadius) to BubbleRadiusSmall

private val SoftBubbleRadius = 20.dp

/** Rounded bubble; the last bubble of a group gets a tail on its outer bottom corner. */
class BubbleShape(
    private val outgoing: Boolean,
    private val position: GroupPosition,
    /** Slate and Sea: larger corners and no tail (a small corner instead), same grouping and inset. */
    private val soft: Boolean = false,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val tail = with(density) { TailWidth.toPx() }
        if (soft) return softOutline(size, tail, with(density) { bubbleRadii(true).first.toPx() }, with(density) { BubbleRadiusSmall.toPx() })
        val big = with(density) { BubbleRadius.toPx() }
        val small = with(density) { BubbleRadiusSmall.toPx() }
        val left = if (outgoing) 0f else tail
        val right = if (outgoing) size.width - tail else size.width
        val bottom = size.height
        // corners on the sender side shrink where the bubble touches its neighbours
        val nearTop = if (position.isStart) big else small
        val nearBottom = if (position.isEnd) big else small
        val r = minOf(big, (right - left) / 2f, bottom / 2f)
        val topLeft = minOf(if (outgoing) r else nearTop, r)
        val topRight = minOf(if (outgoing) nearTop else r, r)
        val bottomRight = minOf(if (outgoing) nearBottom else r, r)
        val bottomLeft = minOf(if (outgoing) r else nearBottom, r)
        val withTail = position.isEnd
        val path = Path()
        path.moveTo(left + topLeft, 0f)
        path.lineTo(right - topRight, 0f)
        path.quadraticTo(right, 0f, right, topRight)
        if (withTail && outgoing) {
            val tailTop = bottom - minOf(bottom, with(density) { 11.dp.toPx() })
            path.lineTo(right, tailTop)
            path.quadraticTo(right, bottom - with(density) { 1.dp.toPx() }, size.width, bottom)
            path.lineTo(right - with(density) { 4.dp.toPx() }, bottom)
        } else {
            path.lineTo(right, bottom - bottomRight)
            path.quadraticTo(right, bottom, right - bottomRight, bottom)
        }
        if (withTail && !outgoing) {
            val tailTop = bottom - minOf(bottom, with(density) { 11.dp.toPx() })
            path.lineTo(left + with(density) { 4.dp.toPx() }, bottom)
            path.lineTo(0f, bottom)
            path.quadraticTo(left, bottom - with(density) { 1.dp.toPx() }, left, tailTop)
        } else {
            path.lineTo(left + bottomLeft, bottom)
            path.quadraticTo(left, bottom, left, bottom - bottomLeft)
        }
        path.lineTo(left, topLeft)
        path.quadraticTo(left, 0f, left + topLeft, 0f)
        path.close()
        return Outline.Generic(path)
    }

    private fun softOutline(size: Size, tail: Float, big: Float, small: Float): Outline {
        val left = if (outgoing) 0f else tail
        val right = if (outgoing) size.width - tail else size.width
        val r = minOf(big, (right - left) / 2f, size.height / 2f)
        val near = minOf(small, r)
        val top = minOf(if (position.isStart) big else small, r)
        // the sender side is square-ish at the bottom (where the tail was) and under the grouped neighbours at the top
        val tl = if (outgoing) r else top
        val tr = if (outgoing) top else r
        val br = if (outgoing) near else r
        val bl = if (outgoing) r else near
        return Outline.Rounded(
            RoundRect(left, 0f, right, size.height, CornerRadius(tl), CornerRadius(tr), CornerRadius(br), CornerRadius(bl))
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    item: MessageItem,
    isGroup: Boolean,
    actions: ChatActions,
    voice: StateFlow<VoicePlayback>,
    /** Tap: the message menu, with the bubble's coordinates for the menu to draw it in place. */
    onMenu: (MessageItem, LayoutCoordinates?) -> Unit,
    /** Long press (and a tap while [selecting]): flips this message's selection; a long press also starts the drag selection. */
    onSelect: (MessageItem) -> Unit,
    onSwipeReply: (MessageItem) -> Unit,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    selected: Boolean = false,
    /** Long press on a reaction chip (the message and the chip's emoji): the "who reacted" sheet. Null: the chips have no long press. */
    onReactors: ((MessageItem, String) -> Unit)? = null,
) {
    ReportComposition(item.key)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val offset = remember { Animatable(0f) }
    val method = LocalSwipeMethod.current
    val threshold = with(density) { method.trigger.toPx() }
    val onSwipeReplyState by rememberUpdatedState(onSwipeReply)
    val onMenuState by rememberUpdatedState(onMenu)
    val onSelectState by rememberUpdatedState(onSelect)
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val chipPress = remember { BooleanArray(1) }
    val openMenu = { onMenuState(item, placed[0]?.takeIf { it.isAttached }) }
    val select = { onSelectState(item) }
    val menuLabel = stringResource(R.string.tatrak_open_menu)
    val selectLabel = stringResource(R.string.select)
    val swipe =
        if (item.actions.canReply && !selecting) {
            Modifier.pointerInput(item.key, method) {
                val telegram = method == SwipeMethod.TELEGRAM
                val max = method.max.toPx()
                val startSlop = if (telegram) SwipeStartSlop.toPx() else viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // distance in the reply direction; tracked here, not read back from offset: snapTo runs later in a launched coroutine
                    var d = 0f
                    var claimed = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // a cancelled gesture arrives as an up that is already consumed
                            if (claimed && !change.isConsumed && d >= threshold) onSwipeReplyState(item)
                            break
                        }
                        val before = d
                        if (!claimed) {
                            // a child (voice seek bar) took it
                            if (change.isConsumed) break
                            val total = change.position - down.position
                            // past the slop the list scrolls (it claims after this row in the Main pass)
                            if (abs(total.y) > viewConfiguration.touchSlop) break
                            val along = total.x * method.direction
                            // Telegram (ChatActivity): within atan(1/3) ≈ 18° of horizontal; Signal (ItemTouchHelper): within 45°
                            if (along <= startSlop || abs(total.x) <= abs(total.y) * (if (telegram) 3 else 1)) {
                                // the list claims in Main after this row; give up once it has (as awaitTouchSlopOrCancellation does)
                                if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.id == down.id && it.isConsumed }) break
                                continue
                            }
                            claimed = true
                            // ItemTouchHelper moves the item by the whole drag, Telegram from where the swipe started
                            if (!telegram) d = along.coerceAtMost(max)
                        } else {
                            d = (d + change.positionChange().x * method.direction).coerceIn(0f, max)
                        }
                        change.consume()
                        if (d == before) continue
                        if (before < threshold && d >= threshold) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val next = d * method.direction
                        scope.launch { offset.snapTo(next) }
                    }
                    if (claimed) scope.launch { offset.animateTo(0f) }
                }
            }
        } else {
            Modifier
        }
    Box(
        modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)) else Modifier)
            .padding(top = if (item.position.isStart) 6.dp else 2.dp)
            .then(swipe)
            .then(
                if (item.actions.any) {
                    // a tap beside the bubble opens the menu too; while selecting the list's own handler takes taps
                    Modifier.semantics { if (selecting) this.selected = selected }
                        .combinedClickable(
                            interactionSource = null,
                            indication = null,
                            onClickLabel = if (selecting) selectLabel else menuLabel,
                            onLongClickLabel = selectLabel,
                            onClick = if (selecting) select else openMenu,
                            onLongClick = select,
                        )
                } else {
                    Modifier
                }
            )
    ) {
        if (selecting) SelectionCheck(selected, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 4.dp))
        if (item.actions.canReply && !selecting) {
            // the row recomposes only when a swipe starts or ends; the fade is read in the layer
            val swiping by remember(offset) { derivedStateOf { offset.value != 0f } }
            if (swiping) {
                val left = method.direction > 0
                Box(
                    Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 12.dp)
                        .size(32.dp)
                        .graphicsLayer { alpha = (abs(offset.value) / threshold).coerceIn(0f, 1f) }
                        .clip(CircleShape)
                        .background(ChatScreenTheme.colors.serviceBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_reply_24dp),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Row(
            horizontalArrangement = if (item.isOutgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
            modifier =
                Modifier.fillMaxWidth()
                    .offset { IntOffset(offset.value.roundToInt(), 0) }
                    // room for the check mark; outgoing bubbles sit at the other end
                    .padding(start = if (selecting && !item.isOutgoing) 40.dp else 6.dp, end = 6.dp),
        ) {
            if (isGroup && !item.isOutgoing) {
                if (item.showAvatar) {
                    val description = stringResource(R.string.tatrak_participant_avatar)
                    EntryMenuBox { show ->
                        Box(
                            Modifier.clip(LocalAvatarShape.current)
                                .combinedClickable(
                                    onClickLabel = stringResource(R.string.tatrak_participant_actions),
                                    onLongClickLabel = stringResource(R.string.tatrak_chat_mention_sender),
                                    onClick = { show(actions.senderMenu(item.key)) },
                                    onLongClick = { actions.mentionSender(item.key) },
                                )
                                .semantics { contentDescription = description }
                        ) {
                            LetterAvatar(item.avatar, item.avatarLetter, item.senderColor, AvatarSize, 15.sp)
                        }
                    }
                } else {
                    Spacer(Modifier.width(AvatarSize))
                }
                Spacer(Modifier.width(2.dp))
            }
            val open = primaryAction(item, actions)
            val menuMessage = LocalMenuMessage.current
            CompositionLocalProvider(LocalChipPress provides chipPress) {
            Bubble(
                item = item,
                actions = actions,
                voice = voice,
                onReactors = onReactors,
                modifier =
                    Modifier.maxWidthFraction(if (isGroup && !item.isOutgoing) 0.82f else 0.86f)
                        .onPlaced { placed[0] = it }
                        // the long-press menu draws this bubble in its place
                        .graphicsLayer { alpha = if (menuMessage.value == item.key) 0f else 1f }
                        // TalkBack focuses the bubble's own clickable node: it says "selected" too
                        .semantics { if (selecting) this.selected = selected }
                        // combinedClickable vibrates on long press itself (hapticFeedbackEnabled)
                        .combinedClickable(
                            interactionSource = null,
                            indication = null,
                            onClickLabel = if (selecting) selectLabel else if (open == null) menuLabel else null,
                            onLongClickLabel = selectLabel,
                            // media keep their own tap; the rest opens the menu (Telegram)
                            onClick =
                                when {
                                    !item.actions.any -> open ?: {}
                                    selecting -> select
                                    else -> open ?: openMenu
                                },
                            onLongClick = if (item.actions.any) select else null,
                        )
                        .then(
                            if (item.actions.any) {
                                Modifier.pointerInput(item.key) {
                                    childLongPress(chipPress) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSelectState(item)
                                    }
                                }
                            } else {
                                Modifier
                            }
                        ),
            )
            }
        }
    }
}

/**
 * Long press on a press a child already took: Compose wraps each LinkAnnotation in its own click
 * handler, so the bubble's combinedClickable never sees a press on a link. Swallows the release so
 * the link does not open as well.
 */
private suspend fun PointerInputScope.childLongPress(chipPress: BooleanArray, onLongPress: () -> Unit) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (!down.isConsumed) return@awaitEachGesture // the bubble's combinedClickable has it
    // a reaction chip has it: its long press opens "who reacted", it does not select the message
    if (chipPress[0]) return@awaitEachGesture
    val ended =
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            do {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            } while (change.pressed && (change.position - down.position).getDistance() <= viewConfiguration.touchSlop)
        }
    if (ended != null) return@awaitEachGesture
    onLongPress()
    do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

private fun primaryAction(item: MessageItem, actions: ChatActions): (() -> Unit)? =
    when (item.kind) {
        MessageKind.IMAGE,
        MessageKind.STICKER -> { { actions.openMedia(item.key) } }
        MessageKind.LOCATION -> { { actions.openLocation(item.key) } }
        MessageKind.STICKER_PACK -> { { actions.openLink(item.text.text) } }
        MessageKind.AUDIO -> { { actions.toggleVoice(item.key) } }
        MessageKind.FILE ->
            when (item.file?.state) {
                FileState.REMOTE,
                FileState.CHECK_SIZE -> { { actions.download(item.key) } }
                FileState.LOCAL -> { { actions.openMedia(item.key) } }
                FileState.DOWNLOADING,
                FileState.UPLOADING -> if (item.actions.canCancel) { { actions.cancelTransfer(item.key) } } else null
                null -> null
            }
        else -> null
    }

/** Caps the width at a fraction of the incoming constraints without forcing it. */
private fun Modifier.maxWidthFraction(fraction: Float) = layout { measurable, constraints ->
    val max = (constraints.maxWidth * fraction).roundToInt()
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = max))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** The avatar bitmap, or the first letter on the name colour while there is none. */
@Composable
internal fun LetterAvatar(avatar: Bitmap?, letter: String, color: Int, size: Dp, fontSize: TextUnit) {
    if (avatar != null) {
        Image(
            remember(avatar) { avatar.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(LocalAvatarShape.current),
        )
    } else {
        Box(
            Modifier.size(size).clip(LocalAvatarShape.current).background(Color(color)),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun Bubble(
    item: MessageItem,
    actions: ChatActions,
    voice: StateFlow<VoicePlayback>,
    modifier: Modifier = Modifier,
    onReactors: ((MessageItem, String) -> Unit)? = null,
) {
    if (item.trustWarning == null && !item.retractUnverified && item.reactions.isEmpty() && item.reply == null &&
        item.caption == null) {
        when (item.kind) {
            MessageKind.EMOJI -> return EmojiMessage(item, modifier)
            MessageKind.STICKER -> return StickerMessage(item, actions, modifier)
            else -> Unit
        }
    }
    val chatColors = TatrakTheme.chatColors
    val screenColors = ChatScreenTheme.colors
    val background =
        when {
            item.trustWarning != null -> screenColors.warningBubble
            item.isOutgoing -> chatColors.bubbleOutgoing
            else -> chatColors.bubbleIncoming
        }
    val soft = LocalTatrakStyle.current == TatrakStyle.SLATE_SEA
    val shape = remember(item.isOutgoing, item.position, soft) { BubbleShape(item.isOutgoing, item.position, soft) }
    val media = item.kind == MessageKind.IMAGE || item.kind == MessageKind.STICKER
    Box(
        modifier
            .background(background, shape)
            .padding(
                start = if (item.isOutgoing) 0.dp else TailWidth,
                end = if (item.isOutgoing) TailWidth else 0.dp,
            )
    ) {
        Column(
            Modifier.padding(
                if (media) {
                    PaddingValues(3.dp)
                } else {
                    PaddingValues(
                        start = 10.dp,
                        end = 9.dp,
                        top = 6.dp,
                        bottom = 6.dp,
                    )
                }
            )
        ) {
            val headerPadding = if (media) Modifier.padding(start = 7.dp, end = 6.dp, top = 3.dp, bottom = 3.dp) else Modifier
            if (item.trustWarning != null) {
                TrustWarning(item.trustWarning, headerPadding)
            }
            if (item.retractUnverified) {
                RetractUnverified(item.isOutgoing, headerPadding)
            }
            if (item.senderName != null) {
                Text(
                    item.senderName,
                    color = screenColors.senderName(Color(item.senderColor)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = headerPadding.padding(bottom = 1.dp),
                )
            }
            item.reply?.let { ReplyQuote(it, item.isOutgoing, actions, headerPadding) }
            BubbleContent(item, actions, voice)
            if (item.reactions.isNotEmpty()) {
                Reactions(
                    item,
                    actions,
                    onReactors,
                    Modifier.padding(top = 4.dp).then(if (media) Modifier.padding(4.dp) else Modifier),
                )
                if (item.kind != MessageKind.CALL) {
                    Meta(
                        item,
                        Modifier.align(Alignment.End)
                            .padding(top = 2.dp)
                            .then(if (media) Modifier.padding(end = 6.dp, bottom = 3.dp) else Modifier),
                    )
                }
            }
        }
    }
}

/** True while a finger is down on a reaction chip with a long press; the message's own long press (select) stands aside. */
private val LocalChipPress = staticCompositionLocalOf { BooleanArray(1) }

/** Scrolls the chat to a message by key; provided by the message list. */
internal val LocalOpenMessage = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
private fun ReplyQuote(reply: ReplyHeader, outgoing: Boolean, actions: ChatActions, modifier: Modifier) {
    val accent =
        if (reply.color != 0) {
            ChatScreenTheme.colors.senderName(Color(reply.color))
        } else if (outgoing) {
            ChatScreenTheme.colors.accentOut
        } else {
            ChatScreenTheme.colors.accentIn
        }
    val open = LocalOpenMessage.current
    val textColor = if (outgoing) TatrakTheme.chatColors.textOutgoing else TatrakTheme.chatColors.textIncoming
    Row(
        modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.12f))
            .then(
                if (reply.targetKey != null) {
                    Modifier.clickable(onClickLabel = stringResource(R.string.tatrak_reply)) { open(reply.targetKey) }
                } else {
                    Modifier
                }
            )
            .height(IntrinsicSize.Min)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        // Telegram's ChatMessageCell: 4 dp corners, the side of 7 dp plus the name's and the text's sizes, text 3 dp further
        val thumbnailKey = reply.targetKey?.takeIf { reply.thumbnail }
        if (thumbnailKey != null) {
            ReplyThumbnail(thumbnailKey, 33.dp, 4.dp, actions, Modifier.align(Alignment.CenterVertically).padding(start = 5.dp, top = 3.dp, bottom = 3.dp))
        }
        Column(Modifier.padding(start = if (thumbnailKey != null) 5.dp else 7.dp, end = 8.dp, top = 3.dp, bottom = 3.dp)) {
            Text(reply.name, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(reply.text, color = textColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The answered photo or video, cropped to a square. Asked for at the bubble's size: the bitmap cache is keyed by the
 * message alone, so a smaller first load would leave the bubble itself blurred.
 */
@Composable
internal fun ReplyThumbnail(uuid: String, size: Dp, radius: Dp, actions: ChatActions, modifier: Modifier = Modifier) {
    val sizePx = with(LocalDensity.current) { MediaMaxWidth.roundToPx() }
    val thumbnail by key(uuid, actions, sizePx) {
        produceState(actions.cachedThumbnail(uuid)) {
            if (value == null) value = actions.loadThumbnail(uuid, sizePx)
        }
    }
    Box(modifier.size(size).clip(RoundedCornerShape(radius)).background(Color.Black.copy(alpha = 0.08f))) {
        thumbnail?.let {
            Image(remember(it) { it.asImageBitmap() }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TrustWarning(text: String, modifier: Modifier) {
    val color = ChatScreenTheme.colors.warningText
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(bottom = 2.dp)) {
        Icon(
            painterResource(R.drawable.ic_warning_24dp),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** The note on a kept message that an unverified retraction asked to delete. */
@Composable
private fun RetractUnverified(outgoing: Boolean, modifier: Modifier) {
    val chatColors = TatrakTheme.chatColors
    val color = if (outgoing) chatColors.timeOutgoing else chatColors.timeIncoming
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(bottom = 2.dp)) {
        Icon(
            painterResource(R.drawable.ic_delete_24dp),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.tatrak_retract_unverified),
            color = color,
            fontSize = 13.sp,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun BubbleContent(item: MessageItem, actions: ChatActions, voice: StateFlow<VoicePlayback>) {
    val chatColors = TatrakTheme.chatColors
    val textColor = if (item.isOutgoing) chatColors.textOutgoing else chatColors.textIncoming
    val secondary = if (item.isOutgoing) chatColors.timeOutgoing else chatColors.timeIncoming
    val showMetaHere = item.reactions.isEmpty() && item.caption == null
    when (item.kind) {
        MessageKind.TEXT,
        MessageKind.EMOJI,
        MessageKind.INFO -> {
            val style =
                when (item.kind) {
                    MessageKind.INFO -> BodyStyle.copy(color = secondary, fontStyle = FontStyle.Italic)
                    MessageKind.EMOJI -> BodyStyle.copy(color = textColor, fontSize = 32.sp, lineHeight = 38.sp)
                    else -> BodyStyle.copy(color = textColor)
                }
            val accent = if (item.isOutgoing) ChatScreenTheme.colors.accentOut else ChatScreenTheme.colors.accentIn
            TextWithMeta(
                text = { onLayout -> MessageText(item.text, style, accent, onTextLayout = onLayout) },
                meta = { if (showMetaHere) Meta(item) },
            )
        }
        MessageKind.IMAGE,
        MessageKind.STICKER -> MediaContent(item, actions, showMetaHere)
        MessageKind.AUDIO -> AudioContent(item, actions, voice, showMetaHere)
        MessageKind.FILE -> FileContent(item, textColor, secondary, showMetaHere)
        MessageKind.LOCATION -> LocationContent(item, textColor, showMetaHere)
        MessageKind.CALL -> CallContent(item, textColor, secondary)
        MessageKind.STICKER_PACK -> StickerPackContent(item, textColor, secondary, showMetaHere)
    }
    // Tatrak: the caption under the attachment, with the time after it
    item.caption?.let { caption ->
        val modifier =
            if (item.kind == MessageKind.IMAGE) {
                val width = item.media?.let { mediaSize(it.width, it.height).first } ?: MediaMaxWidth
                Modifier.widthIn(max = width).padding(start = 7.dp, end = 6.dp, top = 4.dp, bottom = 3.dp)
            } else {
                Modifier.padding(top = 4.dp)
            }
        Caption(caption, item, textColor, item.reactions.isEmpty(), modifier)
    }
}

/** Tatrak: the caption under a file, picture or voice message, with the time after it. */
@Composable
private fun Caption(caption: AnnotatedString, item: MessageItem, textColor: Color, showMeta: Boolean, modifier: Modifier) {
    val accent = if (item.isOutgoing) ChatScreenTheme.colors.accentOut else ChatScreenTheme.colors.accentIn
    TextWithMeta(
        text = { onLayout -> MessageText(caption, BodyStyle.copy(color = textColor), accent, onTextLayout = onLayout) },
        meta = { if (showMeta) Meta(item) },
        modifier = modifier,
    )
}

/**
 * Text followed by the time: on the last line if it fits, as in Telegram, otherwise on a line
 * of its own.
 */
@Composable
private fun TextWithMeta(
    text: @Composable (onLayout: (TextLayoutResult) -> Unit) -> Unit,
    meta: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(content = { text { holder[0] = it }; meta() }, modifier = modifier) { measurables, constraints ->
        val textPlaceable = measurables[0].measure(constraints.copy(minWidth = 0))
        val metaPlaceable = measurables.getOrNull(1)?.measure(constraints.copy(minWidth = 0))
        if (metaPlaceable == null || metaPlaceable.width == 0) {
            return@Layout layout(textPlaceable.width, textPlaceable.height) { textPlaceable.place(0, 0) }
        }
        val gap = 8.dp.roundToPx()
        val result = holder[0]
        val lastLineRight =
            if (result != null && result.lineCount > 0) {
                ceil(result.getLineRight(result.lineCount - 1)).toInt()
            } else {
                textPlaceable.width
            }
        val inline = lastLineRight + gap + metaPlaceable.width <= constraints.maxWidth
        val width: Int
        val height: Int
        val metaY: Int
        if (inline) {
            width = max(textPlaceable.width, lastLineRight + gap + metaPlaceable.width)
            metaY = textPlaceable.height - metaPlaceable.height + 3.dp.roundToPx()
            height = max(textPlaceable.height, metaY + metaPlaceable.height)
        } else {
            width = max(textPlaceable.width, metaPlaceable.width)
            metaY = textPlaceable.height
            height = textPlaceable.height + metaPlaceable.height
        }
        val finalWidth = width.coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(finalWidth, height) {
            textPlaceable.place(0, 0)
            metaPlaceable.place(finalWidth - metaPlaceable.width, metaY)
        }
    }
}

/** Edited mark, lock, time and delivery checks. */
@Composable
fun Meta(item: MessageItem, modifier: Modifier = Modifier, overMedia: Boolean = false) {
    val chatColors = TatrakTheme.chatColors
    val screenColors = ChatScreenTheme.colors
    val color =
        when {
            overMedia -> Color.White
            item.trustWarning != null -> screenColors.warningText
            item.isOutgoing -> chatColors.timeOutgoing
            else -> chatColors.timeIncoming
        }
    val failed = item.status == DeliveryStatus.FAILED || item.status == DeliveryStatus.CANCELLED
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            if (overMedia) {
                modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(screenColors.mediaMetaBackground)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            } else {
                modifier
            },
    ) {
        if (item.edited) {
            Text(
                stringResource(R.string.tatrak_chat_edited),
                style = MetaStyle,
                color = color,
                fontStyle = FontStyle.Italic,
            )
            Spacer(Modifier.width(4.dp))
        }
        if (item.security != Security.NONE) {
            Icon(
                painterResource(
                    if (item.security == Security.VERIFIED) {
                        R.drawable.ic_verified_user_24dp
                    } else {
                        R.drawable.ic_lock_24dp
                    }
                ),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(
            item.statusText ?: item.time,
            style = MetaStyle,
            color = if (failed && item.isOutgoing) screenColors.error else color,
            maxLines = 1,
        )
        if (item.isOutgoing && item.status != DeliveryStatus.NONE) {
            Spacer(Modifier.width(3.dp))
            StatusIcon(item.status, if (overMedia) Color.White else color)
        }
    }
}

@Composable
private fun StatusIcon(status: DeliveryStatus, color: Color) {
    val (icon, tint) =
        when (status) {
            DeliveryStatus.SENDING -> R.drawable.ic_schedule_24dp to color
            DeliveryStatus.SENT -> R.drawable.ic_done_24dp to color
            DeliveryStatus.DELIVERED -> R.drawable.ic_done_all_24dp to color
            DeliveryStatus.READ ->
                R.drawable.ic_done_all_24dp to
                    if (color == Color.White || TatrakTheme.chatColors.bubbleOutgoing.luminance() < 0.4f) {
                        Color(0xFF4DDCFF)
                    } else {
                        Color(0xFF1565C0)
                    }
            DeliveryStatus.FAILED -> R.drawable.ic_error_24dp to ChatScreenTheme.colors.error
            DeliveryStatus.CANCELLED -> R.drawable.ic_cancel_24dp to ChatScreenTheme.colors.error
            DeliveryStatus.OFFERED -> R.drawable.ic_p2p_24dp to color
            DeliveryStatus.NONE -> return
        }
    Icon(
        painterResource(icon),
        contentDescription = stringResource(statusDescription(status) ?: return),
        tint = tint,
        // one 16 dp slot for every status: the smaller clock grew the bubble when the send was acked
        modifier = Modifier.size(16.dp).padding(if (status == DeliveryStatus.SENDING) 1.5.dp else 0.dp),
    )
}

@Composable
private fun EmojiMessage(item: MessageItem, modifier: Modifier) {
    Column(modifier.padding(horizontal = TailWidth), horizontalAlignment = Alignment.End) {
        Text(item.text, style = TextStyle(fontSize = 44.sp, lineHeight = 52.sp))
        Meta(item, overMedia = true)
    }
}

@Composable
private fun StickerMessage(item: MessageItem, actions: ChatActions, modifier: Modifier) {
    val media = item.media ?: return
    val sizePx = with(LocalDensity.current) { StickerSize.roundToPx() }
    val sticker by
        produceState(actions.cachedThumbnail(item.key), item.key) {
            if (value == null) value = actions.loadThumbnail(item.key, sizePx)
        }
    val ratio = media.width.toFloat() / media.height
    Column(modifier.padding(horizontal = TailWidth), horizontalAlignment = Alignment.End) {
        Box(
            Modifier.size(
                if (ratio >= 1f) StickerSize else StickerSize * ratio,
                if (ratio >= 1f) StickerSize / ratio else StickerSize,
            )
        ) {
            sticker?.let {
                Image(
                    remember(it) { it.asImageBitmap() },
                    contentDescription = stringResource(R.string.tatrak_sticker),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Meta(item, overMedia = true)
    }
}

private val StickerSize = 160.dp

/** Scales the attachment's own dimensions into the bubble so the list never jumps on load. */
private fun mediaSize(width: Int, height: Int): Pair<Dp, Dp> {
    val ratio = width.toFloat() / height.toFloat()
    var w = MediaMaxWidth
    var h = w / ratio
    if (h > 340.dp) {
        h = 340.dp
        w = (h * ratio).coerceAtLeast(150.dp)
    }
    if (h < 120.dp) h = 120.dp
    return w to h
}

@Composable
private fun MediaContent(item: MessageItem, actions: ChatActions, showMeta: Boolean) {
    val media = item.media ?: return
    val (width, height) = remember(media.width, media.height) { mediaSize(media.width, media.height) }
    val sizePx = with(LocalDensity.current) { MediaMaxWidth.roundToPx() }
    val thumbnail by
        produceState(actions.cachedThumbnail(item.key), item.key) {
            if (value == null) value = actions.loadThumbnail(item.key, sizePx)
        }
    val inner = bubbleRadii(LocalTatrakStyle.current == TatrakStyle.SLATE_SEA).first - 3.dp
    Box(
        Modifier.size(width, height)
            .clip(RoundedCornerShape(inner))
            .background(Color.Black.copy(alpha = 0.08f))
    ) {
        thumbnail?.let {
            Image(
                remember(it) { it.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (media.progress >= 0) {
            Box(
                Modifier.align(Alignment.Center)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ChatScreenTheme.colors.mediaMetaBackground),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { media.progress / 100f },
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(38.dp),
                )
            }
        } else if (media.isVideo) {
            Box(
                Modifier.align(Alignment.Center)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ChatScreenTheme.colors.mediaMetaBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_play_arrow_24dp),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        if (showMeta) {
            Meta(item, Modifier.align(Alignment.BottomEnd).padding(6.dp), overMedia = true)
        }
    }
}

@Composable
private fun RoundIcon(
    icon: Int,
    background: Color,
    modifier: Modifier = Modifier,
    progress: Int = -1,
    contentDescription: String? = null,
) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (progress >= 0) {
            CircularProgressIndicator(
                progress = { progress / 100f },
                color = contentOn(background),
                strokeWidth = 2.dp,
                modifier = Modifier.size(36.dp),
            )
        }
        Icon(painterResource(icon), contentDescription, tint = contentOn(background), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AudioContent(
    item: MessageItem,
    actions: ChatActions,
    voice: StateFlow<VoicePlayback>,
    showMeta: Boolean,
) {
    // only the bubble that is playing follows the position ticks
    val playback by
        remember(voice, item.key) {
                voice.map { it.takeIf { p -> p.uuid == item.key } }.distinctUntilChanged()
            }
            .collectAsState(null)
    val playing = playback?.playing == true
    val duration = playback?.durationMs?.takeIf { it > 0 } ?: item.audioDurationMs
    val position = playback?.positionMs ?: 0
    val accent = if (item.isOutgoing) ChatScreenTheme.colors.accentOut else ChatScreenTheme.colors.accentIn
    val secondary = if (item.isOutgoing) TatrakTheme.chatColors.timeOutgoing else TatrakTheme.chatColors.timeIncoming
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(230.dp).padding(vertical = 2.dp)) {
        RoundIcon(
            if (playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp,
            accent,
            Modifier.clickable(role = Role.Button) { actions.toggleVoice(item.key) },
            contentDescription = stringResource(if (playing) R.string.pause_audio else R.string.play_audio),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val fraction = voiceFraction(position, duration)
            // seeking only works on the clip loaded in the player; other bubbles show a plain bar
            val seekable = playback != null
            val seek =
                if (seekable) {
                    Modifier.pointerInput(item.key) {
                            detectTapGestures { actions.seekVoice(item.key, it.x / size.width) }
                        }
                        .pointerInput(item.key) {
                            // consumed here so the bubble's swipe-to-reply does not start
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                actions.seekVoice(item.key, change.position.x / size.width)
                            }
                        }
                        .semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                            setProgress { actions.seekVoice(item.key, it); true }
                        }
                } else {
                    Modifier
                }
            // taller touch strip around the 4dp bar
            Box(
                Modifier.fillMaxWidth().height(16.dp).then(seek).then(if (seekable) Modifier.swipeBackExclusion() else Modifier),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(accent.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(accent))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDuration(if (position > 0) position else duration),
                    style = MetaStyle,
                    color = secondary,
                    modifier = Modifier.weight(1f),
                )
                if (showMeta) Meta(item)
            }
        }
    }
}

internal fun voiceFraction(position: Int, duration: Int): Float =
    if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

internal fun formatDuration(ms: Int): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun FileContent(item: MessageItem, textColor: Color, secondary: Color, showMeta: Boolean) {
    val file = item.file ?: return
    val accent = if (item.isOutgoing) ChatScreenTheme.colors.accentOut else ChatScreenTheme.colors.accentIn
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(min = 200.dp).padding(vertical = 2.dp),
    ) {
        val icon =
            when (file.state) {
                FileState.REMOTE,
                FileState.CHECK_SIZE -> R.drawable.ic_download_24dp
                // the bubble tap cancels only when the transfer is cancelable
                FileState.DOWNLOADING,
                FileState.UPLOADING ->
                    if (item.actions.canCancel) R.drawable.ic_close_24dp else R.drawable.ic_description_24dp
                FileState.LOCAL -> R.drawable.ic_description_24dp
            }
        val cancel = stringResource(R.string.cancel_transmission)
        RoundIcon(icon, accent, progress = file.progress, contentDescription = if (icon == R.drawable.ic_close_24dp) cancel else null)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(IntrinsicSize.Max).widthIn(max = 230.dp)) {
            Text(
                file.title,
                color = textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Text(
                    file.subtitle.orEmpty(),
                    color = secondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (showMeta) Meta(item, Modifier.padding(start = 12.dp, bottom = 1.dp))
            }
        }
    }
}

@Composable
private fun LocationContent(item: MessageItem, textColor: Color, showMeta: Boolean) {
    val accent = if (item.isOutgoing) ChatScreenTheme.colors.accentOut else ChatScreenTheme.colors.accentIn
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        RoundIcon(R.drawable.ic_location_pin_24dp, accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(IntrinsicSize.Max)) {
            Text(
                stringResource(R.string.location),
                color = textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.show_location),
                    color = TatrakTheme.chatColors.link,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                if (showMeta) Meta(item, Modifier.padding(start = 12.dp, bottom = 1.dp))
            }
        }
    }
}

/** A shared sticker set: the host instead of the long link; a tap asks to add it. */
@Composable
private fun StickerPackContent(item: MessageItem, textColor: Color, secondary: Color, showMeta: Boolean) {
    val accent = if (item.isOutgoing) ChatScreenTheme.colors.accentOut else ChatScreenTheme.colors.accentIn
    val host = remember(item.text) { item.text.text.toHttpUrlOrNull()?.host.orEmpty() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        RoundIcon(R.drawable.tatrak_ic_emoji_24dp, accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(IntrinsicSize.Max)) {
            Text(
                stringResource(R.string.tatrak_stickers_pack_card),
                color = textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(host, color = secondary, fontSize = 13.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.tatrak_stickers_pack_view),
                    color = TatrakTheme.chatColors.link,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                if (showMeta) Meta(item, Modifier.padding(start = 12.dp, bottom = 1.dp))
            }
        }
    }
}

@Composable
private fun CallContent(item: MessageItem, textColor: Color, secondary: Color) {
    val call = item.call ?: return
    val accent = if (item.isOutgoing) ChatScreenTheme.colors.accentOut else ChatScreenTheme.colors.accentIn
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 190.dp)) {
        Column(Modifier.weight(1f, fill = false).padding(end = 16.dp)) {
            Text(call.title, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(call.icon),
                    contentDescription = null,
                    tint = if (call.missed) ChatScreenTheme.colors.error else Color(0xFF4CAF50),
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (call.duration != null) "${item.time}, ${call.duration}" else item.time,
                    color = secondary,
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_call_24dp),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun Reactions(item: MessageItem, actions: ChatActions, onReactors: ((MessageItem, String) -> Unit)?, modifier: Modifier = Modifier) {
    val whoReacted = stringResource(R.string.tatrak_chat_who_reacted)
    val chipPress = LocalChipPress.current
    val accent = if (item.isOutgoing) ChatScreenTheme.colors.accentOut else ChatScreenTheme.colors.accentIn
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        for (reaction in item.reactions) {
            val enabled =
                if (reaction.mine) item.actions.canRemoveReaction else item.actions.canReact
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (reaction.mine) accent else accent.copy(alpha = 0.14f))
                        // the long press also works where reacting is not allowed; combinedClickable vibrates on it itself
                        .combinedClickable(
                            enabled = enabled || onReactors != null,
                            onClickLabel = null,
                            onLongClickLabel = whoReacted,
                            onClick = { if (enabled) actions.toggleReaction(item.key, reaction.emoji) },
                            onLongClick = onReactors?.let { { it(item, reaction.emoji) } },
                        )
                        // seen before the chip consumes the press: the bubble's childLongPress checks it
                        .then(
                            if (onReactors == null) Modifier
                            else Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    chipPress[0] = true
                                    try {
                                        do {
                                            val event = awaitPointerEvent()
                                        } while (event.changes.any { it.pressed })
                                    } finally {
                                        chipPress[0] = false
                                    }
                                }
                            }
                        )
                        .padding(horizontal = 8.dp),
            ) {
                Text(reaction.emoji, fontSize = 14.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    reaction.count.toString(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (reaction.mine) contentOn(accent) else accent,
                )
            }
        }
    }
}

/** What the delivery tick says to TalkBack; null without one. */
internal fun statusDescription(status: DeliveryStatus): Int? =
    when (status) {
        DeliveryStatus.SENDING -> R.string.tatrak_status_sending
        DeliveryStatus.SENT -> R.string.tatrak_status_sent
        DeliveryStatus.DELIVERED -> R.string.tatrak_status_delivered
        DeliveryStatus.READ -> R.string.tatrak_status_read
        DeliveryStatus.FAILED -> R.string.tatrak_status_failed
        DeliveryStatus.CANCELLED -> R.string.tatrak_status_cancelled
        DeliveryStatus.OFFERED -> R.string.tatrak_status_offered
        DeliveryStatus.NONE -> null
    }
