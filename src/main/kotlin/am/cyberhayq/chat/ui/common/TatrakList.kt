package am.cyberhayq.chat.ui.common

import am.cyberhayq.chat.ui.chat.LetterAvatar
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.StatusBarIcons
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import eu.siacs.conversations.R
import eu.siacs.conversations.utils.UIHelper

/**
 * Shared pieces of the Telegram-style list screens of stage 7 (new chat, message search, media,
 * accounts, share target): one top bar, section headers and the avatar row.
 */

/** A search field that replaces the title while [TatrakTopBar] is searching. */
@Immutable
data class TopBarSearch(
    val needle: String,
    val hint: String,
    val onNeedle: (String) -> Unit,
    val onDone: () -> Unit = {},
    val focusRequest: Int? = null,
)

/** One overflow-menu entry. */
@Immutable
data class MenuEntry(
    val label: String,
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The top bar in the chat colours. With [search] set, the title becomes a focused search field;
 * [menu] goes into a three-dot overflow after [actions].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TatrakTopBar(
    title: String,
    /** Null hides the back arrow (the classic screen's up navigation turned off). */
    onBack: (() -> Unit)?,
    search: TopBarSearch? = null,
    menu: List<MenuEntry> = emptyList(),
    @DrawableRes navigationIcon: Int = R.drawable.ic_arrow_back_24dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val bar = TatrakTheme.chatColors
    val onBar = bar.onTopBar
    StatusBarIcons(dark = bar.topBar.luminance() > 0.5f)
    TopAppBar(
        // landscape: keep the actions clear of a side navigation bar or cutout
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = bar.topBar,
                titleContentColor = onBar,
                navigationIconContentColor = onBar,
                actionIconContentColor = onBar,
            ),
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(navigationIcon), stringResource(R.string.tatrak_chat_back))
                }
            }
        },
        title = {
            if (search != null) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(search.focusRequest) { focus.requestFocus() }
                Box(contentAlignment = Alignment.CenterStart) {
                    if (search.needle.isEmpty()) {
                        Text(search.hint, color = onBar.copy(alpha = 0.6f), fontSize = 18.sp, maxLines = 1)
                    }
                    BasicTextField(
                        value = search.needle,
                        onValueChange = search.onNeedle,
                        singleLine = true,
                        textStyle = TextStyle(color = onBar, fontSize = 18.sp),
                        cursorBrush = SolidColor(onBar),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search.onDone() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            } else {
                Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        actions = {
            actions()
            if (menu.isNotEmpty()) OverflowMenu(menu)
        },
    )
}

@Composable
private fun OverflowMenu(menu: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.tatrak_ic_more_vert_24dp), stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    trailingIcon =
                        if (entry.checked == true) {
                            { Icon(painterResource(R.drawable.ic_check_24dp), null) }
                        } else {
                            null
                        },
                    enabled = entry.enabled,
                    onClick = {
                        open = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** A centred grey line for empty lists and "nothing found". */
@Composable
fun EmptyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 15.sp,
        modifier = modifier.fillMaxWidth().padding(24.dp),
    )
}

/**
 * Avatar, name and one subtitle line, with an optional [trailing] slot and a long-press menu.
 * [subtitleAccent] paints the subtitle in the primary colour (online, connected). [menuOnLongPress]
 * builds the menu at the long press instead of [menu], for menus too costly to work out per row.
 * [onLongClick] runs an action at the long press instead of a menu; [longClickLabel] names it for TalkBack.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AvatarRow(
    name: String,
    subtitle: AnnotatedString,
    avatar: Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    menu: List<MenuEntry> = emptyList(),
    subtitleLines: Int = 1,
    trailing: (@Composable () -> Unit)? = null,
    topEnd: String? = null,
    menuOnLongPress: (() -> List<MenuEntry>)? = null,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var built by remember { mutableStateOf(emptyList<MenuEntry>()) }
    // a menu opened over the keyboard is placed for the shorter window and stays there once the
    // keyboard goes (a long one ends at the window top), so the keyboard goes first
    var menuPending by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val openMenu = {
        if (ime.getBottom(density) > 0) {
            keyboard?.hide()
            menuPending = true
        } else {
            menuOpen = true
        }
    }
    LaunchedEffect(menuPending) {
        if (!menuPending) return@LaunchedEffect
        withTimeoutOrNull(1000) { snapshotFlow { ime.getBottom(density) == 0 }.first { it } }
        menuPending = false
        menuOpen = true
    }
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        role = Role.Button,
                        onClick = onClick,
                        onLongClickLabel = longClickLabel,
                        onLongClick =
                            when {
                                onLongClick != null -> onLongClick
                                menuOnLongPress != null -> ({
                                    built = menuOnLongPress()
                                    if (built.isNotEmpty()) openMenu()
                                })
                                menu.isNotEmpty() -> openMenu
                                else -> null
                            },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            LetterAvatar(
                avatar,
                name.take(1).uppercase(),
                remember(name) { UIHelper.getColorForName(name) },
                AVATAR_SIZE.dp,
                18.sp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        fontSize = 16.sp,
                        fontWeight = if (topEnd != null) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (topEnd != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(topEnd, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // no empty line under the name: it centres on the avatar instead
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        fontSize = 14.sp,
                        maxLines = subtitleLines,
                        overflow = TextOverflow.Ellipsis,
                        color = subtitleColor,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            (if (menuOnLongPress != null) built else menu).forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    enabled = item.enabled,
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

/** Avatar size of [AvatarRow] in dp; also the pixel size the hosts load avatars at. */
const val AVATAR_SIZE = 44
