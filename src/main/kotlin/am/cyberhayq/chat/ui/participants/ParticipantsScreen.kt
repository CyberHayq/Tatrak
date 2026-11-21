package am.cyberhayq.chat.ui.participants

import am.cyberhayq.chat.ui.common.AVATAR_SIZE
import am.cyberhayq.chat.ui.common.AvatarRow
import am.cyberhayq.chat.ui.common.EmptyText
import am.cyberhayq.chat.ui.common.MenuEntry
import am.cyberhayq.chat.ui.common.TatrakScaffold
import am.cyberhayq.chat.ui.common.TatrakTopBar
import am.cyberhayq.chat.ui.common.TopBarSearch
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import eu.siacs.conversations.R
import im.conversations.android.xmpp.model.muc.Affiliation
import im.conversations.android.xmpp.model.muc.Role
import kotlin.math.roundToInt

@Immutable
data class Participant(
    /** Full JID, else real JID; stable across roster updates. */
    val key: String,
    val name: String,
    /** Status, hats and real JID, the other tags of the classic row. */
    val subtitle: String,
    /** The affiliation / role tag of the classic row; 0 when it shows none. */
    @StringRes val role: Int = 0,
    /** The classic row's OpenPGP key id (hex), shown in non-anonymous private groups; null for none. */
    val pgpKey: String? = null,
    /** The avatar hash ([MucOptions.User.getAvatar]): a new one reloads the picture. */
    val avatar: String? = null,
)

@Immutable
data class ParticipantsState(
    /** In the classic order (affiliation first, then name), already filtered by the search. */
    val participants: List<Participant> = emptyList(),
    val loaded: Boolean = false,
)

interface ParticipantsActions {
    fun back()

    fun search(needle: String)

    fun open(key: String)

    /** The classic context menu's visible entries for this participant, worked out at the long press. */
    fun menu(key: String): List<MenuEntry>

    /** An avatar already in memory, so a row scrolled back in starts with it. */
    fun cachedAvatar(key: String, sizePx: Int): Bitmap?

    suspend fun avatar(key: String, sizePx: Int): Bitmap?

    /** The key id's click: the classic UserAdapter's OpenKeychain flow. */
    fun openPgpKey(key: String) {}
}

/** The affiliation, else role, the classic UserAdapter shows as a tag; 0 for none / none. */
@StringRes
fun attributeLabel(affiliation: Affiliation, role: Role): Int =
    when (affiliation) {
        Affiliation.OWNER -> R.string.owner
        Affiliation.ADMIN -> R.string.admin
        Affiliation.MEMBER -> R.string.member
        Affiliation.OUTCAST -> R.string.outcast
        else ->
            when (role) {
                Role.MODERATOR -> R.string.moderator
                Role.PARTICIPANT -> R.string.participant
                Role.VISITOR -> R.string.visitor
                else -> 0
            }
    }

/** Telegram's member list over MucUsersActivity: search in the top bar, long press for the actions. */
@Composable
fun ParticipantsScreen(state: ParticipantsState, actions: ParticipantsActions, initialSearch: String? = null) {
    var searching by rememberSaveable { mutableStateOf(initialSearch != null) }
    var needle by rememberSaveable { mutableStateOf(initialSearch.orEmpty()) }
    LaunchedEffect(actions) { actions.search(needle) }
    val closeSearch = {
        searching = false
        needle = ""
        actions.search("")
    }
    BackHandler(enabled = searching) { closeSearch() }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        TatrakScaffold(
            topBar = {
                TatrakTopBar(
                    title = stringResource(R.string.group_chat_members),
                    onBack = { if (searching) closeSearch() else actions.back() },
                    search =
                        if (!searching) null
                        else
                            TopBarSearch(
                                needle = needle,
                                hint = stringResource(R.string.search_participants),
                                onNeedle = {
                                    needle = it
                                    actions.search(it)
                                },
                            ),
                ) {
                    if (!searching) {
                        IconButton(onClick = { searching = true }) {
                            Icon(painterResource(R.drawable.ic_search_24dp), stringResource(R.string.search))
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        ) { top ->
            val avatarPx = with(LocalDensity.current) { AVATAR_SIZE.dp.toPx().roundToInt() }
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().imePadding(), contentPadding = PaddingValues(top = top)) {
                items(state.participants, key = { it.key }) { participant ->
                    // keyed by the avatar hash too: the host's cache is cleared on a change, the load fetches the new one
                    val avatar by
                        produceState(actions.cachedAvatar(participant.key, avatarPx), participant.key, participant.avatar, avatarPx) {
                            value = actions.avatar(participant.key, avatarPx)
                        }
                    AvatarRow(
                        name = participant.name,
                        subtitle = AnnotatedString(participant.subtitle),
                        avatar = avatar,
                        onClick = { actions.open(participant.key) },
                        topEnd = if (participant.role != 0) stringResource(participant.role) else null,
                        menuOnLongPress = { actions.menu(participant.key) },
                        trailing = participant.pgpKey?.let { keyId -> { PgpKey(keyId) { actions.openPgpKey(participant.key) } } },
                    )
                }
                if (state.loaded && needle.isNotEmpty() && state.participants.isEmpty()) {
                    item(key = "empty") { EmptyText(stringResource(R.string.tatrak_new_chat_nothing_found)) }
                }
            }
        }
    }
}

/** The classic row's clickable key id. */
@Composable
private fun PgpKey(keyId: String, onClick: () -> Unit) {
    val label = stringResource(R.string.openpgp_key_id)
    Text(
        keyId,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier.clip(RoundedCornerShape(6.dp))
                .clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                .semantics { contentDescription = "$label $keyId" }
                .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}
