package am.cyberhayq.chat.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import eu.siacs.conversations.utils.ImStyleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TextStylingTest {

    @Test
    fun wrapsTheSelectionAndKeepsItSelected() {
        val result = TextFieldValue("say hello now", TextRange(4, 9)).wrapSelection('*')
        assertEquals(TextFieldValue("say *hello* now", TextRange(4, 11)), result)
    }

    @Test
    fun whitespaceAroundTheSelectionStaysOutside() {
        val result = TextFieldValue("say hello now", TextRange(3, 10)).wrapSelection('_')
        assertEquals(TextFieldValue("say _hello_ now", TextRange(3, 12)), result)
    }

    @Test
    fun everyLineIsWrappedOnItsOwnAndBlankLinesAreLeft() {
        val result = TextFieldValue("one\n\n  two \nthree", TextRange(0, 17)).wrapSelection('~')
        assertEquals(TextFieldValue("~one~\n\n  ~two~ \n~three~", TextRange(0, 23)), result)
    }

    @Test
    fun aBackwardsSelectionIsWrappedToo() {
        val result = TextFieldValue("a b c", TextRange(3, 2)).wrapSelection('`')
        assertEquals(TextFieldValue("a `b` c", TextRange(2, 5)), result)
    }

    @Test
    fun aCursorOrABlankSelectionChangesNothing() {
        val cursor = TextFieldValue("abc", TextRange(1))
        assertSame(cursor, cursor.wrapSelection('*'))
        val blank = TextFieldValue("a   b", TextRange(1, 4))
        assertEquals(blank, blank.wrapSelection('*'))
    }

    /** What the bubbles render: each wrapped line is one span of that directive, nested ones too. */
    @Test
    fun theRendererSeesTheSpans() {
        for (marker in listOf('*', '_', '`', '~')) {
            val text = TextFieldValue("x one two\nthree y", TextRange(2, 15)).wrapSelection(marker).text
            assertEquals(listOf("$marker", "$marker"), ImStyleParser.parse(text).map { it.keyword })
        }
        val bold = TextFieldValue("hello", TextRange(0, 5)).wrapSelection('*')
        val both = bold.wrapSelection('_').text
        assertEquals("_*hello*_", both)
        assertEquals(listOf("_", "*"), ImStyleParser.parse(both).map { it.keyword })
    }

    @Test
    fun aSelectionInsideAWordGrowsToTheWord() {
        val inside = TextFieldValue("say hello now", TextRange(5, 8)).wrapSelection('*')
        assertEquals(TextFieldValue("say *hello* now", TextRange(4, 11)), inside)
        val brackets = TextFieldValue("hi (world)!", TextRange(4, 9)).wrapSelection('_')
        assertEquals(TextFieldValue("hi _(world)!_", TextRange(3, 13)), brackets)
        // across lines: only the outer ends grow, every line still wrapped on its own
        val lines = TextFieldValue("one two\nthree four", TextRange(5, 11)).wrapSelection('~')
        assertEquals(TextFieldValue("one ~two~\n~three~ four", TextRange(4, 17)), lines)
    }

    @Test
    fun theRendererSeesTheSpansOfAWordPart() {
        for ((text, range) in listOf("h ello o" to TextRange(3, 5), "hello" to TextRange(1, 4), "(world)" to TextRange(1, 6), "a b(c)d e" to TextRange(4, 5))) {
            val wrapped = TextFieldValue(text, range).wrapSelection('*').text
            assertEquals(wrapped, listOf("*"), ImStyleParser.parse(wrapped).map { it.keyword })
        }
        assertEquals("*hello*", TextFieldValue("hello", TextRange(1, 4)).wrapSelection('*').text)
        assertEquals("*(world)*", TextFieldValue("(world)", TextRange(1, 6)).wrapSelection('*').text)
    }

    /** A no-break space is text to the renderer, so the word around it grows as one and the span is accepted. */
    @Test
    fun aNoBreakSpaceIsPartOfTheWordAsForTheRenderer() {
        val wrapped = TextFieldValue("hello\u00A0world", TextRange(6, 11)).wrapSelection('*').text
        assertEquals("*hello\u00A0world*", wrapped)
        assertEquals(listOf("*"), ImStyleParser.parse(wrapped).map { it.keyword })
    }
}
