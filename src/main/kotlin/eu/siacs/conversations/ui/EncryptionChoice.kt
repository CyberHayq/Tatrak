package eu.siacs.conversations.ui

import android.util.Log
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.crypto.axolotl.AxolotlService
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message

/** The encryption menu's selection, shared by the classic fragment and the new chat UI. */
object EncryptionChoice {
    /**
     * ConversationFragment.handleEncryptionSelection up to the saving: returns whether the choice
     * took effect (the caller checks the item); the caller then refreshes its UI.
     */
    @JvmStatic
    fun select(activity: XmppActivity, conversation: Conversation, itemId: Int): Boolean {
        var checked = false
        val updated: Boolean
        if (itemId == R.id.encryption_choice_none) {
            updated = conversation.setNextEncryption(Message.ENCRYPTION_NONE)
            checked = true
        } else if (itemId == R.id.encryption_choice_pgp) {
            if (activity.hasPgp()) {
                if (conversation.account.pgpSignature != null) {
                    updated = conversation.setNextEncryption(Message.ENCRYPTION_PGP)
                    checked = true
                } else {
                    updated = false
                    activity.announcePgp(conversation.account, conversation, null, activity.onOpenPGPKeyPublished)
                }
            } else {
                activity.showInstallPgpDialog()
                updated = false
            }
        } else if (itemId == R.id.encryption_choice_axolotl) {
            Log.d(
                Config.LOGTAG,
                AxolotlService.getLogprefix(conversation.account) +
                    "Enabled axolotl for Contact " +
                    conversation.contact.address,
            )
            updated = conversation.setNextEncryption(Message.ENCRYPTION_AXOLOTL)
            checked = true
        } else {
            updated = conversation.setNextEncryption(Message.ENCRYPTION_NONE)
        }
        if (updated) {
            activity.xmppConnectionService.updateConversation(conversation)
        }
        return checked
    }
}
