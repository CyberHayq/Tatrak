package am.cyberhayq.chat.ui.commands

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R
import kotlinx.coroutines.launch

/** Opens the chat's command sheet; null while the chat has no commands (the button is hidden). Set by ChatFragment. */
val LocalCommandsButton = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Telegram's bot menu button (BotCommandsMenuView): a 32 dp accent pill with the icon and "Commands" while
 * [expanded] (empty field, keyboard down), an icon-only 40 dp capsule otherwise.
 */
@Composable
fun CommandsButton(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.tatrak_commands)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.height(32.dp).widthIn(min = 40.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.animateContentSize().padding(horizontal = 10.dp),
        ) {
            Icon(painterResource(R.drawable.tatrak_ic_menu_24dp), contentDescription = if (expanded) null else label, modifier = Modifier.size(20.dp))
            AnimatedVisibility(expanded, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                Text(label, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp, end = 2.dp))
            }
        }
    }
}

/** Telegram's BotCommandsMenuContainer: the list in a sheet with a drag handle, closed by a pull down or a tap above. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsSheet(commands: List<CommandItem>, onPick: (CommandItem) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        CommandsSheetContent(commands) { item ->
            if (picked) return@CommandsSheetContent
            picked = true
            // only after the sheet hid: a chat switch or rotation cancels the hide, and then nothing starts
            scope.launch { sheetState.hide() }.invokeOnCompletion { if (it == null) onPick(item) }
        }
    }
}

@Composable
internal fun CommandsSheetContent(commands: List<CommandItem>, onPick: (CommandItem) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Text(
            stringResource(R.string.tatrak_commands),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        // saved across rotation by address and node, shown only while the current list still has that blocked item
        var explainKey by rememberSaveable { mutableStateOf<String?>(null) }
        val explain = explainKey?.let { key -> commands.firstOrNull { it.blocked && "${it.jid}\n${it.node}" == key } }
        val whyLabel = stringResource(R.string.tatrak_command_blocked_why)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            // no keys: a server may list the same node twice
            items(commands) { item ->
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(onClickLabel = if (item.blocked) whyLabel else null) {
                            if (item.blocked) explainKey = "${item.jid}\n${item.node}" else onPick(item)
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge, color = if (item.blocked) muted else Color.Unspecified)
                    if (item.blocked) {
                        Text(stringResource(R.string.tatrak_command_blocked, item.jid.domain.toString()), style = MaterialTheme.typography.bodySmall, color = muted)
                    } else if (item.via != null) {
                        Text(stringResource(R.string.tatrak_command_via, item.via), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
        explain?.let { item ->
            AlertDialog(
                onDismissRequest = { explainKey = null },
                title = { Text(stringResource(R.string.tatrak_command_blocked_title)) },
                text = { Text(stringResource(R.string.tatrak_command_blocked_text, item.jid.domain.toString())) },
                confirmButton = { TextButton(onClick = { explainKey = null }) { Text(stringResource(android.R.string.ok)) } },
            )
        }
    }
}
