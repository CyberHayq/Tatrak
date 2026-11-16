package am.cyberhayq.chat.ui.settings

import am.cyberhayq.chat.ui.theme.Accent
import am.cyberhayq.chat.ui.theme.NightBackground
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import com.github.takahirom.roborazzi.captureRoboImage
import eu.siacs.conversations.R
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
class SettingsScreenshotTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsHome() {
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                SettingsHomeScreen(
                    AccountHeader("Ani", "ani@tatrak.am", null, 0xFFE17076.toInt()),
                    {},
                    listOf(
                        listOf(
                            SettingsRow(R.drawable.ic_palette_24dp, "Appearance", "Theme, accent colour, chat wallpaper", Color(0xFF3E88F7)) {},
                            SettingsRow(R.drawable.ic_touch_app_24dp, "Interface", "Theme, Colors, Screenshots, Input", Color(0xFF4AA6E0)) {},
                        ),
                        listOf(
                            SettingsRow(R.drawable.ic_notifications_24dp, "Notifications", "Grace period, Ringtone", Color(0xFFEB5545)) {},
                            SettingsRow(R.drawable.ic_security_24dp, "Security", "E2E Encryption", Color(0xFF5BB45B)) {},
                        ),
                    ),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_home.png")
    }

    /** The Appearance screen with fixed settings; [night] is the stored night background (shown only without dynamic colours). */
    @androidx.compose.runtime.Composable
    private fun Appearance(style: TatrakStyle, accent: Accent, dark: Boolean, night: NightBackground, wallpaper: String? = null) {
        TatrakTheme(darkTheme = dark, style = style, accent = accent, dynamicColors = false, nightBackground = night) {
            AppearanceScreen(
                style = style,
                onStyle = {},
                themes = listOf("automatic" to "Automatic", "light" to "Light", "dark" to "Dark"),
                theme = if (dark) "dark" else "light",
                onTheme = {},
                dynamicColorsAvailable = true,
                dynamicColors = false,
                onDynamicColors = {},
                nightBackground = night,
                onNightBackground = {},
                accent = accent,
                onAccent = {},
                wallpaper = wallpaper,
                onWallpaper = {},
                onWallpaperPhoto = {},
            )
        }
    }

    @Test
    @Config(qualifiers = "+night")
    fun appearanceDark() {
        compose.setContent { Appearance(TatrakStyle.TELEGRAM, Accent.BLUE, dark = true, NightBackground.BLUE_GREY, "preset:sunrise") }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/appearance_dark.png")
    }

    /** Each night background in both styles; the style cards preview the chosen one. */
    @Test
    @Config(qualifiers = "+night")
    fun appearanceNightBackgrounds() {
        var style by mutableStateOf(TatrakStyle.TELEGRAM)
        var night by mutableStateOf(NightBackground.BLUE_GREY)
        compose.setContent { Appearance(style, if (style == TatrakStyle.SLATE_SEA) Accent.SEA else Accent.BLUE, dark = true, night) }
        for (option in TatrakStyle.entries) for (background in NightBackground.entries) {
            compose.runOnIdle { style = option; night = background }
            compose.waitForIdle()
            val prefix = if (option == TatrakStyle.SLATE_SEA) "appearance_slate" else "appearance"
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_${background.key}_dark.png")
        }
    }

    @Test
    fun appearanceSlateLight() {
        // a stored night background changes nothing by day
        compose.setContent { Appearance(TatrakStyle.SLATE_SEA, Accent.SEA, dark = false, NightBackground.GRAPHITE) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/appearance_slate_light.png")
    }

    @Test
    fun appearanceGoldLight() {
        compose.setContent { Appearance(TatrakStyle.TELEGRAM, Accent.GOLD, dark = false, NightBackground.BLUE_GREY, "preset:sunrise") }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/appearance_gold_light.png")
    }

    /** On a 360 dp phone every accent keeps a full 48 x 48 dp target (the row wraps), none overlapping, all on screen. */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun appearanceAccentRowNarrow() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var dark by mutableStateOf(false)
        compose.setContent { Appearance(TatrakStyle.TELEGRAM, Accent.GOLD, dark, NightBackground.AMOLED) }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithContentDescription(context.getString(R.string.tatrak_accent_gold)).performScrollTo()
            val targets = Accent.entries.map { compose.onNodeWithContentDescription(context.getString(it.label)).getUnclippedBoundsInRoot() }
            val width = compose.onRoot().getUnclippedBoundsInRoot().right
            for ((i, t) in targets.withIndex()) {
                assertTrue("target ${Accent.entries[i]} below 48 dp: $t", t.right - t.left >= 48.dp && t.bottom - t.top >= 48.dp)
                assertTrue(t.left >= 0.dp && t.right <= width)
                for (other in targets.drop(i + 1)) {
                    assertTrue("targets overlap", t.right <= other.left || other.right <= t.left || t.bottom <= other.top || other.bottom <= t.top)
                }
            }
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/appearance_accents_360dp_${if (night) "dark" else "light"}.png")
        }
    }
}
