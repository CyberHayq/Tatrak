package eu.siacs.conversations.ui

import android.app.Application
import android.net.Uri
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** CodeQL java/android/unsafe-content-uri-resolution: a VIEW from another app never reads Tatrak's own data. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ImportBackupOwnDataTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun ownProvidersAndPrivateFilesAreRefused() {
        assertTrue(ImportBackupActivity.isOwnData(context, Uri.parse("content://${context.packageName}.files/x/backup.ceb")))
        assertTrue(ImportBackupActivity.isOwnData(context, Uri.fromFile(File(context.filesDir, "a.ceb"))))
        // a path that climbs back into the data directory
        val climb = File(context.cacheDir, "../databases/x").path
        assertTrue(ImportBackupActivity.isOwnData(context, Uri.parse("file://$climb")))
        assertTrue(ImportBackupActivity.isOwnData(context, Uri.parse("android.resource://${context.packageName}/raw/x")))
        assertTrue(ImportBackupActivity.isOwnData(context, Uri.parse("content:///no-authority")))
    }

    @Test
    fun otherAppsFilesAreRead() {
        assertFalse(ImportBackupActivity.isOwnData(context, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fa.ceb")))
        assertFalse(ImportBackupActivity.isOwnData(context, Uri.parse("file:///storage/emulated/0/Download/a.ceb")))
    }
}
