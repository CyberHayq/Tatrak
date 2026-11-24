package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.chat.ScrollAnchor
import am.cyberhayq.chat.ui.chat.SwipeBackLayout
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
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowChoreographer

/**
 * A preview card becoming its chat through a real FragmentManager: the list's [PreviewHandOffs], the chat side of
 * [ChatFragment] ([ChatFragment.takePreviewHandOff] in reInit, [chatAnimator], the wait for the first messages:
 * [postponeForFirstMessages], [startAfterFirstMessages] as listReady calls it) and the transaction of
 * ConversationsActivity.showConversation; the pages stand in for ChatListFragment and ChatFragment, which need the
 * XMPP service.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class PreviewHandOffTest {

    /** ChatListFragment's part: the card is gone when its view goes or [PreviewHandOffs] says so. */
    class ListPage : Fragment() {
        var card = true
        internal val handOffs = PreviewHandOffs(this, CONTAINER_ID) { card = false }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            parentFragmentManager.addOnBackStackChangedListener(handOffs)
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) = View(requireContext())

        override fun onDestroyView() {
            card = false
            handOffs.reset()
            super.onDestroyView()
        }

        override fun onDestroy() {
            parentFragmentManager.removeOnBackStackChangedListener(handOffs)
            super.onDestroy()
        }

        override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int) =
            slideAnimator(this, nextAnim, R.animator.tatrak_list_pop_enter, R.animator.tatrak_list_exit, -0.25f)
    }

    /** ChatFragment's part: takes the hand-off when made for this open, waits for its messages (listReady), animates. */
    class ChatPage : Fragment() {
        internal var handOff: PreviewHandOff? = null
        private var enterFromPreview = false

        var uuid: String? = null

        fun reInit(uuid: String) {
            this.uuid = uuid
            handOff = if (isAdded) null else ChatFragment.takePreviewHandOff(uuid, this)
            enterFromPreview = handOff != null
        }

        /** ChatFragment.listReady. */
        fun listReady() = startAfterFirstMessages()

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) = SwipeBackLayout(requireActivity())

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            postponeForFirstMessages()
        }

        override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int) =
            chatAnimator(this, nextAnim, (enter && enterFromPreview).also { if (enter) enterFromPreview = false })
    }

    class Host : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(FragmentContainerView(this).apply { id = CONTAINER_ID })
        }
    }

    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private val list = ListPage()
    private val fm get() = activity.supportFragmentManager
    private var time = SystemClock.uptimeMillis()

    private fun idle(ms: Long) = repeat((ms / 16).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    /** The posted work (the hand-off's checks, the transactions they wait for) without a frame. */
    private fun runPosted() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun showList() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        fm.beginTransaction().add(CONTAINER_ID, list).commitNow()
        idle(100)
    }

    @After
    fun destroyHost() {
        ChatFragment.previewHandOff = null
        controller.pause().stop().destroy()
    }

    /** ConversationsActivity.showConversation for a chat over the list. */
    private fun showChat(uuid: String): ChatPage {
        val chat = ChatPage()
        val transaction =
            fm.beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.animator.tatrak_chat_enter, R.animator.tatrak_list_exit, R.animator.tatrak_list_pop_enter, R.animator.tatrak_chat_pop_exit)
                .replace(CONTAINER_ID, chat)
        chat.reInit(uuid)
        transaction.addToBackStack(null).commitAllowingStateLoss()
        return chat
    }

    /** The card's tap and, at the pulse end, the open as ChatListFragment.openFromPreview makes it. */
    /**
     * ConversationsActivity.openConversation in one pane: it runs the pending transactions, gives way if that ended a
     * preview's request, and re-inits a chat already in the container instead of making a new one.
     */
    private fun openConversation(uuid: String) {
        fm.executePendingTransactions()
        if (ChatFragment.previewOpenCancelled()) return
        (fm.findFragmentById(CONTAINER_ID) as? ChatPage)?.reInit(uuid) ?: showChat(uuid)
    }

    /** B's notification is committed after A's open was posted: the activity's drain runs B first, and A gives way. */
    @Test
    fun newerNavigationQueuedBehindTheOpenWins() {
        val request = list.handOffs.begin("a")
        list.handOffs.open(request) { openConversation("a") }
        val b = showChat("b")
        runPosted()
        assertSame(b, fm.findFragmentById(CONTAINER_ID))
        assertEquals("b", b.uuid)
        assertNull(b.handOff)
        assertFalse(list.card)
        assertNull(ChatFragment.previewHandOff)
    }

    private fun handOff(uuid: String = "a"): ChatPage {
        val request = list.handOffs.begin(uuid)
        request.startAt = ScrollAnchor("m5", 12)
        lateinit var chat: ChatPage
        list.handOffs.open(request) { chat = showChat(uuid) }
        runPosted()
        return chat
    }

    @Test
    fun takenOnceBySameChatOnly() {
        val handOff = PreviewHandOff("chat-1")
        val chat = Fragment()
        ChatFragment.previewHandOff = handOff
        assertNull(ChatFragment.takePreviewHandOff("chat-2", chat))
        assertSame(handOff, ChatFragment.takePreviewHandOff("chat-1", chat))
        assertSame(chat, handOff.taker)
        assertNull(ChatFragment.takePreviewHandOff("chat-1", chat))
    }

    /** The chat waits for its messages under the card, unresumed, then replaces list and card at once, without the push. */
    @Test
    fun chatReplacesListAndCardOnceReady() {
        val chat = handOff()
        assertEquals(ScrollAnchor("m5", 12), chat.handOff?.startAt)
        assertNull(ChatFragment.previewHandOff)
        idle(100)
        assertFalse(chat.isResumed)
        assertTrue(list.card)
        chat.listReady()
        idle(48)
        assertTrue(chat.isResumed)
        assertEquals(1f, chat.requireView().alpha, 0f)
        assertEquals(0f, chat.requireView().translationX, 0f)
        idle(400)
        assertFalse(list.card)
        assertSame(chat, fm.findFragmentById(CONTAINER_ID))
    }

    /** Without listReady the 300 ms cap releases the chat, as for a row tap. */
    @Test
    fun theCapReleasesASlowChat() {
        val chat = handOff()
        idle(200)
        assertFalse(chat.isResumed)
        idle(200)
        assertTrue(chat.isResumed)
        idle(400)
        assertFalse(list.card)
    }

    /** A notification opened chat B during A's pulse: B stays, A does not open, the card goes. */
    @Test
    fun newerNavigationWins() {
        val request = list.handOffs.begin("a")
        val b = showChat("b")
        runPosted()
        assertFalse(list.card)
        var opened = false
        list.handOffs.open(request) { opened = true }
        runPosted()
        assertFalse(opened)
        assertSame(b, fm.findFragmentById(CONTAINER_ID))
        assertNull(b.handOff)
    }

    /** B opened during A's pulse and Back left it before it was ready: A must not open after all. */
    @Test
    fun newerNavigationLeftWithBackStillEndsTheRequest() {
        val request = list.handOffs.begin("a")
        showChat("b")
        idle(50)
        activity.onBackPressedDispatcher.onBackPressed()
        idle(500)
        assertSame(list, fm.findFragmentById(CONTAINER_ID))
        var opened = false
        list.handOffs.open(request) { opened = true }
        runPosted()
        assertFalse(opened)
        assertFalse(list.card)
    }

    /** Nothing replaced the list (the chat could not open): the inert card must not stay. */
    @Test
    fun noChatOpenedClearsTheCard() {
        list.handOffs.open(list.handOffs.begin("a")) {}
        runPosted()
        assertFalse(list.card)
        assertNull(ChatFragment.previewHandOff)
    }

    @Test
    fun backBeforeTheChatIsReadyClearsTheCard() {
        val chat = handOff()
        idle(50)
        assertFalse(chat.isResumed)
        activity.onBackPressedDispatcher.onBackPressed()
        idle(500)
        assertSame(list, fm.findFragmentById(CONTAINER_ID))
        assertFalse(list.card)
    }

    @Test
    fun backRightAfterTheSwapClearsTheCard() {
        val chat = handOff()
        chat.listReady()
        idle(48)
        assertTrue(chat.isResumed)
        activity.onBackPressedDispatcher.onBackPressed()
        idle(500)
        assertSame(list, fm.findFragmentById(CONTAINER_ID))
        assertFalse(list.card)
    }

    /** Only the enter was instant: Back closes with Telegram's push, as after a row tap. */
    @Test
    fun backLaterClosesWithThePush() {
        val chat = handOff()
        chat.listReady()
        idle(500)
        activity.onBackPressedDispatcher.onBackPressed()
        idle(64)
        val alpha = chat.requireView().alpha
        assertTrue("alpha $alpha", alpha in 0.05f..0.5f)
        idle(400)
        assertSame(list, fm.findFragmentById(CONTAINER_ID))
    }

    /** The swipe back after the hand-off drags and closes the chat as after a row tap. */
    @Test
    fun swipeBackAfterTheHandOff() {
        val chat = handOff()
        chat.listReady()
        idle(500)
        val layout = chat.requireView() as SwipeBackLayout
        val width = layout.width.toFloat()
        layout.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 0f))
        assertTrue(layout.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, width * 0.1f)))
        idle(32)
        activity.window.callback.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, width * 0.6f))
        idle(20)
        assertEquals(width * 0.6f, chat.requireView().translationX, width * 0.02f)
        activity.window.callback.dispatchTouchEvent(event(MotionEvent.ACTION_UP, width * 0.6f))
        idle(1000)
        assertEquals(0, fm.backStackEntryCount)
        assertSame(list, fm.findFragmentById(CONTAINER_ID))
        assertFalse(list.card)
    }

    // slow steps, so no release counts as a fling
    private fun event(action: Int, dx: Float): MotionEvent {
        time += 500
        return MotionEvent.obtain(0, time, action, 10f + dx, 200f, 0)
    }

    private companion object {
        val CONTAINER_ID = View.generateViewId()
    }
}
