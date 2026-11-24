package am.cyberhayq.chat.ui.profile

import am.cyberhayq.chat.ui.theme.groupedBackground
import am.cyberhayq.chat.ui.chatlist.inErrorColor
import am.cyberhayq.chat.ui.chat.ChatHeader
import am.cyberhayq.chat.ui.chat.LetterAvatar
import am.cyberhayq.chat.ui.chatlist.FloatingTopMargin
import am.cyberhayq.chat.ui.chatlist.floatingBar
import am.cyberhayq.chat.ui.common.TatrakScaffold
import am.cyberhayq.chat.ui.theme.BarBackdrop
import am.cyberhayq.chat.ui.theme.backdropBlur
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.StatusBarIcons
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R
import eu.siacs.conversations.utils.UIHelper

@Immutable
data class ProfileMember(val key: String, val name: String, val role: String?, val avatar: Bitmap?)

/** One recent photo or video of the chat, for the strip above "Media". */
@Immutable
data class ProfileMedia(val key: String, val thumbnail: Bitmap)

@Immutable
data class ProfileState(
    val loaded: Boolean = false,
    val header: ChatHeader = ChatHeader(),
    /** Look-alike characters marked, as ContactDetailsActivity shows the address. */
    val address: AnnotatedString = AnnotatedString(""),
    /** Group subject; null for 1:1 chats. */
    val about: String? = null,
    val canCall: Boolean = false,
    val canVideo: Boolean = false,
    val ongoingCall: Boolean = false,
    /** Null when the server has no blocking support (or for groups). */
    val blocked: Boolean? = null,
    val members: List<ProfileMember> = emptyList(),
    val memberCount: Int = 0,
    /** Most recent shared photos and videos, newest first. */
    val media: List<ProfileMedia> = emptyList(),
)

interface ProfileActions {
    fun back()

    fun message()

    fun toggleMute()

    fun call(video: Boolean)

    fun returnToCall()

    fun search()

    fun copyAddress()

    fun share()

    fun showQr()

    fun openAvatar()

    fun openMedia()

    fun openMediaItem(key: String)

    /** Contact details (encryption keys) or group settings: the classic screens. */
    fun openDetails()

    fun openMembers()

    fun toggleBlock()

    fun clearHistory()

    fun deleteChat()
}

/** Telegram-style profile of a chat; keys and room configuration stay on the classic screens. */
@Composable
fun ProfileScreen(state: ProfileState, actions: ProfileActions, modifier: Modifier = Modifier) {
    val header = state.header
    // no Surface around it: without this, plain Text stays black in dark mode
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    TatrakScaffold(
        topBar = { ProfileTopBar(actions) },
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.groupedBackground),
        floatingTop = { FloatingProfileButtons(actions, it) },
    ) { top ->
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(top = top)) {
            item(key = "header") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp),
                ) {
                    Box(Modifier.clickable(role = Role.Image, onClick = actions::openAvatar)) {
                        LetterAvatar(
                            header.avatar,
                            header.title.take(1).uppercase(),
                            remember(header.title) { UIHelper.getColorForName(header.title) },
                            96.dp,
                            38.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        header.styledTitle?.inErrorColor() ?: AnnotatedString(header.title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (header.subtitle != null) {
                        Text(
                            header.subtitle,
                            fontSize = 14.sp,
                            color =
                                if (header.subtitleActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    ActionButtons(state, actions)
                }
                Spacer(Modifier.height(10.dp))
            }
            item(key = "info") {
                Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    if (state.about != null) {
                        InfoRow(AnnotatedString(state.about), stringResource(R.string.tatrak_profile_subject), {}, copy = false)
                        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    InfoRow(state.address.inErrorColor(), stringResource(R.string.account_settings_jabber_id), actions::copyAddress)
                }
                Spacer(Modifier.height(10.dp))
            }
            item(key = "links") {
                Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    if (state.media.isNotEmpty()) MediaStrip(state.media, actions)
                    LinkRow(R.drawable.ic_image_24dp, stringResource(R.string.view_media), actions::openMedia)
                    LinkRow(
                        R.drawable.ic_lock_24dp,
                        stringResource(
                            if (header.isGroup) R.string.tatrak_profile_group_settings
                            else R.string.tatrak_profile_keys
                        ),
                        actions::openDetails,
                        divider = false,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            if (header.isGroup && state.memberCount > 0) {
                item(key = "members-title") {
                    Text(
                        pluralMembers(state.memberCount),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier =
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
                    )
                }
                items(state.members, key = { "m:" + it.key }) { MemberRow(it) }
                item(key = "members-all") {
                    LinkRow(R.drawable.ic_group_24dp, stringResource(R.string.tatrak_profile_all_members), actions::openMembers, divider = false)
                    Spacer(Modifier.height(10.dp))
                }
            }
            item(key = "danger") {
                Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    if (state.blocked != null) {
                        LinkRow(
                            R.drawable.tatrak_ic_block_24dp,
                            stringResource(if (state.blocked) R.string.unblock_contact else R.string.block_contact),
                            actions::toggleBlock,
                            danger = !state.blocked,
                        )
                    }
                    LinkRow(R.drawable.ic_delete_24dp, stringResource(R.string.clear_conversation_history), actions::clearHistory, danger = true)
                    LinkRow(
                        R.drawable.ic_logout_24dp,
                        stringResource(if (header.isGroup) R.string.tatrak_profile_leave else R.string.tatrak_delete_chat),
                        actions::deleteChat,
                        danger = true,
                        divider = false,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    }
}

@Composable
private fun pluralMembers(count: Int): String =
    LocalContext.current.resources
        .getQuantityString(R.plurals.x_participants, count, count)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileTopBar(actions: ProfileActions) {
    val bar = TatrakTheme.chatColors
    val onBar = bar.onTopBar
    StatusBarIcons(dark = bar.topBar.luminance() > 0.5f)
    TopAppBar(
        // landscape: keep the actions clear of a side navigation bar or cutout
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = bar.topBar,
                titleContentColor = onBar,
                navigationIconContentColor = onBar,
                actionIconContentColor = onBar,
            ),
        navigationIcon = {
            IconButton(onClick = actions::back) {
                Icon(painterResource(R.drawable.ic_arrow_back_24dp), stringResource(R.string.tatrak_chat_back))
            }
        },
        title = {},
        actions = { ProfileMenu(actions) },
    )
}

/**
 * Floating bars: Telegram 13's profile header buttons, the back button in a round glass and the others in a capsule, 6 dp
 * from the sides and [FloatingTopMargin] under the status bar; no bar between them, the header runs under the status bar.
 */
@Composable
private fun FloatingProfileButtons(actions: ProfileActions, backdrop: BarBackdrop?) {
    val bar = TatrakTheme.chatColors
    StatusBarIcons(dark = bar.topBar.luminance() > 0.5f)
    val fill = floatingBar(backdrop)
    Row(
        // the margins take touches too, as the other floating bars' do
        Modifier.fillMaxWidth()
            .pointerInput(Unit) {}
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 6.dp, end = 6.dp, top = FloatingTopMargin)
    ) {
        Surface(shape = CircleShape, color = fill, contentColor = bar.onTopBar, shadowElevation = 3.dp, modifier = Modifier.backdropBlur(backdrop, CircleShape)) {
            IconButton(onClick = actions::back) {
                Icon(painterResource(R.drawable.ic_arrow_back_24dp), stringResource(R.string.tatrak_chat_back))
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(shape = CircleShape, color = fill, contentColor = bar.onTopBar, shadowElevation = 3.dp, modifier = Modifier.backdropBlur(backdrop, CircleShape)) {
            Row { ProfileMenu(actions) }
        }
    }
}

@Composable
private fun ProfileMenu(actions: ProfileActions) {
    var menuOpen by remember { mutableStateOf(false) }
    IconButton(onClick = actions::showQr) {
        Icon(painterResource(R.drawable.ic_qr_code_24dp), stringResource(R.string.show_qr_code))
    }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(painterResource(R.drawable.tatrak_ic_more_vert_24dp), stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_uri_with)) },
                onClick = {
                    menuOpen = false
                    actions.share()
                },
            )
        }
    }
}

@Composable
private fun ActionButtons(state: ProfileState, actions: ProfileActions) {
    val buttons = buildList {
        add(Triple(R.drawable.ic_chat_24dp, R.string.message, actions::message))
        add(
            if (state.header.isMuted) Triple(R.drawable.ic_notifications_24dp, R.string.tatrak_unmute, actions::toggleMute)
            else Triple(R.drawable.ic_notifications_off_24dp, R.string.tatrak_mute, actions::toggleMute)
        )
        if (state.ongoingCall) {
            add(Triple(R.drawable.ic_phone_in_talk_24dp, R.string.ongoing_call, actions::returnToCall))
        } else if (state.canCall) {
            add(Triple(R.drawable.ic_call_24dp, R.string.audio_call, { actions.call(video = false) }))
            if (state.canVideo) add(Triple(R.drawable.ic_videocam_24dp, R.string.video_call, { actions.call(video = true) }))
        }
        add(Triple(R.drawable.ic_search_24dp, R.string.tatrak_profile_search, actions::search))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        buttons.forEach { (icon, label, onClick) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(role = Role.Button, onClick = onClick)
                        .padding(vertical = 10.dp),
            ) {
                Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(label), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MediaStrip(media: List<ProfileMedia>, actions: ProfileActions) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
    ) {
        items(media, key = { it.key }) { item ->
            Image(
                item.thumbnail.asImageBitmap(),
                null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.size(MEDIA_THUMBNAIL_DP.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Image) { actions.openMediaItem(item.key) },
            )
        }
    }
}

/** Side of a media strip thumbnail; the activity loads previews at this size. */
const val MEDIA_THUMBNAIL_DP = 80

@Composable
private fun InfoRow(value: AnnotatedString, label: String, onClick: () -> Unit, copy: Boolean = true) {
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = copy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(value, fontSize = 16.sp)
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkRow(icon: Int, title: String, onClick: () -> Unit, danger: Boolean = false, divider: Boolean = true) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(
                painterResource(icon),
                null,
                tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(24.dp))
            Text(title, fontSize = 16.sp, color = color)
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun MemberRow(member: ProfileMember) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        LetterAvatar(
            member.avatar,
            member.name.take(1).uppercase(),
            remember(member.name) { UIHelper.getColorForName(member.name) },
            40.dp,
            17.sp,
        )
        Spacer(Modifier.width(16.dp))
        Text(member.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (member.role != null) {
            Text(member.role, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
