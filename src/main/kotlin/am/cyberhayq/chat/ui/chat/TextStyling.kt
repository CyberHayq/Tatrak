package am.cyberhayq.chat.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Wraps the selection in a XEP-0393 styling directive ('*' bold, '_' italic, '`' monospace, '~' strikethrough, as
 * ImStyleParser renders them). Directives must hug text and spans end at a line break, so each non-blank line is
 * wrapped on its own and the whitespace around it stays outside. An opening directive must follow whitespace or the
 * line start, so a selection that starts or ends inside a word grows to the whole word ("h[ell]o" gives "*hello*",
 * "([world])" gives "*(world)*"). The selection stays on the wrapped text.
 */
internal fun TextFieldValue.wrapSelection(marker: Char): TextFieldValue {
    if (selection.collapsed) return this
    var start = selection.min
    var end = selection.max
    while (start > 0 && !text[start].isSpace() && !text[start - 1].isSpace()) start--
    while (end < text.length && !text[end - 1].isSpace() && !text[end].isSpace()) end++
    val wrapped =
        text.substring(start, end).split('\n').joinToString("\n") { line ->
            val first = line.indexOfFirst { !it.isSpace() }
            if (first < 0) {
                line
            } else {
                val last = line.indexOfLast { !it.isSpace() }
                line.substring(0, first) + marker + line.substring(first, last + 1) + marker + line.substring(last + 1)
            }
        }
    return TextFieldValue(text.replaceRange(start, end, wrapped), TextRange(start, start + wrapped.length))
}

/** ImStyleParser's whitespace (Character.isWhitespace): unlike Kotlin's Char.isWhitespace, a no-break space is text. */
private fun Char.isSpace() = Character.isWhitespace(this)
