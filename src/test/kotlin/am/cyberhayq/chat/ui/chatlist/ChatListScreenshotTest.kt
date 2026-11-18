package am.cyberhayq.chat.ui.chatlist

import am.cyberhayq.chat.ui.search.SearchResult
import am.cyberhayq.chat.ui.search.SearchState
import am.cyberhayq.chat.ui.theme.Accent
import am.cyberhayq.chat.ui.theme.NightBackground
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import eu.siacs.conversations.R
import org.robolectric.RuntimeEnvironment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the chat list on the JVM so the UI can be checked without a device.
 * Images land in build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ChatListScreenshotTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun chatListLight() = capture(darkTheme = false, "chat_list_light")

    @Test
    @Config(qualifiers = "+night")
    fun chatListDark() = capture(darkTheme = true, "chat_list_dark")

    @Test
    fun chatListDrawerLight() = drawer(darkTheme = false, Accent.BLUE, "chat_list_drawer_light")

    @Test
    @Config(qualifiers = "+night")
    fun chatListDrawerAmoledDark() = drawer(darkTheme = true, Accent.RED, "chat_list_drawer_amoled_dark", NightBackground.AMOLED)

    @Test
    @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun chatListDrawerNarrow() = drawer(darkTheme = false, Accent.BLUE, "chat_list_drawer_narrow")

    @Test
    @Config(qualifiers = "w800dp-h1000dp-xxhdpi")
    fun chatListDrawerWide() = drawer(darkTheme = false, Accent.BLUE, "chat_list_drawer_wide")

    private fun drawer(darkTheme: Boolean, accent: Accent, name: String, night: NightBackground = NightBackground.BLUE_GREY) {
        compose.setContent {
            TatrakTheme(darkTheme = darkTheme, accent = accent, dynamicColors = false, nightBackground = night) {
                ChatListDrawer(AccountHeader("Test", "test@example.am", null), NoListActions) {}
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun chatListEmpty() {
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                ChatListScreen(ChatListState(loaded = true), NoListActions)
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_empty.png")
    }

    @Test
    fun chatListSelection() {
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                ChatListScreen(sampleState(), NoListActions, initialSelection = listOf("chat-3", "chat-4"))
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_selection.png")
    }

    @Test
    fun chatListArchive() {
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                ChatListScreen(sampleState(), NoListActions, initialArchiveOpen = true)
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_archive.png")
    }

    // ---- hidden archive and the drawer by swipe ----

    @Test
    fun drawerStaysOpenAfterRecreate() {
        val restore = StateRestorationTester(compose)
        var drawer: DrawerController? = null
        restore.setContent { drawer = rememberDrawerController() }
        compose.runOnIdle { drawer!!.open() }
        compose.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(1f, drawer!!.progress) }
    }

    @Test
    fun gestureDecisions() {
        assertTrue(drawerOpensOnRelease(0.4f, 0f, wasOpen = false))
        assertFalse(drawerOpensOnRelease(0.3f, 0f, wasOpen = false))
        assertTrue(drawerOpensOnRelease(0.1f, 600f, wasOpen = false)) // fling opens
        assertTrue(drawerOpensOnRelease(0.7f, 0f, wasOpen = true)) // stays open
        assertFalse(drawerOpensOnRelease(0.6f, 0f, wasOpen = true))
        assertFalse(drawerOpensOnRelease(0.9f, -600f, wasOpen = true)) // fling back closes
        assertTrue(archiveShowsOnRelease(170, 200))
        assertFalse(archiveShowsOnRelease(169, 200))
        assertFalse(archiveShowsOnRelease(0, 0))
        assertEquals(0.45f, archivePullShare(0, 200), 0.001f)
        assertEquals(0.2f, archivePullShare(200, 200), 0.001f)
        assertEquals(0.45f, archivePullShare(10, 0), 0.001f)
    }

    /** While pulled into view the row is covered by the foreground; past the threshold it says "Release". */
    @Test
    fun pullForegroundCoversTheRowWhileDragging() {
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState(), NoListActions) } }
        val list = compose.onNodeWithText("Saved Messages")
        list.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 40f)); moveBy(androidx.compose.ui.geometry.Offset(0f, 200f)) }
        compose.onNodeWithText("Swipe down to see archive").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_archive_pull_half.png")
        // the row resists (about 2.5 rows of finger travel) and must have been coming in for 200 ms
        list.performTouchInput { advanceEventTime(ARCHIVE_MIN_PULL_MS); moveBy(androidx.compose.ui.geometry.Offset(0f, 800f)) }
        compose.onNodeWithText("Release to see archive").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_archive_pull_release.png")
        // released: the foreground shrinks onto the avatar (Telegram's out animation), then the real row shows
        compose.mainClock.autoAdvance = false
        list.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(120)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_archive_morph.png")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Release to see archive").assertDoesNotExist()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        compose.onNodeWithText("Old project").assertDoesNotExist()
    }

    /** The archive drops in instead of replacing the list at once. */
    @Test
    fun theArchiveSlidesIn() {
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState(), NoListActions) } }
        fullPull()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Archive").performClick()
        compose.mainClock.advanceTimeBy(180)
        // part-way: the list is gone, the archive is dropping in and fading in
        compose.onNodeWithText("Old project").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_archive_opening.png")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Saved Messages").assertDoesNotExist()
        compose.onNodeWithText("Old project").assertIsDisplayed()
    }

    /** A slow pull with the row 85% in view leaves it there (Telegram); the archive opens only on a tap. */
    private fun fullPull() {
        compose.onNodeWithText("Saved Messages").performTouchInput { swipeDown(startY = top + 10f, endY = top + 1800f, durationMillis = 600) }
        compose.waitForIdle()
    }

    /** Released with less than 85% of the row in view: it slides back out (Telegram). */
    @Test
    fun aPartialPullHidesTheRowAgain() {
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState(), NoListActions) } }
        compose.onNodeWithText("Saved Messages").performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 40f)); moveBy(androidx.compose.ui.geometry.Offset(0f, 200f))
            advanceEventTime(ARCHIVE_MIN_PULL_MS); up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Archive").assertIsNotDisplayed()
        compose.onNodeWithText("Swipe down to see archive").assertIsNotDisplayed()
        compose.onNodeWithText("Old project").assertDoesNotExist()
    }

    /** The first archiving tells where the hidden archive is (Telegram's archive hint); later ones do not. */
    @Test
    fun theFirstArchivingShowsTheArchiveHintOnce() {
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState(), NoListActions) } }
        val hint = "Swipe the list down from the top to see the archive."
        fun archiveFirstChat() {
            compose.onNodeWithText("Saved Messages").performTouchInput { longClick() }
            compose.onNodeWithContentDescription("Archive").performClick()
            compose.waitForIdle()
        }
        archiveFirstChat()
        compose.onNodeWithText(hint, substring = true).assertExists()
        archiveFirstChat()
        compose.onNodeWithText("Chat archived", substring = true).assertExists()
        compose.onNodeWithText(hint, substring = true).assertDoesNotExist()
    }

    /** Pulled in quickly, held still past 200 ms, released: the row stays and the release still ticks once. */
    @Test
    fun aHeldPullTicksOnRelease() {
        var ticks = 0
        val haptics = object : androidx.compose.ui.hapticfeedback.HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) { ticks++ }
        }
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalHapticFeedback provides haptics) {
                    ChatListScreen(sampleState(), NoListActions)
                }
            }
        }
        compose.onNodeWithText("Saved Messages").performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 40f)); moveBy(androidx.compose.ui.geometry.Offset(0f, 1200f))
            advanceEventTime(ARCHIVE_MIN_PULL_MS + 50); up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        assertEquals(1, ticks)
    }

    /** A quick jerk pulls the whole row in but under 200 ms: the row slides back out (Telegram's minPullingTime). */
    @Test
    fun aQuickFullPullHidesTheRowAgain() {
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState(), NoListActions) } }
        compose.onNodeWithText("Saved Messages").performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 40f)); moveBy(androidx.compose.ui.geometry.Offset(0f, 1200f)); moveBy(androidx.compose.ui.geometry.Offset(0f, 10f)); up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Old project").assertDoesNotExist()
        compose.onNodeWithText("Archive").assertIsNotDisplayed()
    }

    @Test
    fun aShortPullHidesTheRowAgain() {
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState(), NoListActions) } }
        compose.onNodeWithText("Saved Messages").performTouchInput { swipeDown(startY = centerY, endY = centerY + 50f) }
        compose.waitForIdle()
        compose.onNodeWithText("Archive").assertIsNotDisplayed()
    }

    private fun pinArchive() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        setArchiveHidden(app, false)
    }

    @Test
    fun archiveRowIsHiddenUntilPulledAndPinnedOnRequest() {
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState(), NoListActions) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_archive_hidden.png")
        compose.onNodeWithText("Archive").assertIsNotDisplayed()
        compose.onNodeWithText("Saved Messages").assertIsDisplayed()
    }

    @Test
    fun pinnedArchiveRowShowsAtTheTop() {
        pinArchive()
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState(), NoListActions) } }
        compose.onNodeWithText("Archive").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_archive_pinned.png")
    }

    @Test
    fun emptyArchiveHasNoRow() {
        pinArchive()
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState().copy(archived = emptyList()), NoListActions) } }
        compose.onNodeWithText("Archive").assertDoesNotExist()
    }

    @Test
    fun longPressOnTheArchiveRowOffersToHideIt() {
        pinArchive()
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState(), NoListActions) } }
        compose.onNodeWithText("Archive").performTouchInput { longClick() }
        compose.onNodeWithText("Hide above list").assertExists().performClick()
        compose.waitForIdle()
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        assertTrue(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).getBoolean(ARCHIVE_HIDDEN_KEY, false)
        )
    }

    @Test
    fun aFullPullShowsTheRowAndATapOpensTheArchive() {
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState(), NoListActions) } }
        fullPull()
        compose.onNodeWithText("Old project").assertDoesNotExist()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        compose.onNodeWithText("Swipe down to see archive").assertDoesNotExist()
        compose.onNodeWithText("Release to see archive").assertDoesNotExist()
        compose.onNodeWithText("Archive").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Old project").assertExists()
        // back in the list, the row is hidden again (Telegram)
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Saved Messages").assertIsDisplayed()
        compose.onNodeWithText("Archive").assertIsNotDisplayed()
    }

    /** A drag that starts lower down stops at the first chat: no accidental archive (owner, 2026-11-06). */
    @Test
    fun aDragFromLowerDownStopsAtTheFirstChat() {
        val sample = sampleState()
        val long = sample.copy(chats = (0 until 4).flatMap { n -> sample.chats.map { it.copy(uuid = "${it.uuid}-$n", title = "${it.title} $n") } })
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(long, NoListActions) } }
        compose.onRoot().performTouchInput { swipeUp(startY = bottom - 100f, endY = top + 1000f, durationMillis = 1500) }
        compose.waitForIdle()
        compose.onNodeWithText(long.chats.first().title).assertIsNotDisplayed()
        compose.onRoot().performTouchInput { swipeDown(startY = top + 600f, endY = bottom - 10f, durationMillis = 1500) }
        compose.waitForIdle()
        compose.onNodeWithText("Old project").assertDoesNotExist()
        compose.onNodeWithText(long.chats.first().title).assertIsDisplayed()
        compose.onNodeWithText("Archive").assertIsNotDisplayed()
        // a fresh pull from the top still shows the row
        compose.onRoot().performTouchInput { swipeDown(startY = top + 600f, endY = bottom - 10f, durationMillis = 600) }
        compose.waitForIdle()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        compose.onNodeWithText("Old project").assertDoesNotExist()
    }

    /** A chat that gets a message moves to the top and shows there, not just above the screen. */
    @Test
    fun aChatMovingToTheTopIsShown() {
        // longer than the screen: in a short list the end gap clamps the scroll and hides the bug
        val sample = sampleState()
        val base = sample.copy(chats = (0 until 4).flatMap { n -> sample.chats.map { it.copy(uuid = "${it.uuid}-$n", title = "${it.title} $n") } })
        var state by mutableStateOf(base)
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(state, NoListActions) } }
        compose.waitForIdle()
        val moved = base.chats.last()
        state = base.copy(chats = listOf(moved) + base.chats.dropLast(1))
        compose.waitForIdle()
        compose.onNodeWithText(moved.title).assertIsDisplayed()
        compose.onNodeWithText("Archive").assertIsNotDisplayed()
    }

    /** Every chat archived: the row stays in sight instead of a blank list. */
    @Test
    fun everyChatArchivedKeepsTheArchiveRowInSight() {
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState().copy(chats = emptyList()), NoListActions) } }
        compose.onNodeWithText("Archive").assertIsDisplayed()
    }

    /** Fewer chats than fit on screen: the list still scrolls far enough to keep the row hidden. */
    @Test
    fun hiddenArchiveStaysHiddenInAShortList() {
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState().let { it.copy(chats = it.chats.take(3)) }, NoListActions) } }
        compose.onNodeWithText("Archive").assertIsNotDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_archive_hidden_short.png")
        fullPull()
        compose.onNodeWithText("Archive").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Old project").assertExists()
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Archive").assertIsNotDisplayed()
    }

    @Test
    fun swipeRightOpensTheDrawerOnTheFirstTabOnly() {
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState(), NoListActions) } }
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Settings").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_drawer_by_swipe.png")
        compose.onRoot().performTouchInput { swipeLeft(startX = right - 20f, endX = 20f) }
        compose.waitForIdle()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithText("Personal").performClick()
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test
    fun swipeRightInTheArchiveGoesBack() {
        compose.setContent { TatrakTheme(darkTheme = false) { ChatListScreen(sampleState(), NoListActions, initialArchiveOpen = true) } }
        compose.onNodeWithText("Old project").assertExists()
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Saved Messages").assertExists()
        compose.onNodeWithText("Settings").assertDoesNotExist()
    }

    /** Telegram's search: the chat named like the text, then the messages that contain it. */
    @Test
    fun chatListSearchMessages() {
        val now = System.currentTimeMillis()
        val messages =
            SearchState(
                results =
                    listOf(
                        SearchResult("m1", "Ani", "", "Meeting with the dev team moved to 11", now - 600_000L),
                        SearchResult("m2", "Family", "Mom: ", "Is the dev team coming for dinner?", now - 86_400_000L * 3),
                        SearchResult("m3", "Gor", "", "DEV builds are on the server", now - 86_400_000L * 50),
                    ),
                terms = listOf("dev"),
                searched = true,
            )
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                ChatListScreen(sampleState(), NoListActions, messages = messages, initialQuery = "dev")
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_search_messages.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun chatListSearchNothingDark() {
        compose.setContent {
            TatrakTheme(darkTheme = true) {
                ChatListScreen(sampleState(), NoListActions, messages = SearchState(terms = listOf("zzz"), searched = true), initialQuery = "zzz")
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_search_nothing_dark.png")
    }

    @Test
    fun searchEmptyStateWaitsForCurrentQuery() {
        var messages by mutableStateOf(SearchState(terms = listOf("old"), searched = true))
        compose.setContent {
            TatrakTheme(darkTheme = false) {
                ChatListScreen(sampleState(), NoListActions, messages = messages, initialQuery = "zzz")
            }
        }
        compose.onNodeWithText("Nothing found").assertDoesNotExist()
        compose.runOnIdle { messages = SearchState(terms = listOf("zzz"), searched = false) }
        compose.onNodeWithText("Nothing found").assertDoesNotExist()
        compose.runOnIdle { messages = SearchState(terms = listOf("zzz"), searched = true) }
        compose.onNodeWithText("Nothing found").assertExists()
    }

    @Test
    @Config(qualifiers = "+night")
    fun chatListVioletDark() = capture(darkTheme = true, "chat_list_violet_dark", Accent.VIOLET)

    @Test
    fun chatListAccentsAndNights() {
        var accent by mutableStateOf(Accent.BLUE)
        var style by mutableStateOf(TatrakStyle.TELEGRAM)
        var dark by mutableStateOf(false)
        var night by mutableStateOf(NightBackground.BLUE_GREY)
        compose.setContent {
            TatrakTheme(darkTheme = dark, style = style, accent = accent, nightBackground = night) {
                ChatListScreen(sampleState(), NoListActions)
            }
        }
        // every accent by day and under each night background (Telegram style)
        for (option in Accent.entries) for (mode in listOf(null) + NightBackground.entries) {
            compose.runOnIdle { accent = option; dark = mode != null; night = mode ?: NightBackground.BLUE_GREY }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_accent_${option.key}_${mode?.key ?: "day"}.png")
        }
        // Slate and Sea under the graphite and AMOLED nights (its blue-grey night is chat_list_slate_dark)
        for (mode in listOf(NightBackground.GRAPHITE, NightBackground.AMOLED)) {
            compose.runOnIdle { style = TatrakStyle.SLATE_SEA; accent = Accent.SEA; dark = true; night = mode }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_slate_${mode.key}_dark.png")
        }
        // the dock's selected item in a light accent by day (link colour, fitted to the bar)
        compose.runOnIdle { style = TatrakStyle.SLATE_SEA; accent = Accent.GOLD; dark = false }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_slate_gold_day.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun chatListSlateDark() = capture(darkTheme = true, "chat_list_slate_dark", Accent.SEA, TatrakStyle.SLATE_SEA)

    @Test
    fun chatListSlateLight() = capture(darkTheme = false, "chat_list_slate_light", Accent.SEA, TatrakStyle.SLATE_SEA)

    /** Slate and Sea: the dock is hidden while selecting (design_phase2.md item 1). */
    @Test
    fun chatListSlateSelection() {
        compose.setContent {
            TatrakTheme(darkTheme = false, style = TatrakStyle.SLATE_SEA, accent = Accent.SEA) {
                ChatListScreen(sampleState(), NoListActions, initialSelection = listOf("chat-3", "chat-4"))
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_list_slate_selection.png")
    }

    @Test
    fun dockShowsOnlyOnTheList() {
        assertTrue(dockVisible(searching = false, showArchive = false, selectionMode = false))
        assertFalse(dockVisible(searching = true, showArchive = false, selectionMode = false))
        assertFalse(dockVisible(searching = false, showArchive = true, selectionMode = false))
        assertFalse(dockVisible(searching = false, showArchive = false, selectionMode = true))
    }

    private fun capture(darkTheme: Boolean, name: String, accent: Accent = Accent.BLUE, style: TatrakStyle = TatrakStyle.TELEGRAM) {
        compose.setContent {
            TatrakTheme(darkTheme = darkTheme, style = style, accent = accent) { ChatListScreen(sampleState(), NoListActions) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun sampleState(): ChatListState {
        val now = System.currentTimeMillis()
        val minute = 60_000L
        val day = 24 * 60 * minute
        var index = 0
        fun chat(
            title: String,
            preview: String,
            ago: Long,
            color: Int,
            sender: String? = null,
            unread: Int = 0,
            muted: Boolean = false,
            pinned: Boolean = false,
            status: OutgoingStatus? = null,
            online: Boolean = false,
            typing: Boolean = false,
            draft: Boolean = false,
            group: Boolean = false,
            archived: Boolean = false,
            saved: Boolean = false,
            thumbnail: Bitmap? = null,
        ) =
            ChatListItem(
                isSavedMessages = saved,
                previewMediaKey = thumbnail?.let { "m-$index" },
                previewThumbnail = thumbnail,
                uuid = "chat-${index++}",
                title = title,
                isGroup = group,
                preview = preview,
                previewSender = sender,
                isDraft = draft,
                isServicePreview = false,
                isTyping = typing,
                timestamp = now - ago,
                unreadCount = unread,
                isMuted = muted,
                isPinned = pinned,
                outgoingStatus = status,
                isOnline = online,
                isArchived = archived,
                avatar = letterAvatar(title, color),
            )
        return ChatListState(
            loaded = true,
            archived =
                listOf(
                    chat("Old project", "Let's close this", 30 * day, 0xFFFAA774.toInt(), unread = 1, archived = true),
                    chat("Newsletter", "Weekly digest #42", 5 * day, 0xFF6EC9CB.toInt(), muted = true, archived = true, group = true, sender = "Bot"),
                ),
            account = AccountHeader("Test", "test@example.am", letterAvatar("Test", 0xFF5CAFFA.toInt())),
            chats =
                listOf(
                    chat("Saved Messages", "Ideas for the fork", 3 * minute, 0xFF65AADD.toInt(), pinned = true, status = OutgoingStatus.READ, saved = true),
                    chat("Ani", "See you tomorrow at 10?", 12 * minute, 0xFFE17076.toInt(), unread = 2, online = true),
                    chat("Dev team", "pushed the new build", 40 * minute, 0xFF7BC862.toInt(), sender = "Aram", unread = 14, group = true),
                    chat("Davit", "", 2 * 60 * minute, 0xFFFAA774.toInt(), typing = true, online = true),
                    chat("Family", "image", day + 3 * 60 * minute, 0xFFA695E7.toInt(), sender = "Mom", unread = 3, muted = true, group = true, thumbnail = thumbnail(video = false)),
                    chat("Lilit", "Thanks, got it!", 2 * day, 0xFF6EC9CB.toInt(), status = OutgoingStatus.DELIVERED),
                    chat("Karen", "the meeting notes", 3 * day, 0xFFEE7AAE.toInt(), draft = true),
                    chat("XMPP Armenia", "Prosody 13 is out", 12 * day, 0xFF65AADD.toInt(), sender = "Narek", muted = true, group = true),
                    chat("Sona", "video", 40 * day, 0xFFE17076.toInt(), status = OutgoingStatus.READ, thumbnail = thumbnail(video = true)),
                    chat("Gor", "audio, 0:12", 400 * day, 0xFF7BC862.toInt()),
                ),
        )
    }

    /**
     * A generated picture in the shape FileBackend.getPreviewForUri returns: a square at the
     * preview size, a video with the same play_video_white overlay at 0.75 of the side.
     */
    private fun thumbnail(video: Boolean): Bitmap {
        val size = 60
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (video) 0xFF2E4057.toInt() else 0xFF7EC8E3.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = if (video) 0xFF4F6D7A.toInt() else 0xFFF6D55C.toInt()
        canvas.drawCircle(size * 0.7f, size * 0.3f, size * 0.18f, paint)
        paint.color = 0xFF3CAEA3.toInt()
        canvas.drawRect(0f, size * 0.65f, size.toFloat(), size.toFloat(), paint)
        if (video) {
            val overlay =
                BitmapFactory.decodeResource(RuntimeEnvironment.getApplication().resources, R.drawable.play_video_white)
            val side = size * 0.75f
            val left = (size - side) / 2f
            canvas.drawBitmap(overlay, null, RectF(left, left, left + side - 1, left + side - 1), paint)
        }
        return bitmap
    }

    private fun letterAvatar(name: String, color: Int): Bitmap {
        val size = 162
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = 0xFFFFFFFF.toInt()
                textSize = size * 0.42f
                textAlign = Paint.Align.CENTER
            }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(name.take(1).uppercase(), size / 2f, y, paint)
        return bitmap
    }
}

internal object NoListActions : ChatListActions {
    override fun openChat(uuid: String) = Unit

    override fun newChat() = Unit

    override fun openContacts() = Unit

    override fun manageAccounts() = Unit

    override fun scanQrCode() = Unit

    override fun openSettings() = Unit

    override fun setArchived(uuids: List<String>, archived: Boolean) = Unit

    override fun setPinned(uuids: List<String>, pinned: Boolean) = Unit

    override fun setMuted(uuids: List<String>, till: java.time.Instant?) = Unit

    override fun markRead(uuids: List<String>) = Unit

    override fun delete(uuids: List<String>) = Unit
}
