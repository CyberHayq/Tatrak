package am.cyberhayq.chat.ui.chat

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.slideAnimator
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowChoreographer

/**
 * The list-to-chat transition through a real FragmentManager, with the same animators and
 * custom animations as ChatListFragment, ChatFragment and ConversationsActivity.showConversation:
 * a tap opens and closes with Telegram's 150 ms push, a swipe back drags a full width.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class ChatTransitionTest {

    class ListPage : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) = View(requireContext())

        override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int) =
            slideAnimator(this, nextAnim, R.animator.tatrak_list_pop_enter, R.animator.tatrak_list_exit, -0.25f)
    }

    class ChatPage : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) = SwipeBackLayout(requireActivity())

        override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int) =
            slideAnimator(this, nextAnim, R.animator.tatrak_chat_enter, R.animator.tatrak_chat_pop_exit, 1f)
    }

    private lateinit var activity: FragmentActivity
    private val list = ListPage()
    private val chat = ChatPage()
    private val fm get() = activity.supportFragmentManager
    private val shift get() = 48 * activity.resources.displayMetrics.density
    private val chatView get() = chat.requireView()
    private var time = SystemClock.uptimeMillis()

    // frame by frame: idleFor moves the clock to its end first, so one long idle is a single frame
    private fun idle(ms: Long) = repeat((ms / 16).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    @Before
    fun openChat() {
        // frames only on the test clock, 16 ms apart; otherwise every animator ends on its first frame
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val container = FragmentContainerView(activity).apply { id = View.generateViewId() }
        activity.setContentView(container)
        fm.beginTransaction().add(container.id, list).commitNow()
        idle(100)
        fm.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(
                R.animator.tatrak_chat_enter,
                R.animator.tatrak_list_exit,
                R.animator.tatrak_list_pop_enter,
                R.animator.tatrak_chat_pop_exit,
            )
            .replace(container.id, chat)
            .addToBackStack(null)
            .commit()
        fm.executePendingTransactions()
    }

    @Test
    fun tapOpensWithTelegramsPush() {
        idle(64)
        assertMidPush(chatView.alpha, chatView.translationX)
        // the list stays put under the fading chat
        assertNotNull(list.view?.parent)
        assertEquals(0f, list.requireView().translationX, 0f)
        idle(300)
        assertEquals(1f, chatView.alpha, 0f)
        assertEquals(0f, chatView.translationX, 0f)
        assertNull(list.view?.parent)
    }

    @Test
    fun backWithoutGestureClosesWithTelegramsPush() {
        idle(500)
        activity.onBackPressedDispatcher.onBackPressed()
        idle(64)
        // fading out: alpha 1 -> 0 while translationX goes 0 -> 48 dp
        assertMidPush(1f - chatView.alpha, shift - chatView.translationX)
        assertEquals(0f, list.requireView().translationX, 0f)
        idle(300)
        assertEquals(0, fm.backStackEntryCount)
        assertFalse(chat.isAdded)
    }

    @Test
    fun swipeThatSettlesBack() = settles()

    @Test
    fun swipeThatCompletes() = completes()

    @Test
    @Config(sdk = [30])
    fun swipeThatSettlesBackBelowApi34() = settles()

    @Test
    @Config(sdk = [30])
    fun swipeThatCompletesBelowApi34() = completes()

    private fun settles() {
        val width = startSwipe()
        drag(width * 0.2f)
        release(width * 0.2f)
        idle(1000)
        assertFalse(SwipeBackLayout.dragging)
        assertTrue(chat.isAdded)
        assertEquals(1, fm.backStackEntryCount)
        assertEquals(0f, chatView.translationX, 0f)
        assertEquals(1f, chatView.alpha, 0f)
    }

    private fun completes() {
        val width = startSwipe()
        drag(width * 0.6f)
        release(width * 0.6f)
        idle(1000)
        assertFalse(SwipeBackLayout.dragging)
        assertEquals(0, fm.backStackEntryCount)
        assertFalse(chat.isAdded)
        assertEquals(0f, list.requireView().translationX, 0f)
    }

    /** Starts a swipe back on the opened chat; returns the chat's width. */
    private fun startSwipe(): Float {
        idle(500)
        val layout = chatView as SwipeBackLayout
        val width = layout.width.toFloat()
        assertTrue(width > 0)
        layout.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 0f))
        assertTrue(layout.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, width * 0.1f)))
        assertTrue(SwipeBackLayout.dragging)
        // the FragmentManager prepares the pop on the next message, as the next MOVE would come
        idle(32)
        return width
    }

    /** Moves the finger [dx] from the start; the chat follows it a full width, the list at parallax. */
    private fun drag(dx: Float) {
        activity.window.callback.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, dx))
        idle(20)
        val width = chatView.width
        assertEquals(dx, chatView.translationX, width * 0.02f)
        assertEquals(1f, chatView.alpha, 0f)
        assertEquals(-0.25f * width * (1 - dx / width), list.requireView().translationX, width * 0.02f)
    }

    private fun release(dx: Float) {
        activity.window.callback.dispatchTouchEvent(event(MotionEvent.ACTION_UP, dx))
    }

    // slow steps, so no release counts as a fling
    private fun event(action: Int, dx: Float): MotionEvent {
        time += 500
        return MotionEvent.obtain(0, time, action, 10f + dx, 200f, 0)
    }

    /** Telegram's push a few frames in: partly shown, and 48 dp times the rest still to go. */
    private fun assertMidPush(shown: Float, offset: Float) {
        assertTrue("shown $shown", shown in 0.5f..0.95f)
        assertEquals(shift * (1 - shown), offset, 0.5f)
    }
}
