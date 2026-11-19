package am.cyberhayq.chat.ui.theme

import am.cyberhayq.chat.ui.chat.floatingPill

import android.app.Application
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Precedence of the style and the night background (see knowledge/egm/style-slate-sea/design_draft.md and theming.md). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class TatrakStyleTest {

    @get:Rule val compose = createComposeRule()

    private val slateDayBg = Color(0xFFEEF1F4)
    private val slateNightBg = Color(0xFF0F1316)

    @Test
    fun styleDefaultsToTelegram() {
        assertEquals(TatrakStyle.TELEGRAM, TatrakStyle.read(ApplicationProvider.getApplicationContext()))
        assertEquals(TatrakStyle.TELEGRAM, TatrakStyle.of("nonsense"))
        assertNull(neutralsOf(TatrakStyle.TELEGRAM, dark = false, NightBackground.BLUE_GREY, Accent.BLUE))
        assertNull(neutralsOf(TatrakStyle.TELEGRAM, dark = true, NightBackground.BLUE_GREY, Accent.BLUE))
        assertEquals(NightBackground.BLUE_GREY, NightBackground.read(ApplicationProvider.getApplicationContext()))
        assertEquals(NightBackground.BLUE_GREY, NightBackground.of("nonsense"))
        assertEquals(NightBackground.AMOLED, NightBackground.of("amoled"))
    }

    /** The FAB fill takes a white pencil at 4.5:1 or more (luminance <= 0.1833); an accent dark enough stays as it is. */
    @Test
    fun fabFillTakesWhiteAtFourAndAHalfToOne() {
        for (accent in listOf(Color(0xFF5DA7E8), Color(0xFFE1BC5D), Color(0xFFFFFFFF), Color(0xFF56BCC8))) {
            assertTrue(fabFill(accent).luminance() <= 0.1833f)
        }
        assertEquals(Color(0xFF247BB8), fabFill(Color(0xFF247BB8)))
    }

    /** Unset, the avatar shape follows the style; a stored choice wins in either style. */
    @Test
    fun avatarShapeFollowsStyleUntilChosen() {
        assertEquals(AvatarShape.ROUND, AvatarShape.effective(TatrakStyle.TELEGRAM, null))
        assertEquals(AvatarShape.SQUARE, AvatarShape.effective(TatrakStyle.SLATE_SEA, null))
        assertEquals(AvatarShape.ROUND, AvatarShape.effective(TatrakStyle.SLATE_SEA, "round"))
        assertEquals(AvatarShape.SQUARE, AvatarShape.effective(TatrakStyle.TELEGRAM, "square"))
        assertEquals(CircleShape, avatarShapeOf(AvatarShape.ROUND))
    }

    /** Unset, floating bars follow the style (on for the Glass styles); a stored choice wins in every style. */
    @Test
    fun floatingBarsFollowStyleUntilChosen() {
        for (style in TatrakStyle.entries) {
            val glass = style == TatrakStyle.GLASS || style == TatrakStyle.WARM || style == TatrakStyle.TINTED
            assertEquals(style.key, glass, FloatingBars.effective(style, null))
            assertTrue(FloatingBars.effective(style, true))
            assertFalse(FloatingBars.effective(style, false))
        }
    }

    /**
     * barAlpha() as check_contrast.py reports it: Telegram's day bar stays opaque (its muted text has no headroom), Glass's is
     * 0.96; frosted Glass over its own content 0.88 by day and 0.74 under the AMOLED night; clear is 0.72 everywhere.
     */
    @Test
    fun floatingBarAlpha() {
        val colors = mutableMapOf<String, Triple<ChatColors, Color, Color>>()
        compose.setContent {
            for (style in listOf(TatrakStyle.TELEGRAM, TatrakStyle.GLASS)) {
                TatrakTheme(darkTheme = false, style = style, accent = Accent.BLUE, dynamicColors = false) {
                    colors[style.key] = Triple(TatrakTheme.chatColors, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.background)
                }
            }
            TatrakTheme(darkTheme = true, style = TatrakStyle.GLASS, accent = Accent.BLUE, dynamicColors = false, nightBackground = NightBackground.AMOLED) {
                colors["amoled"] = Triple(TatrakTheme.chatColors, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.background)
            }
        }
        compose.waitForIdle()
        fun alpha(key: String, blur: BarBlur) = colors.getValue(key).let { (c, primary, background) -> barAlpha(c, primary, background, blur) }
        assertEquals(1f, alpha(TatrakStyle.TELEGRAM.key, BarBlur.OFF), 0.001f)
        assertEquals(0.96f, alpha(TatrakStyle.GLASS.key, BarBlur.OFF), 0.001f)
        assertEquals(0.88f, alpha(TatrakStyle.GLASS.key, BarBlur.FROSTED), 0.001f)
        assertEquals(0.74f, alpha("amoled", BarBlur.FROSTED), 0.001f)
        assertEquals(0.72f, alpha("amoled", BarBlur.CLEAR), 0.001f)
        assertEquals(0.72f, alpha(TatrakStyle.TELEGRAM.key, BarBlur.CLEAR), 0.001f)
    }

    /** "Blur under bars": unset or unknown is frosted; below API 31 (no RenderEffect) it is always off. */
    @Test
    fun barBlurNeedsApi31() {
        assertEquals(BarBlur.FROSTED, BarBlur.effective(null, sdk = 31))
        assertEquals(BarBlur.FROSTED, BarBlur.effective("nonsense", sdk = 35))
        assertEquals(BarBlur.FROSTED, BarBlur.effective("frosted", sdk = 35))
        assertEquals(BarBlur.CLEAR, BarBlur.effective("clear", sdk = 35))
        assertEquals(BarBlur.OFF, BarBlur.effective("off", sdk = 35))
        assertEquals(BarBlur.OFF, BarBlur.effective("clear", sdk = 30))
        assertEquals(BarBlur.OFF, BarBlur.effective(null, sdk = 30))
        // never set: frosted (API 35 here)
        var read = BarBlur.OFF
        compose.setContent { read = BarBlur.remember() }
        compose.waitForIdle()
        assertEquals(BarBlur.FROSTED, read)
    }

    /** The chat's floating composer pill as check_contrast.py reports it: 0.98 in Telegram's blue-grey night (the recording dot), else 0.96. */
    @Test
    fun floatingComposerAlpha() {
        val alphas = mutableMapOf<TatrakStyle, Float>()
        compose.setContent {
            TatrakTheme(darkTheme = true, style = TatrakStyle.TELEGRAM, accent = Accent.BLUE, dynamicColors = false, nightBackground = NightBackground.BLUE_GREY) {
                alphas[TatrakStyle.TELEGRAM] = floatingPill().alpha
            }
            TatrakTheme(darkTheme = false, style = TatrakStyle.GLASS, accent = Accent.BLUE, dynamicColors = false) {
                alphas[TatrakStyle.GLASS] = floatingPill().alpha
                alphas[TatrakStyle.WARM] = floatingPill(BarBlur.FROSTED).alpha // Glass, frosted
            }
        }
        compose.waitForIdle()
        // alpha is kept in 8 bits
        assertEquals(0.98f, alphas.getValue(TatrakStyle.TELEGRAM), 0.003f)
        assertEquals(0.96f, alphas.getValue(TatrakStyle.GLASS), 0.003f)
        assertEquals(0.88f, alphas.getValue(TatrakStyle.WARM), 0.003f)
    }

    /** The night background only changes the night; by day each style keeps its one surface set. */
    @Test
    fun nightBackgroundLeavesTheDayAlone() {
        for (night in NightBackground.entries) {
            assertNull(neutralsOf(TatrakStyle.TELEGRAM, dark = false, night, Accent.BLUE))
            assertEquals(slateDayBg, neutralsOf(TatrakStyle.SLATE_SEA, dark = false, night, Accent.BLUE)!!.background)
        }
    }

    @Test
    fun eachStyleHasThreeNights() {
        assertEquals(slateNightBg, neutralsOf(TatrakStyle.SLATE_SEA, dark = true, NightBackground.BLUE_GREY, Accent.BLUE)!!.background)
        assertEquals(Color(0xFF121214), neutralsOf(TatrakStyle.TELEGRAM, dark = true, NightBackground.GRAPHITE, Accent.BLUE)!!.background)
        assertEquals(Color(0xFF111315), neutralsOf(TatrakStyle.SLATE_SEA, dark = true, NightBackground.GRAPHITE, Accent.BLUE)!!.background)
        for (style in TatrakStyle.entries) {
            val amoled = neutralsOf(style, dark = true, NightBackground.AMOLED, Accent.BLUE)!!
            assertEquals(Color.Black, amoled.background)
            assertEquals(Color.Black, amoled.chatBackground)
            assertNotEquals(Color.Black, amoled.bubbleIn)
        }
        // Slate and Sea keeps its own online green on black
        assertEquals(Color(0xFF4CC38A), neutralsOf(TatrakStyle.SLATE_SEA, dark = true, NightBackground.AMOLED, Accent.BLUE)!!.online)
    }

    /** BaseActivity applies these XML overlays, so the classic screens match the Compose surfaces above. */
    @Test
    fun xmlOverlayFollowsStyleAndNight() {
        assertEquals(0, NightBackground.BLUE_GREY.overlay(TatrakStyle.TELEGRAM))
        assertEquals(R.style.ThemeOverlay_Tatrak_Graphite, NightBackground.GRAPHITE.overlay(TatrakStyle.TELEGRAM))
        assertEquals(R.style.ThemeOverlay_Tatrak_Amoled, NightBackground.AMOLED.overlay(TatrakStyle.TELEGRAM))
        assertEquals(R.style.ThemeOverlay_Tatrak_SlateSea, NightBackground.BLUE_GREY.overlay(TatrakStyle.SLATE_SEA))
        assertEquals(R.style.ThemeOverlay_Tatrak_SlateSea_Graphite, NightBackground.GRAPHITE.overlay(TatrakStyle.SLATE_SEA))
        assertEquals(R.style.ThemeOverlay_Tatrak_SlateSea_Amoled, NightBackground.AMOLED.overlay(TatrakStyle.SLATE_SEA))
    }

    /** The Glass styles (2026-11-19): stored keys, Telegram's avatars and accent, Glass overlays for Tinted's XML screens. */
    @Test
    fun glassStyles() {
        assertEquals(TatrakStyle.GLASS, TatrakStyle.of("glass"))
        assertEquals(TatrakStyle.WARM, TatrakStyle.of("warm"))
        assertEquals(TatrakStyle.TINTED, TatrakStyle.of("tinted"))
        for (style in listOf(TatrakStyle.GLASS, TatrakStyle.WARM, TatrakStyle.TINTED)) {
            assertEquals(AvatarShape.ROUND, AvatarShape.effective(style, null))
            assertEquals(Accent.BLUE, TatrakStyle.effectiveAccent(style, null))
            assertNotNull(neutralsOf(style, dark = false, NightBackground.BLUE_GREY, Accent.BLUE))
        }
        for (night in NightBackground.entries) assertEquals(night.overlay(TatrakStyle.GLASS), night.overlay(TatrakStyle.TINTED))
        assertEquals(R.style.ThemeOverlay_Tatrak_Warm_Amoled, NightBackground.AMOLED.overlay(TatrakStyle.WARM))
        assertEquals(Color(0xFFF2F2F7), neutralsOf(TatrakStyle.GLASS, dark = false, NightBackground.AMOLED, Accent.PINK)!!.background)
    }

    /** Tinted Glass has one surface set per accent; AMOLED keeps black grounds and tints only the raised layers. */
    @Test
    fun tintedFollowsTheAccent() {
        for (dark in listOf(false, true)) for (night in NightBackground.entries) {
            val blue = neutralsOf(TatrakStyle.TINTED, dark, night, Accent.BLUE)!!
            val pink = neutralsOf(TatrakStyle.TINTED, dark, night, Accent.PINK)!!
            if (dark && night == NightBackground.AMOLED) {
                for (n in listOf(blue, pink)) {
                    assertEquals(Color.Black, n.background)
                    assertEquals(Color.Black, n.chatBackground)
                }
                assertNotEquals(blue.bubbleIn, pink.bubbleIn)
            } else {
                assertNotEquals("dark=$dark $night", blue.background, pink.background)
            }
        }
        for (accent in Accent.entries) assertNotNull(neutralsOf(TatrakStyle.TINTED, dark = true, NightBackground.GRAPHITE, accent))
    }

    @Test
    fun unsetAccentIsSeaOnlyUnderSlate() {
        assertEquals(Accent.SEA, TatrakStyle.effectiveAccent(TatrakStyle.SLATE_SEA, null))
        assertEquals(Accent.BLUE, TatrakStyle.effectiveAccent(TatrakStyle.TELEGRAM, null))
        // a stored choice, even Blue, is never replaced
        assertEquals(Accent.BLUE, TatrakStyle.effectiveAccent(TatrakStyle.SLATE_SEA, "blue"))
        assertEquals(Accent.PINK, TatrakStyle.effectiveAccent(TatrakStyle.SLATE_SEA, "pink"))
    }

    @Test
    fun dynamicColorsDropNeutralsButKeepAvatarShape() {
        var chat: ChatColors? = null
        var shape: Any? = null
        compose.setContent {
            TatrakTheme(darkTheme = true, style = TatrakStyle.SLATE_SEA, accent = Accent.SEA, dynamicColors = true, nightBackground = NightBackground.AMOLED) {
                chat = TatrakTheme.chatColors
                shape = LocalAvatarShape.current
                Text("x")
            }
        }
        compose.waitForIdle()
        assertNotNull(chat)
        assertNotEquals(slateNightBg, chat!!.chatBackground)
        assertNotEquals(Color.Black, chat!!.chatBackground)
        assertNotEquals(CircleShape, shape)
    }
}
