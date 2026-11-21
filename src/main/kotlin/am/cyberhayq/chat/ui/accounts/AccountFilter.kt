package am.cyberhayq.chat.ui.accounts

import am.cyberhayq.chat.ui.theme.FloatingBars
import am.cyberhayq.chat.ui.theme.TatrakStyle
import android.content.Context
import androidx.preference.PreferenceManager
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.utils.AccountUtils

/**
 * The account the whole app is filtered to, chosen in the dock's account switcher, or all accounts (Tatrak has no current
 * account: every enabled account is online). The one place every screen asks: chats, contacts, calls, profile, search,
 * New chat, the share picker. Notifications ignore it.
 */
object AccountFilter {
    /** The chosen account's uuid; persisted, so renaming the key resets the choice of existing installs. */
    const val KEY = "tatrak_account_filter"

    /**
     * The chosen account, or null for all: none chosen, it was removed or disabled, only one account is enabled, or the
     * style has no floating dock, so nothing would show the filter or clear it (the choice is kept for its return).
     */
    @JvmStatic
    fun selected(service: XmppConnectionService): Account? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(service)
        // as TatrakTheme decides the dock: FloatingBars.remember(TatrakStyle.remember())
        val stored = if (preferences.contains(FloatingBars.KEY)) preferences.getBoolean(FloatingBars.KEY, false) else null
        if (!FloatingBars.effective(TatrakStyle.of(preferences.getString(TatrakStyle.KEY, null)), stored)) return null
        return pick(preferences.getString(KEY, null), service.accounts)
    }

    internal fun pick(uuid: String?, accounts: List<Account>): Account? {
        uuid ?: return null
        val enabled = accounts.filter { it.isEnabled }
        return if (enabled.size < 2) null else enabled.firstOrNull { it.uuid == uuid }
    }

    /** Whether [account]'s chats and contacts show while the app is filtered to [selected] (null: all). */
    @JvmStatic
    fun shows(selected: Account?, account: Account?): Boolean = selected == null || selected.uuid == account?.uuid

    /** Null: all accounts. */
    @JvmStatic
    fun select(context: Context, uuid: String?) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, uuid).apply()
    }

    /** A chat of [account] opened from outside (notification, shortcut, link): a filter to another account follows it. */
    @JvmStatic
    fun follow(service: XmppConnectionService, account: Account) {
        if (!shows(selected(service), account)) select(service, account.uuid)
    }

    /** `AccountUtils.getEnabledAccounts` (the accounts New chat's dialogs offer), only the chosen one while filtered. */
    @JvmStatic
    fun enabledAccounts(service: XmppConnectionService): List<String> =
        selected(service)?.let { listOf(it.jid.asBareJid().toString()) } ?: AccountUtils.getEnabledAccounts(service)
}
