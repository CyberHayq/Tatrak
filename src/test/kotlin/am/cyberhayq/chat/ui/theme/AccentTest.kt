package am.cyberhayq.chat.ui.theme

import android.app.Application
import androidx.compose.ui.graphics.Color
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class AccentTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun everyAccentResolvesInBothModes() {
        for (accent in Accent.entries) {
            val day = resolveAccent(context, accent, dark = false)
            val night = resolveAccent(context, accent, dark = true)
            assertNotEquals(accent.name, day.primary, night.primary)
        }
    }

    /** Blue keeps the shipped bubbles and badge; its day primary is darkened only as far as white button text needs (4.5:1). */
    @Test
    fun blueKeepsTheColoursTheAppShippedWith() {
        assertEquals(Color(0xFF247BB8), resolveAccent(context, Accent.BLUE, dark = false).primary)
        assertEquals(Color(0xFFEFFFDE), resolveAccent(context, Accent.BLUE, dark = false).bubbleOutgoing)
        assertEquals(Color(0xFF229AF0), resolveAccent(context, Accent.BLUE, dark = false).unreadBadge)
        assertEquals(Color(0xFF2B5278), resolveAccent(context, Accent.BLUE, dark = true).bubbleOutgoing)
        assertEquals(Accent.BLUE, Accent.of(null))
        assertEquals(Accent.GREEN, Accent.of("green"))
    }

    /** Nine ordinary accents; keys of the removed signature and editor themes fall back to blue (no migration). */
    @Test
    fun nineAccentsAndRemovedKeysFallBackToBlue() {
        assertEquals(
            listOf("blue", "cyan", "sea", "green", "violet", "pink", "orange", "red", "gold"),
            Accent.entries.map { it.key },
        )
        for (removed in listOf("ember", "steel", "crimson", "teal", "forest", "copper", "onedark", "dracula")) {
            assertEquals(Accent.BLUE, Accent.of(removed))
        }
    }

    /** White stays on the shipped night badge; a pastel dynamic fill (tone 80) gets a dark glyph. */
    @Test
    fun glyphsStayReadableOnLightFills() {
        assertEquals(Color.White, contentOn(Color(0xFF3E6A99)))
        assertEquals(Color(0xFF1A1A1A), contentOn(Color(0xFFA8C8FF)))
        // the shipped day badge keeps white (3.0:1); the lighter night blue takes a dark glyph instead of white at 2.6:1
        assertEquals(Color.White, contentOn(Color(0xFF229AF0)))
        assertEquals(Color(0xFF1A1A1A), contentOn(Color(0xFF5DA7E8)))
    }

    /** Issue #4: a maker's palette with light text on a light outgoing bubble falls back to a dark glyph; readable pairs stay. */
    @Test
    fun outgoingTextStaysReadableOnAMakersPalette() {
        assertEquals(Color(0xFF1A1A1A), readableOn(Color(0xFFB9CBFF), Color(0xFFF5F5F5)))
        assertEquals(Color.Black, readableOn(Color(0xFFB9CBFF), Color.Black))
        assertEquals(Color(0xFFF5F5F5), readableOn(Color(0xFF445274), Color(0xFFF5F5F5)))
        assertEquals(Color.White, readableOn(Color(0xFF445274), Color(0xFF5A6A90)))
    }

    /** A stored "on" restored onto Android 11 must not lock the accent picker (no dynamic colours there). */
    @Test
    @Config(sdk = [30])
    fun dynamicColorsNeedTheSystemToHaveThem() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(eu.siacs.conversations.AppSettings.DYNAMIC_COLORS, true).commit()
        org.junit.Assert.assertFalse(eu.siacs.conversations.AppSettings(context).isDynamicColorsDesired)
    }
}
