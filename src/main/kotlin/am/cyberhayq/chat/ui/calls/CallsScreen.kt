package am.cyberhayq.chat.ui.calls

import am.cyberhayq.chat.ui.chat.LetterAvatar
import am.cyberhayq.chat.ui.common.AVATAR_SIZE
import am.cyberhayq.chat.ui.common.EmptyText
import am.cyberhayq.chat.ui.common.TatrakScaffold
import am.cyberhayq.chat.ui.common.TatrakTopBar
import am.cyberhayq.chat.ui.common.ScrollToTopOn
import am.cyberhayq.chat.ui.common.listBottom
import am.cyberhayq.chat.ui.common.listPadding
import am.cyberhayq.chat.ui.theme.BarBackdrop
import androidx.compose.foundation.lazy.rememberLazyListState
import am.cyberhayq.chat.ui.theme.LocalFloatingBars
import am.cyberhayq.chat.ui.common.MenuEntry
import android.graphics.Bitmap
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R
import eu.siacs.conversations.utils.UIHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row of the call list, ready to draw. */
@Immutable
data class CallEntry(
    /** Message uuid of the newest call of the row. */
    val key: String,
    /** Conversation uuid. */
    val chat: String,
    val name: String,
    val time: Long,
    val received: Boolean,
    val missed: Boolean,
    /** Several alike calls in a row (see [CallLog.group]). */
    val count: Int = 1,
    /** Formatted length of an answered call. */
    val duration: String? = null,
    @DrawableRes val icon: Int,
    val avatar: Bitmap? = null,
)

@Immutable data class CallsState(val entries: List<CallEntry> = emptyList(), val loaded: Boolean = false)

interface CallsActions {
    fun back()

    fun openChat(chat: String)

    fun call(chat: String, video: Boolean)
}

/** Incoming means answered: a missed call is on its own tab, as the row labels split them. */
internal enum class CallsTab(val label: Int, val empty: Int, val keep: (CallEntry) -> Boolean) {
    ALL(R.string.tatrak_tab_all, R.string.tatrak_calls_empty, { true }),
    INCOMING(R.string.tatrak_calls_incoming, R.string.tatrak_calls_no_incoming, { it.received && !it.missed }),
    OUTGOING(R.string.tatrak_calls_outgoing, R.string.tatrak_calls_no_outgoing, { !it.received }),
    MISSED(R.string.tatrak_calls_missed, R.string.tatrak_calls_no_missed, { it.missed }),
}

/**
 * Telegram-style call list: all, incoming, outgoing or missed calls, newest first, call back from a row. With a [dock]
 * it is the chat list's Calls tab: no back button, [scrollToTop] from a tap on the current tab.
 */
@Composable
fun CallsScreen(
    state: CallsState,
    actions: CallsActions,
    modifier: Modifier = Modifier,
    dock: (@Composable (BarBackdrop?) -> Unit)? = null,
    scrollToTop: Int = 0,
) {
    var tab by rememberSaveable { mutableStateOf(CallsTab.ALL) }
    val list = rememberLazyListState()
    ScrollToTopOn(scrollToTop, list)
    val shown = remember(state.entries, tab) { if (tab == CallsTab.ALL) state.entries else state.entries.filter(tab.keep) }
    val time = rememberCallTimeFormatter()
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        // floating: the tabs sit in their own pill, which draws the fill
        val tabFill = if (LocalFloatingBars.current) Color.Transparent else MaterialTheme.colorScheme.surface
        TatrakScaffold(
            topBar = { TatrakTopBar(title = stringResource(R.string.tatrak_calls), onBack = if (dock == null) actions::back else null) },
            bottomBar = dock,
            modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            tabs = {
                // scrollable: four Russian labels do not fit equal widths
                PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, containerColor = tabFill, edgePadding = 0.dp) {
                    CallsTab.entries.forEach { t ->
                        Tab(
                            selected = t == tab,
                            onClick = { tab = t },
                            text = { Text(stringResource(t.label), maxLines = 1) },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        ) { top ->
            if (state.loaded && shown.isEmpty()) {
                EmptyText(stringResource(tab.empty), Modifier.padding(top = top))
            } else {
                LazyColumn(Modifier.fillMaxSize().listBottom(), state = list, contentPadding = listPadding(top)) {
                    items(shown, key = { it.key }) { CallRow(it, time, actions) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(entry: CallEntry, time: CallTimeFormatter, actions: CallsActions) {
    var menuOpen by remember { mutableStateOf(false) }
    val red = MaterialTheme.colorScheme.error
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        role = Role.Button,
                        onClick = { actions.openChat(entry.chat) },
                        onLongClick = { menuOpen = true },
                    )
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            LetterAvatar(
                entry.avatar,
                entry.name.take(1).uppercase(),
                remember(entry.name) { UIHelper.getColorForName(entry.name) },
                AVATAR_SIZE.dp,
                18.sp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (entry.count > 1) "${entry.name} (${entry.count})" else entry.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (entry.missed) red else LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(entry.icon),
                        contentDescription = null,
                        tint = if (entry.missed) red else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        listOfNotNull(kind(entry), time.format(entry.time), entry.duration).joinToString(" · "),
                        fontSize = 14.sp,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { actions.call(entry.chat, video = false) }) {
                Icon(
                    painterResource(R.drawable.ic_call_24dp),
                    contentDescription = stringResource(R.string.audio_call),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            listOf(
                    MenuEntry(stringResource(R.string.audio_call)) { actions.call(entry.chat, video = false) },
                    MenuEntry(stringResource(R.string.video_call)) { actions.call(entry.chat, video = true) },
                    MenuEntry(stringResource(R.string.tatrak_calls_open_chat)) { actions.openChat(entry.chat) },
                )
                .forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        onClick = {
                            menuOpen = false
                            item.onClick()
                        },
                    )
                }
        }
    }
    HorizontalDivider(Modifier.padding(start = (16 + AVATAR_SIZE + 14).dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun kind(entry: CallEntry) =
    stringResource(
        when {
            !entry.received -> R.string.outgoing_call
            entry.missed -> R.string.missed_call
            else -> R.string.incoming_call
        }
    )

/** Time for today's calls, "Yesterday"-free date and time for older ones. */
class CallTimeFormatter(is24Hour: Boolean, locale: Locale) {
    private val time = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)
    private val dayMonth = DateTimeFormatter.ofPattern(if (is24Hour) "d MMM, HH:mm" else "d MMM, h:mm a", locale)
    private val fullDate = DateTimeFormatter.ofPattern(if (is24Hour) "dd.MM.yy, HH:mm" else "dd.MM.yy, h:mm a", locale)

    fun format(timestamp: Long, today: LocalDate = LocalDate.now()): String {
        val dateTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        val date = dateTime.toLocalDate()
        return when {
            !date.isBefore(today) -> time.format(dateTime)
            date.year == today.year -> dayMonth.format(dateTime)
            else -> fullDate.format(dateTime)
        }
    }
}

@Composable
private fun rememberCallTimeFormatter(): CallTimeFormatter {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { CallTimeFormatter(DateFormat.is24HourFormat(context), locale) }
}
