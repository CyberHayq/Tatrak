package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.theme.TatrakTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** Chat screen colors that [am.cyberhayq.chat.ui.theme.ChatColors] has no slot for. */
@Immutable
data class ChatScreenColors(
    /** Background of received messages that fail the OMEMO trust / session check. */
    val warningBubble: Color,
    val warningText: Color,
    /** Date chips, floating date, service messages: drawn over the wallpaper. */
    val serviceBackground: Color,
    val serviceText: Color,
    val unreadDivider: Color,
    val unreadDividerText: Color,
    val accentIn: Color,
    val accentOut: Color,
    val error: Color,
    val mediaMetaBackground: Color,
    /** Sender names use XEP-0392 colors; lift them over dark bubbles. */
    val isDark: Boolean,
) {
    fun senderName(color: Color): Color = if (isDark) lerp(color, Color.White, 0.4f) else color
}

private val LightScreenColors =
    ChatScreenColors(
        warningBubble = Color(0xFFFFF1C9),
        warningText = Color(0xFF8F5600),
        serviceBackground = Color(0x5C3F5F3A),
        serviceText = Color.White,
        unreadDivider = Color(0xCCFFFFFF),
        unreadDividerText = Color(0xFF4A8BC2),
        accentIn = Color(0xFF3A8DDA),
        accentOut = Color(0xFF59B159),
        error = Color(0xFFE53935),
        mediaMetaBackground = Color(0x80000000),
        isDark = false,
    )

private val DarkScreenColors =
    ChatScreenColors(
        warningBubble = Color(0xFF4A3D12),
        warningText = Color(0xFFFFC94D),
        serviceBackground = Color(0xCC1E2C3A),
        serviceText = Color.White,
        unreadDivider = Color(0xFF1C2734),
        unreadDividerText = Color(0xFF6CB2F0),
        accentIn = Color(0xFF64B5EF),
        accentOut = Color(0xFF5FA6E6),
        error = Color(0xFFEF5350),
        mediaMetaBackground = Color(0x80000000),
        isDark = true,
    )

/** First colour of the preset wallpaper behind the chat; date chips are tinted from it. */
internal val LocalWallpaperTint = staticCompositionLocalOf<Color?> { null }

object ChatScreenTheme {
    val colors: ChatScreenColors
        @Composable
        get() {
            val base =
                if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                    DarkScreenColors
                } else {
                    LightScreenColors
                }
            // the accent picks the bubble icon colours; everything else follows day / night
            val chat = TatrakTheme.chatColors
            val tint = LocalWallpaperTint.current
            return remember(base, chat, chat.accentIn, chat.accentOut, chat.link, tint) {
                base.copy(
                    accentIn = chat.accentIn,
                    accentOut = chat.accentOut,
                    unreadDividerText = chat.link,
                    unreadDivider = chat.nightUnreadDivider ?: base.unreadDivider, // the night backgrounds set it for night only; Slate and Sea for both
                    // Telegram tints the chips from the wallpaper so they don't clash with it
                    serviceBackground =
                        tint?.let {
                            (if (base.isDark) lerp(it, Color.White, 0.18f) else lerp(it, Color.Black, 0.4f))
                                .copy(alpha = 0.75f)
                        }
                            ?: chat.nightServiceBackground ?: base.serviceBackground,
                )
            }
        }
}

/** The colors the background formatter bakes into spans; part of the text cache key. */
@Immutable
data class TextPalette(
    val textIncoming: Int,
    val textOutgoing: Int,
    val secondaryIncoming: Int,
    val secondaryOutgoing: Int,
    val link: Int,
)

@Composable
fun currentTextPalette(): TextPalette {
    val colors = TatrakTheme.chatColors
    return TextPalette(
        textIncoming = colors.textIncoming.toArgb(),
        textOutgoing = colors.textOutgoing.toArgb(),
        secondaryIncoming = colors.timeIncoming.toArgb(),
        secondaryOutgoing = colors.timeOutgoing.toArgb(),
        link = colors.link.toArgb(),
    )
}
