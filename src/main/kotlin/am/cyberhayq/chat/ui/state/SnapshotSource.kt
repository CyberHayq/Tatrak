package am.cyberhayq.chat.ui.state

import android.util.Log
import eu.siacs.conversations.Config
import eu.siacs.conversations.services.XmppConnectionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Turns the service's coarse "something changed" callbacks into immutable UI snapshots.
 *
 * The entities in [eu.siacs.conversations.entities] are mutable and shared with the service
 * threads, so the new UI never holds on to them. Every [invalidate] schedules a rebuild on a
 * background thread; invalidations that arrive while a rebuild is running are conflated into one.
 * Snapshots are data classes, so Compose and DiffUtil only redraw what actually changed.
 *
 * [build] may call `publish` more than once, e.g. a fast pass first and a complete pass after.
 */
class SnapshotSource<T>(
    scope: CoroutineScope,
    initial: T,
    private val build: suspend (service: XmppConnectionService, publish: (T) -> Unit) -> Unit,
) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<T> = mutableState.asStateFlow()

    private val invalidations = Channel<XmppConnectionService>(Channel.CONFLATED)

    init {
        // IO, not Default: builds read avatars and thumbnails from disk
        scope.launch(Dispatchers.IO) {
            var retried = false
            for (service in invalidations) {
                try {
                    build(service) { mutableState.value = it }
                    retried = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    // entities can change under our feet: retry once, not forever on a real bug
                    Log.w(Config.LOGTAG, "unable to build UI snapshot", e)
                    if (!retried) {
                        retried = true
                        delay(500)
                        invalidations.trySend(service)
                    }
                }
            }
        }
    }

    /** Replaces the shown state at once, e.g. with an empty one when the subject changes. */
    fun reset(value: T) {
        mutableState.value = value
    }

    fun invalidate(service: XmppConnectionService) {
        invalidations.trySend(service)
    }
}
