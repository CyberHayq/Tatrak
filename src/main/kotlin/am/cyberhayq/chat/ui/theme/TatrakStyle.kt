package am.cyberhayq.chat.ui.theme

import android.content.Context
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.preference.PreferenceManager

/**
 * The selectable visual style: Telegram (default) or "Slate and Sea" (cool slate surfaces, the Sea accent,
 * rounded-square avatars). A plain preference read by key, so the upstream `AppSettings` stays untouched.
 */
enum class TatrakStyle(val key: String) {
    TELEGRAM("telegram"),
    SLATE_SEA("slate_sea");

    companion object {
        const val KEY = "tatrak_style"

        @JvmStatic fun of(key: String?): TatrakStyle = entries.firstOrNull { it.key == key } ?: TELEGRAM

        @JvmStatic fun read(context: Context): TatrakStyle =
            of(PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, null))

        fun set(context: Context, style: TatrakStyle) =
            PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, style.key).apply()

        /** The stored accent key as the accent in effect: Sea while none was ever chosen and the style is Slate and Sea. */
        @JvmStatic fun effectiveAccent(style: TatrakStyle, accentKey: String?): Accent =
            if (accentKey == null && style == SLATE_SEA) Accent.SEA else Accent.of(accentKey)

        @Composable fun remember(): TatrakStyle = of(rememberStringPreference(KEY))
    }
}

/** Shape of the real avatars (not badges, dots or icon circles); a circle unless the style says otherwise. */
val LocalAvatarShape = staticCompositionLocalOf<Shape> { CircleShape }

/** Where the online dot / selection check sits: pushed into the corner of a rounded square. */
val LocalAvatarDotOffset = staticCompositionLocalOf { 0.dp }

internal fun avatarShapeOf(style: TatrakStyle): Shape = if (style == TatrakStyle.SLATE_SEA) RoundedCornerShape(percent = 36) else CircleShape

internal fun avatarDotOffsetOf(style: TatrakStyle): Dp = if (style == TatrakStyle.SLATE_SEA) 3.dp else 0.dp

/** The style in force, provided by [TatrakTheme]; composables read this instead of the preference (one listener, and tests pass `style` to the theme). */
val LocalTatrakStyle = staticCompositionLocalOf { TatrakStyle.TELEGRAM }

/** Manrope in the Slate and Sea style, null (the system font) otherwise; for text that does not inherit the theme's typography. */
val LocalTatrakFont = staticCompositionLocalOf<FontFamily?> { null }
