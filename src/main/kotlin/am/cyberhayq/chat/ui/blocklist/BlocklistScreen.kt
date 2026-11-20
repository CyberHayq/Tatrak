package am.cyberhayq.chat.ui.blocklist

import am.cyberhayq.chat.ui.common.AVATAR_SIZE
import am.cyberhayq.chat.ui.common.AvatarRow
import am.cyberhayq.chat.ui.common.EmptyText
import am.cyberhayq.chat.ui.common.TatrakTopBar
import am.cyberhayq.chat.ui.common.TopBarSearch
import am.cyberhayq.chat.ui.theme.fabFill
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import eu.siacs.conversations.R
import kotlin.math.roundToInt

@Immutable
data class BlockedItem(
    /** The blocked address. */
    val key: String,
    val name: String,
    /** The address with look-alike characters marked, as the classic row draws it. */
    val address: AnnotatedString,
)

@Immutable
data class BlocklistState(
    /** In the classic order, already filtered by the search. */
    val items: List<BlockedItem> = emptyList(),
    val loaded: Boolean = false,
)

interface BlocklistActions {
    fun back()

    fun search(needle: String)

    /** The classic FAB: the "Block XMPP address" dialog. */
    fun block()

    /** The classic long press: the unblock dialog for this address. */
    fun unblock(key: String)

    fun cachedAvatar(key: String, sizePx: Int): Bitmap?

    suspend fun avatar(key: String, sizePx: Int): Bitmap?
}

/** The block list of one account over BlocklistActivity: search in the top bar, the FAB blocks an address. */
@Composable
fun BlocklistScreen(state: BlocklistState, actions: BlocklistActions, initialSearch: String? = null) {
    var searching by rememberSaveable { mutableStateOf(initialSearch != null) }
    var needle by rememberSaveable { mutableStateOf(initialSearch.orEmpty()) }
    LaunchedEffect(actions) { actions.search(needle) }
    val closeSearch = {
        searching = false
        needle = ""
        actions.search("")
    }
    BackHandler(enabled = searching) { closeSearch() }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxSize()) {
                TatrakTopBar(
                    title = stringResource(R.string.title_activity_block_list),
                    onBack = { if (searching) closeSearch() else actions.back() },
                    search =
                        if (!searching) null
                        else
                            TopBarSearch(
                                needle = needle,
                                hint = stringResource(R.string.search_contacts),
                                onNeedle = {
                                    needle = it
                                    actions.search(it)
                                },
                            ),
                ) {
                    if (!searching) {
                        IconButton(onClick = { searching = true }) {
                            Icon(painterResource(R.drawable.ic_search_24dp), stringResource(R.string.search))
                        }
                    }
                }
                val avatarPx = with(LocalDensity.current) { AVATAR_SIZE.dp.toPx().roundToInt() }
                val unblock = stringResource(R.string.unblock)
                LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
                    // the last row stays clear of the FAB
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(state.items, key = { it.key }) { item ->
                        val avatar by
                            produceState(actions.cachedAvatar(item.key, avatarPx), item.key, avatarPx) {
                                value = actions.avatar(item.key, avatarPx)
                            }
                        AvatarRow(
                            name = item.name,
                            subtitle = item.address,
                            avatar = avatar,
                            onClick = { actions.unblock(item.key) },
                            onLongClick = { actions.unblock(item.key) },
                            longClickLabel = unblock,
                        )
                    }
                    if (state.loaded && state.items.isEmpty()) {
                        item(key = "empty") {
                            EmptyText(
                                stringResource(
                                    if (needle.isEmpty()) R.string.tatrak_blocklist_empty else R.string.tatrak_new_chat_nothing_found
                                )
                            )
                        }
                    }
                }
            }
            FloatingActionButton(
                onClick = actions::block,
                shape = CircleShape,
                containerColor = fabFill(MaterialTheme.colorScheme.primary),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().imePadding().padding(16.dp),
            ) {
                Icon(painterResource(R.drawable.tatrak_ic_block_24dp), stringResource(R.string.block_jabber_id))
            }
        }
    }
}
