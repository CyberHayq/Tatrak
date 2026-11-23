package am.cyberhayq.chat.ui.settings

import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.icu.util.ULocale
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.XmppActivity
import java.text.Collator
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

/** One row of the language list; [tag] null is "System default". */
@Immutable data class AppLanguage(val tag: String?, val label: String, val name: String?)

/** The tags of `locales_config` (LocaleConfig is API 33+). */
fun configuredTags(context: Context): List<String> {
    val tags = mutableListOf<String>()
    context.resources.getXml(R.xml.locales_config).use { xml ->
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG && xml.name == "locale") {
                xml.getAttributeValue("http://schemas.android.com/apk/res/android", "name")?.let(tags::add)
            }
        }
    }
    return tags
}

/**
 * The list's tag for [current]: the same locale, else the same language and script (Android 13's picker may store de-DE
 * for our de); a stored tag no longer configured (AppCompat keeps it on 8-12 after an update) stays itself. Null: system.
 */
fun chosenTag(tags: List<String>, current: Locale?): String? {
    current ?: return null
    fun likely(locale: Locale) = ULocale.addLikelySubtags(ULocale.forLocale(locale)).let { it.language to it.script }
    val wanted = likely(current)
    return tags.firstOrNull { Locale.forLanguageTag(it) == current }
        ?: tags.firstOrNull { likely(Locale.forLanguageTag(it)) == wanted }
        ?: current.toLanguageTag()
}

/** "System default", [chosen] when it is not configured (so one row is always checked), then [tags] by their own names. */
fun appLanguages(context: Context, tags: List<String>, chosen: String?): List<AppLanguage> {
    fun entry(tag: String) = AppLanguage(tag, languageLabel(context, tag), languageLabel(context, tag, own = false))
    val collator = Collator.getInstance(Locale.ROOT)
    return listOf(AppLanguage(null, context.getString(R.string.tatrak_language_system), null)) +
        listOfNotNull(chosen?.takeIf { it !in tags }?.let(::entry)) +
        tags.map(::entry).sortedWith(compareBy(collator) { it.label })
}

/** [tag]'s name, capitalised: in its own language, or with [own] false in the app's current one; Armenian from fixed strings. */
fun languageLabel(context: Context, tag: String, own: Boolean = true): String {
    val locale = Locale.forLanguageTag(tag)
    if (locale.language == "hy") {
        val traditional = locale.country == "IR"
        return context.getString(
            when {
                own && traditional -> R.string.tatrak_language_hy_IR
                own -> R.string.tatrak_language_hy
                traditional -> R.string.tatrak_language_hy_IR_name
                else -> R.string.tatrak_language_hy_name
            }
        )
    }
    val target = if (own) locale else context.resources.configuration.locales[0]
    return locale.getDisplayName(target).replaceFirstChar { it.titlecase(target) }
}

/** The language chosen in the app (or in Android 13+'s per-app setting); null follows the system. */
fun appLocale(): Locale? = AppCompatDelegate.getApplicationLocales()[0]

/** The settings home row's summary. */
fun appLanguageSummary(context: Context): String =
    chosenTag(configuredTags(context), appLocale())?.let { languageLabel(context, it) } ?: context.getString(R.string.tatrak_language_system)

/** Sets the app language; AppCompat stores it below API 33 (manifest `autoStoreLocales`) and recreates the activities. */
fun setAppLanguage(tag: String?) =
    AppCompatDelegate.setApplicationLocales(if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))

/**
 * [context] in [locales] below API 33, where services and the application keep the system language: what
 * `ContextCompat.getContextForLanguage` does, but for a given list (it reads AppCompat's file, which is written
 * asynchronously after a choice). As AppCompat does for activities, the system locales follow the chosen ones, so a
 * string missing in the chosen language falls back like the UI. On 33+ the system applies the per-app language to the
 * whole process.
 */
fun languageContext(context: Context, locales: LocaleListCompat): Context =
    languageContext(context, locales, ConfigurationCompat.getLocales(context.resources.configuration))

private fun languageContext(context: Context, locales: LocaleListCompat, system: LocaleListCompat): Context {
    if (Build.VERSION.SDK_INT >= 33 || locales.isEmpty) return context
    val combined = LinkedHashSet<Locale>()
    for (i in 0 until locales.size()) locales[i]?.let(combined::add)
    for (i in 0 until system.size()) system[i]?.let(combined::add)
    // only the locales: font scale, night mode and the other qualifiers follow the context's configuration
    val configuration = Configuration()
    ConfigurationCompat.setLocales(configuration, LocaleListCompat.create(*combined.toTypedArray()))
    return context.createConfigurationContext(configuration)
}

private class LanguageContext(val app: Context, val locales: LocaleListCompat, val system: LocaleListCompat, val context: Context)

@Volatile private var lastLanguageContext: LanguageContext? = null

/**
 * The application context in the in-app language, for text built where an activity must not be kept (view models,
 * background snapshot builders, toasts after the screen may be gone). Below API 33 the application keeps the system
 * language. Made again when the chosen or the system locales change, so a view model kept across the recreation
 * after a choice builds its next snapshot in the new language.
 */
fun appLanguageContext(context: Context): Context {
    val app = context.applicationContext
    // the system applies the per-app language; AppCompat's lookup walks its delegates, not safe off the main thread
    if (Build.VERSION.SDK_INT >= 33) return app
    val locales = AppCompatDelegate.getApplicationLocales()
    val system = ConfigurationCompat.getLocales(app.resources.configuration)
    lastLanguageContext?.let { if (it.app === app && it.locales == locales && it.system == system) return it.context }
    return languageContext(app, locales, system).also { lastLanguageContext = LanguageContext(app, locales, system, it) }
}

@Composable
fun LanguageScreen(languages: List<AppLanguage>, chosen: String?, onPick: (String?) -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        LazyColumn(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).selectableGroup(),
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        ) {
            items(languages, key = { it.tag.orEmpty() }) { language ->
                RadioRow(language.label, language.tag == chosen, language.name) { onPick(language.tag) }
            }
        }
    }
}

/** The settings' Language page, on every Android version (Android 13+ also has the system per-app setting, kept in sync). */
class LanguageFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val tags = configuredTags(requireContext())
            setContent {
                TatrakTheme {
                    // the activity is recreated in the new language; until then the mark moves at once
                    var chosen by remember { mutableStateOf(chosenTag(tags, appLocale())) }
                    val languages = remember(chosen) { appLanguages(requireContext(), tags, chosen) }
                    LanguageScreen(languages, chosen) {
                        setAppLanguage(it)
                        chosen = chosenTag(tags, appLocale())
                        // a running service re-labels its channels now; otherwise XmppActivity's next bind does
                        (activity as? XmppActivity)?.xmppConnectionService?.notificationService?.setLanguage(
                            AppCompatDelegate.getApplicationLocales()
                        )
                    }
                }
            }
        }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.tatrak_language)
    }
}
