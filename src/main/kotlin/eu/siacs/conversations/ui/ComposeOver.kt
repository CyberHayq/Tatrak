package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import eu.siacs.conversations.R

/**
 * Tatrak: puts a Compose screen over an activity's classic layout. The classic views stay
 * attached but hidden, so the legacy code that still finds them (toolbars, adapters, dialogs
 * anchored to them) keeps working; the activity's logic is unchanged and the Compose screen
 * only calls it back.
 */
internal fun AppCompatActivity.composeOver(classic: View, content: @Composable () -> Unit) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    (classic.parent as? ViewGroup)?.removeView(classic)
    classic.visibility = View.GONE
    val composeView = ComposeView(this).apply { id = R.id.tatrak_compose_over; setContent { TatrakTheme { content() } } }
    setContentView(
        FrameLayout(this).apply {
            addView(classic)
            addView(composeView)
        }
    )
}
