package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.ui.theme.rememberPreference
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Slider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.siacs.conversations.AppSettings
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/**
 * Presets, a photo from the gallery and, for a single chat, "same as everywhere" (null). Picks
 * apply at once, so the chat behind the sheet previews them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperSheet(
    current: String?,
    forChat: Boolean,
    onPick: (String?) -> Unit,
    onPhoto: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            stringResource(R.string.tatrak_wallpaper),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        WallpaperGrid(current, forChat, onPick, onPhoto, Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()))
    }
}

/** Not lazy: a dozen tiles, and it must fit inside scrolling pages. */
@Composable
fun WallpaperGrid(
    current: String?,
    forChat: Boolean,
    onPick: (String?) -> Unit,
    onPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = ChatScreenTheme.colors.isDark
    Column(modifier.fillMaxWidth()) {
        WallpaperTiles(current, forChat, dark, onPick, onPhoto)
        // a chat following the app-wide wallpaper has no preset of its own to put a pattern on
        val base = current ?: Wallpapers.DEFAULT.takeIf { !forChat }
        if (base != null && Wallpapers.preset(base) != null) WallpaperPatterns(base, dark, onPick)
        if (current?.startsWith("photo:") == true) WallpaperDim()
    }
}

/** Night dimming of photo wallpapers, one app-wide setting. */
@Composable
private fun WallpaperDim() {
    val context = LocalContext.current
    val saved = rememberPreference(AppSettings.WALLPAPER_DIM) { it.getInt(AppSettings.WALLPAPER_DIM, Wallpapers.DEFAULT_DIM) }
    // the slider moves locally and writes once on release, not on every frame
    var dim by remember(saved) { mutableFloatStateOf(saved.toFloat()) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.tatrak_wallpaper_dim, dim.roundToInt()), fontSize = 14.sp)
        Slider(
            value = dim,
            onValueChange = { dim = it },
            onValueChangeFinished = { AppSettings(context).setWallpaperDim(dim.roundToInt()) },
            valueRange = 0f..80f,
            steps = 15,
        )
    }
}

@Composable
private fun WallpaperPatterns(current: String, dark: Boolean, onPick: (String?) -> Unit) {
    Text(
        stringResource(R.string.tatrak_wallpaper_pattern),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    val selected = Wallpapers.pattern(current)
    FlowRow(Modifier.fillMaxWidth().padding(12.dp)) {
        for (pattern in WallpaperPattern.entries) {
            val spec = Wallpapers.withPattern(current, pattern)
            Tile(pattern == selected, stringResource(pattern.label), { onPick(spec) }) {
                ChatWallpaper(spec, dark, Modifier.fillMaxSize())
                if (dark) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.14f))) // same lift as the colour tiles
            }
        }
    }
}

@Composable
private fun WallpaperTiles(
    current: String?,
    forChat: Boolean,
    dark: Boolean,
    onPick: (String?) -> Unit,
    onPhoto: () -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth().padding(12.dp)) {
        if (forChat) {
            Tile(current == null, stringResource(R.string.tatrak_wallpaper_global), { onPick(null) }) {
                Text(
                    stringResource(R.string.tatrak_wallpaper_global),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }
        for (preset in Wallpapers.presets) {
            // picking another colour keeps the chosen pattern
            val spec = Wallpapers.withPattern(Wallpapers.presetSpec(preset), Wallpapers.pattern(current))
            val selected = current == spec || (!forChat && current == null && preset.id == Wallpapers.DEFAULT)
            Tile(selected, stringResource(preset.name), { onPick(spec) }) {
                ChatWallpaper(spec, dark, Modifier.fillMaxSize())
                // night colours are near black: lift the swatch (only here, the chat keeps its colours) so the tiles tell apart
                if (dark) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.14f)))
            }
        }
        Tile(current?.startsWith("photo:") == true, stringResource(R.string.tatrak_wallpaper_photo), onPhoto) {
            Icon(
                painterResource(R.drawable.ic_image_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Tile(selected: Boolean, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.padding(5.dp)
                .width(76.dp)
                .aspectRatio(0.75f)
                .clip(shape)
                .then(
                    if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape)
                    else Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                )
                .semantics {
                    this.selected = selected
                    contentDescription = label
                }
                .clickable(onClickLabel = label, role = Role.RadioButton, onClick = onClick),
    ) {
        content()
    }
}
