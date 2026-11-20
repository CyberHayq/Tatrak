package eu.siacs.conversations.ui.fragment.settings

import am.cyberhayq.chat.update.UpdateActivity
import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import eu.siacs.conversations.BuildConfig
import eu.siacs.conversations.R

/** Tatrak: the automatic update check and "Check now" (am.cyberhayq.chat.update.Updater). */
class UpdatesSettingsFragment : XmppPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.tatrak_preferences_updates, rootKey)
        findPreference<Preference>("tatrak_update_check_now")?.apply {
            summary = getString(R.string.tatrak_update_current_version, BuildConfig.VERSION_NAME)
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), UpdateActivity::class.java).putExtra(UpdateActivity.EXTRA_CHECK, true))
                true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.tatrak_updates)
    }
}
