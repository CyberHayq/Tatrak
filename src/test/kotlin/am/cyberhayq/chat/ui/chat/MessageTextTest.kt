package am.cyberhayq.chat.ui.chat

import android.app.Application
import android.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import eu.siacs.conversations.Config
import eu.siacs.conversations.ui.ChatFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RobolectricConfig(application = Application::class, sdk = [35])
class MessageTextTest {

    private fun format(body: String, meNick: String? = null): AnnotatedString =
        MessageFormatter.format(body, Color.BLACK, Color.GRAY, meNick = meNick).toAnnotatedString()

    private fun AnnotatedString.styledText(predicate: (androidx.compose.ui.text.SpanStyle) -> Boolean) =
        spanStyles.filter { predicate(it.item) }.map { text.substring(it.start, it.end) }

    @Test
    fun cacheRetainsAFullMessagePass() {
        val cache = FormattedTextCache(2)
        var formatted = 0
        cache.prepare(4)
        repeat(2) {
            repeat(4) { index ->
                cache.getOrPut(index.toString()) { formatted++; AnnotatedString(index.toString()) }
            }
        }
        assertEquals(4, formatted)
    }

    @Test
    fun convertsXep0393Styles() {
        val text = format("*bold* _italic_ ~gone~ `code`")
        assertEquals("*bold* _italic_ ~gone~ `code`", text.text)
        assertEquals(listOf("bold"), text.styledText { it.fontWeight == FontWeight.Bold })
        assertEquals(listOf("italic"), text.styledText { it.fontStyle == FontStyle.Italic })
        assertEquals(listOf("gone"), text.styledText { it.textDecoration == TextDecoration.LineThrough })
        assertEquals(listOf("code"), text.styledText { it.fontFamily == FontFamily.Monospace })
        // the markers stay visible but faded, like in the classic view
        val faded = text.spanStyles.filter { it.item.color.alpha in 0.01f..0.99f }
        assertEquals(8, faded.size)
    }

    @Test
    fun convertsLinks() {
        val text = format("see https://example.com/a?b=1 and xmpp:room@conference.example.org?join")
        val links = text.getLinkAnnotations(0, text.length).map {
            (it.item as LinkAnnotation.Url).url to text.text.substring(it.start, it.end)
        }
        assertEquals(
            listOf(
                "https://example.com/a?b=1" to "https://example.com/a?b=1",
                "xmpp:room@conference.example.org?join" to "xmpp:room@conference.example.org?join",
            ),
            links.sortedBy { text.text.indexOf(it.second) },
        )
    }

    @Test
    fun marksQuotesWithDepth() {
        val text = format("> first level\n>> second level\nanswer")
        // paragraph boundaries break the lines, the line feeds themselves are dropped
        assertEquals("first levelsecond levelanswer", text.text)
        val quotes =
            text.getStringAnnotations(QUOTE_TAG, 0, text.length).map {
                text.text.substring(it.start, it.end).trimEnd() to it.item
            }
        assertEquals(listOf("first level" to "1", "second level" to "2"), quotes)
        assertTrue(text.paragraphStyles.isNotEmpty())
        val answer = text.text.indexOf("answer")
        assertTrue(text.paragraphStyles.none { answer in it.start until it.end })
    }

    @Test
    fun replacesMeCommand() {
        val text = format("/me waves", meNick = "Ani")
        assertEquals("Ani waves", text.text)
        assertEquals(
            listOf("Ani"),
            text.styledText { it.fontWeight == FontWeight.Bold && it.fontStyle == FontStyle.Italic },
        )
    }

    @Test
    fun truncatesVeryLongBodies() {
        val text = format("a".repeat(Config.MAX_DISPLAY_MESSAGE_CHARS + 100))
        assertEquals(Config.MAX_DISPLAY_MESSAGE_CHARS + 1, text.length)
        assertTrue(text.text.endsWith("…"))
    }

    @Test
    fun enlargesEmojiInText() {
        val text = format("nice 👍")
        assertTrue(text.spanStyles.any { text.text.substring(it.start, it.end) == "👍" && it.item.fontSize.value == 1.2f })
    }

    @Test
    fun quoteBlockMatchesInsertAsQuote() {
        assertEquals("> hello\n> world", ChatFragment.quoteBlock("hello\nworld"))
        assertEquals(">> earlier\n> reply", ChatFragment.quoteBlock("> earlier\nreply"))
        assertEquals(">> alt", ChatFragment.quoteBlock("» alt"))
    }
}
