package am.cyberhayq.chat.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/** "Message info" from the long-press menu: sent and received times, readers in a group, encryption method, trust and key. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageInfoSheet(item: MessageItem, onOpenKey: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        MessageInfoSheetContent(item, onOpenKey, Modifier.navigationBarsPadding())
    }
}

@Composable
internal fun MessageInfoSheetContent(item: MessageItem, onOpenKey: () -> Unit, modifier: Modifier = Modifier) {
    // scrolls in a short window (landscape, large text): the sheet has no partially expanded state to drag
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        Text(
            stringResource(R.string.tatrak_chat_message_info),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        if (item.receivedAt != null) {
            val context = LocalContext.current
            val locale = LocalConfiguration.current.locales[0]
            InfoRow(R.string.tatrak_chat_info_sent, receiptTime(context, locale, item.sentAt))
            InfoRow(R.string.tatrak_chat_info_received, receiptTime(context, locale, item.receivedAt))
        }
        if (item.readBy.isNotEmpty()) {
            Text(stringResource(R.string.tatrak_chat_info_read_by), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.readBy.forEach {
                Text(it, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        val key = item.encryptionKey ?: return@Column
        val method = stringResource(key.method)
        Text(
            if (key.trust == null) method else stringResource(R.string.tatrak_chat_key_line, method, stringResource(key.trust)),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        )
        val fingerprint = key.fingerprint ?: return@Column
        // 14sp: a line of four 8-digit groups still fits a 360 dp phone
        SelectionContainer {
            Text(fingerprint, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onOpenKey, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.tatrak_chat_key_open))
        }
    }
}

@Composable
private fun InfoRow(label: Int, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // the short label keeps its width, a long date wraps instead of squeezing it out
        Text(stringResource(label), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
        Text(value, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
