package am.cyberhayq.chat.ui.chat

import androidx.compose.runtime.Immutable
import com.google.common.collect.ImmutableList
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.Reaction
import eu.siacs.conversations.utils.UIHelper

/** One row of the "who reacted" sheet. [who] identifies the reactor for avatar and menu lookups. */
@Immutable
data class ReactorRow(
    val who: String,
    val name: String,
    /** Normalized (VS16), as the chips' emoji. */
    val emoji: String,
    val mine: Boolean,
    /** Another member of a group: the row opens the participant menu. */
    val tappable: Boolean,
    val color: Int,
    val letter: String,
)

/** The reactor's id: "self" for own, "peer" in a one-to-one chat, else occupant-id, real JID or occupant JID. */
internal fun reactorWho(reaction: Reaction, inGroup: Boolean): String =
    when {
        !reaction.received -> "self"
        !inGroup -> "peer"
        else -> reaction.occupantId ?: reaction.trueJid?.asBareJid()?.toString() ?: reaction.from.toString()
    }

/** Rows in the order of message.reactions, one per (who, normalized emoji); [you] names own rows. */
internal fun reactorRows(message: Message, you: String): List<ReactorRow> {
    val conversation = message.conversation as? Conversation ?: return emptyList()
    val multi = conversation.mode == Conversational.MODE_MULTI
    val inGroup = multi && !message.isPrivateMessage
    // a copy: message.reactions may be replaced while we read it
    return ImmutableList.copyOf(message.reactions)
        .map { r ->
            val name =
                when {
                    !r.received -> you
                    // a private message stores only room/nick: the nick then, not whoever holds it now
                    multi && message.isPrivateMessage ->
                        r.from?.resource?.takeIf { it.isNotEmpty() } ?: conversation.mucOptions.getUserOrStub(r).displayName
                    multi -> conversation.mucOptions.getUserOrStub(r).displayName
                    else -> conversation.contact.displayName
                }
            ReactorRow(
                who = reactorWho(r, inGroup),
                name = name,
                emoji = r.normalizedReaction(),
                mine = !r.received,
                tappable = inGroup && r.received,
                color = UIHelper.getColorForName(name),
                letter = name.firstOrNull()?.uppercase() ?: "",
            )
        }
        .distinctBy { it.who to it.emoji }
}

/** null (All) first, then each emoji of [chipOrder] that has rows, with its row count; empty for a single emoji. */
internal fun reactorTabs(rows: List<ReactorRow>, chipOrder: List<String>): List<Pair<String?, Int>> {
    val counts = rows.groupingBy { it.emoji }.eachCount()
    if (counts.size <= 1) return emptyList()
    return listOf<Pair<String?, Int>>(null to rows.size) + chipOrder.mapNotNull { e -> counts[e]?.let { e to it } }
}
