package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.accounts.AccountFilter
import am.cyberhayq.chat.ui.calls.CallEntry
import am.cyberhayq.chat.ui.calls.CallLog
import am.cyberhayq.chat.ui.calls.CallRecord
import am.cyberhayq.chat.ui.calls.CallsActions
import am.cyberhayq.chat.ui.calls.CallsScreen
import am.cyberhayq.chat.ui.calls.CallsState
import am.cyberhayq.chat.ui.common.AVATAR_SIZE
import am.cyberhayq.chat.ui.state.SnapshotSource
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.RtpSessionStatus
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.utils.TimeFrameUtils
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

/**
 * Tatrak: the call list, opened from the chat list's side menu (the docks show it as a tab of the
 * chat list instead, from the same [CallList]).
 */
class CallsActivity : XmppActivity(), XmppConnectionService.OnConversationUpdate {

    private val list = CallList(this, RtpCallStarter(this), lifecycleScope, ::finish)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            TatrakTheme {
                val state by list.state.collectAsStateWithLifecycle()
                CallsScreen(state, list)
            }
        }
    }

    override fun onBackendConnected() = list.invalidate()

    override fun refreshUiReal() = list.invalidate()

    /** A new call is a new message in some chat. */
    override fun onConversationUpdate() = refreshUi()
}

/** The chats whose calls the list shows, by uuid: those of the account the app is filtered to. */
internal fun callChats(service: XmppConnectionService, only: Account? = AccountFilter.selected(service)): Map<String, Conversation> =
    ArrayList(service.conversations).filter { AccountFilter.shows(only, it.account) }.associateBy { it.uuid }

/**
 * The call list's data and actions, for [CallsActivity] and the chat list's Calls tab. Calls are
 * the `TYPE_RTP_SESSION` messages of all chats, read with one indexed query (monocles' call list
 * ran one query per chat and parsed whole messages, which took seconds). The last list is kept in
 * memory, so reopening shows it at once while a fresh one is read.
 */
internal class CallList(
    private val activity: XmppActivity,
    private val calls: RtpCallStarter,
    scope: CoroutineScope,
    private val onBack: () -> Unit,
) : CallsActions {

    private val source = SnapshotSource(scope, cache ?: CallsState()) { service, publish -> build(service, publish) }

    val state = source.state

    fun invalidate() {
        source.invalidate(activity.xmppConnectionService ?: return)
    }

    override fun back() = onBack()

    override fun openChat(chat: String) {
        activity.switchToConversation(activity.xmppConnectionService?.findConversationByUuid(chat) ?: return)
    }

    override fun call(chat: String, video: Boolean) {
        calls.call(activity.xmppConnectionService?.findConversationByUuid(chat) ?: return, video)
    }

    private fun build(service: XmppConnectionService, publish: (CallsState) -> Unit) {
        val only = AccountFilter.selected(service)
        val chats = callChats(service, only)
        // a call with a chat that was closed since has no conversation to open or call back from
        val groups = CallLog.group(query(service, only).filter { it.chat in chats })
        val previous = source.state.value.entries.associate { it.chat to it.avatar }
        val entries =
            groups.map { g ->
                val r = g.newest
                CallEntry(
                    key = r.key,
                    chat = r.chat,
                    name = chats.getValue(r.chat).name.toString(),
                    time = r.time,
                    received = r.received,
                    missed = r.missed,
                    count = g.count,
                    duration = if (r.duration > 0) TimeFrameUtils.resolve(activity, r.duration) else null,
                    icon = RtpSessionStatus.getDrawable(r.received, r.successful),
                    avatar = previous[r.chat],
                )
            }
        publish(CallsState(entries, loaded = true).also { cache = it })
        // avatars after the list: the service caches them, so usually this is quick too
        val size = (AVATAR_SIZE * activity.resources.displayMetrics.density).roundToInt()
        val avatars = entries.map { it.chat }.distinct().associateWith { service.avatarService.get(chats.getValue(it), size, false) }
        val withAvatars = CallsState(entries.map { it.copy(avatar = avatars[it.chat]) }, loaded = true)
        if (withAvatars != source.state.value) publish(withAvatars.also { cache = it })
    }

    /**
     * The newest calls of all chats, or of [only]'s chats (filtered before the limit); only the columns the list shows,
     * over `message_type_index`.
     */
    private fun query(service: XmppConnectionService, only: Account?): List<CallRecord> {
        val records = ArrayList<CallRecord>()
        val ofAccount =
            if (only == null) ""
            else " AND ${Message.CONVERSATION} IN (SELECT ${Conversation.UUID} FROM ${Conversation.TABLENAME} WHERE ${Conversation.ACCOUNT}=?)"
        service.databaseBackend.readableDatabase
            .query(
                Message.TABLENAME,
                arrayOf(Message.UUID, Message.CONVERSATION, Message.TIME_SENT, Message.STATUS, Message.BODY),
                "${Message.TYPE}=? AND ${Message.DELETED}=0$ofAccount",
                listOfNotNull(Message.TYPE_RTP_SESSION.toString(), only?.uuid).toTypedArray(),
                null,
                null,
                "${Message.TIME_SENT} DESC",
                LIMIT.toString(),
            )
            .use { c ->
                while (c.moveToNext()) {
                    val status = RtpSessionStatus.of(c.getString(4))
                    records +=
                        CallRecord(
                            key = c.getString(0),
                            chat = c.getString(1),
                            time = c.getLong(2),
                            received = c.getInt(3) == Message.STATUS_RECEIVED,
                            successful = status.successful,
                            duration = status.duration,
                        )
                }
            }
        return records
    }

    companion object {
        private const val LIMIT = 300

        /** The last list shown, so reopening the screen does not start empty. */
        @Volatile private var cache: CallsState? = null
    }
}
