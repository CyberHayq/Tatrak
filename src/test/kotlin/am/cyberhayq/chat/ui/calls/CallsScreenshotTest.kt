package am.cyberhayq.chat.ui.calls

import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import eu.siacs.conversations.R
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
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
class CallsScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private object NoActions : CallsActions {
        override fun back() {}

        override fun openChat(chat: String) {}

        override fun call(chat: String, video: Boolean) {}
    }

    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L

    private val state =
        CallsState(
            entries =
                listOf(
                    CallEntry("1", "a", "Ani", now - hour, received = true, missed = true, count = 2, icon = R.drawable.ic_call_missed_24db),
                    CallEntry("2", "d", "Davit", now - 3 * hour, received = false, missed = false, duration = "5 min", icon = R.drawable.ic_call_made_24dp),
                    CallEntry("3", "a", "Ani", now - 30 * hour, received = true, missed = false, duration = "42 s", icon = R.drawable.ic_call_received_24dp),
                    CallEntry("4", "g", "Gor", now - 80 * hour, received = false, missed = false, icon = R.drawable.ic_call_missed_outgoing_24dp),
                ),
            loaded = true,
        )

    @Test
    fun calls() {
        compose.setContent { TatrakTheme(darkTheme = false) { CallsScreen(state, NoActions) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/calls.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun missedDark() {
        compose.setContent { TatrakTheme(darkTheme = true) { CallsScreen(state, NoActions) } }
        compose.onNodeWithText("Missed").performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/calls_missed_dark.png")
    }

    @Test
    fun empty() {
        compose.setContent { TatrakTheme(darkTheme = false) { CallsScreen(CallsState(loaded = true), NoActions) } }
        compose.onNodeWithText("No calls yet").assertExists()
    }

    @Test
    fun groupsAlikeNeighboursOfOneDay() {
        val zone = ZoneOffset.UTC
        fun at(day: Int, h: Int) = LocalDateTime.of(2026, 10, day, h, 0).atZone(zone).toInstant().toEpochMilli()
        fun missed(key: String, chat: String, time: Long) = CallRecord(key, chat, time, received = true, successful = false, duration = 0)
        val records =
            listOf(
                missed("1", "a", at(31, 12)),
                missed("2", "a", at(31, 10)),
                CallRecord("3", "a", at(31, 9), received = true, successful = true, duration = 60_000), // answered: new row
                missed("4", "a", at(30, 22)), // another day: new row
                missed("5", "b", at(30, 21)), // another chat: new row
            )
        val groups = CallLog.group(records, zone as ZoneId)
        assertEquals(listOf("1" to 2, "3" to 1, "4" to 1, "5" to 1), groups.map { it.newest.key to it.count })
    }
}
