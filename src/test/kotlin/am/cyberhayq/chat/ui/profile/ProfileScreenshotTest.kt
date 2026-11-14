package am.cyberhayq.chat.ui.profile

import am.cyberhayq.chat.ui.chat.ChatHeader
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
class ProfileScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private object NoActions : ProfileActions {
        override fun back() {}

        override fun message() {}

        override fun toggleMute() {}

        override fun call(video: Boolean) {}

        override fun returnToCall() {}

        override fun search() {}

        override fun copyAddress() {}

        override fun share() {}

        override fun showQr() {}

        override fun openAvatar() {}

        override fun openMedia() {}

        override fun openMediaItem(key: String) {}

        override fun openDetails() {}

        override fun openMembers() {}

        override fun toggleBlock() {}

        override fun clearHistory() {}

        override fun deleteChat() {}
    }

    @Test
    fun contact() {
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                ProfileScreen(
                    ProfileState(
                        loaded = true,
                        header = ChatHeader(title = "Ani", subtitle = "last seen 5 minutes ago"),
                        address = "ani@tatrak.am",
                        canCall = true,
                        canVideo = true,
                        blocked = false,
                    ),
                    NoActions,
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/profile_contact.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun groupDark() {
        compose.setContent {
            TatrakTheme(darkTheme = true) {
                ProfileScreen(
                    ProfileState(
                        loaded = true,
                        header = ChatHeader(title = "Tatrak team", subtitle = "4 participants", isGroup = true, isMuted = true),
                        address = "team@conference.tatrak.am",
                        about = "Release planning and builds",
                        members =
                            listOf(
                                ProfileMember("a", "Ani", "Owner", null),
                                ProfileMember("b", "Bob", "Admin", null),
                                ProfileMember("c", "Davit", null, null),
                            ),
                        memberCount = 4,
                    ),
                    NoActions,
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/profile_group_dark.png")
    }
}
