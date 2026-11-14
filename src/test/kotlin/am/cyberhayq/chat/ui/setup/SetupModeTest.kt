package am.cyberhayq.chat.ui.setup

import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SetupModeTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Test
    fun easyTrustsOmemoKeysAndSystemCertificates() {
        assertFalse(SetupMode.isChosen(context))
        SetupMode.EASY.apply(preferences)
        assertTrue(SetupMode.isChosen(context))
        assertTrue(preferences.getBoolean(AppSettings.BLIND_TRUST_BEFORE_VERIFICATION, false))
        assertTrue(preferences.getBoolean(AppSettings.TRUST_SYSTEM_CA_STORE, false))
        assertEquals("easy", preferences.getString(SetupMode.PREF, null))
    }

    @Test
    fun advancedTrustsNeitherOmemoKeysNorSystemCertificates() {
        SetupMode.ADVANCED.apply(preferences)
        assertFalse(preferences.getBoolean(AppSettings.BLIND_TRUST_BEFORE_VERIFICATION, true))
        assertFalse(preferences.getBoolean(AppSettings.TRUST_SYSTEM_CA_STORE, true))
        assertEquals("advanced", preferences.getString(SetupMode.PREF, null))
    }

    @Test
    fun channelBindingIsRequiredByDefault() {
        assertTrue(context.resources.getBoolean(R.bool.require_channel_binding))
    }

    @Test
    fun setupScreen() {
        compose.setContent { TatrakTheme(darkTheme = false) { SetupModeScreen {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/setup_mode.png")
    }
}
