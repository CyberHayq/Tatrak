package am.cyberhayq.chat.ui.accounts

import am.cyberhayq.chat.ui.theme.TatrakTheme
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.BarBlur
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.github.takahirom.roborazzi.captureRoboImage
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
class AccountsScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private object NoActions : AccountsActions {
        override fun back() {}

        override fun open(key: String) {}

        override fun setEnabled(key: String, enabled: Boolean) {}

        override fun menu(key: String, item: AccountMenu) {}

        override fun action(action: AccountsAction) {}
    }

    private val state =
        AccountsState(
            accounts =
                listOf(
                    AccountEntry("1", "test@example.am", "Online", AccountStatus.CONNECTED, true),
                    AccountEntry("2", "test@example.org", "Disabled", AccountStatus.QUIET, false),
                    AccountEntry("3", "old@jabber.example", "Unauthorized", AccountStatus.ERROR, true),
                )
        )

    @Test
    fun list() {
        compose.setContent { TatrakTheme(darkTheme = false) { AccountsScreen(state, NoActions) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/accounts.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun listDark() {
        compose.setContent { TatrakTheme(darkTheme = true) { AccountsScreen(state, NoActions) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/accounts_dark.png")
    }

    /** Floating bars (Glass): the top bar in a capsule over the list; frosted with the rows scrolled under it. */
    @Test
    fun floatingLight() = floating(dark = false, BarBlur.OFF, "accounts_floating_light")

    @Test
    @Config(qualifiers = "+night")
    fun floatingDark() = floating(dark = true, BarBlur.OFF, "accounts_floating_dark")

    @Test
    fun floatingFrosted() = floating(dark = false, BarBlur.FROSTED, "accounts_floating_frosted", scrolled = true)

    /** The list pads for the floating bar: the first row starts below the bar's title, not under it at the top. */
    @Test
    fun floatingListStartsBelowTheBar() {
        compose.setContent { TatrakTheme(darkTheme = false, style = TatrakStyle.GLASS, barBlur = BarBlur.OFF) { AccountsScreen(state, NoActions) } }
        val title = compose.onNodeWithText(ApplicationProvider.getApplicationContext<Application>().getString(R.string.title_activity_manage_accounts)).getBoundsInRoot()
        val row = compose.onNodeWithText("test@example.am").getBoundsInRoot()
        assertTrue("row ${row.top} under the bar ${title.bottom}", row.top > title.bottom)
    }

    /** Telegram 13's header pieces: the back button centred on its 46 dp glass, the title 76 dp from the side, as Telegram's. */
    @Test
    fun floatingHeaderPieces() {
        compose.setContent { TatrakTheme(darkTheme = false, style = TatrakStyle.GLASS, barBlur = BarBlur.OFF) { AccountsScreen(state, NoActions) } }
        val context = ApplicationProvider.getApplicationContext<Application>()
        val back = compose.onNodeWithContentDescription(context.getString(R.string.tatrak_chat_back)).getBoundsInRoot()
        val title = compose.onNodeWithText(context.getString(R.string.title_activity_manage_accounts)).getBoundsInRoot()
        // the button's centre on the 46 dp glass 6 dp from the side
        assertEquals(29f, ((back.left + back.right) / 2).value, 0.5f)
        assertEquals(76f, title.left.value, 1f)
    }

    private fun floating(dark: Boolean, blur: BarBlur, file: String, scrolled: Boolean = false) {
        compose.setContent {
            TatrakTheme(darkTheme = dark, style = TatrakStyle.GLASS, barBlur = blur) { AccountsScreen(if (scrolled) state.copy(accounts = (1..12).map { i -> state.accounts[i % 3].copy(key = "k$i") }) else state, NoActions) }
        }
        if (scrolled) {
            compose.onRoot().performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 100f, durationMillis = 800) }
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file.png")
    }
}
