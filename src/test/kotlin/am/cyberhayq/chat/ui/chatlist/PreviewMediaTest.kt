package am.cyberhayq.chat.ui.chatlist

import am.cyberhayq.chat.ui.chat.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewMediaTest {

    @Test
    fun photosAndVideosWithDimensionsGetAThumbnail() {
        assertTrue(hasThumbnail("image/jpeg", 800, 600))
        assertTrue(hasThumbnail("image/webp", 512, 512)) // a sticker
        assertTrue(hasThumbnail("video/mp4", 1280, 720))
    }

    @Test
    fun othersKeepTheTextOnlyPreview() {
        assertFalse(hasThumbnail("image/jpeg", 0, 0)) // the chat shows it as a file
        assertFalse(hasThumbnail("image/svg+xml", 100, 100))
        assertFalse(hasThumbnail("audio/mp4", 0, 0))
        assertFalse(hasThumbnail("application/pdf", 595, 842))
        assertFalse(hasThumbnail(null, 100, 100))
    }

    @Test
    fun durationAsInTheVoiceBubble() {
        assertEquals("0:00", formatDuration(400))
        assertEquals("0:12", formatDuration(12_345))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("61:01", formatDuration(3_661_000))
    }
}
