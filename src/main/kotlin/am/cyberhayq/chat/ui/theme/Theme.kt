package am.cyberhayq.chat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import eu.siacs.conversations.R

/**
 * Colors that Material3 has no slot for: chat bubbles, read checks, unread badges.
 * Day values follow Telegram's default theme; night values are approximate.
 */
@Immutable
data class ChatColors(
    val topBar: Color,
    val onTopBar: Color,
    val onTopBarMuted: Color,
    val drawerHeader: Color,
    val archiveAvatar: Color,
    val chatBackground: Color,
    val bubbleIncoming: Color,
    val bubbleOutgoing: Color,
    val textIncoming: Color,
    val textOutgoing: Color,
    val timeIncoming: Color,
    val timeOutgoing: Color,
    val checkRead: Color,
    val link: Color,
    val unreadBadge: Color,
    val unreadBadgeMuted: Color,
    val online: Color,
    /** Icons and progress in received / sent bubbles (play buttons, file icons). */
    val accentIn: Color,
    val accentOut: Color,
    /** Overrides of a house (night; day for the chips) for the date chips and unread divider; null = default. */
    val nightServiceBackground: Color? = null,
    val nightUnreadDivider: Color? = null,
)

private val LightChatColors =
    ChatColors(
        topBar = Color(0xFFFFFFFF),
        onTopBar = Color(0xFF1A1D21),
        onTopBarMuted = Color(0xFF848688),
        drawerHeader = Color(0xFF598FBA),
        archiveAvatar = Color(0xFFB8C2CC),
        chatBackground = Color(0xFFCBD9C0),
        bubbleIncoming = Color(0xFFFFFFFF),
        bubbleOutgoing = Color(0xFFEFFFDE),
        textIncoming = Color(0xFF000000),
        textOutgoing = Color(0xFF000000),
        timeIncoming = Color(0xFFA1AAB3),
        timeOutgoing = Color(0xFF6EB869),
        checkRead = Color(0xFF46AA36),
        link = Color(0xFF2678B6),
        unreadBadge = Color(0xFF229AF0),
        unreadBadgeMuted = Color(0xFFBEC3C7),
        online = Color(0xFF4BCB1C),
        accentIn = Color(0xFF3A8DDA),
        accentOut = Color(0xFF59B159),
    )

private val DarkChatColors =
    ChatColors(
        topBar = Color(0xFF212D3B),
        onTopBar = Color(0xFFFFFFFF),
        onTopBarMuted = Color(0xFF8D9BA8),
        drawerHeader = Color(0xFF2B3A4A),
        archiveAvatar = Color(0xFF3D4D5D),
        chatBackground = Color(0xFF0E1621),
        bubbleIncoming = Color(0xFF182533),
        bubbleOutgoing = Color(0xFF2B5278),
        textIncoming = Color(0xFFF5F5F5),
        textOutgoing = Color(0xFFF5F5F5),
        timeIncoming = Color(0xFF6D7F8F),
        timeOutgoing = Color(0xFF7DA8D3),
        checkRead = Color(0xFF64B5EF),
        link = Color(0xFF71BAFA),
        unreadBadge = Color(0xFF3E6A99),
        unreadBadgeMuted = Color(0xFF3D4D5D),
        online = Color(0xFF5DC2FF),
        accentIn = Color(0xFF64B5EF),
        accentOut = Color(0xFF5FA6E6),
    )

/** A house's neutral surfaces ([neutralsOf]) over the default day / night colours. */
private fun ChatColors.withNeutrals(n: Neutrals) =
    copy(
        topBar = n.topBar,
        onTopBar = n.onTopBar ?: onTopBar,
        textIncoming = n.text ?: textIncoming,
        textOutgoing = n.text ?: textOutgoing,
        onTopBarMuted = n.onTopBarMuted,
        drawerHeader = n.drawerHeader ?: drawerHeader,
        archiveAvatar = n.archive,
        chatBackground = n.chatBackground,
        bubbleIncoming = n.bubbleIn,
        timeIncoming = n.timeIn,
        unreadBadgeMuted = n.archive,
        online = n.online ?: online,
        nightServiceBackground = n.service,
        nightUnreadDivider = n.divider,
    )

private val LightScheme: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF298ACF),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF527DA3),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFF65A9E0),
        background = Color.White,
        surface = Color.White,
        onSurface = Color(0xFF1A1D21),
        surfaceVariant = Color(0xFFF1F1F1),
        onSurfaceVariant = Color(0xFF75787A),
        outlineVariant = Color(0xFFD9D9D9),
        // menus, dialogs and sheets draw on these; Material's defaults are lavender
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color.White,
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color.White,
        surfaceContainerHighest = Color(0xFFF1F1F1),
    )

private val DarkScheme: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF5DA7E8),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF212D3B),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFF5DA7E8),
        background = Color(0xFF1D2733),
        surface = Color(0xFF1D2733),
        surfaceVariant = Color(0xFF242F3D),
        onSurfaceVariant = Color(0xFF7F8B96),
        outlineVariant = Color(0xFF10171F),
        surfaceContainerLowest = Color(0xFF17202B),
        surfaceContainerLow = Color(0xFF1D2733),
        surfaceContainer = Color(0xFF242F3D),
        surfaceContainerHigh = Color(0xFF242F3D),
        surfaceContainerHighest = Color(0xFF2B3746),
    )

private fun ColorScheme.withNeutrals(n: Neutrals) =
    copy(
        background = n.background,
        surface = n.background,
        // the style's own text colour, as its bubbles and XML screens use; null keeps the scheme's
        onBackground = n.text ?: onBackground,
        onSurface = n.text ?: onSurface,
        surfaceVariant = n.surfaceVariant,
        onSurfaceVariant = n.onSurfaceVariant,
        outlineVariant = n.outlineVariant,
        surfaceContainerLowest = n.containerLowest,
        surfaceContainerLow = n.background,
        surfaceContainer = n.container,
        surfaceContainerHigh = n.container,
        surfaceContainerHighest = n.containerHighest,
    )

/** Glyphs on an accent fill: white, or near-black on a light fill (dynamic tone-80 colours, Gold gold, Sea at night). */
fun contentOn(fill: Color): Color = if (fill.luminance() > 0.45f) Color(0xFF1A1A1A) else Color.White

val LocalChatColors = staticCompositionLocalOf { LightChatColors }

/**
 * Neutral colours stay fixed (Telegram day / night) except for a house's own surfaces; [accent] tints the rest, or the system's
 * wallpaper colours when [dynamicColors] is on (Android 12+).
 */
@Composable
fun TatrakTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    style: TatrakStyle = TatrakStyle.remember(),
    accent: Accent = rememberAccent(style),
    dynamicColors: Boolean = rememberDynamicColors(),
    elegant: Boolean = ElegantDark.remember(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamic = dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors =
        remember(context, accent, darkTheme, dynamicColors) {
            if (dynamic) dynamicAccent(context, darkTheme) else resolveAccent(context, accent, darkTheme)
        }
    // a house's own surfaces belong to the chosen accent, not to the system's dynamic colours
    val neutrals = if (dynamic) null else neutralsOf(style, accent, darkTheme, elegant)
    val chatColors =
        remember(colors, darkTheme, neutrals) {
            (if (darkTheme) DarkChatColors else LightChatColors).let { base ->
                neutrals?.let(base::withNeutrals) ?: base
            }.copy(
                drawerHeader =
                    neutrals?.drawerHeader ?: if (darkTheme) DarkChatColors.drawerHeader else colors.primaryContainer,
                bubbleOutgoing = colors.bubbleOutgoing,
                timeOutgoing = colors.timeOutgoing,
                checkRead = colors.checkRead,
                link = colors.link,
                unreadBadge = colors.unreadBadge,
                accentIn = colors.accentIn,
                accentOut = colors.accentOut,
            )
        }
    val scheme =
        remember(colors, darkTheme, neutrals) {
            (if (darkTheme) DarkScheme else LightScheme).let { base -> neutrals?.let(base::withNeutrals) ?: base }.copy(
                primary = colors.primary,
                onPrimary = colors.onPrimary,
                primaryContainer = colors.primaryContainer,
                onPrimaryContainer = colors.onPrimaryContainer,
                secondary = colors.secondary,
            )
        }
    val font = if (style == TatrakStyle.SLATE_SEA) ManropeFamily else null
    CompositionLocalProvider(
        LocalChatColors provides chatColors,
        LocalAvatarShape provides avatarShapeOf(style),
        LocalAvatarDotOffset provides avatarDotOffsetOf(style),
        LocalTatrakStyle provides style,
        LocalTatrakFont provides font,
    ) {
        if (font == null) {
            MaterialTheme(colorScheme = scheme, shapes = menuShapes, content = content)
        } else {
            MaterialTheme(colorScheme = scheme, typography = remember { slateTypography(font) }, shapes = menuShapes, content = content)
        }
    }
}

private val ManropeFamily =
    FontFamily(
        Font(R.font.tatrak_manrope_regular, FontWeight.Normal),
        Font(R.font.tatrak_manrope_medium, FontWeight.Medium),
        Font(R.font.tatrak_manrope_semibold, FontWeight.SemiBold),
        Font(R.font.tatrak_manrope_bold, FontWeight.Bold),
    )

/** Material's default typography with only the family changed, so no size, spacing or line height moves. */
internal fun slateTypography(font: FontFamily): Typography =
    Typography().run {
        copy(
            displayLarge = displayLarge.copy(fontFamily = font),
            displayMedium = displayMedium.copy(fontFamily = font),
            displaySmall = displaySmall.copy(fontFamily = font),
            headlineLarge = headlineLarge.copy(fontFamily = font),
            headlineMedium = headlineMedium.copy(fontFamily = font),
            headlineSmall = headlineSmall.copy(fontFamily = font),
            titleLarge = titleLarge.copy(fontFamily = font),
            titleMedium = titleMedium.copy(fontFamily = font),
            titleSmall = titleSmall.copy(fontFamily = font),
            bodyLarge = bodyLarge.copy(fontFamily = font),
            bodyMedium = bodyMedium.copy(fontFamily = font),
            bodySmall = bodySmall.copy(fontFamily = font),
            labelLarge = labelLarge.copy(fontFamily = font),
            labelMedium = labelMedium.copy(fontFamily = font),
            labelSmall = labelSmall.copy(fontFamily = font),
        )
    }

object TatrakTheme {
    val chatColors: ChatColors
        @Composable get() = LocalChatColors.current
}

// DropdownMenu and other small surfaces use extraSmall: rounded like Telegram's popups instead of Material's 4 dp
private val menuShapes = Shapes(extraSmall = RoundedCornerShape(14.dp))
