package am.cyberhayq.chat.ui.theme

import android.app.Application
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Precedence of the style, houses and the elegant night (see knowledge/egm/style-slate-sea/design_draft.md). */
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
        assertNull(neutralsOf(TatrakStyle.TELEGRAM, Accent.BLUE, dark = false, elegant = false))
        assertNull(neutralsOf(TatrakStyle.TELEGRAM, Accent.BLUE, dark = true, elegant = false))
    }

    @Test
    fun slateAppliesToOrdinaryAccents() {
        assertEquals(slateDayBg, neutralsOf(TatrakStyle.SLATE_SEA, Accent.PINK, dark = false, elegant = false)!!.background)
        assertEquals(slateNightBg, neutralsOf(TatrakStyle.SLATE_SEA, Accent.SEA, dark = true, elegant = false)!!.background)
    }

    @Test
    fun houseWinsOverSlate() {
        for (dark in listOf(false, true)) {
            val house = neutralsOf(TatrakStyle.SLATE_SEA, Accent.STEEL, dark, elegant = false)!!
            assertNotEquals(if (dark) slateNightBg else slateDayBg, house.background)
            assertEquals(neutralsOf(TatrakStyle.TELEGRAM, Accent.STEEL, dark, elegant = false)!!.background, house.background)
        }
        assertNotEquals(slateNightBg, neutralsOf(TatrakStyle.SLATE_SEA, Accent.EMBER, dark = true, elegant = false)!!.background)
    }

    @Test
    fun emberHasNoDayNeutralsSoSlateShows() {
        assertNull(neutralsOf(TatrakStyle.TELEGRAM, Accent.EMBER, dark = false, elegant = false))
        assertEquals(slateDayBg, neutralsOf(TatrakStyle.SLATE_SEA, Accent.EMBER, dark = false, elegant = false)!!.background)
    }

    @Test
    fun slateHasItsOwnElegantNight() {
        val elegant = neutralsOf(TatrakStyle.TELEGRAM, Accent.BLUE, dark = true, elegant = true)!!
        val slate = neutralsOf(TatrakStyle.SLATE_SEA, Accent.BLUE, dark = true, elegant = true)!!
        assertNotEquals(elegant.background, slate.background)
        assertNotEquals(slateNightBg, slate.background)
        // elegant is a night style only, and a house still wins over it
        assertNull(neutralsOf(TatrakStyle.TELEGRAM, Accent.BLUE, dark = false, elegant = true))
        assertEquals(slateDayBg, neutralsOf(TatrakStyle.SLATE_SEA, Accent.BLUE, dark = false, elegant = true)!!.background)
        assertEquals(
            neutralsOf(TatrakStyle.TELEGRAM, Accent.STEEL, dark = true, elegant = false)!!.background,
            neutralsOf(TatrakStyle.SLATE_SEA, Accent.STEEL, dark = true, elegant = true)!!.background,
        )
    }

    @Test
    fun editorPalettesCarryNightAndDaySurfacesOverStyleAndElegant() {
        for (palette in listOf(Accent.ONEDARK, Accent.DRACULA)) for (dark in listOf(false, true)) {
            val own = neutralsOf(TatrakStyle.TELEGRAM, palette, dark, elegant = false)!!
            assertEquals(own.background, neutralsOf(TatrakStyle.SLATE_SEA, palette, dark, elegant = false)!!.background)
            assertEquals(own.background, neutralsOf(TatrakStyle.SLATE_SEA, palette, dark, elegant = true)!!.background)
            assertNotEquals(if (dark) slateNightBg else slateDayBg, own.background)
        }
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
            TatrakTheme(darkTheme = true, style = TatrakStyle.SLATE_SEA, accent = Accent.SEA, dynamicColors = true) {
                chat = TatrakTheme.chatColors
                shape = LocalAvatarShape.current
                Text("x")
            }
        }
        compose.waitForIdle()
        assertNotNull(chat)
        assertNotEquals(slateNightBg, chat!!.chatBackground)
        assertNotEquals(CircleShape, shape)
    }
}
