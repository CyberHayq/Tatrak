package eu.siacs.conversations.ui;

import androidx.annotation.Nullable;
import eu.siacs.conversations.entities.Conversation;

/** A fragment that shows one conversation: the classic {@link ConversationFragment} or the Compose {@link ChatFragment}. */
public interface ConversationHost {
    @Nullable
    Conversation getConversation();
}
