package am.cyberhayq.chat.ui.media

import am.cyberhayq.chat.ui.common.EmptyText
import am.cyberhayq.chat.ui.common.TatrakScaffold
import am.cyberhayq.chat.ui.common.TatrakTopBar
import am.cyberhayq.chat.ui.theme.LocalFloatingBars
import am.cyberhayq.chat.ui.common.MenuEntry
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R
import kotlin.math.roundToInt

/** Telegram's shared-media tabs; the legacy filter types map onto them. */
enum class MediaTab(@StringRes val label: Int) {
    MEDIA(R.string.tatrak_media_tab_media),
    FILES(R.string.tatrak_media_tab_files),
    AUDIO(R.string.tatrak_media_tab_audio),
}

@Immutable
data class MediaItem(
    /** Attachment uuid. */
    val key: String,
    val tab: MediaTab,
    val name: String,
    /** Size and date, for the file and audio lists. */
    val details: String,
    @DrawableRes val icon: Int,
    val video: Boolean = false,
    /** Whether a preview can be drawn; otherwise [icon] is shown. */
    val thumbnail: Boolean = false,
)

@Immutable
data class MediaState(val items: List<MediaItem> = emptyList(), val loaded: Boolean = false)

interface MediaActions {
    fun back()

    fun open(key: String)

    fun share(keys: Set<String>)

    fun save(keys: Set<String>)

    fun delete(keys: Set<String>)

    /** Some of [keys] live in the app's private storage, so "save" can copy them out. */
    fun canSave(keys: Set<String>): Boolean

    /** A square preview of about [sizePx], or null; may be slow, called off the main thread. */
    suspend fun thumbnail(key: String, sizePx: Int): Bitmap?
}

/** Shared media of a chat: photo / video grid, file list and audio list, with multi-select. */
@Composable
fun MediaBrowserScreen(state: MediaState, actions: MediaActions, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableStateOf(MediaTab.MEDIA) }
    var selection by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // drop keys of deleted files
    val present = remember(state.items) { state.items.mapTo(HashSet()) { it.key } }
    LaunchedEffect(present) { if (!present.containsAll(selection)) selection = selection.intersect(present) }
    val shown = remember(state.items, tab) { state.items.filter { it.tab == tab } }
    val toggle: (String) -> Unit = { key -> selection = if (key in selection) selection - key else selection + key }
    val open: (String) -> Unit = { key -> if (selection.isEmpty()) actions.open(key) else toggle(key) }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        // floating: the tabs sit in their own pill, which draws the fill
        val tabFill = if (LocalFloatingBars.current) Color.Transparent else MaterialTheme.colorScheme.surface
        TatrakScaffold(
            topBar = {
                if (selection.isEmpty()) {
                    TatrakTopBar(title = stringResource(R.string.media_browser), onBack = actions::back)
                } else {
                    SelectionBar(selection, shown, actions) { selection = it }
                }
            },
            modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            tabs = {
                PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = tabFill) {
                    MediaTab.entries.forEach { t ->
                        Tab(
                            selected = t == tab,
                            onClick = { tab = t },
                            text = { Text(stringResource(t.label)) },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        ) { top ->
            if (state.loaded && shown.isEmpty()) {
                EmptyText(stringResource(R.string.tatrak_media_empty), Modifier.padding(top = top))
            } else if (tab == MediaTab.MEDIA) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(top = top),
                ) {
                    items(shown, key = { it.key }) { item ->
                        GridCell(item, item.key in selection, actions, { open(item.key) }, { toggle(item.key) })
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(top = top)) {
                    items(shown, key = { it.key }) { item ->
                        FileRow(item, item.key in selection, { open(item.key) }, { toggle(item.key) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    selection: Set<String>,
    shown: List<MediaItem>,
    actions: MediaActions,
    onSelection: (Set<String>) -> Unit,
) {
    val canSave = remember(selection) { actions.canSave(selection) }
    TatrakTopBar(
        title = selection.size.toString(),
        onBack = { onSelection(emptySet()) },
        navigationIcon = R.drawable.ic_close_24dp,
        menu = listOf(MenuEntry(stringResource(R.string.select_all)) { onSelection(selection + shown.map { it.key }) }),
    ) {
        IconButton(onClick = {
            actions.share(selection)
            onSelection(emptySet())
        }) {
            Icon(painterResource(R.drawable.ic_share_24dp), stringResource(R.string.share))
        }
        if (canSave) {
            IconButton(onClick = {
                actions.save(selection)
                onSelection(emptySet())
            }) {
                Icon(painterResource(R.drawable.ic_save_24dp), stringResource(R.string.save))
            }
        }
        IconButton(onClick = {
            actions.delete(selection)
        }) {
            Icon(painterResource(R.drawable.ic_delete_24dp), stringResource(R.string.delete))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    item: MediaItem,
    selected: Boolean,
    actions: MediaActions,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val px = with(LocalDensity.current) { GRID_THUMBNAIL_DP.dp.toPx().roundToInt() }
    val bitmap by
        produceState<Bitmap?>(null, item.key, item.thumbnail) {
            value = if (item.thumbnail) actions.thumbnail(item.key, px) else null
        }
    Box(
        Modifier.aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                remember(image) { image.asImageBitmap() },
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(if (selected) 10.dp else 0.dp),
            )
        } else {
            Icon(
                painterResource(item.icon),
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(40.dp),
            )
        }
        if (selected) {
            Check(Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(item: MediaItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier.size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(item.icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            if (selected) Check(Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(item.details, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
    HorizontalDivider(Modifier.padding(start = 78.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Check(modifier: Modifier) {
    Icon(
        painterResource(R.drawable.ic_check_circle_24dp),
        null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface).size(22.dp),
    )
}

const val GRID_COLUMNS = 3
private const val GRID_THUMBNAIL_DP = 140
