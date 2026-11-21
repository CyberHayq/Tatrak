package am.cyberhayq.chat.ui.chatlist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutQuint
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import am.cyberhayq.chat.ui.theme.BarBackdrop
import am.cyberhayq.chat.ui.theme.backdropSource
import am.cyberhayq.chat.ui.theme.rememberBarBackdrop
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** Telegram's main tabs (`MainTabsActivity`): the chat list and the screens its dock switches to, all one level. */
enum class MainTab {
    CHATS,
    CONTACTS,
    CALLS,
    PROFILE,
}

/** The dock's order: the floating bar's (Telegram 13) or Slate and Sea's classic bar's. */
internal fun mainTabOrder(floating: Boolean): List<MainTab> =
    if (floating) listOf(MainTab.CHATS, MainTab.CONTACTS, MainTab.CALLS, MainTab.PROFILE)
    else listOf(MainTab.CHATS, MainTab.CALLS, MainTab.CONTACTS, MainTab.PROFILE)

/** Telegram's tab switch (`ViewPagerActivity.getManualScrollDuration`), with `EASE_OUT_QUINT`. */
internal const val TAB_SWITCH_MS = 320

/** Whether [MainTabs] is switching, read in the draw phase (a lambda: switching recomposes no page). */
internal val LocalTabSwitching = staticCompositionLocalOf<() -> Boolean> { { false } }

/**
 * A page's own tab bar hides while the tabs switch: [MainTabs] draws one over both sliding pages then, in place, so the
 * dock stays put and only the selection moves, as Telegram's tab bar. What else the dock carries on Chats (search,
 * compose) slides with its page.
 */
@Composable
internal fun Modifier.pageDock(): Modifier {
    val switching = LocalTabSwitching.current
    return graphicsLayer { alpha = if (switching()) 0f else 1f }
}

/**
 * The [page] of [tab]; on a switch the old and the new page slide side by side a full width, the new one in from the side
 * its tab lies on in [order] (`ViewPagerFixed.scrollToPosition`), under the new tab's [dock] held in place. A page left
 * keeps its saved state (scroll, search) for its return. Back on another tab goes to Chats ([onBack]); a page's own Back
 * handling (search, selection) comes first.
 */
@Composable
fun MainTabs(
    tab: MainTab,
    order: List<MainTab>,
    onBack: () -> Unit,
    dock: @Composable (MainTab, BarBackdrop?) -> Unit,
    page: @Composable (MainTab) -> Unit,
) {
    // composed before the pages, so their handlers win
    BackHandler(enabled = tab != MainTab.CHATS, onBack = onBack)
    val holder = rememberSaveableStateHolder()
    var shown by remember { mutableStateOf(tab) }
    var from by remember { mutableStateOf<MainTab?>(null) }
    val progress = remember { Animatable(1f) }
    var width by remember { mutableFloatStateOf(0f) }
    val currentOrder by rememberUpdatedState(order)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LaunchedEffect(tab) {
        if (tab == shown) return@LaunchedEffect
        // first, so no frame draws the new page in place before it slides
        progress.snapTo(0f)
        from = shown
        shown = tab
        progress.animateTo(1f, tween(TAB_SWITCH_MS, easing = EaseOutQuint))
        from = null
    }
    fun offset(p: MainTab): Float {
        val old = from ?: return 0f
        val ahead = currentOrder.indexOf(shown) > currentOrder.indexOf(old)
        // +1: the new page comes in from the end side and the old one leaves to the start
        val dir = (if (ahead) 1f else -1f) * (if (rtl) -1f else 1f)
        val t = progress.value
        return if (p == old) -dir * width * t else dir * width * (1f - t)
    }
    val switching = remember { { from != null } }
    // the dock over the pages blurs both, as each page's own blurs its page
    val backdrop = rememberBarBackdrop()
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { width = it.width.toFloat() }) {
        Box(Modifier.fillMaxSize().then(if (from != null) Modifier.backdropSource(backdrop) else Modifier)) {
            CompositionLocalProvider(LocalTabSwitching provides switching) {
                for (p in listOfNotNull(from, shown)) {
                    key(p) {
                        Box(Modifier.fillMaxSize().graphicsLayer { translationX = offset(p) }) {
                            holder.SaveableStateProvider(p) { page(p) }
                        }
                    }
                }
            }
        }
        if (from != null) Box(Modifier.align(Alignment.BottomCenter)) { dock(shown, backdrop) }
    }
}
