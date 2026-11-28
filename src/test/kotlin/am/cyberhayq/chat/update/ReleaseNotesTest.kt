package am.cyberhayq.chat.update

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    @Test
    fun headingWrappedBulletNestedBulletAndParagraph() {
        val md = "Intro line one\nline two\n\n### Interface\n- First bullet that wraps\n  onto a second line\n  - nested\n* star bullet\n\nTail."
        assertEquals(
            listOf(
                NotesBlock.Paragraph("Intro line one line two"),
                NotesBlock.Heading("Interface", 3),
                NotesBlock.Bullet("First bullet that wraps onto a second line", 0),
                NotesBlock.Bullet("nested", 1),
                NotesBlock.Bullet("star bullet", 0),
                NotesBlock.Paragraph("Tail."),
            ),
            parseReleaseNotes(md),
        )
    }

    @Test
    fun crlfAndEmptyInput() {
        assertEquals(listOf(NotesBlock.Heading("A", 1), NotesBlock.Bullet("b c", 0)), parseReleaseNotes("# A\r\n- b\r\n  c\r\n"))
        assertTrue(parseReleaseNotes("").isEmpty())
        assertTrue(parseReleaseNotes("\n  \n").isEmpty())
    }

    /** Skipped releases' notes as Updater.withSkippedNotes joins them: each body's sections sit under its version heading. */
    @Test
    fun versionHeadingsAboveBodySections() {
        assertEquals(
            listOf(
                NotesBlock.Heading("0.6.0-beta.3", 1),
                NotesBlock.Heading("Fixes", 2),
                NotesBlock.Bullet("a", 0),
                NotesBlock.Heading("0.6.0-beta.2", 1),
                NotesBlock.Paragraph("Intro"),
                NotesBlock.Heading("Interface", 3),
                NotesBlock.Bullet("b", 0),
            ),
            parseReleaseNotes("# 0.6.0-beta.3\n## Fixes\n- a\n\n# 0.6.0-beta.2\nIntro\n\n### Interface\n- b"),
        )
    }

    @Test
    fun hashWithoutSpaceIsText() {
        assertEquals(listOf(NotesBlock.Paragraph("#tag")), parseReleaseNotes("#tag"))
    }

    @Test
    fun inlineCodeBoldAndLink() {
        val s = notesMarkup("a `b` **c** [d](http://x) e")
        assertEquals("a b c d e", s.text)
        assertEquals(listOf(2 to 3, 4 to 5), s.spanStyles.map { it.start to it.end }.sortedBy { it.first }.take(2))
        assertEquals(FontFamily.Monospace, s.spanStyles.first { it.start == 2 }.item.fontFamily)
        assertEquals(FontWeight.Bold, s.spanStyles.first { it.start == 4 }.item.fontWeight)
        assertEquals(2, s.spanStyles.size)
    }

    @Test
    fun unmatchedMarkersStayLiteral() {
        assertEquals("a `b", notesMarkup("a `b").text)
        assertEquals("**b", notesMarkup("**b").text)
        assertEquals("[x](y", notesMarkup("[x](y").text)
        assertEquals("[x] (y)", notesMarkup("[x] (y)").text)
        assertEquals(0, notesMarkup("a `b").spanStyles.size)
    }
}
