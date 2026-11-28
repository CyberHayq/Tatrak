package am.cyberhayq.chat.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** One block of a release body; [text] still holds inline markup (see [notesMarkup]). */
sealed interface NotesBlock {
    val text: String

    data class Heading(override val text: String) : NotesBlock

    data class Paragraph(override val text: String) : NotesBlock

    data class Bullet(override val text: String, val level: Int) : NotesBlock
}

/** Parses the small Markdown subset the changelog uses: headings, `- ` bullets (one nesting level), paragraphs. */
fun parseReleaseNotes(markdown: String): List<NotesBlock> {
    val blocks = mutableListOf<NotesBlock>()
    var open: NotesBlock? = null // the block still taking continuation lines
    fun close() {
        open?.let(blocks::add)
        open = null
    }
    for (raw in markdown.lines()) { // lines() also splits CRLF
        val line = raw.trim()
        if (line.isEmpty()) {
            close()
            continue
        }
        val hashes = line.takeWhile { it == '#' }.length
        if (hashes in 1..6 && line.getOrNull(hashes) == ' ') {
            close()
            blocks += NotesBlock.Heading(line.substring(hashes + 1).trim())
        } else if (line.startsWith("- ") || line.startsWith("* ")) {
            close()
            val indent = raw.length - raw.trimStart().length
            open = NotesBlock.Bullet(line.substring(2).trim(), if (indent >= 2) 1 else 0)
        } else {
            open = when (val b = open) {
                is NotesBlock.Bullet -> b.copy(text = b.text + " " + line)
                is NotesBlock.Paragraph -> b.copy(text = b.text + " " + line)
                // a heading ends on its own line, so what follows starts a paragraph
                else -> { close(); NotesBlock.Paragraph(line) }
            }
        }
    }
    close()
    return blocks
}

/** `code` -> monospace, **bold** -> bold, [text](url) -> text; a marker without its pair stays as typed. */
internal fun notesMarkup(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val rest = s.substring(i)
        when {
            rest.startsWith("`") && rest.indexOf('`', 1) > 1 -> {
                val end = rest.indexOf('`', 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(rest.substring(1, end)) }
                i += end + 1
            }
            rest.startsWith("**") && rest.indexOf("**", 2) > 2 -> {
                val end = rest.indexOf("**", 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(rest.substring(2, end)) }
                i += end + 2
            }
            rest.startsWith("[") && link(rest) != null -> {
                val (label, length) = link(rest)!!
                append(label)
                i += length
            }
            else -> {
                append(s[i])
                i++
            }
        }
    }
}

/** The label and total length of a leading `[label](url)`, or null. */
private fun link(s: String): Pair<String, Int>? {
    val mid = s.indexOf("](")
    val end = s.indexOf(')', mid + 2)
    return if (mid > 1 && end > 0 && '[' !in s.substring(1, mid)) s.substring(1, mid) to end + 1 else null
}

@Composable
fun ReleaseNotesText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseReleaseNotes(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) {
            when (b) {
                is NotesBlock.Heading ->
                    Text(notesMarkup(b.text), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                is NotesBlock.Paragraph -> Text(notesMarkup(b.text), style = MaterialTheme.typography.bodyMedium)
                is NotesBlock.Bullet ->
                    Row(Modifier.padding(start = if (b.level == 0) 0.dp else 16.dp)) {
                        Text("•", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(notesMarkup(b.text), style = MaterialTheme.typography.bodyMedium)
                    }
            }
        }
    }
}
