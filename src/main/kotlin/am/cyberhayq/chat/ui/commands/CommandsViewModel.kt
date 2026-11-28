package am.cyberhayq.chat.ui.commands

import am.cyberhayq.chat.commands.COMMAND_TIMEOUT_MS
import am.cyberhayq.chat.commands.CommandSession
import am.cyberhayq.chat.commands.IqSender
import am.cyberhayq.chat.commands.commandAddress
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.xml.Namespace
import eu.siacs.conversations.xmpp.Jid
import eu.siacs.conversations.xmpp.manager.DiscoManager
import im.conversations.android.xmpp.Entity
import im.conversations.android.xmpp.IqErrorException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

@Immutable data class CommandItem(val jid: Jid, val node: String, val name: String)

/** The chat's command list, the sheet and the running session; kept across rotation (ChatFragment's view model). */
class CommandsViewModel : ViewModel() {
    /** The button shows only while this is non-empty (Cheogram 935570ba). */
    var commands by mutableStateOf<List<CommandItem>>(emptyList())
        private set

    var sheet by mutableStateOf(false)

    var session by mutableStateOf<CommandSession?>(null)
        private set

    /** A link from a command for the fragment to open (it outlives the session that sent it). */
    var link by mutableStateOf<String?>(null)

    private var key: Triple<String, Jid?, Boolean>? = null
    private var listJob: Job? = null
    private var recheckedFor: String? = null

    /** The address [commands] came from. */
    private var listedFor: Jid? = null

    /** On chat open and on every roster or presence update; asks the server only when the address or connection changed. */
    fun refresh(conversation: Conversation) {
        if (key?.first != conversation.uuid) {
            listJob?.cancel()
            commands = emptyList()
            sheet = false
            listedFor = null
        }
        val account = conversation.account
        val online = account.isOnlineAndConnected
        val address = if (online) commandAddress(conversation) else null
        val next = Triple(conversation.uuid, address, online)
        if (next == key) return
        key = next
        // offline keeps the list: a start waits for the connection (Cheogram 48b80f26)
        if (!online) return
        listJob?.cancel()
        if (address == null) {
            commands = emptyList()
            // shortcut: a fresh presence's caps arrive after the roster refresh with no UI update of their own,
            // so look once more a little later; replace with a caps-loaded callback if one appears
            if (recheckedFor != conversation.uuid && conversation.mode == Conversational.MODE_SINGLE && conversation.contact.presences.isNotEmpty()) {
                recheckedFor = conversation.uuid
                listJob = viewModelScope.launch {
                    delay(3_000)
                    key = null
                    refresh(conversation)
                }
            }
            return
        }
        // another resource answers now: the old list would send to the old one
        if (address != listedFor) {
            commands = emptyList()
            sheet = false
        }
        listedFor = address
        listJob = viewModelScope.launch { commands = list(account, address) }
    }

    fun open(item: CommandItem, conversation: Conversation) {
        // a pick from a list that was replaced meanwhile
        if (item !in commands) return
        sheet = false
        session?.cancel()
        session =
            CommandSession(item.jid, item.node, item.name, viewModelScope, sender(conversation.account), { link = it }, onClosed = {
                if (session === it) session = null
            }).also { it.start() }
    }

    /** A chat switch or the chat closing for good: the running command is cancelled. */
    fun leave() {
        sheet = false
        session?.cancel()
    }

    override fun onCleared() = leave()

    private suspend fun list(account: Account, address: Jid): List<CommandItem> {
        val disco = account.xmppConnection?.getManager(DiscoManager::class.java) ?: return emptyList()
        return try {
            withTimeout(COMMAND_TIMEOUT_MS) { disco.items(Entity.discoItem(address), Namespace.COMMANDS).await() }.mapNotNull { item ->
                val node = item.node ?: return@mapNotNull null
                CommandItem(item.jid ?: address, node, item.getAttribute("name")?.takeIf(String::isNotBlank) ?: node)
            }
        } catch (e: TimeoutCancellationException) {
            emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a failed list hides the button (Cheogram f0bcf5f4)
            emptyList()
        }
    }

    private fun sender(account: Account) = IqSender { iq ->
        // shortcut: polls for the connection; a start while offline waits within the command timeout
        while (!account.isOnlineAndConnected) delay(500)
        try {
            account.xmppConnection.sendIqPacket(iq).await()
        } catch (e: IqErrorException) {
            e.response
        }
    }
}

private suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        Futures.addCallback(
            this,
            object : FutureCallback<T> {
                override fun onSuccess(result: T) = continuation.resume(result)

                override fun onFailure(t: Throwable) = continuation.resumeWithException(t)
            },
            MoreExecutors.directExecutor(),
        )
        continuation.invokeOnCancellation { cancel(false) }
    }
