package am.cyberhayq.chat.ui.chatlist

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.services.XmppConnectionService

/** Which chat to show in the second pane of the tablet layout when nothing is selected. */
object ChatSuggestions {

    @JvmStatic
    fun first(service: XmppConnectionService?, exclude: Conversation?): Conversation? {
        service ?: return null
        val conversations = ArrayList<Conversation>()
        service.populateWithOrderedConversations(conversations)
        return conversations.firstOrNull { it !== exclude && it.tatrakArchivedAt == null }
    }
}
