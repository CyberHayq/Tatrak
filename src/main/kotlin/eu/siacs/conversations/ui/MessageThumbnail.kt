package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.media.mediaDecodeDispatcher
import android.graphics.Bitmap
import android.util.Log
import eu.siacs.conversations.Config
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.services.XmppConnectionService
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** An image bubble's thumbnail, for the chat and its preview; null while unavailable. */
internal suspend fun loadMessageThumbnail(service: XmppConnectionService, conversation: Conversation, uuid: String, sizePx: Int): Bitmap? =
    // the shared bounded pool: a fling must not queue dozens of decodes ahead of the visible ones
    withContext(mediaDecodeDispatcher) {
        currentCoroutineContext().ensureActive()
        val message = conversation.findMessageWithUuid(uuid) ?: return@withContext null
        try {
            service.fileBackend.getThumbnail(message, sizePx, false)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            Log.d(Config.LOGTAG, "unable to load thumbnail", e)
            null
        }
    }
