package am.cyberhayq.chat.ui.media

import android.app.Application
import com.google.common.util.concurrent.Futures
import eu.siacs.conversations.persistance.FileBackend
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class SaveResultTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private val a = File("a.jpg")

    @Test fun saysHowManyWereSaved() {
        assertEquals("3 media files saved", SaveResult.message(context, listOf(a, a, a)))
        assertEquals("Saved 2 of 3 media files", SaveResult.message(context, listOf(a, null, a)))
        assertEquals("Could not save files", SaveResult.message(context, listOf(null, null)))
    }

    /** A taken name gets " (1)"; the toast says so. */
    @Test fun oneFileNamesWhatItWasSavedAs() {
        assertEquals("Saved as app (1).apk", SaveResult.message(context, listOf(File("/x/Download/app (1).apk"))))
    }

    @Test fun aFailedSaveCountsAsNotSaved() {
        assertEquals(a, FileBackend.saved(Futures.immediateFuture(a)).get())
        assertEquals(null, FileBackend.saved(Futures.immediateFailedFuture<File>(IllegalStateException("disk"))).get())
    }
}
