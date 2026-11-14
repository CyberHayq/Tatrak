package am.cyberhayq.chat.ui.state

import android.app.Application
import eu.siacs.conversations.services.XmppConnectionService
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RobolectricConfig(application = Application::class, sdk = [35])
class SnapshotSourceTest {

    private fun run(failures: Int): Pair<Int, String> = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val source =
            SnapshotSource(scope, "initial") { _, publish ->
                if (calls.incrementAndGet() <= failures) throw IllegalStateException("changed")
                publish("built")
            }
        source.invalidate(XmppConnectionService())
        delay(1500)
        scope.cancel()
        calls.get() to source.state.value
    }

    @Test
    fun retriesOnceAfterAFailure() {
        assertEquals(2 to "built", run(failures = 1))
    }

    @Test
    fun doesNotLoopOnAPersistentFailure() {
        assertEquals(2 to "initial", run(failures = Int.MAX_VALUE))
    }
}
