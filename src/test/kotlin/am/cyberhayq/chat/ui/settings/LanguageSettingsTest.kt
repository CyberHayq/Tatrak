package am.cyberhayq.chat.ui.settings

import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.fragment.settings.settingsSections
import java.io.File
import java.text.Collator
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** The in-app language: API 30 runs AppCompat's own path (Android 8-12), API 35 the system per-app language. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [30], qualifiers = "w411dp-h891dp-xxhdpi")
class LanguageSettingsTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After fun reset() = setAppLanguage(null)

    private fun languageRow() = settingsSections(context) {}[0]

    @Test
    fun rowFollowsInterface() {
        assertEquals(
            listOf(R.string.tatrak_appearance, R.string.pref_title_interface, R.string.tatrak_language).map(context::getString),
            languageRow().map { it.title },
        )
        assertEquals("System default", languageRow()[2].summary)
    }

    @Test
    fun listHasSystemDefaultThenEveryConfiguredLocale() {
        val configured = Regex("""android:name="([^"]+)"""").findAll(File("src/main/res/xml/locales_config.xml").readText()).map { it.groupValues[1] }.toSet()
        val languages = appLanguages(context, configuredTags(context), null)
        assertEquals(AppLanguage(null, "System default", null), languages[0])
        assertEquals(configured, languages.drop(1).map { it.tag }.toSet())
        assertEquals(configured.size, languages.size - 1)
        val collator = Collator.getInstance(Locale.ROOT)
        languages.drop(1).zipWithNext().forEach { (a, b) -> assertTrue("$a before $b", collator.compare(a.label, b.label) <= 0) }
        assertEquals(AppLanguage("de", "Deutsch", "German"), languages.single { it.tag == "de" })
        assertEquals(AppLanguage("ru", "Русский", "Russian"), languages.single { it.tag == "ru" })
    }

    @Test
    fun choosingSetsAppLocalesAndSummary() {
        val tags = configuredTags(context)
        var chosen by mutableStateOf(chosenTag(tags, appLocale()))
        compose.setContent {
            TatrakTheme(darkTheme = false) { LanguageScreen(appLanguages(context, tags, chosen), chosen) { setAppLanguage(it); chosen = chosenTag(tags, appLocale()) } }
        }
        compose.onNodeWithText("System default").assertIsSelected()
        compose.onNodeWithText("Deutsch").performScrollTo().performClick()
        compose.onNodeWithText("Deutsch").assertIsSelected()
        assertEquals("de", AppCompatDelegate.getApplicationLocales().toLanguageTags())
        assertEquals("Deutsch", languageRow()[2].summary)
        compose.onNodeWithText("System default").performScrollTo().performClick()
        compose.onNodeWithText("System default").assertIsSelected()
        assertNull(appLocale())
        assertEquals("System default", languageRow()[2].summary)
    }

    /** Android 13+: AppCompat hands the choice to the system per-app language (needs a live AppCompat activity). */
    @Test
    @Config(sdk = [35])
    fun choosingOnAndroid13UsesSystemSetting() {
        Robolectric.buildActivity(AppCompatActivity::class.java).setup().use {
            setAppLanguage("uk")
            assertEquals("uk", AppCompatDelegate.getApplicationLocales().toLanguageTags())
            assertEquals("Українська", appLanguageSummary(context))
        }
    }

    /** Exact first, then language and script; a stored tag no longer configured becomes its own checked row. */
    @Test
    fun matchesTheStoredChoice() {
        val tags = configuredTags(context)
        assertNull(chosenTag(tags, null))
        assertEquals("pt-BR", chosenTag(tags, Locale.forLanguageTag("pt-BR")))
        assertEquals("de", chosenTag(tags, Locale.forLanguageTag("de-DE")))
        assertEquals("en-US", chosenTag(tags, Locale.forLanguageTag("en-GB")))
        assertEquals("zh-TW", chosenTag(tags, Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals("zh-CN", chosenTag(tags, Locale.forLanguageTag("zh-SG")))
        assertEquals("hy-IR", chosenTag(tags, Locale.forLanguageTag("hy-IR")))
        // a stored tag no translation has (Frisian): its own row right under System default
        assertEquals("fy-NL", chosenTag(tags, Locale.forLanguageTag("fy-NL")))
        val rows = appLanguages(context, tags, "fy-NL")
        assertEquals(listOf(null, "fy-NL"), rows.take(2).map { it.tag })
        assertEquals(tags.size + 2, rows.size)
        setAppLanguage("de-DE")
        assertEquals("Deutsch", appLanguageSummary(context))
    }

    @Test
    fun armenianLabels() {
        assertEquals("Հայերեն (խորհրդային ուղղագրություն)", languageLabel(context, "hy"))
        assertEquals("Հայերեն (աւանդական ուղղագրութիւն)", languageLabel(context, "hy-IR"))
        assertEquals("Armenian (Soviet orthography)", languageLabel(context, "hy", own = false))
        assertEquals("Armenian (traditional orthography)", languageLabel(context, "hy-IR", own = false))
    }

    @Test
    @Config(sdk = [35])
    fun screenshotLight() = screenshot(dark = false)

    @Test
    @Config(sdk = [35], qualifiers = "+night")
    fun screenshotDark() = screenshot(dark = true)

    private fun screenshot(dark: Boolean) {
        compose.setContent { TatrakTheme(darkTheme = dark) { LanguageScreen(appLanguages(context, configuredTags(context), "ca"), "ca") {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_language_${if (dark) "dark" else "light"}.png")
    }
}
