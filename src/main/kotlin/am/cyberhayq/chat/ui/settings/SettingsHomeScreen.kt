package am.cyberhayq.chat.ui.settings

import am.cyberhayq.chat.ui.theme.groupedBackground
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import am.cyberhayq.chat.ui.chat.LetterAvatar
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import am.cyberhayq.chat.ui.common.ScrollToTopOn
import am.cyberhayq.chat.ui.common.TatrakScaffold
import am.cyberhayq.chat.ui.common.TatrakTopBar
import am.cyberhayq.chat.ui.common.listBottom
import am.cyberhayq.chat.ui.common.listPadding
import am.cyberhayq.chat.ui.theme.BarBackdrop
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import eu.siacs.conversations.R
import androidx.compose.ui.unit.sp

@Immutable data class AccountHeader(val name: String, val address: String, val avatar: Bitmap?, val color: Int)

/** [tint] colours the icon tile, as in Telegram's settings list. */
@Immutable
data class SettingsRow(
    val icon: Int,
    val title: String,
    val summary: String?,
    val tint: Color,
    val onClick: () -> Unit,
)

/** Telegram-style settings home: the account on top, then grouped rows into the classic pages. */
@Composable
fun SettingsHomeScreen(
    account: AccountHeader?,
    onAccount: () -> Unit,
    sections: List<List<SettingsRow>>,
    modifier: Modifier = Modifier,
    /** In a [TatrakScaffold]: its floating top bar's height. */
    top: Dp = 0.dp,
    list: LazyListState = rememberLazyListState(),
) {
    // Text without a colour would fall back to black outside a Surface (unreadable in the dark theme)
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        LazyColumn(
            modifier.fillMaxSize().background(MaterialTheme.colorScheme.groupedBackground).listBottom(),
            state = list,
            contentPadding = listPadding(top),
        ) {
            // always present: an item inserted above the first visible one would open scrolled past it
            item(key = "account") {
                if (account != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable(role = Role.Button, onClick = onAccount)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        LetterAvatar(account.avatar, account.name.take(1).uppercase(), account.color, 64.dp, 26.sp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account.name, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                account.address,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.fillMaxWidth().height(92.dp).background(MaterialTheme.colorScheme.surface))
                }
                Spacer(Modifier.height(10.dp))
            }
            sections.forEachIndexed { index, rows ->
                items(rows, key = { it.title }) { row -> SettingsRowItem(row, divider = row !== rows.last()) }
                if (index != sections.lastIndex) item(key = "gap$index") { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}

/** The chat list's Profile tab: the settings home under a "Settings" bar without a back button, over the [dock]. */
@Composable
fun ProfileTab(
    account: AccountHeader?,
    onAccount: () -> Unit,
    sections: List<List<SettingsRow>>,
    dock: @Composable (BarBackdrop?) -> Unit,
    scrollToTop: Int = 0,
) {
    val list = rememberLazyListState()
    ScrollToTopOn(scrollToTop, list)
    TatrakScaffold(
        topBar = { TatrakTopBar(title = stringResource(R.string.title_activity_settings), onBack = null) },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.groupedBackground),
        bottomBar = dock,
    ) { top ->
        SettingsHomeScreen(account, onAccount, sections, top = top, list = list)
    }
}

@Composable
private fun SettingsRowItem(row: SettingsRow, divider: Boolean) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClick = row.onClick)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(row.tint),
            ) {
                Icon(painterResource(row.icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(row.title, fontSize = 16.sp)
                if (row.summary != null) {
                    Text(
                        row.summary,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
