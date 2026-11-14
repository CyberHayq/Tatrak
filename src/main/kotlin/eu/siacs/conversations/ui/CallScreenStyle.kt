package eu.siacs.conversations.ui

import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/**
 * Tatrak: window setup for the restyled [RtpSessionActivity]. The gradient runs behind transparent
 * system bars with light icons in both themes, and the toolbar title is hidden because the call
 * state is shown under the name instead. Nothing here touches the call itself.
 */
internal object CallScreenStyle {

    @JvmStatic
    fun apply(activity: AppCompatActivity, root: View) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        activity.supportActionBar?.setDisplayShowTitleEnabled(false)
    }
}
