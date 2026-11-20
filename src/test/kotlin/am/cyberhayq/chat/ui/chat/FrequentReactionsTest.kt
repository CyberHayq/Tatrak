package am.cyberhayq.chat.ui.chat

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class FrequentReactionsTest {

    private fun after(vararg emojis: String): String? = emojis.fold(null as String?) { raw, e -> FrequentReactions.added(raw, e) }

    @Test
    fun mostUsedFirst() {
        assertEquals(listOf("🔥", "🎉", "🙏"), FrequentReactions.top(after("🙏", "🎉", "🔥", "🎉", "🔥", "🔥")))
    }

    @Test
    fun tiesGoToTheMostRecent() {
        assertEquals(listOf("🙏", "🎉", "🔥"), FrequentReactions.top(after("🔥", "🎉", "🙏")))
        assertEquals(listOf("🔥", "🙏", "🎉"), FrequentReactions.top(after("🔥", "🎉", "🙏", "🎉", "🙏", "🔥")))
    }

    @Test
    fun firstRowIsLeftOutAndSevenShown() {
        // 👍 is in the first row, as is ❤ in either form
        val raw = after("👍", "👍", "❤", "❤️", "🔥", "🎉", "🙏", "👏", "🤝", "😎", "🤔", "💯")
        assertEquals(listOf("💯", "🤔", "😎", "🤝", "👏", "🙏", "🎉"), FrequentReactions.top(raw))
    }

    @Test
    fun capKeepsTheNewcomerAndDropsTheLeastUsed() {
        val many = (0 until 32).map { "e$it" }
        val full = after(*(many + many).toTypedArray())
        val raw = FrequentReactions.added(full, "new")
        val kept = FrequentReactions.parse(raw).map { it.first }
        assertEquals(32, kept.size)
        assertEquals("new", kept.last())
        // all 32 had two uses; the least recent of them (e0) goes
        assertEquals(false, "e0" in kept)
    }

    @Test
    fun malformedLinesAreSkipped() {
        assertEquals(listOf("🔥" to 3), FrequentReactions.parse("🔥 3\n\nbroken\n🎉 x\n🙏 0"))
    }

    @Test
    fun recordStoresTheVs16Form() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // ✔ is one of the emojis normalizeToVS16 gives the VS16 form
        FrequentReactions.record(context, "✔")
        FrequentReactions.record(context, "✔️")
        val raw = PreferenceManager.getDefaultSharedPreferences(context).getString(FrequentReactions.KEY, null)
        assertEquals(listOf("✔️" to 2), FrequentReactions.parse(raw))
    }
}
