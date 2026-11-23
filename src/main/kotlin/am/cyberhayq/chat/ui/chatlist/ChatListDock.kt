package am.cyberhayq.chat.ui.chatlist

import am.cyberhayq.chat.ui.theme.BarBackdrop
import am.cyberhayq.chat.ui.theme.BarBlur
import am.cyberhayq.chat.ui.theme.LocalChatColors
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.backdropBlur
import am.cyberhayq.chat.ui.theme.barAlpha
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import am.cyberhayq.chat.ui.theme.LocalFloatingBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/** The Slate and Sea bottom bar shows only while the list itself is on screen (not searching, in the archive or selecting). */
internal fun dockVisible(searching: Boolean, showArchive: Boolean, selectionMode: Boolean): Boolean =
    !searching && !showArchive && !selectionMode

/**
 * Slate and Sea bottom bar: the main tabs ([MainTabs]: a tap on the current one scrolls it to the top), and on Chats
 * ([onSearch] set) a floating search pill and compose button above them. The dock itself extends under the navigation bar.
 */
@Composable
internal fun ChatListBottomBar(
    visible: Boolean,
    selected: MainTab,
    onTab: (MainTab) -> Unit,
    onSearch: (() -> Unit)? = null,
    onNewChat: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        val bar = TatrakTheme.chatColors.topBar
        val sides = WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)
        Column {
            // Chats' own: it slides with the page while the tabs switch, the bar under it stays
            if (onSearch != null) Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(sides).padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            ) {
                val search = stringResource(R.string.tatrak_search_chats)
                Surface(
                    onClick = onSearch,
                    shape = RoundedCornerShape(24.dp),
                    // raised over the list (the plain surface equals the list's ground)
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 6.dp,
                    modifier = Modifier.weight(1f).height(48.dp).semantics { role = Role.Button; contentDescription = search },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Icon(painterResource(R.drawable.ic_search_24dp), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            search,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
                Surface(
                    onClick = onNewChat,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_edit_24dp), stringResource(R.string.tatrak_new_chat))
                    }
                }
            }
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .pageDock()
                        .background(bar)
                        .windowInsetsPadding(sides.union(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
                        .padding(horizontal = 4.dp, vertical = 6.dp)
            ) {
                mainTabOrder(floating = false).forEach { TabItem(it, selected, onTab, floating = false, avatar = null) }
            }
        }
    }
}

/** Floating bars: the top pill's gap under the status bar (Telegram 13's header glass, 5 dp under it). */
internal val FloatingTopMargin = 5.dp

/** The floating bars' fill: the bar colour at [barAlpha] (lower over the [backdrop] blurred, per its mode, when blur is on). */
@Composable
internal fun floatingBar(backdrop: BarBackdrop?): Color {
    val colors = TatrakTheme.chatColors
    val scheme = MaterialTheme.colorScheme
    return colors.topBar.copy(alpha = remember(colors, scheme, backdrop) { barAlpha(colors, scheme.primary, scheme.background, backdrop?.mode ?: BarBlur.OFF) })
}

/**
 * Floating bars (any style with the setting on): the screen's own top bar as Telegram 13's header, its pieces (back or
 * menu button, title, actions) each in a glass pill of its own ([TatrakAppBar]), and its [tabs], if any, in a capsule under
 * it, as Telegram 13 draws its folder tabs. The margins, the gaps and the status bar area take touches too: rows faded out
 * under them are not tappable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FloatingTopBar(
    backdrop: BarBackdrop? = null,
    tabs: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = TatrakTheme.chatColors
    val fill = floatingBar(backdrop)
    val pills = remember(fill, backdrop) { HeaderPills(fill, backdrop) }
    // the bars inside go transparent over the pills' fill; the hue stays, so their status bar icons still follow it
    val clear = colors.copy(topBar = colors.topBar.copy(alpha = 0f))
    // consumes the insets, so the TopAppBar inside does not add the status bar again; the pieces' glass sits PieceInset
    // inside their touch targets, so the margins are that much smaller
    Column(Modifier.fillMaxWidth().pointerInput(Unit) {}.windowInsetsPadding(TopAppBarDefaults.windowInsets).padding(top = FloatingTopMargin - PieceInset)) {
        // Telegram: header glass 6 dp from the sides, folder tabs 10.67 dp and 7 dp under it (as the chat's pinned bar)
        Box(Modifier.padding(horizontal = 6.dp - PieceInset)) {
            CompositionLocalProvider(LocalChatColors provides clear, LocalHeaderPills provides pills, content = content)
        }
        if (tabs != null) {
            // 4 dp above and below: the tab indicator stays clear of the round ends
            Box(Modifier.padding(start = 11.dp, end = 11.dp, top = 7.dp - PieceInset).shadow(3.dp, CircleShape).backdropBlur(backdrop, CircleShape).background(fill).padding(vertical = 4.dp)) {
                CompositionLocalProvider(LocalChatColors provides clear, content = tabs)
            }
        }
    }
}

/** Inside [FloatingTopBar]: the fill and backdrop of the header's glass pieces. Null elsewhere: the bars stay TopAppBars. */
internal class HeaderPills(val fill: Color, val backdrop: BarBackdrop?)

internal val LocalHeaderPills = staticCompositionLocalOf<HeaderPills?> { null }

/** A bar's title size: Telegram 13's 17 sp in the floating header's title pill, else the bar's own [plain] size. */
@Composable
internal fun barTitleSize(plain: TextUnit): TextUnit = if (LocalHeaderPills.current != null) HeaderTitleSize else plain

/** Telegram 13's header title in glass mode (`ActionBar.java`: 17 sp). */
private val HeaderTitleSize = 17.sp

/** Telegram's header glass is 46 dp tall; ours sits this much inside a 48 dp touch target on every side. */
internal val PieceInset = 1.dp

/**
 * The top bar of the list and chat screens: an M3 [TopAppBar] (unchanged outside floating bars) or, inside
 * [FloatingTopBar], Telegram 13's glass header: the [navigationIcon] in a circle, the [title] in a capsule filling the
 * room between (or, [centerTitle], as wide as its content and centred there, as Telegram's chat header) and the
 * [actions] in a capsule; a piece with nothing in it (no actions) is left out with its gap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TatrakAppBar(
    navigationIcon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    colors: TopAppBarColors,
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    centerTitle: Boolean = false,
) {
    val pills = LocalHeaderPills.current
    if (pills == null) {
        TopAppBar(title = title, navigationIcon = navigationIcon, actions = actions, windowInsets = windowInsets, colors = colors)
        return
    }
    val gap = 6.dp - PieceInset * 2
    // Telegram: the title 18 dp in from its glass; the chat's avatar row pads itself
    val titlePad = if (centerTitle) 0.dp else 18.dp
    CompositionLocalProvider(
        LocalContentColor provides TatrakTheme.chatColors.onTopBar,
        // 22 sp lines: Telegram's chat subtitle starts 22 dp under its title, and both fit the 46 dp glass
        LocalTextStyle provides MaterialTheme.typography.titleLarge.copy(fontSize = HeaderTitleSize, lineHeight = 22.sp),
    ) {
        Layout(
            contents =
                listOf(
                    { HeaderPiece(pills) { navigationIcon() } },
                    {
                        HeaderPiece(pills) {
                            Box(Modifier.padding(PieceInset).clip(CircleShape).padding(horizontal = titlePad), contentAlignment = Alignment.CenterStart) { title() }
                        }
                    },
                    { HeaderPiece(pills) { Row(verticalAlignment = Alignment.CenterVertically, content = actions) } },
                ),
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(windowInsets),
        ) { (nav, middle, end), constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val back = nav.first().measure(loose)
            val menu = end.first().measure(loose)
            val left = if (back.width > 0) back.width + gap.roundToPx() else 0
            val right = if (menu.width > 0) menu.width + gap.roundToPx() else 0
            val room = (constraints.maxWidth - left - right).coerceAtLeast(0)
            val head = middle.first().measure(if (centerTitle) loose.copy(maxWidth = room) else loose.copy(minWidth = room, maxWidth = room))
            val height = maxOf(back.height, head.height, menu.height)
            layout(constraints.maxWidth, height) {
                back.placeRelative(0, (height - back.height) / 2)
                head.placeRelative(left + (room - head.width) / 2, (height - head.height) / 2)
                menu.placeRelative(constraints.maxWidth - menu.width, (height - menu.height) / 2)
            }
        }
    }
}

/** One glass piece of the floating header: 46 dp of glass inside a 48 dp touch target, as tall as its content. */
@Composable
private fun HeaderPiece(pills: HeaderPills, content: @Composable () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
        // empty (no actions): zero wide, so it draws nothing
        Box(Modifier.matchParentSize().padding(PieceInset).shadow(3.dp, CircleShape).backdropBlur(pills.backdrop, CircleShape).background(pills.fill))
        content()
    }
}

/**
 * Floating bars' bottom bar, as in Telegram 13: a pill with the main tabs Chats / Contacts / Calls / Profile ([MainTabs]:
 * a tap on the current one scrolls it to the top) and, on Chats ([onSearch] set), a round search button; other tabs keep
 * its room, so the pill does not move. Profile shows the drawer's account.
 */
@Composable
internal fun FloatingBottomBar(
    visible: Boolean,
    selected: MainTab,
    account: AccountHeader?,
    onTab: (MainTab) -> Unit,
    onSearch: (() -> Unit)?,
    backdrop: BarBackdrop? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        val colors = TatrakTheme.chatColors
        val bar = floatingBar(backdrop)
        val sides = WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)
        // 16 dp sides: the Scaffold's FAB sits right above the search button
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier.fillMaxWidth()
                    .pointerInput(Unit) {}
                    .windowInsetsPadding(sides.union(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .height(IntrinsicSize.Min),
        ) {
            Row(Modifier.weight(1f).pageDock().shadow(3.dp, CircleShape).backdropBlur(backdrop, CircleShape).background(bar).padding(4.dp)) {
                mainTabOrder(floating = true).forEach { TabItem(it, selected, onTab, floating = true, avatar = account?.avatar) }
            }
            // Chats' own, as Telegram's search: it slides with the page while the tabs switch
            if (onSearch == null) Spacer(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
            else Surface(
                onClick = onSearch,
                shape = CircleShape,
                color = bar,
                contentColor = colors.onTopBar,
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).backdropBlur(backdrop, CircleShape),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_search_24dp), stringResource(R.string.search)) }
            }
        }
    }
}

/**
 * The dock of a main tab other than Chats (whose dock also carries the search and the compose button): the floating
 * bar or Slate and Sea's, as the chat list shows it.
 */
@Composable
internal fun TabDock(selected: MainTab, account: AccountHeader?, onTab: (MainTab) -> Unit, backdrop: BarBackdrop?) {
    if (LocalFloatingBars.current) FloatingBottomBar(true, selected, account, onTab, onSearch = null, backdrop = backdrop)
    else ChatListBottomBar(true, selected, onTab)
}

@Composable
internal fun RowScope.TabItem(tab: MainTab, selected: MainTab, onTab: (MainTab) -> Unit, floating: Boolean, avatar: Bitmap?) {
    val (icon, label) =
        when (tab) {
            MainTab.CHATS -> R.drawable.ic_chat_24dp to R.string.tatrak_dock_chats
            MainTab.CONTACTS -> R.drawable.ic_contacts_24dp to R.string.contacts
            MainTab.CALLS -> R.drawable.ic_call_24dp to R.string.tatrak_calls
            // the floating bar's Profile shows the account; Slate's classic bar keeps Settings
            MainTab.PROFILE -> if (floating) R.drawable.ic_person_24dp to R.string.tatrak_tab_profile else R.drawable.ic_settings_24dp to R.string.action_settings
        }
    // Telegram: a long press on the floating bar's Profile opens the account switcher
    val switcher = if (floating && tab == MainTab.PROFILE) LocalAccountSwitcher.current else null
    DockItem(icon, stringResource(label), selected = tab == selected, onClick = { onTab(tab) }, avatar = if (tab == MainTab.PROFILE) avatar else null, capsule = floating, onLongClick = switcher)
}

/** Opens the account switcher over the dock's Profile item, given the item's bounds in the root; null: no switcher. */
internal val LocalAccountSwitcher = staticCompositionLocalOf<((Rect) -> Unit)?> { null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.DockItem(
    icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    avatar: Bitmap? = null,
    capsule: Boolean = false,
    onLongClick: ((Rect) -> Unit)? = null,
) {
    // read only in the long press, so moving recomposes nothing
    var bounds by remember { mutableStateOf(Rect.Zero) }
    // link: the accent fitted to read on the bar (gen_accents.py); primary is only 3:1 there for light accents
    val color = if (selected) TatrakTheme.chatColors.link else TatrakTheme.chatColors.onTopBarMuted
    // [capsule] (floating bar): Telegram 13's selection, a capsule over the whole tab, icon and label; Slate: round the icon
    val mark = if (selected) color.copy(alpha = 0.14f) else Color.Transparent
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier.weight(1f)
                .then(if (capsule) Modifier.clip(CircleShape).background(mark) else Modifier.clip(RoundedCornerShape(16.dp)))
                .then(if (onLongClick != null) Modifier.onGloballyPositioned { bounds = it.boundsInRoot() } else Modifier)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClickLabel = if (onLongClick != null) stringResource(R.string.tatrak_switch_account) else null,
                    onLongClick = onLongClick?.let { { it(bounds) } },
                )
                .semantics { this.selected = selected }
                .padding(vertical = 4.dp),
    ) {
        // [capsule]: Telegram 13's 48 dp tab in its 56 dp bar (MAIN_TABS_HEIGHT), the 12 sp label from 28 dp down
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.height(if (capsule) 24.dp else 30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (capsule) Color.Transparent else mark)
                    .padding(horizontal = 18.dp),
        ) {
            if (avatar != null) ChatAvatar(avatar, online = false, size = 24.dp) else Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(if (capsule) 24.dp else 22.dp))
        }
        if (capsule) Text(label, color = color, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        else Text(label, color = color, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
