package am.cyberhayq.chat.ui.search

import am.cyberhayq.chat.ui.chatlist.rememberChatTimeFormatter
import am.cyberhayq.chat.ui.common.AvatarRow
import am.cyberhayq.chat.ui.common.EmptyText
import am.cyberhayq.chat.ui.common.TatrakTopBar
import am.cyberhayq.chat.ui.common.MenuEntry
import am.cyberhayq.chat.ui.common.TopBarSearch
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import eu.siacs.conversations.R

/** The former long-press menu of a search result (`search_result_context`). */
enum class SearchMenu(@StringRes val label: Int) {
    OPEN(R.string.view_conversation),
    SHARE(R.string.share_with),
    COPY(R.string.copy_to_clipboard),
    QUOTE(R.string.quote),
}

@Immutable
data class SearchResult(
    /** Message uuid. */
    val key: String,
    /** The chat in a global search, the sender inside one chat. */
    val title: String,
    /** "Sender: " in group results of a global search, else empty. */
    val prefix: String,
    val text: String,
    val timestamp: Long,
    val avatar: Bitmap? = null,
    val menu: List<SearchMenu> = SearchMenu.entries,
)

@Immutable
data class SearchState(
    val results: List<SearchResult> = emptyList(),
    /** The parsed terms the results are for; highlighted in the text. */
    val terms: List<String> = emptyList(),
    /** True once results for the current terms arrived. */
    val searched: Boolean = false,
    /** Searching inside one chat rather than in all of them. */
    val inChat: Boolean = false,
    /** That chat's name, once known; goes into the hint. */
    val chatName: String? = null,
)

interface SearchActions {
    fun back()

    fun search(text: String)

    fun open(key: String)

    fun menu(key: String, item: SearchMenu)
}

/** Message search, Telegram style: the field in the top bar, newest results first. */
@Composable
fun MessageSearchScreen(
    state: SearchState,
    actions: SearchActions,
    modifier: Modifier = Modifier,
    initialText: String = "",
) {
    var needle by rememberSaveable { mutableStateOf(initialText) }
    val time = rememberChatTimeFormatter()
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            val hint =
                when {
                    state.chatName != null -> stringResource(R.string.tatrak_search_in_chat, state.chatName)
                    state.inChat -> stringResource(R.string.tatrak_search_this_chat)
                    else -> stringResource(R.string.tatrak_search_all_chats)
                }
            TatrakTopBar(
                title = stringResource(R.string.search_messages),
                onBack = actions::back,
                search =
                    TopBarSearch(
                        needle = needle,
                        hint = hint,
                        onNeedle = {
                            needle = it
                            actions.search(it)
                        },
                    ),
            )
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                items(state.results, key = { it.key }) { result ->
                    SearchResultRow(result, state.terms, time.format(result.timestamp), actions::open, actions::menu)
                }
                if (state.searched && needle.isNotBlank() && state.results.isEmpty()) {
                    item(key = "empty") { EmptyText(stringResource(R.string.tatrak_new_chat_nothing_found)) }
                }
                if (needle.isBlank()) {
                    item(key = "scope") {
                        EmptyText(
                            stringResource(
                                if (state.inChat) R.string.tatrak_search_scope_chat else R.string.tatrak_search_scope_all
                            )
                        )
                    }
                }
            }
        }
    }
}

/** One found message: chat (or sender) and time on top, the text with the terms marked below. */
@Composable
fun SearchResultRow(
    result: SearchResult,
    terms: List<String>,
    time: String,
    onOpen: (String) -> Unit,
    onMenu: ((String, SearchMenu) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val highlight = MaterialTheme.colorScheme.primary
    val labels = result.menu.map { stringResource(it.label) }
    val subtitle = remember(result, terms, highlight) { highlighted(result.prefix, result.text, terms, highlight) }
    AvatarRow(
        name = result.title,
        subtitle = subtitle,
        avatar = result.avatar,
        onClick = { onOpen(result.key) },
        modifier = modifier,
        menu =
            if (onMenu == null) {
                emptyList()
            } else {
                result.menu.mapIndexed { i, item -> MenuEntry(labels[i]) { onMenu(result.key, item) } }
            },
        subtitleLines = 2,
        topEnd = time,
    )
}

/**
 * [prefix] in the normal colour, then [text] with every occurrence of each term in [highlight]
 * (case-insensitive; wildcards and search keywords from `FtsUtils.parse` are not marked).
 */
internal fun highlighted(prefix: String, text: String, terms: List<String>, highlight: Color): AnnotatedString =
    buildAnnotatedString {
        if (prefix.isNotEmpty()) {
            pushStyle(SpanStyle(color = Color.Unspecified, fontWeight = FontWeight.Medium))
            append(prefix)
            pop()
        }
        val start = length
        append(text)
        val lower = text.lowercase()
        // a few characters change length when lowercased; then the offsets would be off
        if (lower.length != text.length) return@buildAnnotatedString
        for (term in terms) {
            val word = term.trim('*', '-').lowercase()
            if (word.isEmpty() || word == "and" || word == "or" || word == "not" || word == "near") continue
            var from = 0
            while (true) {
                val at = lower.indexOf(word, from)
                if (at < 0) break
                addStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Medium), start + at, start + at + word.length)
                from = at + word.length
            }
        }
    }
