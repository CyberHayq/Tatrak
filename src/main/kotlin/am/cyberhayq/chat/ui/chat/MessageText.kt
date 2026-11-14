package am.cyberhayq.chat.ui.chat

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.annotation.ColorInt
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.gultsch.common.Linkify
import eu.siacs.conversations.Config
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.util.QuoteHelper
import eu.siacs.conversations.utils.Emoticons
import eu.siacs.conversations.utils.StylingHelper
import java.util.regex.Pattern

/** Marks one level of a block quote; overlapping marks give the nesting depth. */
class QuoteMark

/** String annotation tag carrying the quote depth of a paragraph run. */
const val QUOTE_TAG = "tatrak.quote"

private val QuoteIndent = 11.sp

/**
 * The body formatting of the classic message list (MessageAdapter.displayTextMessage), minus the
 * TextView: meant to run on a background thread. The result is converted with
 * [toAnnotatedString].
 */
object MessageFormatter {

    /**
     * @param meNick replaces a leading "/me " when not null
     * @param privateMarker "Private message" / "to nick" prefix for MUC private messages
     * @param highlightNick own nick to highlight in received group messages
     */
    fun format(
        rawBody: String,
        @ColorInt textColor: Int,
        @ColorInt secondaryColor: Int,
        meNick: String? = null,
        privateMarker: String? = null,
        highlightNick: Pattern? = null,
    ): SpannableStringBuilder {
        val trimmed = rawBody.trim()
        val body =
            if (trimmed.length > Config.MAX_DISPLAY_MESSAGE_CHARS) {
                SpannableStringBuilder(trimmed, 0, Config.MAX_DISPLAY_MESSAGE_CHARS).append("…")
            } else {
                SpannableStringBuilder(trimmed)
            }
        if (meNick != null && body.startsWith(Message.ME_COMMAND)) {
            body.replace(0, Message.ME_COMMAND.length, "$meNick ")
        }
        val startsWithQuote = markQuotes(body)
        if (privateMarker == null) {
            if (meNick != null) {
                body.setSpan(StyleSpan(Typeface.BOLD_ITALIC), 0, meNick.length, EXCLUSIVE)
            }
        } else {
            body.insert(0, privateMarker)
            val markerEnd = privateMarker.length
            body.insert(markerEnd, if (startsWithQuote) "\n" else " ")
            body.setSpan(ForegroundColorSpan(secondaryColor), 0, markerEnd, EXCLUSIVE)
            body.setSpan(StyleSpan(Typeface.BOLD), 0, markerEnd, EXCLUSIVE)
            if (meNick != null) {
                body.setSpan(
                    StyleSpan(Typeface.BOLD_ITALIC),
                    markerEnd + 1,
                    markerEnd + 1 + meNick.length,
                    EXCLUSIVE,
                )
            }
        }
        if (highlightNick != null) {
            val matcher = highlightNick.matcher(body)
            while (matcher.find()) {
                body.setSpan(StyleSpan(Typeface.BOLD), matcher.start(), matcher.end(), EXCLUSIVE)
            }
        }
        val emoji = Emoticons.getEmojiPattern(body).matcher(body)
        while (emoji.find()) {
            if (emoji.start() < emoji.end()) {
                body.setSpan(RelativeSizeSpan(1.2f), emoji.start(), emoji.end(), EXCLUSIVE)
            }
        }
        StylingHelper.format(body, textColor)
        Linkify.addLinks(body)
        return body
    }

    /**
     * Same line walk as MessageAdapter.handleTextQuotes: strips one level of `>` per pass and
     * marks the quoted lines. No divider lines are inserted; spacing is up to the renderer.
     */
    fun markQuotes(body: SpannableStringBuilder): Boolean {
        var startsWithQuote = false
        var depth = 1
        while (QuoteHelper.bodyContainsQuoteStart(body) && depth <= Config.QUOTE_MAX_DEPTH) {
            var previous = '\n'
            var lineStart = -1
            var lineTextStart = -1
            var quoteStart = -1
            var i = 0
            while (i <= body.length) {
                val current = if (i < body.length) body[i] else '\n'
                if (lineStart == -1) {
                    if (previous == '\n') {
                        if (i < body.length && QuoteHelper.isPositionQuoteStart(body, i)) {
                            lineStart = i
                            if (quoteStart == -1) quoteStart = i
                            if (i == 0) startsWithQuote = true
                        } else if (quoteStart >= 0) {
                            body.setSpan(QuoteMark(), quoteStart, i - 1, EXCLUSIVE)
                            quoteStart = -1
                        }
                    }
                } else {
                    if (current != ' ' && lineTextStart == -1) {
                        lineTextStart = i
                    }
                    if (current == '\n') {
                        body.delete(lineStart, lineTextStart)
                        i -= lineTextStart - lineStart
                        if (i == lineStart) {
                            // a span over an empty line would not be visible
                            body.insert(i++, " ")
                        }
                        lineStart = -1
                        lineTextStart = -1
                    }
                }
                previous = current
                i++
            }
            if (quoteStart >= 0) {
                body.setSpan(QuoteMark(), quoteStart, body.length, EXCLUSIVE)
            }
            depth++
        }
        return startsWithQuote
    }

    private const val EXCLUSIVE = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
}

/**
 * Converts the platform spans produced by [MessageFormatter] (and Linkify/StylingHelper) into an
 * [AnnotatedString]. Unknown spans are dropped. [QuoteMark]s become indented paragraphs tagged
 * with [QUOTE_TAG] so [MessageText] can draw the quote bars; the line breaks at the edges of a
 * quote are dropped because a paragraph boundary already breaks the line (keeping them would
 * render an empty line).
 */
fun Spanned.toAnnotatedString(
    linkStyles: TextLinkStyles? = null,
    quoteIndent: TextUnit = QuoteIndent,
): AnnotatedString {
    val source = this
    val raw = source.toString()
    val spans = source.getSpans(0, source.length, Any::class.java)
    val sourceDepths = IntArray(raw.length)
    for (span in spans) {
        if (span !is QuoteMark) continue
        val end = minOf(source.getSpanEnd(span), raw.length)
        for (i in maxOf(0, source.getSpanStart(span)) until end) sourceDepths[i]++
    }
    // index in the result for every index of the source (one extra slot for span ends)
    val map = IntArray(raw.length + 1)
    val text = StringBuilder(raw.length)
    val depthList = ArrayList<Int>(raw.length)
    for (i in raw.indices) {
        map[i] = text.length
        if (raw[i] == '\n') {
            val before = if (i > 0) sourceDepths[i - 1] else 0
            val after = if (i + 1 < raw.length) sourceDepths[i + 1] else 0
            if (before != after) continue
        }
        text.append(raw[i])
        depthList += sourceDepths[i]
    }
    map[raw.length] = text.length
    val depths = depthList.toIntArray()
    return buildAnnotatedString {
        append(text.toString())
        for (span in spans) {
            val start = map[source.getSpanStart(span).coerceIn(0, raw.length)]
            val end = map[source.getSpanEnd(span).coerceIn(0, raw.length)]
            if (end <= start) continue
            when (span) {
                is StyleSpan -> addStyle(span.toSpanStyle(), start, end)
                is StrikethroughSpan ->
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                is UnderlineSpan ->
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                is TypefaceSpan -> addStyle(SpanStyle(fontFamily = span.toFontFamily()), start, end)
                is ForegroundColorSpan ->
                    addStyle(SpanStyle(color = Color(span.foregroundColor)), start, end)
                is BackgroundColorSpan ->
                    addStyle(SpanStyle(background = Color(span.backgroundColor)), start, end)
                is RelativeSizeSpan -> addStyle(SpanStyle(fontSize = span.sizeChange.em), start, end)
                is URLSpan -> addLink(LinkAnnotation.Url(span.url, linkStyles), start, end)
            }
        }
        var runStart = 0
        while (runStart < depths.size) {
            val depth = depths[runStart]
            var runEnd = runStart + 1
            while (runEnd < depths.size && depths[runEnd] == depth) runEnd++
            if (depth > 0) {
                val indent = quoteIndent * depth
                addStyle(ParagraphStyle(textIndent = TextIndent(indent, indent)), runStart, runEnd)
                addStringAnnotation(QUOTE_TAG, depth.toString(), runStart, runEnd)
            }
            runStart = runEnd
        }
    }
}

private fun StyleSpan.toSpanStyle(): SpanStyle =
    when (style) {
        Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        Typeface.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
        else -> SpanStyle()
    }

private fun TypefaceSpan.toFontFamily(): FontFamily =
    when (family) {
        "monospace" -> FontFamily.Monospace
        "serif" -> FontFamily.Serif
        "sans-serif" -> FontFamily.SansSerif
        else -> FontFamily.Default
    }

/** Message body text with quote bars drawn next to [QUOTE_TAG] paragraphs. */
@Composable
fun MessageText(
    text: AnnotatedString,
    style: TextStyle,
    quoteColor: Color,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val quotes = remember(text) { text.getStringAnnotations(QUOTE_TAG, 0, text.length) }
    if (quotes.isEmpty()) {
        BasicText(text, modifier, style = style, onTextLayout = onTextLayout)
        return
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text,
        modifier.drawBehind {
            val result = layout ?: return@drawBehind
            val step = QuoteIndent.toPx()
            val barWidth = 2.dp.toPx()
            for (quote in quotes) {
                val depth = quote.item.toIntOrNull() ?: continue
                val top = result.getLineTop(result.getLineForOffset(quote.start))
                val bottom =
                    result.getLineBottom(result.getLineForOffset(maxOf(quote.start, quote.end - 1)))
                for (level in 0 until depth) {
                    drawRect(
                        quoteColor,
                        topLeft = Offset(level * step + 1.dp.toPx(), top + 2.dp.toPx()),
                        size = Size(barWidth, bottom - top - 4.dp.toPx()),
                    )
                }
            }
        },
        style = style,
        onTextLayout = {
            layout = it
            onTextLayout(it)
        },
    )
}
