package am.cyberhayq.chat.update

import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import eu.siacs.conversations.BuildConfig
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val text: String) : UpdateState
}

/**
 * Tatrak: the update flow as dialogs over the calling screen: release notes, download with
 * progress, then the system installer, whose result comes back here ([ACTION_STATUS]).
 * Opened with [EXTRA_CHECK] by "Check now", without it by the chat list's notice.
 */
class UpdateActivity : BaseActivity() {

    private var state by mutableStateOf<UpdateState>(UpdateState.Checking)
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val testBuild = !Updater.canInstall(this)
        setContent {
            TatrakTheme { UpdateDialog(state, testBuild, ::download, ::later, ::finish) }
        }
        // shortcut: a rotation starts over (a running download is cancelled); fine for a once-per-release dialog
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        when {
            intent.action == ACTION_STATUS -> status(intent)
            intent.getBooleanExtra(EXTRA_CHECK, false) -> check()
            else -> Updater.pending(this)?.let { state = UpdateState.Available(it) } ?: finish()
        }
    }

    private fun check() {
        state = UpdateState.Checking
        job = lifecycleScope.launch {
            state =
                try {
                    withContext(Dispatchers.IO) { Updater.check(applicationContext) }
                        ?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
                } catch (e: UpdateException) {
                    UpdateState.Failed(getString(e.text))
                }
        }
    }

    private fun download() {
        val release = (state as? UpdateState.Available)?.release ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.tatrak_update_allow_installs, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        state = UpdateState.Downloading(release, 0f)
        job = lifecycleScope.launch {
            try {
                val file =
                    withContext(Dispatchers.IO) {
                        var shown = -1
                        Updater.download(applicationContext, release) { progress ->
                            // a percent step, not every 64 KiB, recomposes the dialog
                            val percent = (progress * 100).toInt()
                            if (percent != shown) {
                                shown = percent
                                state = UpdateState.Downloading(release, progress)
                            }
                        }
                    }
                state = UpdateState.Installing
                withContext(Dispatchers.IO) { Updater.install(applicationContext, file) }
            } catch (e: UpdateException) {
                state = UpdateState.Failed(getString(e.text))
            }
        }
    }

    private fun later() {
        (state as? UpdateState.Available)?.let { Updater.dismiss(this, it.release.version) }
        finish()
    }

    private fun status(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                state = UpdateState.Installing
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let(::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> finish()
            else ->
                state = UpdateState.Failed(
                    getString(R.string.tatrak_update_error_install, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty())
                )
        }
    }

    override fun finish() {
        job?.cancel()
        super.finish()
    }

    companion object {
        const val ACTION_STATUS = "am.cyberhayq.chat.update.STATUS"
        const val EXTRA_CHECK = "check"
    }
}

/** [testBuild]: this install is not release-signed, so the notes say it will not install. */
@Composable
fun UpdateDialog(state: UpdateState, testBuild: Boolean, onUpdate: () -> Unit, onLater: () -> Unit, onClose: () -> Unit) {
    val close = @Composable { TextButton(onClose) { Text(stringResource(R.string.tatrak_update_close)) } }
    val cancel = @Composable { TextButton(onClose) { Text(stringResource(R.string.tatrak_update_cancel)) } }
    when (state) {
        UpdateState.Checking ->
            AlertDialog(onClose, cancel, text = { Waiting(stringResource(R.string.tatrak_update_checking)) })
        UpdateState.UpToDate ->
            AlertDialog(onClose, close, text = { Text(stringResource(R.string.tatrak_update_up_to_date, BuildConfig.VERSION_NAME)) })
        is UpdateState.Available ->
            AlertDialog(
                onClose,
                confirmButton = { TextButton(onUpdate) { Text(stringResource(R.string.tatrak_update_action)) } },
                dismissButton = { TextButton(onLater) { Text(stringResource(R.string.tatrak_update_later)) } },
                title = { Text(stringResource(R.string.tatrak_update_available, state.release.version)) },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (testBuild) Text(stringResource(R.string.tatrak_update_test_build), color = MaterialTheme.colorScheme.error)
                        Text(state.release.notes)
                    }
                },
            )
        is UpdateState.Downloading ->
            AlertDialog(
                onClose,
                cancel,
                title = { Text(stringResource(R.string.tatrak_update_downloading, state.release.version)) },
                text = { LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth()) },
            )
        UpdateState.Installing ->
            AlertDialog(onClose, close, text = { Waiting(stringResource(R.string.tatrak_update_installing)) })
        is UpdateState.Failed ->
            AlertDialog(onClose, close, title = { Text(stringResource(R.string.tatrak_update_failed)) }, text = { Text(state.text) })
    }
}

@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text(text)
    }
}
