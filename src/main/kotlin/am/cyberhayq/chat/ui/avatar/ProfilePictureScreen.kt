package am.cyberhayq.chat.ui.avatar

import am.cyberhayq.chat.ui.theme.StatusBarIcons
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/**
 * A profile picture over the whole screen with the name on top, as the media viewer's bars draw
 * it. [picture] null: the stored avatar could not be opened, so the screen stays black.
 */
@Composable
fun ProfilePictureScreen(name: String, picture: Bitmap?, onBack: () -> Unit) {
    StatusBarIcons(dark = false)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (picture != null) {
            // shortcut: no pinch zoom (MediaViewerScreen's gestures are private to it), share them when zoom is wanted here
            Image(
                remember(picture) { picture.asImageBitmap() },
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .safeDrawingPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back_24dp), stringResource(R.string.tatrak_chat_back), tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
