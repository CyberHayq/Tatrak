package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.state.SnapshotSource
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import eu.siacs.conversations.ui.util.Attachment
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.services.XmppConnectionService
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val avatarSize = (34 * application.resources.displayMetrics.density).roundToInt()
    private val textCache = FormattedTextCache()

    /** Read by the snapshot thread; replaced on the main thread. */
    @Volatile private var binding: Binding? = null
    @Volatile private var palette: TextPalette? = null
    /** The chat-list preview's model: snapshots ask nothing of the contact ([SnapshotParams.preview]). */
    @Volatile var preview = false
    private var lastService: XmppConnectionService? = null
    @Volatile private var generation = 0
    /** Makes "still the current chat?" and the publish one step against [bind]'s switch. */
    private val switchLock = Any()

    private data class Binding(
        val conversation: Conversation,
        val generation: Int,
        val unreadAnchorUuid: String?,
        val showLastSeen: Boolean,
        val mentions: MentionTracker = MentionTracker(),
    )

    private val source =
        SnapshotSource(viewModelScope, ChatState()) { service, publish ->
            val binding = binding ?: return@SnapshotSource
            val palette = palette ?: return@SnapshotSource
            // a build for the chat shown before a switch must not replace the new chat's screen
            fun publishIfCurrent(state: ChatState) {
                synchronized(switchLock) { if (binding.generation == generation) publish(state) }
            }
            val replyUuid = reply?.uuid
            fun params(cachedOnly: Boolean) =
                SnapshotParams(
                    palette = palette,
                    unreadAnchorUuid = binding.unreadAnchorUuid,
                    avatarSize = avatarSize,
                    avatarsCachedOnly = cachedOnly,
                    generation = binding.generation,
                    showLastSeen = binding.showLastSeen,
                    mentions = binding.mentions,
                    preview = preview,
                    replyUuid = replyUuid,
                )
            val fast =
                ChatSnapshot.build(application, service, binding.conversation, params(true), textCache)
            publishIfCurrent(fast)
            if (missingAvatar(fast)) {
                publishIfCurrent(
                    ChatSnapshot.build(application, service, binding.conversation, params(false), textCache)
                )
            }
        }

    val state: StateFlow<ChatState> = source.state

    var input by mutableStateOf(TextFieldValue())
    var reply by mutableStateOf<ReplyPreview?>(null)

    /** The group private message's occupant (full JID); set by the fragment together with Conversation.nextCounterpart. */
    var privateTo by mutableStateOf<eu.siacs.conversations.xmpp.Jid?>(null)

    /** Picked attachments waiting for Send, like ConversationFragment's media preview strip. */
    var previews by mutableStateOf<List<Attachment>>(emptyList())

    /** The [previews] that are camera captures encrypted into the store: deleted when discarded unsent. */
    val cameraDrafts = HashSet<UUID>()

    // the chat screen is gone for good (Back to the list): its unsent camera drafts go with it
    override fun onCleared() {
        am.cyberhayq.chat.storage.media.TemporaryMedia.deleteDrafts(cameraDrafts, previews)
    }

    /** A message to scroll to once the list has it; cleared by the screen when done. */
    var focus by mutableStateOf<MessageFocus?>(null)

    /** A short notice for the top of the chat (string resource): history loading. */
    var notice by mutableStateOf<Int?>(null)

    val conversation: Conversation?
        get() = binding?.conversation

    /** Mentions of the bound chat already shown or jumped to ([MentionTracker.seen]). */
    val seenMentions: Set<String>
        get() = binding?.mentions?.seen.orEmpty()

    fun mentionsSeen(uuids: Collection<String>) {
        binding?.mentions?.markSeen(uuids)
    }

    /**
     * Shows [conversation]; the "Unread messages" divider stays where it was when it opened.
     * [fresh] starts over even for the same chat (each chat preview opens anew).
     */
    fun bind(conversation: Conversation, service: XmppConnectionService, fresh: Boolean = false) {
        val current = binding
        if (fresh || current == null || current.conversation !== conversation) {
            val unreadAnchor = conversation.firstUnreadMessage?.uuid
            val showLastSeen = AppSettings(getApplication()).isBroadcastLastActivity
            synchronized(switchLock) {
                generation++
                binding = Binding(conversation, generation, unreadAnchor, showLastSeen)
                notice = null
                // drop the previous chat's messages at once instead of showing them under the new chat
                if (current != null) source.reset(ChatState(generation = generation))
            }
            reply = null
            focus = null
        }
        invalidate(service)
    }

    fun setInput(text: String) {
        input = TextFieldValue(text, TextRange(text.length))
    }

    fun setPalette(palette: TextPalette) {
        if (this.palette != palette) {
            this.palette = palette
            lastService?.let(::invalidate)
        }
    }

    fun invalidate(service: XmppConnectionService) {
        lastService = service
        source.invalidate(service)
    }
}

/** The cached-only snapshot lacks an avatar it should show, so a full pass must follow. */
internal fun missingAvatar(state: ChatState): Boolean =
    state.header.avatar == null ||
        state.items.any {
            (it is MessageItem && it.showAvatar && it.avatar == null) ||
                // a read row always has an avatar ("read-" rows from ChatSnapshot.readMarks)
                (it is ServiceItem && it.key.startsWith("read-") && it.avatar == null)
        }
