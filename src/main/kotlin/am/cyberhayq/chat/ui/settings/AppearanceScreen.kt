package am.cyberhayq.chat.ui.settings

import am.cyberhayq.chat.ui.theme.contentOn
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import am.cyberhayq.chat.ui.chat.ChatScreenTheme
import am.cyberhayq.chat.ui.chat.ChatWallpaper
import am.cyberhayq.chat.ui.chat.WallpaperGrid
import am.cyberhayq.chat.ui.theme.Accent
import am.cyberhayq.chat.ui.theme.AvatarShape
import am.cyberhayq.chat.ui.theme.NightBackground
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
import androidx.compose.foundation.selection.selectableGroup
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
    nightBackground: NightBackground,
    onNightBackground: (NightBackground) -> Unit,
    accent: Accent,
    onAccent: (Accent) -> Unit,
    wallpaper: String?,
    onWallpaper: (String) -> Unit,
    onWallpaperPhoto: () -> Unit,
    avatarShape: AvatarShape = AvatarShape.effective(style, null),
    onAvatarShape: (AvatarShape) -> Unit = {},
) {
    // Text without a colour would fall back to black outside a Surface (unreadable in the dark theme)
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            val dynamic = dynamicColorsAvailable && dynamicColors
            Preview(wallpaper)
            Section(R.string.tatrak_appearance_style)
            StyleCards(style, accent, ChatScreenTheme.colors.isDark, if (dynamic) NightBackground.BLUE_GREY else nightBackground, onStyle)
            if (dynamic) {
                Text(
                    stringResource(R.string.tatrak_style_dynamic_note),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            Section(R.string.tatrak_appearance_theme)
            for ((value, label) in themes) RadioRow(label, value == theme) { onTheme(value) }
            if (dynamicColorsAvailable) {
                SwitchRow(R.string.pref_dynamic_colors, R.string.tatrak_appearance_dynamic_summary, dynamicColors, onDynamicColors)
            }
            // dynamic colours win over the night background, so the choice (and its stored value) stays out of the way
            if (!dynamic) {
                Section(R.string.tatrak_night_background)
                for ((option, label) in
                    listOf(
                        NightBackground.BLUE_GREY to R.string.tatrak_night_background_blue_grey,
                        NightBackground.GRAPHITE to R.string.tatrak_night_background_graphite,
                        NightBackground.AMOLED to R.string.tatrak_night_background_amoled,
                    )) RadioRow(stringResource(label), option == nightBackground) { onNightBackground(option) }
            }
            Section(R.string.tatrak_avatar_shape)
            for ((option, label) in listOf(AvatarShape.ROUND to R.string.tatrak_avatar_shape_round, AvatarShape.SQUARE to R.string.tatrak_avatar_shape_square)) {
                RadioRow(stringResource(label), option == avatarShape) { onAvatarShape(option) }
            }
            Section(R.string.tatrak_appearance_accent)
            AccentRow(accent, dynamicColors, onAccent)
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
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label, fontSize = 16.sp)
    }
}

/** The nine accents as 48 dp touch targets (40 dp circles): one line where they fit, wrapping on a narrow phone. */
@Composable
private fun AccentRow(accent: Accent, dynamicColors: Boolean, onAccent: (Accent) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        // classic screens follow the system colours then; the accent would be half-applied
        modifier = Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 16.dp, vertical = 8.dp).alpha(if (dynamicColors) 0.4f else 1f),
    ) {
        for (option in Accent.entries) {
            val name = stringResource(option.label)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.size(48.dp)
                        .selectable(selected = option == accent, enabled = !dynamicColors, role = Role.RadioButton) {
                            onAccent(option)
                        }
                        .semantics { contentDescription = name },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.size(40.dp)
                            .clip(CircleShape)
                            .background(option.swatch)
                            .then(
                                if (option == accent) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier
                            ),
                ) {
                    if (option == accent) {
                        Icon(painterResource(R.drawable.ic_check_24dp), contentDescription = null, tint = contentOn(option.swatch), modifier = Modifier.size(20.dp))
                    }
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
