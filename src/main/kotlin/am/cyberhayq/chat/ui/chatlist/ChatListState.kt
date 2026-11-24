package am.cyberhayq.chat.ui.chatlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import am.cyberhayq.chat.ui.chat.toAnnotatedString
import eu.siacs.conversations.utils.IrregularUnicodeDetector
import eu.siacs.conversations.xmpp.Jid
import eu.siacs.conversations.R
import am.cyberhayq.chat.ui.accounts.AccountFilter
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import am.cyberhayq.chat.ui.chat.formatDuration
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.util.Attachment
import eu.siacs.conversations.utils.UIHelper
import eu.siacs.conversations.xmpp.manager.ChatStateManager
import eu.siacs.conversations.xmpp.manager.JingleManager
import im.conversations.android.xmpp.model.stanza.Presence
import im.conversations.android.xmpp.model.state.Composing
import java.util.UUID
import java.io.File

enum class OutgoingStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}

@Immutable
data class ChatListItem(
    val uuid: String,
    val title: String,
    val isGroup: Boolean,
    val preview: String,
    /** "Alice" in groups, "You" for own messages in groups; null for private chats. */
    val previewSender: String?,
    val isDraft: Boolean,
    /** Status messages, calls and the like; rendered in italics. */
    val isServicePreview: Boolean,
    val isTyping: Boolean,
    val timestamp: Long,
    val unreadCount: Int,
    val isMuted: Boolean,
    val isPinned: Boolean,
    val outgoingStatus: OutgoingStatus?,
    val isOnline: Boolean,
    val isArchived: Boolean,
    /** Compared by identity; AvatarService hands out a new bitmap when the avatar changes. */
    val avatar: Bitmap?,
    /** The chat with oneself; drawn with the bookmark instead of the avatar. */
    val isSavedMessages: Boolean = false,
    /** Uuid of the last message when it is a downloaded photo or video that gets a thumbnail. */
    val previewMediaKey: String? = null,
    /** That message's square thumbnail; null until decoded or when decoding failed. */
    val previewThumbnail: Bitmap? = null,
    /** A call with this contact is running (ConversationAdapter's phone icon). */
    val ongoingCall: Boolean = false,
    /** A group that notifies only on mentions (ConversationAdapter's empty bell). */
    val mentionsOnly: Boolean = false,
    /** [title] with look-alike characters marked, when the title is a stranger's address. */
    val styledTitle: AnnotatedString? = null,
)

@Immutable
data class AccountHeader(val name: String, val address: String, val avatar: Bitmap?)

/** An account in the dock's account switcher, with its unread chats (as the folder tabs count them); a disabled one is drawn muted. */
@Immutable
data class AccountChoice(val uuid: String, val name: String, val avatar: Bitmap?, val unread: Int, val disabled: Boolean = false)

@Immutable
data class ChatListState(
    val loaded: Boolean = false,
    /** Chats outside the archive, pinned first. */
    val chats: List<ChatListItem> = emptyList(),
    /** Telegram-style archive; these chats are still active. */
    val archived: List<ChatListItem> = emptyList(),
    val account: AccountHeader? = null,
    /** True while an enabled account is not connected; the title then reads "Connecting…". */
    val connecting: Boolean = false,
    /** Every enabled account, whatever the filter. */
    val accounts: List<AccountChoice> = emptyList(),
    /** The account the app is filtered to ([AccountFilter]); null: all. */
    val selectedAccount: String? = null,
)

private val Whitespace = Regex("\\s+")

/**
 * Avatars and preview thumbnails missing from [this] (not in the cache yet) taken from the previous
 * snapshot, so rows never flash grey or drop their thumbnail for a moment.
 */
fun ChatListState.keepAvatars(previous: ChatListState): ChatListState {
    val old = (previous.chats + previous.archived).associateBy { it.uuid }
    fun ChatListItem.keep(before: ChatListItem?): ChatListItem {
        var item = this
        if (avatar == null && before?.avatar != null) item = item.copy(avatar = before.avatar)
        if (previewMediaKey != null && previewThumbnail == null && before?.previewMediaKey == previewMediaKey) {
            item = item.copy(previewThumbnail = before.previewThumbnail)
        }
        return item
    }
    fun List<ChatListItem>.keep() = map { it.keep(old[it.uuid]) }
    return copy(
        chats = chats.keep(),
        archived = archived.keep(),
        account = account?.let { if (it.avatar == null) it.copy(avatar = previous.account?.avatar) else it },
        accounts = accounts.map { a -> if (a.avatar == null) a.copy(avatar = previous.accounts.firstOrNull { it.uuid == a.uuid }?.avatar) else a },
    )
}

object ChatListSnapshot {

    fun build(
        context: Context,
        service: XmppConnectionService,
        avatarSize: Int,
        avatarsCachedOnly: Boolean,
        /** Avatars and thumbnails not in the cache are loaded for this many chats from the top only. */
        loadAvatarsFor: Int = Int.MAX_VALUE,
        thumbnailSize: Int,
    ): ChatListState {
        val all = ArrayList<Conversation>()
        service.populateWithOrderedConversations(all)
        val only = AccountFilter.selected(service)
        val conversations = all.filter { AccountFilter.shows(only, it.account) }
        var loaded = 0
        val (archived, chats) =
            conversations
                .map {
                    val cachedOnly =
                        avatarsCachedOnly ||
                            (it.tatrakArchivedAt == null && loaded++ >= loadAvatarsFor) ||
                            (it.tatrakArchivedAt != null && loadAvatarsFor != Int.MAX_VALUE)
                    toItem(context, service, it, avatarSize, cachedOnly, thumbnailSize)
                }
                .partition { it.isArchived }
        val account =
            (only ?: service.accounts.firstOrNull())?.let {
                AccountHeader(
                    name = accountName(it),
                    address = it.jid.asBareJid().toString(),
                    avatar = service.avatarService.get(it, avatarSize, avatarsCachedOnly),
                )
            }
        val connecting = service.accounts.any { AccountFilter.shows(only, it) && it.isConnectionEnabled && !it.isOnlineAndConnected }
        return ChatListState(
            loaded = true,
            chats = chats,
            archived = archived,
            account = account,
            connecting = connecting,
            // every account, a disabled one too: it can still be the filter
            accounts =
                service.accounts.map { a ->
                    AccountChoice(
                        uuid = a.uuid,
                        name = accountName(a),
                        avatar = service.avatarService.get(a, avatarSize, avatarsCachedOnly),
                        // the folder tabs' count: unmuted chats outside the archive
                        unread = all.count { it.account === a && it.tatrakArchivedAt == null && !it.isMuted && it.unreadCount() > 0 },
                        disabled = !a.isEnabled,
                    )
                },
            selectedAccount = only?.uuid,
        )
    }

    private fun accountName(account: Account): String = account.displayName ?: account.jid.local ?: account.jid.toString()

    private fun toItem(
        context: Context,
        service: XmppConnectionService,
        conversation: Conversation,
        avatarSize: Int,
        avatarsCachedOnly: Boolean,
        thumbnailSize: Int,
    ): ChatListItem {
        val message = conversation.latestVisibleMessage
        val isGroup = conversation.mode == Conversational.MODE_MULTI
        val draft = if (conversation.isRead) conversation.draft else null
        val params = if (draft == null && previewIsFileDescription(message)) message.fileParams else null
        val (preview, isServicePreview) =
            if (draft != null) {
                draft.message() to false
            } else {
                val pair = UIHelper.getMessagePreview(context, message)
                val text = UIHelper.shorten(pair.first).toString()
                previewWithDuration(context, text, params) to pair.second
            }
        val file =
            if (params != null && hasThumbnail(message.mimeType, params.width, params.height)) service.fileBackend.getFile(message)
            else null
        val failureKey = file?.let { thumbnailFailureKey(message.uuid, it) }
        val mediaKey = message.uuid.takeIf { failureKey != null && !failedRecently(failureKey) }
        val outgoing = message.status > Message.STATUS_RECEIVED
        val previewSender =
            when {
                draft != null -> null
                message.type == Message.TYPE_STATUS || message.type == Message.TYPE_RTP_SESSION ->
                    null
                !isGroup -> null
                outgoing -> context.getString(R.string.tatrak_you)
                else ->
                    UIHelper.getMessageDisplayName(message).split(Whitespace).firstOrNull {
                        it.isNotEmpty()
                    }
            }
        val name = conversation.name
        return ChatListItem(
            uuid = conversation.uuid,
            title = chatTitle(context, conversation, name),
            styledTitle = styledChatTitle(context, conversation, name),
            isGroup = isGroup,
            preview = preview,
            previewSender = previewSender,
            isDraft = draft != null,
            isServicePreview = isServicePreview,
            isTyping = !isGroup && isTyping(conversation),
            timestamp = draft?.instant()?.toEpochMilli() ?: message.timeSent,
            unreadCount = conversation.unreadCount(),
            isMuted = conversation.isMuted,
            isPinned = conversation.isPinnedOnTop,
            outgoingStatus =
                if (draft == null && message.type != Message.TYPE_RTP_SESSION) {
                    outgoingStatus(message.status)
                } else {
                    null
                },
            isOnline = !isGroup && isOnline(conversation),
            isArchived = conversation.tatrakArchivedAt != null,
            avatar = service.avatarService.get(conversation, avatarSize, avatarsCachedOnly),
            isSavedMessages = isSavedMessages(conversation),
            ongoingCall = !isGroup && hasOngoingCall(conversation),
            mentionsOnly = !conversation.alwaysNotify(),
            previewMediaKey = mediaKey,
            previewThumbnail =
                if (mediaKey != null && file != null && failureKey != null) {
                    thumbnail(service, message, file, failureKey, thumbnailSize, avatarsCachedOnly)
                } else {
                    null
                },
        )
    }

    /**
     * Files whose thumbnail could not be made ([thumbnailFailureKey]), with the failure's [SystemClock.elapsedRealtime];
     * they keep the text-only preview without a retry on every snapshot. Bounded; a file that appears or changes gets a
     * new key, and an unchanged one is tried again after [ThumbnailRetryMillis] (the failure may have been transient).
     */
    internal val noThumbnail = LruCache<String, Long>(64)

    internal const val ThumbnailRetryMillis = 5 * 60_000L

    internal fun failedRecently(failureKey: String, now: Long = SystemClock.elapsedRealtime()): Boolean {
        val at = noThumbnail.get(failureKey) ?: return false
        if (now - at < ThumbnailRetryMillis) return true
        noThumbnail.remove(failureKey)
        return false
    }

    /** A message's file as it is now: a missing file, a finished download and a rewrite each give their own key. */
    internal fun thumbnailFailureKey(uuid: String, file: File): String = "$uuid:${file.length()}:${file.lastModified()}"

    /**
     * The media browser's square preview: FileBackend.getPreviewForUri decrypts through the media
     * store, keeps the bitmap in the app's in-memory bitmap cache under its own size, and draws the
     * play mark on a video.
     */
    private fun thumbnail(
        service: XmppConnectionService,
        message: Message,
        file: File,
        failureKey: String,
        size: Int,
        cachedOnly: Boolean,
    ): Bitmap? {
        val backend = service.fileBackend
        val attachment = Attachment.of(UUID.fromString(message.uuid), file, message.mimeType)
        if (cachedOnly) return backend.getPreviewForUri(attachment, size, true)
        // a missing video would come back as a black square
        val bitmap =
            if (file.exists()) runCatching { backend.getPreviewForUri(attachment, size, false) }.getOrNull() else null
        if (bitmap == null) noThumbnail.put(failureKey, SystemClock.elapsedRealtime())
        return bitmap
    }

    /** The branches of UIHelper.getMessagePreview that end in the file's description. */
    internal fun previewIsFileDescription(message: Message): Boolean =
        message.transferable == null &&
            message.type != Message.TYPE_RETRACTED &&
            message.type != Message.TYPE_HIDDEN &&
            message.isFileOrImage &&
            !message.isDeleted &&
            message.encryption !in NoFileEncryptions

    private val NoFileEncryptions =
        setOf(
            Message.ENCRYPTION_IMPORTED_PQ_UNAVAILABLE,
            Message.ENCRYPTION_IMPORTED_PQ_FAILED,
            Message.ENCRYPTION_PGP,
            Message.ENCRYPTION_DECRYPTION_FAILED,
            Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE,
            Message.ENCRYPTION_AXOLOTL_FAILED,
        )

    /** "audio, 0:12": the chat shows a file without dimensions but with a runtime as a voice message. */
    private fun previewWithDuration(context: Context, text: String, params: Message.FileParams?): String {
        val runtime = if (params == null || (params.width > 0 && params.height > 0)) 0 else params.runtime
        return if (runtime > 0) context.getString(R.string.tatrak_preview_with_duration, text, formatDuration(runtime)) else text
    }

    private fun outgoingStatus(status: Int): OutgoingStatus? =
        when (status) {
            Message.STATUS_UNSEND,
            Message.STATUS_WAITING,
            Message.STATUS_OFFERED -> OutgoingStatus.SENDING
            Message.STATUS_SEND -> OutgoingStatus.SENT
            Message.STATUS_SEND_RECEIVED -> OutgoingStatus.DELIVERED
            Message.STATUS_SEND_DISPLAYED -> OutgoingStatus.READ
            Message.STATUS_SEND_FAILED -> OutgoingStatus.FAILED
            else -> null
        }

    private fun isTyping(conversation: Conversation): Boolean {
        val connection = conversation.account.xmppConnection ?: return false
        return connection.getManager(ChatStateManager::class.java).getIncoming(conversation.address) ==
            Composing::class.java
    }

    /** One-to-one chats only, as ConversationAdapter. */
    internal fun hasOngoingCall(conversation: Conversation): Boolean =
        conversation.account.xmppConnection
            ?.getManager(JingleManager::class.java)
            ?.getOngoingRtpConnection(conversation.contact)
            ?.isPresent == true

    internal fun isOnline(conversation: Conversation): Boolean =
        when (conversation.contact.shownStatus) {
            Presence.Availability.CHAT,
            Presence.Availability.ONLINE -> true
            else -> false
        }
}

/** Side of the photo / video thumbnail in a chat list preview. */
internal const val PREVIEW_THUMBNAIL_DP = 20

/** A photo or video the chat shows as media (known dimensions) gets a thumbnail in the preview. */
internal fun hasThumbnail(mime: String?, width: Int, height: Int): Boolean =
    width > 0 && height > 0 && mime != null &&
        ((mime.startsWith("image/") && mime != "image/svg+xml") || mime.startsWith("video/"))

/** The chat with oneself is the "Saved messages" notebook. */
internal fun isSavedMessages(conversation: Conversation): Boolean =
    conversation.mode == Conversational.MODE_SINGLE && conversation.contact.isSelf

internal fun chatTitle(context: Context, conversation: Conversation, name: CharSequence = conversation.name): String =
    if (isSavedMessages(conversation)) {
        context.getString(R.string.tatrak_saved_messages)
    } else {
        name.toString()
    }

/** ConversationAdapter: a name that is the stranger's address gets its look-alike characters marked. */
internal fun styledChatTitle(context: Context, conversation: Conversation, name: CharSequence = conversation.name): AnnotatedString? =
    if (isSavedMessages(conversation)) null else (name as? Jid)?.let { styledAddress(context, it) }

/**
 * [IrregularUnicodeDetector]'s marks on look-alike characters, as the classic lists draw an address.
 * The snapshot builders get the application context, which has no theme; the app theme gives colorError.
 */
internal fun styledAddress(context: Context, jid: Jid): AnnotatedString =
    IrregularUnicodeDetector.style(ContextThemeWrapper(context, R.style.Theme_Conversations3), jid).toAnnotatedString()

/**
 * [styledAddress]'s marks in the current theme's error colour: the snapshot is styled on the
 * application context, which may not follow the app's own light / dark choice.
 */
@Composable
internal fun AnnotatedString.inErrorColor(): AnnotatedString {
    val error = MaterialTheme.colorScheme.error
    return remember(this, error) {
        if (spanStyles.isEmpty()) this
        else AnnotatedString(text, spanStyles.map { AnnotatedString.Range(it.item.copy(color = error), it.start, it.end) })
    }
}
