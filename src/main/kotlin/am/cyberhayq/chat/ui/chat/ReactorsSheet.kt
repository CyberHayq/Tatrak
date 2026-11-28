package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.common.EntryMenuBox
import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/** Who reacted to a message (long press on a reaction chip): a tab per emoji, a row per person; [initialEmoji] picks the tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactorsSheet(
    uuid: String,
    rows: List<ReactorRow>,
    chipOrder: List<String>,
    initialEmoji: String,
    actions: ChatActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ReactorsSheetContent(uuid, rows, chipOrder, initialEmoji, actions, onDismiss, Modifier.navigationBarsPadding())
    }
}

@Composable
internal fun ReactorsSheetContent(
    uuid: String,
    rows: List<ReactorRow>,
    chipOrder: List<String>,
    initialEmoji: String,
    actions: ChatActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = reactorTabs(rows, chipOrder)
    // null is the "All" tab
    var chosen by rememberSaveable(uuid, initialEmoji) { mutableStateOf<String?>(initialEmoji) }
    // the emoji's last reaction was taken back while the sheet is open: fall back to All
    val selected = chosen.takeIf { e -> tabs.any { it.first == e } }
    Column(modifier.fillMaxWidth()) {
        if (tabs.isNotEmpty()) {
            PrimaryScrollableTabRow(
                selectedTabIndex = tabs.indexOfFirst { it.first == selected }.coerceAtLeast(0),
                containerColor = Color.Transparent,
                edgePadding = 0.dp,
            ) {
                tabs.forEach { (emoji, count) ->
                    Tab(
                        selected = emoji == selected,
                        onClick = { chosen = emoji },
                        text = {
                            Text(if (emoji == null) stringResource(R.string.tatrak_reactors_all, count) else "$emoji $count", maxLines = 1)
                        },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val shown = if (selected == null) rows else rows.filter { it.emoji == selected }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown, key = { "${it.who}|${it.emoji}" }) { row -> ReactorItem(uuid, row, actions, onDismiss) }
        }
    }
}

@Composable
private fun ReactorItem(uuid: String, row: ReactorRow, actions: ChatActions, onDismiss: () -> Unit) {
    val size = 40.dp
    val px = with(LocalDensity.current) { size.roundToPx() }
    val avatar by produceState<Bitmap?>(null, uuid, row.who) { value = actions.reactorAvatar(uuid, row.who, px) }
    val content: @Composable (Modifier) -> Unit = { base ->
        Row(
            base.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            LetterAvatar(avatar, row.letter, row.color, size, 16.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                row.name,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(row.emoji, fontSize = 20.sp)
        }
    }
    if (row.tappable) {
        EntryMenuBox { show ->
            content(
                Modifier.clickable(onClickLabel = stringResource(R.string.tatrak_participant_actions)) {
                    // the sheet closes before the chosen entry runs: its dialogs and screens open over the chat
                    show(actions.reactorMenu(uuid, row.who).map { e -> e.copy(onClick = { onDismiss(); e.onClick() }) })
                }
            )
        }
    } else {
        content(Modifier)
    }
}
