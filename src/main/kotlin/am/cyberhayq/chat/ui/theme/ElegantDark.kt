package am.cyberhayq.chat.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.preference.PreferenceManager

/**
 * The opt-in "elegant dark" night style (charcoal layers, off-white text). A plain preference
 * read by key, so the upstream `AppSettings` stays untouched; off by default.
 */
object ElegantDark {
    const val KEY = "tatrak_elegant_dark"

    @JvmStatic
    fun enabled(context: Context) = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false)

    fun set(context: Context, value: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, value).apply()

    @Composable
    fun remember() = rememberPreference(KEY) { it.getBoolean(KEY, false) }
}
