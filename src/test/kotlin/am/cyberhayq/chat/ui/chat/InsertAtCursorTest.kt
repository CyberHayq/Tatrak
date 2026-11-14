package am.cyberhayq.chat.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class InsertAtCursorTest {

    @Test
    fun insertsAtTheCursor() {
        val result = TextFieldValue("Hi there", TextRange(2)).insertAtCursor("👋")
        assertEquals("Hi👋 there", result.text)
        assertEquals(TextRange(4), result.selection)
    }

    @Test
    fun replacesABackwardsSelection() {
        val result = TextFieldValue("Hi there", TextRange(8, 3)).insertAtCursor("😀")
        assertEquals("Hi 😀", result.text)
        assertEquals(TextRange(5), result.selection)
    }

    @Test
    fun linkIsSeparatedFromTextAroundCursor() {
        val result = TextFieldValue("Hello world", TextRange(3)).insertLinkAtCursor("https://example.org")
        assertEquals("Hel https://example.org lo world", result.text)
        assertEquals(TextRange(24), result.selection)
    }

    @Test
    fun linkReplacesSelectionWithoutAddingRedundantSpaces() {
        val result = TextFieldValue("Hi old friend", TextRange(6, 3)).insertLinkAtCursor("url")
        assertEquals("Hi url friend", result.text)
    }

    @Test
    fun appendsLinkToDraftWithoutRemovingWhitespace() {
        assertEquals("url", appendToDraft("", "url"))
        assertEquals("draft url", appendToDraft("draft", "url"))
        assertEquals("draft\nurl", appendToDraft("draft\n", "url"))
    }
}
