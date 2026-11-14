package am.cyberhayq.chat.ui.settings

import am.cyberhayq.chat.ui.theme.contentOn
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import am.cyberhayq.chat.ui.chat.ChatScreenTheme
import am.cyberhayq.chat.ui.chat.ChatWallpaper
import am.cyberhayq.chat.ui.chat.WallpaperGrid
import am.cyberhayq.chat.ui.theme.Accent
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/** Theme mode, accent and the app-wide chat wallpaper, with a live preview. */
@Composable
fun AppearanceScreen(
    style: TatrakStyle,
    onStyle: (TatrakStyle) -> Unit,
    themes: List<Pair<String, String>>,
    theme: String,
    onTheme: (String) -> Unit,
    dynamicColorsAvailable: Boolean,
    dynamicColors: Boolean,
    onDynamicColors: (Boolean) -> Unit,
    elegantDark: Boolean,
    onElegantDark: (Boolean) -> Unit,
    accent: Accent,
    onAccent: (Accent) -> Unit,
    wallpaper: String?,
    onWallpaper: (String) -> Unit,
    onWallpaperPhoto: () -> Unit,
) {
    // Text without a colour would fall back to black outside a Surface (unreadable in the dark theme)
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            Preview(wallpaper)
            Section(R.string.tatrak_appearance_style)
            StyleCards(style, accent, ChatScreenTheme.colors.isDark, elegantDark && !(dynamicColorsAvailable && dynamicColors), onStyle)
            if (dynamicColorsAvailable && dynamicColors) {
                Text(
                    stringResource(R.string.tatrak_style_dynamic_note),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            Section(R.string.tatrak_appearance_theme)
            for ((value, label) in themes) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .selectable(selected = value == theme, role = Role.RadioButton) { onTheme(value) }
                            .padding(horizontal = 8.dp),
                ) {
                    RadioButton(selected = value == theme, onClick = null, modifier = Modifier.padding(12.dp))
                    Text(label, fontSize = 16.sp)
                }
            }
            // dynamic colours win over the elegant night, so the switch (and its stored value) stays out of the way
            if (!(dynamicColorsAvailable && dynamicColors)) {
                SwitchRow(R.string.tatrak_appearance_elegant, R.string.tatrak_appearance_elegant_summary, elegantDark, onElegantDark)
            }
            if (dynamicColorsAvailable) {
                SwitchRow(R.string.pref_dynamic_colors, R.string.tatrak_appearance_dynamic_summary, dynamicColors, onDynamicColors)
            }
            Section(R.string.tatrak_appearance_accent)
            AccentRow(Accent.entries.filter { !it.signature && !it.palette }, accent, dynamicColors, onAccent)
            Section(R.string.tatrak_appearance_accent_editor)
            AccentRow(Accent.entries.filter { it.palette }, accent, dynamicColors, onAccent)
            Section(R.string.tatrak_appearance_accent_signature)
            AccentRow(Accent.entries.filter { it.signature }, accent, dynamicColors, onAccent)
            Section(R.string.tatrak_wallpaper)
            WallpaperGrid(wallpaper, forChat = false, onPick = { it?.let(onWallpaper) }, onPhoto = onWallpaperPhoto)
        }
    }
}

@Composable
private fun SwitchRow(@StringRes title: Int, @StringRes summary: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .toggleable(checked, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), fontSize = 16.sp)
            Text(stringResource(summary), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AccentRow(options: List<Accent>, accent: Accent, dynamicColors: Boolean, onAccent: (Accent) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // classic screens follow the system colours then; the accent would be half-applied
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).alpha(if (dynamicColors) 0.4f else 1f),
    ) {
        for (option in options) {
            val name = stringResource(option.label)
            val glyph = contentOn(option.swatch)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.size(48.dp)
                        .clip(CircleShape)
                        .background(option.swatch)
                        .then(
                            if (option == accent) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        .selectable(selected = option == accent, enabled = !dynamicColors, role = Role.RadioButton) {
                            onAccent(option)
                        }
                        .semantics { contentDescription = name },
            ) {
                if (option == accent) {
                    Icon(painterResource(R.drawable.ic_check_24dp), contentDescription = null, tint = glyph)
                }
            }
        }
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** Two bubbles over the wallpaper, in the current accent. */
@Composable
private fun Preview(wallpaper: String?) {
    val colors = TatrakTheme.chatColors
    Box(Modifier.fillMaxWidth().height(170.dp)) {
        ChatWallpaper(wallpaper, ChatScreenTheme.colors.isDark, Modifier.fillMaxSize())
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            Bubble(stringResource(R.string.tatrak_appearance_preview_in), colors.bubbleIncoming, colors.textIncoming, Alignment.Start)
            Bubble(stringResource(R.string.tatrak_appearance_preview_out), colors.bubbleOutgoing, colors.textOutgoing, Alignment.End)
        }
    }
}

@Composable
private fun Bubble(text: String, background: Color, color: Color, alignment: Alignment.Horizontal) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (alignment == Alignment.Start) Alignment.CenterStart else Alignment.CenterEnd) {
        Text(
            text,
            color = color,
            fontSize = 15.sp,
            modifier =
                Modifier.widthIn(max = 260.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(background)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
