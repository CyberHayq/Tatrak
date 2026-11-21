package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.LocalTatrakFont
import am.cyberhayq.chat.ui.theme.LocalTatrakStyle
import am.cyberhayq.chat.ui.theme.BarBackdrop
import am.cyberhayq.chat.ui.theme.BarBlur
import am.cyberhayq.chat.ui.theme.frostedContent
import am.cyberhayq.chat.ui.theme.LocalFloatingBars
import am.cyberhayq.chat.ui.theme.backdropBlur
import am.cyberhayq.chat.ui.theme.barAlpha
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import am.cyberhayq.chat.ui.media.mediaDecodeDispatcher
import android.util.LruCache
import android.view.ViewGroup
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import am.cyberhayq.chat.ui.theme.rememberPreference
import eu.siacs.conversations.AppSettings
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import eu.siacs.conversations.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(
    composer: ComposerInfo,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    reply: ReplyPreview?,
    actions: ChatActions,
    recording: StateFlow<VoiceRecording>,
    modifier: Modifier = Modifier,
    previews: List<String> = emptyList(),
    /** Key of the newest message in the list: a sent text keeps the field's height until it lands there. */
    newest: String? = null,
    /** Floating bars with blur: the pills draw it blurred behind them. */
    backdrop: BarBackdrop? = null,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // floating bars, in any style: Slate's pill composer over the messages
    val pills = LocalTatrakStyle.current == TatrakStyle.SLATE_SEA || LocalFloatingBars.current
    var panel by rememberSaveable { mutableStateOf(KeyboardPanel.NONE) }
    // the keyboard was asked back in place of this panel: it stays until the keyboard is up
    var leaving by remember { mutableStateOf(KeyboardPanel.NONE) }
    BackHandler(enabled = panel != KeyboardPanel.NONE) { panel = KeyboardPanel.NONE }
    // NONE asks the keyboard back, except while picked attachments hide the text field
    val onPanel: (KeyboardPanel) -> Unit = { next ->
        leaving = if (next == KeyboardPanel.NONE && previews.isEmpty()) (if (panel != KeyboardPanel.NONE) panel else leaving) else KeyboardPanel.NONE
        panel = next
    }
    val emojiPicker = remember { EmojiPickerSlot() }
    var keyboardPx by rememberSaveable { mutableIntStateOf(0) }
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val nav = WindowInsets.navigationBars
    KeyboardHeight(ime, imeTarget, nav) { keyboardPx = it }
    LaunchedEffect(leaving) {
        if (leaving != KeyboardPanel.NONE) {
            awaitKeyboard(density, ime, imeTarget, nav)
            leaving = KeyboardPanel.NONE
        }
    }
    val focus = remember { FocusRequester() }
    val rec = recording.collectAsState()
    val recActive by remember { derivedStateOf { rec.value.active } }
    val shown = if (panel != KeyboardPanel.NONE) panel else leaving
    val panelShown = shown != KeyboardPanel.NONE && !recActive && composer.canWrite && !composer.requiresClassic
    val panelPx = if (keyboardPx > 0) keyboardPx else with(density) { 280.dp.roundToPx() }
    val panelHeight = with(density) { panelPx.toDp() }
    val insets = remember(ime, nav, panelShown, panelPx) { ComposerInsets(ime, nav, if (panelShown) panelPx else 0) }
    // a pick closes the panel without the keyboard: the camera, picker or map opens over the chat
    fun picked(action: () -> Unit): () -> Unit = {
        panel = KeyboardPanel.NONE
        leaving = KeyboardPanel.NONE
        action()
    }
    val panelContent: @Composable () -> Unit = {
        if (shown == KeyboardPanel.ATTACH) {
            AttachPanel(
                panelHeight,
                picked(actions::takePhoto),
                picked(actions::recordVideo),
                picked(actions::attachGallery),
                picked(actions::attachFile),
                picked(actions::attachLocation),
                if (composer.contacts) picked(actions::attachContact) else null,
            )
        } else {
            EmojiPanel(panelHeight, input, onInputChange, actions, emojiPicker, if (pills) colors.surfaceContainer else colors.surface)
        }
    }
    val mentions =
        rememberMentionSuggestions(
            composer.mentions && composer.canWrite && !composer.requiresClassic && previews.isEmpty(),
            input,
            onInputChange,
            actions,
        )
    if (pills) {
        val fill = if (LocalFloatingBars.current) floatingPill(backdrop?.mode ?: BarBlur.OFF) else colors.surfaceContainer
        SlateComposer(composer, input, onInputChange, reply, actions, recording, modifier, previews, panel, onPanel, panelShown, panelContent, insets, focus, mentions, newest, fill, backdrop)
        return
    }
    Surface(color = colors.surface, shadowElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.windowInsetsPadding(insets)) {
            if (mentions != null) {
                MentionList(mentions, actions)
                HorizontalDivider(color = colors.outlineVariant)
            }
            val editing = composer.editing
            when {
                editing != null ->
                    ContextPanel(
                        icon = R.drawable.ic_edit_24dp,
                        title = stringResource(R.string.tatrak_chat_editing),
                        text = editing.text,
                        onClose = actions::cancelEdit,
                    )
                reply != null ->
                    ContextPanel(
                        icon = R.drawable.ic_reply_24dp,
                        title = stringResource(R.string.tatrak_chat_reply_to, reply.name),
                        text = reply.text,
                        onClose = actions::cancelReply,
                        thumbnail = reply.thumbnailFor(actions),
                    )
            }
            when {
                composer.requiresClassic -> Notice(stringResource(R.string.tatrak_chat_pgp_notice), actions)
                !composer.canWrite ->
                    Text(
                        composer.hint,
                        color = colors.onSurfaceVariant,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp),
                    )
                else -> {
                    val rec by recording.collectAsState()
                    InputRow(composer, input, onInputChange, actions, rec, panel, previews, onPanel, focus, mentions, newest)
                    if (panelShown) panelContent()
                    BlockSwipeBack(panel != KeyboardPanel.NONE || rec.active)
                }
            }
        }
    }
}

/**
 * Floating bars: the fill of the composer's and the banner's pills, surfaceContainer (raised over the wallpaper) at
 * [barAlpha] for the text, hint and link on it, as the floating top bar is (per the [blur] mode).
 */
@Composable
internal fun floatingPill(blur: BarBlur = BarBlur.OFF): Color {
    val scheme = MaterialTheme.colorScheme
    val chat = TatrakTheme.chatColors
    val link = chat.link
    return remember(scheme, chat, blur) {
        // the recording dot and primary (send, reply title) are the non-text parts: Telegram's blue-grey night needs 0.98 for the dot
        scheme.surfaceContainer.copy(
            alpha = barAlpha(scheme.surfaceContainer, scheme.onSurface, scheme.onSurfaceVariant, link, listOf(RecordingRed, scheme.primary), blur, frostedContent(chat, scheme.background, scheme.primary))
        )
    }
}

/**
 * Slate and Sea, and floating bars in any style: the same content in a floating pill over the wallpaper. The insets stay
 * outside the pill (so the keyboard height is measured as before), the reply or edit panel and the emoji panel are separate
 * blocks around it. [fill] colours the pills; the emoji or attach panel stays opaque.
 */
@Composable
private fun SlateComposer(
    composer: ComposerInfo,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    reply: ReplyPreview?,
    actions: ChatActions,
    recording: StateFlow<VoiceRecording>,
    modifier: Modifier,
    previews: List<String>,
    panel: KeyboardPanel,
    onPanel: (KeyboardPanel) -> Unit,
    panelShown: Boolean,
    panelContent: @Composable () -> Unit,
    insets: WindowInsets,
    focus: FocusRequester,
    mentions: MentionSuggestions?,
    newest: String?,
    fill: Color,
    backdrop: BarBackdrop?,
) {
    val colors = MaterialTheme.colorScheme
    // raised over the wallpaper (in the night style the plain surface equals it)
    val pill = colors.surfaceContainer
    // floating: Telegram 13's input island, 7 dp from the sides and 9 dp from the bottom; the "@" list and the reply block take
    // the input's radius (Telegram's mention list does, its reply sits inside the island); Slate keeps its own
    val floating = LocalFloatingBars.current
    val side = if (floating) 7.dp else 10.dp
    val inputShape = RoundedCornerShape(if (floating) InputRadius else 28.dp)
    val block = RoundedCornerShape(if (floating) InputRadius else 16.dp)
    Column(modifier.fillMaxWidth().windowInsetsPadding(insets)) {
        if (mentions != null) {
            Surface(
                color = fill,
                shape = block,
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth().padding(start = side, end = side, bottom = 6.dp).backdropBlur(backdrop, block),
            ) {
                MentionList(mentions, actions)
            }
        }
        val editing = composer.editing
        val contextPanel: (@Composable () -> Unit)? =
            when {
                editing != null -> {
                    { ContextPanel(R.drawable.ic_edit_24dp, stringResource(R.string.tatrak_chat_editing), editing.text, actions::cancelEdit) }
                }
                reply != null -> {
                    { ContextPanel(R.drawable.ic_reply_24dp, stringResource(R.string.tatrak_chat_reply_to, reply.name), reply.text, actions::cancelReply, reply.thumbnailFor(actions)) }
                }
                else -> null
            }
        if (contextPanel != null) {
            Surface(
                color = fill,
                shape = block,
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth().padding(start = side, end = side, bottom = 6.dp).backdropBlur(backdrop, block),
                content = contextPanel,
            )
        }
        Surface(
            color = fill,
            shape = inputShape,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(start = side, end = side, bottom = if (floating) 9.dp else 8.dp).backdropBlur(backdrop, inputShape),
        ) {
            when {
                composer.requiresClassic -> Notice(stringResource(R.string.tatrak_chat_pgp_notice), actions)
                !composer.canWrite ->
                    Text(
                        composer.hint,
                        color = colors.onSurfaceVariant,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp),
                    )
                else -> {
                    val rec by recording.collectAsState()
                    InputRow(composer, input, onInputChange, actions, rec, panel, previews, onPanel, focus, mentions, newest)
                    BlockSwipeBack(panel != KeyboardPanel.NONE || rec.active)
                }
            }
        }
        if (panelShown) {
            // floating: Telegram's panel under the keyboard island, 29 dp corners
            val top = if (floating) 29.dp else 16.dp
            Surface(color = pill, shape = RoundedCornerShape(topStart = top, topEnd = top), modifier = Modifier.fillMaxWidth(), content = panelContent)
        }
    }
}

/** Floating: half the one-line input's height (about 53 dp: a capsule), kept as it grows, as Telegram's 22 dp on its 44 dp island. */
private val InputRadius = 27.dp

@Composable
private fun InputRow(
    composer: ComposerInfo,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    actions: ChatActions,
    recording: VoiceRecording,
    panel: KeyboardPanel,
    previews: List<String>,
    onPanel: (KeyboardPanel) -> Unit,
    focus: FocusRequester,
    mentions: MentionSuggestions?,
    newest: String?,
) {
    val context = LocalContext.current
    val currentInput by rememberUpdatedState(input)
    // the field keeps the sent text's height until the message is in the list: both then move in one frame
    // (shrinking first dropped the list for a frame before the new bubble pushed it up)
    val fieldPx = remember { intArrayOf(0) }
    var heldPx by remember { mutableIntStateOf(0) }
    var heldFor by remember { mutableStateOf<String?>(null) }
    val held = if (heldPx > 0 && newest == heldFor) heldPx else 0
    // an edit adds no message; keyed by the send too, so a second send at the same height gets its own 500 ms
    LaunchedEffect(heldPx, heldFor) { if (heldPx > 0) { delay(500); heldPx = 0 } }
    fun send() {
        heldFor = newest
        heldPx = fieldPx[0]
        actions.send()
    }
    val currentMentions by rememberUpdatedState(mentions)
    val styles =
        listOf(
            '*' to stringResource(R.string.tatrak_format_bold),
            '_' to stringResource(R.string.tatrak_format_italic),
            '`' to stringResource(R.string.tatrak_format_monospace),
            '~' to stringResource(R.string.tatrak_format_strikethrough),
        )
    val enterIsSend =
        rememberPreference(AppSettings.ENTER_IS_SEND) {
            it.getBoolean(AppSettings.ENTER_IS_SEND, context.resources.getBoolean(R.bool.enter_is_send))
        }
    val colors = MaterialTheme.colorScheme
    val editing = composer.editing != null
    var locked by remember { mutableStateOf(false) }
    // how far the finger has slid towards cancel, 0..1, so the hint follows it
    var slide by remember { mutableFloatStateOf(0f) }
    var askDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(recording.active) {
        if (!recording.active) {
            locked = false
            slide = 0f
            askDiscard = false
        }
    }
    // the system back would leave the chat and silently discard the note: ask first
    BackHandler(enabled = recording.active) { askDiscard = true }
    if (askDiscard && recording.active) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.tatrak_voice_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    askDiscard = false
                    actions.stopRecording(send = false)
                }) { Text(stringResource(R.string.tatrak_voice_discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.tatrak_voice_keep)) }
            },
        )
    }
    val keyboard = LocalSoftwareKeyboardController.current
    // the clip and the emoji button swap their panel in for the keyboard; tapped again, the keyboard comes back
    fun toggle(target: KeyboardPanel) {
        if (panel == target) {
            onPanel(KeyboardPanel.NONE)
            if (previews.isEmpty()) {
                focus.requestFocus()
                keyboard?.show()
            }
        } else {
            keyboard?.hide()
            onPanel(target)
        }
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(end = 2.dp),
    ) {
        if (recording.active) {
            RecordingIndicator(recording, locked, slide, actions, Modifier.weight(1f))
        } else if (previews.isNotEmpty()) {
            // as the classic screen: the strip takes the text field's place until Send
            PreviewStrip(previews, actions, Modifier.weight(1f))
            IconButton(onClick = { toggle(KeyboardPanel.ATTACH) }, modifier = Modifier.padding(bottom = 1.dp)) {
                Icon(
                    painterResource(R.drawable.ic_attach_file_24dp),
                    contentDescription = stringResource(R.string.tatrak_chat_attach),
                    tint = colors.onSurfaceVariant,
                )
            }
        } else {
            val emojiOpen = panel == KeyboardPanel.EMOJI
            IconButton(onClick = { toggle(KeyboardPanel.EMOJI) }, modifier = Modifier.padding(bottom = 1.dp)) {
                Icon(
                    painterResource(if (emojiOpen) R.drawable.ic_keyboard_24dp else R.drawable.tatrak_ic_emoji_24dp),
                    contentDescription =
                        stringResource(if (emojiOpen) R.string.tatrak_chat_keyboard else R.string.tatrak_chat_emoji),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
        if (previews.isEmpty()) {
            val interactions = remember { MutableInteractionSource() }
            val pressed by interactions.collectIsPressedAsState()
            // tapping the field brings the keyboard back in place of the panel
            LaunchedEffect(pressed) { if (pressed) onPanel(KeyboardPanel.NONE) }
            // kept at zero width under a recording: dropping the field would drop the focus and the
            // keyboard, and the composer falling under the held mic counted as a slide up to the lock
            Box(if (recording.active) Modifier.width(0.dp).clearAndSetSemantics {} else Modifier.weight(1f)) {
                BasicTextField(
                    value = input,
                    // typing into the hidden field would turn the mic into Send and end the recording
                    onValueChange = { if (!recording.active) onInputChange(it) },
                    textStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, color = colors.onSurface, fontFamily = LocalTatrakFont.current),
                    cursorBrush = SolidColor(colors.primary),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    interactionSource = interactions,
                    modifier =
                        Modifier.fillMaxWidth()
                            .onSizeChanged { fieldPx[0] = it.height }
                            // the default spring of animateItem: on send the list slides up as the field shrinks
                            // bottom-aligned: a pasted block grows upwards, the caret line stays visible
                            .animateContentSize(alignment = Alignment.BottomStart)
                            .heightIn(min = with(LocalDensity.current) { held.toDp() })
                            .padding(vertical = 14.dp)
                            .focusRequester(focus)
                            // the system selection toolbar, after Cut / Copy / Paste / Select all
                            .appendTextContextMenuComponents {
                                if (currentInput.selection.collapsed) return@appendTextContextMenuComponents
                                separator()
                                for ((marker, label) in styles) {
                                    item(key = marker, label = label) {
                                        onInputChange(currentInput.wrapSelection(marker))
                                        close()
                                    }
                                }
                            }
                            // hardware keyboard, as EditMessage.onKeyDown: Enter (or Ctrl+Enter) sends,
                            // Shift+Enter is a new line, Ctrl+Up edits the last own message; Tab picks the
                            // first "@" suggestion
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                                when {
                                    enter && !event.isShiftPressed && (event.isCtrlPressed || enterIsSend) -> {
                                        send()
                                        true
                                    }
                                    event.key == Key.DirectionUp && event.isCtrlPressed -> actions.editLast()
                                    event.key == Key.Tab && currentMentions != null -> {
                                        currentMentions?.let { it.pick(it.matches.first()) }
                                        true
                                    }
                                    else -> false
                                }
                            },
                    decorationBox = { field ->
                        Box {
                            if (input.text.isEmpty()) {
                                Text(
                                    composer.hint,
                                    fontSize = 17.sp,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            field()
                        }
                    },
                )
            }
            if (!recording.active && !editing) {
                IconButton(onClick = { toggle(KeyboardPanel.ATTACH) }, modifier = Modifier.padding(bottom = 1.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_attach_file_24dp),
                        contentDescription = stringResource(R.string.tatrak_chat_attach),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
        val canSend = input.text.isNotEmpty() || editing || locked || previews.isNotEmpty()
        AnimatedContent(
            targetState = canSend,
            transitionSpec = {
                (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith
                    (scaleOut(targetScale = 0.6f) + fadeOut())
            },
            label = "send",
            modifier = Modifier.padding(bottom = 1.dp),
        ) { send ->
            if (send) {
                IconButton(onClick = { if (locked) actions.stopRecording(send = true) else send() }) {
                    Icon(
                        painterResource(if (editing) R.drawable.ic_check_24dp else R.drawable.ic_send_24dp),
                        contentDescription = stringResource(R.string.tatrak_chat_send),
                        tint = colors.primary,
                    )
                }
            } else {
                MicButton(recording.active, actions, onSlide = { slide = it }) { locked = true }
            }
        }
    }
}

/**
 * Telegram's lock above the held mic: a pill with an open padlock and an arrow that follows the
 * finger up and closes the padlock as it reaches the lock distance. A popup, so the composer
 * surface does not clip it.
 */
@Composable
private fun LockHint(lift: Float, lockPx: Float) {
    val density = LocalDensity.current
    val base = with(density) { (48 + 12).dp.roundToPx() }
    val smooth by animateFloatAsState(lift, spring(stiffness = Spring.StiffnessMedium), label = "lift")
    val travel = (smooth * lockPx * 0.5f).roundToInt()
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) }
    Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -(base + travel))) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.graphicsLayer {
                        alpha = appear.value.coerceIn(0f, 1f)
                        scaleX = 0.6f + 0.4f * appear.value
                        scaleY = 0.6f + 0.4f * appear.value
                    }
                    .shadow(4.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            Icon(
                painterResource(if (lift >= 1f) R.drawable.ic_lock_24dp else R.drawable.ic_lock_open_outline_24dp),
                contentDescription = stringResource(R.string.tatrak_voice_lock_hint),
                tint = if (lift >= 1f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Icon(
                painterResource(R.drawable.ic_arrow_back_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 1f - lift),
                modifier = Modifier.padding(top = 4.dp).size(16.dp).rotate(90f),
            )
        }
    }
}

/** Telegram's hold-to-record: release sends, slide left cancels, slide up locks. */
@Composable
private fun MicButton(active: Boolean, actions: ChatActions, onSlide: (Float) -> Unit, onLock: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentActions by rememberUpdatedState(actions)
    val currentOnLock by rememberUpdatedState(onLock)
    val currentOnSlide by rememberUpdatedState(onSlide)
    val hint = stringResource(R.string.tatrak_voice_hold_hint)
    val label = stringResource(R.string.tatrak_chat_record_voice)
    val cancelPx = with(density) { 100.dp.toPx() }
    val lockPx = with(density) { 80.dp.toPx() }
    // how far the finger has gone towards the lock, 0..1, for the floating hint
    var lift by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) { if (!active) lift = 0f }
    if (active) LockHint(lift, lockPx)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(48.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    // TalkBack users can't hold and slide: a click starts a locked recording
                    onClick(label) {
                        if (currentActions.startRecording()) currentOnLock()
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val released = withTimeoutOrNull(HoldMillis) { waitForUpOrCancellation() ?: Unit }
                        if (released != null) {
                            Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
                            return@awaitEachGesture
                        }
                        if (!currentActions.startRecording()) return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val startedAt = SystemClock.elapsedRealtime()
                        var done = false
                        try {
                            while (!done) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    // under a second is a slip of the finger, not a message
                                    val long = SystemClock.elapsedRealtime() - startedAt >= MinRecordingMillis
                                    currentActions.stopRecording(send = long)
                                    if (!long) Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
                                    done = true
                                } else {
                                    change.consume()
                                    val delta = change.position - down.position
                                    lift = (-delta.y / lockPx).coerceIn(0f, 1f)
                                    currentOnSlide((-delta.x / cancelPx).coerceIn(0f, 1f))
                                    if (delta.x < -cancelPx) {
                                        currentActions.stopRecording(send = false)
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        done = true
                                    } else if (delta.y < -lockPx) {
                                        currentOnLock()
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        done = true
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            // the button left composition mid-hold; don't leave the microphone on
                            if (!done) currentActions.stopRecording(send = false)
                            throw e
                        }
                    }
                },
    ) {
        // the button swells under the finger and settles back with a spring, like Telegram's
        val slate = LocalTatrakStyle.current == TatrakStyle.SLATE_SEA
        // Slate and Sea: a ring inside the 48 dp box, so the button swells less to leave room for it
        if (slate && active) {
            Box(Modifier.fillMaxSize().border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape))
        }
        val scale by animateFloatAsState(
            if (active) (if (slate) 1.05f else 1.2f) else 1f,
            spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
            label = "micScale",
        )
        val fill by animateColorAsState(
            if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
            tween(160),
            label = "micFill",
        )
        val tint by animateColorAsState(
            if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            tween(160),
            label = "micTint",
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(40.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(fill),
        ) {
            Icon(painterResource(R.drawable.ic_mic_24dp), contentDescription = null, tint = tint)
        }
    }
}

@Composable
private fun RecordingIndicator(
    recording: VoiceRecording,
    locked: Boolean,
    slide: Float,
    actions: ChatActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var elapsed by remember(recording.startedAt) {
        mutableLongStateOf(SystemClock.elapsedRealtime() - recording.startedAt)
    }
    LaunchedEffect(recording.startedAt) {
        while (true) {
            elapsed = SystemClock.elapsedRealtime() - recording.startedAt
            delay(200)
        }
    }
    val blink by
        rememberInfiniteTransition(label = "rec")
            .animateFloat(1f, 0.2f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dot")
    // the row slides in from the left and fades in instead of popping into place
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
    val enterShift = with(LocalDensity.current) { 32.dp.toPx() }
    val slideShift = with(LocalDensity.current) { 64.dp.toPx() }
    val mark = stringResource(R.string.tatrak_voice_plain_mark)
    val description = stringResource(R.string.tatrak_voice_recording).let { if (recording.plainFile) "$it, $mark" else it }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .heightIn(min = 50.dp)
                .graphicsLayer {
                    alpha = appear.value
                    translationX = -(1f - appear.value) * enterShift
                }
                .padding(start = 16.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = description
                },
    ) {
        if (LocalTatrakStyle.current == TatrakStyle.SLATE_SEA) {
            // a static halo behind the blinking dot (no second animation)
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(RecordingRed.copy(alpha = 0.18f)))
                Box(Modifier.size(10.dp).alpha(blink).clip(CircleShape).background(RecordingRed))
            }
            Spacer(Modifier.width(6.dp))
        } else {
            Box(Modifier.size(10.dp).alpha(blink).clip(CircleShape).background(RecordingRed))
            Spacer(Modifier.width(10.dp))
        }
        val seconds = elapsed / 1000
        Text("%d:%02d".format(seconds / 60, seconds % 60), fontSize = 16.sp, color = colors.onSurface)
        if (recording.plainFile) {
            Spacer(Modifier.width(6.dp))
            Icon(
                painterResource(R.drawable.ic_lock_open_outline_24dp),
                contentDescription = mark,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        if (locked) {
            TextButton(onClick = { actions.stopRecording(send = false) }) {
                Text(stringResource(R.string.cancel), color = colors.error)
            }
        } else {
            // follows the finger to the left and fades out as the cancel distance is reached
            Text(
                stringResource(R.string.tatrak_voice_slide_hint),
                fontSize = 14.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.graphicsLayer {
                        translationX = -slide * slideShift
                        alpha = 1f - slide
                    },
            )
        }
    }
}

/**
 * The keyboard's height once it has settled (not a frame of its animation), so the emoji panel
 * takes exactly its place. Its own scope, so only this recomposes while the keyboard animates.
 */
@Composable
private fun KeyboardHeight(ime: WindowInsets, target: WindowInsets, nav: WindowInsets, onHeight: (Int) -> Unit) {
    val density = LocalDensity.current
    val bottom = ime.getBottom(density)
    val targetBottom = target.getBottom(density)
    val navBottom = nav.getBottom(density)
    SideEffect { if (keyboardSettled(bottom, targetBottom, navBottom)) onHeight(bottom - navBottom) }
}

/** Picked attachments waiting for Send: square previews with a remove button, file icon otherwise. */
@Composable
private fun PreviewStrip(previews: List<String>, actions: ChatActions, modifier: Modifier) {
    val sizePx = with(LocalDensity.current) { PreviewSize.roundToPx() }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(start = 10.dp, end = 4.dp),
        modifier = modifier.padding(vertical = 6.dp),
    ) {
        items(previews, key = { it }) { key ->
            val thumbnail by produceState<Bitmap?>(null, key) { value = actions.previewThumbnail(key, sizePx) }
            Box(Modifier.size(PreviewSize).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                val bitmap = thumbnail
                if (bitmap != null) {
                    Image(
                        remember(bitmap) { bitmap.asImageBitmap() },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.ic_description_24dp),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(onClickLabel = stringResource(R.string.tatrak_chat_remove_attachment)) { actions.removePreview(key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_close_24dp),
                        contentDescription = stringResource(R.string.tatrak_chat_remove_attachment),
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

private val PreviewSize = 56.dp

/** Holds the emoji picker between openings of the panel. */
internal class EmojiPickerSlot {
    var view: EmojiPickerView? = null
}

/**
 * The installed emoji2 picker. One per [slot] and chat screen: inflating it and loading its emoji
 * data took a frame hitch on every open.
 */
@Composable
internal fun EmojiPicker(slot: EmojiPickerSlot, onPick: (String) -> Unit, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            val view = slot.view ?: EmojiPickerView(context).also { slot.view = it }
            (view.parent as? ViewGroup)?.removeView(view)
            view
        },
        update = { view -> view.setOnEmojiPickedListener { onPick(it.emoji) } },
        modifier = modifier,
    )
}

/** Sticker tiles decoded at tile size, kept while the app runs (a few MB). */
private object StickerThumbs {
    private val cache =
        object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

    fun cached(file: File, sizePx: Int): Bitmap? = cache.get(file.path + "@" + sizePx)

    fun decode(file: File, sizePx: Int): Bitmap? {
        cached(file, sizePx)?.let { return it }
        // sticker files are encrypted: decoded by the store, which refuses a damaged one
        val store = EncryptedMediaStore.get()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        store.decodeBitmap(file, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        val bitmap = store.decodeBitmap(file, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(file.path + "@" + sizePx, bitmap)
        return bitmap
    }
}

/** What sits in place of the keyboard under the composer. */
internal enum class KeyboardPanel { NONE, EMOJI, ATTACH }

/** Emoji (the installed emoji2 picker) and stickers in place of the keyboard, as tall as it was. */
@Composable
private fun EmojiPanel(
    height: Dp,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    actions: ChatActions,
    emojiPicker: EmojiPickerSlot,
    container: Color = MaterialTheme.colorScheme.surface,
) {
    val current by rememberUpdatedState(input)
    var stickers by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().height(height)) {
        TabRow(selectedTabIndex = if (stickers) 1 else 0, containerColor = container) {
            Tab(selected = !stickers, onClick = { stickers = false }, text = { Text(stringResource(R.string.tatrak_chat_emoji)) })
            Tab(selected = stickers, onClick = { stickers = true }, text = { Text(stringResource(R.string.tatrak_stickers)) })
        }
        if (stickers) {
            StickerGrid(actions, Modifier.weight(1f))
        } else {
            EmojiPicker(emojiPicker, { onInputChange(current.insertAtCursor(it)) }, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun StickerGrid(actions: ChatActions, modifier: Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { StickerStore.load(context) }
    val scope = rememberCoroutineScope()
    val packs by StickerStore.stickers.collectAsState()
    var deleting by remember { mutableStateOf<File?>(null) }
    var deletingPack by remember { mutableStateOf<StickerPack?>(null) }
    val own = packs.firstOrNull()
    val tilePx = with(LocalDensity.current) { 76.dp.roundToPx() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(76.dp),
        contentPadding = PaddingValues(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        item(key = "add") {
            StickerTile(R.drawable.ic_add_24dp, R.string.tatrak_stickers_add, actions::importStickers)
        }
        if (own != null && own.files.isNotEmpty()) {
            item(key = "share") {
                StickerTile(R.drawable.ic_share_24dp, R.string.tatrak_stickers_share, actions::shareStickers)
            }
        }
        for (pack in packs) {
            if (pack.id.isNotEmpty()) {
                item(key = "header-" + pack.id, span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) {
                        Text(
                            pack.name,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { deletingPack = pack }) {
                            Icon(
                                painterResource(R.drawable.ic_delete_24dp),
                                contentDescription = stringResource(R.string.tatrak_stickers_delete_pack),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(pack.files, key = { pack.id + "/" + it.name }, contentType = { "sticker" }) { file ->
                val bitmap by
                    produceState(StickerThumbs.cached(file, tilePx), file) {
                        if (value == null) {
                            value =
                                withContext(mediaDecodeDispatcher) {
                                    currentCoroutineContext().ensureActive()
                                    StickerThumbs.decode(file, tilePx)
                                }
                        }
                    }
                Box(
                    Modifier.aspectRatio(1f).padding(4.dp).combinedClickable(
                        onClickLabel = stringResource(R.string.tatrak_chat_send),
                        onLongClickLabel = stringResource(R.string.delete),
                        onLongClick = { deleting = file },
                    ) {
                        actions.sendSticker(file)
                    }
                ) {
                    bitmap?.let {
                        Image(
                            remember(it) { it.asImageBitmap() },
                            contentDescription = stringResource(R.string.tatrak_sticker),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
    if (packs.all { it.files.isEmpty() }) {
        Text(
            stringResource(R.string.tatrak_stickers_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
    }
    deleting?.let { file ->
        ConfirmDelete(R.string.tatrak_sticker_delete, { deleting = null }) { scope.launch { StickerStore.delete(context, file) } }
    }
    deletingPack?.let { pack ->
        ConfirmDelete(R.string.tatrak_stickers_delete_pack_confirm, { deletingPack = null }) {
            scope.launch { StickerStore.deletePack(context, pack) }
        }
    }
}

@Composable
private fun StickerTile(icon: Int, label: Int, onClick: () -> Unit) {
    val text = stringResource(label)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.aspectRatio(1f).padding(4.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClickLabel = text, onClick = onClick),
    ) {
        Icon(painterResource(icon), contentDescription = text, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConfirmDelete(message: Int, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = { onDelete(); onDismiss() }) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Replaces the selection (or inserts at the cursor) and puts the cursor after [text]. */
internal fun TextFieldValue.insertAtCursor(text: String): TextFieldValue {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    return TextFieldValue(this.text.replaceRange(start, end, text), TextRange(start + text.length))
}

/** Inserts a link as a separate token while preserving the surrounding text. */
internal fun TextFieldValue.insertLinkAtCursor(link: String): TextFieldValue {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    val before = if (start > 0 && !text[start - 1].isWhitespace()) " " else ""
    val after = if (end < text.length && !text[end].isWhitespace()) " " else ""
    return insertAtCursor(before + link + after)
}

internal fun appendToDraft(draft: String, link: String): String =
    draft + (if (draft.isNotEmpty() && !draft.last().isWhitespace()) " " else "") + link

/** The recording dot. */
private val RecordingRed = Color(0xFFE53935)

private const val HoldMillis = 250L
private const val MinRecordingMillis = 1000L

@Composable
private fun ContextPanel(
    icon: Int,
    title: String,
    text: String,
    onClose: () -> Unit,
    thumbnail: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = 14.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Box(Modifier.width(2.dp).height(34.dp).background(colors.primary))
        Spacer(Modifier.width(8.dp))
        if (thumbnail != null) {
            thumbnail()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = colors.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text,
                color = colors.onSurface,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                painterResource(R.drawable.ic_close_24dp),
                contentDescription = stringResource(R.string.cancel),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

/** Telegram's reply panel (ChatReplyContainer): a 34 dp square with 2 dp corners, the text 10 dp after it. */
private fun ReplyPreview.thumbnailFor(actions: ChatActions): (@Composable () -> Unit)? =
    if (thumbnail) { { ReplyThumbnail(uuid, 34.dp, 2.dp, actions) } } else null

@Composable
private fun Notice(text: String, actions: ChatActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = actions::openClassic) {
            Text(stringResource(R.string.tatrak_chat_classic_view), color = TatrakTheme.chatColors.link)
        }
    }
}
