package am.cyberhayq.chat.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.Window
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

/**
 * Telegram's swipe-back: a rightward drag anywhere on the chat drives the back dispatcher like
 * the system's predictive back gesture, so the fragment pop animation follows the finger.
 * Thresholds from Telegram's ActionBarLayout: start after 0.4 cm when |dx| / 3 > |dy|, finish
 * past a third of the width or at 3500 px/s.
 *
 * Below API 34 the FragmentManager still starts the pop when back starts (the list is added to
 * the container under the chat) but does not seek animators, so this layout moves the chat and
 * the list with the finger itself; [slideAnimator] then carries on from where they are.
 *
 * The system's edge back (API 34+) is taken in front of the dispatcher and passed on only on
 * release: the system shows its arrow, the chat stays put, then the normal pop plays (as in
 * Forkgram classic and monocles). Otherwise the FragmentManager would seek the full slide by the
 * system's progress, and arrow and slide would look like two gestures at once. Compose
 * BackHandlers still win, since the release goes through the same dispatcher.
 */
@SuppressLint("ViewConstructor")
class SwipeBackLayout(private val activity: ComponentActivity) : FrameLayout(activity) {

    /** Off on tablets, where the chat is not on the back stack. */
    var swipeEnabled = true

    /** Off with the Signal swipe method: only the system back gesture closes the chat. */
    var dragEnabled = true

    /** Areas with their own horizontal gestures (voice seek bar, emoji panel), in this view's coordinates. */
    val exclusions = HashMap<Any, Rect>()

    private val dispatcher = activity.onBackPressedDispatcher
    private val startSlop = 0.4f / 2.54f * resources.displayMetrics.xdpi
    private var downX = 0f
    private var downY = 0f
    private var downLocalX = 0
    private var downLocalY = 0
    private var swipeWidth = 0
    private var tracking = false
    private var velocity: VelocityTracker? = null

    /** Below API 34 the drag is drawn here, see the class comment. */
    private val manual = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** How far left the list sits at the start of the drag, as a fraction of the width (Telegram: a quarter). */
    var parallax = 0.25f
    private var container: ViewGroup? = null

    private val systemBack =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            OnBackInvokedCallback {
                // the keyboard's own back callback sits at PRIORITY_DEFAULT, below this one: close it here first
                val ime = WindowInsetsCompat.Type.ime()
                if (ViewCompat.getRootWindowInsets(this@SwipeBackLayout)?.isVisible(ime) == true) {
                    WindowCompat.getInsetsController(activity.window, this@SwipeBackLayout).hide(ime)
                } else dispatcher.onBackPressed()
            }
        } else null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // above PRIORITY_DEFAULT, where the dispatcher listens; overlays (PRIORITY_OVERLAY) still come first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && swipeEnabled) {
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(1, systemBack!!)
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(systemBack!!)
        super.onDetachedFromWindow()
    }

    // a chat waiting to slide in has alpha 0 over the visible list: let touches reach the list
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = alpha != 0f && super.dispatchTouchEvent(ev)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                downLocalX = ev.x.toInt()
                downLocalY = ev.y.toInt()
                // the system's back zone (gesture navigation) is left to the system, as in Telegram:
                // starting ours there too would run both back gestures at once
                val edge = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.systemGestures())?.left ?: 0
                tracking = swipeEnabled && dragEnabled && ev.rawX >= edge && exclusions.values.none { it.contains(ev.x.toInt(), ev.y.toInt()) }
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                // a block can come during the gesture: a long press that starts selecting messages
                if (tracking && exclusions.values.any { it.contains(downLocalX, downLocalY) }) tracking = false
                if (!tracking) return false
                velocity?.addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (dx >= startSlop && abs(dx) / 3 > abs(dy)) {
                    start(ev)
                    return true
                }
                // vertical scroll or a leftward swipe (reply): leave this gesture alone
                if (abs(dy) > startSlop || dx < -startSlop) tracking = false
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return false
    }

    /**
     * Compose asks for this as soon as any child takes a drag past its own (smaller) slop, e.g.
     * the swipe-to-reply detector on every bubble; children that really own horizontal drags are
     * listed in [exclusions] instead, so this layout keeps deciding and only passes it up.
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    /** The rest of the gesture arrives through the window (see [start]); this only keeps it. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean = ev.actionMasked != MotionEvent.ACTION_DOWN

    /**
     * Starting back makes the FragmentManager take this view out of its container (it keeps
     * drawing while it animates), so it would stop getting touches: the window feeds the rest of
     * the gesture to [follow] until the finger lifts.
     */
    private fun start(ev: MotionEvent) {
        tracking = false
        swipeWidth = width
        parent?.requestDisallowInterceptTouchEvent(true)
        val window = activity.window
        val original = window.callback
        window.callback =
            object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (!follow(event)) window.callback = original
                    return true
                }
            }
        container = parent as? ViewGroup
        dispatcher.dispatchOnBackStarted(event(ev))
    }

    /** The screen the pop brings back; the FragmentManager adds it to the container when back starts. */
    private fun below(): View? = container?.let { c -> (0 until c.childCount).map(c::getChildAt).firstOrNull { it !== this } }

    private fun drag(dx: Float) {
        val x = dx.coerceIn(0f, swipeWidth.toFloat())
        translationX = x
        below()?.translationX = -parallax * swipeWidth * (1 - progress(x, swipeWidth))
    }

    /** Below API 34: slides back to the start, then lets the FragmentManager put the chat back. */
    private fun settleBack() {
        ValueAnimator.ofFloat(translationX, 0f).apply {
            duration = 200
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { drag(it.animatedValue as Float) }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        below()?.translationX = 0f
                        translationX = 0f
                        container = null
                        dispatcher.dispatchOnBackCancelled()
                    }
                }
            )
            start()
        }
    }

    /** False once the gesture is over. */
    private fun follow(ev: MotionEvent): Boolean {
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (manual) drag(ev.rawX - downX)
                dispatcher.dispatchOnBackProgressed(event(ev))
            }
            MotionEvent.ACTION_UP -> {
                val vx = velocity?.run { computeCurrentVelocity(1000); xVelocity } ?: 0f
                if (shouldFinish(ev.rawX - downX, vx, swipeWidth)) {
                    container = null
                    dispatcher.onBackPressed()
                } else cancel()
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                cancel()
                return false
            }
        }
        return true
    }

    private fun cancel() {
        if (manual) return settleBack()
        dispatcher.dispatchOnBackCancelled()
        // API 34+: after a quick cancel the FragmentManager may leave the reversed animator short
        // of its start, so the chat would stay shifted; slide it home once it is back in place
        postDelayed(
            {
                if (parent != null && translationX != 0f) {
                    animate().translationX(0f).setDuration(160).setInterpolator(DecelerateInterpolator(1.5f)).start()
                }
            },
            400, // longer than the 300 ms transition, or both animators pull the chat at once
        )
    }

    /**
     * The FragmentManager (API 34+) seeks the animators by this progress, and their
     * DecelerateInterpolator(1.5f) would move the chat ahead of the finger: pass the inverse of
     * 1 - (1 - x)³ so the chat stays under it.
     */
    private fun event(ev: MotionEvent) =
        BackEventCompat(ev.rawX, ev.rawY, seekProgress(progress(ev.rawX - downX, swipeWidth)), BackEventCompat.EDGE_LEFT)

    companion object {
        private const val FLING_VELOCITY = 3500f

        fun progress(dx: Float, width: Int): Float = if (width <= 0) 0f else (dx / width).coerceIn(0f, 1f)

        fun seekProgress(p: Float): Float = 1f - kotlin.math.cbrt(1f - p.coerceIn(0f, 1f))

        fun shouldFinish(dx: Float, vx: Float, width: Int): Boolean =
            dx > width / 3f || (vx >= FLING_VELOCITY && dx > 0)
    }
}

val LocalSwipeBack = compositionLocalOf<SwipeBackLayout?> { null }

/** Keeps swipe-back from starting inside this element, which handles horizontal drags itself. */
fun Modifier.swipeBackExclusion(): Modifier = composed {
    val layout = LocalSwipeBack.current ?: return@composed Modifier
    val key = remember { Any() }
    DisposableEffect(layout) { onDispose { layout.exclusions.remove(key) } }
    // the ComposeView fills the layout, so root coordinates are the layout's
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot()
        layout.exclusions[key] =
            Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
    }
}

/** Turns swipe-back off while [active], e.g. while the emoji panel owns back or a recording runs. */
@Composable
fun BlockSwipeBack(active: Boolean) {
    val layout = LocalSwipeBack.current ?: return
    DisposableEffect(layout, active) {
        val key = Any()
        if (active) layout.exclusions[key] = Rect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        onDispose { layout.exclusions.remove(key) }
    }
}
