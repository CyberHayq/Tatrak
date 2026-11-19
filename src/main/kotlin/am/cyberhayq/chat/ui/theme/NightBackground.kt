package am.cyberhayq.chat.ui.theme

import android.content.Context
import androidx.annotation.StyleRes
import androidx.compose.runtime.Composable
import androidx.preference.PreferenceManager
import eu.siacs.conversations.R

/**
 * The night surfaces, separate from the accent: blue-grey (default), graphite or AMOLED black; the day is not affected.
 * A plain preference read by key, so the upstream `AppSettings` stays untouched. Dynamic colours win over it.
 */
enum class NightBackground(val key: String) {
    BLUE_GREY("blue_grey"),
    GRAPHITE("graphite"),
    AMOLED("amoled");

    /** The XML neutrals overlay `BaseActivity` applies under the accent (equal to the style's day neutrals by day); 0 = none. */
    @StyleRes
    fun overlay(style: TatrakStyle): Int =
        when (style) {
            TatrakStyle.TELEGRAM -> pick(0, R.style.ThemeOverlay_Tatrak_Graphite, R.style.ThemeOverlay_Tatrak_Amoled)
            TatrakStyle.SLATE_SEA ->
                pick(R.style.ThemeOverlay_Tatrak_SlateSea, R.style.ThemeOverlay_Tatrak_SlateSea_Graphite, R.style.ThemeOverlay_Tatrak_SlateSea_Amoled)
            // Tinted Glass tints Compose only; its XML screens take the neutral Glass surfaces (owner, 2026-11-19)
            TatrakStyle.GLASS, TatrakStyle.TINTED ->
                pick(R.style.ThemeOverlay_Tatrak_Glass, R.style.ThemeOverlay_Tatrak_Glass_Graphite, R.style.ThemeOverlay_Tatrak_Glass_Amoled)
            TatrakStyle.WARM -> pick(R.style.ThemeOverlay_Tatrak_Warm, R.style.ThemeOverlay_Tatrak_Warm_Graphite, R.style.ThemeOverlay_Tatrak_Warm_Amoled)
        }

    private fun pick(blueGrey: Int, graphite: Int, amoled: Int): Int =
        when (this) {
            BLUE_GREY -> blueGrey
            GRAPHITE -> graphite
            AMOLED -> amoled
        }

    companion object {
        const val KEY = "tatrak_night_background"

        @JvmStatic fun of(key: String?): NightBackground = entries.firstOrNull { it.key == key } ?: BLUE_GREY

        @JvmStatic fun read(context: Context): NightBackground =
            of(PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, null))

        fun set(context: Context, value: NightBackground) =
            PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, value.key).apply()

        @Composable fun remember(): NightBackground = of(rememberStringPreference(KEY))
    }
}
