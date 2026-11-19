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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/** The Slate and Sea bottom bar shows only while the list itself is on screen (not searching, in the archive or selecting). */
internal fun dockVisible(searching: Boolean, showArchive: Boolean, selectionMode: Boolean): Boolean =
    !searching && !showArchive && !selectionMode

/**
 * Slate and Sea bottom bar: a floating search pill and compose button above a row of shortcuts (not a navigation graph:
 * Chats scrolls to the top, the others open their screens). The dock itself extends under the navigation bar.
 */
@Composable
internal fun ChatListBottomBar(
    visible: Boolean,
    actions: ChatListActions,
    onSearch: () -> Unit,
    onChats: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        val bar = TatrakTheme.chatColors.topBar
        val sides = WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)
        Column {
            Row(
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
                    onClick = actions::newChat,
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
                        .background(bar)
                        .windowInsetsPadding(sides.union(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
                        .padding(horizontal = 4.dp, vertical = 6.dp)
            ) {
                DockItem(R.drawable.ic_chat_24dp, stringResource(R.string.tatrak_dock_chats), selected = true, onClick = onChats)
                DockItem(R.drawable.ic_call_24dp, stringResource(R.string.tatrak_calls), selected = false, onClick = actions::openCalls)
                DockItem(R.drawable.ic_contacts_24dp, stringResource(R.string.contacts), selected = false, onClick = actions::openContacts)
                DockItem(R.drawable.ic_settings_24dp, stringResource(R.string.action_settings), selected = false, onClick = actions::openSettings)
            }
        }
    }
}

/**
 * Floating bars (any style with the setting on): the screen's own top bars in a rounded pill over the list, in the bar
 * colour at [barAlpha] (lower over the [backdrop] blurred, per its mode, when blur is on). The margins and the status bar area take
 * touches too: rows faded out under them are not tappable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FloatingTopBar(
    /** Under the content inside the pill: keeps the chat list's tab indicator off the rounded corners. */
    bottom: Dp = 6.dp,
    backdrop: BarBackdrop? = null,
    content: @Composable () -> Unit,
) {
    val colors = TatrakTheme.chatColors
    val scheme = MaterialTheme.colorScheme
    val alpha = remember(colors, scheme, backdrop) { barAlpha(colors, scheme.primary, scheme.background, backdrop?.mode ?: BarBlur.OFF) }
    val shape = RoundedCornerShape(24.dp)
    // consumes the insets, so the TopAppBar inside does not add the status bar again
    Box(Modifier.fillMaxWidth().pointerInput(Unit) {}.windowInsetsPadding(TopAppBarDefaults.windowInsets).padding(start = 8.dp, end = 8.dp, top = 4.dp)) {
        // the bars inside go transparent over the pill's one fill; the hue stays, so their status bar icons still follow it
        Column(Modifier.shadow(3.dp, shape).backdropBlur(backdrop, shape).background(colors.topBar.copy(alpha = alpha)).padding(bottom = bottom)) {
            CompositionLocalProvider(LocalChatColors provides colors.copy(topBar = colors.topBar.copy(alpha = 0f)), content = content)
        }
    }
}

/**
 * Floating bars' bottom bar, as in Telegram 13: a pill with Chats / Contacts / Calls / Profile and a round search button;
 * shortcuts to the existing screens, not a navigation graph. Profile shows the drawer's account and opens the settings.
 */
@Composable
internal fun FloatingBottomBar(
    visible: Boolean,
    account: AccountHeader?,
    actions: ChatListActions,
    onSearch: () -> Unit,
    onChats: () -> Unit,
    backdrop: BarBackdrop? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        val colors = TatrakTheme.chatColors
        val scheme = MaterialTheme.colorScheme
        val bar = colors.topBar.copy(alpha = remember(colors, scheme, backdrop) { barAlpha(colors, scheme.primary, scheme.background, backdrop?.mode ?: BarBlur.OFF) })
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
            Row(Modifier.weight(1f).shadow(3.dp, CircleShape).backdropBlur(backdrop, CircleShape).background(bar).padding(4.dp)) {
                DockItem(R.drawable.ic_chat_24dp, stringResource(R.string.tatrak_dock_chats), selected = true, onClick = onChats)
                DockItem(R.drawable.ic_contacts_24dp, stringResource(R.string.contacts), selected = false, onClick = actions::openContacts)
                DockItem(R.drawable.ic_call_24dp, stringResource(R.string.tatrak_calls), selected = false, onClick = actions::openCalls)
                DockItem(R.drawable.ic_person_24dp, stringResource(R.string.tatrak_tab_profile), selected = false, onClick = actions::openSettings, avatar = account?.avatar)
            }
            Surface(
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

@Composable
private fun RowScope.DockItem(icon: Int, label: String, selected: Boolean, onClick: () -> Unit, avatar: Bitmap? = null) {
    // link: the accent fitted to read on the bar (gen_accents.py); primary is only 3:1 there for light accents
    val color = if (selected) TatrakTheme.chatColors.link else TatrakTheme.chatColors.onTopBarMuted
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier.weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (selected) color.copy(alpha = 0.14f) else Color.Transparent)
                    .padding(horizontal = 18.dp),
        ) {
            if (avatar != null) ChatAvatar(avatar, online = false, size = 24.dp) else Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(22.dp))
        }
        Text(label, color = color, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
