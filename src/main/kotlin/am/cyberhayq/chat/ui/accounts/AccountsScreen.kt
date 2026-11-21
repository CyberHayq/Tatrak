package am.cyberhayq.chat.ui.accounts

import am.cyberhayq.chat.ui.common.AvatarRow
import am.cyberhayq.chat.ui.common.TatrakScaffold
import am.cyberhayq.chat.ui.common.TatrakTopBar
import am.cyberhayq.chat.ui.common.MenuEntry
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/** The former long-press menu of an account (`manageaccounts_context`). */
enum class AccountMenu(@StringRes val label: Int) {
    ENABLE(R.string.mgmt_account_enable),
    PUBLISH_AVATAR(R.string.mgmt_account_publish_avatar),
    ANNOUNCE_PGP(R.string.mgmt_account_publish_pgp),
    DISABLE(R.string.mgmt_account_disable),
    DELETE(R.string.mgmt_account_delete),
}

/** The former options menu (`manageaccounts`); which entries show is decided by the host. */
enum class AccountsAction(@StringRes val label: Int) {
    ADD(R.string.action_add_account),
    ADD_WITH_CERTIFICATE(R.string.action_add_account_with_certificate),
    IMPORT_BACKUP(R.string.restore_backup),
    SETTINGS(R.string.action_settings),
    ENABLE_ALL(R.string.enable_all_accounts),
    DISABLE_ALL(R.string.disable_all_accounts),
}

enum class AccountStatus {
    CONNECTED,
    QUIET,
    ERROR,
}

@Immutable
data class AccountEntry(
    /** Account uuid. */
    val key: String,
    val address: String,
    val status: String,
    val statusKind: AccountStatus,
    val enabled: Boolean,
    val avatar: Bitmap? = null,
    val menu: List<AccountMenu> = emptyList(),
)

@Immutable
data class AccountsState(
    val accounts: List<AccountEntry> = emptyList(),
    /** The row under the list: [AccountsAction.ADD] or, with X.509 login, the certificate one. */
    val addRow: AccountsAction = AccountsAction.ADD,
    val menu: List<AccountsAction> = emptyList(),
)

interface AccountsActions {
    fun back()

    fun open(key: String)

    fun setEnabled(key: String, enabled: Boolean)

    fun menu(key: String, item: AccountMenu)

    fun action(action: AccountsAction)
}

/** The account list: avatar, address, connection state and an on/off switch per account. */
@Composable
fun AccountsScreen(state: AccountsState, actions: AccountsActions, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        val labels = state.menu.map { stringResource(it.label) }
        TatrakScaffold(
            topBar = {
                TatrakTopBar(
                    title = stringResource(R.string.title_activity_manage_accounts),
                    onBack = actions::back,
                    menu = state.menu.mapIndexed { i, a -> MenuEntry(labels[i]) { actions.action(a) } },
                )
            },
            modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        ) { top ->
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(top = top)) {
                items(state.accounts, key = { it.key }) { account -> AccountRow(account, actions) }
                item(key = "add") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable(role = Role.Button) { actions.action(state.addRow) }
                                .padding(horizontal = 26.dp, vertical = 14.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_add_24dp),
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(34.dp))
                        Text(stringResource(state.addRow.label), fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(account: AccountEntry, actions: AccountsActions) {
    val labels = account.menu.map { stringResource(it.label) }
    AvatarRow(
        name = account.address,
        subtitle = AnnotatedString(account.status),
        avatar = account.avatar,
        onClick = { actions.open(account.key) },
        subtitleColor =
            when (account.statusKind) {
                AccountStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                AccountStatus.QUIET -> MaterialTheme.colorScheme.onSurfaceVariant
                AccountStatus.ERROR -> MaterialTheme.colorScheme.error
            },
        menu = account.menu.mapIndexed { i, item -> MenuEntry(labels[i]) { actions.menu(account.key, item) } },
        trailing = { Switch(checked = account.enabled, onCheckedChange = { actions.setEnabled(account.key, it) }) },
    )
}
