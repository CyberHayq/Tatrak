package am.cyberhayq.chat.update

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The installer session and its status: abandoned when not committed, delivered to the dialog through [UpdateStatusReceiver]. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class UpdateInstallTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val installer = context.packageManager.packageInstaller

    /** The dialog opened by the chat list's notice, showing a stored release. */
    private fun dialog(): ActivityController<UpdateActivity> {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Updater.RELEASE, Release("9.9.9", "n", "${Updater.DOWNLOAD_PREFIX}x", 1).toJson()).commit()
        val controller = Robolectric.buildActivity(UpdateActivity::class.java).setup()
        // BaseActivity's storage gate, as the test has no database
        shadowOf(controller.get()).nextStartedActivity
        return controller
    }

    private fun apk() = File(context.cacheDir, "update.apk").apply { writeBytes(ByteArray(100)) }

    /** The installer's status as the receiver gets it: our attempt's extra filled in by the installer. */
    private fun status(status: Int, confirmation: Intent? = null, session: Int = -1, attempt: Long = 0) =
        Intent().putExtra(PackageInstaller.EXTRA_STATUS, status).putExtra(Intent.EXTRA_INTENT, confirmation).putExtra(PackageInstaller.EXTRA_SESSION_ID, session)
            .putExtra(Updater.EXTRA_ATTEMPT, attempt)

    /** A committed session of [attempt], waiting for the installer. */
    private fun session(attempt: Long): Int {
        Updater.commit(context, apk(), attempt)
        return installer.allSessions.last().sessionId
    }

    private fun failed(text: Int) = UpdateState.Failed(context.getString(text))

    @Test
    fun failedWriteAbandonsTheSession() {
        try {
            Updater.commit(context, File(context.cacheDir, "missing.apk"), 0)
            fail("committed a missing file")
        } catch (e: UpdateException) {
            assertEquals(R.string.tatrak_update_failed, e.text)
        }
        assertEquals(0, installer.allSessions.size)
    }

    /**
     * A real commit: the installer's answer is a broadcast that reaches the open dialog. Robolectric's
     * installer answers every commit at once, without a status extra, which reads as a failure.
     */
    @Test
    fun installerFailureReachesTheDialog() {
        val activity = dialog().get()
        session(activity.attempt)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.state.toString(), activity.state is UpdateState.Failed)
    }

    /** A dialog closed while its session was prepared, the notice opened again: the old session's late answer is not the new dialog's. */
    @Test
    fun lateStatusOfAnOlderAttemptIsNotDelivered() {
        val activity = dialog().get()
        session(activity.attempt + 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.state.toString(), activity.state is UpdateState.Available)
    }

    /** A PendingIntent shared by all sessions would be updated to carry the newest attempt also for an older session. */
    @Test
    fun eachSessionKeepsItsAttempt() {
        val sessions = listOf(session(1), session(2))
        val attempts =
            sessions.map {
                val pending = PendingIntent.getBroadcast(context, it, Intent(context, UpdateStatusReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE)
                pending?.let { shadowOf(it).savedIntent.getLongExtra(Updater.EXTRA_ATTEMPT, 0) }
            }
        assertEquals(listOf(1L, 2L), attempts)
    }

    @Test
    fun repeatedConfirmationOpensOnce() {
        val activity = dialog().get()
        val confirmation = status(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("x"), 5)
        UpdateStatusReceiver().onReceive(context, confirmation)
        UpdateStatusReceiver().onReceive(context, confirmation)
        assertEquals("x", shadowOf(activity).nextStartedActivity?.action)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    /** Android 17: after CONFIRM_INSTALL a timed-out developer verifier asks again for the same session, with another screen. */
    @Test
    fun secondConfirmationOfTheSessionOpens() {
        val activity = dialog().get()
        val install = status(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("android.content.pm.action.CONFIRM_INSTALL"), 5)
        UpdateStatusReceiver().onReceive(context, install)
        UpdateStatusReceiver().onReceive(context, status(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("android.content.pm.action.CONFIRM_DEVELOPER_VERIFICATION"), 5))
        UpdateStatusReceiver().onReceive(context, status(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("android.content.pm.action.CONFIRM_DEVELOPER_VERIFICATION"), 5))
        // Robolectric hands out the newest start first
        val opened = generateSequence { shadowOf(activity).nextStartedActivity?.action }.toList()
        assertEquals(listOf("android.content.pm.action.CONFIRM_DEVELOPER_VERIFICATION", "android.content.pm.action.CONFIRM_INSTALL"), opened)
    }

    /** The session failed while its confirmation waited for the foreground: nothing opens. */
    @Test
    fun finalStatusDropsTheQueuedConfirmation() {
        val controller = dialog()
        controller.pause().stop()
        UpdateStatusReceiver().onReceive(context, status(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("x"), 5))
        UpdateStatusReceiver().onReceive(context, status(PackageInstaller.STATUS_FAILURE, session = 5))
        controller.start().resume()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertTrue(controller.get().state.toString(), controller.get().state is UpdateState.Failed)
    }

    /** Android destroys the dialog in the background before its confirmation could open: the session is freed. */
    @Test
    fun queuedConfirmationIsAbandonedWithItsDialog() {
        val controller = dialog()
        val id = session(controller.get().attempt)
        controller.pause().stop()
        UpdateStatusReceiver().onReceive(context, status(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("x"), id))
        controller.destroy()
        assertEquals(0, installer.allSessions.size)
    }

    /** The confirmation arrives while Tatrak is in the background: it opens when the dialog is back. */
    @Test
    fun confirmationWaitsForTheForeground() {
        val controller = dialog()
        val confirmation = Intent("android.content.pm.action.CONFIRM_INSTALL")
        controller.pause().stop()
        UpdateStatusReceiver().onReceive(context, status(PackageInstaller.STATUS_PENDING_USER_ACTION, confirmation))
        assertEquals(UpdateState.Installing, controller.get().state)
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.start().resume()
        assertEquals(confirmation.action, shadowOf(controller.get()).nextStartedActivity?.action)
    }

    @Test
    fun noConfirmationFails() {
        val activity = dialog().get()
        UpdateStatusReceiver().onReceive(context, status(PackageInstaller.STATUS_PENDING_USER_ACTION, session = session(activity.attempt)))
        assertEquals(failed(R.string.tatrak_update_error_no_installer), activity.state)
        assertEquals(0, installer.allSessions.size)
    }

    @Test
    fun confirmationThatCannotOpenFails() {
        val activity = dialog().get()
        shadowOf(context).checkActivities(true)
        UpdateStatusReceiver().onReceive(context, status(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("am.cyberhayq.chat.test.NOTHING"), session(activity.attempt)))
        assertEquals(failed(R.string.tatrak_update_error_no_installer), activity.state)
        assertEquals(0, installer.allSessions.size)
    }

    @Test
    fun missingUnknownSourcesScreenFails() {
        val activity = dialog().get()
        shadowOf(context).checkActivities(true)
        activity.download()
        assertEquals(failed(R.string.tatrak_update_allow_installs), activity.state)
    }

    /** Nobody is left to confirm: the waiting session is freed. */
    @Test
    fun closedDialogAbandonsTheWaitingSession() {
        dialog().pause().stop().destroy()
        UpdateStatusReceiver().onReceive(context, status(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("x"), session(0)))
        assertEquals(0, installer.allSessions.size)
    }
}
