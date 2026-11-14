package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.media.MediaActions
import am.cyberhayq.chat.ui.media.MediaBrowserScreen
import am.cyberhayq.chat.ui.media.MediaItem
import am.cyberhayq.chat.ui.media.MediaState
import am.cyberhayq.chat.ui.media.MediaTab
import am.cyberhayq.chat.ui.media.mediaDecodeDispatcher
import android.graphics.Bitmap
import android.view.View
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import eu.siacs.conversations.ui.adapter.MediaAdapter
import eu.siacs.conversations.ui.util.Attachment
import eu.siacs.conversations.ui.util.ViewUtil
import eu.siacs.conversations.utils.UIHelper
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tatrak: the Compose media browser of [MediaBrowserActivity]. Loading, sharing, saving and deleting
 * (with its confirmation dialog) stay in the activity; this class sorts what the activity loaded
 * into tabs and passes the selection back.
 */
internal class MediaBrowserCompose(private val activity: MediaBrowserActivity) : MediaActions {

    private val state = MutableStateFlow(MediaState())

    // replaced on the main thread with each publish
    private var attachments: Map<String, Attachment> = emptyMap()
    private var building: Job? = null

    fun attach(classic: View) {
        activity.composeOver(classic) {
            val current by state.collectAsStateWithLifecycle()
            MediaBrowserScreen(current, this)
        }
    }

    /** Called on the main thread with every attachment of the chat, newest first. */
    fun publish(all: List<Attachment>) {
        attachments = all.associateBy { it.uuid.toString() }
        building?.cancel()
        // file sizes and dates touch the disk
        building =
            activity.lifecycleScope.launch {
                val items = withContext(Dispatchers.IO) { all.map(::item) }
                state.value = state.value.copy(items = items, loaded = true)
            }
    }

    private fun item(attachment: Attachment): MediaItem {
        val type = MediaBrowserActivity.typeOf(attachment)
        val tab =
            when (type) {
                MediaBrowserActivity.FilterType.IMAGES, MediaBrowserActivity.FilterType.VIDEOS -> MediaTab.MEDIA
                MediaBrowserActivity.FilterType.AUDIO -> MediaTab.AUDIO
                else -> MediaTab.FILES
            }
        val uri = attachment.uri
        val file = if (uri.scheme == "file") uri.path?.let(::File) else null
        val details =
            if (file != null && file.exists()) {
                UIHelper.filesizeToString(am.cyberhayq.chat.storage.media.EncryptedMediaStore.get().length(file)) + " · " +
                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.lastModified()))
            } else {
                attachment.mime.orEmpty()
            }
        return MediaItem(
            key = attachment.uuid.toString(),
            tab = tab,
            name = uri.lastPathSegment ?: attachment.mime.orEmpty(),
            details = details,
            icon = MediaAdapter.getImageDrawable(attachment),
            video = type == MediaBrowserActivity.FilterType.VIDEOS,
            thumbnail = attachment.renderThumbnail(),
        )
    }

    private fun selected(keys: Set<String>): List<Attachment> = keys.mapNotNull { attachments[it] }

    // --- actions ------------------------------------------------------------------------------

    override fun back() = activity.finish()

    override fun open(key: String) {
        // the gallery's chat, so the viewer swipes through all of it
        ViewUtil.view(activity, attachments[key] ?: return, activity.intent.getStringExtra("account"), activity.intent.getStringExtra("jid"))
    }

    override fun share(keys: Set<String>) = activity.shareAttachments(selected(keys))

    override fun save(keys: Set<String>) = activity.saveAttachments(selected(keys))

    override fun delete(keys: Set<String>) = activity.deleteAttachments(selected(keys))

    override fun canSave(keys: Set<String>) = activity.canSaveAttachments(selected(keys))

    override suspend fun thumbnail(key: String, sizePx: Int): Bitmap? {
        val attachment = attachments[key] ?: return null
        val service = activity.xmppConnectionService ?: return null
        // FileBackend keeps the previews in the app's bitmap cache
        return withContext(mediaDecodeDispatcher) {
            currentCoroutineContext().ensureActive()
            runCatching { service.fileBackend.getPreviewForUri(attachment, sizePx, false) }.getOrNull()
        }
    }
}
