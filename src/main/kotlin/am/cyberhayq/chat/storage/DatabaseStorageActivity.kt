package am.cyberhayq.chat.storage

import com.google.android.material.dialog.MaterialAlertDialogBuilder as AlertDialogBuilder
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import com.google.android.material.button.MaterialButton as Button
import android.widget.LinearLayout
import com.google.android.material.textview.MaterialTextView as TextView
import android.widget.ScrollView
import eu.siacs.conversations.ui.BaseActivity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.ConversationActivity
import eu.siacs.conversations.ui.ImportBackupActivity
import eu.siacs.conversations.worker.ExportBackupWorker
import java.util.UUID

/** A normal foreground screen owns consent; background entry points never migrate silently. */
class DatabaseStorageActivity : BaseActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private lateinit var actions: LinearLayout
    private var displayed: DatabaseStorage.State? = null
    private var exportId: UUID? = null
    private val backupPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enqueueExport()
        else AlertDialogBuilder(this).setMessage(R.string.tatrak_storage_backup_failed)
            .setPositiveButton(R.string.ok, null).show()
    }
    private val refresh = object : Runnable {
        override fun run() {
            val state = DatabaseStorage.state()
            if (state == DatabaseStorage.State.READY) {
                if (!intent.getBooleanExtra("tatrak_return_to_caller", false)) {
                    startActivity(Intent(this@DatabaseStorageActivity, ConversationActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
                finish()
                return
            }
            if (state != displayed && exportId == null) { displayed = state; render(state) }
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.tatrak_storage_title)
        onBackPressedDispatcher.addCallback(this) { finishAffinity() }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        text = TextView(this).apply { textSize = 18f }
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(text); content.addView(actions)
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(content) })
        DatabaseStorage.start(this)
        savedInstanceState?.getString("export")?.let { observeExport(UUID.fromString(it)) }
    }

    override fun onStart() { super.onStart(); handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }
    override fun onSaveInstanceState(out: Bundle) {
        exportId?.let { out.putString("export", it.toString()) }
        super.onSaveInstanceState(out)
    }

    private fun button(label: Int, action: () -> Unit) {
        actions.addView(Button(this).apply { setText(label); setOnClickListener { action() } })
    }
    private fun render(state: DatabaseStorage.State) {
        actions.removeAllViews()
        when (state) {
            DatabaseStorage.State.STARTING, DatabaseStorage.State.WORKING -> text.setText(R.string.tatrak_storage_working)
            DatabaseStorage.State.MIGRATION_REQUIRED -> {
                text.setText(R.string.tatrak_storage_migration)
                button(R.string.tatrak_storage_backup) {
                    // Same permission order as BackupSettingsFragment's one-off backup action.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        backupPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else enqueueExport()
                }
                button(R.string.tatrak_storage_continue) {
                    AlertDialogBuilder(this).setMessage(R.string.tatrak_storage_without_backup)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.tatrak_storage_continue) { _, _ -> DatabaseStorage.migrate(this) }.show()
                }
            }
            DatabaseStorage.State.RECOVERY_REQUIRED -> {
                text.text = DatabaseStorage.error() ?: getString(R.string.tatrak_storage_failed)
                button(R.string.tatrak_storage_retry) { DatabaseStorage.retry(this) }
                button(R.string.restore_backup) { startActivity(Intent(this, ImportBackupActivity::class.java)) }
                button(R.string.tatrak_storage_fresh) {
                    AlertDialogBuilder(this).setMessage(R.string.tatrak_storage_fresh_warning)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.tatrak_storage_fresh) { _, _ -> DatabaseStorage.freshStart(this) }.show()
                }
            }
            DatabaseStorage.State.READY -> Unit
        }
    }
    private fun enqueueExport() {
        if (DatabaseStorage.state() != DatabaseStorage.State.MIGRATION_REQUIRED || exportId != null) return
        val work = OneTimeWorkRequest.Builder(ExportBackupWorker::class.java)
            .setInputData(Data.Builder().putBoolean("tatrak_before_migration", true).build()).build()
        WorkManager.getInstance(this).enqueue(work)
        observeExport(work.id)
    }

    private fun observeExport(id: UUID) {
        exportId = id
        actions.removeAllViews(); text.setText(R.string.tatrak_storage_exporting)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info ->
            if (info?.state?.isFinished != true || exportId != id) return@observe
            exportId = null; displayed = null
            if (info.state == WorkInfo.State.SUCCEEDED) {
                AlertDialogBuilder(this).setMessage(R.string.tatrak_storage_backup_done)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.tatrak_storage_continue) { _, _ -> DatabaseStorage.migrate(this) }.show()
            } else {
                AlertDialogBuilder(this).setMessage(R.string.tatrak_storage_backup_failed)
                    .setPositiveButton(R.string.ok, null).show()
            }
        }
    }
}
