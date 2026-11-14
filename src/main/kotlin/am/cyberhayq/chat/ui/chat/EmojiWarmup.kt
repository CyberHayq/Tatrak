package am.cyberhayq.chat.ui.chat

import eu.siacs.conversations.utils.Emoticons
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The emoji library behind [Emoticons] loads its whole emoji table on first use. The chat snapshot
 * asks `Message.bodyIsOnlyEmojis()` for every message, so the first chat opened after a start
 * waited for that table: 275 ms on an x86 emulator in a debug build (80 ms optimised), more on a
 * phone. Loading it on a background thread while the chat list is on screen moves that out of the
 * way; a chat opened before it is done just waits for the same load, as before.
 */
object EmojiWarmup {
    private val started = AtomicBoolean()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({ runCatching { Emoticons.isOnlyEmoji("🙂") } }, "tatrak-emoji-warmup")
            .apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
            .start()
    }
}
