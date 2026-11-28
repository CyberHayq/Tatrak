package am.cyberhayq.chat.commands

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.xml.Namespace
import eu.siacs.conversations.xmpp.Jid
import eu.siacs.conversations.xmpp.manager.DiscoManager

/**
 * Where a chat's command list lives, in Cheogram's order (ConversationFragment.refreshCommands,
 * Contact.resourceWhichSupport): the first resource that advertises commands, the bare address when its own disco
 * does (also offline, Cheogram 9b48d181) or the room has the feature, the address when it is a domain (a gateway or
 * a server); else none.
 */
fun commandAddress(address: Jid, resources: List<Pair<Jid, Boolean>>, bareHasCommands: Boolean, roomHasCommands: Boolean): Jid? =
    resources.firstOrNull { it.second }?.first ?: address.takeIf { bareHasCommands || roomHasCommands || it.isDomainJid }

/** [commandAddress] from the cached disco of the chat's contact or room. */
fun commandAddress(conversation: Conversation): Jid? {
    val disco = conversation.account.xmppConnection?.getManager(DiscoManager::class.java) ?: return null
    val address = conversation.address.asBareJid()
    if (conversation.mode == Conversational.MODE_MULTI) {
        return commandAddress(address, emptyList(), false, conversation.mucOptions.hasFeature(Namespace.COMMANDS))
    }
    val presences = conversation.contact.presences
    val infos = disco.get(presences)
    val resources = presences.mapIndexed { i, p -> p.from to (infos.getOrNull(i)?.orNull()?.hasFeature(Namespace.COMMANDS) == true) }
    return commandAddress(address, resources, disco.get(address)?.hasFeature(Namespace.COMMANDS) == true, false)
}
