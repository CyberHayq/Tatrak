package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.accounts.AccountFilter
import am.cyberhayq.chat.ui.common.AVATAR_SIZE
import am.cyberhayq.chat.ui.search.SearchMenu
import am.cyberhayq.chat.ui.search.SearchResult
import am.cyberhayq.chat.ui.search.SearchState
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.utils.UIHelper
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tatrak: turns the messages of `XmppConnectionService.search` into [SearchResult] rows and loads
 * their avatars. Shared by [SearchActivity] (one chat or all) and the chat list's inline search.
 */
internal class MessageSearchResults(
    private val context: Context,
    private val scope: CoroutineScope,
    private val service: () -> XmppConnectionService?,
    private val global: Boolean,
) {
    private val mutable = MutableStateFlow(SearchState(inChat = !global))
    val state: StateFlow<SearchState> = mutable

    private var messages: Map<String, Message> = emptyMap()
    private var avatars: Job? = null

    fun message(key: String): Message? = messages[key]

    /** Results for [terms], oldest first as the service delivers them; shown newest first. */
    fun publish(terms: List<String>, results: List<Message>) {
        val byKey = LinkedHashMap<String, Message>()
        // the search of all chats shows only the filtered account's; shortcut: the service's result limit applies before
        val only = if (global) service()?.let(AccountFilter::selected) else null
        val entries =
            results.asReversed().filter { AccountFilter.shows(only, it.conversation.account) }.map { message ->
                byKey[message.uuid] = message
                val conversation = message.conversation
                val multi = conversation.mode == Conversational.MODE_MULTI
                SearchResult(
                    key = message.uuid,
                    title = if (global) name(conversation) else UIHelper.getMessageDisplayName(message),
                    prefix =
                        if (global && multi && message.status == Message.STATUS_RECEIVED) {
                            UIHelper.getMessageDisplayName(message) + ": "
                        } else {
                            ""
                        },
                    text = UIHelper.getMessagePreview(context, message).first.toString(),
                    timestamp = message.timeSent,
                    // the classic menu hides copy and quote for locations
                    menu =
                        if (message.isGeoUri) listOf(SearchMenu.OPEN, SearchMenu.SHARE) else SearchMenu.entries,
                )
            }
        messages = byKey
        val previous = mutable.value.results.associate { it.key to it.avatar }
        mutable.update {
            it.copy(
                results = entries.map { r -> r.copy(avatar = previous[r.key]) },
                terms = terms,
                searched = true,
            )
        }
        loadAvatars(byKey)
    }

    fun pending() {
        mutable.update { it.copy(searched = false) }
    }

    fun clear() {
        avatars?.cancel()
        messages = emptyMap()
        mutable.update { SearchState(inChat = it.inChat, chatName = it.chatName) }
    }

    /** Inside one chat: its name, for the hint. */
    fun setChatName(name: String?) {
        mutable.update { it.copy(chatName = name) }
    }

    private fun loadAvatars(messages: Map<String, Message>) {
        val service = service() ?: return
        val size = (AVATAR_SIZE * context.resources.displayMetrics.density).roundToInt()
        avatars?.cancel()
        avatars =
            scope.launch(Dispatchers.IO) {
                // one avatar per chat (global search) or per sender, not per message
                val bySource = HashMap<Any, Bitmap?>()
                val loaded = HashMap<String, Bitmap?>()
                for ((key, message) in messages) {
                    val conversation = message.conversation
                    loaded[key] =
                        if (global && conversation is Conversation) {
                            bySource.getOrPut(conversation) { service.avatarService.get(conversation, size, false) }
                        } else if (global) {
                            bySource.getOrPut(conversation.uuid) { service.avatarService.get(message, size, false) }
                        } else {
                            // inside one chat: our own messages share one avatar
                            val sender =
                                if (message.status == Message.STATUS_RECEIVED) "r:" + message.counterpart
                                else "sent"
                            bySource.getOrPut(sender) { service.avatarService.get(message, size, false) }
                        }
                }
                mutable.update { s ->
                    s.copy(results = s.results.map { r -> if (r.key in loaded) r.copy(avatar = loaded[r.key]) else r })
                }
            }
    }

    companion object {
        fun name(conversation: Conversational): String =
            when {
                conversation is Conversation -> conversation.name.toString()
                conversation.mode == Conversational.MODE_SINGLE -> conversation.contact.displayName
                else -> conversation.address.asBareJid().toString()
            }

        /**
         * Opens [conversation] in [ConversationsActivity] scrolled to [messageUuid] (see
         * [ChatFragment.EXTRA_MESSAGE_UUID]), like `XmppActivity.switchToConversation`.
         */
        @JvmStatic
        fun openAt(context: Context, conversation: Conversation, messageUuid: String) {
            context.startActivity(
                Intent(context, ConversationsActivity::class.java)
                    .setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION)
                    .putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.uuid)
                    .putExtra(ChatFragment.EXTRA_MESSAGE_UUID, messageUuid)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }
}
