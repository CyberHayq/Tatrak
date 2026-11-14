package eu.siacs.conversations.ui.fragment.settings

import am.cyberhayq.chat.ui.settings.AccountHeader
import am.cyberhayq.chat.ui.settings.SettingsHomeScreen
import am.cyberhayq.chat.ui.settings.SettingsRow
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.common.base.Strings
import eu.siacs.conversations.BuildConfig
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.AboutActivity
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.interfaces.OnBackendConnected
import eu.siacs.conversations.utils.AccountUtils
import eu.siacs.conversations.utils.UIHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Start page of the settings: replaces the plain [MainSettingsFragment] list with a
 * Telegram-style one; every row opens the existing preference page the old list linked to.
 */
class SettingsHomeFragment : Fragment(), OnBackendConnected {

    private var account by mutableStateOf<AccountHeader?>(null)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TatrakTheme {
                    SettingsHomeScreen(account, { AccountUtils.launchManageAccounts(requireActivity()) }, sections())
                }
            }
        }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.title_activity_settings)
        loadAccount()
    }

    override fun onBackendConnected() = loadAccount()

    private fun loadAccount() {
        val service = (activity as? XmppActivity)?.xmppConnectionService ?: return
        val first = service.accounts.firstOrNull() ?: return
        val size = (64 * resources.displayMetrics.density).toInt()
        viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
            val avatar = withContext(Dispatchers.IO) { service.avatarService.get(first, size, false) }
            val name = first.displayName ?: first.jid.local ?: first.jid.toString()
            account = AccountHeader(name, first.jid.asBareJid().toString(), avatar, UIHelper.getColorForName(name))
        }
    }

    private fun open(fragment: Fragment) =
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

    private fun row(icon: Int, title: Int, summary: Int?, tint: Long, page: () -> Fragment) =
        SettingsRow(icon, getString(title), summary?.let(::getString), Color(tint)) { open(page()) }

    private fun sections(): List<List<SettingsRow>> {
        val showPush = !Strings.isNullOrEmpty(getString(R.string.default_push_server))
        return listOf(
            listOf(
                row(R.drawable.ic_palette_24dp, R.string.tatrak_appearance, R.string.tatrak_appearance_summary, 0xFF3E88F7) {
                    AppearanceFragment()
                },
                row(R.drawable.ic_touch_app_24dp, R.string.pref_title_interface, R.string.pref_summary_appearance, 0xFF4AA6E0) {
                    InterfaceSettingsFragment()
                },
            ),
            listOf(
                row(R.drawable.ic_notifications_24dp, R.string.notifications, R.string.pref_notifications_summary, 0xFFEB5545) {
                    NotificationsSettingsFragment()
                },
                row(R.drawable.ic_privacy_tip_24dp, R.string.pref_privacy, R.string.pref_privacy_summary, 0xFF8E8E93) {
                    PrivacySettingsFragment()
                },
                row(R.drawable.ic_security_24dp, R.string.pref_title_security, R.string.pref_summary_security, 0xFF5BB45B) {
                    SecuritySettingsFragment()
                },
                row(R.drawable.ic_lightbulb_24dp, R.string.pref_presence_settings, R.string.pref_presence_settings_summary, 0xFFF0A030) {
                    AvailabilitySettingsFragment()
                },
            ),
            listOfNotNull(
                row(R.drawable.ic_attachment_24dp, R.string.pref_attachments, R.string.pref_attachments_summary, 0xFF2CB4C5) {
                    AttachmentsSettingsFragment()
                },
                row(
                    R.drawable.ic_settings_ethernet_24dp,
                    R.string.pref_connection_options,
                    if (ConnectionSettingsFragment.hideChannelDiscovery()) R.string.pref_connection_summary
                    else R.string.pref_connection_summary_w_cd,
                    0xFF7C6CEB,
                ) {
                    ConnectionSettingsFragment()
                },
                row(R.drawable.ic_archive_24dp, R.string.backup_and_restore, R.string.pref_backup_summary, 0xFFE57A3C) {
                    BackupSettingsFragment()
                },
                if (showPush) {
                    row(R.drawable.ic_cloud_sync_24dp, R.string.unified_push_distributor, R.string.unified_push_summary, 0xFF5E9BD6) {
                        UpSettingsFragment()
                    }
                } else {
                    null
                },
            ),
            listOf(
                SettingsRow(
                    R.drawable.ic_help_24dp,
                    getString(R.string.title_activity_about_x, BuildConfig.APP_NAME),
                    null,
                    Color(0xFF8E8E93),
                ) {
                    startActivity(Intent(requireContext(), AboutActivity::class.java))
                },
            ),
        )
    }
}
