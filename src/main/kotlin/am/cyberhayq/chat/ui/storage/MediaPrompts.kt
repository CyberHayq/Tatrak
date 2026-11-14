package am.cyberhayq.chat.ui.storage

import am.cyberhayq.chat.storage.media.VoiceStream
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.siacs.conversations.R

/** The one-time warnings about plain copies of media, one dialog for the Compose and the classic screens. */
object MediaPrompts {
    private const val SAVE_ACCEPTED = "tatrak_save_warning_accepted"
    private const val VOICE_ACCEPTED = "tatrak_voice_warning_accepted"
    private const val REQUEST_SAVE_PERMISSION = 0x7a71 // no host activity handles it: the user taps "Save" again

    /** "Save" copies a file out of the store: the first time the user is told; "Cancel" asks again next time. */
    @JvmStatic
    fun confirmSave(context: Context, onSave: Runnable) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(SAVE_ACCEPTED, false)) return saveWithPermission(context, onSave)
        MaterialAlertDialogBuilder(context)
            .setMessage(R.string.tatrak_save_warning)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.edit().putBoolean(SAVE_ACCEPTED, true).apply()
                saveWithPermission(context, onSave)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Up to Android 9 a copy into Pictures or Downloads needs the storage permission, which nothing
     * else asks for since the shared-storage setting is gone: it is asked for, and the user taps
     * "Save" again once it is granted (as after the microphone request).
     */
    private fun saveWithPermission(context: Context, onSave: Runnable) {
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? Activity }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED ||
            activity == null
        ) {
            return onSave.run()
        }
        ActivityCompat.requestPermissions(activity, arrayOf(permission), REQUEST_SAVE_PERMISSION)
    }

    /** True when the next recording goes through a plain file and the user was not told yet. */
    @JvmStatic
    fun voiceWarningDue(context: Context): Boolean =
        VoiceStream.usesFile(context) &&
            !PreferenceManager.getDefaultSharedPreferences(context).getBoolean(VOICE_ACCEPTED, false)

    /** The one-time voice warning: "Record" remembers it and runs [onRecord]; "Cancel" or Back runs [onCancel]. */
    @JvmStatic
    fun warnPlainVoice(context: Context, onRecord: Runnable, onCancel: Runnable): AlertDialog =
        MaterialAlertDialogBuilder(context)
            .setMessage(R.string.tatrak_voice_plain_warning)
            .setPositiveButton(R.string.tatrak_voice_record) { _, _ ->
                PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(VOICE_ACCEPTED, true).apply()
                onRecord.run()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel.run() }
            .setOnCancelListener { onCancel.run() }
            .show()
}
