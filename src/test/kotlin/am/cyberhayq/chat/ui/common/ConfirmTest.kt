package am.cyberhayq.chat.ui.common

import android.app.Application
import android.content.DialogInterface
import android.os.Looper
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.takahirom.roborazzi.captureScreenRoboImage
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import com.google.common.util.concurrent.Futures
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The confirmations of destructive actions: shown with their text, Cancel does nothing, the button runs the action. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ConfirmTest {

    private fun activity(): AppCompatActivity {
        // themed before onCreate as BaseActivity does, so the dialogs look as in the app
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Conversations3)
        activity.theme.applyStyle(R.style.ThemeOverlay_Tatrak, true)
        controller.setup()
        return activity
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun AlertDialog.text(id: Int) = findViewById<TextView>(id)?.text?.toString()

    private fun AlertDialog.click(which: Int) {
        getButton(which).performClick()
        idle()
    }

    @Test
    fun cancelDoesNothingConfirmRuns() {
        val activity = activity()
        var runs = 0
        fun show() = confirmAction(activity, "Leave Book club?", "You leave the group.", R.string.leave) { runs++ }
        val first = show()
        idle()
        assertTrue(first.isShowing)
        assertEquals("Leave Book club?", first.text(androidx.appcompat.R.id.alertTitle))
        assertEquals("You leave the group.", first.text(android.R.id.message))
        assertEquals("Leave", first.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
        first.click(DialogInterface.BUTTON_NEGATIVE)
        assertFalse(first.isShowing)
        assertEquals(0, runs)
        show().click(DialogInterface.BUTTON_POSITIVE)
        assertEquals(1, runs)
    }

    /** One file, shared by others, several files: the parts that differ, and always the copies elsewhere. */
    @Test
    fun deleteFilesTextSaysWhereTheFileGoes() {
        val activity = activity()
        assertEquals(
            "The file is deleted from this device; its message stays and shows it as deleted. " +
                "Copies on the server and on other devices stay.",
            deleteFilesText(activity, 1, 0),
        )
        assertEquals(
            "The file is deleted from this device; its message stays and shows it as deleted. " +
                "It also disappears from 2 other messages that show it, in this or other chats. " +
                "Copies on the server and on other devices stay.",
            deleteFilesText(activity, 1, 2),
        )
        assertEquals(
            "3 files are deleted from this device; their messages stay and show them as deleted. " +
                "They also disappear from 1 other message that shows them, in this or another chat. " +
                "Copies on the server and on other devices stay.",
            deleteFilesText(activity, 3, 1),
        )
    }

    private class Recorder : XmppConnectionService() {
        val calls = ArrayList<String>()

        override fun clearConversationHistory(conversation: Conversation) {
            calls += "clear"
        }

        override fun archiveConversation(conversation: Conversation) {
            calls += "archive"
        }
    }

    // the name without a roster or room behind it
    private fun conversation(mode: Int) =
        object : Conversation("c1", "Book club", null, "a1", Jid.of("club@conference.example.com"), 0, Conversation.STATUS_AVAILABLE, mode, Conversation.Attributes.parse(null)) {
            override fun getName(): CharSequence = "Book club"
        }.apply { account = Account(Jid.of("tester@example.com"), "secret") }

    /** ConversationFragment's order: clear, then close (leave) when ticked; Cancel calls nothing. */
    @Test
    fun clearHistoryCallsTheLegacyMethods() {
        val activity = activity()
        val service = Recorder()
        val done = ArrayList<Boolean>()
        fun show(mode: Int): AlertDialog {
            confirmClearHistory(activity, conversation(mode), { service }) { done += it }
            idle()
            return org.robolectric.shadows.ShadowDialog.getLatestDialog() as AlertDialog
        }
        val group = show(Conversational.MODE_MULTI)
        assertEquals("Also leave the group", group.findViewById<CheckBox>(R.id.tatrak_checkbox)!!.text.toString())
        group.click(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(emptyList<String>(), service.calls)
        // unticked by default (owner, 2026-11-20): clearing does not leave the group
        show(Conversational.MODE_MULTI).click(DialogInterface.BUTTON_POSITIVE)
        assertEquals(listOf("clear"), service.calls)
        val single = show(Conversational.MODE_SINGLE)
        val box = single.findViewById<CheckBox>(R.id.tatrak_checkbox)!!
        assertEquals("Also delete the chat from my list", box.text.toString())
        box.isChecked = true
        single.click(DialogInterface.BUTTON_POSITIVE)
        assertEquals(listOf("clear", "clear", "archive"), service.calls)
        assertEquals(listOf(false, true), done)
    }

    /** The title names the chat; once its host shows another chat, the button clears nothing. */
    @Test
    fun clearHistoryNamesTheChatAndRechecksIt() {
        val activity = activity()
        val service = Recorder()
        var shown = true
        confirmClearHistory(activity, conversation(Conversational.MODE_SINGLE), { service }, { shown }) {}
        idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("Clear the history of Book club?", dialog.text(androidx.appcompat.R.id.alertTitle))
        shown = false
        dialog.click(DialogInterface.BUTTON_POSITIVE)
        assertEquals(emptyList<String>(), service.calls)
    }

    /** A rotation during the media browser's count: the old activity is destroyed, not finishing, and gets no dialog. */
    @Test
    fun deleteFilesCountAfterRecreationShowsNothing() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Conversations3)
        controller.setup()
        // getInstance needs the unlocked storage; the empty selection never opens it
        ReflectionHelpers.setStaticField(DatabaseBackend::class.java, "instance", DatabaseBackend.isolated(activity, "unused", ByteArray(0)))
        val gate = CountDownLatch(1)
        try {
            XmppConnectionService.DATABASE_READER.execute { gate.await(5, TimeUnit.SECONDS) }
            confirmDeleteFiles(activity, emptyList()) {}
            controller.pause().stop().destroy()
            assertFalse(activity.isFinishing)
            gate.countDown()
            Futures.submit(Callable { 0 }, XmppConnectionService.DATABASE_READER).get(5, TimeUnit.SECONDS)
            idle()
            assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        } finally {
            gate.countDown()
            ReflectionHelpers.setStaticField(DatabaseBackend::class.java, "instance", null)
        }
    }

    // screenshots of a few representative confirmations, light and dark

    private fun leaveGroup(name: String) {
        val activity = activity()
        confirmAction(activity, deleteChatTitle(activity, "Book club", true), deleteChatText(activity, "Book club", true), R.string.leave) {}
        idle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun leaveGroupLight() = leaveGroup("confirm_leave_group")

    @Test @Config(qualifiers = "+night") fun leaveGroupDark() = leaveGroup("confirm_leave_group_dark")

    private fun deleteSharedFile(name: String) {
        val activity = activity()
        confirmAction(activity, activity.getString(R.string.delete_file_dialog), deleteFilesText(activity, 1, 2), R.string.tatrak_delete) {}
        idle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun deleteSharedFileLight() = deleteSharedFile("confirm_delete_shared_file")

    @Test @Config(qualifiers = "+night") fun deleteSharedFileDark() = deleteSharedFile("confirm_delete_shared_file_dark")

    private fun clearHistory(name: String) {
        val activity = activity()
        confirmClearHistory(activity, conversation(Conversational.MODE_SINGLE), { null }) {}
        idle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun clearHistoryLight() = clearHistory("confirm_clear_history")

    @Test @Config(qualifiers = "+night") fun clearHistoryDark() = clearHistory("confirm_clear_history_dark")

    @Test
    @Config(qualifiers = "+ru")
    fun autoDeleteRussian() {
        val activity = activity()
        confirmAction(
            activity,
            activity.getString(R.string.tatrak_auto_delete_title),
            activity.getString(R.string.tatrak_auto_delete_explained, "1 неделя"),
            R.string.tatrak_delete,
        ) {}
        idle()
        captureScreenRoboImage("build/outputs/roborazzi/confirm_auto_delete_ru.png")
    }
}
