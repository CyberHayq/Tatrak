package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.chatlist.styledAddress
import am.cyberhayq.chat.ui.chat.ChatSnapshot
import am.cyberhayq.chat.ui.common.confirmAction
import am.cyberhayq.chat.ui.common.confirmClearHistory
import am.cyberhayq.chat.ui.common.deleteChatText
import am.cyberhayq.chat.ui.common.deleteChatTitle
import am.cyberhayq.chat.ui.profile.MEDIA_THUMBNAIL_DP
import am.cyberhayq.chat.ui.profile.ProfileActions
import am.cyberhayq.chat.ui.profile.ProfileMedia
import am.cyberhayq.chat.ui.profile.ProfileMember
import am.cyberhayq.chat.ui.profile.ProfileScreen
import am.cyberhayq.chat.ui.profile.ProfileState
import am.cyberhayq.chat.ui.state.SnapshotSource
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import de.gultsch.common.MiniUri
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.util.Attachment
import eu.siacs.conversations.ui.util.ViewUtil
import eu.siacs.conversations.xmpp.OnUpdateBlocklist
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection
import eu.siacs.conversations.xmpp.jingle.Media
import eu.siacs.conversations.xmpp.manager.BlockingManager
import eu.siacs.conversations.xmpp.manager.JingleManager
import im.conversations.android.xmpp.model.muc.Affiliation
import java.util.Collections
import kotlin.math.roundToInt

/**
 * Telegram-style profile of a 1:1 or group chat, opened from the chat header. Everything
 * security-related (keys, room configuration) is a link to the classic details screens.
 */
class ProfileActivity :
    XmppActivity(),
    ProfileActions,
    XmppConnectionService.OnConversationUpdate,
    XmppConnectionService.OnRosterUpdate,
    XmppConnectionService.OnMucRosterUpdate,
    OnUpdateBlocklist {

    private var conversation: Conversation? = null

    /** Loaded once per backend connection, off the main thread; see [loadMedia]. */
    @Volatile private var media: List<Pair<ProfileMedia, Attachment>> = emptyList()

    private val source by lazy {
        SnapshotSource(lifecycleScope, ProfileState()) { service, publish ->
            conversation?.let { publish(build(service, it)) }
        }
    }

    private val calls = RtpCallStarter(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            TatrakTheme {
                val state by source.state.collectAsStateWithLifecycle()
                ProfileScreen(state, this)
            }
        }
    }

    override fun onBackendConnected() {
        val c = xmppConnectionService.findConversationByUuid(intent.getStringExtra(EXTRA_UUID) ?: "")
        if (c == null || !xmppConnectionService.isConversationStillOpen(c)) {
            finish()
            return
        }
        conversation = c
        source.invalidate(xmppConnectionService)
        loadMedia(xmppConnectionService, c)
    }

    /** Recent photos and videos for the strip, through the same query as the media browser. */
    private fun loadMedia(service: XmppConnectionService, c: Conversation) {
        val size = (MEDIA_THUMBNAIL_DP * resources.displayMetrics.density).roundToInt()
        service.getAttachments(c, MEDIA_QUERY_LIMIT) { attachments ->
            media =
                attachments
                    .asSequence()
                    .filter { it.renderThumbnail() && it.mime != "application/pdf" }
                    .mapNotNull { a ->
                        val bitmap = runCatching { service.fileBackend.getPreviewForUri(a, size, false) }.getOrNull()
                        bitmap?.let { ProfileMedia(a.uuid.toString(), it) to a }
                    }
                    .take(MEDIA_STRIP)
                    .toList()
            source.invalidate(service)
        }
    }

    override fun refreshUiReal() {
        val service = xmppConnectionService ?: return
        val c = conversation ?: return
        if (!service.isConversationStillOpen(c)) {
            finish()
            return
        }
        source.invalidate(service)
    }

    override fun onConversationUpdate() = refreshUi()

    override fun onRosterUpdate() = refreshUi()

    override fun onMucRosterUpdate() = refreshUi()

    override fun OnUpdateBlocklist(status: OnUpdateBlocklist.Status) = refreshUi()

    private fun build(service: XmppConnectionService, c: Conversation): ProfileState {
        val density = resources.displayMetrics.density
        val isGroup = c.mode == Conversational.MODE_MULTI
        val connection = c.account.xmppConnection
        val members =
            if (isGroup) {
                val size = (40 * density).roundToInt()
                c.mucOptions.getUsersPreview(MEMBER_PREVIEW).map {
                    ProfileMember(
                        key = (it.fullJid ?: it.realJid)?.toString() ?: it.displayName,
                        name = it.displayName,
                        role =
                            when (it.affiliation) {
                                Affiliation.OWNER -> getString(R.string.owner)
                                Affiliation.ADMIN -> getString(R.string.admin)
                                else -> null
                            },
                        avatar = service.avatarService.get(it, size, false),
                    )
                }
            } else {
                emptyList()
            }
        return ProfileState(
            loaded = true,
            header =
                ChatSnapshot.header(
                    this,
                    service,
                    c,
                    (96 * density).roundToInt(),
                    false,
                    AppSettings(this).isBroadcastLastActivity,
                ),
            address = styledAddress(this, c.address.asBareJid()),
            about = if (isGroup) c.mucOptions.subject else null,
            canCall = !isGroup,
            canVideo = !isGroup && isCameraFeatureAvailable(),
            ongoingCall =
                !isGroup && connection.getManager(JingleManager::class.java).getOngoingRtpConnection(c.contact).isPresent,
            blocked =
                if (!isGroup && connection.getManager(BlockingManager::class.java).hasFeature()) c.isBlocked else null,
            members = members,
            memberCount = if (isGroup) c.mucOptions.userCount else 0,
            media = media.map { it.first },
        )
    }

    // --- actions ------------------------------------------------------------------------------

    override fun back() = finish()

    override fun message() = finish()

    override fun toggleMute() {
        val c = conversation ?: return
        toggleMute(this, xmppConnectionService ?: return, c) { refreshUi() }
    }

    /** ConversationFragment.checkPermissionAndTriggerAudioCall / VideoCall, see [RtpCallStarter]. */
    override fun call(video: Boolean) {
        calls.call(conversation ?: return, video)
    }

    override fun returnToCall() {
        val c = conversation ?: return
        val account = c.account
        val ongoing =
            account.xmppConnection.getManager(JingleManager::class.java).getOngoingRtpConnection(c.contact)
        if (!ongoing.isPresent) return
        val id = ongoing.get()
        val intent =
            Intent(this, RtpSessionActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(RtpSessionActivity.EXTRA_ACCOUNT, account.jid.asBareJid().toString())
                .putExtra(RtpSessionActivity.EXTRA_WITH, id.with.toString())
        if (id is AbstractJingleConnection) {
            intent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, id.sessionId)
            startActivity(intent)
        } else if (id is JingleManager.RtpSessionProposal) {
            intent.putExtra(
                RtpSessionActivity.EXTRA_LAST_ACTION,
                if (Media.audioOnly(id.media)) RtpSessionActivity.ACTION_MAKE_VOICE_CALL
                else RtpSessionActivity.ACTION_MAKE_VIDEO_CALL,
            )
            intent.putExtra(RtpSessionActivity.EXTRA_PROPOSED_SESSION_ID, id.sessionId)
            startActivity(intent)
        }
    }

    override fun search() {
        val c = conversation ?: return
        startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_CONVERSATION_UUID, c.uuid))
    }

    override fun copyAddress() {
        val c = conversation ?: return
        if (copyTextToClipboard(c.address.asBareJid().toString(), R.string.account_settings_jabber_id)) {
            Toast.makeText(this, R.string.tatrak_profile_address_copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun share() = shareLink(true)

    override fun showQr() = showQrCode()

    /** ContactDetailsActivity / ConferenceDetailsActivity.getShareableUri. */
    override fun getShareableUri(http: Boolean): MiniUri? {
        val c = conversation ?: return null
        val uri =
            if (c.mode == Conversational.MODE_MULTI) {
                MiniUri.Xmpp(
                    c.address.asBareJid(),
                    mapOf<String, Collection<String>>(MiniUri.Xmpp.ACTION_JOIN to Collections.singleton(MiniUri.EMPTY_STRING)),
                )
            } else {
                MiniUri.Xmpp(c.contact.address)
            }
        return if (http) uri.asInvitationUri() else uri
    }

    /** ContactDetailsActivity's avatar click; for a group, ConferenceDetailsActivity's (owner changes it). */
    override fun openAvatar() {
        val c = conversation ?: return
        if (c.mode == Conversational.MODE_MULTI) {
            val mucOptions = c.mucOptions
            when {
                !mucOptions.hasVCards() ->
                    Toast.makeText(this, R.string.host_does_not_support_group_chat_avatars, Toast.LENGTH_SHORT).show()
                !mucOptions.self.ranks(Affiliation.OWNER) ->
                    Toast.makeText(this, R.string.only_the_owner_can_change_group_chat_avatar, Toast.LENGTH_SHORT).show()
                else ->
                    startActivity(
                        Intent(this, PublishGroupChatProfilePictureActivity::class.java).putExtra("uuid", c.uuid)
                    )
            }
            return
        }
        val avatar = c.contact.avatar ?: return
        startActivity(
            Intent(this, ViewProfilePictureActivity::class.java)
                .setData(Uri.fromParts("avatar", avatar, null))
                .putExtra(ViewProfilePictureActivity.EXTRA_DISPLAY_NAME, c.contact.displayName)
        )
    }

    override fun openMedia() {
        MediaBrowserActivity.launch(this, conversation ?: return)
    }

    override fun openMediaItem(key: String) {
        val attachment = media.firstOrNull { it.first.key == key }?.second ?: return
        val c = conversation
        if (c == null) ViewUtil.view(this, attachment)
        else ViewUtil.view(this, attachment, c.account.uuid, c.address.asBareJid().toString())
    }

    /** The former ChatFragment.openDetails (ConversationFragment.openConversationDetails). */
    override fun openDetails() {
        val c = conversation ?: return
        if (c.mode == Conversational.MODE_MULTI) {
            ConferenceDetailsActivity.open(this, c)
            return
        }
        startActivity(
            Intent(this, ContactDetailsActivity::class.java)
                .setAction(ContactDetailsActivity.ACTION_VIEW_CONTACT)
                .putExtra(EXTRA_ACCOUNT, c.account.jid.asBareJid().toString())
                .putExtra("contact", c.contact.address.toString())
        )
    }

    override fun openMembers() {
        val c = conversation ?: return
        startActivity(Intent(this, MucUsersActivity::class.java).putExtra("uuid", c.uuid))
    }

    override fun toggleBlock() {
        BlockContactDialog.show(this, conversation ?: return)
    }

    /** The chat's flow and text; a closed chat closes the profile like [deleteChat]. */
    override fun clearHistory() {
        val c = conversation ?: return
        confirmClearHistory(this, c, { xmppConnectionService }) { closed -> if (closed) finish() else refreshUi() }
    }

    /** Like deleting from the chat list; the chat screen closes itself when it sees it gone. */
    override fun deleteChat() {
        val c = conversation ?: return
        val name = c.name.toString()
        val group = c.mode == Conversation.MODE_MULTI
        confirmAction(this, deleteChatTitle(this, name, group), deleteChatText(this, name, group), if (group) R.string.leave else R.string.tatrak_delete) {
            val service = xmppConnectionService ?: return@confirmAction
            c.tatrakArchivedAt = null
            service.archiveConversation(c)
            finish()
        }
    }

    companion object {
        const val EXTRA_UUID = "uuid"
        private const val MEMBER_PREVIEW = 20
        private const val MEDIA_STRIP = 12
        /** Rows to scan: documents and voice messages are skipped, so ask for more than the strip shows. */
        private const val MEDIA_QUERY_LIMIT = 40
    }
}
