package am.cyberhayq.chat.ui.chatlist

import androidx.compose.ui.text.AnnotatedString
import am.cyberhayq.chat.ui.calls.CallEntry
import am.cyberhayq.chat.ui.calls.CallsActions
import am.cyberhayq.chat.ui.calls.CallsScreen
import am.cyberhayq.chat.ui.calls.CallsState
import am.cyberhayq.chat.ui.newchat.NewChatActions
import am.cyberhayq.chat.ui.newchat.NewChatEntry
import am.cyberhayq.chat.ui.newchat.NewChatMenu
import am.cyberhayq.chat.ui.newchat.NewChatRow
import am.cyberhayq.chat.ui.newchat.NewChatScreen
import am.cyberhayq.chat.ui.newchat.NewChatState
import am.cyberhayq.chat.ui.settings.ProfileTab
import am.cyberhayq.chat.ui.settings.SettingsRow
import am.cyberhayq.chat.ui.theme.BarBackdrop
import am.cyberhayq.chat.ui.theme.BarBlur
import am.cyberhayq.chat.ui.theme.LocalFloatingBars
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import eu.siacs.conversations.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** The dock's main tabs ([MainTabs]) as the chat list fragment wires them, with sample pages. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MainTabsTest {

    @get:Rule val compose = createComposeRule()

    private var back: OnBackPressedDispatcher? = null

    /** A dock item: clickable and selectable, unlike a page title of the same text. */
    private fun dockItem(label: String) =
        compose.onNode(hasText(label) and hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))

    private fun show(style: TatrakStyle = TatrakStyle.GLASS, dark: Boolean = false, start: MainTab = MainTab.CHATS) {
        compose.setContent {
            TatrakTheme(darkTheme = dark, style = style, barBlur = BarBlur.OFF) {
                back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                Tabs(start)
            }
        }
    }

    @Test
    fun aTapSelectsTheTabAndBackReturnsToChats() {
        show()
        dockItem("Chats").assertIsSelected()
        dockItem("Calls").performClick()
        compose.waitForIdle()
        dockItem("Calls").assertIsSelected()
        dockItem("Chats").assertIsNotSelected()
        compose.onNodeWithText("Davit").assertIsDisplayed()
        compose.onNodeWithText("Saved Messages").assertDoesNotExist()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.waitForIdle()
        dockItem("Chats").assertIsSelected()
        compose.onNodeWithText("Saved Messages").assertIsDisplayed()
        compose.onNodeWithText("Davit").assertDoesNotExist()
    }

    /** Telegram: the old and the new page side by side, the new one from the side of its tab; the dock stays put. */
    @Test
    fun midSwitchBothPagesShowSideBySide() {
        show()
        val dockTop = dockItem("Chats").getUnclippedBoundsInRoot().top
        compose.mainClock.autoAdvance = false
        dockItem("Calls").performClick()
        frames(6)
        val chats = compose.onNodeWithText("Saved Messages").getUnclippedBoundsInRoot()
        val calls = compose.onNodeWithText("Davit").getUnclippedBoundsInRoot()
        // Calls lies after Chats in the floating dock: it comes in from the right, Chats leaves to the left
        assertTrue("chats ${chats.left} calls ${calls.left}", chats.left < 0.dp && calls.left > chats.left)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/main_tabs_switching.png")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Saved Messages").assertDoesNotExist()
        assertEquals(dockTop, dockItem("Calls").getUnclippedBoundsInRoot().top)
    }

    /** Back to an earlier tab: the pages move the other way. */
    @Test
    fun anEarlierTabComesInFromTheLeft() {
        show(start = MainTab.PROFILE)
        compose.mainClock.autoAdvance = false
        dockItem("Contacts").performClick()
        frames(6)
        val contacts = compose.onNodeWithText("Bob").getUnclippedBoundsInRoot()
        assertTrue("contacts ${contacts.left}", contacts.left < 0.dp)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun aTapOnTheCurrentTabScrollsItToTheTop() {
        show(start = MainTab.CALLS)
        compose.onRoot().performTouchInput { swipeUp(startY = bottom - 400f, endY = top + 200f, durationMillis = 300) }
        compose.waitForIdle()
        compose.onNodeWithText("Call 0").assertDoesNotExist()
        dockItem("Calls").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Call 0").assertIsDisplayed()
        dockItem("Calls").assertIsSelected()
    }

    /** A page keeps its state while another tab shows: the Contacts search is still open on return. */
    @Test
    fun aPageKeepsItsStateAcrossTabs() {
        show(start = MainTab.CONTACTS)
        compose.onNode(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Search"))).performClick()
        compose.waitForIdle()
        dockItem("Calls").performClick()
        compose.waitForIdle()
        dockItem("Contacts").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Search contacts").assertIsDisplayed()
        // Back closes the search first, then goes to Chats
        compose.runOnIdle { back!!.onBackPressed() }
        compose.waitForIdle()
        dockItem("Contacts").assertIsSelected()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.waitForIdle()
        dockItem("Chats").assertIsSelected()
    }

    @Test
    fun floatingTabsLight() = tabs(TatrakStyle.GLASS, dark = false, "floating", listOf("Chats", "Contacts", "Calls", "Profile"))

    @Test
    @Config(qualifiers = "+night")
    fun floatingTabsDark() = tabs(TatrakStyle.GLASS, dark = true, "floating", listOf("Chats", "Contacts", "Calls", "Profile"))

    @Test
    fun slateTabsLight() = tabs(TatrakStyle.SLATE_SEA, dark = false, "slate", listOf("Chats", "Calls", "Contacts", "Settings"))

    @Test
    @Config(qualifiers = "+night")
    fun slateTabsDark() = tabs(TatrakStyle.SLATE_SEA, dark = true, "slate", listOf("Chats", "Calls", "Contacts", "Settings"))

    /** Each tab of a dock style, in its order; on the other tabs the dock is the same, only the selection moves. */
    private fun tabs(style: TatrakStyle, dark: Boolean, name: String, labels: List<String>) {
        show(style, dark)
        val dockTop = dockItem("Chats").getUnclippedBoundsInRoot().top
        for (label in labels) {
            dockItem(label).performClick()
            compose.waitForIdle()
            dockItem(label).assertIsSelected()
            assertEquals(label, dockTop, dockItem(label).getUnclippedBoundsInRoot().top)
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/main_tabs_${name}_${label.lowercase()}_${if (dark) "dark" else "light"}.png")
        }
    }

    /** Paused clock: the switch starts in an effect, which runs on the main looper. */
    private fun frames(n: Int) =
        repeat(n) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
        }

    @Composable
    private fun Tabs(start: MainTab) {
        var tab by remember { mutableStateOf(start) }
        var top by remember { mutableIntStateOf(0) }
        val select: (MainTab) -> Unit = { if (it == tab) top++ else tab = it }
        val dock = { selected: MainTab -> @Composable { backdrop: BarBackdrop? -> TabDock(selected, null, select, backdrop) } }
        MainTabs(tab, mainTabOrder(LocalFloatingBars.current), onBack = { tab = MainTab.CHATS }, dock = { t, b -> TabDock(t, null, select, b) }) { page ->
            when (page) {
                MainTab.CHATS -> ChatListScreen(chats, NoListActions, onTab = select)
                MainTab.CONTACTS -> NewChatScreen(contacts, NoNewChat, contactsOnly = true, dock = dock(page), scrollToTop = top)
                MainTab.CALLS -> CallsScreen(calls, NoCalls, dock = dock(page), scrollToTop = top)
                MainTab.PROFILE -> ProfileTab(null, {}, sections, dock(page), top)
            }
        }
    }

    private val chats =
        ChatListState(
            loaded = true,
            chats =
                listOf("Saved Messages", "Ani", "Tatrak team").mapIndexed { i, title ->
                    ChatListItem(
                        uuid = "c$i",
                        title = title,
                        isGroup = i == 2,
                        preview = "Hello",
                        previewSender = null,
                        isDraft = false,
                        isServicePreview = false,
                        isTyping = false,
                        timestamp = System.currentTimeMillis() - i * 3_600_000L,
                        unreadCount = 0,
                        isMuted = false,
                        isPinned = false,
                        outgoingStatus = null,
                        isOnline = false,
                        isArchived = false,
                        avatar = null,
                        isSavedMessages = i == 0,
                    )
                },
        )

    private val contacts =
        NewChatState(
            rows = NewChatRow.entries,
            contacts = listOf(NewChatEntry("a", "Ani", AnnotatedString("online"), online = true), NewChatEntry("b", "Bob", AnnotatedString("bob@tatrak.am"))),
            loaded = true,
        )

    private val calls =
        CallsState(
            (0 until 20).map { i ->
                val name = if (i == 1) "Davit" else "Call $i"
                CallEntry("$i", "c$i", name, System.currentTimeMillis() - i * 3_600_000L, received = true, missed = false, icon = R.drawable.ic_call_received_24dp)
            },
            loaded = true,
        )

    private val sections =
        listOf(
            listOf(SavedMessages.profileRow(ApplicationProvider.getApplicationContext()) {}),
            listOf(
                SettingsRow(R.drawable.ic_palette_24dp, "Appearance", "Theme, accent colour, chat wallpaper", Color(0xFF3E88F7)) {},
                SettingsRow(R.drawable.ic_notifications_24dp, "Notifications", "Grace period, Ringtone", Color(0xFFEB5545)) {},
            ),
        )

    private object NoCalls : CallsActions {
        override fun back() {}

        override fun openChat(chat: String) {}

        override fun call(chat: String, video: Boolean) {}
    }

    private object NoNewChat : NewChatActions {
        override fun back() {}

        override fun search(needle: String) {}

        override fun searchDone() {}

        override fun row(row: NewChatRow) {}

        override fun openContact(key: String) {}

        override fun openGroup(key: String) {}

        override fun contactMenu(key: String, item: NewChatMenu) {}

        override fun groupMenu(key: String, item: NewChatMenu) {}

        override fun toggleHideOffline() {}
    }
}
