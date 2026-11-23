package am.cyberhayq.chat.update

import am.cyberhayq.chat.ui.theme.rememberPreference
import am.cyberhayq.chat.ui.theme.rememberStringPreference
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/** The chat list's notice of a newer release; shows while [Updater.pending] has one. */
@Composable
fun UpdateNotice() {
    val context = LocalContext.current
    // read as state so a finished check or "Later" shows or hides it at once
    val stored = rememberStringPreference(Updater.RELEASE)
    val dismissed = rememberStringPreference(Updater.DISMISSED)
    val betas = rememberPreference(Updater.PRERELEASE) { it.getBoolean(Updater.PRERELEASE, false) }
    val release = remember(stored, dismissed, betas) { Updater.pending(context) } ?: return
    UpdateNoticeRow(
        release.version,
        onUpdate = { context.startActivity(Intent(context, UpdateActivity::class.java)) },
        onLater = { Updater.dismiss(context, release.version) },
    )
}

@Composable
fun UpdateNoticeRow(version: String, onUpdate: () -> Unit, onLater: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_new_releases_24dp), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(
            stringResource(R.string.tatrak_update_available, version),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onLater) { Text(stringResource(R.string.tatrak_update_later)) }
        TextButton(onUpdate) { Text(stringResource(R.string.tatrak_update_action)) }
    }
}
