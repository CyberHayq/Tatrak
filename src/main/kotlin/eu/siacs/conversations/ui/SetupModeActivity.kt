package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.setup.SetupModeScreen
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import eu.siacs.conversations.utils.SignupUtils

/**
 * Tatrak: shown once on a fresh install, before the first account is set up. The choice writes the
 * security defaults ([am.cyberhayq.chat.ui.setup.SetupMode]) and the normal first-run flow goes on.
 */
class SetupModeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            TatrakTheme {
                SetupModeScreen { mode ->
                    mode.apply(PreferenceManager.getDefaultSharedPreferences(this))
                    startActivity(SignupUtils.getRedirectionIntent(this))
                    finish()
                }
            }
        }
    }
}
