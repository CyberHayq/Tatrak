package am.cyberhayq.chat.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.siacs.conversations.R
import kotlinx.coroutines.launch

/** "More reactions": the composer's emoji picker in a sheet, in place of the classic dialog and AddReactionActivity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactionSheet(picker: EmojiPickerSlot, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // one reaction per opening, as the classic screen finishes on its first pick
    var picked by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ReactionSheetContent(picker) { emoji ->
            if (!picked) {
                picked = true
                onPick(emoji)
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
    }
}

@Composable
internal fun ReactionSheetContent(picker: EmojiPickerSlot, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().height(420.dp)) {
        Text(
            stringResource(R.string.add_reaction_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        EmojiPicker(picker, onPick, Modifier.fillMaxWidth().weight(1f))
    }
}
