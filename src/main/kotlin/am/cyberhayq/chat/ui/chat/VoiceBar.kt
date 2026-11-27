package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.chatlist.PieceInset
import am.cyberhayq.chat.ui.theme.BarBackdrop
import am.cyberhayq.chat.ui.theme.BarBlur
import am.cyberhayq.chat.ui.theme.LocalFloatingBars
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.backdropBlur
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the mini player bar shows; null in [VoiceSession.bar] while nothing plays or is paused. */
@Immutable
data class VoiceBarState(
    val conversationUuid: String,
    val messageUuid: String,
    /** The sender's display name, or "You". */
    val sender: String,
    val playing: Boolean,
    val positionMs: Int,
    val durationMs: Int,
    /** 1.0, 1.5 or 2.0 */
    val speed: Float,
)

interface VoiceBarActions {
    fun toggle()

    fun cycleSpeed()

    fun close()

    /** Opens the chat at the playing message. */
    fun open()
}

/** The default of the screens' `voiceBar` parameter: no bar (previews, tests, screens opened on their own). */
val NoVoiceBar: StateFlow<VoiceBarState?> = MutableStateFlow(null)

object NoVoiceBarActions : VoiceBarActions {
    override fun toggle() {}

    override fun cycleSpeed() {}

    override fun close() {}

    override fun open() {}
}

/** Telegram 13's pinned bar shape, as the chat's floating banner. */
private val PillShape = RoundedCornerShape(18.dp)

/**
 * The mini player under a top bar: a strip in the top bar's colour, or with floating bars a pill of its own under the
 * header's (as the chat's floating banner). Collects [bar] itself, so the position ticks recompose only the bar.
 */
@Composable
fun VoiceBar(bar: StateFlow<VoiceBarState?>, actions: VoiceBarActions, backdrop: BarBackdrop? = null) {
    val state by bar.collectAsState()
    val shown = state ?: return
    if (LocalFloatingBars.current) {
        Surface(
            color = floatingPill(backdrop?.mode ?: BarBlur.OFF),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = PillShape,
            shadowElevation = 2.dp,
            // the margins take touches too, as the header's: the list under them is faded out
            modifier =
                Modifier.pointerInput(Unit) {}
                    .padding(start = 7.dp, end = 7.dp, top = 7.dp - PieceInset)
                    .fillMaxWidth()
                    .backdropBlur(backdrop, PillShape),
        ) { VoiceBarContent(shown, actions) }
    } else {
        val colors = TatrakTheme.chatColors
        Surface(color = colors.topBar, contentColor = colors.onTopBar, shape = RectangleShape, modifier = Modifier.fillMaxWidth()) {
            VoiceBarContent(shown, actions)
        }
    }
}

@Composable
private fun VoiceBarContent(state: VoiceBarState, actions: VoiceBarActions) {
    val openLabel = stringResource(R.string.tatrak_voice_bar_open)
    // numbers read left to right also in a right-to-left layout ("0:07 / 0:19", "1.5×")
    val numbers = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
    Box(Modifier.fillMaxWidth().clickable(onClickLabel = openLabel) { actions.open() }) {
        // grows with a large font instead of clipping the time line
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::toggle) {
                Icon(
                    painterResource(if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp),
                    contentDescription = stringResource(if (state.playing) R.string.pause_audio else R.string.play_audio),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.tatrak_voice_bar_title, state.sender),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val duration = if (state.durationMs > 0) formatDuration(state.durationMs) else "--:--"
                Text(
                    "${formatDuration(state.positionMs)} / $duration",
                    fontSize = 12.sp,
                    maxLines = 1,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    style = numbers,
                )
            }
            val speed = stringResource(speedLabel(state.speed))
            val speedDescription = stringResource(R.string.tatrak_voice_speed_label, speed)
            TextButton(onClick = actions::cycleSpeed, modifier = Modifier.semantics { contentDescription = speedDescription }) {
                Text(speed, style = numbers)
            }
            IconButton(onClick = actions::close) {
                Icon(painterResource(R.drawable.ic_close_24dp), contentDescription = stringResource(R.string.tatrak_voice_close))
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outlineVariant)) {
            Box(
                Modifier.fillMaxHeight()
                    .fillMaxWidth(voiceFraction(state.positionMs, state.durationMs))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

private fun speedLabel(speed: Float): Int =
    when (speed) {
        1.5f -> R.string.tatrak_voice_speed_1_5
        2f -> R.string.tatrak_voice_speed_2
        else -> R.string.tatrak_voice_speed_1
    }
