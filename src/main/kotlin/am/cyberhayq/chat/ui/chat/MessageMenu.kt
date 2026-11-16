package am.cyberhayq.chat.ui.chat

import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutQuint
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Reaction
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * The key of the message whose long-press menu is open. Its row hides the bubble (the menu draws
 * it in its place), read in a graphics layer so no row recomposes when the menu opens.
 */
internal val LocalMenuMessage = staticCompositionLocalOf<State<String?>> { mutableStateOf(null) }

/**
 * Long-press overlay: the message stays where it was in the list ([anchor], in this overlay's
 * coordinates, which fill the chat screen) and the quick reactions and actions go around it, above or below, wherever there
 * is room (see [MenuPlacement]). Only when neither side has room (a very long message, or one
 * mostly scrolled off) the message moves, animated, and scrolls inside if still too tall.
 * Without an [anchor] the message is centred.
 */
@Composable
fun MessageMenu(
    item: MessageItem,
    isGroup: Boolean,
    actions: ChatActions,
    voice: StateFlow<VoicePlayback>,
    anchor: Rect? = null,
    onMoreReactions: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    fun run(action: (String) -> Unit): () -> Unit = {
        onDismiss()
        action(item.key)
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(AppearMillis, easing = EaseOutQuint)) }
    val insets = WindowInsets.safeDrawing
    val pop =
        Modifier.graphicsLayer {
            alpha = appear.value
            val scale = 0.85f + 0.15f * appear.value
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(if (item.isOutgoing) 1f else 0f, 0.5f)
        }
    val hasStrip = item.actions.canReact || item.actions.canRemoveReaction
    Layout(
        contents =
            listOf(
                { if (hasStrip) ReactionStrip(item, actions, onMoreReactions, onDismiss, pop) },
                {
                    Box(Modifier.verticalScroll(rememberScrollState()), propagateMinConstraints = true) {
                        Bubble(item, actions, voice)
                    }
                },
                { MenuActions(item, actions, ::run, pop) },
            ),
        modifier =
            Modifier.fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = 0.4f * appear.value)) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.cancel),
                    onClick = onDismiss,
                ),
    ) { (stripMeasurables, bubbleMeasurables, actionMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = 8.dp.roundToPx()
        val gap = 8.dp.roundToPx()
        val top = insets.getTop(this) + margin
        val bottom = height - insets.getBottom(this) - margin
        val loose = Constraints(maxWidth = (width - 2 * margin).coerceAtLeast(0), maxHeight = (bottom - top).coerceAtLeast(0))
        val strip = stripMeasurables.firstOrNull()?.measure(loose)
        val menu = actionMeasurables.first().measure(loose)
        val stripSpace = strip?.let { it.height + gap } ?: 0
        val menuSpace = menu.height + gap
        val anchored = anchor != null && anchor.width > 0f
        val bubbleWidth =
            if (anchored) anchor!!.width.roundToInt()
            else ((width - 2 * margin) * if (isGroup && !item.isOutgoing) 0.86f else 0.9f).roundToInt()
        // decided from the anchor before measuring: an unmoved bubble keeps its full height
        val placement =
            if (anchored) {
                MenuPlacement.of(
                    bubbleTop = anchor!!.top.roundToInt(),
                    bubbleHeight = anchor.height.roundToInt(),
                    strip = stripSpace,
                    menu = menuSpace,
                    top = top,
                    bottom = bottom,
                )
            } else {
                null
            }
        val bubbleRoom = (bottom - top - stripSpace - menuSpace).coerceAtLeast(0)
        val bubble =
            bubbleMeasurables.first().measure(
                Constraints(
                    minWidth = if (anchored) bubbleWidth else 0,
                    maxWidth = bubbleWidth,
                    // the inner scroll needs a finite bound; an unmoved bubble gets at least its own height
                    maxHeight = if (placement != null) maxOf(height, anchor!!.height.roundToInt()) else bubbleRoom,
                )
            )
        val total = stripSpace + bubble.height + menuSpace
        layout(width, height) {
            val bubbleX =
                when {
                    anchored -> anchor!!.left.roundToInt()
                    item.isOutgoing -> width - margin - bubble.width
                    else -> margin
                }
            val anchorY = if (anchored) anchor!!.top.roundToInt() else null
            fun sideX(w: Int): Int {
                val x = if (item.isOutgoing) bubbleX + bubble.width - w else bubbleX
                return x.coerceIn(margin, (width - margin - w).coerceAtLeast(margin))
            }
            if (placement != null && anchorY != null) {
                // the message stays; reactions and actions go where there is room
                val y = anchorY
                val below = y + bubble.height + gap
                when (placement) {
                    MenuPlacement.AROUND -> {
                        strip?.place(sideX(strip.width), y - stripSpace)
                        menu.place(sideX(menu.width), below)
                    }
                    MenuPlacement.BELOW -> {
                        strip?.place(sideX(strip.width), below)
                        menu.place(sideX(menu.width), below + stripSpace)
                    }
                    MenuPlacement.ABOVE -> {
                        strip?.place(sideX(strip.width), y - stripSpace)
                        menu.place(sideX(menu.width), y - stripSpace - menuSpace)
                    }
                    MenuPlacement.SWAPPED -> {
                        menu.place(sideX(menu.width), y - menuSpace)
                        strip?.place(sideX(strip.width), below)
                    }
                }
                bubble.place(bubbleX, y)
            } else {
                // no room on either side: move the message as little as needed
                val fromY = anchorY ?: ((top + bottom - total) / 2 + stripSpace)
                val toY = (fromY - stripSpace).coerceIn(top, (bottom - total).coerceAtLeast(top)) + stripSpace
                val y = (fromY + (toY - fromY) * appear.value).roundToInt()
                strip?.place(sideX(strip.width), y - stripSpace)
                bubble.place(bubbleX, y)
                menu.place(sideX(menu.width), y + bubble.height + gap)
            }
        }
    }
}

/** Where the reactions and actions go around an unmoved message. */
internal enum class MenuPlacement {
    /** Reactions above, actions below: the usual case. */
    AROUND,
    /** Message near the top: reactions, then actions, below it. */
    BELOW,
    /** Message near the bottom: actions, then reactions, above it. */
    ABOVE,
    /** Actions above, reactions below. */
    SWAPPED;

    companion object {
        /**
         * Pixel sizes include the gap to the message. Null when no side has room and the message
         * has to move.
         */
        fun of(bubbleTop: Int, bubbleHeight: Int, strip: Int, menu: Int, top: Int, bottom: Int): MenuPlacement? {
            val above = bubbleTop - top
            val below = bottom - (bubbleTop + bubbleHeight)
            return when {
                strip <= above && menu <= below -> AROUND
                strip + menu <= below -> BELOW
                strip + menu <= above -> ABOVE
                menu <= above && strip <= below -> SWAPPED
                else -> null
            }
        }
    }
}

@Composable
private fun MenuActions(item: MessageItem, actions: ChatActions, run: ((String) -> Unit) -> () -> Unit, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        // wider when it shows a fingerprint, so a line of four groups does not wrap
        modifier = modifier.widthIn(min = 200.dp, max = if (item.encryptionKey?.fingerprint != null) 300.dp else 260.dp).padding(horizontal = TailWidth),
    ) {
        // scrolls when a long list does not fit a small window
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            if (item.receivedAt != null) {
                // Tatrak: like Telegram's "read at" row; informational, not clickable
                val context = LocalContext.current
                val locale = LocalConfiguration.current.locales[0]
                fun time(epochMs: Long) = receiptTime(context, locale, epochMs)
                MenuInfo(stringResource(R.string.tatrak_chat_sent_at, time(item.sentAt)))
                MenuInfo(stringResource(R.string.tatrak_chat_received_at, time(item.receivedAt)))
            }
            item.encryptionKey?.let { MenuKey(it, run(actions::openMessageKey)) }
            if (item.receivedAt != null || item.encryptionKey != null) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            val can = item.actions
            if (can.canReply) MenuEntry(R.drawable.ic_reply_24dp, R.string.reply, run(actions::reply))
            if (can.canCopy) MenuEntry(R.drawable.tatrak_chat_ic_copy_24dp, R.string.copy_to_clipboard, run(actions::copy))
            if (can.canEdit) MenuEntry(R.drawable.ic_edit_24dp, R.string.correct_message, run(actions::edit))
            if (can.canRetry) MenuEntry(R.drawable.ic_replay_24dp, R.string.send_again, run(actions::retry))
            if (can.canDownload) MenuEntry(R.drawable.ic_download_24dp, R.string.tatrak_chat_download, run(actions::download))
            can.copyLinkLabel?.let { MenuEntry(R.drawable.tatrak_chat_ic_copy_24dp, it, run(actions::copyLink)) }
            if (can.canCopyUrl) MenuEntry(R.drawable.tatrak_chat_ic_copy_24dp, R.string.copy_original_url, run(actions::copyUrl))
            if (can.canViewHistory) MenuEntry(R.drawable.ic_schedule_24dp, R.string.view_edit_history, run(actions::viewEditHistory))
            if (can.canShowError) MenuEntry(R.drawable.ic_error_24dp, R.string.show_error_message, run(actions::showError))
            if (can.canOpenWith) MenuEntry(R.drawable.ic_open_with_24dp, R.string.open_with, run(actions::openWith))
            if (can.canShare) {
                MenuEntry(R.drawable.tatrak_chat_ic_forward_24dp, R.string.tatrak_chat_forward, run(actions::forward))
                MenuEntry(R.drawable.ic_share_24dp, R.string.share, run(actions::share))
            }
            if (item.kind == MessageKind.STICKER) MenuEntry(R.drawable.ic_add_24dp, R.string.tatrak_sticker_add_mine, run(actions::addSticker))
            if (can.canSave) MenuEntry(R.drawable.ic_save_24dp, R.string.save, run(actions::saveFile))
            if (can.canDeleteFile) MenuEntry(R.drawable.ic_delete_24dp, R.string.delete_file_dialog, run(actions::deleteFile))
            if (can.canDelete) MenuEntry(R.drawable.ic_delete_24dp, R.string.delete, run(actions::delete))
            if (can.canCancel) MenuEntry(R.drawable.ic_close_24dp, R.string.cancel_transmission, run(actions::cancelTransfer))
        }
    }
}

private const val AppearMillis = 250

@Composable
private fun ReactionStrip(item: MessageItem, actions: ChatActions, onMore: (String) -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    val mine = remember(item.reactions) { item.reactions.filter { it.mine }.map { it.emoji }.toSet() }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = modifier.padding(horizontal = TailWidth),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            for (emoji in Reaction.SUGGESTIONS) {
                val selected = emoji in mine
                val enabled = if (selected) item.actions.canRemoveReaction else item.actions.canReact
                Box(
                    Modifier.size(40.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable(enabled = enabled) {
                            onDismiss()
                            actions.toggleReaction(item.key, emoji)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 24.sp)
                }
            }
            if (item.actions.canReact) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable {
                        onDismiss()
                        onMore(item.key)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_add_24dp),
                        contentDescription = stringResource(R.string.tatrak_chat_more_reactions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(icon: Int, label: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Text(stringResource(label), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MenuInfo(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
    )
}

/** "OMEMO · verified ✓" and, for OMEMO, the key's fingerprint, which opens the key list. */
@Composable
private fun MenuKey(key: KeyInfo, onOpen: () -> Unit) {
    val method = stringResource(key.method)
    MenuInfo(if (key.trust == null) method else stringResource(R.string.tatrak_chat_key_line, method, stringResource(key.trust)))
    val fingerprint = key.fingerprint ?: return
    Text(
        fingerprint,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.tatrak_chat_key_open), onClick = onOpen)
                .padding(horizontal = 18.dp, vertical = 4.dp),
    )
}

/**
 * Date and time with seconds in the device locale and 12/24-hour setting; the year only when it
 * is not the current one.
 */
internal fun receiptTime(context: Context, locale: Locale, epochMs: Long): String {
    val zone = ZoneId.systemDefault()
    val year = if (Instant.ofEpochMilli(epochMs).atZone(zone).year == LocalDate.now(zone).year) "" else "y"
    val time = if (DateFormat.is24HourFormat(context)) "Hmmss" else "hmmssa"
    // SimpleDateFormat: it takes every symbol the ICU skeleton lookup may return
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "${year}dMMM$time"), locale).format(Date(epochMs))
}
