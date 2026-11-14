package am.cyberhayq.chat.ui.chat

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.entities.Message
import java.util.regex.Pattern

/** A group participant offered by the "@" list; [color] is the letter avatar's. */
@Immutable data class MentionCandidate(val nick: String, val color: Int)

/** The "@word" at the cursor: "@" at [start], the word ends at [end], [query] is what is typed before the cursor. */
internal data class MentionQuery(val start: Int, val end: Int, val query: String)

/** The word around a collapsed cursor when it starts with "@" at the start of the text or after whitespace. */
internal fun mentionQuery(value: TextFieldValue): MentionQuery? {
    if (!value.selection.collapsed) return null
    val text = value.text
    val cursor = value.selection.end
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    if (start >= cursor || text[start] != '@') return null
    var end = cursor
    while (end < text.length && !text[end].isWhitespace()) end++
    return MentionQuery(start, end, text.substring(start + 1, cursor))
}

/**
 * Replaces the "@word" with what tab completion (ConversationFragment.onTabPressed) inserts: "nick: " at the start of the
 * message, "nick " elsewhere. The "@" goes: clients highlight the bare nick. Whitespace already after the word is reused,
 * as highlightInConference does.
 */
internal fun TextFieldValue.completeMention(query: MentionQuery, nick: String): TextFieldValue {
    val suffix = if (query.start == 0) ": " else " "
    val spaceFollows = query.end < text.length && Character.isWhitespace(text[query.end])
    val insert = nick + if (spaceFollows) suffix.dropLast(1) else suffix
    val cursor = query.start + insert.length + if (spaceFollows) 1 else 0
    return TextFieldValue(text.replaceRange(query.start, query.end, insert), TextRange(cursor))
}

/**
 * ConversationFragment.highlightInConference on a [TextFieldValue], branch for branch; [knownNicks] is
 * NickValidityChecker.check for the conversation. The selection moves like Android's selection spans do on insert.
 */
internal fun TextFieldValue.highlightInConference(nick: String, knownNicks: (List<String>) -> Boolean): TextFieldValue {
    val pos = selection.start
    // String.trim() of Java: everything up to U+0020 counts as blank
    if (text.trim { it <= ' ' }.isEmpty() || pos == 0) return insertKeepingSelection(0, "$nick: ")
    val before = text[pos - 1]
    val after = if (text.length > pos) text[pos] else '\u0000'
    if (before == '\n') return insertKeepingSelection(pos, "$nick: ")
    if (pos > 2 && text.substring(pos - 2, pos) == ": " &&
        knownNicks(NickSeparator.split(text.substring(0, pos - 2)).toList())) {
        return insertKeepingSelection(pos - 2, ", $nick")
    }
    val inserted =
        insertKeepingSelection(
            pos,
            (if (Character.isWhitespace(before)) "" else " ") + nick + (if (Character.isWhitespace(after)) "" else " "),
        )
    return if (Character.isWhitespace(after)) inserted.copy(selection = TextRange(inserted.selection.start + 1)) else inserted
}

/** String.split(", ") of Java, which drops trailing empty parts. */
private val NickSeparator = Pattern.compile(", ")

/** Editable.insert: a selection edge at or after [at] moves behind the inserted text. */
private fun TextFieldValue.insertKeepingSelection(at: Int, insert: String): TextFieldValue {
    fun shift(point: Int) = if (point >= at) point + insert.length else point
    return TextFieldValue(
        text.substring(0, at) + insert + text.substring(at),
        TextRange(shift(selection.start), shift(selection.end)),
    )
}

/**
 * Telegram's pending mentions of one bound chat: received messages with our nick, by the same pattern the bubbles
 * highlight it with (NotificationService.generateNickHighlightPattern). A message counts once a snapshot has seen it
 * unread (Message.isRead): unread when the chat opened, or arrived while it is open. It stays counted after the list
 * marks it read (Conversation.markRead marks everything below the newest message on screen, seen or not) until the
 * screen reports it in [seen]: shown on screen after the initial scroll, or jumped to. History pages and messages read
 * before the chat opened never count. Lives in the ChatViewModel's binding, so recreation keeps it.
 */
class MentionTracker {
    /** Uuids seen unread with our nick; touched by the snapshot thread only (builds run one at a time). */
    private val tracked = HashSet<String>()

    /** Mentions already shown or jumped to; written on the main thread, read by composition. */
    var seen by mutableStateOf(emptySet<String>())
        private set

    fun markSeen(uuids: Collection<String>) {
        if (!seen.containsAll(uuids)) seen = seen + uuids
    }

    /** The tracked mentions among the loaded [messages], oldest first; [seen] ones included, the screen drops them. */
    fun update(messages: List<Message>, nick: Pattern?): List<String> {
        if (nick == null) return emptyList()
        return messages
            .filter { message ->
                message.status == Message.STATUS_RECEIVED &&
                    message.type != Message.TYPE_STATUS &&
                    message.type != Message.TYPE_RETRACTED &&
                    (message.uuid in tracked || !message.isRead) &&
                    message.bodyWithoutReplyFallback?.let { nick.matcher(it).find() } == true
            }
            .map { it.uuid }
            .onEach { tracked += it }
    }
}

/** What the composer shows for the "@word" at the cursor; null when the list is closed. */
internal class MentionSuggestions(val matches: List<MentionCandidate>, val pick: (MentionCandidate) -> Unit)

/**
 * The "@" autocomplete of group chats: the participants are asked for once per "@word" (off the main thread, by the
 * host) and filtered by the typed prefix. Closes on a pick, Back, when the cursor leaves the word or nothing matches.
 */
@Composable
internal fun rememberMentionSuggestions(
    enabled: Boolean,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    actions: ChatActions,
): MentionSuggestions? {
    val query = if (enabled) mentionQuery(input) else null
    val active = query != null
    val candidates by produceState(emptyList<MentionCandidate>(), active) {
        value = if (active) actions.mentionCandidates() else emptyList()
    }
    // Back closes the list for this "@word" only
    var dismissed by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(active) { if (!active) dismissed = null }
    val prefix = query?.query
    val matches =
        remember(candidates, prefix) {
            if (prefix == null) emptyList() else candidates.filter { it.nick.startsWith(prefix, ignoreCase = true) }
        }
    val shown = query != null && query.start != dismissed && matches.isNotEmpty()
    BackHandler(enabled = shown) { dismissed = query?.start }
    if (!shown) return null
    return MentionSuggestions(matches) { onInputChange(input.completeMention(query, it.nick)) }
}

@Composable
internal fun MentionList(suggestions: MentionSuggestions, actions: ChatActions, modifier: Modifier = Modifier) {
    val avatarPx = with(LocalDensity.current) { MentionAvatar.roundToPx() }
    // four and a half rows: the cut-off one says the list scrolls
    LazyColumn(modifier.fillMaxWidth().heightIn(max = MentionRowHeight * 4.5f)) {
        items(suggestions.matches, key = { it.nick }) { candidate ->
            val avatar by produceState<Bitmap?>(null, candidate.nick) { value = actions.mentionAvatar(candidate.nick, avatarPx) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
                        .height(MentionRowHeight)
                        .clickable { suggestions.pick(candidate) }
                        .padding(horizontal = 14.dp),
            ) {
                LetterAvatar(avatar, candidate.nick.take(1).uppercase(), candidate.color, MentionAvatar, 14.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    candidate.nick,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val MentionRowHeight = 48.dp
private val MentionAvatar = 32.dp
