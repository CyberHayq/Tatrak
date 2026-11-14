package am.cyberhayq.chat.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.Activities

/**
 * Picks dark or light status bar icons for the screen on top. On leave it puts back what the
 * classic XML screens use ([Activities.setStatusAndNavigationBarColors]: dark icons in light
 * mode), but only if no other screen has set icons since: the list is disposed after the chat's
 * enter animation and must not overwrite the chat's choice.
 */
@Composable
fun StatusBarIcons(dark: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, dark) {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) {
            context = context.baseContext
        }
        val window = (context as? Activity)?.window
        if (window == null) {
            onDispose {}
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val decor = window.decorView
            // not read back from the controller: the classic screens set the legacy view flag,
            // which the controller does not report
            val classic = !Activities.isNightMode(context)
            val token = Any()
            decor.setTag(R.id.tatrak_status_bar_owner, token)
            controller.isAppearanceLightStatusBars = dark
            onDispose {
                if (decor.getTag(R.id.tatrak_status_bar_owner) === token) {
                    decor.setTag(R.id.tatrak_status_bar_owner, null)
                    controller.isAppearanceLightStatusBars = classic
                }
            }
        }
    }
}
