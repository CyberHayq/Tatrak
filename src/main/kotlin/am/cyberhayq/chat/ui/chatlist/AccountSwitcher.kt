package am.cyberhayq.chat.ui.chatlist

import am.cyberhayq.chat.ui.theme.LocalAvatarShape
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/**
 * Telegram's account selector (`MainTabsActivity.openAccountSelector`): a long press on the floating dock's Profile
 * ([anchor]: its bounds in the root; null: closed) dims the screen, lifts the item onto a rounded plate and opens a menu
 * above it: "Add account", then "All accounts" (only with more than one account) and every account with its avatar,
 * name and unread chats, the [selected] one (null: all) ringed; a disabled one muted, with "Disabled" under its name
 * (Telegram has no disabled accounts; Cheogram's account header lists them too). The caller blurs what lies under it.
 */
@Composable
internal fun AccountSwitcher(
    anchor: Rect?,
    accounts: List<AccountChoice>,
    selected: String?,
    tab: MainTab,
    avatar: Bitmap?,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    // kept while the menu fades out
    var shown by remember { mutableStateOf(Rect.Zero) }
    if (anchor != null) shown = anchor
    BackHandler(enabled = anchor != null, onBack = onDismiss)
    AnimatedVisibility(visible = anchor != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
        val scheme = MaterialTheme.colorScheme
        // ItemOptions' dim: 0x66 over a light window, 0x33 over a dark one
        val dim = if (scheme.surface.luminance() > 0.5f) 0.4f else 0.2f
        // Telegram's popup grows from the button
        val scale by transition.animateFloat({ tween(150) }, label = "menu") { if (it == EnterExitState.Visible) 1f else 0.85f }
        val density = LocalDensity.current
        val plate = RoundedCornerShape(28.dp)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)).pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
            Layout(
                content = {
                    // the Profile item above the dim, on Telegram's plate (28 dp corners, a soft shadow)
                    Row(
                        Modifier.size(with(density) { shown.width.toDp() }, with(density) { shown.height.toDp() })
                            .shadow(6.dp, plate)
                            .background(scheme.surfaceContainer, plate)
                    ) {
                        TabItem(MainTab.PROFILE, tab, { onDismiss() }, floating = true, avatar = avatar, all = selected == null && accounts.size > 1)
                    }
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = scheme.surfaceContainer,
                        shadowElevation = 6.dp,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(1f, 1f)
                        },
                    ) {
                        // one account: nothing to filter, so a tap on its row only closes the menu
                        AccountMenu(accounts, selected, onAdd = { onDismiss(); onAdd() }, onSelect = { onDismiss(); if (accounts.size > 1) onSelect(it) })
                    }
                },
            ) { measurables, constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val item = measurables[0].measure(loose)
                val margin = 8.dp.roundToPx()
                // the space above the item; the account rows scroll inside it
                val menu = measurables[1].measure(loose.copy(maxHeight = (shown.top.toInt() - 8.dp.roundToPx() - margin).coerceIn(0, constraints.maxHeight)))
                // the menu's end over the item's, 8 dp above it (ItemOptions' gap and its 4 dp lift)
                val x = (shown.right.toInt() - menu.width).coerceIn(margin, (constraints.maxWidth - menu.width - margin).coerceAtLeast(margin))
                val y = (shown.top.toInt() - menu.height - 8.dp.roundToPx()).coerceAtLeast(margin)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    item.place(shown.left.toInt(), shown.top.toInt())
                    menu.place(x, y)
                }
            }
        }
    }
}

@Composable
private fun AccountMenu(accounts: List<AccountChoice>, selected: String?, onAdd: () -> Unit, onSelect: (String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // Telegram's account rows are 230 x 48 dp
    Column(Modifier.width(230.dp).padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = onAdd).padding(horizontal = 18.dp),
        ) {
            Icon(painterResource(R.drawable.ic_person_add_24dp), null, tint = scheme.onSurfaceVariant)
            Text(stringResource(R.string.action_add_account), color = scheme.onSurface, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp))
        }
        // ItemOptions.addGap
        Spacer(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(8.dp).background(scheme.onSurface.copy(alpha = 0.05f)))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            // one account: nothing to filter
            if (accounts.size > 1) {
                AccountRow(stringResource(R.string.tatrak_all_accounts), null, all = true, accounts.sumOf { it.unread }, selected == null, disabled = false) { onSelect(null) }
            }
            accounts.forEach {
                AccountRow(it.name, it.avatar, all = false, it.unread, it.uuid == selected || accounts.size == 1, it.disabled) { onSelect(it.uuid) }
            }
        }
    }
}

/**
 * Telegram's `accountView`: a 32 dp avatar, shrunk inside a ring when selected, and the name in up to two lines; a
 * [disabled] account's name in the muted text colour (`onSurfaceVariant`, readable at 4.5:1), its avatar faded,
 * "Disabled" under the name (TalkBack reads both: the clickable row merges them).
 */
@Composable
private fun AccountRow(name: String, avatar: Bitmap?, all: Boolean, unread: Int, selected: Boolean, disabled: Boolean, onClick: () -> Unit) {
    val shape = LocalAvatarShape.current
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .height(48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(start = 12.dp, end = 14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(34.dp).then(if (selected) Modifier.border(1.33.dp, accent, shape) else Modifier),
        ) {
            val size = if (selected) 26.dp else 32.dp
            if (all) {
                AllAccountsIcon(size)
            } else {
                Box(if (disabled) Modifier.alpha(DISABLED_ALPHA) else Modifier) { ChatAvatar(avatar, online = false, size = size) }
            }
        }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(
                name,
                color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                maxLines = if (disabled) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (disabled) {
                Text(stringResource(R.string.tatrak_account_disabled), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
            }
        }
        if (unread > 0) UnreadBadge(unread, TatrakTheme.chatColors.unreadBadge)
    }
}

/** Material's alpha for disabled content. */
private const val DISABLED_ALPHA = 0.38f

/** "All accounts": a group icon on the accent, in the avatars' shape (the switcher's row, the dock's Profile item). */
@Composable
internal fun AllAccountsIcon(size: Dp) {
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.primary, LocalAvatarShape.current), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_group_24dp), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.6f))
    }
}
