package eu.siacs.conversations.ui

import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.xmpp.manager.PresenceManager

/** The "Add back" / "Allow" snackbar actions, shared by the classic fragment and the new chat UI. */
object SubscriptionRequests {
    /** ConversationFragment.mAddBackClickListener. */
    @JvmStatic
    fun addBack(activity: XmppActivity, contact: Contact) {
        activity.xmppConnectionService.createContact(contact)
        activity.switchToContactDetails(contact)
    }

    /** ConversationFragment.mAllowPresenceSubscription (the caller hides its banner). */
    @JvmStatic
    fun allow(contact: Contact) {
        // the classic screen assumes a connection; a banner can outlive it for a moment
        val connection = contact.account.xmppConnection ?: return
        connection.getManager(PresenceManager::class.java).subscribed(contact.address.asBareJid())
    }
}
