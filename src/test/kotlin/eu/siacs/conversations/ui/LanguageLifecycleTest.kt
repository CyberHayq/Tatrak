package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.chatlist.MainTab
import am.cyberhayq.chat.ui.settings.setAppLanguage
import am.cyberhayq.chat.ui.theme.FloatingBars
import android.Manifest
import android.app.LocaleManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.os.Looper
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.activity.SettingsActivity
import eu.siacs.conversations.ui.fragment.settings.SettingsHomeFragment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * The Language page in the real SettingsActivity and the chat list's Profile tab: API 30 is AppCompat's own path (it
 * recreates the activity), API 35 the system per-app language (Robolectric's LocaleManager sends no configuration change,
 * so the test recreates as the framework would). A process restart cannot run here.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(
    application = ReviewFlowsTest.TestApp::class,
    sdk = [30],
    qualifiers = "w411dp-h891dp",
    shadows = [ReviewFlowsTest.NoAxolotlStore::class],
)
class LanguageLifecycleTest {

    class SettingsHost : SettingsActivity() {
        override fun connectToBackend() {
            if (bindAtStart) bindNow()
        }

        /** The service connection arriving: what XmppActivity's ServiceConnection does. */
        fun bindNow() {
            xmppConnectionService = ReviewFlowsTest.Host.service
            xmppConnectionServiceBound = true
            registerListeners()
            onBackendConnected()
        }

        companion object {
            var bindAtStart = true
        }

        override fun onStop() {
            xmppConnectionServiceBound = false
            super.onStop()
        }
    }

    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<ReviewFlowsTest.TestApp>()

    @Before
    fun start() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS)
        FloatingBars.set(app, true)
        ReviewFlowsTest.Host.service = ReviewFlowsTest.TestService(app)
    }

    @After
    fun reset() {
        SettingsHost.bindAtStart = true
        ReviewFlowsTest.Host.service = null // the service holds the Robolectric sandbox's objects
        // AppCompat's choice is static; on 33+ it lives in the (shadow) LocaleManager
        if (Build.VERSION.SDK_INT >= 33) frameworkLocales(LocaleList.getEmptyLocaleList()) else setAppLanguage(null)
    }

    private fun frameworkLocales(locales: LocaleList) {
        app.getSystemService(LocaleManager::class.java).applicationLocales = locales
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Test fun fromSettingsHome() = settingsHome()

    /**
     * A choice made while the service is not bound reaches it at the next bind of any activity, also when the
     * Language page was left before its own bind (API 30): XmppActivity's bind hands the language over.
     */
    @Test
    fun choiceBeforeBindingReachesTheService() {
        val service = ReviewFlowsTest.Host.service!!
        ReflectionHelpers.callInstanceMethod<Unit>(service.notificationService, "initializeChannels")
        val channels = app.getSystemService(NotificationManager::class.java)
        assertEquals("Messages", channels.getNotificationChannel("messages").name)
        SettingsHost.bindAtStart = false
        val intent = Intent(app, SettingsHost::class.java).putExtra(SettingsHomeFragment.EXTRA_PAGE, R.string.tatrak_language)
        val settings = Robolectric.buildActivity(SettingsHost::class.java, intent).setup()
        idle()
        // near the end of the lazy list: not composed until scrolled to
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Русский"))
        compose.onNodeWithText("Русский").performClick()
        idle()
        assertEquals("not bound: nothing reached the service yet", "Messages", channels.getNotificationChannel("messages").name)
        // Back closes the page (opened directly, as from the Profile tab) before the bind ever arrives
        settings.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(settings.get().isFinishing)
        settings.pause().stop().destroy()
        assertEquals("Messages", channels.getNotificationChannel("messages").name)
        // the next activity to bind, not the Language page, hands the language over
        SettingsHost.bindAtStart = true
        val next = Robolectric.buildActivity(SettingsHost::class.java).setup()
        assertEquals("Сообщения", channels.getNotificationChannel("messages").name)
        next.pause().stop().destroy()
    }

    @Test @Config(sdk = [35]) fun fromSettingsHomeOnAndroid13() = settingsHome()

    @Test fun fromProfile() = profile()

    @Test @Config(sdk = [35]) fun fromProfileOnAndroid13() = profile()

    private fun settingsHome() {
        val settings = Robolectric.buildActivity(SettingsHost::class.java).setup()
        idle()
        compose.onNodeWithText("System default").assertExists()
        compose.onNodeWithText("Language").performClick()
        idle()
        val before = settings.get()
        compose.onNodeWithText("Deutsch").performScrollTo().performClick()
        idle()
        if (Build.VERSION.SDK_INT >= 33) settings.recreate()
        assertNotSame("the activity is recreated", before, settings.get())
        idle()
        compose.onNodeWithText("Deutsch").performScrollTo().assertIsSelected()
        settings.get().onBackPressedDispatcher.onBackPressed()
        idle()
        compose.onNodeWithText("Deutsch").assertExists()
        if (Build.VERSION.SDK_INT >= 33) {
            // cleared in Android's own per-app setting while the page is in the background
            settings.pause()
            frameworkLocales(LocaleList.getEmptyLocaleList())
            settings.resume()
            idle()
            compose.onNodeWithText("System default").assertExists()
            compose.onNodeWithText("Deutsch").assertDoesNotExist()
        }
        settings.pause().stop().destroy()
    }

    /** The Profile tab remembers its rows; choosing en-US while the system is en-US recreates nothing (API 30). */
    private fun profile() {
        val main = Robolectric.buildActivity(ReviewFlowsTest.Host::class.java).setup()
        val fm = main.get().supportFragmentManager
        fm.executePendingTransactions()
        ReflectionHelpers.callInstanceMethod<Unit>(
            fm.findFragmentById(R.id.main_fragment),
            "selectTab",
            ClassParameter.from(MainTab::class.java, MainTab.PROFILE),
        )
        idle()
        compose.onNodeWithText("System default").performScrollTo().assertExists()
        compose.onNodeWithText("Language").performClick()
        val intent = shadowOf(main.get()).nextStartedActivity
        assertEquals(R.string.tatrak_language, intent.getIntExtra(SettingsHomeFragment.EXTRA_PAGE, 0))
        main.pause().stop()
        val settings = Robolectric.buildActivity(SettingsHost::class.java, intent.setClass(app, SettingsHost::class.java)).setup()
        idle()
        compose.onNodeWithText("English (United States)").performScrollTo().performClick()
        idle()
        settings.pause().stop().destroy()
        main.restart().resume()
        idle()
        compose.onNodeWithText("English (United States)").performScrollTo().assertExists()
        main.pause().stop().destroy()
    }
}
