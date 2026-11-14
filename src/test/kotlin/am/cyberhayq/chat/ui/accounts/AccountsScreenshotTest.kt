package am.cyberhayq.chat.ui.accounts

import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
}
