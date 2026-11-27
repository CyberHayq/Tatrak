package am.cyberhayq.chat.ui.chatlist

import am.cyberhayq.chat.ui.accounts.AccountFilter
import am.cyberhayq.chat.ui.settings.SettingsRow
import android.content.Context
import androidx.compose.ui.graphics.Color
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.xmpp.manager.MessageArchiveManager
import java.util.function.Consumer

/** "Saved messages", the account's chat with itself (public issue #6). */
object SavedMessages {
    /** The self-chat, created if needed: the same call as upstream's "Note to self" (ConversationsOverviewFragment). */
    @JvmStatic
    fun conversation(service: XmppConnectionService, account: Account): Conversation {
        val self = account.selfContact
        return service.findOrCreateConversation(self.account, self.address, false, true)
    }

    /**
     * Called by BindProcessor once per account (its first successful bind): unpinned or deleted later, it stays so.
     * The bind runs on the connection thread after service discovery, or from the service (its wake-up calls
     * XmppConnection.sendDiscoTimeout) when discovery timed out: then MAM may not be known yet, see [fetchHistoryIfEmpty].
     */
    @JvmStatic
    fun pinOnFirstSignIn(service: XmppConnectionService, account: Account) {
        val c = conversation(service, account)
        c.isPinnedOnTop = true
        // pinned inside Tatrak's own archive folder it would stay hidden
        c.tatrakArchivedAt = null
        service.updateConversation(c)
        service.updateConversationUi()
    }

    /**
     * The account the app is filtered to directly, otherwise [pick] (AccountPickerDialog.Enabled, which asks only with
     * several and opens nothing without one). The dialog may answer after [alive] turned false, and its list is a
     * snapshot: an account removed or disabled meanwhile opens nothing.
     */
    fun open(service: XmppConnectionService, pick: (Consumer<Account>) -> Unit, alive: () -> Boolean, open: (Conversation) -> Unit) {
        val only = AccountFilter.writable(service)
        if (only != null) return open(conversation(service, only))
        pick { picked ->
            if (alive() && picked.isEnabled && service.accounts.any { it === picked }) open(conversation(service, picked))
        }
    }

    /**
     * The self-chat shown empty: its first MAM page. postProcessConversation asks for it when the chat is made, but
     * skips it while MAM is not known (a first bind after the disco timeout), and the account catch-up does nothing
     * without a message to start from. Queued on DATABASE_READER behind that post-processing, so its DB load and
     * its own query come first. hasMessagesLeftOnServer is false after a query reached the end and after a clear.
     */
    @JvmStatic
    fun fetchHistoryIfEmpty(c: Conversation) {
        if (c.mode != Conversation.MODE_SINGLE || !c.contact.isSelf) return
        XmppConnectionService.DATABASE_READER.execute {
            val account = c.account
            val mam = account.xmppConnection?.getManager(MessageArchiveManager::class.java) ?: return@execute
            // the checks of postProcessConversation and loadMoreMessages
            if (c.countMessages() == 0 && c.hasMessagesLeftOnServer() && account.isOnlineAndConnected &&
                !c.contact.isBlocked && mam.hasFeature() && !mam.queryInProgress(c)
            ) mam.query(c)
        }
    }

    /** The Profile tab's first row (Telegram has no such row; owner's choice for issue #6). */
    fun profileRow(context: Context, onClick: () -> Unit) =
        SettingsRow(R.drawable.tatrak_ic_bookmark_24dp, context.getString(R.string.tatrak_saved_messages), null, Color(0xFF3E88F7), onClick)
}
