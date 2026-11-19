package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.theme.contentOn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.toSize
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.LocalAvatarDotOffset
import am.cyberhayq.chat.ui.theme.StatusBarIcons
import am.cyberhayq.chat.ui.theme.rememberStringPreference
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.Stable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.utils.UIHelper
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Everything the chat screen asks of its host; messages are referenced by uuid only. */
interface ChatActions {
    fun back()

    fun openDetails()

    fun openSearch()

    fun toggleMute()

    fun togglePin()

    fun openClassic()

    /** Classic snackbar "Add back" (contact not in the roster asked to subscribe). */
    fun addBack()

    /** Classic snackbar "Allow" (roster contact asked to subscribe). */
    fun allowSubscription()

    /** An R.id.encryption_choice_* of the classic menu. */
    fun selectEncryption(itemId: Int)

    fun send()

    fun attachGallery()

    /** Camera photo, the classic ATTACHMENT_CHOICE_TAKE_PHOTO flow. */
    fun takePhoto()

    /** Camera video, the classic ATTACHMENT_CHOICE_RECORD_VIDEO flow. */
    fun recordVideo()

    fun attachFile()

    fun attachLocation()

    /** A phone contact as a vCard file, the classic ATTACHMENT_CHOICE_CONTACT flow. */
    fun attachContact()

    /** Hold-to-record began; false when it could not (permission asked, PGP, no microphone). */
    fun startRecording(): Boolean

    fun stopRecording(send: Boolean)

    fun loadMore()

    /** The newest message on screen once scrolling settles. */
    fun markRead(uuid: String)

    fun reply(uuid: String)

    fun cancelReply()

    fun edit(uuid: String)

    fun cancelEdit()

    fun copy(uuid: String)

    fun retry(uuid: String)

    fun share(uuid: String)

    fun toggleReaction(uuid: String, emoji: String)

    /** A reaction from the picker sheet, as AddReactionActivity adds it (never removes one). */
    fun addReaction(uuid: String, emoji: String)

    fun openMedia(uuid: String)

    fun download(uuid: String)

    fun saveFile(uuid: String)

    fun cancelTransfer(uuid: String)

    fun sendSticker(file: File)

    /** Picks pictures from the gallery and adds them to [StickerStore]. */
    fun importStickers()

    /** Uploads the own set as a pack and puts its link into the composer. */
    fun shareStickers()

    /** Copies a received sticker into the own set. */
    fun addSticker(uuid: String)

    /** This chat's wallpaper spec ([Wallpapers]); null follows the app-wide one. */
    fun setWallpaper(spec: String?)

    /** Picks a photo from the gallery and makes it this chat's wallpaper. */
    fun pickWallpaperPhoto()

    /** Asks: delete on this device only, or (own messages) for everyone via XEP-0424. */
    fun delete(uuid: String)

    /** Selection bar: [delete]'s question once for all [uuids] (oldest first), then [onDeleted] once they are gone. */
    fun deleteMessages(uuids: List<String>, onDeleted: () -> Unit) {}

    /** Selection bar: the texts of [uuids] (oldest first) on the clipboard in Telegram's format. */
    fun copyMessages(uuids: List<String>) {}

    /** Selection bar: one chat picker for [uuids] (oldest first); the texts arrive as a quote, the files together. */
    fun forwardMessages(uuids: List<String>) {}

    fun openLocation(uuid: String)

    fun toggleVoice(uuid: String)

    /** [fraction] of the clip that is loaded in the player, 0..1. */
    fun seekVoice(uuid: String, fraction: Float)

    fun openLink(url: String)

    /** Called off the main thread by the image bubbles; null while unavailable. */
    suspend fun loadThumbnail(uuid: String, sizePx: Int): Bitmap?

    /** The thumbnail if it is already in memory (a map lookup), so a rebound bubble starts with it. */
    fun cachedThumbnail(uuid: String): Bitmap? = null

    /** Square preview of a picked attachment waiting for Send; null for files without one. */
    suspend fun previewThumbnail(key: String, sizePx: Int): Bitmap? = null

    fun removePreview(key: String) {}

    /** Header call button; the classic audio / video call flow with its permission checks. */
    fun call(video: Boolean) {}

    /** Opens the running call with this contact. */
    fun returnToCall() {}

    fun viewEditHistory(uuid: String) {}

    fun showError(uuid: String) {}

    /** The key list (contact's or own account's) with this message's key highlighted. */
    fun openMessageKey(uuid: String) {}

    fun openWith(uuid: String) {}

    fun deleteFile(uuid: String) {}

    fun copyLink(uuid: String) {}

    fun copyUrl(uuid: String) {}

    /** Opens the in-app chat picker with this message, not the system chooser. */
    fun forward(uuid: String) {}

    fun archive() {}

    fun clearHistory() {}

    fun toggleBlock() {}

    /** Ctrl+Up: edit the newest own message; false when there is none. */
    fun editLast(): Boolean = false

    /** Group avatar tap: the classic onContactPictureClicked, which puts the sender's nick into the message. */
    fun mentionSender(uuid: String) {}

    /** The group's participants for the "@" list, own nick left out; called off the main thread. */
    suspend fun mentionCandidates(): List<MentionCandidate> = emptyList()

    /** A participant's avatar for the "@" list; null while unavailable. */
    suspend fun mentionAvatar(nick: String, sizePx: Int): Bitmap? = null

    /** These mentions ([ChatState.mentions]) were shown on screen or jumped to. */
    fun mentionsSeen(uuids: Collection<String>) {}

    /** The loaded chat has made its initial scroll; the next layout shows its first messages. */
    fun listReady() {}
}

/**
 * Scroll to and briefly highlight this message once it is in the list (a search result).
 * [id] tells two requests for the same message apart.
 */
@Immutable
data class MessageFocus(val uuid: String, val id: Long = System.nanoTime())

@Immutable
data class ReplyPreview(val uuid: String, val name: String, val text: String, val color: Int)

/** Older messages are asked for while this many items are left above the top visible one. */
private const val LOAD_MORE_AHEAD = 25

@Composable
fun ChatScreen(
    state: ChatState,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    reply: ReplyPreview?,
    actions: ChatActions,
    voice: StateFlow<VoicePlayback>,
    recording: StateFlow<VoiceRecording>,
    modifier: Modifier = Modifier,
    focus: MessageFocus? = null,
    onFocused: (MessageFocus) -> Unit = {},
    /** Keys of picked attachments waiting for Send. */
    previews: List<String> = emptyList(),
    /** A short notice (string resource) that fades in at the top of the chat. */
    notice: Int? = null,
    /** The notice has faded out: clear it, so the next one shows again and an old one does not come back. */
    onNoticeShown: () -> Unit = {},
    /** Mentions already shown or jumped to; dropped from the "@" button. */
    seenMentions: Set<String> = emptySet(),
) {
    val menu = remember(state.conversationUuid) { mutableStateOf<Pair<String, Rect>?>(null) }
    val menuKey = remember(menu) { derivedStateOf { menu.value?.first } }
    // the menu overlay fills this screen; the long-pressed bubble's bounds are taken relative to it
    val screen = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // the message whose "more reactions" picker is open; dropped when the fragment shows another conversation
    var reactingTo by rememberSaveable(state.conversationUuid) { mutableStateOf<String?>(null) }
    val reactionPicker = remember { EmojiPickerSlot() }
    var infoFor by rememberSaveable(state.conversationUuid) { mutableStateOf<String?>(null) }
    var wallpaperOpen by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable(state.conversationUuid) { mutableStateOf(emptyList<String>()) }
    // newest first like the list; messages that left the list drop out
    val selected = remember(selection, state.items) {
        if (selection.isEmpty()) {
            emptyList()
        } else {
            val keys = selection.toHashSet()
            state.items.filterIsInstance<MessageItem>().filter { it.key in keys }
        }
    }
    val selecting = selected.isNotEmpty()
    // vanished keys go from the saved selection too, so they neither block the menu nor come back selected
    LaunchedEffect(selected) { if (state.items.isNotEmpty() && selected.size != selection.size) selection = selected.map { it.key } }
    BackHandler(enabled = selecting) { selection = emptyList() }
    BlockSwipeBack(selecting)
    val uriHandler = remember(actions) {
        object : UriHandler {
            override fun openUri(uri: String) = actions.openLink(uri)
        }
    }
    CompositionLocalProvider(
        LocalUriHandler provides uriHandler,
        // Material's default tracking looks loose next to the bubbles
        LocalTextStyle provides LocalTextStyle.current.copy(letterSpacing = 0.sp),
        LocalMenuMessage provides menuKey,
    ) {
        Box(modifier.fillMaxSize().onPlaced { screen[0] = it }) {
            val global = rememberStringPreference(AppSettings.WALLPAPER)
            val wallpaper = state.wallpaper ?: global
            val dark = ChatScreenTheme.colors.isDark
            ChatWallpaper(wallpaper, dark, Modifier.fillMaxSize())
            val tint =
                Wallpapers.preset(wallpaper)?.takeIf { it.id != Wallpapers.DEFAULT }
                    ?.let { (if (dark) it.night else it.day)[0] }
            CompositionLocalProvider(LocalWallpaperTint provides tint) {
                Column(Modifier.fillMaxSize()) {
                    if (selecting) {
                        val oldestFirst = selected.asReversed()
                        fun keys(can: (MessageActions) -> Boolean) = oldestFirst.filter { can(it.actions) }.map { it.key }.takeIf { it.isNotEmpty() }
                        SelectionBar(
                            count = selected.size,
                            onClose = { selection = emptyList() },
                            onCopy = keys { it.canCopy }?.let { { actions.copyMessages(it); selection = emptyList() } },
                            onForward = keys { it.canShare }?.let { { actions.forwardMessages(it); selection = emptyList() } },
                            onDelete =
                                if (oldestFirst.all { it.actions.canDelete }) {
                                    { actions.deleteMessages(oldestFirst.map { it.key }) { selection = emptyList() } }
                                } else {
                                    null
                                },
                        )
                    } else {
                        ChatTopBar(state.header, state.composer.encryption, actions) { wallpaperOpen = true }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        key(state.conversationUuid) {
                            MessageList(
                                state, actions, voice, focus, onFocused, seenMentions,
                                selection = remember(selected) { selected.map { it.key } },
                                onSelectionChange = { selection = it },
                                onMenu = { item, bubble -> if (!selecting) menu.value = item.key to bounds(screen[0], bubble) },
                            )
                        }
                        state.banner?.let { ChatBannerBar(it, actions, Modifier.align(Alignment.TopCenter)) }
                        ChatNotice(notice, onNoticeShown, Modifier.align(Alignment.TopCenter).padding(top = if (state.banner != null) 56.dp else 12.dp))
                    }
                    Composer(
                        composer = state.composer,
                        input = input,
                        onInputChange = onInputChange,
                        reply = reply,
                        actions = actions,
                        recording = recording,
                        previews = previews,
                        newest = state.items.firstOrNull()?.key,
                    )
                }
            }
            val open = menu.value
            val menuItem = open?.let { (key) -> state.items.firstOrNull { it.key == key } as? MessageItem }
            if (open != null && menuItem != null) {
                MessageMenu(menuItem, state.header.isGroup, actions, voice, anchor = open.second, onMoreReactions = { reactingTo = it }, onInfo = { infoFor = it }) { menu.value = null }
            }
        }
    }
    if (wallpaperOpen) {
        WallpaperSheet(
            current = state.wallpaper,
            forChat = true,
            onPick = actions::setWallpaper,
            onPhoto = actions::pickWallpaperPhoto,
            onDismiss = { wallpaperOpen = false },
        )
    }
    reactingTo?.let { uuid ->
        ReactionSheet(reactionPicker, onPick = { actions.addReaction(uuid, it) }, onDismiss = { reactingTo = null })
    }
    infoFor?.let { key ->
        (state.items.firstOrNull { it.key == key } as? MessageItem)?.let { item ->
            MessageInfoSheet(item, onOpenKey = { actions.openMessageKey(key) }, onDismiss = { infoFor = null })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(header: ChatHeader, encryption: Int?, actions: ChatActions, onWallpaper: () -> Unit) {
    // same header as the chat list (Telegram's default day theme: white bar, dark text)
    val bar = TatrakTheme.chatColors
    val onBar = bar.onTopBar
    StatusBarIcons(dark = bar.topBar.luminance() > 0.5f)
    var menuOpen by remember { mutableStateOf(false) }
    var encryptionOpen by remember { mutableStateOf(false) }
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
                Icon(
                    painterResource(R.drawable.ic_arrow_back_24dp),
                    contentDescription = stringResource(R.string.tatrak_chat_back),
                )
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = actions::openDetails),
            ) {
                Box(Modifier.size(40.dp)) {
                    LetterAvatar(
                        header.avatar,
                        header.title.take(1).uppercase(),
                        remember(header.title) { UIHelper.getColorForName(header.title) },
                        40.dp,
                        17.sp,
                    )
                    if (header.isOnline) {
                        val onlineDescription = stringResource(R.string.presence_online)
                        Box(
                            Modifier.align(Alignment.BottomEnd)
                                .size(14.dp)
                                .offset(LocalAvatarDotOffset.current, LocalAvatarDotOffset.current)
                                .semantics { contentDescription = onlineDescription }
                                .border(2.dp, bar.topBar, CircleShape)
                                .padding(2.dp)
                                .background(bar.online, CircleShape)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        header.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (header.subtitle != null) {
                        Text(
                            header.subtitle,
                            fontSize = 14.sp,
                            color = if (header.subtitleActive) MaterialTheme.colorScheme.primary else bar.onTopBarMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        actions = {
            if (header.canCall && header.canVideo && !header.ongoingCall) {
                IconButton(onClick = { actions.call(true) }) {
                    Icon(painterResource(R.drawable.ic_videocam_24dp), contentDescription = stringResource(R.string.video_call))
                }
            }
            if (header.canCall) {
                IconButton(onClick = { if (header.ongoingCall) actions.returnToCall() else actions.call(false) }) {
                    Icon(
                        painterResource(if (header.ongoingCall) R.drawable.ic_phone_in_talk_24dp else R.drawable.ic_call_24dp),
                        contentDescription = stringResource(if (header.ongoingCall) R.string.ongoing_call else R.string.audio_call),
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painterResource(R.drawable.tatrak_ic_more_vert_24dp),
                        contentDescription = stringResource(R.string.more_options),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = {
                        menuOpen = false
                        encryptionOpen = false
                    },
                ) {
                    if (encryptionOpen && encryption != null) {
                        // the classic menu's single-choice group
                        listOf(
                                Triple(R.id.encryption_choice_none, Message.ENCRYPTION_NONE, R.string.encryption_choice_unencrypted),
                                Triple(R.id.encryption_choice_axolotl, Message.ENCRYPTION_AXOLOTL, R.string.encryption_choice_omemo),
                                Triple(R.id.encryption_choice_pgp, Message.ENCRYPTION_PGP, R.string.encryption_choice_pgp),
                            )
                            .forEach { (id, value, label) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    leadingIcon = {
                                        if (encryption == value) {
                                            Icon(painterResource(R.drawable.ic_check_24dp), contentDescription = null)
                                        } else {
                                            Spacer(Modifier.size(24.dp))
                                        }
                                    },
                                    onClick = {
                                        menuOpen = false
                                        encryptionOpen = false
                                        actions.selectEncryption(id)
                                    },
                                )
                            }
                        return@DropdownMenu
                    }
                    (listOf(
                            Triple(R.string.search_messages, R.drawable.ic_search_24dp, actions::openSearch),
                            if (header.isMuted) {
                                Triple(R.string.tatrak_unmute, R.drawable.ic_notifications_24dp, actions::toggleMute)
                            } else {
                                Triple(R.string.tatrak_mute, R.drawable.ic_notifications_off_24dp, actions::toggleMute)
                            },
                            Triple(
                                if (header.isPinned) R.string.tatrak_unpin else R.string.tatrak_pin,
                                R.drawable.tatrak_ic_pin_24dp,
                                actions::togglePin,
                            ),
                            Triple(R.string.tatrak_wallpaper, R.drawable.ic_image_24dp, onWallpaper),
                            Triple(R.string.action_archive_chat, R.drawable.ic_archive_24dp, actions::archive),
                            Triple(R.string.clear_conversation_history, R.drawable.ic_delete_24dp, actions::clearHistory),
                            Triple(R.string.tatrak_chat_classic_view, R.drawable.ic_open_with_24dp, actions::openClassic),
                        ) +
                            listOfNotNull(
                                header.isBlocked?.let {
                                    Triple(
                                        if (it) R.string.unblock_contact else R.string.block_contact,
                                        R.drawable.tatrak_ic_block_24dp,
                                        actions::toggleBlock,
                                    )
                                }
                            ))
                        .forEach { (label, icon, action) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                leadingIcon = {
                                    Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                onClick = {
                                        menuOpen = false
                                    action()
                                },
                            )
                        }
                    if (encryption != null) {
                        // opens the classic menu's choices in this menu
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when (encryption) {
                                            Message.ENCRYPTION_PGP -> R.string.encrypted_with_openpgp
                                            Message.ENCRYPTION_AXOLOTL -> R.string.encrypted_with_omemo
                                            else -> R.string.not_encrypted
                                        }
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    painterResource(
                                        if (encryption == Message.ENCRYPTION_NONE) R.drawable.ic_lock_open_outline_24dp else R.drawable.ic_lock_24dp
                                    ),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = { encryptionOpen = true },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun MessageList(
    state: ChatState,
    actions: ChatActions,
    voice: StateFlow<VoicePlayback>,
    focus: MessageFocus?,
    onFocused: (MessageFocus) -> Unit,
    seenMentions: Set<String>,
    /** Selected message keys, newest first. */
    selection: List<String>,
    onSelectionChange: (List<String>) -> Unit,
    onMenu: (MessageItem, LayoutCoordinates?) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val items by rememberUpdatedState(state.items)
    val isGroup = state.header.isGroup

    // no read markers until the chat has scrolled to the unread divider
    var initialScrollDone by remember(state.generation) { mutableStateOf(false) }
    InitialScroll(state, listState) {
        initialScrollDone = true
        actions.listReady()
    }
    FollowNewMessages(state, listState)

    LaunchedEffect(listState) {
        snapshotFlow {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                // keyed by size: still near the top after a small page loads asks for the next one. 25 items ahead,
                // Telegram's checkLoadCount while scrolling (ChatActivity); with 5 a fast fling hit the top first
                if (last >= 0 && last >= items.size - 1 - LOAD_MORE_AHEAD) items.size else -1
            }
            .filter { it >= 0 }
            .distinctUntilChanged()
            .collect { actions.loadMore() }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
                if (!initialScrollDone || listState.isScrollInProgress) {
                    null
                } else {
                    listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull {
                        (items.getOrNull(it.index) as? MessageItem)?.key
                    }
                }
            }
            .filterNotNull()
            .distinctUntilChanged()
            .collect(actions::markRead)
    }

    Box(Modifier.fillMaxSize()) {
        if (state.loaded && state.items.isEmpty()) {
            EmptyChat(Modifier.align(Alignment.Center))
        }
        // stable lambda: a new one would recompose every row through the composition local
        val currentItems by rememberUpdatedState(items)
        val flash = remember { MessageFlash() }
        val openMessage: (String) -> Unit = remember(listState) {
            { key ->
                val index = currentItems.indexOfFirst { it.key == key }
                if (index >= 0) {
                    scope.launch {
                        listState.animateScrollToItem(index)
                        flash.run(key)
                    }
                }
            }
        }
        // a search result: waits until the host has loaded the message, then centres and flashes it
        val currentOnFocused by rememberUpdatedState(onFocused)
        LaunchedEffect(focus, state.items, initialScrollDone) {
            val target = focus ?: return@LaunchedEffect
            if (!initialScrollDone) return@LaunchedEffect
            val index = state.items.indexOfFirst { it.key == target.uuid }
            if (index < 0) return@LaunchedEffect
            currentOnFocused(target)
            // not in this effect: the next snapshot (avatars) would cancel it halfway
            scope.launch {
                listState.centerOn(index)
                flash.run(target.uuid)
            }
        }
        // mentions of our nick: done once shown on screen (after the initial scroll) or jumped to; the view model keeps them
        val pendingMentions = remember(state.mentions, seenMentions) { state.mentions.filterNot { it in seenMentions } }
        LaunchedEffect(listState, pendingMentions) {
            if (pendingMentions.isEmpty()) return@LaunchedEffect
            val wanted = pendingMentions.toHashSet()
            snapshotFlow {
                    if (!initialScrollDone) emptyList() else listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.filter { it in wanted }
                }
                .filter { it.isNotEmpty() }
                .collect(actions::mentionsSeen)
        }
        val currentSelection by rememberUpdatedState(selection)
        val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
        // set by a row's long press, read by the list's pointer handler for the rest of that gesture
        val drag = remember { arrayOfNulls<SelectionDrag>(1) }
        val onSelect: (MessageItem) -> Unit = remember {
            { item ->
                val next = toggled(currentSelection, item.key)
                // from an unselected row the drag selects, even when the cap kept this row out
                drag[0] = SelectionDrag(item.key, next, item.key !in currentSelection)
                currentOnSelectionChange(next)
            }
        }
        val selectionSet = remember(selection) { selection.toHashSet() }
        CompositionLocalProvider(LocalOpenMessage provides openMessage) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(top = if (state.banner != null) 52.dp else 8.dp, bottom = 8.dp),
                modifier =
                    Modifier.fillMaxSize().pointerInput(listState) {
                        selectionGestures(listState, drag, scope, { currentSelection }, { currentItems }) { currentOnSelectionChange(it) }
                    },
            ) {
                items(state.items, key = { it.key }, contentType = { it.contentType }) { item ->
                    when (item) {
                        is MessageItem ->
                            MessageRow(
                                item = item,
                                isGroup = isGroup,
                                actions = actions,
                                voice = voice,
                                onMenu = onMenu,
                                onSelect = onSelect,
                                onSwipeReply = { actions.reply(it.key) },
                                modifier = Modifier.animateItem().then(flash.modifier(item.key)),
                                selecting = selection.isNotEmpty(),
                                selected = item.key in selectionSet,
                            )
                        is DateItem -> DateChip(item.day, Modifier.animateItem().padding(vertical = 6.dp))
                        is UnreadItem -> UnreadDivider(Modifier.animateItem())
                        is ServiceItem -> ServiceMessage(item.text, Modifier.animateItem())
                    }
                }
            }
        }
        FloatingDate(
            listState,
            { index -> items.getOrNull(index)?.day },
            Modifier.align(Alignment.TopCenter).padding(top = if (state.banner != null) 50.dp else 6.dp),
        )
        val showFab by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp)) {
            // Telegram's "@" above "scroll down": the oldest unread mention first
            AnimatedVisibility(visible = pendingMentions.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                JumpButton(pendingMentions.size, stringResource(R.string.tatrak_chat_next_mention), onClick = {
                    val target = pendingMentions.firstOrNull() ?: return@JumpButton
                    actions.mentionsSeen(listOf(target))
                    val index = state.items.indexOfFirst { it.key == target }
                    if (index >= 0) {
                        scope.launch {
                            listState.centerOn(index)
                            flash.run(target)
                        }
                    }
                }) {
                    Text("@", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AnimatedVisibility(visible = showFab, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                ScrollToBottomButton(state.unreadCount) {
                    scope.launch {
                        if (listState.firstVisibleItemIndex > 30) {
                            listState.scrollToItem(0)
                        } else {
                            listState.animateScrollToItem(0)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Scrolls [index] to the middle of the viewport: search results and mention jumps. Measured from where the item landed:
 * near the oldest loaded message scrollToItem stops early, and a fixed half-viewport step pushed it off screen.
 */
private suspend fun LazyListState.centerOn(index: Int) {
    scrollToItem(index)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val middle = ((layoutInfo.viewportSize.height - item.size) / 2f).coerceAtLeast(0f)
    scrollBy(item.offset - middle)
}

/** The short highlight of a message jumped to (search result, quoted message), as in Telegram. */
@Stable
private class MessageFlash {
    var key by mutableStateOf<String?>(null)
        private set

    val alpha = Animatable(0f)

    suspend fun run(target: String) {
        key = target
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(200))
        delay(700)
        alpha.animateTo(0f, tween(600))
        if (key == target) key = null
    }

    /** Drawn behind the whole row, so it reads as a band across the screen. */
    @Composable
    fun modifier(itemKey: String): Modifier {
        if (key != itemKey) return Modifier
        val color = MaterialTheme.colorScheme.primary
        return Modifier.drawBehind { drawRect(color.copy(alpha = 0.22f * alpha.value)) }
    }
}

/** Opens a newly bound chat at the "Unread messages" divider, otherwise at the bottom. */
@Composable
private fun InitialScroll(state: ChatState, listState: LazyListState, onDone: () -> Unit) {
    val done = remember(state.generation) { booleanArrayOf(false) }
    LaunchedEffect(state.generation, state.loaded) {
        if (!state.loaded || done[0]) return@LaunchedEffect
        done[0] = true
        val divider = state.items.indexOfFirst { it is UnreadItem }
        if (divider >= 0) {
            listState.scrollToItem(divider)
            val viewport = listState.layoutInfo.viewportSize.height
            listState.scrollBy(-(viewport * 0.75f))
        }
        onDone()
    }
}

/** Keeps the newest message in view when it arrives while the user is at the bottom. */
@Composable
private fun FollowNewMessages(state: ChatState, listState: LazyListState) {
    val newest = state.items.firstOrNull() as? MessageItem
    val previous = remember(state.generation) { arrayOfNulls<String>(1) }
    val scope = rememberCoroutineScope()
    // SideEffect: before this frame's measure, so the new item lands in the same frame as the composer change
    SideEffect {
        val key = newest?.key ?: return@SideEffect
        val before = previous[0]
        previous[0] = key
        if (before == null || before == key) return@SideEffect
        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 && !listState.isScrollInProgress) {
            // stay at the bottom instead of keeping the old first item by key: animateItem then slides the older
            // messages up with the same spring as the composer's height, so a shrinking composer does not jerk the list
            listState.requestScrollToItem(0)
        } else if (newest.isOutgoing || listState.firstVisibleItemIndex <= 2) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }
}

@Composable
private fun FloatingDate(
    listState: LazyListState,
    dayAt: (Int) -> String?,
    modifier: Modifier,
) {
    val day by remember {
        derivedStateOf {
            val top = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            top?.let { dayAt(it.index) }
        }
    }
    var visible by remember { mutableStateOf(false) }
    val scrolling = listState.isScrollInProgress
    LaunchedEffect(scrolling) {
        if (scrolling) {
            visible = true
        } else {
            delay(1200)
            visible = false
        }
    }
    AnimatedVisibility(visible && day != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        DateChip(day.orEmpty())
    }
}

@Composable
private fun DateChip(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = ChatScreenTheme.colors.serviceText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier =
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(ChatScreenTheme.colors.serviceBackground)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ServiceMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = ChatScreenTheme.colors.serviceText,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(ChatScreenTheme.colors.serviceBackground)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun UnreadDivider(modifier: Modifier = Modifier) {
    val colors = ChatScreenTheme.colors
    Box(
        modifier.fillMaxWidth().padding(vertical = 8.dp).background(colors.unreadDivider),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.tatrak_chat_unread_messages),
            color = colors.unreadDividerText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 5.dp),
        )
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .padding(horizontal = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ChatScreenTheme.colors.serviceBackground)
                .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            stringResource(R.string.tatrak_chat_empty_title),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.tatrak_chat_empty_subtitle),
            color = Color.White,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ScrollToBottomButton(unread: Int, onClick: () -> Unit) {
    JumpButton(unread, null, onClick) {
        Icon(
            painterResource(R.drawable.tatrak_chat_ic_expand_more_24dp),
            contentDescription = stringResource(R.string.tatrak_chat_scroll_to_bottom),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** A round button over the list with a [count] badge on top; [description] names it when [content] does not. */
@Composable
private fun JumpButton(count: Int, description: String?, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            modifier =
                Modifier.padding(top = 10.dp)
                    .size(44.dp)
                    .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        ) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
        if (count > 0) {
            Text(
                if (count > 999) "999+" else count.toString(),
                color = contentOn(TatrakTheme.chatColors.unreadBadge),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .widthIn(min = 22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(TatrakTheme.chatColors.unreadBadge)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * A short notice that does not flash: it fades in after a moment, stays long enough to read and
 * fades out slowly. A one-shot event: [onShown] clears it.
 */
@Composable
private fun ChatNotice(notice: Int?, onShown: () -> Unit, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<Int?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(notice) {
        if (notice == null) {
            visible = false
            return@LaunchedEffect
        }
        delay(700)
        shown = notice
        visible = true
        delay(2500)
        visible = false
        onShown()
    }
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(tween(450)), exit = fadeOut(tween(700))) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = ChatScreenTheme.colors.serviceBackground,
            contentColor = ChatScreenTheme.colors.serviceText,
        ) {
            Text(
                shown?.let { stringResource(it) } ?: "",
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ChatBannerBar(banner: ChatBanner, actions: ChatActions, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp).height(44.dp),
        ) {
            Text(
                stringResource(
                    when (banner) {
                        ChatBanner.ACCOUNT_DISABLED -> R.string.tatrak_chat_account_disabled
                        ChatBanner.CONNECTING -> R.string.tatrak_connecting
                        ChatBanner.GROUP_OFFLINE -> R.string.tatrak_chat_group_offline
                        ChatBanner.ADD_BACK -> R.string.contact_added_you
                        ChatBanner.ALLOW_SUBSCRIPTION -> R.string.contact_asks_for_presence_subscription
                    }
                ),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            when (banner) {
                ChatBanner.GROUP_OFFLINE ->
                    TextButton(onClick = actions::openClassic) {
                        Text(stringResource(R.string.tatrak_chat_classic_view))
                    }
                ChatBanner.ADD_BACK ->
                    TextButton(onClick = actions::addBack) { Text(stringResource(R.string.add_back)) }
                ChatBanner.ALLOW_SUBSCRIPTION ->
                    TextButton(onClick = actions::allowSubscription) { Text(stringResource(R.string.allow)) }
                else -> Unit
            }
        }
    }
}

/** [bubble]'s bounds in [screen]'s coordinates, unclipped; empty when either is gone. */
private fun bounds(screen: LayoutCoordinates?, bubble: LayoutCoordinates?): Rect =
    if (screen != null && bubble != null && screen.isAttached && bubble.isAttached) {
        Rect(screen.localPositionOf(bubble, Offset.Zero), bubble.size.toSize())
    } else {
        Rect.Zero
    }
