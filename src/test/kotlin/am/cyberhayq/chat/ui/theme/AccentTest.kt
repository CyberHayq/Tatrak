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

    @Test
    fun blueKeepsTheColoursTheAppShippedWith() {
        assertEquals(Color(0xFF298ACF), resolveAccent(context, Accent.BLUE, dark = false).primary)
        assertEquals(Color(0xFFEFFFDE), resolveAccent(context, Accent.BLUE, dark = false).bubbleOutgoing)
        assertEquals(Color(0xFF2B5278), resolveAccent(context, Accent.BLUE, dark = true).bubbleOutgoing)
        assertEquals(Accent.BLUE, Accent.of(null))
        assertEquals(Accent.GREEN, Accent.of("green"))
    }

    /** White stays on the shipped night badge; a pastel dynamic fill (tone 80) gets a dark glyph. */
    @Test
    fun glyphsStayReadableOnLightFills() {
        assertEquals(Color.White, contentOn(Color(0xFF3E6A99)))
        assertEquals(Color(0xFF1A1A1A), contentOn(Color(0xFFA8C8FF)))
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
