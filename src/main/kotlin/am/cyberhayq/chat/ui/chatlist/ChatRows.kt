package am.cyberhayq.chat.ui.chatlist

import am.cyberhayq.chat.ui.state.ReportComposition
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.LocalAvatarDotOffset
import am.cyberhayq.chat.ui.theme.LocalAvatarShape
import am.cyberhayq.chat.ui.theme.contentOn
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

internal val AvatarSize = 54.dp
internal val RowHeight = 72.dp
internal val RowHorizontalPadding = 10.dp
internal val ArchiveIconSize = 26.dp
private val AvatarTextGap = 12.dp
private val DraftColor = Color(0xFFDD4B39)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(
    chat: ChatListItem,
    time: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReportComposition(chat.uuid)
    val colors = MaterialTheme.colorScheme
    val chatColors = TatrakTheme.chatColors
    RowFrame(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        avatar = {
            ChatAvatar(chat.avatar, chat.isOnline && !selected && !chat.isSavedMessages, AvatarSize, selected, chat.isSavedMessages)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(
                    chat.styledTitle?.inErrorColor() ?: AnnotatedString(chat.title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // ConversationAdapter's order: a running call, then muted, then mentions only
                val status =
                    when {
                        chat.ongoingCall -> R.drawable.ic_phone_in_talk_24dp to R.string.ongoing_call
                        chat.isMuted -> R.drawable.ic_volume_off_24dp to null
                        chat.mentionsOnly -> R.drawable.ic_notifications_none_24dp to R.string.notify_only_when_highlighted
                        else -> null
                    }
                status?.let { (icon, description) ->
                    Icon(
                        painterResource(icon),
                        contentDescription = description?.let { stringResource(it) },
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp).size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            chat.outgoingStatus?.let { OutgoingStatusIcon(it) }
            Text(time, fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                previewText(chat, colors.primary),
                inlineContent = previewThumbnail(chat.previewThumbnail),
                fontSize = 15.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                chat.unreadCount > 0 ->
                    UnreadBadge(
                        chat.unreadCount,
                        if (chat.isMuted) chatColors.unreadBadgeMuted else chatColors.unreadBadge,
                    )
                chat.isPinned ->
                    Icon(
                        painterResource(R.drawable.tatrak_ic_pin_24dp),
                        contentDescription = null,
                        tint = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 8.dp).size(18.dp),
                    )
            }
        }
    }
}

/**
 * The "Archive" entry at the top of the All tab. Long press offers Telegram's
 * "Hide above list" / "Pin in chat list" ([hidden] is the current state).
 */
@Composable
fun ArchiveRow(
    archived: List<ChatListItem>,
    hidden: Boolean,
    onClick: () -> Unit,
    onToggleHidden: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val label = stringResource(if (hidden) R.string.tatrak_archive_pin else R.string.tatrak_archive_hide)
    Box(modifier) {
        ArchiveRowFrame(archived, onClick) { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    menu = false
                    onToggleHidden()
                },
            )
        }
    }
}

@Composable
private fun ArchiveRowFrame(archived: List<ChatListItem>, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val chatColors = TatrakTheme.chatColors
    val unreadChats = archived.count { it.unreadCount > 0 }
    RowFrame(
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier,
        avatar = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(AvatarSize).background(chatColors.archiveAvatar, LocalAvatarShape.current),
            ) {
                Icon(
                    painterResource(R.drawable.ic_archive_24dp),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(ArchiveIconSize),
                )
            }
        },
    ) {
        Text(
            stringResource(R.string.tatrak_archive),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                archived.joinToString(", ") { it.title },
                fontSize = 15.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (unreadChats > 0) {
                UnreadBadge(unreadChats, chatColors.unreadBadgeMuted)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowFrame(
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
    avatar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(RowHeight)
            .background(if (selected) colors.surfaceVariant else colors.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = RowHorizontalPadding),
        ) {
            avatar()
            Spacer(Modifier.width(AvatarTextGap))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) { content() }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = colors.outlineVariant,
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .padding(start = RowHorizontalPadding + AvatarSize + AvatarTextGap),
        )
    }
}

@Composable
private fun previewText(chat: ChatListItem, accent: Color): AnnotatedString =
    buildAnnotatedString {
        when {
            chat.isTyping ->
                withStyle(SpanStyle(color = accent)) { append(stringResource(R.string.tatrak_typing)) }
            chat.isDraft -> {
                withStyle(SpanStyle(color = DraftColor)) {
                    append(stringResource(R.string.tatrak_draft))
                    append(": ")
                }
                append(chat.preview)
            }
            else -> {
                chat.previewSender?.let {
                    withStyle(SpanStyle(color = accent)) {
                        append(it)
                        append(": ")
                    }
                }
                if (chat.previewThumbnail != null) {
                    appendInlineContent(ThumbnailId)
                    append(' ')
                }
                if (chat.isServicePreview) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(chat.preview) }
                } else {
                    append(chat.preview)
                }
            }
        }
    }

private const val ThumbnailId = "thumbnail"

/** The photo / video thumbnail of the last message as an inline square before the preview text. */
@Composable
private fun previewThumbnail(bitmap: Bitmap?): Map<String, InlineTextContent> {
    if (bitmap == null) return emptyMap()
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val side = PREVIEW_THUMBNAIL_DP.dp
    // sp for the placeholder, sized from dp so the square stays 20 dp at any font scale
    val sideSp = with(LocalDensity.current) { side.toSp() }
    return mapOf(
        ThumbnailId to
            InlineTextContent(Placeholder(sideSp, sideSp, PlaceholderVerticalAlign.TextCenter)) {
                Image(
                    image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(side).clip(RoundedCornerShape(3.dp)),
                )
            }
    )
}

@Composable
private fun OutgoingStatusIcon(status: OutgoingStatus) {
    val chatColors = TatrakTheme.chatColors
    val (icon, tint) =
        when (status) {
            OutgoingStatus.SENDING ->
                R.drawable.ic_schedule_24dp to MaterialTheme.colorScheme.onSurfaceVariant
            OutgoingStatus.SENT -> R.drawable.ic_done_24dp to chatColors.checkRead
            OutgoingStatus.DELIVERED ->
                R.drawable.ic_done_all_24dp to MaterialTheme.colorScheme.onSurfaceVariant
            OutgoingStatus.READ -> R.drawable.ic_done_all_24dp to chatColors.checkRead
            OutgoingStatus.FAILED -> R.drawable.ic_error_24dp to MaterialTheme.colorScheme.error
        }
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.padding(end = 4.dp).size(16.dp),
    )
}

@Composable
fun UnreadBadge(count: Int, color: Color, modifier: Modifier = Modifier.padding(start = 8.dp)) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .sizeIn(minWidth = 22.dp, minHeight = 22.dp)
                .background(color, CircleShape)
                .padding(horizontal = 6.dp),
    ) {
        Text(
            if (count > 999) "999+" else count.toString(),
            color = contentOn(color),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun ChatAvatar(avatar: Bitmap?, online: Boolean, size: Dp, selected: Boolean = false, savedMessages: Boolean = false) {
    val shape = LocalAvatarShape.current
    val dot = LocalAvatarDotOffset.current
    Box(Modifier.size(size)) {
        if (savedMessages) {
            val accent = MaterialTheme.colorScheme.primary
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().background(accent, shape)) {
                Icon(
                    painterResource(R.drawable.tatrak_ic_bookmark_24dp),
                    contentDescription = null,
                    tint = contentOn(accent),
                    modifier = Modifier.fillMaxSize(0.48f),
                )
            }
        } else if (avatar != null) {
            val image = remember(avatar) { avatar.asImageBitmap() }
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant, shape))
        }
        val ring = Modifier.offset(dot, dot).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
        if (selected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .size(22.dp)
                        .then(ring)
                        .padding(2.dp)
                        .background(TatrakTheme.chatColors.online, CircleShape),
            ) {
                Icon(
                    painterResource(R.drawable.ic_done_24dp),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else if (online) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .size(14.dp)
                    .then(ring)
                    .padding(2.dp)
                    .background(TatrakTheme.chatColors.online, CircleShape)
            )
        }
    }
}
