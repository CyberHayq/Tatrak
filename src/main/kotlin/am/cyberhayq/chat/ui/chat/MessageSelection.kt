package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.theme.StatusBarIcons
import am.cyberhayq.chat.ui.theme.TatrakTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Telegram's cap on selected messages (ChatActivity.startMultiselect). */
internal const val MAX_SELECTED = 100

/** What the long press of the current gesture started: dragging from [key] selects ([select]) or unselects rows over [base]. */
internal class SelectionDrag(val key: String, val base: List<String>, val select: Boolean)

/** [selection] with [key] flipped; nothing is added past [MAX_SELECTED]. */
internal fun toggled(selection: List<String>, key: String): List<String> =
    when {
        key in selection -> selection - key
        selection.size < MAX_SELECTED -> selection + key
        else -> selection
    }

/**
 * The selection while a long-press drag runs from list index [from] to [to]: [base] plus (or minus, when not [select])
 * every selectable row in between, added from [from] outwards until [MAX_SELECTED]. After Telegram's
 * RecyclerListView.chekMultiselect and ChatActivity.startMultiselect (21f7d10), recomputed from the range instead of
 * stepped row by row; rows selected before the gesture stay as they were in both.
 */
internal fun dragSelection(base: List<String>, from: Int, to: Int, select: Boolean, keyAt: (Int) -> String?): List<String> {
    val range = (if (to >= from) from..to else from downTo to).asSequence().mapNotNull(keyAt)
    if (!select) {
        val drop = range.toHashSet()
        return base.filterNot { it in drop }
    }
    val result = LinkedHashSet(base)
    for (key in range) {
        if (result.size >= MAX_SELECTED) break
        result += key
    }
    return result.toList()
}

/**
 * Telegram's copy of several messages (ChatActivity copy and getMessageContent, 21f7d10): [messages]
 * oldest first, a "Name:" line before each run of one sender when there is more than one message and [withNames]
 * (not in the own Saved Messages), a blank line between messages.
 */
internal fun selectionCopyText(messages: List<SelectedText>, withNames: Boolean): String {
    val named = withNames && messages.size > 1
    var previous: SelectedText? = null
    return messages.joinToString("\n\n") { message ->
        // a new run when the sender changes, by identity: two people can share a display name (Telegram: from id)
        val header = if (named && (previous == null || previous!!.sender != message.sender)) "${message.name}:\n" else ""
        previous = message
        header + message.text
    }
}

/** One message for [selectionCopyText]: [sender] identifies who sent it (null for the own account), [name] is shown. */
internal data class SelectedText(val sender: String?, val name: String, val text: String)

// Telegram's auto-scroll zone at each end of the list and its step per frame (RecyclerListView.onTouchEvent, scroller)
private val AutoScrollEdge = 56.dp
private val AutoScrollStep = 12.dp

/**
 * Long-press drag selection and, while selecting, tap to toggle. Runs in the list's Initial pass, ahead of the rows and
 * the list's own scrolling: a row's long press arms [drag], and from then on this handler owns the gesture.
 */
internal suspend fun PointerInputScope.selectionGestures(
    listState: LazyListState,
    drag: Array<SelectionDrag?>,
    scope: CoroutineScope,
    selection: () -> List<String>,
    items: () -> List<ChatItem>,
    onChange: (List<String>) -> Unit,
) {
    val edge = AutoScrollEdge.toPx()
    val step = AutoScrollStep.toPx()
    fun keyAt(index: Int) = (items().getOrNull(index) as? MessageItem)?.takeIf { it.actions.any }?.key
    fun dragTo(start: SelectionDrag, y: Float) {
        val from = items().indexOfFirst { it.key == start.key }
        val to = listState.layoutInfo.indexAt(y) ?: return
        if (from < 0) return
        val next = dragSelection(start.base, from, to, start.select, ::keyAt)
        if (next != selection()) onChange(next)
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        drag[0] = null
        // a touch that stops a fling is not a tap on a row (Telegram: the list takes it)
        var tap = selection().isNotEmpty() && !listState.isScrollInProgress
        var y = down.position.y
        var direction = 0
        var scroller: Job? = null
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                val start = drag[0]
                if (start != null) {
                    // neither the list scrolls nor the row swipes under the drag
                    change.consume()
                    if (!change.pressed) break
                    y = change.position.y
                    dragTo(start, y)
                    val wanted = if (y < edge) 1 else if (y > size.height - edge) -1 else 0
                    if (wanted != direction) {
                        direction = wanted
                        scroller?.cancel()
                        // reversed list: a positive scroll shows older messages, at the top
                        scroller =
                            if (wanted == 0) {
                                null
                            } else {
                                scope.launch {
                                    while (true) {
                                        listState.scrollBy(wanted * step)
                                        dragTo(start, y)
                                        withFrameNanos {}
                                    }
                                }
                            }
                    }
                    continue
                }
                if (!change.pressed) {
                    // in the Initial pass only a cancelled gesture arrives already consumed
                    val key = if (tap && !change.isConsumed) listState.layoutInfo.indexAt(down.position.y)?.let(::keyAt) else null
                    if (key != null) {
                        change.consume() // the row's own tap (menu, media, link) must not run as well
                        onChange(toggled(selection(), key))
                    }
                    break
                }
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false
            }
        } finally {
            scroller?.cancel()
            drag[0] = null
        }
    }
}

/** The list item under [y] (pixels from the list's top edge); the nearest one when [y] is in the content padding. */
internal fun LazyListLayoutInfo.indexAt(y: Float): Int? {
    // offsets run from the list's start (the bottom when reversed), after the content padding before the first item
    val main = (if (reverseLayout) viewportSize.height - y else y) + viewportStartOffset
    return visibleItemsInfo
        .minByOrNull {
            when {
                main < it.offset -> it.offset - main
                main >= it.offset + it.size -> main - (it.offset + it.size) + 1
                else -> 0f
            }
        }
        ?.index
}

/** Telegram's check mark at the start of a row while selecting. */
@Composable
internal fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else ChatScreenTheme.colors.serviceBackground)
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(painterResource(R.drawable.ic_check_24dp), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/** Replaces the chat's top bar while messages are selected; a null action hides its button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onCopy: (() -> Unit)?,
    onForward: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val bar = TatrakTheme.chatColors
    StatusBarIcons(dark = bar.topBar.luminance() > 0.5f)
    TopAppBar(
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = bar.topBar,
                titleContentColor = bar.onTopBar,
                navigationIconContentColor = bar.onTopBar,
                actionIconContentColor = bar.onTopBar,
            ),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close_24dp), contentDescription = stringResource(R.string.tatrak_close_selection))
            }
        },
        title = { Text(count.toString(), fontWeight = FontWeight.Medium, fontSize = 20.sp) },
        actions = {
            listOfNotNull(
                    onCopy?.let { Triple(R.drawable.tatrak_chat_ic_copy_24dp, R.string.copy_to_clipboard, it) },
                    onForward?.let { Triple(R.drawable.tatrak_chat_ic_forward_24dp, R.string.tatrak_chat_forward, it) },
                    onDelete?.let { Triple(R.drawable.ic_delete_24dp, R.string.delete, it) },
                )
                .forEach { (icon, label, action) ->
                    IconButton(onClick = action) { Icon(painterResource(icon), contentDescription = stringResource(label)) }
                }
        },
    )
}
