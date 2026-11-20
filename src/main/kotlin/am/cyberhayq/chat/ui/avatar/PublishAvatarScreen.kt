package am.cyberhayq.chat.ui.avatar

import am.cyberhayq.chat.ui.common.MenuEntry
import am.cyberhayq.chat.ui.common.TatrakTopBar
import am.cyberhayq.chat.ui.theme.LocalAvatarShape
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/** The "contacts only" switch: shown once the PEP access model is known. */
@Immutable data class ContactsOnly(val checked: Boolean, val enabled: Boolean, val loading: Boolean)

/** What the classic publish screen shows; the host reads it from the activity's views. */
@Immutable
data class PublishAvatarState(
    val title: String = "",
    /** False during the initial account setup, where the classic screen has no up arrow. */
    val showBack: Boolean = true,
    val avatar: Bitmap? = null,
    /** "Or long press to bring back default": the long press puts the default picture back. */
    val backToDefault: Boolean = false,
    /** Null where the classic screen hides the switch (group chats). */
    val contactsOnly: ContactsOnly? = null,
    /** The hint or warning line (no server support, offline, publication failed). */
    val warning: String? = null,
    /** "Publish" or "Publishing…". */
    val publishLabel: String = "",
    val publishEnabled: Boolean = false,
    /** "Cancel", or "Skip" during the initial account setup. */
    val cancelLabel: String = "",
    /** The classic options menu (delete avatar). */
    val menu: List<MenuEntry> = emptyList(),
)

interface PublishAvatarActions {
    fun back()

    /** Tap on the avatar: the gallery and the crop screen. */
    fun pick()

    fun backToDefault()

    fun contactsOnly(checked: Boolean)

    fun publish()

    fun cancel()
}

/** Choosing and publishing an account's or a group chat's avatar, over the classic publish screens. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PublishAvatarScreen(state: PublishAvatarState, actions: PublishAvatarActions) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            TatrakTopBar(title = state.title, onBack = if (state.showBack) actions::back else null, menu = state.menu)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            ) {
                Spacer(Modifier.height(32.dp))
                val description = stringResource(R.string.your_avatar_tap_to_select_new_avatar)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        // the size the activity decodes the preview at
                        Modifier.size(dimensionResource(R.dimen.publish_avatar_size))
                            .clip(LocalAvatarShape.current)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .combinedClickable(
                                role = Role.Button,
                                onClick = actions::pick,
                                onLongClickLabel = if (state.backToDefault) stringResource(R.string.tatrak_avatar_back_to_default) else null,
                                onLongClick = if (state.backToDefault) actions::backToDefault else null,
                            )
                            .semantics { contentDescription = description },
                ) {
                    val avatar = state.avatar
                    if (avatar != null) {
                        Image(remember(avatar) { avatar.asImageBitmap() }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(
                            painterResource(R.drawable.ic_add_24dp),
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Hint(stringResource(R.string.touch_to_choose_picture))
                if (state.backToDefault) Hint(stringResource(R.string.or_long_press_for_default))
                state.contactsOnly?.let { ContactsOnlyRow(it, actions) }
                state.warning?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                TextButton(onClick = actions::cancel) { Text(state.cancelLabel) }
                Button(onClick = actions::publish, enabled = state.publishEnabled) { Text(state.publishLabel) }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** While the access model loads, the row keeps its place but is not shown, as the classic INVISIBLE switch. */
@Composable
private fun ContactsOnlyRow(switch: ContactsOnly, actions: PublishAvatarActions) {
    val hidden = if (switch.loading) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.padding(top = 12.dp)
                .then(hidden)
                .toggleable(switch.checked, enabled = switch.enabled && !switch.loading, role = Role.Switch, onValueChange = actions::contactsOnly)
                .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(stringResource(R.string.show_to_contacts_only), fontSize = 16.sp, modifier = Modifier.padding(end = 16.dp))
        Switch(checked = switch.checked, onCheckedChange = null, enabled = switch.enabled)
    }
}
