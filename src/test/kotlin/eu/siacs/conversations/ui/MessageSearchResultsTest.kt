package eu.siacs.conversations.ui

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class MessageSearchResultsTest {
    @Test
    fun pendingKeepsPreviousTermsUntilNewResultsArrive() {
        val results = MessageSearchResults(RuntimeEnvironment.getApplication(), CoroutineScope(Dispatchers.Unconfined), { null }, true)
        results.publish(listOf("old"), emptyList())
        assertTrue(results.state.value.searched)
        results.pending()
        assertFalse(results.state.value.searched)
        assertEquals(listOf("old"), results.state.value.terms)
        results.publish(listOf("new"), emptyList())
        assertTrue(results.state.value.searched)
        assertEquals(listOf("new"), results.state.value.terms)
    }
}
