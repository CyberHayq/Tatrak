package am.cyberhayq.chat.ui.newchat

import am.cyberhayq.chat.ui.chatlist.inErrorColor
import am.cyberhayq.chat.ui.common.AvatarRow
import am.cyberhayq.chat.ui.common.EmptyText
import am.cyberhayq.chat.ui.common.TatrakScaffold
import am.cyberhayq.chat.ui.common.TatrakTopBar
import am.cyberhayq.chat.ui.common.MenuEntry
import am.cyberhayq.chat.ui.common.SectionTitle
import am.cyberhayq.chat.ui.common.TopBarSearch
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/** The former speed-dial entries, as Telegram's rows at the top of "New message". */
enum class NewChatRow(@DrawableRes val icon: Int, @StringRes val label: Int) {
    NEW_GROUP(R.drawable.ic_group_24dp, R.string.create_private_group_chat),
    NEW_CHANNEL(R.drawable.ic_public_24dp, R.string.create_public_channel),
    ADD_CONTACT(R.drawable.ic_person_24dp, R.string.add_contact),
    JOIN(R.drawable.ic_login_24dp, R.string.join_public_channel),
    DISCOVER(R.drawable.ic_travel_explore_24dp, R.string.discover_channels),
}

/** The former long-press context menus of the contact and bookmark lists. */
enum class NewChatMenu(@StringRes val label: Int) {
    DETAILS(R.string.view_contact_details),
    CALL(R.string.audio_call),
    QR(R.string.show_qr_code),
    BLOCK(R.string.block_contact),
    UNBLOCK(R.string.unblock_contact),
    DELETE_CONTACT(R.string.delete_contact),
    SHARE(R.string.share_uri_with),
    DELETE_BOOKMARK(R.string.delete_bookmark),
    DELETE_AND_CLOSE(R.string.delete_and_close),
}

@Immutable
data class NewChatEntry(
    /** Account uuid and address; stable across rebuilds. */
    val key: String,
    val name: String,
    /** "online", or the address with look-alike characters marked. */
    val subtitle: AnnotatedString,
    /** "Blocked", roster groups and presence, as the classic row's tags; drawn before [subtitle]. */
    val tags: List<String> = emptyList(),
    val online: Boolean = false,
    val avatar: Bitmap? = null,
    val menu: List<NewChatMenu> = emptyList(),
)

@Immutable
data class NewChatState(
    val rows: List<NewChatRow> = NewChatRow.entries,
    val contacts: List<NewChatEntry> = emptyList(),
    val groups: List<NewChatEntry> = emptyList(),
    val hideOffline: Boolean = false,
    /** Set once the first lists arrived, so "nothing found" doesn't flash at start. */
    val loaded: Boolean = false,
)

interface NewChatActions {
    fun back()

    fun search(needle: String)

    /** The keyboard's search key: opens the only match, like the classic screen. */
    fun searchDone()

    fun row(row: NewChatRow)

    fun openContact(key: String)

    fun openGroup(key: String)

    fun contactMenu(key: String, item: NewChatMenu)

    fun groupMenu(key: String, item: NewChatMenu)

    fun toggleHideOffline()
}

/** Telegram's "New message": search, create / join rows, then contacts and groups. */
@Composable
fun NewChatScreen(
    state: NewChatState,
    actions: NewChatActions,
    modifier: Modifier = Modifier,
    initialSearch: String? = null,
    contactsOnly: Boolean = false,
    searchRequest: Pair<Int, String?>? = null,
) {
    var searching by rememberSaveable { mutableStateOf(initialSearch != null) }
    var needle by rememberSaveable { mutableStateOf(initialSearch.orEmpty()) }
    LaunchedEffect(actions) { actions.search(needle) }
    // the host's legacy search entry points: invite JID, start_searching, hardware Search key
    LaunchedEffect(searchRequest) {
        if (searchRequest != null) {
            searching = true
            searchRequest.second?.let { needle = it; actions.search(it) }
        }
    }
    val closeSearch = {
        searching = false
        needle = ""
        actions.search("")
    }
    BackHandler(enabled = searching) { closeSearch() }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        TatrakScaffold(
            topBar = {
                TatrakTopBar(
                    title = stringResource(if (contactsOnly) R.string.contacts else R.string.tatrak_new_chat_title),
                    onBack = {
                        if (!searching) {
                            actions.back()
                        } else {
                            closeSearch()
                        }
                    },
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
                                onDone = actions::searchDone,
                                focusRequest = searchRequest?.first,
                            ),
                    menu =
                        listOf(
                            MenuEntry(stringResource(R.string.hide_offline), state.hideOffline, onClick = actions::toggleHideOffline)
                        ),
                ) {
                    if (!searching) {
                        IconButton(onClick = { searching = true }) {
                            Icon(painterResource(R.drawable.ic_search_24dp), stringResource(R.string.search))
                        }
                    }
                }
            },
            modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        ) { top ->
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().imePadding(), contentPadding = PaddingValues(top = top)) {
                if (needle.isEmpty()) {
                    items(state.rows.filter { !contactsOnly || it == NewChatRow.ADD_CONTACT }, key = { "row:" + it.name }) { row ->
                        ActionRow(row) { actions.row(row) }
                    }
                }
                if (state.contacts.isNotEmpty()) {
                    item(key = "contacts") { SectionTitle(stringResource(R.string.contacts)) }
                    items(state.contacts, key = { "c:" + it.key }) { entry ->
                        EntryRow(entry, { actions.openContact(entry.key) }, contactsOnly) { actions.contactMenu(entry.key, it) }
                    }
                }
                if (!contactsOnly && state.groups.isNotEmpty()) {
                    item(key = "groups") { SectionTitle(stringResource(R.string.group_chats)) }
                    items(state.groups, key = { "g:" + it.key }) { entry ->
                        EntryRow(entry, { actions.openGroup(entry.key) }, false) { actions.groupMenu(entry.key, it) }
                    }
                }
                if (state.loaded && needle.isNotEmpty() && state.contacts.isEmpty() && (contactsOnly || state.groups.isEmpty())) {
                    item(key = "empty") { EmptyText(stringResource(R.string.tatrak_new_chat_nothing_found)) }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(row: NewChatRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            painterResource(row.icon),
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(32.dp))
        Text(stringResource(row.label), fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EntryRow(entry: NewChatEntry, onClick: () -> Unit, contactsOnly: Boolean, onMenu: (NewChatMenu) -> Unit) {
    val labels = entry.menu.map { stringResource(it.label) }
    AvatarRow(
        name = entry.name,
        // the participant rows' look: tags joined by " · " in the subtitle line
        subtitle = entry.subtitle.inErrorColor().let { address -> remember(entry.tags, address) { if (entry.tags.isEmpty()) address else AnnotatedString(entry.tags.joinToString(" · ", postfix = " · ")) + address } },
        avatar = entry.avatar,
        onClick = onClick,
        subtitleColor =
            if (entry.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        menu = entry.menu.mapIndexed { i, item -> MenuEntry(labels[i]) { onMenu(item) } },
        trailing = if (contactsOnly && NewChatMenu.CALL in entry.menu) {{
            IconButton(onClick = { onMenu(NewChatMenu.CALL) }) {
                Icon(painterResource(R.drawable.ic_call_24dp), stringResource(R.string.audio_call))
            }
        }} else null,
    )
}
