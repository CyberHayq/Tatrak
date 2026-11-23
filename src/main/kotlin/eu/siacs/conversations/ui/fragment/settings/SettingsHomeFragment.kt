package eu.siacs.conversations.ui.fragment.settings

import am.cyberhayq.chat.ui.accounts.AccountFilter
import am.cyberhayq.chat.ui.settings.AccountHeader
import am.cyberhayq.chat.ui.settings.LanguageFragment
import am.cyberhayq.chat.ui.settings.SettingsHomeScreen
import am.cyberhayq.chat.ui.settings.SettingsRow
import am.cyberhayq.chat.ui.settings.appLanguageSummary
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.common.base.Strings
import eu.siacs.conversations.BuildConfig
import eu.siacs.conversations.R
import eu.siacs.conversations.services.XmppConnectionService
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

    /** The language row's summary, re-read on resume: choosing the language already in effect recreates nothing. */
    private var language by mutableStateOf("")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TatrakTheme {
                    val sections = remember(language) { settingsSections(requireContext()) { open(page(it)!!) } }
                    SettingsHomeScreen(account, { AccountUtils.launchManageAccounts(requireActivity()) }, sections)
                }
            }
        }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.title_activity_settings)
        loadAccount()
    }

    override fun onResume() {
        super.onResume()
        language = appLanguageSummary(requireContext())
    }

    override fun onBackendConnected() = loadAccount()

    private fun loadAccount() {
        val service = (activity as? XmppActivity)?.xmppConnectionService ?: return
        viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch { account = settingsAccount(service, resources) ?: return@launch }
    }

    private fun open(fragment: Fragment) =
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

    companion object {
        /** [SettingsActivity][eu.siacs.conversations.ui.activity.SettingsActivity] opens this page (a row title) at once. */
        const val EXTRA_PAGE = "tatrak_settings_page"

        /** The page a row opens, by the row's title; null for anything else (the extra comes from outside too). */
        @JvmStatic
        fun page(title: Int): Fragment? =
            when (title) {
                R.string.tatrak_appearance -> AppearanceFragment()
                R.string.pref_title_interface -> InterfaceSettingsFragment()
                R.string.tatrak_language -> LanguageFragment()
                R.string.notifications -> NotificationsSettingsFragment()
                R.string.pref_privacy -> PrivacySettingsFragment()
                R.string.pref_title_security -> SecuritySettingsFragment()
                R.string.pref_presence_settings -> AvailabilitySettingsFragment()
                R.string.pref_attachments -> AttachmentsSettingsFragment()
                R.string.pref_connection_options -> ConnectionSettingsFragment()
                R.string.backup_and_restore -> BackupSettingsFragment()
                R.string.unified_push_distributor -> UpSettingsFragment()
                R.string.tatrak_updates -> UpdatesSettingsFragment()
                else -> null
            }
    }
}

/**
 * The settings home's header: the account the app is filtered to, else the first, as [SettingsHomeFragment] and the chat
 * list's Profile tab show it.
 */
internal suspend fun settingsAccount(service: XmppConnectionService, resources: Resources): AccountHeader? {
    val first = AccountFilter.selected(service) ?: service.accounts.firstOrNull() ?: return null
    val size = (64 * resources.displayMetrics.density).toInt()
    val avatar = withContext(Dispatchers.IO) { service.avatarService.get(first, size, false) }
    val name = first.displayName ?: first.jid.local ?: first.jid.toString()
    return AccountHeader(name, first.jid.asBareJid().toString(), avatar, UIHelper.getColorForName(name))
}

/** The settings home's rows; [open] gets the row's title, which [SettingsHomeFragment.page] turns into its page. */
internal fun settingsSections(context: Context, open: (Int) -> Unit): List<List<SettingsRow>> {
    fun row(icon: Int, title: Int, summary: Int?, tint: Long) =
        SettingsRow(icon, context.getString(title), summary?.let(context::getString), Color(tint)) { open(title) }
    val showPush = !Strings.isNullOrEmpty(context.getString(R.string.default_push_server))
    return listOf(
        listOf(
            row(R.drawable.ic_palette_24dp, R.string.tatrak_appearance, R.string.tatrak_appearance_summary, 0xFF3E88F7),
            row(R.drawable.ic_touch_app_24dp, R.string.pref_title_interface, R.string.pref_summary_appearance, 0xFF4AA6E0),
            SettingsRow(R.drawable.ic_public_24dp, context.getString(R.string.tatrak_language), appLanguageSummary(context), Color(0xFFA36CD9)) {
                open(R.string.tatrak_language)
            },
        ),
        listOf(
            row(R.drawable.ic_notifications_24dp, R.string.notifications, R.string.pref_notifications_summary, 0xFFEB5545),
            row(R.drawable.ic_privacy_tip_24dp, R.string.pref_privacy, R.string.pref_privacy_summary, 0xFF8E8E93),
            row(R.drawable.ic_security_24dp, R.string.pref_title_security, R.string.pref_summary_security, 0xFF5BB45B),
            row(R.drawable.ic_lightbulb_24dp, R.string.pref_presence_settings, R.string.pref_presence_settings_summary, 0xFFF0A030),
        ),
        listOfNotNull(
            row(R.drawable.ic_attachment_24dp, R.string.pref_attachments, R.string.pref_attachments_summary, 0xFF2CB4C5),
            row(
                R.drawable.ic_settings_ethernet_24dp,
                R.string.pref_connection_options,
                if (ConnectionSettingsFragment.hideChannelDiscovery()) R.string.pref_connection_summary
                else R.string.pref_connection_summary_w_cd,
                0xFF7C6CEB,
            ),
            row(R.drawable.ic_archive_24dp, R.string.backup_and_restore, R.string.pref_backup_summary, 0xFFE57A3C),
            if (showPush) row(R.drawable.ic_cloud_sync_24dp, R.string.unified_push_distributor, R.string.unified_push_summary, 0xFF5E9BD6) else null,
        ),
        listOf(
            SettingsRow(
                R.drawable.ic_new_releases_24dp,
                context.getString(R.string.tatrak_updates),
                context.getString(R.string.tatrak_update_current_version, BuildConfig.VERSION_NAME),
                Color(0xFF34A853),
            ) {
                open(R.string.tatrak_updates)
            },
            SettingsRow(
                R.drawable.ic_help_24dp,
                context.getString(R.string.title_activity_about_x, BuildConfig.APP_NAME),
                null,
                Color(0xFF8E8E93),
            ) {
                context.startActivity(Intent(context, AboutActivity::class.java))
            },
        ),
    )
}
