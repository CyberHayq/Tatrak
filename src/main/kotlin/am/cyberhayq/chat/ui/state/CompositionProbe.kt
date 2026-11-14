package am.cyberhayq.chat.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets tests count how often list rows are (re)composed, to prove that an update only redraws
 * the rows that changed. Null outside tests, so production pays one null check per row.
 */
val LocalCompositionProbe = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Inline on purpose: a separate composable with an unchanged key would itself be skipped. */
@Composable
@Suppress("NOTHING_TO_INLINE")
inline fun ReportComposition(key: String) {
    val probe = LocalCompositionProbe.current
    if (probe != null) {
        SideEffect { probe(key) }
    }
}
