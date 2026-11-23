package eu.siacs.conversations.ui

import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import am.cyberhayq.chat.ui.settings.appLanguageContext
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.media.SaveResult
import am.cyberhayq.chat.ui.media.mediaDecodeDispatcher
import am.cyberhayq.chat.ui.storage.MediaPrompts
import am.cyberhayq.chat.ui.viewer.MediaViewerScreen
import am.cyberhayq.chat.ui.viewer.ViewerActions
import am.cyberhayq.chat.ui.viewer.ViewerImage
import am.cyberhayq.chat.ui.viewer.ViewerImages
import am.cyberhayq.chat.ui.viewer.ViewerInfo
import am.cyberhayq.chat.ui.viewer.ViewerItem
import am.cyberhayq.chat.ui.viewer.ViewerState
import android.graphics.Bitmap
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.persistance.FileBackend
import eu.siacs.conversations.ui.util.Attachment
import eu.siacs.conversations.ui.util.ViewUtil
import eu.siacs.conversations.utils.UIHelper
import eu.siacs.conversations.xmpp.Jid
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Tatrak: shows a chat's photos and videos inside the app instead of handing them to another app.
 * Opens on the tapped file at once, then loads the chat's other media (the same query as the
 * media browser) so one can swipe through them. "Open with…" still hands the file out.
 */
class MediaViewerActivity : XmppActivity(), ViewerActions {

    private class Entry(val file: File, val mime: String?)

    private val state = MutableStateFlow(ViewerState())
    private val entries = HashMap<String, Entry>()
    private var conversation: Conversation? = null
    private var loadStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val path = intent.getStringExtra(EXTRA_FILE)
        val uuid = intent.getStringExtra(EXTRA_UUID)
        if (path == null || uuid == null) {
            finish()
            return
        }
        val mime = intent.getStringExtra(EXTRA_MIME)
        entries[uuid] = Entry(File(path), mime)
        state.value = ViewerState(listOf(ViewerItem(uuid, isVideo(mime))))
        setContent {
            val current by state.collectAsStateWithLifecycle()
            TatrakTheme(darkTheme = true) { MediaViewerScreen(current, this) }
        }
    }

    override fun refreshUiReal() {}

    override fun onBackendConnected() {
        if (loadStarted) return
        val service = xmppConnectionService ?: return
        val uuid = intent.getStringExtra(EXTRA_UUID) ?: return
        loadStarted = true
        val account = intent.getStringExtra(EXTRA_ACCOUNT)
        val jid = intent.getStringExtra(EXTRA_JID)?.let(Jid::ofOrInvalid)
        conversation =
            if (account != null && jid != null) {
                service.findAccountByUuid(account)?.let { service.find(it, jid) }
            } else {
                service.conversations.firstOrNull { it.findMessageWithUuid(uuid) != null }
            }
        val chat = conversation
        val onLoaded = { all: List<Attachment> -> runOnUiThread { publish(uuid, all) } }
        when {
            chat != null -> service.getAttachments(chat, 0, onLoaded)
            account != null && jid != null -> service.getAttachments(account, jid, 0, onLoaded)
        }
    }

    private fun publish(start: String, all: List<Attachment>) {
        val (media, index) = viewerPages(start, all) ?: return
        media.forEach { entries[it.uuid.toString()] = Entry(File(it.uri.path!!), it.mime) }
        state.value = ViewerState(media.map { ViewerItem(it.uuid.toString(), isVideo(it.mime)) }, index)
    }

    private fun attachment(key: String): Attachment? {
        val entry = entries[key] ?: return null
        return Attachment.of(UUID.fromString(key), entry.file, entry.mime)
    }

    // --- actions ------------------------------------------------------------------------------

    override fun close() = finish()

    override fun share(key: String) {
        val entry = entries[key] ?: return
        val uri =
            try {
                FileBackend.getUriForUri(this, Uri.fromFile(entry.file)).buildUpon().appendQueryParameter("uuid", key).build()
            } catch (e: SecurityException) {
                Toast.makeText(this, getString(R.string.no_permission_to_access_x, entry.file.absolutePath), Toast.LENGTH_SHORT).show()
                return
            }
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType(ViewUtil.nullToWildcard(entry.mime))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, null))
    }

    // only a store file is saved (a shared-folder file already is one); no service needed, so the
    // button is right on the first frame, before the service is bound
    override fun canSave(key: String): Boolean {
        val entry = entries[key] ?: return false
        return runCatching { EncryptedMediaStore.get(this).sharedName(entry.file) != null }.getOrDefault(false)
    }

    /** The first "Save" warns that the copy is not encrypted. */
    override fun save(key: String) = MediaPrompts.confirmSave(this) { saveNow(key) }

    /** Same as the media browser's "save": copies a file from private storage to the shared one. */
    private fun saveNow(key: String) {
        val service = xmppConnectionService ?: return
        val attachment = attachment(key) ?: return
        val fileBackend = service.fileBackend
        val locations = fileBackend.inferStorageLocation(listOf(attachment))
        if (locations.isEmpty()) return
        Futures.addCallback(
            fileBackend.saveInternalToExternal(locations),
            object : FutureCallback<List<File?>> {
                override fun onSuccess(result: List<File?>) {
                    val context = appLanguageContext(this@MediaViewerActivity)
                    Toast.makeText(context, SaveResult.message(context, result), Toast.LENGTH_LONG)
                        .show()
                }

                override fun onFailure(t: Throwable) {
                    Log.e(Config.LOGTAG, "could not save attachment", t)
                    Toast.makeText(appLanguageContext(this@MediaViewerActivity), R.string.could_not_save_files, Toast.LENGTH_LONG).show()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun openWith(key: String) {
        val entry = entries[key] ?: return
        ViewUtil.viewExternally(this, entry.file, key, ViewUtil.nullToWildcard(entry.mime))
    }

    override suspend fun info(key: String): ViewerInfo? {
        val entry = entries[key] ?: return null
        val chat = conversation
        val service = xmppConnectionService
        return withContext(Dispatchers.IO) {
            val message =
                if (chat == null) null
                else chat.findMessageWithUuid(key) ?: runCatching { service?.databaseBackend?.getMessageWithUuidOrRemoteId(chat, key) }.getOrNull()
            if (message != null) {
                ViewerInfo(UIHelper.getMessageDisplayName(message), dateTime(message.timeSent))
            } else {
                ViewerInfo(entry.file.name, if (entry.file.exists()) dateTime(entry.file.lastModified()) else "")
            }
        }
    }

    override suspend fun image(key: String, maxPx: Int): ViewerImage? {
        val entry = entries[key] ?: return null
        return withContext(mediaDecodeDispatcher) {
            currentCoroutineContext().ensureActive()
            ViewerImages.decode(entry.file, entry.mime, maxPx)
        }
    }

    // the chat and the media browser keep thumbnails under the message uuid
    override fun preview(key: String): Bitmap? = xmppConnectionService?.bitmapCache?.get(key)

    override fun videoFile(key: String): java.io.File? = entries[key]?.file

    private fun dateTime(time: Long) =
        DateUtils.formatDateTime(this, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)

    companion object {
        private const val EXTRA_FILE = "file"
        private const val EXTRA_UUID = "uuid"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_ACCOUNT = "account"
        private const val EXTRA_JID = "jid"

        private fun isVideo(mime: String?) = mime?.startsWith("video/") == true

        /** Photos and videos open here; SVG and everything else go to another app. */
        @JvmStatic
        fun canShow(mime: String?): Boolean =
            mime != null && ((mime.startsWith("image/") && !mime.startsWith("image/svg")) || isVideo(mime))

        /**
         * @param account with [jid], the chat whose media to swipe through; without them the chat
         *   is found by the message [uuid] among the loaded ones.
         */
        @JvmStatic
        @JvmOverloads
        fun intent(context: Context, file: File, uuid: String, mime: String?, account: String? = null, jid: String? = null): Intent =
            Intent(context, MediaViewerActivity::class.java)
                .putExtra(EXTRA_FILE, file.absolutePath)
                .putExtra(EXTRA_UUID, uuid)
                .putExtra(EXTRA_MIME, mime)
                .putExtra(EXTRA_ACCOUNT, account)
                .putExtra(EXTRA_JID, jid)
    }
}

/** Returns null when the viewer should keep its initial single page. */
internal fun viewerPages(start: String, all: List<Attachment>): Pair<List<Attachment>, Int>? {
    val media = all.filter { MediaViewerActivity.canShow(it.mime) && it.uri.scheme == "file" }.reversed()
    val index = media.indexOfFirst { it.uuid.toString() == start }
    return if (index < 0 || media.size < 2) null else media to index
}
