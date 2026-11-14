package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.LocalTatrakFont
import am.cyberhayq.chat.ui.theme.LocalTatrakStyle
import android.app.Application
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Soft bubbles of the Slate and Sea style (design_phase2.md item 2) and the style locals that reach them. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class BubbleShapeTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun radii() {
        assertEquals(17.dp to 6.dp, bubbleRadii(soft = false))
        assertEquals(20.dp to 6.dp, bubbleRadii(soft = true))
    }

    private fun bounds(shape: BubbleShape, w: Float = 200f, h: Float = 60f) =
        shape.createOutline(Size(w, h), LayoutDirection.Ltr, Density(1f))

    @Test
    fun softKeepsTheTailInsetAndGrouping() {
        // incoming: the 6 dp tail inset stays on the left, outgoing on the right
        val incoming = bounds(BubbleShape(false, GroupPosition.SINGLE, soft = true)) as Outline.Rounded
        assertEquals(6f, incoming.roundRect.left, 0.01f)
        assertEquals(200f, incoming.roundRect.right, 0.01f)
        val outgoing = bounds(BubbleShape(true, GroupPosition.SINGLE, soft = true)) as Outline.Rounded
        assertEquals(194f, outgoing.roundRect.right, 0.01f)
        // outgoing, first of a group: the near top corner is the big one, the bottom (tail side) a small plain corner
        val first = (bounds(BubbleShape(true, GroupPosition.FIRST, soft = true)) as Outline.Rounded).roundRect
        assertEquals(20f, first.topRightCornerRadius.x, 0.01f)
        assertEquals(6f, first.bottomRightCornerRadius.x, 0.01f)
        // a middle bubble squeezes the near top corner as well
        val middle = (bounds(BubbleShape(true, GroupPosition.MIDDLE, soft = true)) as Outline.Rounded).roundRect
        assertEquals(6f, middle.topRightCornerRadius.x, 0.01f)
        // corners never exceed half the height
        val flat = (bounds(BubbleShape(false, GroupPosition.SINGLE, soft = true), h = 24f) as Outline.Rounded).roundRect
        assertEquals(12f, flat.topLeftCornerRadius.x, 0.01f)
    }

    @Test
    fun telegramShapeIsStillThePath() {
        assertTrue(bounds(BubbleShape(true, GroupPosition.SINGLE)) is Outline.Generic)
    }

    @Test
    fun themeProvidesStyleAndFont() {
        var style: TatrakStyle? = null
        var font: Any? = null
        compose.setContent { TatrakTheme(darkTheme = false, style = TatrakStyle.SLATE_SEA) { style = LocalTatrakStyle.current; font = LocalTatrakFont.current } }
        compose.waitForIdle()
        assertEquals(TatrakStyle.SLATE_SEA, style)
        assertNotNull(font)
    }

    @Test
    fun telegramHasNoFont() {
        var style: TatrakStyle? = null
        var font: Any? = "unset"
        compose.setContent { TatrakTheme(darkTheme = false, style = TatrakStyle.TELEGRAM) { style = LocalTatrakStyle.current; font = LocalTatrakFont.current } }
        compose.waitForIdle()
        assertEquals(TatrakStyle.TELEGRAM, style)
        assertNull(font)
    }
}
