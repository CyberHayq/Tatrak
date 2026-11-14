package am.cyberhayq.chat.ui.settings

import am.cyberhayq.chat.ui.theme.Accent
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

    @Test
    @Config(qualifiers = "+night")
    fun appearanceDark() {
        compose.setContent {
            TatrakTheme(darkTheme = true, accent = Accent.EMBER) {
                AppearanceScreen(
                    style = TatrakStyle.TELEGRAM,
                    onStyle = {},
                    themes = listOf("automatic" to "Automatic", "light" to "Light", "dark" to "Dark"),
                    theme = "dark",
                    onTheme = {},
                    dynamicColorsAvailable = true,
                    dynamicColors = false,
                    onDynamicColors = {},
                    elegantDark = false,
                    onElegantDark = {},
                    accent = Accent.EMBER,
                    onAccent = {},
                    wallpaper = "preset:sunrise",
                    onWallpaper = {},
                    onWallpaperPhoto = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/appearance_ember_dark.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun appearanceSlateDark() {
        var elegant by mutableStateOf(false)
        compose.setContent {
            TatrakTheme(darkTheme = true, style = TatrakStyle.SLATE_SEA, accent = Accent.SEA, elegant = elegant) {
                AppearanceScreen(
                    style = TatrakStyle.SLATE_SEA,
                    onStyle = {},
                    themes = listOf("automatic" to "Automatic", "light" to "Light", "dark" to "Dark"),
                    theme = "dark",
                    onTheme = {},
                    dynamicColorsAvailable = true,
                    dynamicColors = false,
                    onDynamicColors = {},
                    elegantDark = elegant,
                    onElegantDark = {},
                    accent = Accent.SEA,
                    onAccent = {},
                    wallpaper = null,
                    onWallpaper = {},
                    onWallpaperPhoto = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/appearance_slate_dark.png")
        // Slate and Sea under the elegant switch: graphite night
        compose.runOnIdle { elegant = true }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/appearance_slate_elegant_dark.png")
    }

    @Test
    fun appearanceSlateLight() {
        compose.setContent {
            TatrakTheme(darkTheme = false, style = TatrakStyle.SLATE_SEA, accent = Accent.SEA) {
                AppearanceScreen(
                    style = TatrakStyle.SLATE_SEA,
                    onStyle = {},
                    themes = listOf("automatic" to "Automatic", "light" to "Light", "dark" to "Dark"),
                    theme = "light",
                    onTheme = {},
                    dynamicColorsAvailable = true,
                    dynamicColors = false,
                    onDynamicColors = {},
                    elegantDark = true,
                    onElegantDark = {},
                    accent = Accent.SEA,
                    onAccent = {},
                    wallpaper = null,
                    onWallpaper = {},
                    onWallpaperPhoto = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/appearance_slate_light.png")
    }

    @Test
    fun appearanceHouses() {
        var accent by mutableStateOf(Accent.STEEL)
        var dark by mutableStateOf(false)
        var elegant by mutableStateOf(false)
        compose.setContent {
            TatrakTheme(darkTheme = dark, accent = accent, elegant = elegant) {
                AppearanceScreen(
                    style = TatrakStyle.TELEGRAM,
                    onStyle = {},
                    themes = listOf("automatic" to "Automatic", "light" to "Light", "dark" to "Dark"),
                    theme = if (dark) "dark" else "light",
                    onTheme = {},
                    dynamicColorsAvailable = true,
                    dynamicColors = false,
                    onDynamicColors = {},
                    elegantDark = elegant,
                    onElegantDark = {},
                    accent = accent,
                    onAccent = {},
                    wallpaper = null,
                    onWallpaper = {},
                    onWallpaperPhoto = {},
                )
            }
        }
        for (house in Accent.entries.filter { (it.signature || it.palette) && it != Accent.EMBER }) for (night in listOf(false, true)) {
            compose.runOnIdle { accent = house; dark = night }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/appearance_${house.key}_${if (night) "dark" else "light"}.png")
        }
        // the opt-in elegant night: ordinary accents take it, a house keeps its own surfaces
        for (option in listOf(Accent.BLUE, Accent.GREEN, Accent.PINK, Accent.EMBER)) {
            compose.runOnIdle { accent = option; dark = true; elegant = true }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/appearance_elegant_${option.key}_dark.png")
        }
    }

    @Test
    fun appearanceGoldLight() {
        compose.setContent {
            TatrakTheme(darkTheme = false, accent = Accent.GOLD) {
                AppearanceScreen(
                    style = TatrakStyle.TELEGRAM,
                    onStyle = {},
                    themes = listOf("automatic" to "Automatic", "light" to "Light", "dark" to "Dark"),
                    theme = "light",
                    onTheme = {},
                    dynamicColorsAvailable = true,
                    dynamicColors = false,
                    onDynamicColors = {},
                    elegantDark = false,
                    onElegantDark = {},
                    accent = Accent.GOLD,
                    onAccent = {},
                    wallpaper = "preset:sunrise",
                    onWallpaper = {},
                    onWallpaperPhoto = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/appearance_gold_light.png")
    }
}
