package am.cyberhayq.chat.ui.chatlist

import android.app.Application
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ThumbnailFailureTest {

    @Test
    fun aFileThatAppearsOrChangesIsTriedAgain() {
        val file = File(Files.createTempDirectory("thumb").toFile(), "m.jpg")
        val missing = ChatListSnapshot.thumbnailFailureKey("m", file)
        file.writeBytes(ByteArray(10))
        val downloaded = ChatListSnapshot.thumbnailFailureKey("m", file)
        assertNotEquals(missing, downloaded)
        file.writeBytes(ByteArray(20))
        assertNotEquals(downloaded, ChatListSnapshot.thumbnailFailureKey("m", file))
        file.delete()
        assertEquals(missing, ChatListSnapshot.thumbnailFailureKey("m", file))
    }

    @Test
    fun theFailuresAreBounded() {
        val cache = ChatListSnapshot.noThumbnail
        cache.evictAll()
        repeat(cache.maxSize() + 1) { cache.put("k$it", 0L) }
        assertEquals(cache.maxSize(), cache.size())
        assertNull(cache.get("k0"))
    }

    /** A failure may be transient (a file still being written, low memory): an unchanged file is tried again later. */
    @Test
    fun anUnchangedFileIsTriedAgainAfterAWhile() {
        val cache = ChatListSnapshot.noThumbnail
        cache.evictAll()
        assertFalse(ChatListSnapshot.failedRecently("f", now = 1_000L))
        cache.put("f", 1_000L)
        assertTrue(ChatListSnapshot.failedRecently("f", now = 1_000L + ChatListSnapshot.ThumbnailRetryMillis - 1))
        assertFalse(ChatListSnapshot.failedRecently("f", now = 1_000L + ChatListSnapshot.ThumbnailRetryMillis))
        assertNull(cache.get("f"))
    }
}
