package am.cyberhayq.chat.ui.search

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightTest {

    private fun marked(prefix: String, text: String, terms: List<String>): List<String> {
        val s = highlighted(prefix, text, terms, Color.Red)
        return s.spanStyles.filter { it.item.color == Color.Red }.map { s.text.substring(it.start, it.end) }
    }

    @Test
    fun everyOccurrenceIgnoringCase() {
        assertEquals(listOf("Cafe", "cafe"), marked("", "Cafe or cafe", listOf("cafe")))
    }

    @Test
    fun prefixIsNotSearched() {
        assertEquals(listOf("bob"), marked("Bob: ", "hi bob", listOf("bob")))
    }

    @Test
    fun wildcardsAndKeywordsAreSkipped() {
        assertEquals(listOf("caf"), marked("", "cafe or tea", listOf("caf*", "OR")))
    }
}
