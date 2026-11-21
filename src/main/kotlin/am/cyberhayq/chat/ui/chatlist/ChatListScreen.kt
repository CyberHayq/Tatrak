package am.cyberhayq.chat.ui.chatlist

import am.cyberhayq.chat.update.UpdateNotice
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.EaseOutQuint
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import am.cyberhayq.chat.ui.common.SectionTitle
import am.cyberhayq.chat.ui.common.deleteChatText
import am.cyberhayq.chat.ui.common.deleteChatTitle
import am.cyberhayq.chat.ui.search.SearchResultRow
import am.cyberhayq.chat.ui.search.SearchState
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.LocalTatrakStyle
import am.cyberhayq.chat.ui.theme.BarBlur
import am.cyberhayq.chat.ui.theme.LocalBarBlur
import am.cyberhayq.chat.ui.common.BarSearchField
import am.cyberhayq.chat.ui.common.TopBarSearch
import am.cyberhayq.chat.ui.theme.LocalFloatingBars
import am.cyberhayq.chat.ui.theme.backdropSource
import am.cyberhayq.chat.ui.theme.fadeUnderBars
import am.cyberhayq.chat.ui.theme.rememberBarBackdrop
import androidx.compose.animation.AnimatedVisibility
import am.cyberhayq.chat.ui.theme.StatusBarIcons
import am.cyberhayq.chat.ui.theme.contentOn
import am.cyberhayq.chat.ui.theme.fabFill
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import eu.siacs.conversations.R
import eu.siacs.conversations.utils.FtsUtils
import eu.siacs.conversations.utils.TimeFrameUtils
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

interface ChatListActions {
    fun openChat(uuid: String)

    fun newChat()

    fun openContacts()

    fun manageAccounts()

    fun scanQrCode()

    /** The classic QR menu's "Invite to server" (shown when [ChatListScreen]'s canInvite is set). */
    fun inviteToServer() {}

    fun openSettings()

    /** Drawer button: light <-> dark (a fixed choice, not "automatic"). */
    fun toggleNightMode() {}

    /** The call list ([eu.siacs.conversations.ui.CallsActivity]). */
    fun openCalls() {}

    fun setArchived(uuids: List<String>, archived: Boolean)

    fun setPinned(uuids: List<String>, pinned: Boolean)

    /** [till] null unmutes, [Instant.MAX] mutes until further notice. */
    fun setMuted(uuids: List<String>, till: Instant?)

    fun markRead(uuids: List<String>)

    /** Conversations' "end conversation": closes the chat and leaves group chats. */
    fun delete(uuids: List<String>)

    /**
     * Full-text search over the messages of all chats (debounced by the screen); blank stops it.
     * Results come back through the `messages` parameter of [ChatListScreen].
     */
    fun searchMessages(text: String) {}

    /** A found message: opens its chat scrolled to it. */
    fun openMessage(key: String) {}

    /** Long press on a chat's avatar: Telegram's preview, which leaves the chat unread. */
    fun previewChat(uuid: String) {}
}

/** Built-in folders shown as tabs, like Telegram's default filters. */
enum class ChatFolder(val label: Int) {
    ALL(R.string.tatrak_tab_all),
    PERSONAL(R.string.tatrak_tab_personal),
    GROUPS(R.string.tatrak_tab_groups),
    UNREAD(R.string.tatrak_tab_unread);

    fun matches(chat: ChatListItem): Boolean =
        when (this) {
            ALL -> true
            PERSONAL -> !chat.isGroup
            GROUPS -> chat.isGroup
            UNREAD -> chat.unreadCount > 0
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    state: ChatListState,
    actions: ChatListActions,
    initialSelection: List<String> = emptyList(),
    initialArchiveOpen: Boolean = false,
    messages: SearchState = SearchState(),
    initialQuery: String? = null,
    /** An enabled account's server can create invites (AccountPickerDialog.EasyInvite). */
    canInvite: Boolean = false,
    /** A chat archived from its own menu: archived here like the selection's archive, with Undo. */
    archiveRequest: String? = null,
    onArchiveRequestDone: () -> Unit = {},
) {
    val drawer = rememberDrawerController()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var searching by rememberSaveable { mutableStateOf(initialQuery != null) }
    var query by rememberSaveable { mutableStateOf(initialQuery.orEmpty()) }
    var showArchive by rememberSaveable { mutableStateOf(initialArchiveOpen) }
    var selection by rememberSaveable { mutableStateOf(initialSelection) }
    var muteDialog by remember { mutableStateOf<List<String>?>(null) }
    var deleteDialog by remember { mutableStateOf<List<String>?>(null) }
    val pagerState = rememberPagerState { ChatFolder.entries.size }
    val chatColors = TatrakTheme.chatColors
    val context = LocalContext.current
    val slate = LocalTatrakStyle.current == TatrakStyle.SLATE_SEA
    // floating bars replace the Telegram and Slate bars (Slate's dock included) when on, in any style
    val floating = LocalFloatingBars.current
    var scrollToTop by remember { mutableIntStateOf(0) }

    StatusBarIcons(dark = chatColors.topBar.luminance() > 0.5f)

    val all = remember(state.chats, state.archived) { state.chats + state.archived }
    val byUuid = remember(all) { all.associateBy { it.uuid } }
    // chats can disappear while selected (archived elsewhere, deleted); drop them silently
    val selected = remember(selection, byUuid) { selection.filter { it in byUuid } }
    val selectionMode = selected.isNotEmpty()

    fun clearSelection() {
        selection = emptyList()
    }

    fun toggle(uuid: String) {
        selection = if (uuid in selection) selection - uuid else selection + uuid
    }

    fun archive(uuids: List<String>, archived: Boolean) {
        actions.setArchived(uuids, archived)
        clearSelection()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (archived) {
                val done =
                    if (uuids.size == 1) {
                        context.getString(R.string.tatrak_archived_one)
                    } else {
                        context.getString(R.string.tatrak_archived_many, uuids.size)
                    }
                // Telegram's archive hint: once, and only while the archive row is hidden above the list
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val hint = prefs.getBoolean(ARCHIVE_HIDDEN_KEY, true) && !prefs.getBoolean(ARCHIVE_HINT_SHOWN_KEY, false)
                if (hint) prefs.edit().putBoolean(ARCHIVE_HINT_SHOWN_KEY, true).apply()
                val message = if (hint) done + "\n" + context.getString(R.string.tatrak_archive_hint) else done
                // with an action M3 defaults to Indefinite, which never goes away
                val result =
                    snackbar.showSnackbar(
                        message,
                        context.getString(R.string.tatrak_undo),
                        duration = SnackbarDuration.Long,
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    actions.setArchived(uuids, false)
                }
            } else {
                snackbar.showSnackbar(context.getString(R.string.tatrak_unarchived))
            }
        }
    }

    LaunchedEffect(archiveRequest) {
        archiveRequest ?: return@LaunchedEffect
        archive(listOf(archiveRequest), true)
        onArchiveRequestDone()
    }

    // derived: reading the drag progress here would recompose the whole screen on every frame
    val drawerOpen by remember(drawer) { derivedStateOf { drawer.isOpen } }
    BackHandler(enabled = drawerOpen) { drawer.close() }
    BackHandler(enabled = selectionMode) { clearSelection() }
    BackHandler(enabled = searching && !selectionMode) {
        searching = false
        query = ""
    }
    BackHandler(enabled = showArchive && !searching && !selectionMode) { showArchive = false }

    // messages are searched once typing pauses, like Telegram; closing the search stops it
    LaunchedEffect(searching, query) {
        val text = if (searching) query.trim() else ""
        if (text.isNotEmpty()) delay(SEARCH_DEBOUNCE_MS)
        actions.searchMessages(text)
    }

    // created once: new lambdas on every snapshot would make every visible row recompose
    val currentSelectionMode by rememberUpdatedState(selectionMode)
    val currentActions by rememberUpdatedState(actions)
    val rowCallbacks = remember {
        RowCallbacks(
            onClick = { uuid -> if (currentSelectionMode) toggle(uuid) else currentActions.openChat(uuid) },
            onLongClick = { uuid -> toggle(uuid) },
            onAvatarLongClick = { uuid -> currentActions.previewChat(uuid) },
        )
    }
    val openArchive = remember { { showArchive = true } }
    val archiveHidden = rememberArchiveHidden()
    val currentArchiveHidden by rememberUpdatedState(archiveHidden)
    val toggleArchiveHidden = remember { { setArchiveHidden(context, !currentArchiveHidden) } }

    // left-to-right on the All tab opens the menu; other tabs keep it for the pager (previous tab)
    DrawerHost(
        controller = drawer,
        canOpenByDrag = { state.loaded && !searching && !showArchive && !selectionMode && pagerState.currentPage == 0 },
        drawerContent = {
            ChatListDrawer(
                state.account,
                actions,
                onArchive = if (state.archived.isEmpty()) null else openArchive,
                canInvite = canInvite,
            ) { drawer.close() }
        },
    ) {
        // into the archive and back: the whole screen moves
        AnimatedContent(
            targetState = showArchive,
            transitionSpec = { archiveTransition(targetState) },
            label = "archive",
        ) { inArchive ->
            val dock = dockVisible(searching, inArchive, selectionMode)
            // one per screen of the archive transition: both lists are drawn while it runs
            val backdrop = rememberBarBackdrop()
            val bars = @Composable {
                when {
                    selectionMode ->
                        SelectionTopBar(
                            selected = selected.mapNotNull(byUuid::get),
                            onClose = ::clearSelection,
                            onPin = { pinned ->
                                actions.setPinned(selected, pinned)
                                clearSelection()
                            },
                            onMute = { muted ->
                                if (muted) {
                                    muteDialog = selected
                                } else {
                                    actions.setMuted(selected, null)
                                    clearSelection()
                                }
                            },
                            onArchive = { archived -> archive(selected, archived) },
                            onMarkRead = {
                                actions.markRead(selected)
                                clearSelection()
                            },
                            onDelete = { deleteDialog = selected },
                        )
                    searching ->
                        SearchTopBar(
                            query = query,
                            onQueryChange = { query = it },
                            onClose = {
                                searching = false
                                query = ""
                            },
                        )
                    inArchive ->
                        PlainTopBar(
                            title = stringResource(R.string.tatrak_archive),
                            navigationIcon = R.drawable.ic_arrow_back_24dp,
                            navigationLabel = stringResource(R.string.tatrak_close_search),
                            onNavigation = { showArchive = false },
                            onSearch = { searching = true },
                        )
                    else ->
                        PlainTopBar(
                            title =
                                stringResource(
                                    if (state.connecting) R.string.tatrak_connecting else R.string.app_name
                                ),
                            navigationIcon = R.drawable.tatrak_ic_menu_24dp,
                            navigationLabel = stringResource(R.string.tatrak_open_menu),
                            onNavigation = { drawer.open() },
                            onSearch = { searching = true },
                            // floating: the search button sits in the bottom bar
                            showSearch = !slate && !floating,
                        )
                }
            }
            val tabs: (@Composable () -> Unit)? =
                if (searching || inArchive) null
                else {
                    {
                        Column {
                            FolderTabs(
                                chats = state.chats,
                                selectedIndex = pagerState.currentPage,
                                onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
                                // floating: clear of the tab pill's round ends
                                edgePadding = if (floating) 16.dp else 8.dp,
                            )
                            UpdateNotice()
                        }
                    }
                }
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                // Slate and Sea and floating bars: the bottom bar carries the navigation bar inset, and the snackbar sits above it
                snackbarHost = {
                    SnackbarHost(
                        snackbar,
                        if ((slate || floating) && dock) Modifier else Modifier.navigationBarsPadding(),
                    )
                },
                bottomBar = {
                    if (floating) {
                        FloatingBottomBar(dock, state.account, actions, onSearch = { searching = true }, onChats = { scrollToTop++ }, backdrop)
                    } else if (slate) {
                        ChatListBottomBar(
                            visible = dock,
                            actions = actions,
                            onSearch = { searching = true },
                            onChats = { scrollToTop++ },
                        )
                    }
                },
                topBar = {
                    if (floating) {
                        FloatingTopBar(backdrop, tabs, bars)
                    } else {
                        Column(Modifier.background(chatColors.topBar)) {
                            bars()
                            tabs?.invoke()
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                },
                floatingActionButton = {
                    if (floating) {
                        // above the bottom bar, following it in and out
                        AnimatedVisibility(dock, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                            // clear of a side navigation bar or cutout, as the bottom pill is; Telegram 13's 48 dp button, 20 dp from the side
                            NewChatButton(
                                actions::newChat,
                                Modifier.windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.End))
                                    .padding(end = 4.dp)
                                    .size(48.dp),
                            )
                        }
                    } else if (!slate && !searching && !inArchive && !selectionMode) {
                        NewChatButton(actions::newChat, Modifier.navigationBarsPadding())
                    }
                },
            ) { padding ->
                // floating: the content runs under the bars, which the lists pad for (FAB included) and fade into
                val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val pad =
                    if (!floating) null
                    else PaddingValues(
                        top = padding.calculateTopPadding(),
                        bottom = maxOf(padding.calculateBottomPadding(), nav) + if (dock) FabClearance else 0.dp,
                    )
                val listModifier = if (floating) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(padding)
                Box(
                    // with blur the bars draw the content under them themselves (unfaded: the source is inside the fade)
                    if (floating) Modifier.fillMaxSize().fadeUnderBars(padding).backdropSource(backdrop)
                    else Modifier
                ) {
                    when {
                        !state.loaded -> Box(listModifier)
                        searching -> {
                            val found =
                                remember(all, query) {
                                    if (query.isBlank()) all else all.filter { it.title.contains(query.trim(), true) }
                                }
                            if (query.isBlank()) {
                                ChatList(found, null, rowCallbacks, selected, listModifier, EmptyKind.NOTHING_FOUND, dock = if (slate) false else null, pad = pad)
                            } else {
                                SearchResults(found, messages, query, rowCallbacks, selected, actions::openMessage, listModifier, pad)
                            }
                        }
                        inArchive -> {
                            // Telegram's swipe back; unlike the menu it does not follow the finger
                            val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
                            val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
                            ChatList(
                                state.archived,
                                null,
                                rowCallbacks,
                                selected,
                                listModifier.claimHorizontalDrag(
                                    canClaim = { dx -> dx * sign > 0f && !selectionMode },
                                    onEnd = { velocity, dx ->
                                        if (drawerOpensOnRelease(dx * sign / widthPx, velocity * sign, false)) showArchive = false
                                    },
                                ),
                                EmptyKind.ARCHIVE,
                                dock = if (slate) false else null,
                                pad = pad,
                            )
                        }
                        else ->
                            HorizontalPager(
                                state = pagerState,
                                // only the page on screen is composed; the neighbour doubled every list update
                                beyondViewportPageCount = 0,
                                key = { ChatFolder.entries[it] },
                                modifier = listModifier,
                            ) { page ->
                                val folder = ChatFolder.entries[page]
                                val chats = remember(state.chats, folder) { state.chats.filter(folder::matches) }
                                val archive =
                                    state.archived.takeIf { folder == ChatFolder.ALL && it.isNotEmpty() }
                                ChatList(
                                    chats = chats,
                                    archive = archive?.let { ArchiveEntry(it, archiveHidden, openArchive, toggleArchiveHidden) },
                                    callbacks = rowCallbacks,
                                    selected = selected,
                                    modifier = Modifier.fillMaxSize(),
                                    empty = EmptyKind.NO_CHATS,
                                    scrollToTop = scrollToTop,
                                    dock = if (slate) dockVisible(searching, inArchive, selectionMode) else null,
                                    pad = pad,
                                )
                            }
                    }
                }
            }
        }
    }

    muteDialog?.let { uuids ->
        MuteDialog(
            onPick = { till ->
                actions.setMuted(uuids, till)
                muteDialog = null
                clearSelection()
            },
            onDismiss = { muteDialog = null },
        )
    }
    deleteDialog?.let { uuids ->
        val one = uuids.singleOrNull()?.let(byUuid::get)
        AlertDialog(
            onDismissRequest = { deleteDialog = null },
            title = {
                Text(
                    if (one != null) {
                        deleteChatTitle(context, one.title, one.isGroup)
                    } else {
                        stringResource(R.string.tatrak_delete_chats_title, uuids.size)
                    }
                )
            },
            text = {
                Text(
                    if (one != null) {
                        deleteChatText(context, one.title, one.isGroup)
                    } else {
                        stringResource(R.string.tatrak_delete_chats_explained)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.delete(uuids)
                        deleteDialog = null
                        clearSelection()
                    }
                ) {
                    Text(
                        stringResource(if (one?.isGroup == true) R.string.leave else R.string.tatrak_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialog = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Stable
private class RowCallbacks(
    val onClick: (String) -> Unit,
    val onLongClick: (String) -> Unit,
    val onAvatarLongClick: (String) -> Unit,
)

// a data class with the remembered opener, so an unchanged archive lets ChatList skip
private data class ArchiveEntry(
    val chats: List<ChatListItem>,
    val hidden: Boolean,
    val open: () -> Unit,
    val toggleHidden: () -> Unit,
)

private enum class EmptyKind {
    NO_CHATS,
    ARCHIVE,
    NOTHING_FOUND,
}

@Composable
private fun ChatList(
    chats: List<ChatListItem>,
    archive: ArchiveEntry?,
    callbacks: RowCallbacks,
    selected: List<String>,
    modifier: Modifier,
    empty: EmptyKind,
    scrollToTop: Int = 0,
    /** Slate and Sea: whether the dock is shown (the Scaffold already reserves its height); null = the Telegram padding. */
    dock: Boolean? = null,
    /** Floating bars: the list runs under them and pads for them; replaces [dock]. */
    pad: PaddingValues? = null,
) {
    if (chats.isEmpty() && archive == null) {
        EmptyChatList(empty, if (pad == null) modifier else modifier.padding(pad))
        return
    }
    val formatter = rememberChatTimeFormatter()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // hidden: the row is item 0 but the list starts scrolled past it, so a pull brings it into view by
    // plain scrolling, covered by the pull foreground until a release shows it (ArchiveReveal)
    // with no chat outside the archive the row stays in sight, or the screen would be blank
    val hiddenArchive = archive != null && archive.hidden && chats.isNotEmpty()
    val blurred = pad != null && LocalBarBlur.current != BarBlur.OFF
    val rowPx = with(LocalDensity.current) { RowHeight.roundToPx() }
    val listState = rememberLazyListState(initialFirstVisibleItemScrollOffset = if (hiddenArchive) rowPx else 0)
    val reveal = remember(listState) { ArchiveReveal(listState) }
    val scope = rememberCoroutineScope()
    // Compose keeps the first visible chat in place, so a chat moving to the top would land above the
    // screen; at the top of the list it is shown instead, as in Telegram
    val firstChat = chats.firstOrNull()?.uuid
    val shownFirst = remember { arrayOfNulls<String>(1) }
    SideEffect {
        val top = if (hiddenArchive && !reveal.revealed) 1 else 0
        if (shownFirst[0] != null && shownFirst[0] != firstChat &&
            listState.firstVisibleItemIndex == top && listState.firstVisibleItemScrollOffset == 0) {
            listState.requestScrollToItem(top)
        }
        shownFirst[0] = firstChat
    }
    var seen by remember { mutableIntStateOf(scrollToTop) }
    LaunchedEffect(scrollToTop) {
        if (scrollToTop != seen) {
            seen = scrollToTop
            listState.animateScrollToItem(if (hiddenArchive) 1 else 0)
        }
    }
    LaunchedEffect(listState, hiddenArchive) {
        if (!hiddenArchive) return@LaunchedEffect
        // a revealed row is hidden again once it scrolls out of view (Telegram)
        launch { snapshotFlow { listState.firstVisibleItemIndex > 0 }.filter { it }.collect { reveal.revealed = false } }
        // an unrevealed row never stays in view at rest (a release is handled by ArchiveReveal.onPreFling);
        // a list that cannot scroll further would retry this animation forever
        snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect {
            if (!reveal.revealed && listState.firstVisibleItemIndex == 0 && listState.canScrollForward) {
                listState.animateScrollToItem(1)
            }
        }
    }
    val archiveLabel = stringResource(R.string.tatrak_archive)
    val contentBottom = when (dock) { null -> bottom + 88.dp; true -> 0.dp; false -> bottom }
    val contentPadding = pad ?: PaddingValues(bottom = contentBottom)
    BoxWithConstraints(
        modifier
            .then(if (hiddenArchive) Modifier.archivePull(reveal) else Modifier)
            // TalkBack: the hidden row is not on screen, so the archive is also an action of the list
            .then(
                if (archive == null) Modifier
                else Modifier.semantics { customActions = listOf(CustomAccessibilityAction(archiveLabel) { archive.open(); true }) }
            )
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            if (archive != null) {
                item(key = "archive", contentType = "archive") {
                    // blur under bars: the hidden row waits under the top bar, which would show it blurred; it shows once pulled
                    val hide = if (hiddenArchive && blurred) Modifier.graphicsLayer { alpha = if (reveal.revealed || reveal.pullProgress > 0f) 1f else 0f } else Modifier
                    Box(Modifier.animateItem().then(hide)) {
                        ArchiveRow(
                            archive.chats,
                            archive.hidden,
                            archive.open,
                            {
                                archive.toggleHidden()
                                // pinned -> hidden: slide the row away, as Telegram does
                                if (!archive.hidden) scope.launch { listState.animateScrollToItem(1) }
                            },
                        )
                        if (hiddenArchive) ArchivePullForeground(reveal, Modifier.matchParentSize())
                    }
                }
            }
            items(chats, key = { it.uuid }, contentType = { "chat" }) { chat ->
                ChatRow(
                    chat = chat,
                    time = formatter.format(chat.timestamp),
                    selected = chat.uuid in selected,
                    onClick = { callbacks.onClick(chat.uuid) },
                    onLongClick = { callbacks.onLongClick(chat.uuid) },
                    modifier = Modifier.animateItem(),
                    // while selecting, the avatar is part of the row: its long press toggles, no preview action
                    onAvatarLongClick = if (selected.isEmpty()) { { callbacks.onAvatarLongClick(chat.uuid) } } else null,
                )
            }
            // Telegram's end gap: a list shorter than the screen must still scroll the hidden row out of view
            if (hiddenArchive) {
                item(key = "gap", contentType = "gap") {
                    // in pixels: rows are measured in whole pixels, a dp sum comes up short at some densities
                    val gap = constraints.maxHeight - rowPx * chats.size -
                        with(LocalDensity.current) { (contentPadding.calculateTopPadding() + contentPadding.calculateBottomPadding()).roundToPx() }
                    Spacer(Modifier.height(with(LocalDensity.current) { gap.coerceAtLeast(0).toDp() }))
                }
            }
        }
    }
}

/**
 * Not a chat's side slide: the archive drops in from a little above and fades in as the list fades out
 * moving down, continuing the pull that opens it; going back reverses it (440 ms). A full-height drop
 * would bring the archive's empty lower part in first.
 */
private fun archiveTransition(entering: Boolean): ContentTransform {
    // the leaving screen is gone before the coming one shows and moves, so the two lists never overlap
    val gone = 90
    val dir = if (entering) 1 else -1
    val out = slideOutVertically(tween(350, easing = EaseOutQuint)) { it * dir / 8 } + fadeOut(tween(gone))
    val into = slideInVertically(tween(350, gone, EaseOutQuint)) { -it * dir / 8 } + fadeIn(tween(220, gone))
    return into togetherWith out
}

/**
 * Telegram's chat list search: matching chats on top, then the messages of all chats that contain
 * the text. Message results of an older text stay until the new ones arrive.
 */
@Composable
private fun SearchResults(
    chats: List<ChatListItem>,
    messages: SearchState,
    query: String,
    callbacks: RowCallbacks,
    selected: List<String>,
    onMessage: (String) -> Unit,
    modifier: Modifier,
    pad: PaddingValues? = null,
) {
    if (chats.isEmpty() && messages.results.isEmpty()) {
        // nothing matched the name; say so only once the message search has answered too
        if (messages.searched && messages.terms == FtsUtils.parse(query.trim())) {
            EmptyChatList(EmptyKind.NOTHING_FOUND, if (pad == null) modifier else modifier.padding(pad))
        } else {
            Box(modifier)
        }
        return
    }
    val formatter = rememberChatTimeFormatter()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(modifier = modifier, contentPadding = pad ?: PaddingValues(bottom = bottom + 16.dp)) {
        if (chats.isNotEmpty() && messages.results.isNotEmpty()) {
            item(key = "chats-title", contentType = "title") {
                SectionTitle(stringResource(R.string.tatrak_search_section_chats))
            }
        }
        items(chats, key = { it.uuid }, contentType = { "chat" }) { chat ->
            ChatRow(
                chat = chat,
                time = formatter.format(chat.timestamp),
                selected = chat.uuid in selected,
                onClick = { callbacks.onClick(chat.uuid) },
                onLongClick = { callbacks.onLongClick(chat.uuid) },
                onAvatarLongClick = if (selected.isEmpty()) { { callbacks.onAvatarLongClick(chat.uuid) } } else null,
            )
        }
        if (messages.results.isNotEmpty()) {
            item(key = "messages-title", contentType = "title") {
                SectionTitle(stringResource(R.string.tatrak_search_section_messages))
            }
            // keyed apart from the chats: a message uuid could equal a chat uuid only by accident
            items(messages.results, key = { "m:" + it.key }, contentType = { "message" }) { result ->
                SearchResultRow(result, messages.terms, formatter.format(result.timestamp), onMessage, null)
            }
        }
    }
}

private const val SEARCH_DEBOUNCE_MS = 300L

/** Floating bars: room under the last row for the FAB above the bottom bar (48 dp and the Scaffold's 16 dp spacing). */
private val FabClearance = 64.dp

@Composable
private fun NewChatButton(onClick: () -> Unit, modifier: Modifier) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = fabFill(MaterialTheme.colorScheme.primary),
        contentColor = Color.White,
        modifier = modifier,
    ) {
        Icon(
            painterResource(R.drawable.ic_edit_24dp),
            contentDescription = stringResource(R.string.tatrak_new_chat),
        )
    }
}

@Composable
private fun FolderTabs(chats: List<ChatListItem>, selectedIndex: Int, onSelect: (Int) -> Unit, edgePadding: Dp) {
    val chatColors = TatrakTheme.chatColors
    val accent = MaterialTheme.colorScheme.primary
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        containerColor = chatColors.topBar,
        contentColor = accent,
        edgePadding = edgePadding,
        divider = {},
        indicator = {
            Box(
                Modifier.tabIndicatorOffset(selectedIndex, matchContentSize = false)
                    .height(3.dp)
                    .background(accent, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            )
        },
    ) {
        ChatFolder.entries.forEachIndexed { index, folder ->
            val unread = remember(chats, folder) { chats.count { folder.matches(it) && it.unreadCount > 0 && !it.isMuted } }
            val active = index == selectedIndex
            Tab(
                selected = active,
                onClick = { onSelect(index) },
                selectedContentColor = accent,
                unselectedContentColor = chatColors.onTopBarMuted,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(folder.label), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (unread > 0) {
                        UnreadBadge(
                            unread,
                            if (active) accent else chatColors.unreadBadgeMuted,
                            Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlainTopBar(
    title: String,
    navigationIcon: Int,
    navigationLabel: String,
    onNavigation: () -> Unit,
    onSearch: () -> Unit,
    showSearch: Boolean = true,
) {
    TatrakAppBar(
        colors = topBarColors(),
        navigationIcon = {
            IconButton(onClick = onNavigation) {
                Icon(painterResource(navigationIcon), contentDescription = navigationLabel)
            }
        },
        title = { Text(title, fontWeight = FontWeight.Medium, fontSize = barTitleSize(20.sp)) },
        actions = {
            if (showSearch) {
                IconButton(onClick = onSearch) {
                    Icon(
                        painterResource(R.drawable.ic_search_24dp),
                        contentDescription = stringResource(R.string.search),
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val onBar = TatrakTheme.chatColors.onTopBar
    TatrakAppBar(
        colors = topBarColors(),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back_24dp),
                    contentDescription = stringResource(R.string.tatrak_close_search),
                )
            }
        },
        title = {
            // the floating header's 46 dp pill: the M3 field is 56 dp tall, the bars' plain field fits
            if (LocalHeaderPills.current != null) {
                BarSearchField(TopBarSearch(query, stringResource(R.string.search), onQueryChange))
            } else {
                val focusRequester = remember { FocusRequester() }
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = onBar,
                            unfocusedTextColor = onBar,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            focusedPlaceholderColor = TatrakTheme.chatColors.onTopBarMuted,
                            unfocusedPlaceholderColor = TatrakTheme.chatColors.onTopBarMuted,
                        ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selected: List<ChatListItem>,
    onClose: () -> Unit,
    onPin: (Boolean) -> Unit,
    onMute: (Boolean) -> Unit,
    onArchive: (Boolean) -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
) {
    val allPinned = selected.all { it.isPinned }
    val allMuted = selected.all { it.isMuted }
    val allArchived = selected.all { it.isArchived }
    var menu by remember { mutableStateOf(false) }
    TatrakAppBar(
        colors = topBarColors(),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(R.drawable.ic_close_24dp),
                    contentDescription = stringResource(R.string.tatrak_close_selection),
                )
            }
        },
        title = { Text(selected.size.toString(), fontWeight = FontWeight.Medium, fontSize = barTitleSize(20.sp)) },
        actions = {
            IconButton(onClick = { onPin(!allPinned) }) {
                Icon(
                    painterResource(R.drawable.tatrak_ic_pin_24dp),
                    contentDescription = stringResource(if (allPinned) R.string.tatrak_unpin else R.string.tatrak_pin),
                )
            }
            IconButton(onClick = { onMute(!allMuted) }) {
                Icon(
                    painterResource(
                        if (allMuted) R.drawable.ic_notifications_24dp else R.drawable.ic_notifications_off_24dp
                    ),
                    contentDescription = stringResource(if (allMuted) R.string.tatrak_unmute else R.string.tatrak_mute),
                )
            }
            IconButton(onClick = { onArchive(!allArchived) }) {
                Icon(
                    painterResource(if (allArchived) R.drawable.ic_unarchive_24dp else R.drawable.ic_archive_24dp),
                    contentDescription =
                        stringResource(if (allArchived) R.string.tatrak_unarchive else R.string.tatrak_archive),
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        painterResource(R.drawable.tatrak_ic_more_vert_24dp),
                        contentDescription = stringResource(R.string.tatrak_more),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mark_as_read)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_mark_chat_read_24dp), null) },
                        onClick = {
                            menu = false
                            onMarkRead()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tatrak_delete_chat), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_delete_24dp),
                                null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun topBarColors() =
    TatrakTheme.chatColors.let {
        TopAppBarDefaults.topAppBarColors(
            containerColor = it.topBar,
            titleContentColor = it.onTopBar,
            navigationIconContentColor = it.onTopBar,
            actionIconContentColor = it.onTopBar,
        )
    }

@Composable
private fun MuteDialog(onPick: (Instant) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val durations = integerArrayResource(R.array.mute_options_durations)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disable_notifications)) },
        text = {
            Column {
                durations.forEach { seconds ->
                    val label =
                        if (seconds == -1) {
                            stringResource(R.string.until_further_notice)
                        } else {
                            TimeFrameUtils.resolve(context, 1000L * seconds)
                        }
                    Text(
                        label,
                        fontSize = 16.sp,
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable {
                                    onPick(
                                        if (seconds == -1) {
                                            Instant.MAX
                                        } else {
                                            Instant.now().plus(Duration.ofSeconds(seconds.toLong()))
                                        }
                                    )
                                }
                                .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun EmptyChatList(kind: EmptyKind, modifier: Modifier) {
    val (title, hint) =
        when (kind) {
            EmptyKind.NO_CHATS -> R.string.tatrak_no_chats to R.string.tatrak_no_chats_hint
            EmptyKind.ARCHIVE -> R.string.tatrak_archive_empty to R.string.tatrak_archive_empty_hint
            EmptyKind.NOTHING_FOUND -> R.string.tatrak_nothing_found to null
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize().padding(32.dp),
    ) {
        Text(
            stringResource(title),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(hint),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun ChatListDrawer(
    account: AccountHeader?,
    actions: ChatListActions,
    onArchive: (() -> Unit)? = null,
    canInvite: Boolean = false,
    close: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val header = TatrakTheme.chatColors.drawerHeader
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val drawerWidth = drawerWidth(LocalConfiguration.current.screenWidthDp.dp)
    ModalDrawerSheet(modifier = Modifier.width(drawerWidth), drawerShape = RectangleShape, windowInsets = WindowInsets(0)) {
        Box(
            Modifier.fillMaxWidth()
                // the theme's own colour, darker towards the bottom right
                .background(Brush.linearGradient(listOf(header, lerp(header, Color.Black, 0.35f))))
                .statusBarsPadding()
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                ChatAvatar(account?.avatar, online = false, size = 56.dp)
                Spacer(Modifier.height(12.dp))
                Text(account?.name.orEmpty(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(account?.address.orEmpty(), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1)
            }
            // day / night in one tap, as in Telegram's drawer
            IconButton(onClick = actions::toggleNightMode, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Icon(
                    painterResource(if (dark) R.drawable.tatrak_ic_light_mode_24dp else R.drawable.ic_dark_mode_24dp),
                    contentDescription = stringResource(R.string.tatrak_drawer_toggle_theme),
                    tint = Color.White,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        DrawerItem(R.drawable.ic_contacts_24dp, stringResource(R.string.contacts)) {
            close()
            actions.openContacts()
        }
        if (onArchive != null) {
            DrawerItem(R.drawable.ic_archive_24dp, stringResource(R.string.tatrak_archive)) {
                close()
                onArchive()
            }
        }
        DrawerItem(R.drawable.ic_call_24dp, stringResource(R.string.tatrak_calls)) {
            close()
            actions.openCalls()
        }
        DrawerDivider()
        DrawerItem(R.drawable.ic_person_24dp, stringResource(R.string.action_accounts)) {
            close()
            actions.manageAccounts()
        }
        DrawerItem(R.drawable.ic_qr_code_scanner_24dp, stringResource(R.string.scan_qr_code)) {
            close()
            actions.scanQrCode()
        }
        if (canInvite) {
            DrawerItem(R.drawable.ic_join_24dp, stringResource(R.string.server_invite)) {
                close()
                actions.inviteToServer()
            }
        }
        DrawerDivider()
        DrawerItem(R.drawable.ic_settings_24dp, stringResource(R.string.action_settings)) {
            close()
            actions.openSettings()
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.app_name) + if (version.isEmpty()) "" else " $version",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).navigationBarsPadding().padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun DrawerItem(icon: Int, label: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        label = { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium) },
        selected = false,
        onClick = onClick,
        shape = RectangleShape,
        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
    )
}
