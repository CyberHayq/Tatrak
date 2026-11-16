package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.theme.rememberStringPreference
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How a chat answers horizontal swipes on messages, picked in the Interface settings. A plain
 * preference read by key, so the upstream `AppSettings` stays untouched.
 */
enum class SwipeMethod(
    val key: String,
    /** -1: reply swipes go left, 1: right. */
    val direction: Float,
    /** Finger travel that sends the reply on release. */
    val trigger: Dp,
    /** How far the bubble follows the finger. */
    val max: Dp,
) {
    /** Telegram (`ChatActivity`): leftward past 0.4 cm within about 18° of horizontal; a rightward drag closes the chat. */
    TELEGRAM("telegram", -1f, 56.dp, 80.dp),

    /** Signal (`ConversationItemSwipeCallback`, an `ItemTouchHelper`): rightward past the touch slop within 45°; only the system back gesture closes the chat. */
    SIGNAL("signal", 1f, 64.dp, 96.dp);

    companion object {
        const val KEY = "tatrak_swipe_method"

        fun of(key: String?): SwipeMethod = entries.firstOrNull { it.key == key } ?: TELEGRAM

        @Composable fun remember(): SwipeMethod = of(rememberStringPreference(KEY))
    }
}

/** The method in force in a chat, provided by `ChatFragment` (one preference listener per screen). */
val LocalSwipeMethod = staticCompositionLocalOf { SwipeMethod.TELEGRAM }
