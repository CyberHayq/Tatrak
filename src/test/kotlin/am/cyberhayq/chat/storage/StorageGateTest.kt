package am.cyberhayq.chat.storage

import android.app.Activity
import android.app.Application
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

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class StorageGateTest {
    @Test fun keepsCallerAliveAndCoalescesRepeatedAdmissionChecks() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            assertTrue(DatabaseStorage.showGate(activity))
            assertFalse(activity.isFinishing)
            val intent = shadowOf(activity).nextStartedActivity
            assertTrue(intent.getBooleanExtra("tatrak_return_to_caller", false))
            assertTrue(DatabaseStorage.showGate(activity))
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun recreatesAGatedActivityWhenStorageIsReady() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        assertTrue(DatabaseStorage.showGate(activity))
        DatabaseStorage.recreateGated()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(activity.isDestroyed)
    }

    @Test fun onlyStagesUntouchedForAnHourAreOrphans() {
        val directory = kotlin.io.path.createTempDirectory("stage").toFile()
        val file = java.io.File(directory, "history.db").apply { writeText("x") }
        val now = System.currentTimeMillis()
        assertFalse(DatabaseStorage.isOrphan(directory, now) { !it.exists() })
        file.setLastModified(now - 2 * 60 * 60 * 1000L)
        directory.setLastModified(now - 2 * 60 * 60 * 1000L)
        assertTrue(DatabaseStorage.isOrphan(directory, now) { !it.exists() })
        // an I/O error is not an absence: when `metadata` cannot be told absent, the generation is kept
        assertFalse(DatabaseStorage.isOrphan(directory, now) { false })
        // once active (left behind by "Start fresh"): never an orphan, however old
        java.io.File(directory, "metadata").apply { writeText("{}"); setLastModified(now - 2 * 60 * 60 * 1000L) }
        assertFalse(DatabaseStorage.isOrphan(directory, now) { !it.exists() })
        directory.deleteRecursively()
    }

    @Test fun aRefusedRecoveryOfAnActivatedStageCountsAsPastCommit() {
        // a second commit worker of one stage arrives when storage is no longer waiting for recovery
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val base = java.io.File(context.noBackupFilesDir, "tatrak-databases").apply { mkdirs() }
        val id = java.util.UUID.randomUUID().toString()
        val stage = DatabaseStorage.Generation(id, java.io.File(base, id), ByteArray(32))
        val platformCheck = DatabaseStorage.confirmedAbsent
        DatabaseStorage.confirmedAbsent = { !it.exists() }
        try {
            // no selector names it: the refusal is a plain failure, the worker may roll back
            try { DatabaseStorage.recoverImport(context, stage); org.junit.Assert.fail() }
            catch (e: DatabaseStorage.PastCommitException) { org.junit.Assert.fail("not past commit") }
            catch (e: IllegalStateException) { }
            // the first worker activated it: the refusal must say so, or published media is moved back
            java.io.File(base, "active").writeBytes(Selector.encode(id))
            try { DatabaseStorage.recoverImport(context, stage); org.junit.Assert.fail() }
            catch (e: DatabaseStorage.PastCommitException) { assertTrue(e.cause is IllegalStateException) }
            assertFalse(DatabaseStorage.isReady()) // a refused call changes no state
        } finally { DatabaseStorage.confirmedAbsent = platformCheck; base.deleteRecursively() }
    }
}
