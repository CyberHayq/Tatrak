// Adapted from Cheogram Android (GPL-3.0), Conversation.java CommandSession, commit 494871f:
// rewritten in Kotlin and restructured for Tatrak; not a verbatim copy.
package am.cyberhayq.chat.commands

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.siacs.conversations.xml.Element
import eu.siacs.conversations.xmpp.Jid
import im.conversations.android.xmpp.model.stanza.Iq
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Sends a request and returns the response IQ, result or error; throws when the connection drops. */
fun interface IqSender {
    suspend fun send(iq: Iq): Element
}

/** Tatrak has no per-IQ timeout (Cheogram f790c34e). */
internal const val COMMAND_TIMEOUT_MS = 120_000L
internal const val LOADING_DELAY_MS = 500L
internal const val LONG_DELAY_MS = 3_500L

/**
 * One run of an ad-hoc command (XEP-0050): the current [stage], the user's edits and the request in flight. Lives in
 * the view model's [scope], so rotation keeps it; all calls on the main thread.
 */
class CommandSession(
    val to: Jid,
    val node: String,
    val name: String,
    private val scope: CoroutineScope,
    private val sender: IqSender,
    /** An `xmpp:` or https link from the command (jabber:x:oob). */
    private val openLink: (String) -> Unit,
    private val onClosed: (CommandSession) -> Unit,
    private val timeoutMs: Long = COMMAND_TIMEOUT_MS,
) {
    /** Null until the first answer. */
    var stage by mutableStateOf<CommandStage?>(null)
        private set

    /** A request runs: every press but Cancel is ignored (Cheogram 908d9b46). */
    var executing by mutableStateOf(false)
        private set

    /** The spinner: shown 500 ms into a request, or at once for a grid press (Cheogram a39da8ce). */
    var loading by mutableStateOf(false)
        private set

    /** "This can take a while", 3.5 s after the spinner. */
    var slow by mutableStateOf(false)
        private set

    /** A field was changed on this stage: leaving asks first. */
    var edited by mutableStateOf(false)
        private set

    /** Required fields left empty on the last Next / Complete. */
    var missing by mutableStateOf<Set<String>>(emptySet())
        private set

    var closed = false
        private set

    /** Counts new stages: the screen keys field rows by it, so a row's own state (password shown) starts fresh. */
    var generation = 0
        private set

    private val edits = mutableStateMapOf<String, List<String>>()
    private var job: Job? = null
    private var cancelSent = false

    /** The last session id the server gave while the command runs: a later reply may lack it. */
    private var sessionId: String? = null

    fun start() = send(commandIq(to, node, null, CommandActions.EXECUTE, null), at = false)

    /** What the field holds now: the edit, else what the server sent. */
    fun values(field: FormField): List<String> = edits[field.key] ?: field.values

    fun set(field: FormField, values: List<String>) {
        // the request in flight already carries the old value and its answer clears edits
        if (executing || closed) return
        edits[field.key] = values
        edited = true
        if (field.key in missing) missing = missing - field.key
    }

    /** A button of the grid: sets the lone field and proceeds at once. */
    fun pick(value: String) {
        val grid = stage?.grid ?: return
        if (executing || closed) return
        edits[grid.key] = listOf(value)
        execute(CommandActions.EXECUTE, at = true)
    }

    fun execute(action: String) = execute(action, at = false)

    private fun execute(action: String, at: Boolean) {
        if (closed) return
        when (action) {
            // Close after an error still cancels a session the server may keep (XEP-0050 4.4)
            CommandActions.CLOSE -> return if (stage?.error != null) cancel() else close()
            CommandActions.CANCEL -> return cancel()
        }
        val stage = stage ?: return
        if (executing) return
        if (action != CommandActions.PREV) {
            val empty = stage.fillable.filter { f -> f.required && values(f).all { it.isBlank() } }.map { it.key }.toSet()
            missing = empty
            if (empty.isNotEmpty()) return
        }
        // a finished stage only goes back (Cheogram 0dccd5fc) or closes
        if (stage.status != EXECUTING && action != CommandActions.PREV) return close()
        // prev and cancel never carry the form (Cheogram 5c20049d); no form, none sent (5f311288)
        var sent = action
        val form =
            if (stage.form && action != CommandActions.PREV) {
                val values = HashMap<String, List<String>>(edits)
                stage.fields.firstOrNull { it.variable == ACTIONS_FIELD }?.let { values[it.key] = listOf(action); sent = CommandActions.EXECUTE }
                for (f in stage.fields) if (f.type == "boolean") values[f.key] = listOf(isTrue(values[f.key]?.firstOrNull() ?: f.values.firstOrNull()).toString())
                submitForm(stage, values)
            } else null
        send(commandIq(stage.from ?: to, node, stage.sessionId ?: sessionId, sent, form), at)
    }

    /** Back, Cancel or a chat switch: cancels on the server once, if it still runs there (Cheogram 816e33d9), and closes. */
    fun cancel() {
        if (closed) return
        val id = sessionId
        if (!cancelSent && id != null) {
            cancelSent = true
            val iq = commandIq(stage?.from ?: to, node, id, CommandActions.CANCEL, null)
            // undispatched: the IQ goes out now, also from a scope that is being cleared
            scope.launch(start = CoroutineStart.UNDISPATCHED) { runCatching { withTimeout(timeoutMs) { sender.send(iq) } } }
        }
        close()
    }

    fun close() {
        if (closed) return
        closed = true
        job?.cancel()
        onClosed(this)
    }

    private fun send(iq: Iq, at: Boolean) {
        executing = true
        loading = at
        job =
            scope.launch {
                // coroutines, not java.util.Timer: nothing fires after a cancel (Cheogram 72f66b92, f5aeab7d)
                val timer =
                    launch {
                        if (!at) delay(LOADING_DELAY_MS)
                        loading = true
                        delay(LONG_DELAY_MS)
                        slow = true
                    }
                val next =
                    try {
                        CommandStage.parse(withTimeout(timeoutMs) { sender.send(iq) })
                    } catch (e: TimeoutCancellationException) {
                        CommandStage.error(iq.to, CommandError(null, null, timeout = true))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        CommandStage.error(iq.to, CommandError(null, null))
                    }
                timer.cancel()
                executing = false
                loading = false
                slow = false
                show(next)
            }
    }

    private fun show(next: CommandStage) {
        val current = stage
        when {
            next.status == COMPLETED || next.status == CANCELED || next.error?.gone == true -> sessionId = null
            next.sessionId != null -> sessionId = next.sessionId
        }
        // a rejected submission (bad-payload, not-allowed...) keeps the form and its edits, the error above it
        if (next.error != null && !next.error.timeout && !next.error.gone && current?.status == EXECUTING && current.form) {
            stage = current.copy(error = next.error)
            return
        }
        generation++
        edits.clear()
        edited = false
        missing = emptySet()
        next.link?.let(openLink)
        next.xmppUri?.let(openLink)
        if (next.closes) return close()
        stage = next
    }
}
