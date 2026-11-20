package eu.siacs.conversations.ui

import am.cyberhayq.chat.storage.media.TemporaryMedia
import am.cyberhayq.chat.ui.media.mediaDecodeDispatcher
import am.cyberhayq.chat.ui.storage.MediaPrompts
import am.cyberhayq.chat.ui.chat.ChatSnapshot
import am.cyberhayq.chat.ui.common.confirmAction
import am.cyberhayq.chat.ui.common.confirmClearHistory
import am.cyberhayq.chat.ui.common.deleteFilesText
import am.cyberhayq.chat.ui.chat.ChatActions
import am.cyberhayq.chat.ui.chat.ChatBanner
import am.cyberhayq.chat.ui.chat.ChatScreen
import am.cyberhayq.chat.ui.chat.ChatViewModel
import am.cyberhayq.chat.ui.chat.FrequentReactions
import am.cyberhayq.chat.ui.chat.LocalSwipeBack
import am.cyberhayq.chat.ui.media.SaveResult
import am.cyberhayq.chat.ui.chat.LocalSwipeMethod
import am.cyberhayq.chat.ui.chat.SwipeMethod
import am.cyberhayq.chat.ui.chat.MentionCandidate
import am.cyberhayq.chat.ui.chat.MessageFocus
import am.cyberhayq.chat.ui.chat.highlightInConference
import am.cyberhayq.chat.ui.chat.ReplyPreview
import am.cyberhayq.chat.ui.chat.StickerStore
import am.cyberhayq.chat.ui.chat.SwipeBackLayout
import am.cyberhayq.chat.ui.chat.VoicePlayer
import am.cyberhayq.chat.ui.chat.VoiceRecorder
import am.cyberhayq.chat.ui.chat.Wallpapers
import am.cyberhayq.chat.ui.chat.currentTextPalette
import am.cyberhayq.chat.ui.chat.appendToDraft
import am.cyberhayq.chat.ui.chat.insertLinkAtCursor
import am.cyberhayq.chat.ui.chat.replyId
import am.cyberhayq.chat.ui.chat.replyQuote
import am.cyberhayq.chat.ui.chat.SelectedText
import am.cyberhayq.chat.ui.chat.selectionCopyText
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.gultsch.common.Patterns
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.MucOptions
import eu.siacs.conversations.entities.ReplyTo
import eu.siacs.conversations.entities.TransferablePlaceholder
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.persistance.FileBackend
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.text.FixedURLSpan
import eu.siacs.conversations.ui.util.Attachment
import eu.siacs.conversations.ui.util.PresenceSelector
import eu.siacs.conversations.ui.util.QuoteHelper
import eu.siacs.conversations.ui.util.ShareUtil
import eu.siacs.conversations.ui.util.TrustKeysGate
import eu.siacs.conversations.ui.util.ViewUtil
import eu.siacs.conversations.utils.Compatibility
import eu.siacs.conversations.utils.Emoticons
import eu.siacs.conversations.utils.GeoHelper
import eu.siacs.conversations.utils.MessageUtils
import eu.siacs.conversations.utils.NickValidityChecker
import eu.siacs.conversations.utils.QuickLoader
import eu.siacs.conversations.utils.TimeFrameUtils
import eu.siacs.conversations.utils.UIHelper
import eu.siacs.conversations.xmpp.manager.ChatStateManager
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection
import eu.siacs.conversations.xmpp.jingle.Media
import eu.siacs.conversations.xmpp.manager.HttpUploadManager
import eu.siacs.conversations.xmpp.manager.JingleManager
import eu.siacs.conversations.xmpp.manager.MessageArchiveManager
import eu.siacs.conversations.xmpp.manager.ModerationManager
import eu.siacs.conversations.xmpp.mam.MamReference
import im.conversations.android.provider.VCardProvider
import im.conversations.android.xmpp.model.state.Composing
import im.conversations.android.xmpp.model.state.Paused
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Hosts the Compose chat screen inside [ConversationsActivity]. Sending, trust checks,
 * attachments and reactions go through the same service and activity methods as
 * [ConversationFragment]; anything not ported is reached via [ConversationsActivity.openClassicConversation].
 *
 * Lives in this package because [XmppFragment.refresh] is package-private.
 */
class ChatFragment : XmppFragment(), ConversationHost, ChatActions {

    private sealed interface AfterTrust {
        data object Text : AfterTrust

        data class Attachments(val attachments: List<Attachment>) : AfterTrust
    }

    private val viewModel by lazy { ViewModelProvider(this)[ChatViewModel::class.java] }
    private val voiceRecorder by lazy { VoiceRecorder(requireContext().applicationContext) }
    private var voiceWarning: androidx.appcompat.app.AlertDialog? = null

    private val voicePlayer by lazy {
        VoicePlayer(lifecycleScope, requireContext().getSystemService(AudioManager::class.java)) {
            context?.let { Toast.makeText(it, R.string.tatrak_viewer_load_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private var conversation: Conversation? = null
    private var pendingUuid: String? = null
    /** Picker results that arrived before the service was bound (process death). */
    private var pendingAttachments: List<Attachment>? = null
    private var pendingExtras: Bundle? = null
    private var afterTrust: AfterTrust? = null
    private var afterPermissions: (() -> Unit)? = null
    private var userTyping = false
    private var typingTimeout: Job? = null
    /** Newest message on screen; sent again on resume because the activity ignores reads while paused. */
    private var lastVisibleUuid: String? = null
    /** Loading history up to a search result; see [showMessage]. */
    private var jumpJob: Job? = null

    private val trustKeysLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val pending = afterTrust
            afterTrust = null
            if (result.resultCode == Activity.RESULT_OK) {
                when (pending) {
                    AfterTrust.Text -> sendMessage()
                    is AfterTrust.Attachments -> commitAttachments(pending.attachments)
                    null -> Unit
                }
            }
        }

    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isNotEmpty()) {
                addPreviews(Attachment.of(requireContext(), uris, null))
            }
        }

    private val openDocumentsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                addPreviews(uris.flatMap { Attachment.of(requireContext(), it, Attachment.Type.FILE) })
            }
        }

    /** Where the camera app writes the photo; kept across recreation like the classic STATE_PHOTO_URI. */
    private var pendingPhotoUri: Uri? = null

    private val takePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = pendingPhotoUri
            pendingPhotoUri = null
            if (result.resultCode == Activity.RESULT_OK && uri != null) {
                // the draft is the encrypted capture; the plain one only if encrypting failed. The view
                // model's scope, not the fragment: a rotation while encrypting must not lose the photo
                val context = requireContext().applicationContext
                val chat = conversation?.uuid
                viewModel.viewModelScope.launch {
                    val stored = withContext(Dispatchers.IO) { uri.path?.let { TemporaryMedia.encryptCapture(context, File(it)) } }
                    // another chat opened meanwhile: the capture was for the one left, a discarded draft
                    if (conversation?.uuid != chat) {
                        stored?.delete()
                        return@launch
                    }
                    val added = Attachment.of(context, stored?.let(Uri::fromFile) ?: uri, Attachment.Type.IMAGE)
                    if (stored != null) viewModel.cameraDrafts += added.map { it.uuid }
                    addPreviews(added)
                }
            }
        }

    /** ConversationFragment ATTACHMENT_CHOICE_RECORD_VIDEO: the camera app returns the video in the result. */
    private val recordVideoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                addPreviews(Attachment.extractAttachments(requireContext(), data, Attachment.Type.FILE))
            }
        }

    // ConversationFragment.handlePositiveActivityResult for ATTACHMENT_CHOICE_CONTACT
    private val pickContactLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                return@registerForActivityResult
            }
            val vCard =
                try {
                    VCardProvider(requireContext()).vCardFromPickIntent(data.data)
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(requireContext(), R.string.invalid_contact, Toast.LENGTH_LONG).show()
                    Log.d(Config.LOGTAG, "could not find vcf uri", e)
                    return@registerForActivityResult
                }
            addPreviews(Attachment.of(vCard, "text/x-vcard"))
        }

    private val shareLocationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                return@registerForActivityResult
            }
            // same geo URI as ConversationFragment.handlePositiveActivityResult
            val latitude = data.getDoubleExtra("latitude", 0.0)
            val longitude = data.getDoubleExtra("longitude", 0.0)
            val accuracy = data.getIntExtra("accuracy", 0)
            val geo =
                if (accuracy > 0) {
                    Uri.parse(String.format("geo:%s,%s;u=%s", latitude, longitude, accuracy))
                } else {
                    Uri.parse(String.format("geo:%s,%s", latitude, longitude))
                }
            commitAttachments(Attachment.of(requireContext(), geo, Attachment.Type.LOCATION))
        }

    private val wallpaperPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val spec = Wallpapers.importPhoto(context, uri)
                if (spec == null) {
                    Toast.makeText(context, R.string.tatrak_wallpaper_failed, Toast.LENGTH_SHORT).show()
                } else {
                    setWallpaper(spec)
                }
            }
        }

    private val stickerPicker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            val context = requireContext().applicationContext
            lifecycleScope.launch { StickerStore.import(context, uris) }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val action = afterPermissions
            afterPermissions = null
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                XmppConnectionService.toggleForegroundService(activity as? XmppActivity)
            }
            if (result.values.all { it }) {
                action?.invoke()
            } else {
                // the message for the first denied permission, as ConversationFragment
                val res =
                    when (result.entries.firstOrNull { !it.value }?.key) {
                        Manifest.permission.RECORD_AUDIO -> R.string.no_microphone_permission
                        Manifest.permission.CAMERA -> R.string.no_camera_permission
                        else -> R.string.no_storage_permission
                    }
                Toast.makeText(
                        requireContext(),
                        getString(res, getString(R.string.app_name)),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }

    /** Registers its permission request here: a fragment may do so only before it is created. */
    private var calls: RtpCallStarter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        calls = RtpCallStarter(requireXmppActivity(), this)
        if (conversation == null) {
            pendingUuid = savedInstanceState?.getString(STATE_CONVERSATION_UUID)
        }
        // the trust screen's result can arrive in a recreated fragment (rotation, process death)
        savedInstanceState
            ?.let { BundleCompat.getParcelableArrayList(it, STATE_TRUST_ATTACHMENTS, Attachment::class.java) }
            ?.let { afterTrust = AfterTrust.Attachments(it) }
        pendingPhotoUri = savedInstanceState?.getString(STATE_PHOTO_URI)?.let(Uri::parse)
        // a rotation keeps them in the view model; this is for a recreated process
        savedInstanceState
            ?.let { BundleCompat.getParcelableArrayList(it, STATE_MEDIA_PREVIEWS, Attachment::class.java) }
            ?.let {
                if (viewModel.previews.isEmpty()) {
                    viewModel.previews = it
                    savedInstanceState.getStringArrayList(STATE_CAMERA_DRAFTS)?.mapTo(viewModel.cameraDrafts, UUID::fromString)
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val layout = SwipeBackLayout(requireActivity())
        // two panes: the chat is not on the back stack, nothing to swipe back to
        layout.swipeEnabled = !ConversationsActivity.isTabletView(requireActivity())
        layout.addView(
            ComposeView(requireContext()).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val swipeMethod = SwipeMethod.remember()
                    SideEffect { layout.dragEnabled = swipeMethod == SwipeMethod.TELEGRAM }
                    CompositionLocalProvider(LocalSwipeBack provides layout, LocalSwipeMethod provides swipeMethod) {
                        TatrakTheme {
                            val palette = currentTextPalette()
                            LaunchedEffect(palette) { viewModel.setPalette(palette) }
                            val state by viewModel.state.collectAsStateWithLifecycle()
                            ChatScreen(
                                state = state,
                                input = viewModel.input,
                                onInputChange = ::onInputChange,
                                reply = viewModel.reply,
                                actions = this@ChatFragment,
                                voice = voicePlayer.state,
                                recording = voiceRecorder.state,
                                focus = viewModel.focus,
                                onFocused = { if (viewModel.focus == it) viewModel.focus = null },
                                previews = viewModel.previews.map { it.uuid.toString() },
                                notice = viewModel.notice,
                                onNoticeShown = { viewModel.notice = null },
                                seenMentions = viewModel.seenMentions,
                            )
                        }
                    }
                }
            }
        )
        return layout
    }

    /** Set by [ConversationsActivity.showConversation] when this chat slides in over the list. */
    var slideAfterLoad = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // As Telegram: slide in once the first messages are laid out (listReady). Composing them in
        // the middle of the slide dropped frames on slower phones. The fragment still reaches
        // onStart (bind) while postponed; it stays below RESUMED, see markRead.
        // shortcut: fixed 300 ms cap, after it the slide starts and messages pop in mid-way as before
        if (slideAfterLoad) postponeEnterTransition(300, TimeUnit.MILLISECONDS)
    }

    override fun listReady() {
        if (!slideAfterLoad) return
        slideAfterLoad = false
        viewLifecycleOwner.lifecycleScope.launch {
            // the next frame lays out the initial scroll (alpha 0), the one after starts the slide
            awaitFrame()
            awaitFrame()
            startPostponedEnterTransition()
        }
    }

    /** Animators, not the old view animations: only these can follow a back gesture. */
    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): Animator? =
        slideAnimator(this, nextAnim, R.animator.tatrak_chat_enter, R.animator.tatrak_chat_pop_exit, 1f)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        conversation?.let { outState.putString(STATE_CONVERSATION_UUID, it.uuid) }
        (afterTrust as? AfterTrust.Attachments)?.let {
            outState.putParcelableArrayList(STATE_TRUST_ATTACHMENTS, ArrayList(it.attachments))
        }
        pendingPhotoUri?.let { outState.putString(STATE_PHOTO_URI, it.toString()) }
        if (viewModel.previews.isNotEmpty()) {
            outState.putParcelableArrayList(STATE_MEDIA_PREVIEWS, ArrayList(viewModel.previews))
            outState.putStringArrayList(STATE_CAMERA_DRAFTS, viewModel.cameraDrafts.mapTo(ArrayList()) { it.toString() })
        }
    }

    override fun onStart() {
        super.onStart()
        val service = xmppConnectionService ?: return
        val c = conversation ?: return
        // extras handed to reInit before this fragment was added
        val extras = pendingExtras
        pendingExtras = null
        bind(service, c, extras)
    }

    override fun onResume() {
        super.onResume()
        view?.post { lastVisibleUuid?.let(::markRead) }
    }

    override fun onStop() {
        super.onStop()
        voicePlayer.pause()
        // like RecordingActivity.onStop: leaving the screen discards the recording
        if (voiceRecorder.state.value.active) stopRecording(send = false)
        voiceWarning?.dismiss() // shown again by the next hold; dismissing does not accept it
        voiceWarning = null
        val c = conversation ?: return
        saveDraft(c)
        xmppConnectionService?.notificationService?.setOpenConversation(null)
    }

    override fun onDestroy() {
        voicePlayer.release()
        super.onDestroy()
    }

    override fun getConversation(): Conversation? = conversation

    /** Called by the activity like [ConversationFragment.reInit]. */
    fun reInit(conversation: Conversation, extras: Bundle?) {
        QuickLoader.set(conversation.uuid)
        val previous = this.conversation
        if (previous != null && previous !== conversation) {
            saveDraft(previous)
            // ConversationFragment.saveMessageDraftStopAudioPlayer clears the media previews too
            TemporaryMedia.deleteDrafts(viewModel.cameraDrafts, viewModel.previews)
            viewModel.previews = emptyList()
            voicePlayer.release()
            voiceRecorder.stop(keep = false)
        }
        this.conversation = conversation
        // as ConversationFragment.reInit: a read chat opens with the last pages only, so loaded
        // history does not keep growing (and every snapshot with it) across visits
        if (extras != null && conversation.isRead) conversation.trim()
        val service = xmppConnectionService
        if (service == null || !isAdded) {
            pendingExtras = extras
            return
        }
        bind(service, conversation, extras)
    }

    override fun onBackendConnected() {
        val service = xmppConnectionService ?: return
        var c = conversation
        if (c == null) {
            val uuid = pendingUuid ?: return
            c = service.findConversationByUuid(uuid)
            if (c == null) {
                // restored after process death, but the chat is gone: leave like ConversationFragment
                (activity as? ConversationsActivity)?.onConversationArchived(null)
                return
            }
            conversation = c
        }
        pendingUuid = null
        val extras = pendingExtras
        pendingExtras = null
        bind(service, c, extras)
        commitPendingAttachments()
    }

    override fun refresh() {
        val service = xmppConnectionService ?: return
        val c = conversation ?: return
        if (!service.isConversationStillOpen(c)) {
            (activity as? ConversationsActivity)?.onConversationArchived(c)
            return
        }
        viewModel.invalidate(service)
    }

    private fun bind(service: XmppConnectionService, c: Conversation, extras: Bundle?) {
        if (!service.isConversationStillOpen(c)) {
            (activity as? ConversationsActivity)?.onConversationArchived(c)
            return
        }
        if (viewModel.conversation !== c) {
            viewModel.bind(c, service)
            val participating =
                c.mode == Conversational.MODE_SINGLE || c.mucOptions.participating()
            val draft = c.draft
            viewModel.setInput(if (participating && draft != null) draft.message() else "")
            userTyping = false
            lastVisibleUuid = null
        } else {
            viewModel.invalidate(service)
        }
        c.messagesLoaded.set(true)
        service.notificationService.setOpenConversation(c)
        extras?.let { processExtras(c, it) }
    }

    // --- composer ---------------------------------------------------------------------------

    private fun onInputChange(value: TextFieldValue) {
        val old = viewModel.input.text
        viewModel.input = value
        if (value.text != old) onTextChanged(value.text)
    }

    /** Chat states as EditMessage + ConversationFragment.onTyping… send them. */
    private fun onTextChanged(text: String) {
        val c = conversation ?: return
        if (xmppConnectionService == null) return
        typingTimeout?.cancel()
        typingTimeout =
            viewLifecycleOwner.lifecycleScope.launch {
                delay(Config.TYPING_TIMEOUT * 1000L)
                if (userTyping) {
                    userTyping = false
                    ChatStateManager.send(c, Paused::class.java)
                }
            }
        if (!userTyping && text.isNotEmpty()) {
            userTyping = true
            ChatStateManager.send(c, Composing::class.java)
        } else if (text.isEmpty()) {
            userTyping = false
            ChatStateManager.send(c, Config.DEFAULT_CHAT_STATE)
            if (storeNextMessage(c, "")) {
                (activity as? ConversationsActivity)?.onConversationsListItemUpdated()
            }
        }
    }

    private fun saveDraft(c: Conversation) {
        if (xmppConnectionService == null) return
        val text = viewModel.input.text
        storeNextMessage(c, text)
        typingTimeout?.cancel()
        userTyping = false
        ChatStateManager.send(c, if (text.isEmpty()) Config.DEFAULT_CHAT_STATE else Paused::class.java)
    }

    /** ConversationFragment.storeNextMessage. */
    private fun storeNextMessage(
        c: Conversation,
        text: String,
        service: XmppConnectionService? = xmppConnectionService,
    ): Boolean {
        service ?: return false
        val participating =
            c.mode == Conversational.MODE_SINGLE || c.mucOptions.participating()
        if (c.status != Conversation.STATUS_ARCHIVED && participating && c.setNextMessage(text)) {
            service.updateConversation(c)
            return true
        }
        return false
    }

    override fun send() = sendMessage()

    /** The sequence of ConversationFragment.sendMessage(), minus PGP which stays classic. */
    private fun sendMessage() {
        // ConversationFragment.sendMessage: with previews, Send commits them and nothing else
        val previews = viewModel.previews
        if (previews.isNotEmpty()) {
            viewModel.previews = emptyList()
            commitAttachments(previews)
            return
        }
        val c = conversation ?: return
        val service = xmppConnectionService ?: return
        val typed = viewModel.input.text
        val reply = viewModel.reply
        val quote =
            if (reply != null && c.correctingMessage == null && typed.isNotEmpty()) {
                quoteBlock(reply.text) + "\n"
            } else {
                ""
            }
        val body = quote + typed
        if (body.isEmpty()) return
        if (c.nextEncryption == Message.ENCRYPTION_PGP) {
            openClassic()
            return
        }
        if (trustKeysIfNeeded(c, AfterTrust.Text)) return
        val message: Message
        val correcting = c.correctingMessage
        if (correcting == null) {
            message = Message(c, body, c.nextEncryption)
            Message.configurePrivateMessage(message)
            if (quote.isNotEmpty()) {
                message.replyTo = reply?.let { message(it.uuid) }?.let { replyTo(c, it, quote) }
            }
        } else {
            message = correcting
            val uuid = message.uuid
            // the quote fallback was hidden while editing; put it back in front of the new text
            val edited = keptFallback(message) + body
            // this resets both encryption and fingerprint; when using axolotl fingerprint will be
            // added again on send
            message.putEdited(Message.BodyVersion(edited, c.nextEncryption, null))
            check(DatabaseBackend.getInstance(requireContext()).updateMessage(message, uuid)) {
                "Could not update message after edit"
            }
        }
        service.sendMessage(message)
        messageSent(c)
    }

    /** XEP-0461 reference to [parent]; null when it has no [replyId] yet, then only the quote is sent. */
    private fun replyTo(c: Conversation, parent: Message, quote: String): ReplyTo? {
        val id = replyId(parent, c.mode == Conversational.MODE_MULTI) ?: return null
        val to =
            when {
                parent.status == Message.STATUS_RECEIVED -> parent.counterpart
                c.mode == Conversational.MODE_MULTI -> c.mucOptions.self.fullJid
                else -> c.account.jid.asBareJid()
            }
        return ReplyTo(id, to?.toString(), 0, quote.codePointCount(0, quote.length))
    }

    /** The quote fallback at the start of [message]'s body, kept when the text is edited. */
    private fun keptFallback(message: Message): String {
        val reply = message.replyTo ?: return ""
        if (!reply.hasFallback() || reply.fallbackStart != 0) return ""
        val body = message.body
        if (reply.fallbackEnd > body.codePointCount(0, body.length)) return ""
        return body.substring(0, body.offsetByCodePoints(0, reply.fallbackEnd))
    }

    /** ConversationFragment.messageSent. */
    private fun messageSent(c: Conversation) {
        viewModel.reply = null
        if (c.setCorrectingMessage(null)) {
            viewModel.setInput(c.draftMessage.orEmpty())
            c.setDraftMessage(null)
        } else {
            viewModel.setInput("")
        }
        storeNextMessage(c, viewModel.input.text)
        userTyping = false
        refresh()
    }

    /**
     * Same OMEMO gate as ConversationFragment.trustKeysIfNeeded(Conversation, int): only for
     * OMEMO, and the check itself is the shared [TrustKeysGate].
     */
    private fun trustKeysIfNeeded(c: Conversation, then: AfterTrust): Boolean {
        if (c.nextEncryption != Message.ENCRYPTION_AXOLOTL) return false
        val intent = TrustKeysGate.intentIfTrustNeeded(requireActivity(), c) ?: return false
        afterTrust = then
        trustKeysLauncher.launch(intent)
        return true
    }

    // --- attachments ------------------------------------------------------------------------

    override fun attachGallery() {
        if (requireClassicForPgp()) return
        pickMediaLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    /** ConversationFragment.attachFile + invokeAttachFileIntent for ATTACHMENT_CHOICE_TAKE_PHOTO. */
    override fun takePhoto() {
        if (requireClassicForPgp()) return
        withPermissions(
            listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA)
        ) {
            val context = requireContext()
            val file = FileBackend.Cache(context).takePicture()
            val intent =
                Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, FileBackend.getUriForFile(context, file))
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pendingPhotoUri = Uri.fromFile(file)
            try {
                takePhotoLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                pendingPhotoUri = null
                Toast.makeText(context, R.string.no_application_found, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** ConversationFragment.attachFile + invokeAttachFileIntent for ATTACHMENT_CHOICE_RECORD_VIDEO. */
    override fun recordVideo() {
        if (requireClassicForPgp()) return
        withPermissions(
            listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA)
        ) {
            try {
                recordVideoLauncher.launch(Intent(MediaStore.ACTION_VIDEO_CAPTURE))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(requireContext(), R.string.no_application_found, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun attachFile() {
        if (requireClassicForPgp()) return
        openDocumentsLauncher.launch(arrayOf(ViewUtil.WILDCARD))
    }

    override fun attachLocation() {
        if (requireClassicForPgp()) return
        shareLocationLauncher.launch(Intent(requireContext(), ShareLocationActivity::class.java))
    }

    /** ConversationFragment.attachFile + invokeAttachFileIntent for ATTACHMENT_CHOICE_CONTACT. */
    override fun attachContact() {
        if (requireClassicForPgp()) return
        withPermissions(listOf(Manifest.permission.READ_CONTACTS)) {
            try {
                pickContactLauncher.launch(ConversationFragment.contactPickIntent(requireContext()))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(requireContext(), R.string.no_application_found, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun startRecording(): Boolean {
        if (requireClassicForPgp()) return false
        val permissions =
            listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO)
        if (!hasPermissions(permissions)) {
            // the hold is over by the time the dialog closes; the user holds again
            withPermissions(permissions) {}
            return false
        }
        voicePlayer.pause()
        val context = requireContext()
        if (!MediaPrompts.voiceWarningDue(context) && voiceRecorder.start()) {
            view?.keepScreenOn = true
            return true
        }
        // a plain-file recording is announced first (also right after a failed pipe start); the
        // hold records nothing and the user holds again, as after the permission request
        if (MediaPrompts.voiceWarningDue(context)) voiceWarning = MediaPrompts.warnPlainVoice(context, {}, {})
        else Toast.makeText(context, R.string.unable_to_start_recording, Toast.LENGTH_SHORT).show()
        return false
    }

    /** The RecordingActivity result path: the file goes through [commitAttachments]. */
    override fun stopRecording(send: Boolean) {
        view?.keepScreenOn = false
        // the file is written asynchronously: send it to the chat it was recorded in
        val target = conversation
        val host = activity as? XmppActivity
        voiceRecorder.stop(send) { file ->
            val context = host ?: return@stop
            if (file == null) {
                Toast.makeText(context, R.string.unable_to_save_recording, Toast.LENGTH_SHORT).show()
                return@stop
            }
            // the store file is adopted by the message without a copy
            val recordings =
                Attachment.extractAttachments(
                    context,
                    Intent().setData(Uri.fromFile(file)),
                    Attachment.Type.RECORDING,
                )
            if (isAdded) commitAttachments(recordings, target)
            else if (target != null) sendDetached(host, target, recordings)
        }
    }

    /**
     * The chat was left while the note was being written: send it like [commitAttachments] when
     * no dialog is needed (key trust, choosing a device); otherwise say it was not sent.
     */
    private fun sendDetached(host: XmppActivity, c: Conversation, recordings: List<Attachment>) {
        val service = host.xmppConnectionService
        val needsDialog =
            (c.nextEncryption == Message.ENCRYPTION_AXOLOTL &&
                TrustKeysGate.intentIfTrustNeeded(host, c) != null) ||
                !(c.mode == Conversation.MODE_MULTI ||
                    Attachment.canBeSendInBand(recordings) ||
                    (c.account.httpUploadAvailable() &&
                        FileBackend.allFilesUnderSize(host, recordings, getMaxHttpUploadSize(c))))
        if (service == null || recordings.isEmpty() || needsDialog) {
            Toast.makeText(host, R.string.tatrak_voice_not_sent, Toast.LENGTH_LONG).show()
            return
        }
        for (recording in recordings) {
            Futures.addCallback(
                service.attachFileToConversation(c, recording.uri, recording.mime),
                object : FutureCallback<Void?> {
                    override fun onSuccess(result: Void?) = Unit

                    override fun onFailure(t: Throwable) {
                        Log.d(Config.LOGTAG, "could not attach voice note", t)
                        Toast.makeText(host, R.string.tatrak_voice_not_sent, Toast.LENGTH_LONG).show()
                    }
                },
                ContextCompat.getMainExecutor(host),
            )
        }
    }

    private fun requireClassicForPgp(): Boolean {
        val c = conversation ?: return true
        if (c.nextEncryption == Message.ENCRYPTION_PGP) {
            openClassic()
            return true
        }
        return false
    }

    /** ConversationFragment.commitAttachments without the preview strip. */
    private fun commitPendingAttachments() {
        pendingAttachments?.let {
            pendingAttachments = null
            commitAttachments(it)
        }
    }

    private fun commitAttachments(attachments: List<Attachment>, target: Conversation? = conversation) {
        if (attachments.isEmpty()) return
        val c = target
        val service = xmppConnectionService
        if (c == null || service == null) {
            pendingAttachments = attachments
            return
        }
        if (attachments.any { it.type != Attachment.Type.LOCATION }) {
            if (!hasPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))) {
                withPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    commitAttachments(attachments, c)
                }
                return
            }
        }
        if (trustKeysIfNeeded(c, AfterTrust.Attachments(attachments))) return
        val callback =
            PresenceSelector.OnPresenceSelected {
                for (attachment in attachments) {
                    when (attachment.type) {
                        Attachment.Type.LOCATION ->
                            service.attachLocationToConversation(c, attachment.uri)
                        Attachment.Type.IMAGE ->
                            attach(R.string.preparing_image, attachment.uri) {
                                service.attachImageToConversation(c, attachment.uri, attachment.mime)
                            }
                        else ->
                            attach(R.string.preparing_file, attachment.uri) {
                                service.attachFileToConversation(c, attachment.uri, attachment.mime)
                            }
                    }
                }
            }
        if (
            c.mode == Conversation.MODE_MULTI ||
                Attachment.canBeSendInBand(attachments) ||
                (c.account.httpUploadAvailable() &&
                    FileBackend.allFilesUnderSize(
                        activity,
                        attachments,
                        getMaxHttpUploadSize(c),
                    ))
        ) {
            callback.onPresenceSelected()
        } else {
            (activity as? XmppActivity)?.selectPresence(c, callback)
        }
    }

    private fun attach(
        preparing: Int,
        uri: Uri,
        start: () -> ListenableFuture<Void?>,
    ) {
        val activity = activity as? XmppActivity ?: return
        val toast = Toast.makeText(activity, preparing, Toast.LENGTH_LONG)
        toast.show()
        activity.delegateUriPermissionsToService(uri)
        Futures.addCallback(
            start(),
            object : FutureCallback<Void?> {
                override fun onSuccess(result: Void?) {
                    toast.cancel()
                }

                override fun onFailure(t: Throwable) {
                    Log.d(Config.LOGTAG, "could not attach file", t)
                    toast.cancel()
                    val context = context ?: return
                    if (t is FileBackend.FileCopyException) {
                        Toast.makeText(context, t.resId, Toast.LENGTH_LONG).show()
                    } else if (!t.message.isNullOrEmpty()) {
                        Toast.makeText(context, t.message, Toast.LENGTH_LONG).show()
                    }
                }
            },
            ContextCompat.getMainExecutor(activity),
        )
    }

    private fun getMaxHttpUploadSize(c: Conversation): Long? {
        val connection = c.account.xmppConnection
        val service = connection.getManager(HttpUploadManager::class.java).service ?: return -1L
        return service.maxFileSize
    }

    private fun hasPermissions(permissions: List<String>): Boolean =
        missingPermissions(permissions).isEmpty()

    /** ConversationFragment.hasPermissions: storage was needed only for the removed shared-storage setting. */
    private fun missingPermissions(permissions: List<String>): List<String> =
        permissions.filter { permission ->
            permission != Manifest.permission.WRITE_EXTERNAL_STORAGE &&
                requireContext().checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }

    private fun withPermissions(permissions: List<String>, action: () -> Unit) {
        val missing = missingPermissions(permissions)
        if (missing.isEmpty()) {
            action()
        } else {
            afterPermissions = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // --- extras -----------------------------------------------------------------------------

    /** TEXT, AS_QUOTE, STREAM and DOWNLOAD_UUID of ConversationFragment.processExtras. */
    private fun processExtras(c: Conversation, extras: Bundle) {
        val downloadUuid = extras.getString(ConversationsActivity.EXTRA_DOWNLOAD_UUID)
        val text = extras.getString(Intent.EXTRA_TEXT)
        val asQuote = extras.getBoolean(ConversationsActivity.EXTRA_AS_QUOTE)
        val doNotAppend = extras.getBoolean(ConversationsActivity.EXTRA_DO_NOT_APPEND, false)
        val type = extras.getString(ConversationsActivity.EXTRA_TYPE)
        extras.getString(EXTRA_MESSAGE_UUID)?.let { showMessage(c, it) }
        val uris = extractUris(extras)
        if (uris.isNotEmpty()) {
            val attachments =
                if (uris.size == 1 && "geo" == uris[0].scheme) {
                    Attachment.of(requireContext(), uris[0], Attachment.Type.LOCATION)
                } else {
                    Attachment.of(requireContext(), cleanUris(uris), type)
                }
            // a forward of several messages (forwardMessages, marked by AS_QUOTE) lands in the composer: the texts as
            // a quote, the files as previews, sent together; other shares keep the send dialog
            if (asQuote) {
                if (requireClassicForPgp()) return
                text?.let(::quote)
                addPreviews(attachments)
            } else {
                confirmAttachments(attachments)
            }
            return
        }
        if (text != null && Patterns.URI_GEO.matcher(text).matches()) {
            confirmAttachments(
                Attachment.of(requireContext(), Uri.parse(text), Attachment.Type.LOCATION)
            )
            return
        } else if (text != null && asQuote) {
            quote(text)
        } else if (text != null) {
            appendText(text, doNotAppend)
        }
        val message = downloadUuid?.let { c.findMessageWithFileAndUuid(it) }
        if (message != null) startDownloadable(message)
    }

    private fun quote(text: String) {
        val current = viewModel.input.text
        val prefix = if (current.isEmpty() || current.endsWith("\n")) current else "$current\n"
        viewModel.setInput(prefix + quoteBlock(text) + "\n")
    }

    private fun appendText(text: String, doNotAppend: Boolean) {
        val previous = viewModel.input.text
        if (doNotAppend && previous.isNotEmpty()) {
            Toast.makeText(activity, R.string.already_drafting_message, Toast.LENGTH_LONG).show()
            return
        }
        val addition =
            when {
                UIHelper.isLastLineQuote(previous) -> "\n" + text
                previous.isNotEmpty() && !previous.last().isWhitespace() -> " $text"
                else -> text
            }
        viewModel.setInput(previous + addition)
    }

    @Suppress("DEPRECATION")
    private fun extractUris(extras: Bundle): List<Uri> {
        extras.getParcelableArrayList<Uri>(Intent.EXTRA_STREAM)?.let { return it.filterNotNull() }
        return listOfNotNull(extras.getParcelable(Intent.EXTRA_STREAM))
    }

    /** ConversationFragment.cleanUris: never attach the app's own private files. */
    private fun cleanUris(uris: List<Uri>): List<Uri> =
        uris.filter { uri ->
            val dangerous = FileBackend.dangerousFile(uri)
            if (dangerous) {
                Toast.makeText(
                        requireActivity(),
                        R.string.security_violation_not_attaching_file,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
            !dangerous
        }

    /** Shared content has no preview strip here, so ask before sending it. */
    private fun confirmAttachments(attachments: List<Attachment>) {
        if (attachments.isEmpty()) return
        if (requireClassicForPgp()) return
        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(conversation?.name)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.tatrak_chat_send_files,
                    attachments.size,
                    attachments.size,
                )
            )
            .setPositiveButton(R.string.tatrak_chat_send) { _, _ -> commitAttachments(attachments) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- message actions --------------------------------------------------------------------

    private fun message(uuid: String): Message? = conversation?.findMessageWithUuid(uuid)

    override fun reply(uuid: String) {
        val c = conversation ?: return
        val message = message(uuid) ?: return
        val quote = replyQuote(requireContext(), message)
        if (quote.isEmpty()) return
        if (c.correctingMessage != null) cancelEdit()
        val name = UIHelper.getMessageDisplayName(message)
        viewModel.reply = ReplyPreview(uuid, name, quote, UIHelper.getColorForName(name))
    }

    override fun cancelReply() {
        viewModel.reply = null
    }

    /** ConversationFragment.correctMessage. */
    override fun edit(uuid: String) {
        val c = conversation ?: return
        val message = message(uuid) ?: return
        viewModel.reply = null
        c.setCorrectingMessage(message)
        c.setDraftMessage(viewModel.input.text)
        viewModel.setInput(message.bodyWithoutReplyFallback)
        refresh()
    }

    /** SendButtonAction.CANCEL of ConversationFragment. */
    override fun cancelEdit() {
        val c = conversation ?: return
        if (c.setCorrectingMessage(null)) {
            viewModel.setInput(c.draftMessage.orEmpty())
            c.setDraftMessage(null)
        }
        refresh()
    }

    override fun copy(uuid: String) {
        val message = message(uuid) ?: return
        ShareUtil.copyToClipboard(requireXmppActivity(), message)
    }

    override fun share(uuid: String) {
        val message = message(uuid) ?: return
        ShareUtil.share(requireXmppActivity(), message)
    }

    override fun forward(uuid: String) {
        val message = message(uuid) ?: return
        ShareUtil.forward(requireXmppActivity(), message)
    }

    /** ShareUtil.copyToClipboard with the selected messages' texts in Telegram's format. */
    override fun copyMessages(uuids: List<String>) {
        val c = conversation ?: return
        val messages = uuids.mapNotNull(::message)
        if (messages.isEmpty()) return
        val activity = requireXmppActivity()
        if (activity.copyTextToClipboard(selectionText(c, messages), R.string.message) &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(activity, R.string.message_copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectionText(c: Conversation, messages: List<Message>): String =
        selectionCopyText(
            messages.map {
                val sender = if (it.status == Message.STATUS_RECEIVED) it.counterpart?.toString() else null
                SelectedText(sender, UIHelper.getMessageDisplayName(it), it.bodyWithoutReplyFallback)
            },
            withNames = !(c.mode == Conversation.MODE_SINGLE && c.contact.isSelf),
        )

    /**
     * ShareUtil.forward (share with inApp) for several messages at once: the files as one ACTION_SEND_MULTIPLE, the
     * texts as one quote in Telegram's copy format. A single message goes the menu's way.
     */
    override fun forwardMessages(uuids: List<String>) {
        val c = conversation ?: return
        val messages = uuids.mapNotNull(::message)
        val activity = requireXmppActivity()
        messages.singleOrNull()?.let { return ShareUtil.forward(activity, it) }
        if (messages.isEmpty()) return
        val files = messages.filter { it.isFileOrImage && !it.isGeoUri }
        val uris = ArrayList<Uri>()
        for (message in files) {
            val file = activity.xmppConnectionService.fileBackend.getFile(message)
            // as ShareUtil.share: getUriForFile throws for a file outside file_paths.xml
            try {
                uris += FileBackend.getUriForFile(activity, file).buildUpon().appendQueryParameter("uuid", message.uuid).build()
            } catch (e: SecurityException) {
                Toast.makeText(activity, activity.getString(R.string.no_permission_to_access_x, file.absolutePath), Toast.LENGTH_SHORT).show()
                return
            }
        }
        val texts = messages.filterNot { it in files }
        // AS_QUOTE also marks a files-only batch as ours (processExtras puts it into the composer)
        val intent = Intent(activity, ShareWithActivity::class.java).putExtra(ConversationsActivity.EXTRA_AS_QUOTE, true)
        if (texts.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, selectionText(c, texts))
        if (uris.isEmpty()) {
            intent.setAction(Intent.ACTION_SEND).setType("text/plain")
        } else {
            intent.setAction(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT).show()
        }
    }

    override fun copyLink(uuid: String) {
        val message = message(uuid) ?: return
        ShareUtil.copyLinkToClipboard(requireXmppActivity(), message)
    }

    override fun copyUrl(uuid: String) {
        val message = message(uuid) ?: return
        ShareUtil.copyUrlToClipboard(requireXmppActivity(), message)
    }

    /** ConversationFragment.viewEditHistory. */
    override fun viewEditHistory(uuid: String) {
        val message = message(uuid) ?: return
        startActivity(
            Intent(requireContext(), EditHistoryActivity::class.java)
                .putExtra(EditHistoryActivity.EXTRA_CONVERSATION_UUID, message.conversationUuid)
                .putExtra(EditHistoryActivity.EXTRA_MESSAGE_UUID, message.uuid)
        )
    }

    /**
     * ConversationFragment.onContactPictureLongClicked, "Contact details" / "Account details" (group:
     * MucDetailsContextMenuHelper's, by the sender's real JID): the key screens highlight the
     * message's OMEMO key. Unlike the group helper, our own group message also passes the key to the
     * account screen, so it is highlighted there too. Only an OMEMO fingerprint is tappable.
     */
    override fun openMessageKey(uuid: String) {
        val message = message(uuid) ?: return
        val fingerprint = message.fingerprint ?: return
        val account = message.conversation.account
        val contact =
            when {
                message.status > Message.STATUS_RECEIVED -> null
                message.conversation is Conversation && message.conversation.mode == Conversational.MODE_MULTI -> {
                    val realJid = (message.conversation as Conversation).mucOptions.getUserOrStub(message).realJid
                    // an anonymous sender: the classic menu opens nothing either
                    realJid?.let { account.roster.getContact(it) } ?: return
                }
                else -> message.contact
            }
        if (contact == null || contact.isSelf) {
            requireXmppActivity().switchToAccount(account, fingerprint)
        } else {
            requireXmppActivity().switchToContactDetails(contact, fingerprint)
        }
    }

    /** ConversationFragment.showErrorMessage. */
    override fun showError(uuid: String) {
        val message = message(uuid) ?: return
        val errorMessage = message.errorMessage
        val parts = errorMessage?.split('\u001f').orEmpty()
        val displayError = if (parts.size == 2) parts[1] else errorMessage
        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.error_message)
            .setMessage(displayError)
            .setNegativeButton(R.string.copy_to_clipboard) { _, _ ->
                if (
                    requireXmppActivity().copyTextToClipboard(displayError, R.string.error_message) &&
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                ) {
                    Toast.makeText(requireContext(), R.string.error_message_copied_to_clipboard, Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    /** ConversationFragment.openWith: bypasses the in-app viewer. */
    override fun openWith(uuid: String) {
        val message = message(uuid) ?: return
        if (message.isGeoUri) {
            GeoHelper.view(activity, message)
            return
        }
        val file = xmppConnectionService?.fileBackend?.getFile(message) ?: return
        if (!file.exists()) {
            Toast.makeText(requireContext(), R.string.file_deleted, Toast.LENGTH_SHORT).show()
            return
        }
        ViewUtil.viewExternally(
            requireContext(),
            file,
            message.uuid,
            ViewUtil.nullToWildcard(eu.siacs.conversations.utils.MimeUtils.getMimeType(file)),
        )
    }

    /** ConversationFragment.deleteFileDialog: asks, and warns when other chats use the file. */
    override fun deleteFile(uuid: String) {
        val message = message(uuid) ?: return
        val location = message.relativeFilePath ?: return
        val future = DatabaseBackend.getInstance(requireContext()).getMessagesWithFileFuture(location.file())
        Futures.addCallback(
            future,
            object : FutureCallback<List<String>> {
                override fun onSuccess(result: List<String>) = confirmDeleteFile(message, result.count { it != message.uuid })

                override fun onFailure(t: Throwable) {
                    Log.e(Config.LOGTAG, "could not retrieve messages count", t)
                    confirmDeleteFile(message, 0)
                }
            },
            ContextCompat.getMainExecutor(requireContext()),
        )
    }

    /** [others]: messages besides this one that show the file; deleteFile removes it from them too. */
    private fun confirmDeleteFile(message: Message, others: Int) {
        // the query may finish after the chat was left
        val host = activity?.takeIf { isAdded && !it.isFinishing } ?: return
        confirmAction(host, getString(R.string.delete_file_dialog), deleteFilesText(host, 1, others), R.string.tatrak_delete) {
            val service = xmppConnectionService ?: return@confirmAction
            if (service.fileBackend.deleteFile(message)) {
                message.isDeleted = true
                service.evictPreview(message.uuid)
                service.updateMessage(message, false)
                (activity as? ConversationsActivity)?.onConversationsListItemUpdated()
                refresh()
            }
        }
    }

    override fun call(video: Boolean) {
        val c = conversation ?: return
        calls?.call(c, video)
    }

    /** ConversationFragment.returnToOngoingCall. */
    override fun returnToCall() {
        val c = conversation ?: return
        val account = c.account
        val ongoing =
            account.xmppConnection?.getManager(JingleManager::class.java)?.getOngoingRtpConnection(c.contact)
                ?: return
        if (!ongoing.isPresent) return
        val id = ongoing.get()
        val intent =
            Intent(requireContext(), RtpSessionActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(RtpSessionActivity.EXTRA_ACCOUNT, account.jid.asBareJid().toString())
                .putExtra(RtpSessionActivity.EXTRA_WITH, id.with.toString())
        if (id is AbstractJingleConnection) {
            intent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, id.sessionId)
            startActivity(intent)
        } else if (id is JingleManager.RtpSessionProposal) {
            intent.putExtra(
                RtpSessionActivity.EXTRA_LAST_ACTION,
                if (Media.audioOnly(id.media)) RtpSessionActivity.ACTION_MAKE_VOICE_CALL
                else RtpSessionActivity.ACTION_MAKE_VIDEO_CALL,
            )
            intent.putExtra(RtpSessionActivity.EXTRA_PROPOSED_SESSION_ID, id.sessionId)
            startActivity(intent)
        }
    }

    /**
     * The chat list's archive (local, with Undo), not ConversationFragment's action_archive, which
     * closes the chat and leaves a group: the list archives it and offers Undo; back to it, as Telegram.
     */
    override fun archive() {
        val c = conversation ?: return
        parentFragmentManager.setFragmentResult(ChatListFragment.ARCHIVE_FROM_CHAT, bundleOf(ChatListFragment.ARCHIVE_FROM_CHAT to c.uuid))
        (activity as? ConversationsActivity)?.onConversationArchived(c)
    }

    /** ConversationFragment.clearHistoryDialog, including "end the conversation too". */
    override fun clearHistory() {
        val c = conversation ?: return
        confirmClearHistory(requireActivity(), c, { xmppConnectionService }) { closed ->
            if (closed) {
                (activity as? ConversationsActivity)?.onConversationArchived(c)
            } else {
                (activity as? ConversationsActivity)?.onConversationsListItemUpdated()
                refresh()
            }
        }
    }

    override fun toggleBlock() {
        val c = conversation ?: return
        BlockContactDialog.show(requireXmppActivity(), c)
    }

    /** ConversationFragment.onArrowUpCtrlPressed. */
    override fun editLast(): Boolean {
        val message = conversation?.lastEditableMessage
        if (message == null) {
            Toast.makeText(activity, R.string.could_not_correct_message, Toast.LENGTH_LONG).show()
            return false
        }
        edit(message.uuid)
        return true
    }

    /** The MUC branch of ConversationFragment.onContactPictureClicked for a received message. */
    override fun mentionSender(uuid: String) {
        val message = message(uuid) ?: return
        val c = message.conversation as? Conversation ?: return
        if (message.status > Message.STATUS_RECEIVED || c.mode != Conversation.MODE_MULTI) return
        val mucOptions = c.mucOptions
        if (!mucOptions.participating()) {
            Toast.makeText(requireActivity(), R.string.you_are_not_participating, Toast.LENGTH_SHORT).show()
            return
        }
        val user = mucOptions.getUserOrStub(message)
        if (user.resource() != null) {
            if (user is MucOptions.Stub) {
                Toast.makeText(
                        requireActivity(),
                        requireActivity().getString(R.string.user_has_left_conference, user.displayName),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
            highlightInConference(c, user.resource())
        } else {
            val counterpart = message.counterpart
            if (counterpart != null && counterpart.isFullJid) highlightInConference(c, counterpart.resource)
        }
    }

    /** ConversationFragment.highlightInConference. */
    private fun highlightInConference(c: Conversation, nick: String) {
        onInputChange(viewModel.input.highlightInConference(nick) { NickValidityChecker.check(c, it) })
    }

    /** The source list of ConversationFragment.onTabPressed, without our own nick. */
    override suspend fun mentionCandidates(): List<MentionCandidate> {
        val c = conversation ?: return emptyList()
        if (c.mode != Conversation.MODE_MULTI) return emptyList()
        return withContext(Dispatchers.Default) {
            val mucOptions = c.mucOptions
            val own = mucOptions.actualNick
            mucOptions.users
                .mapNotNull { it.resource() }
                .filter { it != own }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .map { MentionCandidate(it, UIHelper.getColorForName(it)) }
        }
    }

    override suspend fun mentionAvatar(nick: String, sizePx: Int): Bitmap? {
        val c = conversation ?: return null
        val service = xmppConnectionService ?: return null
        return withContext(mediaDecodeDispatcher) {
            currentCoroutineContext().ensureActive()
            val user = c.mucOptions.users.firstOrNull { it.resource() == nick } ?: return@withContext null
            service.avatarService.get(user, sizePx, false)
        }
    }

    /** ConversationFragment.resendMessage(message, false). */
    override fun retry(uuid: String) {
        val message = message(uuid) ?: return
        val service = xmppConnectionService ?: return
        if (message.isFileOrImage) {
            val c = message.conversation as? Conversation ?: return
            val file = service.fileBackend.getFile(message)
            if ((file.exists() && file.canRead()) || message.hasFileOnRemoteHost()) {
                val connection = c.account.xmppConnection
                if (
                    !message.hasFileOnRemoteHost() &&
                        connection != null &&
                        c.mode == Conversational.MODE_SINGLE &&
                        !connection
                            .getManager(HttpUploadManager::class.java)
                            .isAvailableForSize(message.fileParams.size)
                ) {
                    requireXmppActivity().selectPresence(c) {
                        message.counterpart = c.nextCounterpart
                        service.resendFailedMessages(message, false)
                    }
                    return
                }
            } else if (!Compatibility.hasStoragePermission(activity)) {
                Toast.makeText(
                        requireContext(),
                        getString(R.string.no_storage_permission, getString(R.string.app_name)),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                return
            } else {
                Toast.makeText(requireContext(), R.string.file_deleted, Toast.LENGTH_SHORT).show()
                message.isDeleted = true
                service.updateMessage(message, false)
                (activity as? ConversationsActivity)?.onConversationsListItemUpdated()
                refresh()
                return
            }
        }
        service.resendFailedMessages(message, false)
    }

    override fun toggleReaction(uuid: String, emoji: String) {
        val message = message(uuid) ?: return
        val normalized = Emoticons.normalizeToVS16(emoji)
        val ours = LinkedHashSet(message.aggregatedReactions.ourReactions)
        if (!ours.remove(normalized)) {
            ours.add(normalized)
            FrequentReactions.record(requireContext(), normalized)
        }
        requireXmppActivity().sendReactions(message, ours)
    }

    /** AddReactionActivity.addReaction: an emoji already among ours re-sends the same set. */
    override fun addReaction(uuid: String, emoji: String) {
        val message = message(uuid)
        if (message == null) {
            Toast.makeText(requireContext(), R.string.could_not_add_reaction, Toast.LENGTH_LONG).show()
            return
        }
        val ours = message.aggregatedReactions.ourReactions
        if (!ours.contains(emoji)) FrequentReactions.record(requireContext(), emoji)
        requireXmppActivity().sendReactions(message, if (ours.contains(emoji)) ours else ours + emoji)
    }

    /** MessageAdapter.openDownloadable. */
    override fun openMedia(uuid: String) {
        val message = message(uuid) ?: return
        val service = xmppConnectionService ?: return
        withPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            val file = service.fileBackend.getFile(message)
            ViewUtil.view(requireContext(), file, message)
        }
    }

    override fun download(uuid: String) {
        val message = message(uuid) ?: return
        startDownloadable(message)
    }

    /** The first "Save" warns that the copy is not encrypted. */
    override fun saveFile(uuid: String) = MediaPrompts.confirmSave(requireContext()) { saveFileNow(uuid) }

    /** ConversationFragment.saveFile. */
    private fun saveFileNow(uuid: String) {
        val message = message(uuid) ?: return
        val service = xmppConnectionService ?: return
        val storageLocation = message.relativeFilePath
        if (storageLocation == null || storageLocation.sharedStorage()) return
        Futures.addCallback(
            service.fileBackend.saveInternalToExternal(storageLocation),
            object : FutureCallback<File> {
                override fun onSuccess(result: File) {
                    val context = context ?: return
                    Toast.makeText(context, SaveResult.message(context, listOf(result)), Toast.LENGTH_LONG).show()
                }

                override fun onFailure(t: Throwable) {
                    Log.e(Config.LOGTAG, "could not save attachment", t)
                    val context = context ?: return
                    Toast.makeText(context, R.string.could_not_save_files, Toast.LENGTH_LONG).show()
                }
            },
            ContextCompat.getMainExecutor(requireContext()),
        )
    }

    /** Sent as a plain file: an image attachment would be re-encoded as JPEG. */
    override fun sendSticker(file: File) {
        if (requireClassicForPgp()) return
        commitAttachments(Attachment.of(Uri.fromFile(file), "image/webp"))
    }

    override fun importStickers() {
        stickerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun addSticker(uuid: String) {
        val message = message(uuid) ?: return
        val file = xmppConnectionService?.fileBackend?.getFile(message) ?: return
        if (!file.exists()) return
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            // the attachment is encrypted: the store reads it through MediaProvider
            if (StickerStore.import(context, listOf(FileBackend.getUriForFile(context, file))) > 0) {
                Toast.makeText(context, R.string.tatrak_sticker_added, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Uploads the own set and its pack JSON over HTTP upload; the link goes into the composer. */
    override fun shareStickers() {
        val c = conversation ?: return
        val manager = c.account.xmppConnection?.getManager(HttpUploadManager::class.java) ?: return
        val files = StickerStore.stickers.value.firstOrNull()?.files.orEmpty().take(StickerStore.MAX_PACK_SIZE)
        if (files.isEmpty()) return
        val context = requireContext().applicationContext
        val name = getString(R.string.tatrak_stickers_my_pack, c.account.displayName ?: c.account.jid.local)
        Toast.makeText(context, R.string.tatrak_stickers_sharing, Toast.LENGTH_SHORT).show()
        val service = xmppConnectionService
        // the activity's scope: leaving the chat destroys this fragment, the link still goes to the draft
        requireActivity().lifecycleScope.launch {
            val link =
                withContext(Dispatchers.IO) {
                    try {
                        val urls = files.map { manager.uploadSticker(it, "image/webp").get().toString() }
                        val json = File(context.cacheDir, StickerStore.PACK_FILE)
                        json.writeText(StickerStore.packJson(name, urls))
                        manager.uploadSticker(json, "application/json").get().toString()
                    } catch (e: Exception) {
                        Log.d(Config.LOGTAG, "could not share stickers", e)
                        null
                    }
                }
            if (link == null) {
                Toast.makeText(context, R.string.tatrak_stickers_share_failed, Toast.LENGTH_SHORT).show()
            } else {
                if (isAdded && conversation === c && view != null) {
                    onInputChange(viewModel.input.insertLinkAtCursor(link))
                } else if (storeNextMessage(c, appendToDraft(c.draft?.message().orEmpty(), link), service)) {
                    Toast.makeText(context, context.getString(R.string.tatrak_stickers_link_saved, c.name), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.tatrak_stickers_share_failed, Toast.LENGTH_SHORT).show()
                }
                // without a stickers.<domain> component the server's cleanup will break the link
                if (!manager.hasStickerService()) {
                    Toast.makeText(context, R.string.tatrak_stickers_link_temporary, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** A shared set (ours or a Cheogram / monocles pack): asks, then downloads it. */
    private fun importStickerPack(url: HttpUrl) {
        val account = conversation?.account ?: return
        val service = xmppConnectionService ?: return
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val pack =
                withContext(Dispatchers.IO) {
                    download(service, account, url, 256 * 1024, null)?.let {
                        StickerStore.parsePack(String(it))
                    }
                }
            if (pack == null) {
                Toast.makeText(context, R.string.tatrak_stickers_pack_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (name, urls) = pack
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.tatrak_stickers_pack_title, name.ifEmpty { url.host }, urls.size))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.add) { _, _ ->
                    lifecycleScope.launch {
                        val added =
                            withContext(Dispatchers.IO) {
                                val images =
                                    urls.mapNotNull { it.toHttpUrlOrNull() }.mapNotNull {
                                        download(service, account, it, StickerStore.MAX_STICKER_BYTES, "image/")
                                    }
                                StickerStore.addPack(context, name.ifEmpty { url.host }, images)
                            }
                        Toast.makeText(
                                context,
                                if (added > 0) R.string.tatrak_stickers_pack_added else R.string.tatrak_stickers_pack_failed,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                }
                .show()
        }
    }

    /** At most [maxBytes] from [url] with the account's HTTP client; null on any failure. */
    private fun download(
        service: XmppConnectionService,
        account: Account,
        url: HttpUrl,
        maxBytes: Int,
        mimePrefix: String?,
    ): ByteArray? =
        try {
            service.httpConnectionManager.buildHttpClient(url, account, 30, false)
                .newCall(Request.Builder().url(url).build())
                .execute()
                .use { response ->
                    val body = response.body
                    val type = body.contentType()?.toString().orEmpty()
                    if (!response.isSuccessful || (mimePrefix != null && !type.startsWith(mimePrefix))) {
                        return@use null
                    }
                    if (body.contentLength() > maxBytes) return@use null
                    val bytes = body.source().apply { request(maxBytes + 1L) }.buffer
                    if (bytes.size > maxBytes) null else bytes.readByteArray()
                }
        } catch (e: IOException) {
            Log.d(Config.LOGTAG, "could not download $url", e)
            null
        }

    override fun setWallpaper(spec: String?) {
        val c = conversation ?: return
        val service = xmppConnectionService ?: return
        c.tatrakWallpaper = spec
        service.updateConversation(c)
        refresh()
        val context = requireContext().applicationContext
        lifecycleScope.launch { Wallpapers.deleteUnused(context, service) }
    }

    override fun pickWallpaperPhoto() {
        wallpaperPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun delete(uuid: String) {
        val message = message(uuid) ?: return
        val builder = MaterialAlertDialogBuilder(requireContext()).setNegativeButton(R.string.cancel, null)
        if (ChatSnapshot.canRetract(message.conversation as Conversation, message)) {
            builder.setItems(arrayOf(getString(R.string.tatrak_delete_for_me), getString(R.string.tatrak_retract))) { _, which ->
                if (which == 0) deleteLocal(message) else retract(message)
            }
        } else {
            builder
                .setMessage(R.string.tatrak_delete_for_me_confirm)
                .setPositiveButton(R.string.delete) { _, _ -> deleteLocal(message) }
        }
        builder.show()
    }

    /** [delete]'s question once for the whole selection; then its per-message actions, oldest first. */
    override fun deleteMessages(uuids: List<String>, onDeleted: () -> Unit) {
        val messages = uuids.mapNotNull(::message)
        if (messages.isEmpty()) return
        val builder = MaterialAlertDialogBuilder(requireContext()).setNegativeButton(R.string.cancel, null)
        if (messages.all { ChatSnapshot.canRetract(it.conversation as Conversation, it) }) {
            builder.setItems(arrayOf(getString(R.string.tatrak_delete_for_me), getString(R.string.tatrak_retract))) { _, which ->
                val kept = ArrayList<Message.StorageLocation>()
                messages.forEach { if (which == 0) deleteLocal(it, kept) else retract(it, kept) }
                warnKeptFiles(kept)
                onDeleted()
            }
        } else {
            builder
                .setMessage(resources.getQuantityString(R.plurals.tatrak_delete_messages_for_me_confirm, messages.size, messages.size))
                .setPositiveButton(R.string.delete) { _, _ ->
                    val kept = ArrayList<Message.StorageLocation>()
                    messages.forEach { deleteLocal(it, kept) }
                    warnKeptFiles(kept)
                    onDeleted()
                }
        }
        builder.show()
    }

    /** XEP-0424 "delete for everyone"; a batch collects files kept in shared storage in [kept] instead of a dialog each. */
    private fun retract(message: Message, kept: MutableList<Message.StorageLocation>? = null) {
        val manager = message.conversation.account.xmppConnection?.getManager(ModerationManager::class.java)
        val location = message.relativeFilePath.takeIf { message.isFileOrImage }
        if (manager?.retract(message) != true) {
            Toast.makeText(activity, R.string.tatrak_retract_failed, Toast.LENGTH_SHORT).show()
        } else if (kept == null) {
            warnKeptFile(location)
        } else if (location != null && location.sharedStorage()) {
            kept += location
        }
    }

    /** A file outside the app's store (an imported backup's) stays with the message gone: the user deletes it. */
    private fun warnKeptFile(location: Message.StorageLocation?) {
        if (location == null || !location.sharedStorage()) return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.tatrak_shared_file_kept, location.file().absolutePath))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** Only this device: nothing is sent, the others keep the message. A batch collects kept files in [kept]. */
    private fun deleteLocal(message: Message, kept: MutableList<Message.StorageLocation>? = null) {
        val service = xmppConnectionService ?: return
        val c = conversation ?: return
        val db = DatabaseBackend.getInstance(requireContext())
        // an upload would still send the file, a download would write it after the row is gone
        message.transferable?.cancel()
        val location = message.relativeFilePath
        val hadFile = message.isFileOrImage // the hidden row below is no file message any more
        // the attachment goes too, unless another message still shows it
        if (hadFile && location != null && !location.sharedStorage() &&
            db.getMessagesWithFile(location.file()).none { it != message.uuid }) {
            service.fileBackend.deleteFile(message)
        }
        service.notificationService.clear(message)
        val unsent = message.status == Message.STATUS_UNSEND || message.status == Message.STATUS_WAITING ||
            message.status == Message.STATUS_SEND_FAILED || message.status == Message.STATUS_OFFERED
        if (unsent) {
            c.remove(message)
            db.deleteMessage(message.uuid)
        } else {
            // the server archive still has it: an erased hidden row lets catch-up skip it as a duplicate
            message.eraseContent()
            message.type = Message.TYPE_HIDDEN
            message.markRead()
            db.updateMessage(message, true)
        }
        service.updateConversationUi()
        (activity as? ConversationsActivity)?.onConversationsListItemUpdated()
        refresh()
        if (!hadFile) return
        if (kept == null) warnKeptFile(location) else if (location != null && location.sharedStorage()) kept += location
    }

    /** One dialog for a batch delete's files that stay in shared storage. */
    private fun warnKeptFiles(kept: List<Message.StorageLocation>) {
        kept.singleOrNull()?.let { return warnKeptFile(it) }
        if (kept.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(
                resources.getQuantityString(
                    R.plurals.tatrak_shared_files_kept, kept.size, kept.size, kept.joinToString("\n") { it.file().absolutePath }
                )
            )
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** ConversationFragment.cancelTransmission. */
    override fun cancelTransfer(uuid: String) {
        val message = message(uuid) ?: return
        val transferable = message.transferable
        if (transferable != null) {
            transferable.cancel()
        } else if (message.status != Message.STATUS_RECEIVED) {
            xmppConnectionService?.markMessage(
                message,
                Message.STATUS_SEND_FAILED,
                Message.ERROR_MESSAGE_CANCELLED,
            )
        }
    }

    /** ConversationFragment.startDownloadable. */
    private fun startDownloadable(message: Message) {
        withPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            val service = xmppConnectionService ?: return@withPermissions
            val transferable = message.transferable
            if (transferable != null) {
                if (transferable is TransferablePlaceholder && message.hasFileOnRemoteHost()) {
                    service.httpConnectionManager.createNewDownloadConnection(message, true)
                    return@withPermissions
                }
                if (!transferable.start()) {
                    Toast.makeText(activity, R.string.not_connected_try_again, Toast.LENGTH_SHORT)
                        .show()
                }
            } else if (
                message.treatAsDownloadable() ||
                    message.hasFileOnRemoteHost() ||
                    MessageUtils.unInitiatedButKnownSize(message)
            ) {
                service.httpConnectionManager.createNewDownloadConnection(message, true)
            }
        }
    }

    override fun openLocation(uuid: String) {
        val message = message(uuid) ?: return
        val intent =
            try {
                GeoHelper.showLocationIntent(requireContext(), message)
            } catch (e: IllegalArgumentException) {
                return
            }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                    requireContext(),
                    R.string.no_application_found_to_display_location,
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
    }

    override fun toggleVoice(uuid: String) {
        val message = message(uuid) ?: return
        val service = xmppConnectionService ?: return
        val file = service.fileBackend.getFile(message)
        if (file.exists()) {
            voicePlayer.toggle(uuid, file)
        } else {
            openMedia(uuid)
        }
    }

    override fun seekVoice(uuid: String, fraction: Float) = voicePlayer.seek(uuid, fraction)

    /** Routes taps through [FixedURLSpan] so xmpp:, geo: and web links behave as before. */
    override fun openLink(url: String) {
        val http = url.toHttpUrlOrNull()
        if (http != null && isStickerPack(http)) {
            importStickerPack(http)
            return
        }
        val view = view ?: return
        val text = SpannableString(url)
        text.setSpan(URLSpan(url), 0, url.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        FixedURLSpan.fix(text)
        text.getSpans(0, text.length, URLSpan::class.java).firstOrNull()?.onClick(view)
    }

    override suspend fun loadThumbnail(uuid: String, sizePx: Int): Bitmap? {
        val service = xmppConnectionService ?: return null
        val c = conversation ?: return null
        return loadMessageThumbnail(service, c, uuid, sizePx)
    }

    override fun cachedThumbnail(uuid: String): Bitmap? = xmppConnectionService?.bitmapCache?.get(uuid)

    /** The classic MediaPreviewAdapter's picture: FileBackend.getPreviewForUri (images, video, PDF). */
    override suspend fun previewThumbnail(key: String, sizePx: Int): Bitmap? {
        val attachment = viewModel.previews.firstOrNull { it.uuid.toString() == key } ?: return null
        val service = xmppConnectionService ?: return null
        return withContext(mediaDecodeDispatcher) {
            currentCoroutineContext().ensureActive()
            runCatching { service.fileBackend.getPreviewForUri(attachment, sizePx, false) }.getOrNull()
        }
    }

    override fun removePreview(key: String) {
        val (removed, kept) = viewModel.previews.partition { it.uuid.toString() == key }
        viewModel.previews = kept
        TemporaryMedia.deleteDrafts(viewModel.cameraDrafts, removed)
    }

    /** Picked files wait in the strip above the keyboard until Send, like the classic screen. */
    private fun addPreviews(attachments: List<Attachment>) {
        if (attachments.isNotEmpty()) viewModel.previews = viewModel.previews + attachments
    }

    // --- list -------------------------------------------------------------------------------

    override fun markRead(uuid: String) {
        val c = conversation ?: return
        // right after reInit the previous chat can still be on screen; an unknown uuid would make
        // Conversation.markRead mark the whole new chat as read
        if (viewModel.state.value.conversationUuid != c.uuid) return
        lastVisibleUuid = uuid
        // not yet resumed: still invisible while the slide waits for listReady; onResume sends it
        if (!isResumed) return
        (activity as? ConversationsActivity)?.onConversationRead(c, uuid)
    }

    override fun mentionsSeen(uuids: Collection<String>) = viewModel.mentionsSeen(uuids)

    override fun loadMore() {
        val c = conversation ?: return
        val service = xmppConnectionService ?: return
        if (!c.messagesLoaded.compareAndSet(true, false)) return
        val messages = ArrayList<Message>()
        c.populateWithMessages(messages)
        val oldest = messages.firstOrNull { it.type != Message.TYPE_STATUS }
        if (oldest == null) {
            c.messagesLoaded.set(true)
            return
        }
        service.loadMoreMessages(
            c,
            oldest.timeSent,
            object : XmppConnectionService.OnMoreMessagesLoaded {
                override fun onMoreMessagesLoaded(count: Int, conversation: Conversation) {
                    conversation.messagesLoaded.set(true)
                    activity?.runOnUiThread { refresh() }
                }

                // "Fetching history", "No more history on server": a toast per scroll up stacked; now an
                // optional, softly faded notice in the chat (off by default)
                override fun informUser(r: Int) {
                    val activity = activity ?: return
                    activity.runOnUiThread {
                        if (AppSettings(activity).isHistoryNotices) viewModel.notice = r
                    }
                }
            },
        )
    }

    /**
     * Scrolls to [uuid] (a search result). Older history is loaded page by page, like scrolling
     * up would, until the message is there; past [JUMP_MAX_PAGES] the chat just stays open.
     */
    private fun showMessage(c: Conversation, uuid: String) {
        jumpJob?.cancel()
        viewModel.focus = null
        jumpJob =
            lifecycleScope.launch {
                var pages = 0
                while (conversation === c) {
                    if (c.findMessageWithUuid(uuid) != null) {
                        viewModel.focus = MessageFocus(uuid)
                        refresh()
                        return@launch
                    }
                    if (pages++ >= JUMP_MAX_PAGES) break
                    val loaded = loadOlderPage(c) ?: break
                    if (loaded == 0) break
                }
                Log.d(Config.LOGTAG, "search result $uuid not found in the history of ${c.uuid}")
                // the pages loaded so far still belong on screen
                if (conversation === c) refresh()
            }
    }

    /** One page older than the oldest loaded message; null if there is nothing to load from. */
    private suspend fun loadOlderPage(c: Conversation): Int? {
        val service = xmppConnectionService ?: return null
        // the list's own loadMore holds this flag while a page is loading; wait for it
        var waited = 0
        while (!c.messagesLoaded.compareAndSet(true, false)) {
            if (waited++ >= 50) return null
            delay(100)
        }
        val messages = ArrayList<Message>()
        c.populateWithMessages(messages)
        val oldest = messages.firstOrNull { it.type != Message.TYPE_STATUS }
        if (oldest == null) {
            c.messagesLoaded.set(true)
            return null
        }
        return withTimeoutOrNull(JUMP_PAGE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                service.loadMoreMessages(
                    c,
                    oldest.timeSent,
                    object : XmppConnectionService.OnMoreMessagesLoaded {
                        override fun onMoreMessagesLoaded(count: Int, conversation: Conversation) {
                            conversation.messagesLoaded.set(true)
                            if (continuation.isActive) continuation.resume(count)
                        }

                        override fun informUser(r: Int) = Unit
                    },
                )
            }
        }.also { if (it == null) c.messagesLoaded.set(true) }
    }

    // --- top bar ----------------------------------------------------------------------------

    override fun back() {
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    /** The profile; the own chat keeps opening the account like ConversationFragment. */
    override fun openDetails() {
        val c = conversation ?: return
        val intent =
            if (c.mode == Conversational.MODE_SINGLE && c.contact.isSelf) {
                Intent(requireContext(), EditAccountActivity::class.java)
                    .putExtra("jid", c.account.jid.asBareJid().toString())
            } else {
                Intent(requireContext(), ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_UUID, c.uuid)
            }
        startActivity(intent)
    }

    override fun openSearch() {
        val c = conversation ?: return
        startActivity(
            Intent(requireContext(), SearchActivity::class.java)
                .putExtra(SearchActivity.EXTRA_CONVERSATION_UUID, c.uuid)
        )
    }

    override fun toggleMute() {
        val c = conversation ?: return
        val service = xmppConnectionService ?: return
        toggleMute(requireActivity(), service, c) {
            (activity as? ConversationsActivity)?.onConversationsListItemUpdated()
            refresh()
        }
    }

    override fun togglePin() {
        val c = conversation ?: return
        val service = xmppConnectionService ?: return
        c.isPinnedOnTop = !c.isPinnedOnTop
        service.updateConversation(c)
        (activity as? ConversationsActivity)?.onConversationsListItemUpdated()
        refresh()
    }

    /** The listeners of ConversationFragment.updateSnackBar, each as there. */
    override fun bannerAction(banner: ChatBanner) {
        val c = conversation ?: return
        val activity = requireXmppActivity()
        val service = xmppConnectionService ?: return
        when (banner) {
            // mEnableAccountListener
            ChatBanner.ACCOUNT_DISABLED, ChatBanner.ACCOUNT_LOGGED_OUT -> {
                val account = c.account
                account.setOption(Account.OPTION_SOFT_DISABLED, false)
                account.setOption(Account.OPTION_DISABLED, false)
                service.updateAccount(account)
            }
            // mUnblockClickListener; asks first like the domain case, whose dialog calls the same sendUnblockRequest
            ChatBanner.BLOCKED -> BlockContactDialog.show(activity, c)
            ChatBanner.ADD_BACK -> SubscriptionRequests.addBack(activity, c.contact)
            ChatBanner.ALLOW_SUBSCRIPTION -> {
                SubscriptionRequests.allow(c.contact)
                refresh()
            }
            // clickToMuc
            ChatBanner.NICK_IN_USE, ChatBanner.INVALID_NICK -> ConferenceDetailsActivity.open(activity, c)
            // joinMuc
            ChatBanner.SERVER_NOT_FOUND_RETRY,
            ChatBanner.SERVER_TIMEOUT_RETRY,
            ChatBanner.RESOURCE_CONSTRAINT,
            ChatBanner.KICKED,
            ChatBanner.TECHNICAL_PROBLEMS,
            ChatBanner.UNKNOWN_ERROR,
            ChatBanner.SHUTDOWN -> service.joinMuc(c)
            // leaveMuc
            ChatBanner.SERVER_NOT_FOUND_LEAVE,
            ChatBanner.SERVER_TIMEOUT_LEAVE,
            ChatBanner.BANNED,
            ChatBanner.MEMBERS_ONLY,
            ChatBanner.DESTROYED ->
                confirmAction(
                    activity,
                    getString(R.string.tatrak_leave_group_title, c.name),
                    getString(
                        if (banner == ChatBanner.DESTROYED) R.string.tatrak_leave_destroyed_explained
                        else R.string.tatrak_leave_unjoined_explained
                    ),
                    R.string.leave,
                ) {
                    xmppConnectionService?.archiveConversation(c)
                }
            // enterPassword
            ChatBanner.PASSWORD_REQUIRED ->
                activity.quickPasswordEdit(c.mucOptions.password ?: "") { value ->
                    activity.xmppConnectionService.providePasswordForMuc(c, value)
                    null
                }
            // acceptJoin
            ChatBanner.NON_ANONYMOUS -> {
                c.setAcceptNonAnonymous(true)
                service.updateConversation(c)
                service.joinMuc(c)
            }
            // clickToDecryptListener; ConversationsActivity.onActivityResult handles REQUEST_DECRYPT_PGP
            ChatBanner.PGP_PENDING -> {
                val pendingIntent = c.account.pgpDecryptionService.pendingIntent
                if (pendingIntent != null) {
                    try {
                        @Suppress("DEPRECATION")
                        requireActivity()
                            .startIntentSenderForResult(
                                pendingIntent.intentSender,
                                ConversationFragment.REQUEST_DECRYPT_PGP,
                                null,
                                0,
                                0,
                                0,
                                Compatibility.pgpStartIntentSenderOptions(),
                            )
                    } catch (e: IntentSender.SendIntentException) {
                        Toast.makeText(activity, R.string.unable_to_connect_to_keychain, Toast.LENGTH_SHORT).show()
                        c.account.pgpDecryptionService.continueDecryption(true)
                    }
                }
                refresh()
            }
            ChatBanner.CONNECTING, ChatBanner.JOINING, ChatBanner.STRANGER -> Unit
        }
    }

    /** The reject item of ConversationFragment.showBlockSubmenu. */
    override fun rejectSubscription() {
        val c = conversation ?: return
        val name = c.contact.displayName
        confirmAction(
            requireActivity(),
            getString(R.string.tatrak_reject_request_title, name),
            getString(R.string.tatrak_reject_request_explained, name),
            R.string.reject_request,
        ) {
            xmppConnectionService?.stopPresenceUpdatesTo(c.contact) ?: return@confirmAction
            refresh()
        }
    }

    /** The block_domain / block_contact items of ConversationFragment.showBlockSubmenu. */
    override fun block(domain: Boolean) {
        val c = conversation ?: return
        val activity = requireXmppActivity()
        if (domain) {
            BlockContactDialog.show(activity, c.account.roster.getContact(c.address.domain))
        } else {
            BlockContactDialog.show(activity, c)
        }
    }

    /** MessageAdapter.loadMoreMessages. */
    override fun loadHistory() {
        val c = conversation ?: return
        val service = xmppConnectionService ?: return
        val connection = c.account.xmppConnection ?: return
        // the row may be stale: a second tap while the first query runs, or a chat archived meanwhile
        // (MessageArchiveManager refuses archived chats with an exception)
        if (c.status == Conversation.STATUS_ARCHIVED || !ChatSnapshot.showLoadMore(c)) {
            refresh()
            return
        }
        c.setLastClearHistory(0, null)
        service.updateConversation(c)
        c.setHasMessagesLeftOnServer(true)
        c.setFirstMamReference(null)
        var timestamp = c.lastMessageTransmitted.timestamp()
        if (timestamp == 0L) {
            timestamp = System.currentTimeMillis()
        }
        c.messagesLoaded.set(true)
        val query =
            connection.getManager(MessageArchiveManager::class.java).query(c, MamReference(0), timestamp, false)
        if (query != null) {
            Toast.makeText(activity, R.string.fetching_history_from_server, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(activity, R.string.not_fetching_history_retention_period, Toast.LENGTH_SHORT).show()
        }
        // the classic list redraws the row away on its next update; the snapshot is rebuilt now
        refresh()
    }

    /** ConversationFragment.handleEncryptionSelection. */
    override fun selectEncryption(itemId: Int) {
        val c = conversation ?: return
        EncryptionChoice.select(requireXmppActivity(), c, itemId)
        refresh()
        requireXmppActivity().refreshUi()
    }

    override fun openClassic() {
        val c = conversation ?: return
        saveDraft(c)
        (activity as? ConversationsActivity)?.openClassicConversation(c)
    }

    companion object {
        private const val STATE_CONVERSATION_UUID = "tatrak_chat_conversation_uuid"
        private const val STATE_TRUST_ATTACHMENTS = "tatrak_chat_trust_attachments"
        private const val STATE_PHOTO_URI = "tatrak_chat_photo_uri"
        private const val STATE_MEDIA_PREVIEWS = "tatrak_chat_media_previews"
        private const val STATE_CAMERA_DRAFTS = "tatrak_chat_camera_drafts"

        /** Opens the chat scrolled to this message (search results); see [showMessage]. */
        const val EXTRA_MESSAGE_UUID = "am.cyberhayq.chat.message_uuid"

        /** 50 messages a page: up to 3000 messages back. */
        private const val JUMP_MAX_PAGES = 60
        private const val JUMP_PAGE_TIMEOUT_MS = 10_000L

        /** EditMessage.insertAsQuote: one more level for existing quotes, "> " for the rest. */
        fun quoteBlock(text: String): String =
            QuoteHelper.replaceAltQuoteCharsInText(text)
                .replace(Regex("(^|\n)(" + QuoteHelper.QUOTE_CHAR + ")"), "$1$2$2")
                .replace(Regex("(^|\n)(?!" + QuoteHelper.QUOTE_CHAR + ")(.*)"), "$1> $2")
    }
}

/**
 * The Telegram slide between list and chat: [enterAnim] comes in from `fraction` of the width,
 * [exitAnim] goes out to it; other transitions keep their resource. Width is only known at run
 * time, which the XML animators can't express.
 */
internal fun slideAnimator(fragment: Fragment, nextAnim: Int, enterAnim: Int, exitAnim: Int, fraction: Float): Animator? {
    val view = fragment.view ?: return null
    val width = view.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
    val (from, to) =
        when (nextAnim) {
            enterAnim -> width * fraction to 0f
            exitAnim -> 0f to width * fraction
            else -> return null
        }
    // Below API 34 SwipeBackLayout has already moved the views with the finger: carry on from
    // there. Read on the first frame, since the animator is made when the gesture starts.
    var start: Float? = null
    return ValueAnimator.ofFloat(0f, 1f).apply {
        // Telegram's chat transition curve, 1 - (1 - x)³; SwipeBackLayout.seekProgress inverts it
        duration = 300
        interpolator = DecelerateInterpolator(1.5f)
        addUpdateListener {
            val s = start ?: (if (view.translationX != 0f) view.translationX else from).also { start = it }
            view.translationX = s + (to - s) * (it.animatedValue as Float)
        }
        addListener(
            object : AnimatorListenerAdapter() {
                // a cancelled swipe-back reverses to a few pixels short of the start
                override fun onAnimationEnd(animation: Animator, isReverse: Boolean) {
                    if (isReverse || (fragment.isAdded && !fragment.isRemoving)) view.translationX = 0f
                }
            }
        )
    }
}

/** Unmutes right away, otherwise asks for how long like ConversationFragment.muteConversationDialog. */
internal fun toggleMute(
    activity: Activity,
    service: XmppConnectionService,
    c: Conversation,
    onChanged: () -> Unit,
) {
    if (c.isMuted) {
        c.mutedTill = null
        service.updateConversation(c)
        onChanged()
        return
    }
    val durations = activity.resources.getIntArray(R.array.mute_options_durations)
    val labels =
        durations
            .map {
                if (it == -1) {
                    activity.getString(R.string.until_further_notice)
                } else {
                    TimeFrameUtils.resolve(activity, 1000L * it)
                }
            }
            .toTypedArray<CharSequence>()
    MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.disable_notifications)
        .setItems(labels) { _, which ->
            c.mutedTill =
                if (durations[which] == -1) {
                    Instant.MAX
                } else {
                    Instant.now().plus(Duration.ofSeconds(durations[which].toLong()))
                }
            service.updateConversation(c)
            onChanged()
        }
        .show()
}

internal fun isStickerPack(url: HttpUrl): Boolean = StickerStore.isPack(url)

