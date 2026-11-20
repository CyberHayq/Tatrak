package am.cyberhayq.chat.ui.avatar

import am.cyberhayq.chat.ui.common.MenuEntry
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
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
class AvatarScreensScreenshotTest {

    @get:Rule val compose = createComposeRule()

    /** A generated picture: a sun over a sea. */
    private fun picture(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(0xFF4FC3F7.toInt())
            canvas.drawRect(0f, height * 0.6f, width.toFloat(), height.toFloat(), Paint().apply { color = 0xFF0277BD.toInt() })
            canvas.drawCircle(width / 2f, height * 0.4f, width / 5f, Paint().apply { color = 0xFFFFD54F.toInt() })
        }

    private class Recorder : PublishAvatarActions {
        val calls = ArrayList<String>()

        override fun back() {
            calls += "back"
        }

        override fun pick() {
            calls += "pick"
        }

        override fun backToDefault() {
            calls += "default"
        }

        override fun contactsOnly(checked: Boolean) {
            calls += "contactsOnly=$checked"
        }

        override fun publish() {
            calls += "publish"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    private fun account(menu: List<MenuEntry> = listOf(MenuEntry("Delete avatar") {})) =
        PublishAvatarState(
            title = "Publish avatar",
            avatar = picture(288, 288),
            backToDefault = true,
            contactsOnly = ContactsOnly(checked = true, enabled = true, loading = false),
            publishLabel = "Publish",
            publishEnabled = true,
            cancelLabel = "Cancel",
            menu = menu,
        )

    private val group =
        PublishAvatarState(
            title = "Group chat avatar",
            publishLabel = "Publish",
            publishEnabled = false,
            cancelLabel = "Cancel",
        )

    private fun publish(state: PublishAvatarState, actions: PublishAvatarActions = Recorder(), dark: Boolean = false, style: TatrakStyle = TatrakStyle.TELEGRAM) =
        compose.setContent {
            TatrakTheme(darkTheme = dark, style = style, accent = TatrakStyle.effectiveAccent(style, null)) { PublishAvatarScreen(state, actions) }
        }

    @Test
    fun publishAccount() {
        publish(account())
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/avatar_publish.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun publishAccountDark() {
        publish(account(), dark = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/avatar_publish_dark.png")
    }

    @Test
    fun publishAccountSlate() {
        publish(account(), style = TatrakStyle.SLATE_SEA)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/avatar_publish_slate.png")
    }

    /** No server support: the warning, the switch and Publish disabled; the initial setup's Skip without an up arrow. */
    @Test
    fun publishWarningInSetup() {
        val state =
            account().copy(
                showBack = false,
                backToDefault = false,
                contactsOnly = ContactsOnly(checked = false, enabled = false, loading = false),
                warning = "Your server does not support the publication of avatars",
                publishEnabled = false,
                cancelLabel = "Skip",
            )
        publish(state)
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithText("Publish").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/avatar_publish_warning.png")
    }

    @Test
    fun publishGroup() {
        publish(group)
        compose.onNodeWithText("Show to contacts only").assertDoesNotExist()
        compose.onNodeWithText("(Or long press to bring back default)").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/avatar_publish_group.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun publishGroupPublishingDark() {
        publish(group.copy(avatar = picture(288, 288), publishLabel = "Publishing…"), dark = true)
        compose.onNodeWithText("Publishing…").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/avatar_publish_group_dark.png")
    }

    @Test
    fun loadingAccessModelHidesTheSwitch() {
        val recorder = Recorder()
        publish(account().copy(contactsOnly = ContactsOnly(checked = false, enabled = true, loading = true)), recorder)
        compose.onNodeWithText("Show to contacts only").assertDoesNotExist()
    }

    @Test
    fun actionsCallTheHost() {
        var deleted = 0
        val recorder = Recorder()
        publish(account(listOf(MenuEntry("Delete avatar") { deleted++ })), recorder)
        val avatar = compose.onNodeWithContentDescription("Your avatar. Tap to select new avatar from gallery.")
        avatar.performClick()
        avatar.performTouchInput { longClick() }
        avatar.assert(SemanticsMatcher("long press named") { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Bring back the default picture" })
        compose.onNodeWithText("Show to contacts only").performClick()
        compose.onNodeWithText("Publish").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete avatar").performClick()
        assertEquals(listOf("pick", "default", "contactsOnly=false", "publish", "cancel", "back"), recorder.calls)
        assertEquals(1, deleted)
    }

    @Test
    fun noLongPressWithoutADefault() {
        val recorder = Recorder()
        publish(group, recorder)
        compose.onNodeWithContentDescription("Your avatar. Tap to select new avatar from gallery.").performTouchInput { longClick() }
        compose.onNodeWithText("Publish").performClick()
        // as the classic image without a long-click listener: the press ends as a tap; Publish is disabled
        assertEquals(listOf("pick"), recorder.calls)
    }

    @Test
    fun viewPicture() {
        var back = 0
        compose.setContent { TatrakTheme(darkTheme = false) { ProfilePictureScreen("Ani Petrosyan", picture(640, 640)) { back++ } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/avatar_view.png")
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    @Test
    @Config(qualifiers = "+night")
    fun viewPictureDark() {
        compose.setContent { TatrakTheme(darkTheme = true, style = TatrakStyle.SLATE_SEA) { ProfilePictureScreen("Ani Petrosyan", picture(640, 640)) {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/avatar_view_dark.png")
    }

    @Test
    fun viewUnreadablePicture() {
        compose.setContent { TatrakTheme(darkTheme = false) { ProfilePictureScreen("Ani Petrosyan", null) {} } }
        compose.onNodeWithText("Ani Petrosyan").assertExists()
    }
}
