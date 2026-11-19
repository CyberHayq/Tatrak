package am.cyberhayq.chat.ui.theme

import android.app.Application
import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.Color
import com.google.android.material.R as MaterialR
import eu.siacs.conversations.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The XML overlays BaseActivity applies give classic dialogs and sheets the same surfaces as Compose ([neutralsOf]). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class NightOverlayTest {

    /** An activity themed the way BaseActivity themes it with dynamic colours off. */
    private fun themed(style: TatrakStyle, night: NightBackground): AppCompatActivity {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Conversations3)
        activity.theme.applyStyle(R.style.ThemeOverlay_Tatrak, true)
        night.overlay(style).takeIf { it != 0 }?.let { activity.theme.applyStyle(it, true) }
        activity.theme.applyStyle(TatrakStyle.effectiveAccent(style, null).overlay, true)
        controller.setup()
        return activity
    }

    private fun AppCompatActivity.color(attr: Int): Color {
        val value = TypedValue()
        check(theme.resolveAttribute(attr, value, true))
        return Color(value.data)
    }

    private fun assertSame(style: TatrakStyle, night: NightBackground, dark: Boolean) {
        val n = neutralsOf(style, dark, night, TatrakStyle.effectiveAccent(style, null))!!
        val a = themed(style, night)
        val what = "$style $night dark=$dark"
        assertEquals(what, n.background, a.color(android.R.attr.colorBackground))
        assertEquals(what, n.containerLowest, a.color(MaterialR.attr.colorSurfaceContainerLowest))
        assertEquals(what, n.background, a.color(MaterialR.attr.colorSurfaceContainerLow))
        assertEquals(what, n.container, a.color(MaterialR.attr.colorSurfaceContainer))
        assertEquals(what, n.container, a.color(MaterialR.attr.colorSurfaceContainerHigh))
        assertEquals(what, n.containerHighest, a.color(MaterialR.attr.colorSurfaceContainerHighest))
        assertEquals(what, n.outlineVariant, a.color(MaterialR.attr.colorOutlineVariant))
    }

    @Test
    @Config(qualifiers = "+night")
    fun nightOverlaysMatchCompose() {
        for (night in listOf(NightBackground.GRAPHITE, NightBackground.AMOLED)) assertSame(TatrakStyle.TELEGRAM, night, dark = true)
        for (style in ownSurfaces) for (night in NightBackground.entries) assertSame(style, night, dark = true)
    }

    private val ownSurfaces = listOf(TatrakStyle.SLATE_SEA, TatrakStyle.GLASS, TatrakStyle.WARM)

    /** By day the night overlays equal the style's day surfaces. */
    @Test
    fun dayIsTheStylesOwn() {
        for (style in ownSurfaces) for (night in NightBackground.entries) assertSame(style, night, dark = false)
        // Telegram day has no Compose neutrals: the overlays keep the white containers of LightScheme
        for (night in NightBackground.entries) {
            assertEquals(Color.White, themed(TatrakStyle.TELEGRAM, night).color(MaterialR.attr.colorSurfaceContainer))
        }
    }
}
