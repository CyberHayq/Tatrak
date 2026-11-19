package am.cyberhayq.chat.ui.share

import am.cyberhayq.chat.ui.chatlist.inErrorColor
import am.cyberhayq.chat.ui.common.AvatarRow
import am.cyberhayq.chat.ui.common.EmptyText
import am.cyberhayq.chat.ui.common.TatrakTopBar
import am.cyberhayq.chat.ui.common.TopBarSearch
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import eu.siacs.conversations.R

@Immutable
data class ShareTarget(
    /** Conversation uuid. */
    val key: String,
    val name: String,
    /** The chat's address, look-alike characters marked. */
    val address: AnnotatedString,
    val avatar: Bitmap? = null,
)

@Immutable
data class ShareState(val targets: List<ShareTarget> = emptyList(), val loaded: Boolean = false)

interface ShareActions {
    fun back()

    fun pick(key: String)

    /** The former "+" item: pick any contact or address, then share there. */
    fun newChat()
}

/** Telegram-style "Share with…" picker: the open chats, most recent first, with a search. */
@Composable
fun SharePickerScreen(state: ShareState, actions: ShareActions, modifier: Modifier = Modifier) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var needle by rememberSaveable { mutableStateOf("") }
    val shown =
        remember(state.targets, needle) {
            val n = needle.trim()
            if (n.isEmpty()) state.targets
            else state.targets.filter { it.name.contains(n, ignoreCase = true) || it.address.contains(n, ignoreCase = true) }
        }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            TatrakTopBar(
                title = stringResource(R.string.title_activity_share_with),
                onBack = {
                    if (searching) {
                        searching = false
                        needle = ""
                    } else {
                        actions.back()
                    }
                },
                search = if (searching) TopBarSearch(needle, stringResource(R.string.search), { needle = it }) else null,
            ) {
                if (!searching) {
                    IconButton(onClick = { searching = true }) {
                        Icon(painterResource(R.drawable.ic_search_24dp), stringResource(R.string.search))
                    }
                }
                IconButton(onClick = actions::newChat) {
                    Icon(painterResource(R.drawable.ic_add_24dp), stringResource(R.string.start_chat))
                }
            }
            if (state.loaded && shown.isEmpty()) {
                EmptyText(
                    stringResource(if (needle.isBlank()) R.string.tatrak_media_empty else R.string.tatrak_new_chat_nothing_found)
                )
            }
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(shown, key = { it.key }) { target ->
                    AvatarRow(
                        name = target.name,
                        subtitle = target.address.inErrorColor(),
                        avatar = target.avatar,
                        onClick = { actions.pick(target.key) },
                    )
                }
            }
        }
    }
}
