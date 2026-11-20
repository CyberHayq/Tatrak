package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.theme.LocalFloatingBars
import eu.siacs.conversations.R
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Telegram's chat preview (long press on a chat list avatar): the chat in a card over the dimmed list.
 * A tap opens the chat; an outside tap or Back closes it. [actions] must not send read markers.
 */
@Composable
fun ChatPreview(state: ChatState, actions: ChatActions, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val open by rememberUpdatedState(onOpen)
    val openLabel = stringResource(R.string.tatrak_chat_open)
    val voice = remember { MutableStateFlow(VoicePlayback()) }
    val recording = remember { MutableStateFlow(VoiceRecording()) }
    val list = rememberLazyListState()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxWidth(0.92f)
                .fillMaxHeight(0.75f)
                .clip(RoundedCornerShape(16.dp))
                .semantics { onClick(openLabel) { open(); true } }
                // Initial pass, before the bubbles: a short tap that never left the slop opens the chat and is consumed,
                // so no bubble, voice or jump button acts on it; a drag is left to the list, a long press does nothing
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // a touch that stops a fling belongs to the list (as selectionGestures does)
                        var tap = !list.isScrollInProgress
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            // a second finger, a lost pointer or a cancel (it arrives already consumed) is no tap
                            if (change == null || event.changes.size > 1 || change.isConsumed) {
                                tap = false
                                break
                            }
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false
                            if (!change.pressed) {
                                if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) tap = false
                                if (tap) change.consume()
                                break
                            }
                        }
                        if (tap) open()
                    }
                }
        ) {
            // the classic layout: the wallpaper behind the list, no floating pills over it
            CompositionLocalProvider(LocalFloatingBars provides false) {
                ChatScreen(
                    state = state,
                    input = TextFieldValue(),
                    onInputChange = {},
                    reply = null,
                    actions = actions,
                    voice = voice,
                    recording = recording,
                    preview = true,
                    listState = list,
                )
            }
        }
    }
}
