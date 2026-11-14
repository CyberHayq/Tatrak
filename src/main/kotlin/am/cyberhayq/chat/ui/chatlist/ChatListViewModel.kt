package am.cyberhayq.chat.ui.chatlist

import am.cyberhayq.chat.ui.state.SnapshotSource
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.siacs.conversations.R
import eu.siacs.conversations.services.XmppConnectionService
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

class ChatListViewModel(application: Application) : AndroidViewModel(application) {

    private val avatarSize =
        application.resources.getDimensionPixelSize(R.dimen.tatrak_chat_list_avatar)

    private val thumbnailSize =
        (PREVIEW_THUMBNAIL_DP * application.resources.displayMetrics.density).roundToInt()

    private val source =
        SnapshotSource(viewModelScope, ChatListState()) { service, publish ->
            // cached avatars and thumbnails only first, so the list shows up without waiting for
            // the disk; those not cached yet keep the previous snapshot's bitmap
            val previous = state.value
            val fast = ChatListSnapshot.build(application, service, avatarSize, true, thumbnailSize = thumbnailSize)
            publish(fast.keepAvatars(previous))
            if (fast.missingBitmaps()) {
                // the rows on screen first, then the rest
                val top = ChatListSnapshot.build(application, service, avatarSize, false, TOP_ROWS, thumbnailSize)
                publish(top.keepAvatars(previous))
                if (top.missingBitmaps()) {
                    publish(ChatListSnapshot.build(application, service, avatarSize, false, thumbnailSize = thumbnailSize))
                }
            }
        }

    val state: StateFlow<ChatListState> get() = source.state

    fun invalidate(service: XmppConnectionService) = source.invalidate(service)

    private fun ChatListState.missingBitmaps() =
        (chats + archived).any { it.avatar == null || (it.previewMediaKey != null && it.previewThumbnail == null) } ||
            account?.avatar == null

    private companion object {
        const val TOP_ROWS = 20
    }
}
