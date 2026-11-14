package eu.siacs.conversations.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.services.CallIntegrationConnectionService
import eu.siacs.conversations.ui.util.PresenceSelector
import eu.siacs.conversations.utils.PermissionUtils
import eu.siacs.conversations.xmpp.Jid
import eu.siacs.conversations.xmpp.jingle.RtpCapability
import eu.siacs.conversations.xmpp.manager.JingleManager

/**
 * Tatrak: starts an audio or video call the way the classic `ConversationFragment` does
 * (`checkPermissionAndTriggerAudioCall` / `VideoCall`, `isAccountInsufficientState`,
 * `triggerRtpSession`), shared by the profile and the call list. Create it while the activity is
 * being constructed (a property initializer): it registers a permission request.
 */
internal class RtpCallStarter(
    private val activity: XmppActivity,
    /** A fragment registers its own request: the activity is already started when it attaches. */
    caller: ActivityResultCaller = activity,
) {

    private var pending: Pair<Conversation, Boolean>? = null

    private val permissions =
        caller.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val (conversation, video) = pending ?: return@registerForActivityResult
            pending = null
            // as ConversationFragment.onRequestPermissionsResult: an interrupted request returns
            // nothing, and Bluetooth / local network are optional for a call
            if (result.isEmpty()) return@registerForActivityResult
            val res = deniedMessage(result)
            if (res == null) {
                triggerRtpSession(conversation, video)
            } else {
                Toast.makeText(
                        activity,
                        activity.getString(res, activity.getString(R.string.app_name)),
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }

    companion object {
        /** The toast for the first denied required permission, null when the call may start. */
        internal fun deniedMessage(result: Map<String, Boolean>): Int? {
            val required =
                PermissionUtils.removeOptionalRtpPermissions(
                    result.keys.toTypedArray(),
                    result.values
                        .map { if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }
                        .toIntArray(),
                )
            return when (PermissionUtils.getFirstDenied(required.grantResults(), required.permissions())) {
                null -> null
                Manifest.permission.CAMERA -> R.string.no_camera_permission
                else -> R.string.no_microphone_permission
            }
        }
    }

    /** Asks for confirmation first, so a mis-tap never rings the contact. */
    fun call(conversation: Conversation, video: Boolean) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(
                activity.getString(
                    if (video) R.string.tatrak_call_confirm_video else R.string.tatrak_call_confirm_audio,
                    conversation.name,
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.tatrak_call_confirm_yes) { _, _ -> start(conversation, video) }
            .show()
    }

    private fun start(conversation: Conversation, video: Boolean) {
        if (isAccountInsufficientState(conversation.account)) return
        val missing =
            PermissionUtils.rtpPermissions(video).filter {
                activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isEmpty()) {
            triggerRtpSession(conversation, video)
        } else {
            pending = conversation to video
            permissions.launch(missing.toTypedArray())
        }
    }

    /** ConversationFragment.isAccountInsufficientState. */
    private fun isAccountInsufficientState(account: Account): Boolean {
        if (account.isConnectionEnabled) {
            if (AppSettings(activity).isUseTor || account.isOnion) {
                Toast.makeText(activity, R.string.disable_tor_to_make_call, Toast.LENGTH_SHORT).show()
                return true
            }
            return false
        }
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.this_account_is_disabled)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.enable) { _, _ ->
                account.setOption(Account.OPTION_SOFT_DISABLED, false)
                account.setOption(Account.OPTION_DISABLED, false)
                activity.xmppConnectionService?.updateAccount(account)
            }
            .show()
        return true
    }

    /** ConversationFragment.triggerRtpSession. */
    private fun triggerRtpSession(conversation: Conversation, video: Boolean) {
        val service = activity.xmppConnectionService ?: return
        if (JingleManager.isBusy(service.accounts)) {
            Toast.makeText(activity, R.string.only_one_call_at_a_time, Toast.LENGTH_LONG).show()
            return
        }
        val action = if (video) RtpSessionActivity.ACTION_MAKE_VIDEO_CALL else RtpSessionActivity.ACTION_MAKE_VOICE_CALL
        val account = conversation.account
        if (account.setOption(Account.OPTION_SOFT_DISABLED, false)) {
            service.updateAccount(account)
        }
        val contact = conversation.contact
        if (Config.USE_JINGLE_MESSAGE_INIT && RtpCapability.jmiSupport(contact)) {
            placeCall(account, contact.address.asBareJid(), action)
        } else {
            val capability = if (video) RtpCapability.Capability.VIDEO else RtpCapability.Capability.AUDIO
            PresenceSelector.selectFullJidForDirectRtpConnection(activity, contact, capability) {
                placeCall(account, it, action)
            }
        }
    }

    private fun placeCall(account: Account, with: Jid, action: String) =
        CallIntegrationConnectionService.placeCall(
            activity.xmppConnectionService,
            account,
            with,
            RtpSessionActivity.actionToMedia(action),
        )
}
