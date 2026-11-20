package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.theme.rememberPreference
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.preference.PreferenceManager
import eu.siacs.conversations.entities.Reaction
import eu.siacs.conversations.utils.Emoticons

/**
 * Telegram's "frequently used" reactions: a local count of the emojis the user adds as reactions,
 * shown as the message menu's second row. Stored as "emoji count" lines, most used first; among
 * equal counts the most recently added comes first.
 */
internal object FrequentReactions {
    const val KEY = "tatrak_frequent_reactions"
    const val SHOWN = 7
    // shortcut: counts never decay, so an old favourite outranks a new one for good; add decay if that annoys
    private const val KEPT = 32

    private val firstRow = Reaction.SUGGESTIONS.map(Emoticons::normalizeToVS16).toSet()

    fun parse(raw: String?): List<Pair<String, Int>> =
        raw.orEmpty().lineSequence().mapNotNull { line ->
            val emoji = line.substringBeforeLast(' ', "")
            val count = line.substringAfterLast(' ').toIntOrNull()
            if (emoji.isEmpty() || count == null || count < 1) null else emoji to count
        }.toList()

    /** [raw] with one more use of [emoji]; a new emoji is kept even when the list is full. */
    fun added(raw: String?, emoji: String): String {
        val old = parse(raw)
        val count = (old.firstOrNull { it.first == emoji }?.second ?: 0) + 1
        // newest first, then a stable sort: among equal counts the most recent leads
        val sorted = (listOf(emoji to count) + old.filter { it.first != emoji }).sortedByDescending { it.second }
        val kept = if (sorted.size > KEPT) sorted - sorted.last { it.first != emoji } else sorted
        return kept.joinToString("\n") { "${it.first} ${it.second}" }
    }

    /** The second row: the most used, without the first row's emojis. */
    fun top(raw: String?): List<String> = parse(raw).map { it.first }.filter { it !in firstRow }.take(SHOWN)

    /** Counts [emoji] in the form toggleReaction sends (VS16), so both forms of one emoji count once. */
    fun record(context: Context, emoji: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString(KEY, added(prefs.getString(KEY, null), Emoticons.normalizeToVS16(emoji))).apply()
    }

    @Composable fun remember(): List<String> = rememberPreference(KEY) { top(it.getString(KEY, null)) }
}
