package am.cyberhayq.chat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The composer's bottom space while the keyboard, and the emoji or attach panel take turns. */
class KeyboardSpaceTest {

    private val nav = 48
    private val keyboard = 800

    /** Content height plus bottom space: the composer's top edge stays where it is when this does. */
    private fun total(ime: Int, panel: Int) = panel + composerBottomPx(ime, nav, panel)

    @Test
    fun keyboardAloneIsPaddedAsBefore() {
        assertEquals(nav, composerBottomPx(0, nav, 0))
        assertEquals(nav + keyboard, composerBottomPx(nav + keyboard, nav, 0))
    }

    @Test
    fun keyboardSlidesUnderTheEmojiPanelWithoutMovingTheComposer() {
        val before = total(nav + keyboard, panel = 0)
        // every frame of the keyboard going away and coming back, with the panel shown
        for (ime in nav + keyboard downTo 0 step 37) assertEquals(before, total(ime, panel = keyboard))
        // the panel is dropped once the keyboard is fully up
        assertEquals(before, total(nav + keyboard, panel = 0))
    }

    @Test
    fun aTallerKeyboardStillPushesAboveThePanel() {
        assertEquals(nav + 900, total(nav + 900, panel = keyboard))
    }

    @Test
    fun settledOnlyWhenTheKeyboardIsUpAndHasArrived() {
        // asked for, animation not started yet
        assertFalse(keyboardSettled(ime = nav, target = nav, nav = nav))
        // animating up
        assertFalse(keyboardSettled(ime = nav + 300, target = nav + keyboard, nav = nav))
        assertTrue(keyboardSettled(ime = nav + keyboard, target = nav + keyboard, nav = nav))
        // animating away
        assertFalse(keyboardSettled(ime = nav + 300, target = 0, nav = nav))
        assertFalse(keyboardSettled(ime = 0, target = 0, nav = 0))
    }
}
