package am.cyberhayq.chat.ui.settings

import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.robolectric.Robolectric
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
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

/** The ten classic pages as the Compose renderer draws their XML; fragment code (summaries set in code, visibility) does not run here. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h1500dp-xhdpi")
class PreferenceListTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private var page by mutableStateOf<Pair<PreferenceScreen, PreferenceGroupAdapter>?>(null)

    /** What `setPreferenceScreen()` and `bindPreferences()` do for the fragment: attach the tree (dependencies) and build the classic adapter. */
    @SuppressLint("RestrictedApi")
    private fun show(xml: Int, dark: Boolean = false) {
        val manager = PreferenceManager(context)
        val screen = manager.inflateFromResource(context, xml, null)
        manager.setPreferences(screen)
        screen.onAttached()
        val adapter = PreferenceGroupAdapter(screen)
        // setContent runs once per test; later pages swap the state
        if (page == null) compose.setContent { TatrakTheme(darkTheme = dark) { page?.let { (s, a) -> PreferenceList(s, a) } } }
        compose.runOnIdle { page = screen to adapter }
        compose.waitForIdle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/preferences_$name.png")

    @Test
    fun pages() {
        for ((name, xml) in
            listOf(
                "attachments" to R.xml.preferences_attachments,
                "availability" to R.xml.preferences_availability,
                "backup" to R.xml.preferences_backup,
                "connection" to R.xml.preferences_connection,
                "interface" to R.xml.preferences_interface,
                "interface_bubbles" to R.xml.preferences_interface_bubbles,
                "notifications" to R.xml.preferences_notifications,
                "privacy" to R.xml.preferences_privacy,
                "security" to R.xml.preferences_security,
                "up" to R.xml.preferences_up,
                "updates" to R.xml.tatrak_preferences_updates,
            )) {
            show(xml)
            capture(name)
        }
    }

    @Test
    @Config(qualifiers = "+night")
    fun notificationsDark() {
        show(R.xml.preferences_notifications, dark = true)
        capture("notifications_dark")
    }

    /** The updates page in the dark: the automatic check is on by default (until the F-Droid submission), betas off. */
    @Test
    @Config(qualifiers = "+night")
    fun updatesDark() {
        show(R.xml.tatrak_preferences_updates, dark = true)
        compose.onNodeWithText(context.getString(R.string.tatrak_update_auto)).assertIsOn()
        compose.onNodeWithText(context.getString(R.string.tatrak_update_prerelease)).assertIsOff()
        capture("updates_dark")
    }

    /** A tap on a switch writes it through the Preference and redraws its dependents; a disabled row ignores taps. */
    @Test
    fun switchTapAndDisabledRow() {
        show(R.xml.preferences_availability)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val manual = compose.onNodeWithText(context.getString(R.string.pref_manually_change_presence))
        val away = compose.onNodeWithText(context.getString(R.string.pref_away_when_screen_off))
        manual.assertIsOff()
        away.assertIsEnabled()

        manual.performClick()
        manual.assertIsOn()
        assertTrue(prefs.getBoolean("manually_change_presence", false))
        // disableDependentsState: managing presence by hand turns the automatic rules off
        away.assertIsNotEnabled()
        away.performClick()
        away.assertIsOff()
        assertFalse(prefs.getBoolean("away_when_screen_off", false))
    }

    /** In a real fragment the adapter is set before the first composition, so a change made in code redraws the row. */
    @Test
    fun fragmentFollowsChangesMadeInCode() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val page = AvailabilityPage()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, page).commitNow()
        val manual = compose.onNodeWithText(context.getString(R.string.pref_manually_change_presence))
        manual.assertIsOff()
        page.findPreference<SwitchPreferenceCompat>("manually_change_presence")!!.isChecked = true
        manual.assertIsOn()
    }

    /** A keyed plain row handled only in onPreferenceTreeClick (remove_trusted_certificates) is a button; the key-less explanation stays text. */
    @Test
    fun keyedPlainRowReachesTreeClick() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val page = TreeClickPage()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, page).commitNow()
        compose.onNodeWithText("Explanation").assertHasNoClickAction()
        compose.onNodeWithText("Handled").assertHasClickAction().performClick()
        assertEquals(listOf("handled"), page.clicked)
    }
}

class TreeClickPage : PreferenceFragmentCompat() {
    val clicked = mutableListOf<String?>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        screen.addPreference(Preference(requireContext()).apply { summary = "Explanation" })
        screen.addPreference(Preference(requireContext()).apply { key = "handled"; title = "Handled" })
        preferenceScreen = screen
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        clicked += preference.key
        return true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        preferenceListView(this, super.onCreateView(inflater, container, savedInstanceState))
}

class AvailabilityPage : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) =
        setPreferencesFromResource(R.xml.preferences_availability, rootKey)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        preferenceListView(this, super.onCreateView(inflater, container, savedInstanceState))
}
