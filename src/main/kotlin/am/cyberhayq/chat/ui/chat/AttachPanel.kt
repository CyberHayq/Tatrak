package am.cyberhayq.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/** The attach menu in place of the keyboard, as tall as it was, like the emoji panel. */
@Composable
fun AttachPanel(
    height: Dp,
    onCamera: () -> Unit,
    onVideo: () -> Unit,
    onGallery: () -> Unit,
    onFile: () -> Unit,
    onLocation: () -> Unit,
    /** Null when the build may not read phone contacts. */
    onContact: (() -> Unit)?,
) {
    val options =
        listOfNotNull(
            AttachChoice(R.drawable.ic_camera_alt_24dp, Color(0xFFE3735E), R.string.attachment_choice_camera, onCamera),
            AttachChoice(R.drawable.ic_videocam_24dp, Color(0xFF9C6BD6), R.string.attachment_choice_video, onVideo),
            AttachChoice(R.drawable.ic_photo_24dp, Color(0xFF3F9BE8), R.string.tatrak_chat_gallery, onGallery),
            AttachChoice(R.drawable.ic_description_24dp, Color(0xFF4BB0D8), R.string.tatrak_chat_file, onFile),
            AttachChoice(R.drawable.ic_location_pin_24dp, Color(0xFF5DB85C), R.string.location, onLocation),
            onContact?.let { AttachChoice(R.drawable.ic_person_24dp, Color(0xFFE5A23C), R.string.attachment_choice_contact, it) },
        )
    // a short (landscape) keyboard leaves room for one row only
    val perRow = if (height < 200.dp) options.size else 3
    Column(
        verticalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth().height(height).padding(vertical = 8.dp),
    ) {
        for (row in options.chunked(perRow)) {
            Row(Modifier.fillMaxWidth()) {
                for (choice in row) AttachOption(choice)
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private class AttachChoice(val icon: Int, val color: Color, val label: Int, val onClick: () -> Unit)

@Composable
private fun RowScope.AttachOption(choice: AttachChoice) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = choice.onClick).padding(vertical = 6.dp),
    ) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(choice.color), contentAlignment = Alignment.Center) {
            Icon(painterResource(choice.icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(choice.label), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
