package am.cyberhayq.chat.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import kotlin.math.roundToInt
import androidx.preference.PreferenceManager

/** "Floating bars" in Appearance: while never set it follows the style (on for the Glass styles), a stored choice wins in every style. */
object FloatingBars {
    const val KEY = "tatrak_floating_bars"

    fun effective(style: TatrakStyle, stored: Boolean?): Boolean =
        stored ?: (style == TatrakStyle.GLASS || style == TatrakStyle.WARM || style == TatrakStyle.TINTED)

    fun set(context: Context, on: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, on).apply()

    @Composable fun remember(style: TatrakStyle): Boolean =
        effective(style, rememberPreference(KEY) { if (it.contains(KEY)) it.getBoolean(KEY, false) else null })
}

/** Whether the chat list's and the chat's bars float over their content, provided by [TatrakTheme]. */
val LocalFloatingBars = staticCompositionLocalOf { false }

/**
 * "Blur under bars" in Appearance: [FROSTED] (default) keeps the bars readable over the style's own content, [CLEAR] is
 * Telegram's thin glass at a fixed alpha. The blur (RenderEffect) needs API 31; below it the bars always fade ([OFF]).
 */
enum class BarBlur(val key: String) {
    OFF("off"),
    FROSTED("frosted"),
    CLEAR("clear");

    companion object {
        const val KEY = "tatrak_bar_blur"

        /** Unset or unknown: frosted. */
        fun of(key: String?): BarBlur = entries.firstOrNull { it.key == key } ?: FROSTED

        fun effective(stored: String?, sdk: Int = Build.VERSION.SDK_INT): BarBlur = if (sdk >= Build.VERSION_CODES.S) of(stored) else OFF

        fun set(context: Context, value: BarBlur) =
            PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, value.key).apply()

        @Composable fun remember(): BarBlur = effective(rememberStringPreference(KEY))
    }
}

/** The effective "Blur under bars" (off below API 31), provided by [TatrakTheme]; it matters only with [LocalFloatingBars]. */
val LocalBarBlur = staticCompositionLocalOf { BarBlur.OFF }

/** [BarBlur.CLEAR]: every pill at this alpha, no contrast search (owner, 2026-11-19). */
const val ClearBarAlpha = 0.72f

/**
 * [BarBlur.FROSTED]: what a frosted bar is checked over, the style's own content: the background, chat background, incoming
 * bubble, the accent's outgoing bubble and the primary colour.
 * shortcut: photos, stickers, media and photo wallpapers are not in the set (a white photo under a night bar can drop its
 * text below 4.5:1), upgrade to sampling the blurred content if that shows up.
 */
fun frostedContent(colors: ChatColors, background: Color, primary: Color): List<Color> =
    listOf(background, colors.chatBackground, colors.bubbleIncoming, colors.bubbleOutgoing, primary)

/** Alpha of a floating top bar: [barAlpha] of its colour for its text, muted text, link and [primary] (folder tab), per [blur] mode. */
fun barAlpha(colors: ChatColors, primary: Color, background: Color, blur: BarBlur = BarBlur.OFF): Float =
    barAlpha(colors.topBar, colors.onTopBar, colors.onTopBarMuted, colors.link, listOf(primary), blur, frostedContent(colors, background, primary))

/**
 * Alpha of a floating [bar] colour until its [text], [muted] text and [link] reach 4.5:1 and its non-text [parts] 3:1, with
 * the bar laid over the worst content under it. Off: from 0.96 up, over pure black and pure white. Frosted: from 0.72 up,
 * over each of [content] ([frostedContent]). Clear: [ClearBarAlpha]. `bar_alpha()` in check_contrast.py mirrors it.
 */
fun barAlpha(
    bar: Color,
    text: Color,
    muted: Color,
    link: Color,
    parts: List<Color> = emptyList(),
    blur: BarBlur = BarBlur.OFF,
    content: List<Color> = emptyList(),
): Float {
    if (blur == BarBlur.CLEAR) return ClearBarAlpha
    fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }
    val frosted = blur == BarBlur.FROSTED
    var step = if (frosted) 72 else 96
    while (step < 100 && (if (frosted) content else listOf(Color.Black, Color.White)).any { under ->
            val fill = bar.copy(alpha = step / 100f).compositeOver(under)
            listOf(text, muted, link).any { contrast(it, fill) < 4.5f } || parts.any { contrast(it, fill) < 3f }
        }) step++
    return step / 100f
}

/**
 * Blur under floating bars: the content under them is drawn once into [layer] ([backdropSource]) and each bar draws that
 * layer again, blurred, behind itself ([backdropBlur]). Null (see [rememberBarBackdrop]) while blur or floating bars are off.
 */
class BarBackdrop internal constructor(internal val layer: GraphicsLayer, val mode: BarBlur) {
    internal var source: LayoutCoordinates? = null
}

/** A backdrop for one screen's content and bars when floating bars and blur are both on, else null. */
@Composable
fun rememberBarBackdrop(): BarBackdrop? {
    val mode = LocalBarBlur.current
    if (!LocalFloatingBars.current || mode == BarBlur.OFF) return null
    val layer = rememberGraphicsLayer()
    return remember(layer, mode) { BarBackdrop(layer, mode) }
}

/** The content the bars blur: drawn once into the [backdrop]'s layer, which is then drawn here as usual. */
fun Modifier.backdropSource(backdrop: BarBackdrop?): Modifier =
    if (backdrop == null) this
    else onPlaced { backdrop.source = it }.drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/** Behind a floating bar of this [shape]: the [backdrop]'s content under it, blurred, clipped to the shape. Nothing when null. */
fun Modifier.backdropBlur(backdrop: BarBackdrop?, shape: Shape): Modifier =
    if (backdrop == null) this else this then BackdropBlurElement(backdrop, shape)

/** Telegram's frosted bars; the bar's layer is recorded this much larger on every side, so its edges blur real content. */
private val BlurRadius = 24.dp

private data class BackdropBlurElement(val backdrop: BarBackdrop, val shape: Shape) : ModifierNodeElement<BackdropBlurNode>() {
    override fun create() = BackdropBlurNode(backdrop, shape)

    override fun update(node: BackdropBlurNode) {
        node.backdrop = backdrop
        node.shape = shape
        node.clip = null
        node.invalidateDraw()
    }
}

private class BackdropBlurNode(var backdrop: BarBackdrop, var shape: Shape) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var layer: GraphicsLayer? = null
    private var drawnAt: IntOffset? = null
    // the clip path and the size it was made for: no allocation per frame
    internal var clip: Path? = null
    private var clipSize = Size.Unspecified

    private fun at(): IntOffset? {
        val source = backdrop.source ?: return null
        val self = requireLayoutCoordinates()
        return if (source.isAttached && self.isAttached) source.localPositionOf(self, Offset.Zero).round() else null
    }

    // a bar that moves (the composer over the keyboard, the bottom bar sliding in) re-records in the same frame: positions
    // are dispatched after layout, before drawing
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (at() != drawnAt) invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val at = at()
        drawnAt = at
        val source = backdrop.source
        // an empty pill (a header without actions) has no size: no layer to record
        if (at != null && source != null && size.width > 0f && size.height > 0f) {
            // the bar and a blur radius around it, kept inside the content: Clamp then repeats the content's own edge, where
            // Decal (or recording past the edge) would fade the bar's edge towards transparent
            val margin = BlurRadius.roundToPx()
            val left = (at.x - margin).coerceAtLeast(0)
            val top = (at.y - margin).coerceAtLeast(0)
            val right = (at.x + size.width.roundToInt() + margin).coerceAtMost(source.size.width)
            val bottom = (at.y + size.height.roundToInt() + margin).coerceAtMost(source.size.height)
            if (right > left && bottom > top) {
                val blur =
                    layer ?: requireGraphicsContext().createGraphicsLayer().also {
                        it.renderEffect = BlurEffect(margin.toFloat(), margin.toFloat(), TileMode.Clamp)
                        layer = it
                    }
                // the content under the bar never changes the recording: the source layer is drawn by reference
                blur.record(IntSize(right - left, bottom - top)) {
                    translate(-left.toFloat(), -top.toFloat()) { drawLayer(backdrop.layer) }
                }
                blur.topLeft = IntOffset(left - at.x, top - at.y)
                if (clip == null || clipSize != size) {
                    clip = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@draw)) }
                    clipSize = size
                }
                clipPath(clip!!) { drawLayer(blur) }
            }
        }
        drawContent()
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
    }
}

/** Within a row's empty top margin (the text starts about 10 dp in), so a row at rest under the top bar is not dimmed. */
private val FadeLength = 8.dp

/** Telegram's fade under its floating tabs (MainTabsActivity, BlurredBackgroundWithFadeDrawable): over 60 dp from the slot's top. */
private val BottomFadeLength = 60.dp

/** The content's alpha along the bottom fade: Telegram's background-coloured stops 0x60, 0xB0, 0xE8 (of 285), inverted. */
private val BottomFadeStops = listOf(1f, 1f - 0x60 / 285f, 1f - 0xB0 / 285f, 1f - 0xE8 / 285f).map { Color.Black.copy(alpha = it) }

/**
 * The content fades towards floating bars as high as [bars]' top and bottom (a Scaffold's padding; the screen's edges
 * included). Under the top bar it is gone, so the hidden archive row never shows in the pill's corners, and dims over the
 * [FadeLength] before the bar's edge. Under the bottom bar it stays visible, as in Telegram: from the slot's top it fades
 * over [BottomFadeLength] down to about a fifth and holds there to the screen's edge. A mask, not a colour, so the
 * chat's wallpaper shows through. One offscreen layer for the whole content, no per-item work; the heights are read
 * in the draw cache, so a bar that grows (the keyboard under the chat's composer) redraws without recomposing. With blur the
 * pills show the content blurred from [backdropSource] inside this mask.
 */
fun Modifier.fadeUnderBars(bars: PaddingValues): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            // rebuilt only when the size or a bar's height changes, not on every frame of a scroll
            val fade = FadeLength.toPx()
            val t = bars.calculateTopPadding().toPx()
            val b = bars.calculateBottomPadding().toPx()
            val opaque = listOf(Color.Transparent, Color.Black)
            val top = t
            val bottom = size.height - b
            val topMask = Brush.verticalGradient(opaque, top, top + fade)
            val bottomMask = Brush.verticalGradient(BottomFadeStops, bottom, bottom + BottomFadeLength.toPx())
            onDrawWithContent {
                drawContent()
                if (t > 0f) drawRect(topMask, size = Size(size.width, top + fade), blendMode = BlendMode.DstIn)
                if (b > 0f) {
                    drawRect(bottomMask, topLeft = Offset(0f, bottom), size = Size(size.width, b), blendMode = BlendMode.DstIn)
                }
            }
        }
