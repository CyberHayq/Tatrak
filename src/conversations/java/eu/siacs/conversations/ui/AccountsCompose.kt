package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.accounts.AccountEntry
import am.cyberhayq.chat.ui.accounts.AccountMenu
import am.cyberhayq.chat.ui.accounts.AccountStatus
import am.cyberhayq.chat.ui.accounts.AccountsAction
import am.cyberhayq.chat.ui.accounts.AccountsActions
import am.cyberhayq.chat.ui.accounts.AccountsScreen
import am.cyberhayq.chat.ui.accounts.AccountsState
import am.cyberhayq.chat.ui.common.AVATAR_SIZE
import android.view.View
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Account
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tatrak: the Compose account list of [ManageAccountActivity]. Enabling, disabling, deleting,
 * avatar and PGP publishing, adding (also with a certificate) and backup import stay in the
 * activity; account settings open the unchanged [EditAccountActivity].
 */
internal class AccountsCompose(private val activity: ManageAccountActivity) : AccountsActions {

    private val state = MutableStateFlow(AccountsState())
    private var accounts: Map<String, Account> = emptyMap()
    private var avatars: Job? = null

    fun attach(classic: View) {
        activity.composeOver(classic) {
            val current by state.collectAsStateWithLifecycle()
            AccountsScreen(current, this)
        }
    }

    /** Called on the main thread at the end of the activity's `refreshUiReal()`. */
    fun publish(list: List<Account>) {
        accounts = list.associateBy { it.uuid }
        val previous = state.value.accounts.associate { it.key to it.avatar }
        state.value =
            AccountsState(
                accounts = list.map { entry(it).copy(avatar = previous[it.uuid]) },
                addRow = if (Config.X509_VERIFICATION) AccountsAction.ADD_WITH_CERTIFICATE else AccountsAction.ADD,
                // same visibility rules as onCreateOptionsMenu
                menu =
                    buildList {
                        if (!Config.X509_VERIFICATION) add(AccountsAction.ADD_WITH_CERTIFICATE)
                        add(AccountsAction.IMPORT_BACKUP)
                        if (activity.accountsLeftToEnable()) add(AccountsAction.ENABLE_ALL)
                        if (activity.accountsLeftToDisable()) add(AccountsAction.DISABLE_ALL)
                        add(AccountsAction.SETTINGS)
                    },
            )
        loadAvatars(list)
    }

    private fun entry(account: Account): AccountEntry {
        val status = account.status
        val text =
            if (account.isServiceOutage) {
                val planned = account.serviceOutageStatus?.isPlanned == true
                activity.getString(
                    if (planned) R.string.account_status_service_outage_scheduled
                    else R.string.account_status_service_outage_known
                )
            } else {
                activity.getString(status.readableId)
            }
        return AccountEntry(
            key = account.uuid,
            address = account.jid.asBareJid().toString(),
            status = text,
            statusKind =
                when (status) {
                    Account.State.ONLINE -> AccountStatus.CONNECTED
                    Account.State.DISABLED, Account.State.LOGGED_OUT, Account.State.CONNECTING -> AccountStatus.QUIET
                    else -> AccountStatus.ERROR
                },
            enabled = status != Account.State.DISABLED,
            // same visibility rules as onCreateContextMenu
            menu =
                if (account.isEnabled) {
                    listOf(AccountMenu.PUBLISH_AVATAR, AccountMenu.ANNOUNCE_PGP, AccountMenu.DISABLE, AccountMenu.DELETE)
                } else {
                    listOf(AccountMenu.ENABLE, AccountMenu.DELETE)
                },
        )
    }

    private fun loadAvatars(list: List<Account>) {
        val service = activity.xmppConnectionService ?: return
        val size = (AVATAR_SIZE * activity.resources.displayMetrics.density).roundToInt()
        avatars?.cancel()
        avatars =
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val loaded = list.associate { it.uuid to service.avatarService.get(it, size, false) }
                state.update { s ->
                    s.copy(accounts = s.accounts.map { a -> if (a.key in loaded) a.copy(avatar = loaded[a.key]) else a })
                }
            }
    }

    // --- actions ------------------------------------------------------------------------------

    override fun back() = activity.onBackPressedDispatcher.onBackPressed()

    override fun open(key: String) {
        activity.switchToAccount(accounts[key] ?: return)
    }

    override fun setEnabled(key: String, enabled: Boolean) {
        activity.onClickTglAccountState(accounts[key] ?: return, enabled)
    }

    override fun menu(key: String, item: AccountMenu) {
        val account = accounts[key] ?: return
        activity.accountAction(
            account,
            when (item) {
                AccountMenu.ENABLE -> R.id.mgmt_account_enable
                AccountMenu.PUBLISH_AVATAR -> R.id.mgmt_account_publish_avatar
                AccountMenu.ANNOUNCE_PGP -> R.id.mgmt_account_announce_pgp
                AccountMenu.DISABLE -> R.id.mgmt_account_disable
                AccountMenu.DELETE -> R.id.mgmt_account_delete
            },
        )
    }

    override fun action(action: AccountsAction) {
        if (action == AccountsAction.SETTINGS) {
            activity.openSettings()
            return
        }
        activity.menuAction(
            when (action) {
                AccountsAction.ADD -> R.id.action_add_account
                AccountsAction.ADD_WITH_CERTIFICATE -> R.id.action_add_account_with_cert
                AccountsAction.IMPORT_BACKUP -> R.id.action_import_backup
                AccountsAction.ENABLE_ALL -> R.id.action_enable_all
                AccountsAction.DISABLE_ALL -> R.id.action_disable_all
                AccountsAction.SETTINGS -> error("handled above")
            }
        )
    }
}
