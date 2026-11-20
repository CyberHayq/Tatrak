package am.cyberhayq.chat.ui.edithistory

import am.cyberhayq.chat.ui.chat.MessageFormatter
import am.cyberhayq.chat.ui.chat.MessageText
import am.cyberhayq.chat.ui.chat.toAnnotatedString
import am.cyberhayq.chat.ui.common.TatrakTopBar
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

@Immutable
data class EditVersion(
    val text: String,
    /** [text] describes the version (encrypted, a file, deleted) rather than being its body. */
    val info: Boolean,
    /** The sender's name when the body starts with /me; [MessageFormatter] puts it in. */
    val meNick: String? = null,
    val timestamp: Long,
)

/** A message's versions, oldest first and scrolled to the newest at the bottom, as the classic list. */
@Composable
fun EditHistoryScreen(versions: List<EditVersion>, onBack: () -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(versions.size) { if (versions.isNotEmpty()) list.scrollToItem(versions.lastIndex) }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            TatrakTopBar(title = stringResource(R.string.edit_history), onBack = onBack)
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), state = list) {
                items(versions) { VersionRow(it) }
            }
        }
    }
}

@Composable
private fun VersionRow(version: EditVersion) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val text =
        remember(version, colors.onSurface, colors.onSurfaceVariant, colors.primary) {
            if (version.info) {
                AnnotatedString(version.text)
            } else {
                MessageFormatter.format(version.text, colors.onSurface.toArgb(), colors.onSurfaceVariant.toArgb(), meNick = version.meNick)
                    .toAnnotatedString(TextLinkStyles(SpanStyle(color = colors.primary)))
            }
        }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            remember(version.timestamp) {
                DateUtils.formatDateTime(
                    context,
                    version.timestamp,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                )
            },
            color = colors.onSurfaceVariant,
            fontSize = 13.sp,
        )
        MessageText(
            text,
            TextStyle(
                color = if (version.info) colors.onSurfaceVariant else colors.onSurface,
                fontSize = 16.sp,
                fontStyle = if (version.info) FontStyle.Italic else FontStyle.Normal,
            ),
            quoteColor = colors.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.outlineVariant)
}
