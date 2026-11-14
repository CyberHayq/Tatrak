package am.cyberhayq.chat.ui.storage

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import eu.siacs.conversations.R
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class MediaPromptsTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val context = ContextThemeWrapper(activity, R.style.Theme_Conversations3)

    private fun shown(): AlertDialog? = (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }

    private fun press(button: Int) {
        shown()!!.getButton(button).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun saveWarnsOnceAndCancelAsksAgain() {
        var saves = 0
        MediaPrompts.confirmSave(context) { saves++ }
        press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(0, saves)

        MediaPrompts.confirmSave(context) { saves++ }
        press(DialogInterface.BUTTON_POSITIVE)
        assertEquals(1, saves)

        ShadowDialog.reset()
        MediaPrompts.confirmSave(context) { saves++ }
        assertNull(shown()) // accepted: no dialog any more
        assertEquals(2, saves)
    }

    @Test
    @Config(sdk = [28])
    fun upToAndroid9SaveAsksForTheStoragePermissionFirst() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("tatrak_save_warning_accepted", true).apply()
        var saves = 0
        MediaPrompts.confirmSave(context) { saves++ }
        assertEquals(0, saves)
        assertEquals(
            listOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
            shadowOf(activity).lastRequestedPermission?.requestedPermissions?.toList(),
        )

        shadowOf(activity.application).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        MediaPrompts.confirmSave(context) { saves++ }
        assertEquals(1, saves)
    }

    @Test
    fun theVoiceWarningIsDueOnlyOnTheFilePathUntilRecordIsPressed() {
        assertFalse(MediaPrompts.voiceWarningDue(context)) // the pipe: nothing plain to warn about
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("tatrak_voice_pipe_failed", true).apply()
        assertTrue(MediaPrompts.voiceWarningDue(context))

        var records = 0
        var cancels = 0
        MediaPrompts.warnPlainVoice(context, { records++ }, { cancels++ })
        press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(1, cancels)
        assertTrue(MediaPrompts.voiceWarningDue(context))

        MediaPrompts.warnPlainVoice(context, { records++ }, { cancels++ }).cancel() // Back
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(2, cancels)

        MediaPrompts.warnPlainVoice(context, { records++ }, { cancels++ })
        press(DialogInterface.BUTTON_POSITIVE)
        assertEquals(1, records)
        assertFalse(MediaPrompts.voiceWarningDue(context))
    }
}
