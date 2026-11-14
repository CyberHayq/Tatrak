package am.cyberhayq.chat.ui.setup

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.services.XmppConnectionService

/**
 * Channel binding is required by default. When a server does not offer it, the account fails with
 * [Account.State.CHANNEL_BINDING]; this asks once whether to stop requiring it, naming the risk.
 * Only the existing setting is written; the connection code is untouched.
 */
object ChannelBindingPrompt {
    private val declined = HashSet<String>()
    private var dialog: AlertDialog? = null

    @JvmStatic
    fun maybeShow(activity: Activity, account: Account, service: XmppConnectionService?) {
        if (account.status != Account.State.CHANNEL_BINDING) {
            declined.remove(account.uuid)
            return
        }
        // secure domains require it regardless of the setting: turning it off would not help
        if (service == null || AppSettings.SECURE_DOMAINS.contains(account.domain)) return
        if (dialog?.isShowing == true || !declined.add(account.uuid)) return
        dialog =
            AlertDialog.Builder(activity)
                .setTitle(R.string.tatrak_cb_title)
                .setMessage(activity.getString(R.string.tatrak_cb_message, account.domain.toString()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.tatrak_cb_accept) { _, _ ->
                    PreferenceManager.getDefaultSharedPreferences(activity)
                        .edit()
                        .putBoolean(AppSettings.REQUIRE_CHANNEL_BINDING, false)
                        .apply()
                    service.reconnectAccountInBackground(account)
                }
                .show()
    }
}
