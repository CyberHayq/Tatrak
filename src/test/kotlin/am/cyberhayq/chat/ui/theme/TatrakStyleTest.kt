package am.cyberhayq.chat.ui.theme

import android.app.Application
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.R
import org.junit.Assert.assertEquals
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
        assertNull(neutralsOf(TatrakStyle.TELEGRAM, dark = false, NightBackground.BLUE_GREY))
        assertNull(neutralsOf(TatrakStyle.TELEGRAM, dark = true, NightBackground.BLUE_GREY))
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

    /** The night background only changes the night; by day each style keeps its one surface set. */
    @Test
    fun nightBackgroundLeavesTheDayAlone() {
        for (night in NightBackground.entries) {
            assertNull(neutralsOf(TatrakStyle.TELEGRAM, dark = false, night))
            assertEquals(slateDayBg, neutralsOf(TatrakStyle.SLATE_SEA, dark = false, night)!!.background)
        }
    }

    @Test
    fun eachStyleHasThreeNights() {
        assertEquals(slateNightBg, neutralsOf(TatrakStyle.SLATE_SEA, dark = true, NightBackground.BLUE_GREY)!!.background)
        assertEquals(Color(0xFF121214), neutralsOf(TatrakStyle.TELEGRAM, dark = true, NightBackground.GRAPHITE)!!.background)
        assertEquals(Color(0xFF111315), neutralsOf(TatrakStyle.SLATE_SEA, dark = true, NightBackground.GRAPHITE)!!.background)
        for (style in TatrakStyle.entries) {
            val amoled = neutralsOf(style, dark = true, NightBackground.AMOLED)!!
            assertEquals(Color.Black, amoled.background)
            assertEquals(Color.Black, amoled.chatBackground)
            assertNotEquals(Color.Black, amoled.bubbleIn)
        }
        // Slate and Sea keeps its own online green on black
        assertEquals(Color(0xFF4CC38A), neutralsOf(TatrakStyle.SLATE_SEA, dark = true, NightBackground.AMOLED)!!.online)
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
