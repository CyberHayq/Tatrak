package am.cyberhayq.chat.ui.chatlist

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class KeepAvatarsTest {

    private fun item(uuid: String, avatar: Bitmap?) =
        ChatListItem(
            uuid = uuid,
            title = uuid,
            isGroup = false,
            preview = "",
            previewSender = null,
            isDraft = false,
            isServicePreview = false,
            isTyping = false,
            timestamp = 0,
            unreadCount = 0,
            isMuted = false,
            isPinned = false,
            outgoingStatus = null,
            isOnline = false,
            isArchived = false,
            avatar = avatar,
        )

    private fun bitmap() = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    @Test
    fun missingAvatarKeepsThePreviousOneAndNewAvatarsWin() {
        val old = bitmap()
        val fresh = bitmap()
        val previous = ChatListState(chats = listOf(item("a", old), item("b", old)))
        val next = ChatListState(chats = listOf(item("a", null), item("b", fresh), item("c", null)))

        val kept = next.keepAvatars(previous)

        assertSame(old, kept.chats[0].avatar)
        assertSame(fresh, kept.chats[1].avatar)
        assertNull(kept.chats[2].avatar)
    }

    @Test
    fun thumbnailIsKeptOnlyForTheSameMessage() {
        val old = bitmap()
        fun withMedia(uuid: String, key: String?, thumbnail: Bitmap?) =
            item(uuid, bitmap()).copy(previewMediaKey = key, previewThumbnail = thumbnail)
        val previous = ChatListState(chats = listOf(withMedia("a", "m1", old), withMedia("b", "m2", old), withMedia("c", "m3", old)))
        val next = ChatListState(chats = listOf(withMedia("a", "m1", null), withMedia("b", "m9", null), withMedia("c", null, null)))

        val kept = next.keepAvatars(previous)

        assertSame(old, kept.chats[0].previewThumbnail)
        assertNull(kept.chats[1].previewThumbnail) // a newer photo: not the old picture
        assertNull(kept.chats[2].previewThumbnail) // the last message is no photo any more
    }
}
