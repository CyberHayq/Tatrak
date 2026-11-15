package am.cyberhayq.chat.ui.setup

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import eu.siacs.conversations.AppSettings

/**
 * The first-run choice of security defaults. Only existing settings are written (the ones the
 * Security settings screen shows), nothing in the crypto code changes.
 */
enum class SetupMode(
    val key: String,
    /** Blind trust before verification: new OMEMO keys are trusted until verified by hand. */
    val autoTrustOmemo: Boolean,
    /** Whether the system's CA certificates are accepted for server connections. */
    val trustSystemCa: Boolean,
    /** Whether a login fails when the server offers no channel binding (owner, 2026-11-15). */
    val requireChannelBinding: Boolean,
) {
    EASY("easy", autoTrustOmemo = true, trustSystemCa = true, requireChannelBinding = false),
    ADVANCED("advanced", autoTrustOmemo = false, trustSystemCa = false, requireChannelBinding = true);

    fun apply(preferences: SharedPreferences) {
        preferences
            .edit()
            .putBoolean(AppSettings.BLIND_TRUST_BEFORE_VERIFICATION, autoTrustOmemo)
            .putBoolean(AppSettings.TRUST_SYSTEM_CA_STORE, trustSystemCa)
            .putBoolean(AppSettings.REQUIRE_CHANNEL_BINDING, requireChannelBinding)
            .putString(PREF, key)
            .apply()
    }

    companion object {
        const val PREF = "tatrak_setup_mode"

        @JvmStatic
        fun isChosen(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context).contains(PREF)
    }
}
