package am.cyberhayq.chat.ui.participants

import am.cyberhayq.chat.ui.common.MenuEntry
import am.cyberhayq.chat.ui.theme.TatrakTheme
import androidx.compose.ui.test.swipeUp
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.BarBlur
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import eu.siacs.conversations.R
import im.conversations.android.xmpp.model.muc.Affiliation
import im.conversations.android.xmpp.model.muc.Role
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
class ParticipantsScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private open class NoActions : ParticipantsActions {
        override fun back() {}

        override fun search(needle: String) {}

        override fun open(key: String) {}

        override fun menu(key: String): List<MenuEntry> = emptyList()

        override fun cachedAvatar(key: String, sizePx: Int): Bitmap? = null

        override suspend fun avatar(key: String, sizePx: Int): Bitmap? = null
    }

    private val state =
        ParticipantsState(
            listOf(
                Participant("a", "Ani", "ani@tatrak.am", R.string.owner),
                Participant("b", "Aram", "Developers · aram@tatrak.am", R.string.admin, pgpKey = "0x1A2B3C4D5E6F7081"),
                Participant("c", "Narek", "", R.string.moderator),
                Participant("d", "Lilit", "lilit@example.org", R.string.member),
                Participant("e", "Sona", "Offline · sona@example.org", R.string.member),
                Participant("f", "guest42", "", R.string.participant),
            ),
            loaded = true,
        )

    @Test
    fun labelsAsTheClassicTags() {
        assertEquals(R.string.owner, attributeLabel(Affiliation.OWNER, Role.MODERATOR))
        assertEquals(R.string.admin, attributeLabel(Affiliation.ADMIN, Role.MODERATOR))
        assertEquals(R.string.member, attributeLabel(Affiliation.MEMBER, Role.PARTICIPANT))
        assertEquals(R.string.outcast, attributeLabel(Affiliation.OUTCAST, Role.NONE))
        assertEquals(R.string.moderator, attributeLabel(Affiliation.NONE, Role.MODERATOR))
        assertEquals(R.string.participant, attributeLabel(Affiliation.NONE, Role.PARTICIPANT))
        assertEquals(R.string.visitor, attributeLabel(Affiliation.NONE, Role.VISITOR))
        assertEquals(0, attributeLabel(Affiliation.NONE, Role.NONE))
    }

    @Test
    fun list() {
        compose.setContent { TatrakTheme(darkTheme = false) { ParticipantsScreen(state, NoActions()) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/participants.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun listDark() {
        compose.setContent { TatrakTheme(darkTheme = true) { ParticipantsScreen(state, NoActions()) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/participants_dark.png")
    }

    @Test
    fun searching() {
        var searched: String? = null
        val actions = object : NoActions() {
            override fun search(needle: String) {
                searched = needle
            }
        }
        // the host filters; the screen shows what it publishes
        val found = state.copy(participants = state.participants.filter { it.name.contains("ar", ignoreCase = true) })
        compose.setContent { TatrakTheme(darkTheme = false) { ParticipantsScreen(found, actions, initialSearch = "ar") } }
        compose.runOnIdle { assertEquals("ar", searched) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/participants_search.png")
    }

    @Test
    fun tapShowsTheHostMenu() {
        var picked: String? = null
        val actions = object : NoActions() {
            override fun menu(key: String) =
                listOf(
                    MenuEntry("Make admin") { picked = key },
                    MenuEntry("Send private message", enabled = false) {},
                )
        }
        compose.setContent { TatrakTheme(darkTheme = false) { ParticipantsScreen(state, actions) } }
        compose.onNodeWithText("Lilit").performClick()
        compose.onNodeWithText("Send private message").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/participants_menu.png")
        compose.onNodeWithText("Make admin").performClick()
        assertEquals("d", picked)
    }

    @Test
    fun longPressMentions() {
        var opened: String? = null
        val actions = object : NoActions() {
            override fun open(key: String) {
                opened = key
            }

            override fun menu(key: String): List<MenuEntry> = error("a long press must not open the menu")
        }
        compose.setContent { TatrakTheme(darkTheme = false) { ParticipantsScreen(state, actions) } }
        compose.onNodeWithText("Lilit").performTouchInput { longClick() }
        assertEquals("d", opened)
    }

    @Test
    fun keyIdOpensTheKey() {
        var opened: String? = null
        val actions = object : NoActions() {
            override fun openPgpKey(key: String) {
                opened = key
            }
        }
        compose.setContent { TatrakTheme(darkTheme = false) { ParticipantsScreen(state, actions) } }
        compose.onNodeWithContentDescription("OpenPGP Key ID 0x1A2B3C4D5E6F7081").performClick()
        assertEquals("b", opened)
    }

    /** Floating bars (Glass): the top bar in a capsule over the list; frosted with the rows scrolled under it. */
    @Test
    fun floatingLight() = floating(dark = false, BarBlur.OFF, "participants_floating_light")

    @Test
    @Config(qualifiers = "+night")
    fun floatingDark() = floating(dark = true, BarBlur.OFF, "participants_floating_dark")

    @Test
    fun floatingFrosted() = floating(dark = false, BarBlur.FROSTED, "participants_floating_frosted", scrolled = true)

    private fun floating(dark: Boolean, blur: BarBlur, file: String, scrolled: Boolean = false) {
        compose.setContent {
            TatrakTheme(darkTheme = dark, style = TatrakStyle.GLASS, barBlur = blur) { ParticipantsScreen(if (scrolled) state.copy(participants = (1..12).map { i -> state.participants[i % 6].copy(key = "k$i") }) else state, NoActions()) }
        }
        if (scrolled) {
            compose.onRoot().performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 100f, durationMillis = 800) }
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file.png")
    }
}
