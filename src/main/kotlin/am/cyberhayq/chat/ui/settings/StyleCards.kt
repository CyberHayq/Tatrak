package am.cyberhayq.chat.ui.settings

import am.cyberhayq.chat.ui.theme.Accent
import am.cyberhayq.chat.ui.theme.NightBackground
import am.cyberhayq.chat.ui.theme.TatrakStyle
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/**
 * What a style card draws, as fixed colours so a card shows its own style whatever the current one is. They mirror the
 * wallpaper, incoming bubble and text colours of art/tatrak/gen_accents.py (Slate) and of ChatColors in Theme.kt (Telegram).
 */
private class CardLook(val wallpaper: Color, val incoming: Color, val text: Color, val bubble: Shape, val avatar: Shape)

private fun cardLook(style: TatrakStyle, dark: Boolean, night: NightBackground): CardLook {
    val slate = style == TatrakStyle.SLATE_SEA
    val bubble = RoundedCornerShape(if (slate) 14.dp else 10.dp)
    val avatar = if (slate) RoundedCornerShape(36) else CircleShape
    fun look(wallpaper: Long, incoming: Long, text: Long) = CardLook(Color(wallpaper), Color(incoming), Color(text), bubble, avatar)
    return when {
        !dark -> if (slate) look(0xFFE3E9EE, 0xFFF8FAFB, 0xFF14191F) else look(0xFFCBD9C0, 0xFFFFFFFF, 0xFF000000)
        night == NightBackground.AMOLED -> look(0xFF000000, 0xFF1A1A1A, 0xFFE8E8E8)
        night == NightBackground.GRAPHITE -> if (slate) look(0xFF0B0C0E, 0xFF1E2124, 0xFFE8EBED) else look(0xFF0B0B0D, 0xFF1E1E22, 0xFFECE9E6)
        else -> if (slate) look(0xFF0F1316, 0xFF1F262B, 0xFFE6EAEE) else look(0xFF0E1621, 0xFF182533, 0xFFF5F5F5)
    }
}

/** The two style choices as cards; side by side, stacked when the text is large or the screen narrow. */
@Composable
internal fun StyleCards(style: TatrakStyle, accent: Accent, dark: Boolean, night: NightBackground, onStyle: (TatrakStyle) -> Unit) {
    val configuration = LocalConfiguration.current
    val stacked = configuration.fontScale > 1.3f || configuration.screenWidthDp < 340
    val cards: List<@Composable (Modifier) -> Unit> =
        listOf(
            { m -> StyleCard(TatrakStyle.TELEGRAM, R.string.tatrak_style_telegram, R.string.tatrak_style_telegram_summary, style, accent, dark, night, onStyle, m) },
            { m -> StyleCard(TatrakStyle.SLATE_SEA, R.string.tatrak_style_slate_sea, R.string.tatrak_style_slate_sea_summary, style, accent, dark, night, onStyle, m) },
        )
    if (stacked) {
        Column(Modifier.selectableGroup().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cards.forEach { it(Modifier.fillMaxWidth()) }
        }
    } else {
        Row(Modifier.selectableGroup().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            cards.forEach { it(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun StyleCard(
    card: TatrakStyle,
    @StringRes title: Int,
    @StringRes summary: Int,
    current: TatrakStyle,
    accent: Accent,
    dark: Boolean,
    night: NightBackground,
    onStyle: (TatrakStyle) -> Unit,
    modifier: Modifier,
) {
    val selected = card == current
    val shape = RoundedCornerShape(16.dp)
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, ring, shape)
            .selectable(selected = selected, role = Role.RadioButton) { onStyle(card) }
            .semantics(mergeDescendants = true) {}
            .padding(10.dp)
    ) {
        CardPreview(cardLook(card, dark, night), accent.swatch)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(title), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            // not colour alone: the selected card also carries a check
            if (selected) Icon(painterResource(R.drawable.ic_check_24dp), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Text(stringResource(summary), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CardPreview(look: CardLook, accent: Color) {
    val outgoing = lerp(look.incoming, accent, 0.3f)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp)).background(look.wallpaper).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(20.dp).clip(look.avatar).background(accent))
            Box(Modifier.width(54.dp).height(20.dp).clip(look.bubble).background(look.incoming)) { Line(look.text, 30.dp) }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.width(70.dp).height(20.dp).clip(look.bubble).background(outgoing)) { Line(look.text, 46.dp) }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.width(44.dp).height(20.dp).clip(look.bubble).background(outgoing)) { Line(look.text, 22.dp) }
        }
    }
}

/** A text-like stripe inside a preview bubble. */
@Composable
private fun Line(color: Color, width: androidx.compose.ui.unit.Dp) {
    Box(Modifier.padding(start = 8.dp, top = 8.dp).width(width).height(4.dp).clip(CircleShape).background(color.copy(alpha = 0.55f)))
}
