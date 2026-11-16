package am.cyberhayq.chat.storage

import com.google.android.material.dialog.MaterialAlertDialogBuilder as AlertDialogBuilder
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.material.button.MaterialButton as Button
import android.widget.LinearLayout
import com.google.android.material.textview.MaterialTextView as TextView
import android.widget.ScrollView
import eu.siacs.conversations.ui.BaseActivity
import androidx.activity.addCallback
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.ConversationActivity
import eu.siacs.conversations.ui.ImportBackupActivity

/** A normal foreground screen owns consent: recovery choices are never made in the background. */
class DatabaseStorageActivity : BaseActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private lateinit var actions: LinearLayout
    private var displayed: DatabaseStorage.State? = null
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
            if (state != displayed) { displayed = state; render(state) }
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
    }

    override fun onStart() { super.onStart(); handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }

    private fun button(label: Int, action: () -> Unit) {
        actions.addView(Button(this).apply { setText(label); setOnClickListener { action() } })
    }
    private fun render(state: DatabaseStorage.State) {
        actions.removeAllViews()
        when (state) {
            DatabaseStorage.State.STARTING, DatabaseStorage.State.WORKING -> text.setText(R.string.tatrak_storage_working)
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
}
