package am.cyberhayq.chat.update

import am.cyberhayq.chat.ui.chatlist.ChatListScreen
import am.cyberhayq.chat.ui.chatlist.ChatListState
import am.cyberhayq.chat.ui.chatlist.NoListActions
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import eu.siacs.conversations.R
import org.junit.Assert.assertEquals
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
class UpdateUiTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val release = Release("9.9.9", "- Faster chat list\n- Updates from GitHub releases", "${Updater.DOWNLOAD_PREFIX}x", 1)

    private fun chatList(dark: Boolean, name: String) {
        prefs.edit().putString(Updater.RELEASE, release.toJson()).commit()
        compose.setContent { TatrakTheme(darkTheme = dark) { ChatListScreen(ChatListState(loaded = true), NoListActions) } }
        compose.onNodeWithText("Version 9.9.9 is available").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun noticeLight() = chatList(dark = false, "update_notice_light")

    @Test @Config(qualifiers = "+night") fun noticeDark() = chatList(dark = true, "update_notice_dark")

    /** "Later" hides the notice and remembers the version; no release stored shows nothing. */
    @Test
    fun laterHidesTheNotice() {
        prefs.edit().putString(Updater.RELEASE, release.toJson()).commit()
        compose.setContent { TatrakTheme(darkTheme = false) { UpdateNotice() } }
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("Version 9.9.9 is available").assertDoesNotExist()
        assertEquals("9.9.9", prefs.getString(Updater.DISMISSED, null))
    }

    /** A stored beta follows "Beta versions" while the notice is shown (the preference written directly, as the settings screen also clears the release). */
    @Test
    fun betaNoticeFollowsTheSwitch() {
        val text = "Version 9.9.9-beta.1 is available"
        prefs.edit().putString(Updater.RELEASE, release.copy(version = "9.9.9-beta.1").toJson()).putBoolean(Updater.PRERELEASE, true).commit()
        compose.setContent { TatrakTheme(darkTheme = false) { UpdateNotice() } }
        compose.onNodeWithText(text).assertExists()
        prefs.edit().putBoolean(Updater.PRERELEASE, false).commit()
        compose.onNodeWithText(text).assertDoesNotExist()
        prefs.edit().putBoolean(Updater.PRERELEASE, true).commit()
        compose.onNodeWithText(text).assertExists()
    }

    private fun dialog(state: UpdateState, testBuild: Boolean, dark: Boolean, name: String) {
        compose.setContent { TatrakTheme(darkTheme = dark) { UpdateDialog(state, testBuild, {}, {}, {}) } }
        compose.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun notesLight() = dialog(UpdateState.Available(release), testBuild = true, dark = false, "update_notes_light")

    @Test @Config(qualifiers = "+night") fun notesDark() = dialog(UpdateState.Available(release), testBuild = false, dark = true, "update_notes_dark")

    @Test fun progressLight() = dialog(UpdateState.Downloading(release, 0.4f), testBuild = false, dark = false, "update_progress_light")

    @Test
    fun failureShowsItsText() {
        dialog(UpdateState.Failed(context.getString(R.string.tatrak_update_error_signature)), testBuild = false, dark = false, "update_failed_light")
        compose.onNodeWithText(context.getString(R.string.tatrak_update_error_signature)).assertExists()
    }
}
