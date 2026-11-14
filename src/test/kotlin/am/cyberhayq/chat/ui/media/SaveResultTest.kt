package am.cyberhayq.chat.ui.media

import android.app.Application
import com.google.common.util.concurrent.Futures
import eu.siacs.conversations.persistance.FileBackend
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

    @Test fun saysHowManyWereSaved() {
        assertEquals("3 media files saved", SaveResult.message(context, listOf(true, true, true)))
        assertEquals("Saved 2 of 3 media files", SaveResult.message(context, listOf(true, false, true)))
        // a cancelled save comes back as null from successfulAsList
        assertEquals("Saved 1 of 2 media files", SaveResult.message(context, listOf(null, true)))
        assertEquals("Could not save files", SaveResult.message(context, listOf(false, false)))
    }

    @Test fun aFailedSaveCountsAsNotSaved() {
        assertEquals(true, FileBackend.saved(Futures.immediateVoidFuture()).get())
        assertEquals(false, FileBackend.saved(Futures.immediateFailedFuture(IllegalStateException("disk"))).get())
    }
}
