package am.cyberhayq.chat.ui.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Space under the composer's content, in px from the screen bottom, when [panel] px of emoji panel
 * sit just above it and [held] px are kept for a keyboard that went away: the keyboard slides
 * under the panel (or the held space) instead of adding to it, so the composer does not jump.
 */
internal fun composerBottomPx(ime: Int, nav: Int, panel: Int, held: Int): Int = maxOf(ime, nav + panel, held) - panel

/**
 * The keyboard is up and its inset animation has ended: Compose sets the IME animation target
 * when the animation starts and the current inset reaches it in the animation's last frame.
 */
internal fun keyboardSettled(ime: Int, target: Int, nav: Int): Boolean = target > nav && ime == target

/**
 * The composer's insets: the navigation bar at the sides, [composerBottomPx] at the bottom. Read
 * while laying out, so the keyboard animation re-lays out the composer without recomposing it.
 */
internal class ComposerInsets(
    private val ime: WindowInsets,
    private val nav: WindowInsets,
    private val panelPx: Int,
    private val heldPx: () -> Int,
) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection) = nav.getLeft(density, layoutDirection)

    override fun getTop(density: Density) = 0

    override fun getRight(density: Density, layoutDirection: LayoutDirection) = nav.getRight(density, layoutDirection)

    override fun getBottom(density: Density) = composerBottomPx(ime.getBottom(density), nav.getBottom(density), panelPx, heldPx())
}

/**
 * Waits until the keyboard asked for has come up and its animation has ended. Gives up when it
 * does not start within a second (a hardware keyboard, a refused show) or does not settle.
 */
internal suspend fun awaitKeyboard(density: Density, ime: WindowInsets, target: WindowInsets, nav: WindowInsets) {
    withTimeoutOrNull(KeyboardWaitMillis) {
        snapshotFlow { target.getBottom(density) > nav.getBottom(density) }.first { it }
    } ?: return
    withTimeoutOrNull(KeyboardWaitMillis) {
        snapshotFlow { keyboardSettled(ime.getBottom(density), target.getBottom(density), nav.getBottom(density)) }.first { it }
    }
}

private const val KeyboardWaitMillis = 1000L
