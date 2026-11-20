package am.cyberhayq.chat.ui.blocklist

import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
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
class BlocklistScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private open class NoActions : BlocklistActions {
        override fun back() {}

        override fun search(needle: String) {}

        override fun block() {}

        override fun unblock(key: String) {}

        override fun cachedAvatar(key: String, sizePx: Int): Bitmap? = null

        // a generated picture for one row, letters for the others
        override suspend fun avatar(key: String, sizePx: Int): Bitmap? =
            if (key != "spam@example.org") null
            else Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply { Canvas(this).drawColor(0xFF7E57C2.toInt()) }
    }

    private val state =
        BlocklistState(
            listOf(
                BlockedItem("bot@spam.example", "bot@spam.example", AnnotatedString("bot@spam.example")),
                BlockedItem("mallory@example.org", "Mallory", AnnotatedString("mallory@example.org")),
                BlockedItem("spam@example.org", "Spam Account", AnnotatedString("spam@example.org")),
                BlockedItem("spam.example", "spam.example", AnnotatedString("spam.example")),
            ),
            loaded = true,
        )

    private fun show(state: BlocklistState, actions: BlocklistActions = NoActions(), dark: Boolean = false, style: TatrakStyle = TatrakStyle.TELEGRAM) =
        compose.setContent {
            TatrakTheme(darkTheme = dark, style = style, accent = TatrakStyle.effectiveAccent(style, null)) {
                BlocklistScreen(state, actions)
            }
        }

    @Test
    fun list() {
        show(state)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/blocklist.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun listDark() {
        show(state, dark = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/blocklist_dark.png")
    }

    @Test
    fun listSlate() {
        show(state, style = TatrakStyle.SLATE_SEA)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/blocklist_slate.png")
    }

    @Test
    fun empty() {
        show(BlocklistState(loaded = true))
        compose.onNodeWithText("No blocked addresses").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/blocklist_empty.png")
    }

    @Test
    fun nothingShownBeforeTheListLoads() {
        show(BlocklistState())
        compose.onNodeWithText("No blocked addresses").assertDoesNotExist()
    }

    @Test
    fun fabBlocksAnAddress() {
        var blocked = 0
        show(state, object : NoActions() {
            override fun block() {
                blocked++
            }
        })
        compose.onNodeWithContentDescription("Block XMPP address").performClick()
        assertEquals(1, blocked)
    }

    @Test
    fun longPressTapAndTalkBackUnblock() {
        val unblocked = ArrayList<String>()
        show(state, object : NoActions() {
            override fun unblock(key: String) {
                unblocked += key
            }
        })
        compose.onNodeWithText("Mallory").performTouchInput { longClick() }
        compose.onNodeWithText("spam.example").performClick()
        // TalkBack's action on the row is named "Unblock"
        val row = compose.onNode(hasText("Spam Account"), useUnmergedTree = false)
        row.assert(SemanticsMatcher("long press named Unblock") { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Unblock" })
        row.performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(listOf("mallory@example.org", "spam.example", "spam@example.org"), unblocked)
    }
}
