package am.cyberhayq.chat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeBackTest {

    @Test
    fun finishesPastAThirdOrOnAFling() {
        assertFalse(SwipeBackLayout.shouldFinish(dx = 300f, vx = 0f, width = 1080))
        assertTrue(SwipeBackLayout.shouldFinish(dx = 361f, vx = 0f, width = 1080))
        assertTrue(SwipeBackLayout.shouldFinish(dx = 100f, vx = 3500f, width = 1080))
        // a fast flick back to the left edge cancels
        assertFalse(SwipeBackLayout.shouldFinish(dx = -10f, vx = 5000f, width = 1080))
    }

    @Test
    fun progressIsClamped() {
        assertEquals(0.5f, SwipeBackLayout.progress(540f, 1080), 0.001f)
        assertEquals(0f, SwipeBackLayout.progress(-50f, 1080), 0f)
        assertEquals(1f, SwipeBackLayout.progress(2000f, 1080), 0f)
        assertEquals(0f, SwipeBackLayout.progress(10f, 0), 0f)
    }

    @Test
    fun seekProgressUndoesTheDecelerateInterpolator() {
        // API 34+ seeks the 300 ms DecelerateInterpolator(1.5f) animators by this fraction; feeding it
        // back through the interpolator must give the finger's own progress, so the chat stays under it
        // (DecelerateInterpolator with factor 1.5 is 1 - (1 - t)^3; android.jar here is only a stub)
        fun decelerate(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
        for (p in listOf(0f, 0.1f, 0.33f, 0.5f, 0.9f, 1f)) {
            assertEquals(p, decelerate(SwipeBackLayout.seekProgress(p)), 0.001f)
        }
        assertEquals(0f, SwipeBackLayout.seekProgress(-0.2f), 0f)
        assertEquals(1f, SwipeBackLayout.seekProgress(1.5f), 0f)
    }
}
