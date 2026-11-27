package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.chatlist.chatTitle
import am.cyberhayq.chat.ui.chatlist.styledChatTitle
import am.cyberhayq.chat.ui.chatlist.ChatListSnapshot
import am.cyberhayq.chat.ui.chatlist.hasThumbnail

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import com.google.common.base.Objects
import eu.siacs.conversations.Config
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.MucOptions
import eu.siacs.conversations.entities.ReadByMarker
import eu.siacs.conversations.entities.ReplyTo
import eu.siacs.conversations.entities.RtpSessionStatus
import eu.siacs.conversations.entities.Transferable
import eu.siacs.conversations.ui.util.ConversationMenuConfigurator
import eu.siacs.conversations.http.HttpDownloadConnection
import eu.siacs.conversations.xmpp.manager.BlockingManager
import eu.siacs.conversations.xmpp.manager.LastActivityManager
import eu.siacs.conversations.xmpp.manager.EntityTimeManager
import eu.siacs.conversations.xmpp.manager.MessageArchiveManager
import im.conversations.android.xmpp.model.stanza.Presence
import im.conversations.android.xmpp.model.state.Paused
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.SpannedString
import android.text.TextUtils
import android.text.style.StyleSpan
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import android.content.pm.PackageManager
import eu.siacs.conversations.utils.GeoHelper
import de.gultsch.common.Linkify
import com.google.common.collect.Iterables
import androidx.annotation.StringRes
import eu.siacs.conversations.services.NotificationService
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.xmpp.Jid
import eu.siacs.conversations.utils.CryptoHelper
import eu.siacs.conversations.utils.MessageUtils
import eu.siacs.conversations.utils.TimeFrameUtils
import eu.siacs.conversations.utils.UIHelper
import eu.siacs.conversations.xmpp.jingle.JingleFileTransferConnection
import eu.siacs.conversations.xmpp.manager.ChatStateManager
import eu.siacs.conversations.services.QuickConversationsService
import im.conversations.android.xmpp.model.idle.LastUserInteraction
import im.conversations.android.xmpp.model.reactions.Restrictions
import im.conversations.android.xmpp.model.state.Composing
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Position of a bubble in a run of merged messages, in reading (chronological) order. */
enum class GroupPosition {
    SINGLE,
    FIRST,
    MIDDLE,
    LAST;

    val isStart: Boolean
        get() = this == SINGLE || this == FIRST

    /** The bottom bubble of a group carries the tail, the avatar and the larger gap below. */
    val isEnd: Boolean
        get() = this == SINGLE || this == LAST
}

enum class MessageKind {
    TEXT,
    EMOJI,
    /** Placeholder text: undecryptable, PGP, deleted file, … rendered in italics. */
    INFO,
    IMAGE,
    /** A small webp/png picture, drawn without a bubble like Telegram's stickers. */
    STICKER,
    AUDIO,
    FILE,
    LOCATION,
    CALL,
    /** A message that is only a sticker-pack link: a card with the host and an "Add" button. */
    STICKER_PACK,
}

enum class DeliveryStatus {
    NONE,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
    CANCELLED,
    OFFERED,
}

enum class Security {
    NONE,
    ENCRYPTED,
    VERIFIED,
}

enum class FileState {
    /** Offered by the peer, not downloaded yet. */
    REMOTE,
    /** A link that may be a file; size unknown until checked. */
    CHECK_SIZE,
    DOWNLOADING,
    UPLOADING,
    LOCAL,
}

@Immutable data class ReactionChip(val emoji: String, val count: Int, val mine: Boolean)

@Immutable
data class MediaInfo(val width: Int, val height: Int, val isVideo: Boolean, val progress: Int = -1)

@Immutable
data class FileInfo(
    val title: String,
    val subtitle: String?,
    val state: FileState,
    /** 0..100 while transferring, -1 otherwise. */
    val progress: Int = -1,
)

@Immutable
data class CallInfo(
    val title: String,
    val duration: String?,
    val missed: Boolean,
    @param:DrawableRes val icon: Int,
)

@Immutable
data class MessageActions(
    val canReply: Boolean = false,
    val canCopy: Boolean = false,
    val canEdit: Boolean = false,
    val canRetry: Boolean = false,
    val canShare: Boolean = false,
    val canReact: Boolean = false,
    val canRemoveReaction: Boolean = false,
    val canDownload: Boolean = false,
    val canSave: Boolean = false,
    val canCancel: Boolean = false,
    /** "Delete": always possible locally; [ChatSnapshot.canRetract] adds "for everyone". */
    val canDelete: Boolean = false,
    val canViewHistory: Boolean = false,
    val canShowError: Boolean = false,
    val canOpenWith: Boolean = false,
    val canDeleteFile: Boolean = false,
    val canCopyUrl: Boolean = false,
    /** Label of "copy link" by the scheme of the first link in the text; null = no link. */
    @param:StringRes val copyLinkLabel: Int? = null,
) {
    val any: Boolean
        get() =
            canReply || canCopy || canEdit || canRetry || canShare || canReact || canDownload ||
                canSave || canCancel || canDelete || canViewHistory || canShowError || canOpenWith ||
                canDeleteFile || canCopyUrl || copyLinkLabel != null

    companion object {
        val NONE = MessageActions()
    }
}

sealed interface ChatItem {
    val key: String
    val contentType: Int
    /** Date of the item, shown in the floating date chip while scrolling. */
    val day: String
}

@Immutable
data class MessageItem(
    override val key: String,
    override val day: String,
    val isOutgoing: Boolean,
    val position: GroupPosition,
    val kind: MessageKind,
    val text: AnnotatedString,
    val time: String,
    /** Replaces [time] while sending a file or after a known failure ("File too large"). */
    val statusText: String? = null,
    val status: DeliveryStatus = DeliveryStatus.NONE,
    val security: Security = Security.NONE,
    val edited: Boolean = false,
    /** Group chats: name above the first bubble of a run of received messages. */
    val senderName: String? = null,
    val senderColor: Int = 0,
    /** Group chats: avatar next to the last bubble of a run of received messages. */
    val showAvatar: Boolean = false,
    val avatar: Bitmap? = null,
    val avatarLetter: String = "",
    /** Non-null for received messages that fail the trust check of the classic screen. */
    val trustWarning: String? = null,
    /**
     * Somebody asked to delete this encrypted message without proving to be the author; it is
     * kept (see ModerationManager.handleUserRetraction) and the bubble says so.
     */
    val retractUnverified: Boolean = false,
    val reactions: List<ReactionChip> = emptyList(),
    val media: MediaInfo? = null,
    val file: FileInfo? = null,
    val call: CallInfo? = null,
    val audioDurationMs: Int = 0,
    val actions: MessageActions = MessageActions.NONE,
    /** XEP-0461: the answered message above the content. */
    val reply: ReplyHeader? = null,
    /** Tatrak: text under the attachment (Message.CAPTION), whatever state the attachment is in. */
    val caption: AnnotatedString? = null,
    /**
     * Tatrak: epoch ms when this device stored a received message (Message.TIME_RECEIVED); null for
     * own messages and when unknown. Raw so only the open menu formats it, with [sentAt] beside it.
     */
    val receivedAt: Long? = null,
    /** Message.getTimeSent when [receivedAt] is known; 0 when the menu shows no times. */
    val sentAt: Long = 0L,
    /** Tatrak: the key line of the long-press menu; null for call records. */
    val encryptionKey: KeyInfo? = null,
    /** Tatrak, private groups: who has read this own message or a later one (their read markers). */
    val readBy: List<String> = emptyList(),
) : ChatItem {
    override val contentType: Int
        get() = kind.ordinal * 2 + if (isOutgoing) 1 else 0
}

/**
 * How a message was encrypted, for the long-press menu: the method (CryptoHelper.encryptionTypeToText),
 * the OMEMO key's trust and its fingerprint as two lines of four 8-digit groups.
 */
@Immutable
data class KeyInfo(@StringRes val method: Int, @StringRes val trust: Int? = null, val fingerprint: String? = null)

/** The menu's trust word for an OMEMO key; [own]: the key of this device. */
@StringRes
internal fun keyTrustLabel(status: FingerprintStatus?, own: Boolean): Int =
    when {
        own -> R.string.tatrak_chat_key_this_device
        status == null -> R.string.tatrak_chat_key_unknown
        status.isVerified -> R.string.tatrak_chat_key_verified
        status.isCompromised -> R.string.tatrak_chat_key_compromised
        status.isTrusted -> R.string.tatrak_chat_key_trusted
        else -> R.string.tatrak_chat_key_untrusted
    }

/** An OMEMO fingerprint ("05" + 64 hex digits) as two lines of four 8-digit groups. */
internal fun fingerprintLines(fingerprint: String): String =
    // "05" is the key type byte, left out as the classic key screens do
    fingerprint.substring(2).chunked(8).chunked(4).joinToString("\n") { it.joinToString(" ") }

/**
 * [targetKey] is null when the answered message isn't loaded or no longer exists; [thumbnail]: its picture is
 * drawn beside the text (see [hasReplyThumbnail]).
 */
@Immutable
data class ReplyHeader(
    val name: String,
    val text: String,
    val color: Int,
    val targetKey: String?,
    val thumbnail: Boolean = false,
)

@Immutable
data class DateItem(override val key: String, override val day: String) : ChatItem {
    override val contentType: Int
        get() = 100
}

@Immutable
data class UnreadItem(override val key: String, override val day: String, val count: Int) :
    ChatItem {
    override val contentType: Int
        get() = 101
}

@Immutable
data class ServiceItem(
    override val key: String,
    override val day: String,
    val text: AnnotatedString,
    /** MessageAdapter's status icon: paused notifications, the contact's night time. */
    @DrawableRes val icon: Int = 0,
    /** MessageAdapter's avatar on a read row: the reader, or the readers combined. */
    val avatar: Bitmap? = null,
) : ChatItem {
    constructor(key: String, day: String, text: String) : this(key, day, AnnotatedString(text))

    override val contentType: Int
        get() = 102
}

/** The classic "Load more messages" row: asks the server archive again (after Clear history, or an empty chat). */
@Immutable
data class LoadMoreItem(override val day: String) : ChatItem {
    override val key: String
        get() = "load-more"

    override val contentType: Int
        get() = 103
}

@Immutable
data class ChatHeader(
    val title: String = "",
    val subtitle: String? = null,
    /** Typing / online: subtitle drawn in the accent color. */
    val subtitleActive: Boolean = false,
    val avatar: Bitmap? = null,
    val isGroup: Boolean = false,
    val isMuted: Boolean = false,
    val isPinned: Boolean = false,
    val isOnline: Boolean = false,
    /** One-to-one chat: the call button (the classic screen shows it for every such chat). */
    val canCall: Boolean = false,
    val canVideo: Boolean = false,
    /** A call with this contact is running: the button returns to it. */
    val ongoingCall: Boolean = false,
    /** Null when blocking is not available (group, or the server lacks it). */
    val isBlocked: Boolean? = null,
    /** [title] with look-alike characters marked, when the title is a stranger's address. */
    val styledTitle: AnnotatedString? = null,
)

/** The classic snackbar of ConversationFragment.updateSnackBar: its text and button, if any. */
enum class ChatBanner(@StringRes val text: Int, @StringRes val button: Int? = null) {
    ACCOUNT_DISABLED(R.string.this_account_is_disabled, R.string.enable),
    ACCOUNT_LOGGED_OUT(R.string.this_account_is_logged_out, R.string.log_in),
    BLOCKED(R.string.contact_blocked, R.string.unblock),
    ADD_BACK(R.string.contact_added_you, R.string.add_back),
    ALLOW_SUBSCRIPTION(R.string.contact_asks_for_presence_subscription, R.string.allow),
    CONNECTING(R.string.tatrak_connecting),
    // a group not joined while the account is online, by MucOptions.Error
    NICK_IN_USE(R.string.nick_in_use, R.string.edit),
    JOINING(R.string.joining_conference),
    SERVER_NOT_FOUND_RETRY(R.string.remote_server_not_found, R.string.try_again),
    SERVER_NOT_FOUND_LEAVE(R.string.remote_server_not_found, R.string.leave),
    SERVER_TIMEOUT_RETRY(R.string.remote_server_timeout, R.string.try_again),
    SERVER_TIMEOUT_LEAVE(R.string.remote_server_timeout, R.string.leave),
    PASSWORD_REQUIRED(R.string.conference_requires_password, R.string.enter_password),
    BANNED(R.string.conference_banned, R.string.leave),
    MEMBERS_ONLY(R.string.conference_members_only, R.string.leave),
    RESOURCE_CONSTRAINT(R.string.conference_resource_constraint, R.string.try_again),
    KICKED(R.string.conference_kicked, R.string.join),
    TECHNICAL_PROBLEMS(R.string.conference_technical_problems, R.string.try_again),
    UNKNOWN_ERROR(R.string.conference_unknown_error, R.string.try_again),
    INVALID_NICK(R.string.invalid_muc_nick, R.string.edit),
    SHUTDOWN(R.string.conference_shutdown, R.string.try_again),
    DESTROYED(R.string.conference_destroyed, R.string.leave),
    NON_ANONYMOUS(R.string.group_chat_will_make_your_jabber_id_public, R.string.join),
    PGP_PENDING(R.string.openpgp_messages_found, R.string.decrypt),
    /** The button opens [BlockMenu]. */
    STRANGER(R.string.received_message_from_stranger, R.string.block),
}

/**
 * ConversationFragment.showBlockSubmenu: the stranger banner's Block button, and a long press on
 * Add back / Allow. "Block entire domain" is always there; [blockContact] for an address with a
 * local part; [reject] (stopPresenceUpdatesTo) for a pending request from someone not a stranger.
 */
@Immutable
data class BlockMenu(val blockContact: Boolean, val reject: Boolean)

@Immutable
data class EditPreview(val uuid: String, val text: String)

@Immutable
data class ComposerInfo(
    val canWrite: Boolean = true,
    /** OpenPGP is selected for this chat; sending is left to the classic screen. */
    val requiresClassic: Boolean = false,
    val hint: String = "",
    val editing: EditPreview? = null,
    /** The chat's next encryption when the classic menu shows its choice; null when it is hidden. */
    val encryption: Int? = null,
    /** A group we take part in: "@" offers the participants. */
    val mentions: Boolean = false,
    /** The build may read phone contacts: the attach menu offers Contact, as ConversationFragment does. */
    val contacts: Boolean = false,
)

@Immutable
data class ChatState(
    val loaded: Boolean = false,
    val conversationUuid: String? = null,
    /** Changes whenever another conversation is bound; resets the initial scroll. */
    val generation: Int = 0,
    val header: ChatHeader = ChatHeader(),
    /** Newest first, for a reverse-layout list. */
    val items: List<ChatItem> = emptyList(),
    val unreadCount: Int = 0,
    val banner: ChatBanner? = null,
    /** Non-null for the banners that have the classic block menu. */
    val blockMenu: BlockMenu? = null,
    val composer: ComposerInfo = ComposerInfo(),
    /** This chat's own wallpaper spec; null follows the app-wide setting. */
    val wallpaper: String? = null,
    /** Received messages that mention our nick and were unread here, oldest first ([MentionTracker]). */
    val mentions: List<String> = emptyList(),
    /** The open composer's reply target if its thumbnail is available in this snapshot. */
    val replyThumbnailUuid: String? = null,
)

/** Inputs of [ChatSnapshot.build] that come from the UI rather than the service. */
data class SnapshotParams(
    val palette: TextPalette,
    val unreadAnchorUuid: String?,
    val avatarSize: Int,
    val avatarsCachedOnly: Boolean,
    val generation: Int,
    val showLastSeen: Boolean,
    /** The bound chat's pending mentions. */
    val mentions: MentionTracker,
    /** The chat-list preview: nothing is asked of the contact or their server (entity time, last activity). */
    val preview: Boolean = false,
    val replyUuid: String? = null,
)

/** Formatted bodies keyed by message uuid, body and colors; formatting is the costly part. */
class FormattedTextCache(private val size: Int = 1024) {
    private val cache = LruCache<String, AnnotatedString>(size)

    fun prepare(messageCount: Int) {
        cache.resize(maxOf(size, messageCount))
    }

    fun getOrPut(key: String, create: () -> AnnotatedString): AnnotatedString =
        cache.get(key) ?: create().also { cache.put(key, it) }
}

object ChatSnapshot {

    /** The conditions ModerationManager.retract checks before sending. */
    fun canRetract(conversation: Conversation, message: Message): Boolean =
        !message.isDeleted &&
            message.type != Message.TYPE_RETRACTED &&
            (message.status == Message.STATUS_SEND ||
                message.status == Message.STATUS_SEND_RECEIVED ||
                message.status == Message.STATUS_SEND_DISPLAYED) &&
            (conversation.mode != Conversational.MODE_MULTI ||
                message.isPrivateMessage ||
                (conversation.mucOptions.occupantId() &&
                    conversation.mucOptions.participating() &&
                    message.serverMsgId != null))

    /** The message items of [messages] as [build] makes them, without the header (it needs the running service). */
    @androidx.annotation.VisibleForTesting
    internal fun items(
        context: Context,
        service: XmppConnectionService,
        conversation: Conversation,
        params: SnapshotParams,
        messages: List<Message>,
    ): List<ChatItem> = Builder(context, service, conversation, params, FormattedTextCache()).items(messages)

    fun build(
        context: Context,
        service: XmppConnectionService,
        conversation: Conversation,
        params: SnapshotParams,
        textCache: FormattedTextCache,
    ): ChatState {
        val messages = ArrayList<Message>()
        conversation.populateWithMessages(messages)
        messages.removeAll { it.type == Message.TYPE_HIDDEN }
        textCache.prepare(messages.size)
        val builder = Builder(context, service, conversation, params, textCache)
        val banner = banner(conversation)
        return ChatState(
            loaded = true,
            conversationUuid = conversation.uuid,
            generation = params.generation,
            header =
                header(context, service, conversation, params.avatarSize, params.avatarsCachedOnly, params.showLastSeen, query = !params.preview),
            items = withStatusRows(context, conversation, builder.items(messages), query = !params.preview),
            unreadCount = conversation.unreadCount(),
            banner = banner,
            blockMenu = if (banner == ChatBanner.STRANGER || banner == ChatBanner.ADD_BACK || banner == ChatBanner.ALLOW_SUBSCRIPTION) blockMenu(conversation) else null,
            composer = composer(context, conversation),
            wallpaper = conversation.tatrakWallpaper,
            mentions = builder.mentions(messages),
            replyThumbnailUuid = params.replyUuid?.takeIf { uuid ->
                messages.firstOrNull { it.uuid == uuid }?.let(::hasReplyThumbnail) == true
            },
        )
    }

    /** Also the profile's header. */
    internal fun header(
        context: Context,
        service: XmppConnectionService,
        conversation: Conversation,
        avatarSize: Int,
        cachedOnly: Boolean,
        showLastSeen: Boolean,
        /** False: no XEP-0012 query for an offline contact's last activity. */
        query: Boolean = true,
    ): ChatHeader {
        val isGroup = conversation.mode == Conversational.MODE_MULTI
        var active = false
        val subtitle: String? =
            if (isGroup) {
                val mucOptions = conversation.mucOptions
                val count = mucOptions.userCount
                // the classic screen's typing row (ConversationFragment.updateStatusMessages); "stopped typing" is left out as in one-to-one chats
                val typing = mucOptions.getUsersWithChatState(5).takeIf { it.chatState == Composing::class.java }?.users.orEmpty()
                active = typing.isNotEmpty()
                when {
                    typing.size == 1 -> context.getString(R.string.contact_is_typing, typing[0].displayName)
                    typing.size > 1 -> context.getString(R.string.contacts_are_typing, UIHelper.concatNames(typing))
                    mucOptions.isPrivateAndNonAnonymous || count < 1 -> null
                    else -> context.resources.getQuantityString(R.plurals.x_participants, count, count)
                }
            } else if (isTyping(conversation)) {
                active = true
                context.getString(R.string.tatrak_typing)
            } else if (showLastSeen) {
                val contact = conversation.contact
                var interaction = contact.lastUserInteraction
                if (interaction is LastUserInteraction.None && query) {
                    // offline: ask the contact's server (XEP-0012), cached; nothing if it can't tell
                    contact.account.xmppConnection
                        ?.getManager(LastActivityManager::class.java)
                        ?.lastSeen(contact)
                        ?.let { interaction = LastUserInteraction.idle(it) }
                }
                active = interaction is LastUserInteraction.Online
                UIHelper.lastUserInteraction(context, interaction)
            } else {
                null
            }
        val mutedTill = conversation.mutedTill
        val name = conversation.name
        return ChatHeader(
            title = chatTitle(context, conversation, name),
            styledTitle = styledChatTitle(context, conversation, name),
            subtitle = subtitle,
            subtitleActive = active,
            avatar =
                service.avatarService.get(conversation, avatarSize, cachedOnly),
            isGroup = isGroup,
            isOnline = !isGroup && ChatListSnapshot.isOnline(conversation),
            isMuted = mutedTill != null && mutedTill.isAfter(Instant.now()),
            isPinned = conversation.isPinnedOnTop,
            canCall = !isGroup,
            canVideo = !isGroup && context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            ongoingCall = !isGroup && ChatListSnapshot.hasOngoingCall(conversation),
            isBlocked =
                if (!isGroup &&
                    conversation.account.xmppConnection?.getManager(BlockingManager::class.java)?.hasFeature() == true
                ) {
                    conversation.isBlocked
                } else {
                    null
                },
        )
    }

    private fun isTyping(conversation: Conversation): Boolean {
        val connection = conversation.account.xmppConnection ?: return false
        return connection.getManager(ChatStateManager::class.java).getIncoming(conversation.address) ==
            Composing::class.java
    }

    /** ConversationFragment.updateSnackBar in its order, then [ChatBanner.CONNECTING] where the classic screen shows nothing. */
    @androidx.annotation.VisibleForTesting
    internal fun banner(conversation: Conversation): ChatBanner? {
        if (conversation.status == Conversation.STATUS_ARCHIVED) return null
        val account = conversation.account
        val connection = account.xmppConnection
        val contact = if (conversation.mode == Conversational.MODE_SINGLE) conversation.contact else null
        return when (account.status) {
            Account.State.DISABLED -> ChatBanner.ACCOUNT_DISABLED
            Account.State.LOGGED_OUT -> ChatBanner.ACCOUNT_LOGGED_OUT
            else ->
                subscriptionBanner(
                    conversation.isBlocked,
                    contact?.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST) == true,
                    contact?.showInRoster() == true,
                )
        }
            ?: laterBanner(
                conversation.mode == Conversational.MODE_MULTI && !conversation.mucOptions.online() && account.status == Account.State.ONLINE,
                { mucBanner(conversation.mucOptions.error, conversation.receivedMessagesCount() > 0) },
                // hasPendingPgpIntent reads the connection's decryption service
                { connection != null && account.hasPendingPgpIntent(conversation) },
                {
                    connection?.getManager(BlockingManager::class.java)?.hasFeature() == true &&
                        conversation.countMessages() != 0 &&
                        !conversation.isBlocked &&
                        conversation.isWithStranger
                },
                { account.isOnlineAndConnected },
            )
    }

    internal fun blockMenu(conversation: Conversation): BlockMenu? {
        if (conversation.mode != Conversational.MODE_SINGLE) return null
        return BlockMenu(
            blockContact = conversation.address.local != null,
            reject = !conversation.isWithStranger && conversation.contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST),
        )
    }

    /** ConversationFragment.updateStatusMessages: "Load more messages" above the oldest item, the contact's status row below the newest. */
    private fun withStatusRows(context: Context, conversation: Conversation, items: List<ChatItem>, query: Boolean): List<ChatItem> {
        val status = contactStatus(context, conversation, items.firstOrNull()?.day.orEmpty(), query)
        val loadMore = showLoadMore(conversation)
        if (status == null && !loadMore) return items
        val all = ArrayList<ChatItem>(items.size + 2)
        status?.let { all += it }
        all += items
        if (loadMore) all += LoadMoreItem(items.lastOrNull()?.day.orEmpty())
        return all
    }

    /** ConversationFragment.showLoadMoreMessages. */
    internal fun showLoadMore(conversation: Conversation): Boolean {
        val connection = conversation.account.xmppConnection ?: return false
        val archive = connection.getManager(MessageArchiveManager::class.java)
        val mamSupport =
            if (conversation.mode == Conversational.MODE_SINGLE) archive.hasFeature() else conversation.mucOptions.mamSupport()
        return loadMoreRow(
            mamSupport && !conversation.contact.isBlocked,
            conversation.lastClearHistory?.timestamp(),
            conversation.countMessages() == 0 && conversation.messagesLoaded.get() && conversation.hasMessagesLeftOnServer(),
        ) { archive.queryInProgress(conversation) }
    }

    /** The one-to-one part of ConversationFragment.updateStatusMessages without the typing rows (the header shows typing). */
    private fun contactStatus(context: Context, conversation: Conversation, day: String, query: Boolean): ServiceItem? {
        if (conversation.mode != Conversational.MODE_SINGLE) return null
        val connection = conversation.account.xmppConnection ?: return null
        val state = connection.getManager(ChatStateManager::class.java).getIncoming(conversation.address)
        // getZonedDateTime asks the contact's devices (XEP-0202) when nothing is cached
        val time = if (query) connection.getManager(EntityTimeManager::class.java).getZonedDateTime(conversation.address) else null
        if (state == Composing::class.java || state == Paused::class.java) return null
        return contactStatusRow(
            context,
            conversation.name,
            conversation.contact.shownStatus == Presence.Availability.DND,
            // as the classic getOrNull: only an answer that is already there
            if (time != null && time.isDone) runCatching { time.get() }.getOrNull() else null,
            EntityTimeManager.noRecentMessages(conversation),
            day,
        )
    }

    private fun composer(context: Context, conversation: Conversation): ComposerInfo {
        val isGroup = conversation.mode == Conversational.MODE_MULTI
        val participating = !isGroup || conversation.mucOptions.participating()
        val correcting = conversation.correctingMessage
        // a group private message's hint is the composer's (ChatViewModel.privateTo), not this background snapshot's
        val hint =
            when {
                correcting != null -> context.getString(R.string.send_corrected_message)
                !participating -> context.getString(R.string.you_are_not_participating)
                else -> UIHelper.getMessageHint(context, conversation)
            }
        return ComposerInfo(
            canWrite = participating,
            requiresClassic = conversation.nextEncryption == Message.ENCRYPTION_PGP,
            hint = hint,
            encryption =
                conversation.nextEncryption.takeIf {
                    ConversationMenuConfigurator.isEncryptionMenuVisible(conversation)
                },
            editing = correcting?.let { EditPreview(it.uuid, UIHelper.shorten(it.bodyWithoutReplyFallback).toString()) },
            mentions = isGroup && participating,
            contacts = QuickConversationsService.isContactListIntegration(context),
        )
    }

    /**
     * [Message.isValidInSession] for every message of [messages] (the conversation's list, oldest
     * first) in two passes. Calling it per message walks the neighbours each time, which is
     * quadratic over long runs of received messages (~0.5 s for 3000 messages, benchmark build).
     */
    internal fun validInSession(messages: List<Message>, nextEncryption: Int): BooleanArray {
        fun own(message: Message) = !message.isCarbon && message.status != Message.STATUS_RECEIVED
        val past = IntArray(messages.size)
        var last = Message.ENCRYPTION_NONE
        for (i in messages.indices) {
            past[i] = last
            if (own(messages[i])) last = messages[i].encryption
        }
        val valid = BooleanArray(messages.size)
        var next = nextEncryption
        for (i in messages.indices.reversed()) {
            val pastEncryption = cleanedEncryption(past[i])
            val futureEncryption = cleanedEncryption(next)
            val inUnencryptedSession =
                pastEncryption == Message.ENCRYPTION_NONE ||
                    futureEncryption == Message.ENCRYPTION_NONE ||
                    pastEncryption != futureEncryption
            valid[i] = inUnencryptedSession || cleanedEncryption(messages[i].encryption) == pastEncryption
            if (own(messages[i])) next = messages[i].encryption
        }
        return valid
    }

    /**
     * Reply targets of one snapshot: [Conversation.findMessageWithServerMsgId] and
     * [Conversation.findMessageWithUuidOrRemoteId] (without filters) as maps over the copied list,
     * keeping their first-match order; each call of those walks the whole list under its lock.
     */
    internal class MessageIndex(messages: List<Message>) {
        private val byServerId = HashMap<String, Message>()
        private val byUuidOrRemoteId = HashMap<String, Message>()

        init {
            for (message in messages) {
                message.serverMsgId?.let { byServerId.putIfAbsent(it, message) }
                byUuidOrRemoteId.putIfAbsent(message.uuid, message)
                message.remoteMsgId?.let { byUuidOrRemoteId.putIfAbsent(it, message) }
            }
        }

        fun withServerMsgId(id: String): Message? = byServerId[id]

        fun withUuidOrRemoteId(id: String): Message? = byUuidOrRemoteId[id]
    }

    /** Message.getCleanedEncryption, which is private. */
    private fun cleanedEncryption(encryption: Int) =
        when (encryption) {
            Message.ENCRYPTION_DECRYPTED, Message.ENCRYPTION_DECRYPTION_FAILED -> Message.ENCRYPTION_PGP
            Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE, Message.ENCRYPTION_AXOLOTL_FAILED ->
                Message.ENCRYPTION_AXOLOTL
            else -> encryption
        }

    private class Builder(
        private val context: Context,
        private val service: XmppConnectionService,
        private val conversation: Conversation,
        private val params: SnapshotParams,
        private val textCache: FormattedTextCache,
    ) {
        private val isGroup = conversation.mode == Conversational.MODE_MULTI
        private val zone = ZoneId.systemDefault()
        private val locale: Locale = context.resources.configuration.locales[0]
        private val timeFormat =
            DateTimeFormatter.ofPattern(
                if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
                locale,
            )
        private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", locale)
        private val fullDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
        private val today = LocalDate.now(zone)
        private val dayLabels = HashMap<LocalDate, String>()
        private val avatars = HashMap<String, Bitmap?>()
        private val linkStyles =
            TextLinkStyles(style = SpanStyle(color = Color(params.palette.link)))
        private val highlightNick =
            if (isGroup) {
                conversation.mucOptions.actualNick?.let {
                    NotificationService.generateNickHighlightPattern(it)
                }
            } else {
                null
            }

        fun mentions(messages: List<Message>): List<String> = params.mentions.update(messages, highlightNick)

        /** Set by [items] before any reply is resolved. */
        private var index = MessageIndex(emptyList())

        /** Set by [items]: "… have read up to this point" rows by message index, readers of own messages by uuid. */
        private var readRows: Map<Int, Pair<String, Bitmap?>> = emptyMap()
        private var readers: Map<String, List<String>> = emptyMap()

        fun items(messages: List<Message>): List<ChatItem> {
            index = MessageIndex(messages)
            readMarks(messages)
            val items = ArrayList<ChatItem>(messages.size + 8)
            val anchor = params.unreadAnchorUuid
            val anchorIndex = if (anchor == null) -1 else messages.indexOfFirst { it.uuid == anchor }
            val days = messages.map { Instant.ofEpochMilli(it.timeSent).atZone(zone).toLocalDate() }
            val validInSession = validInSession(messages, conversation.nextEncryption)
            for (i in messages.indices) {
                val message = messages[i]
                val date = days[i]
                val day = dayLabel(date)
                val newDay = i == 0 || days[i - 1] != date
                if (newDay) {
                    items += DateItem("date-$date", day)
                }
                if (i == anchorIndex) {
                    val count = (i until messages.size).count {
                        messages[it].status == Message.STATUS_RECEIVED
                    }
                    if (count > 0) items += UnreadItem("unread", day, count)
                }
                if (message.type == Message.TYPE_STATUS) {
                    items += ServiceItem(message.uuid, day, message.body.orEmpty())
                    continue
                }
                val mergeTop =
                    !newDay && i != anchorIndex && i - 1 !in readRows && merge(messages[i - 1], message)
                val mergeBottom =
                    i + 1 < messages.size &&
                        days[i + 1] == date &&
                        i + 1 != anchorIndex &&
                        i !in readRows &&
                        merge(message, messages[i + 1])
                val position =
                    when {
                        mergeTop && mergeBottom -> GroupPosition.MIDDLE
                        mergeTop -> GroupPosition.LAST
                        mergeBottom -> GroupPosition.FIRST
                        else -> GroupPosition.SINGLE
                    }
                items += toItem(message, day, position, validInSession[i])
                readRows[i]?.let { (text, avatar) -> items += ServiceItem("read-${message.uuid}", day, AnnotatedString(text), avatar = avatar) }
            }
            items.reverse()
            return items
        }

        /**
         * The walk of ConversationFragment.updateStatusMessages over group read markers, newest
         * first: a row under the message where a member's marker first shows (members who wrote
         * later are left out, as there), and for every own message all members whose marker is on
         * it or a later one.
         */
        private fun readMarks(messages: List<Message>) {
            readRows = emptyMap()
            readers = emptyMap()
            if (!isGroup) return
            val mucOptions = conversation.mucOptions
            if (!mucOptions.isPrivateAndNonAnonymous) return
            val allUsers = mucOptions.users
            val rows = HashMap<Int, Pair<String, Bitmap?>>()
            val byUuid = HashMap<String, List<String>>()
            val added = HashSet<ReadByMarker>() // markers in a row below and senders below
            val seen = HashSet<ReadByMarker>() // markers only
            val names = ArrayList<String>()
            var readersSoFar = emptyList<String>() // one copy per new reader, shared by the messages above it
            var everyone = false
            for (i in messages.indices.reversed()) {
                val message = messages[i]
                val markers = message.readByMarkers
                for (marker in markers) {
                    if (!ReadByMarker.contains(marker, seen)) {
                        seen += marker
                        mucOptions.getUser(marker)?.let { names += it.displayName }
                    }
                }
                if (readersSoFar.size != names.size) readersSoFar = names.toList()
                // sent messages only: a failed or waiting one was not read; a private one reached one member,
                // so later public markers say nothing about it
                val sent =
                    message.status == Message.STATUS_SEND ||
                        message.status == Message.STATUS_SEND_RECEIVED ||
                        message.status == Message.STATUS_SEND_DISPLAYED
                if (sent && message.type != Message.TYPE_STATUS && !message.isPrivateMessage && readersSoFar.isNotEmpty()) {
                    byUuid[message.uuid] = readersSoFar
                }
                if (everyone) continue
                val shown = ArrayList<MucOptions.User>()
                for (marker in markers) {
                    if (!ReadByMarker.contains(marker, added)) {
                        added += marker
                        mucOptions.getUser(marker)?.let { shown += it }
                    }
                }
                val sender = ReadByMarker.from(message)
                val size = shown.size
                when {
                    size == 1 -> context.getString(R.string.contact_has_read_up_to_this_point, shown[0].displayName)
                    size in 2..4 -> context.getString(R.string.contacts_have_read_up_to_this_point, UIHelper.concatNames(shown))
                    size > 4 && ReadByMarker.allUsersRepresented(allUsers, markers, sender) ->
                        context.getString(R.string.everyone_has_read_up_to_this_point)
                    size > 4 ->
                        context.getString(R.string.contacts_and_n_more_have_read_up_to_this_point, UIHelper.concatNames(shown, 3), size - 3)
                    else -> null
                }?.let { rows[i] = it to readAvatar(it, shown) }
                added += sender
                everyone = ReadByMarker.allUsersRepresented(allUsers, added)
            }
            readRows = rows
            readers = byUuid
        }

        /** The avatar MessageAdapter draws for the classic read row, from the same status message. */
        private fun readAvatar(body: String, shown: List<MucOptions.User>): Bitmap? {
            val status = Message.createStatusMessage(conversation, body)
            if (shown.size == 1) {
                status.counterpart = shown[0].fullJid
                status.trueCounterpart = shown[0].realJid
            } else {
                status.counterparts = shown
            }
            return service.avatarService.get(status, params.avatarSize, params.avatarsCachedOnly)
        }

        private fun dayLabel(date: LocalDate): String =
            dayLabels.getOrPut(date) {
                when {
                    date == today -> context.getString(R.string.today)
                    date == today.minusDays(1) -> context.getString(R.string.yesterday)
                    date.year == today.year -> dayMonth.format(date)
                    else -> fullDate.format(date)
                }
            }

        private fun toItem(
            message: Message,
            day: String,
            position: GroupPosition,
            validInSession: Boolean,
        ): MessageItem {
            val outgoing = message.status != Message.STATUS_RECEIVED
            val encryption = message.encryption
            val omemo = encryption == Message.ENCRYPTION_AXOLOTL
            val trustWarning =
                if (encryption in 8..10) context.getString(R.string.tatrak_backup_pq_history)
                else if (!outgoing && message.type != Message.TYPE_RTP_SESSION && message.type != Message.TYPE_RETRACTED) {
                    // same condition as MessageAdapter.render(…, BubbleMessageItemViewHolder)
                    val inValidSession = validInSession && (!omemo || message.isTrusted)
                    when {
                        inValidSession -> null
                        omemo && !message.isTrusted -> context.getString(R.string.not_trusted)
                        else -> context.getString(CryptoHelper.encryptionTypeToText(encryption))
                    }
                } else {
                    null
                }
            val showSender = isGroup && !outgoing && position.isStart
            val showAvatar = isGroup && !outgoing && position.isEnd
            val name = if (showSender || showAvatar) UIHelper.getMessageDisplayName(message) else null
            val receivedAt = if (outgoing) null else message.timeReceived
            val trust =
                if (encryption == Message.ENCRYPTION_AXOLOTL) conversation.account.axolotlService?.getFingerprintTrust(message.fingerprint)
                else null
            val base =
                MessageItem(
                    key = message.uuid,
                    day = day,
                    isOutgoing = outgoing,
                    position = position,
                    kind = MessageKind.TEXT,
                    text = EMPTY,
                    time =
                        timeFormat.format(Instant.ofEpochMilli(message.timeSent).atZone(zone)),
                    statusText = statusText(message),
                    status = if (outgoing) deliveryStatus(message) else DeliveryStatus.NONE,
                    security = security(message, trust),
                    edited = showsEdited(message),
                    senderName = if (showSender) name else null,
                    senderColor = if (name != null) UIHelper.getColorForName(name) else 0,
                    showAvatar = showAvatar,
                    avatar = if (showAvatar) avatar(message) else null,
                    avatarLetter = name?.firstOrNull()?.uppercase() ?: "",
                    trustWarning = trustWarning,
                    retractUnverified = message.retractAttempt != 0L,
                    reactions = reactions(message),
                    actions = actions(message),
                    reply = replyHeader(message),
                    receivedAt = receivedAt,
                    sentAt = if (receivedAt != null) message.timeSent else 0L,
                    encryptionKey = if (message.type == Message.TYPE_RTP_SESSION) null else keyInfo(message, trust),
                    readBy = readers[message.uuid].orEmpty(),
                )
            // Tatrak: after content(), so no attachment state (deleted, downloading, cancelled) hides it
            val caption = message.caption
            return content(message, base).let {
                if (caption.isNullOrBlank() || message.type == Message.TYPE_RETRACTED || message.type == Message.TYPE_HIDDEN) it
                else it.copy(caption = text(message, outgoing, caption))
            }
        }

        /** The branch order of MessageAdapter.render, so every message lands in the same kind. */
        private fun content(message: Message, base: MessageItem): MessageItem {
            if (message.type == Message.TYPE_RETRACTED) {
                return info(base, context.getString(R.string.tatrak_message_deleted))
            }
            if (message.encryption == Message.ENCRYPTION_IMPORTED_PQ_UNAVAILABLE ||
                message.encryption == Message.ENCRYPTION_IMPORTED_PQ_FAILED) {
                return info(base, context.getString(R.string.tatrak_backup_pq_unavailable))
            }
            if (message.type == Message.TYPE_RTP_SESSION) {
                return base.copy(kind = MessageKind.CALL, call = call(message, base.isOutgoing))
            }
            val transferable = message.transferable
            val transferStatus = transferable?.status
            val unInitiatedButKnownSize = MessageUtils.unInitiatedButKnownSize(message)
            val encryption = message.encryption
            if (
                unInitiatedButKnownSize ||
                    message.isDeleted ||
                    (transferable != null && transferStatus != Transferable.STATUS_UPLOADING)
            ) {
                val description = UIHelper.getFileDescriptionString(context, message)
                val size = message.fileParams.size?.let { UIHelper.filesizeToString(it) }
                return when {
                    unInitiatedButKnownSize || transferStatus == Transferable.STATUS_OFFER ->
                        base.copy(
                            kind = MessageKind.FILE,
                            file = FileInfo(description, size, FileState.REMOTE),
                        )
                    transferStatus == Transferable.STATUS_OFFER_CHECK_FILESIZE ->
                        base.copy(
                            kind = MessageKind.FILE,
                            file =
                                FileInfo(
                                    description,
                                    context.getString(R.string.check_x_filesize, description),
                                    FileState.CHECK_SIZE,
                                ),
                        )
                    transferStatus == Transferable.STATUS_DOWNLOADING ||
                        transferStatus == Transferable.STATUS_CHECKING ->
                        base.copy(
                            kind = MessageKind.FILE,
                            file =
                                FileInfo(
                                    description,
                                    UIHelper.getMessagePreview(context, message).first.toString(),
                                    FileState.DOWNLOADING,
                                    transferable.progress,
                                ),
                        )
                    else -> info(base, UIHelper.getMessagePreview(context, message).first)
                }
            }
            if (
                message.isFileOrImage &&
                    encryption != Message.ENCRYPTION_PGP &&
                    encryption != Message.ENCRYPTION_DECRYPTION_FAILED
            ) {
                val params = message.fileParams
                val uploading = transferStatus == Transferable.STATUS_UPLOADING
                val progress = if (uploading) transferable.progress else -1
                return when {
                    params.width > 0 && params.height > 0 ->
                        base.copy(
                            kind =
                                if (isSticker(message, params) && message.caption.isNullOrBlank()) MessageKind.STICKER
                                else MessageKind.IMAGE,
                            media =
                                MediaInfo(
                                    params.width,
                                    params.height,
                                    message.mimeType?.startsWith("video/") == true,
                                    progress,
                                ),
                        )
                    isVoice(message) ->
                        base.copy(kind = MessageKind.AUDIO, audioDurationMs = params.runtime)
                    else ->
                        base.copy(
                            kind = MessageKind.FILE,
                            file =
                                FileInfo(
                                    UIHelper.getFileDescriptionString(context, message),
                                    params.size?.let { UIHelper.filesizeToString(it) },
                                    if (uploading) FileState.UPLOADING else FileState.LOCAL,
                                    progress,
                                ),
                        )
                }
            }
            val account = conversation.account
            return when (encryption) {
                Message.ENCRYPTION_PGP ->
                    info(
                        base,
                        context.getString(
                            if (account.isPgpDecryptionServiceConnected) {
                                if (!account.hasPendingPgpIntent(conversation)) {
                                    R.string.message_decrypting
                                } else {
                                    R.string.pgp_message
                                }
                            } else {
                                R.string.install_openkeychain
                            }
                        ),
                    )
                Message.ENCRYPTION_DECRYPTION_FAILED ->
                    info(base, context.getString(R.string.decryption_failed))
                Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE ->
                    info(base, context.getString(R.string.not_encrypted_for_this_device))
                Message.ENCRYPTION_AXOLOTL_FAILED ->
                    info(base, context.getString(R.string.omemo_decryption_failed))
                else ->
                    when {
                        message.isGeoUri -> base.copy(kind = MessageKind.LOCATION)
                        message.bodyIsOnlyEmojis() && message.type != Message.TYPE_PRIVATE ->
                            base.copy(
                                kind = MessageKind.EMOJI,
                                text = AnnotatedString(message.body.trim()),
                            )
                        message.treatAsDownloadable() -> {
                            val description = UIHelper.getFileDescriptionString(context, message)
                            val host = runCatching { URI(message.body).host }.getOrNull()
                            base.copy(
                                kind = MessageKind.FILE,
                                file =
                                    FileInfo(
                                        description,
                                        if (host != null) {
                                            context.getString(
                                                R.string.check_x_filesize_on_host,
                                                description,
                                                host,
                                            )
                                        } else {
                                            context.getString(R.string.check_x_filesize, description)
                                        },
                                        FileState.CHECK_SIZE,
                                    ),
                            )
                        }
                        else -> {
                            val pack = StickerStore.packLink(message.body)
                            if (pack != null) {
                                base.copy(kind = MessageKind.STICKER_PACK, text = AnnotatedString(pack.toString()))
                            } else {
                                base.copy(kind = MessageKind.TEXT, text = text(message, base.isOutgoing))
                            }
                        }
                    }
            }
        }

        /** Looked up in this conversation only, the way the reference was made. */
        private fun replyHeader(message: Message): ReplyHeader? {
            val reply = message.replyTo ?: return null
            val parent =
                if (isGroup && !message.isPrivateMessage) {
                    index.withServerMsgId(reply.id)
                } else {
                    index.withUuidOrRemoteId(reply.id)
                }
            if (parent == null) {
                // the quote the sender put in the body is all we know
                val name = reply.to?.let { Jid.ofOrInvalid(it) }?.let { it.resource ?: it.local }
                return ReplyHeader(
                    name ?: context.getString(R.string.tatrak_reply),
                    fallbackQuote(message, reply),
                    0,
                    null,
                )
            }
            val name = UIHelper.getMessageDisplayName(parent)
            val text =
                if (isAttachment(parent) || parent.isGeoUri || parent.encryption == Message.ENCRYPTION_PGP ||
                    parent.type == Message.TYPE_RETRACTED || parent.type == Message.TYPE_HIDDEN) {
                    UIHelper.getMessagePreview(context, parent).first.toString()
                } else {
                    parent.bodyWithoutReplyFallback
                }
            return ReplyHeader(
                name,
                text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                UIHelper.getColorForName(name),
                parent.uuid,
                hasReplyThumbnail(parent),
            )
        }

        private fun info(base: MessageItem, text: CharSequence) =
            base.copy(kind = MessageKind.INFO, text = AnnotatedString(text.toString()))

        private fun text(
            message: Message,
            outgoing: Boolean,
            source: String? = message.bodyWithoutReplyFallback,
        ): AnnotatedString {
            val body = source ?: return EMPTY
            if (body.isBlank()) return EMPTY
            val palette = params.palette
            val isPrivate = message.isPrivateMessage
            val highlight = if (!outgoing) highlightNick else null
            val meNick =
                if (message.hasMeCommand()) UIHelper.getMessageDisplayName(message) else null
            val privateTo = if (isPrivate && outgoing) message.counterpart?.resource else null
            // resolved before the lookup: the key follows the language it is in
            val privateMarker =
                if (!isPrivate) {
                    null
                } else if (!outgoing) {
                    context.getString(R.string.private_message)
                } else {
                    context.getString(R.string.private_message_to, privateTo ?: "")
                }
            val key =
                // the whole body, not its hash: an edit to a colliding text must not show the old one
                "${message.uuid}|$outgoing|${palette.hashCode()}|$privateMarker|" +
                    "${highlight?.pattern()}|$meNick|$body"
            return textCache.getOrPut(key) {
                MessageFormatter.format(
                        body,
                        if (outgoing) palette.textOutgoing else palette.textIncoming,
                        if (outgoing) palette.secondaryOutgoing else palette.secondaryIncoming,
                        meNick = meNick,
                        privateMarker = privateMarker,
                        highlightNick = highlight,
                    )
                    .toAnnotatedString(linkStyles)
            }
        }

        private fun call(message: Message, outgoing: Boolean): CallInfo {
            val status = RtpSessionStatus.of(message.body)
            val received = !outgoing
            val missed = received && !status.successful && status.duration <= 0
            val title =
                context.getString(
                    when {
                        !received -> R.string.outgoing_call
                        missed -> R.string.missed_call
                        else -> R.string.incoming_call
                    }
                )
            return CallInfo(
                title = title,
                duration =
                    if (status.duration > 0) TimeFrameUtils.resolve(context, status.duration) else null,
                missed = missed,
                icon = RtpSessionStatus.getDrawable(received, status.successful),
            )
        }

        private fun statusText(message: Message): String? =
            when (message.status) {
                Message.STATUS_SEND_FAILED -> {
                    val parts = message.errorMessage.orEmpty().split("\u001f", limit = 2)
                    if (parts.size == 2 && parts[0] == "file-too-large") {
                        context.getString(R.string.file_too_large)
                    } else {
                        null
                    }
                }
                Message.STATUS_UNSEND ->
                    message.transferable?.let {
                        context.getString(R.string.sending_file, it.progress)
                    }
                else -> null
            }

        private fun deliveryStatus(message: Message): DeliveryStatus =
            when (message.status) {
                Message.STATUS_WAITING,
                Message.STATUS_UNSEND -> DeliveryStatus.SENDING
                Message.STATUS_SEND -> DeliveryStatus.SENT
                Message.STATUS_SEND_RECEIVED ->
                    if ((isGroup && !message.isPrivateMessage) || conversation.contact.isSelf) DeliveryStatus.SENT
                    else DeliveryStatus.DELIVERED
                Message.STATUS_SEND_DISPLAYED -> DeliveryStatus.READ
                Message.STATUS_SEND_FAILED ->
                    if (Message.ERROR_MESSAGE_CANCELLED == message.errorMessage) {
                        DeliveryStatus.CANCELLED
                    } else {
                        DeliveryStatus.FAILED
                    }
                Message.STATUS_OFFERED -> DeliveryStatus.OFFERED
                else -> DeliveryStatus.NONE
            }

        /** [status]: AxolotlService.getFingerprintTrust of an OMEMO message's key, else null. */
        private fun security(message: Message, status: FingerprintStatus?): Security {
            val encryption = message.encryption
            if (encryption == Message.ENCRYPTION_NONE) return Security.NONE
            if (status != null && status.isVerified) return Security.VERIFIED
            return Security.ENCRYPTED
        }

        private val ownFingerprint by lazy { conversation.account.axolotlService?.ownFingerprint }

        /** [axolotl]: the trust looked up for an ENCRYPTION_AXOLOTL message (maybe null), reused here. */
        private fun keyInfo(message: Message, axolotl: FingerprintStatus?): KeyInfo {
            val method = CryptoHelper.encryptionTypeToText(message.encryption)
            val fingerprint = message.fingerprint
            if (method != R.string.encryption_choice_omemo || fingerprint == null || fingerprint.length <= 2) {
                return KeyInfo(method)
            }
            // looked up already for ENCRYPTION_AXOLOTL (a null is not cached, so not again); a message
            // that failed to decrypt can still name its key
            val status =
                if (message.encryption == Message.ENCRYPTION_AXOLOTL) axolotl
                else conversation.account.axolotlService?.getFingerprintTrust(fingerprint)
            val trust = keyTrustLabel(status, own = fingerprint == ownFingerprint)
            return KeyInfo(method, trust, fingerprintLines(fingerprint))
        }

        private fun reactions(message: Message): List<ReactionChip> {
            if (message.reactions.isEmpty()) return emptyList()
            val aggregated = message.aggregatedReactions
            return aggregated.reactions.map {
                ReactionChip(it.key, it.value, aggregated.ourReactions.contains(it.key))
            }
        }

        private fun avatar(message: Message): Bitmap? {
            val key = "${message.occupantId}|${message.counterpart}|${message.trueCounterpart}"
            return avatars.getOrPut(key) {
                service.avatarService.get(message, params.avatarSize, params.avatarsCachedOnly)
            }
        }

        /** Visibility rules of ConversationFragment.populateContextMenu for the ported items. */
        private fun actions(message: Message): MessageActions {
            if (message.type == Message.TYPE_RETRACTED) return MessageActions(canDelete = true)
            if (
                message.type == Message.TYPE_STATUS ||
                    message.type == Message.TYPE_RTP_SESSION ||
                    message.encryption == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE ||
                    message.encryption == Message.ENCRYPTION_AXOLOTL_FAILED ||
                    message.encryption == Message.ENCRYPTION_IMPORTED_PQ_UNAVAILABLE ||
                    message.encryption == Message.ENCRYPTION_IMPORTED_PQ_FAILED
            ) {
                return MessageActions.NONE
            }
            val transferable = message.transferable
            if (
                message.status == Message.STATUS_RECEIVED &&
                    transferable != null &&
                    (transferable.status == Transferable.STATUS_CANCELLED ||
                        transferable.status == Transferable.STATUS_FAILED)
            ) {
                return MessageActions.NONE
            }
            val deleted = message.isDeleted
            val encrypted =
                message.encryption == Message.ENCRYPTION_DECRYPTION_FAILED ||
                    message.encryption == Message.ENCRYPTION_PGP
            val receiving =
                message.status == Message.STATUS_RECEIVED &&
                    (transferable is JingleFileTransferConnection ||
                        transferable is HttpDownloadConnection)
            val unInitiatedButKnownSize = MessageUtils.unInitiatedButKnownSize(message)
            val showError =
                message.status == Message.STATUS_SEND_FAILED &&
                    message.errorMessage != null &&
                    Message.ERROR_MESSAGE_CANCELLED != message.errorMessage
            val singleOrOccupantId =
                !isGroup ||
                    (conversation.mucOptions.occupantId() &&
                        conversation.mucOptions.participating())
            val reactionBase =
                message.status != Message.STATUS_SEND_FAILED && !deleted && singleOrOccupantId
            val reactionsRemaining =
                message.status == Message.STATUS_SEND_FAILED ||
                    !isGroup ||
                    Restrictions.reactionsPerUserRemaining(message)
            val textual =
                !message.isFileOrImage &&
                    !encrypted &&
                    !message.isGeoUri &&
                    !message.treatAsDownloadable() &&
                    !unInitiatedButKnownSize &&
                    transferable == null
            // ConversationFragment.populateContextMenu: cancelable / save_file
            val cancelable =
                (transferable != null && !deleted) ||
                    (message.status == Message.STATUS_WAITING ||
                        message.status == Message.STATUS_UNSEND ||
                        message.status == Message.STATUS_OFFERED) && message.needsUploading()
            val path = message.relativeFilePath
            val mime = if (message.isFileOrImage) message.mimeType else null
            val firstUri = if (textual) Iterables.getFirst(Linkify.getLinks(message.body), null) else null
            return MessageActions(
                canDelete = true,
                canViewHistory = message.hasEditHistory(),
                canShowError = showError,
                canOpenWith =
                    (message.isGeoUri && GeoHelper.isResolveUniversalUri(context, message)) ||
                        (mime != null && mime.startsWith("audio/")),
                canDeleteFile = message.isFileOrImage && !deleted && !cancelable && path != null,
                canCopyUrl =
                    message.encryption == Message.ENCRYPTION_NONE &&
                        (message.hasFileOnRemoteHost() ||
                            message.treatAsDownloadable() ||
                            unInitiatedButKnownSize ||
                            transferable is HttpDownloadConnection),
                copyLinkLabel =
                    firstUri?.let {
                        when (it.scheme) {
                            "xmpp" -> R.string.copy_jabber_id
                            "http", "https", "gemini" -> R.string.copy_link
                            "geo" -> R.string.copy_geo_uri
                            "tel" -> R.string.copy_telephone_number
                            "mailto" -> R.string.copy_email_address
                            else -> R.string.copy_URI
                        }
                    },
                canReply =
                    !showError &&
                        !encrypted &&
                        // a private message is answered privately (to its sender or recipient): only with that full JID
                        !(message.isPrivateMessage && message.counterpart?.isFullJid != true) &&
                        if (isAttachment(message)) {
                            // a quote alone ("> image") would say nothing without the reference
                            replyId(message, isGroup) != null
                        } else {
                            textual && !message.body.isNullOrBlank()
                        },
                canCopy = textual,
                canEdit =
                    !showError &&
                        message.status != Message.STATUS_RECEIVED &&
                        message.acceptMessageCorrection() &&
                        singleOrOccupantId,
                canRetry = message.status == Message.STATUS_SEND_FAILED,
                canShare =
                    (message.isFileOrImage && !deleted && !receiving) ||
                        (message.type == Message.TYPE_TEXT &&
                            !message.treatAsDownloadable() &&
                            !unInitiatedButKnownSize &&
                            transferable == null),
                canReact = reactionBase && reactionsRemaining,
                canRemoveReaction = reactionBase,
                canDownload = message.isFileOrImage && deleted && message.hasFileOnRemoteHost(),
                canSave =
                    message.isFileOrImage && !deleted && !cancelable && path != null &&
                        !path.sharedStorage(),
                canCancel = cancelable,
            )
        }
    }

    /** Copy of MessageAdapter.merge: same direction, encryption, sender and within the window. */
    fun merge(a: Message, b: Message): Boolean {
        if (a is Message.MessageVersion || b is Message.MessageVersion) return false
        if (viewType(a) != viewType(b)) return false
        val receivedA = a.status == Message.STATUS_RECEIVED
        val receivedB = b.status == Message.STATUS_RECEIVED
        if (receivedA != receivedB) return false
        if (
            homogenizedEncryption(a.encryption) != homogenizedEncryption(b.encryption) ||
                !Objects.equal(a.fingerprint, b.fingerprint)
        ) {
            return false
        }
        if (
            a.conversation.mode == Conversation.MODE_MULTI && a.status == Message.STATUS_RECEIVED
        ) {
            val occupantIdA = a.occupantId
            val occupantIdB = b.occupantId
            if (occupantIdA != null && occupantIdB != null && occupantIdA != occupantIdB) {
                return false
            }
            val counterpartA = a.counterpart
            if (counterpartA == null || counterpartA != b.counterpart) return false
        }
        return b.timeSent - a.timeSent <= Config.MESSAGE_MERGE_WINDOW
    }

    private fun viewType(message: Message): Int =
        when {
            message.type == Message.TYPE_STATUS -> 0
            message.type == Message.TYPE_RTP_SESSION -> 1
            message.status <= Message.STATUS_RECEIVED -> 2
            else -> 3
        }

    private fun homogenizedEncryption(encryption: Int): Int =
        when (encryption) {
            Message.ENCRYPTION_AXOLOTL,
            Message.ENCRYPTION_AXOLOTL_FAILED,
            Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE -> Message.ENCRYPTION_AXOLOTL
            Message.ENCRYPTION_PGP,
            Message.ENCRYPTION_DECRYPTED,
            Message.ENCRYPTION_DECRYPTION_FAILED -> Message.ENCRYPTION_PGP
            else -> encryption
        }

    private val EMPTY = AnnotatedString("")
}

/** A file, image or file offered for download: its body is the file URL, for OMEMO with the key. */
internal fun isAttachment(message: Message): Boolean =
    message.isFileOrImage || message.treatAsDownloadable() || MessageUtils.unInitiatedButKnownSize(message)

/** A reply to [message] shows its picture: a local image or video, not a sticker (Telegram shows none for those). */
internal fun hasReplyThumbnail(message: Message): Boolean {
    val params = message.fileParams
    return ChatListSnapshot.previewIsFileDescription(message) &&
        hasThumbnail(message.mimeType, params.width, params.height) &&
        !isSticker(message, params)
}

/** What a reply quotes of [message]: an attachment by its kind ("image"), never by its URL. */
internal fun replyQuote(context: Context, message: Message): String =
    if (isAttachment(message)) {
        UIHelper.getFileDescriptionString(context, message)
    } else {
        MessageUtils.prepareQuote(message)
    }

/**
 * XEP-0461 id of [parent]: the room's stanza-id in channels, the message id otherwise (the rule
 * ReactionManager uses); null when there is none yet.
 */
internal fun replyId(parent: Message, group: Boolean): String? =
    if (group && !parent.isPrivateMessage) parent.serverMsgId else parent.messageId

/** The first quoted line of the XEP-0428 fallback, without its "> " markers. */
internal fun fallbackQuote(message: Message, reply: ReplyTo): String {
    val body = message.body ?: return ""
    val quote = body.removeSuffix(reply.stripFallback(body)).ifEmpty { return "" }
    return quote.lineSequence()
        .map { it.trimStart('>', ' ') }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
}

/**
 * Stickers (Telegram, Cheogram packs, our own [StickerStore]) are webp at most 512 px; nothing in
 * the stanza says "sticker" when it travels as an ordinary (possibly OMEMO) file, so the shape
 * of the file is the signal. png is not: a small png is a photo or screenshot and keeps its bubble.
 */
internal fun isSticker(message: Message, params: Message.FileParams): Boolean {
    val mime = message.mimeType ?: return false
    return mime == "image/webp" &&
        params.width in 1..StickerMaxPx &&
        params.height in 1..StickerMaxPx
}

/** A retracted message keeps its edit ids on purpose, but its placeholder is not "edited". */
internal fun showsEdited(message: Message): Boolean =
    message.edited() && message.type != Message.TYPE_RETRACTED && message.type != Message.TYPE_HIDDEN

/** The classic snackbar's blocked / add-back / allow cases. */
internal fun subscriptionBanner(blocked: Boolean, pendingRequest: Boolean, inRoster: Boolean): ChatBanner? =
    when {
        blocked -> ChatBanner.BLOCKED
        !pendingRequest -> null
        !inRoster -> ChatBanner.ADD_BACK
        else -> ChatBanner.ALLOW_SUBSCRIPTION
    }

/** The rest of ConversationFragment.updateSnackBar, in its order; each check runs only when reached. */
internal fun laterBanner(
    groupOffline: Boolean,
    groupError: () -> ChatBanner?,
    pgpPending: () -> Boolean,
    stranger: () -> Boolean,
    connected: () -> Boolean,
): ChatBanner? =
    when {
        groupOffline -> groupError()
        pgpPending() -> ChatBanner.PGP_PENDING
        stranger() -> ChatBanner.STRANGER
        // Tatrak: where the classic screen shows nothing while offline
        !connected() -> ChatBanner.CONNECTING
        else -> null
    }

/** ConversationFragment.updateSnackBar for a group not joined while the account is online. */
internal fun mucBanner(error: MucOptions.Error, receivedMessages: Boolean): ChatBanner? =
    when (error) {
        MucOptions.Error.NICK_IN_USE -> ChatBanner.NICK_IN_USE
        MucOptions.Error.NO_RESPONSE -> ChatBanner.JOINING
        MucOptions.Error.SERVER_NOT_FOUND ->
            if (receivedMessages) ChatBanner.SERVER_NOT_FOUND_RETRY else ChatBanner.SERVER_NOT_FOUND_LEAVE
        MucOptions.Error.REMOTE_SERVER_TIMEOUT ->
            if (receivedMessages) ChatBanner.SERVER_TIMEOUT_RETRY else ChatBanner.SERVER_TIMEOUT_LEAVE
        MucOptions.Error.PASSWORD_REQUIRED -> ChatBanner.PASSWORD_REQUIRED
        MucOptions.Error.BANNED -> ChatBanner.BANNED
        MucOptions.Error.MEMBERS_ONLY -> ChatBanner.MEMBERS_ONLY
        MucOptions.Error.RESOURCE_CONSTRAINT -> ChatBanner.RESOURCE_CONSTRAINT
        MucOptions.Error.KICKED -> ChatBanner.KICKED
        MucOptions.Error.TECHNICAL_PROBLEMS -> ChatBanner.TECHNICAL_PROBLEMS
        MucOptions.Error.UNKNOWN -> ChatBanner.UNKNOWN_ERROR
        MucOptions.Error.INVALID_NICK -> ChatBanner.INVALID_NICK
        MucOptions.Error.SHUTDOWN -> ChatBanner.SHUTDOWN
        MucOptions.Error.DESTROYED -> ChatBanner.DESTROYED
        MucOptions.Error.NON_ANONYMOUS -> ChatBanner.NON_ANONYMOUS
        MucOptions.Error.NONE -> null
    }

/** ConversationFragment.showLoadMoreMessages; [queryInProgress] is asked last, as there. */
internal fun loadMoreRow(mam: Boolean, lastClearHistory: Long?, emptyWithHistoryLeft: Boolean, queryInProgress: () -> Boolean): Boolean =
    mam && ((lastClearHistory != null && lastClearHistory != 0L) || (emptyWithHistoryLeft && !queryInProgress()))

/** MessageAdapter's DND and local-time rows under ConversationFragment.updateStatusMessages's conditions. */
internal fun contactStatusRow(
    context: Context,
    name: CharSequence,
    dnd: Boolean,
    contactTime: ZonedDateTime?,
    noRecentMessages: Boolean,
    day: String = "",
): ServiceItem? =
    when {
        dnd && noRecentMessages ->
            ServiceItem(
                "contact-status",
                day,
                AnnotatedString(context.getString(R.string.contact_has_paused_their_notifications, name)),
                R.drawable.ic_notifications_paused_24dp,
            )
        contactTime != null &&
            EntityTimeManager.isDifferentTimeZone(contactTime) &&
            EntityTimeManager.isNightTime(contactTime) &&
            noRecentMessages -> {
            // the time is taken when shown and set in bold, as MessageAdapter does
            val time = SpannableString(Instant.now().atZone(contactTime.offset).toLocalTime().truncatedTo(ChronoUnit.MINUTES).toString())
            time.setSpan(StyleSpan(Typeface.BOLD), 0, time.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val text = SpannedString(TextUtils.expandTemplate(context.getString(R.string.its_time_for_contact), time, name))
            ServiceItem("contact-status", day, text.toAnnotatedString(), R.drawable.ic_schedule_24dp)
        }
        else -> null
    }

internal const val StickerMaxPx = 512
