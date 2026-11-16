package am.cyberhayq.chat.ui.chat

import am.cyberhayq.chat.storage.DatabaseStorage
import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import am.cyberhayq.chat.storage.media.MediaArea
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import am.cyberhayq.chat.ui.theme.rememberPreference
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.services.XmppConnectionService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A built-in background: one colour or a two-colour gradient, with a night variant. */
data class WallpaperPreset(
    val id: String,
    @param:StringRes val name: Int,
    val day: List<Color>,
    val night: List<Color>,
)

/** A drawn pattern repeated over a preset; `id` is the spec suffix after `#`. */
enum class WallpaperPattern(val id: String, @param:StringRes val label: Int) {
    NONE("", R.string.tatrak_wallpaper_pattern_none),
    SPARKLES("sparkles", R.string.tatrak_wallpaper_pattern_sparkles),
    LEAVES("leaves", R.string.tatrak_wallpaper_pattern_leaves),
    BUBBLES("bubbles", R.string.tatrak_wallpaper_pattern_bubbles),
}

/**
 * Chat backgrounds. A spec is `default`, `preset:<id>` or `photo:<file in files/wallpapers>`,
 * a preset optionally followed by `#<pattern id>`;
 * the app-wide one is `AppSettings.WALLPAPER`, a chat's own one `Conversation.tatrakWallpaper`
 * (null = follow the app-wide one).
 */
object Wallpapers {

    const val DEFAULT = "default"
    private const val PRESET = "preset:"
    private const val PHOTO = "photo:"
    private const val MAX_PHOTO_PX = 1920

    val presets =
        listOf(
            WallpaperPreset(DEFAULT, R.string.tatrak_wallpaper_default, listOf(Color(0xFFCBD9C0)), listOf(Color(0xFF0E1621))),
            WallpaperPreset("sky", R.string.tatrak_wallpaper_sky, listOf(Color(0xFFC9DCEB)), listOf(Color(0xFF0F1A26))),
            WallpaperPreset("sand", R.string.tatrak_wallpaper_sand, listOf(Color(0xFFE8DCC2)), listOf(Color(0xFF1E1A14))),
            WallpaperPreset("lavender", R.string.tatrak_wallpaper_lavender, listOf(Color(0xFFD9D2EC)), listOf(Color(0xFF1A1626))),
            WallpaperPreset("rose", R.string.tatrak_wallpaper_rose, listOf(Color(0xFFEED3D8)), listOf(Color(0xFF241519))),
            WallpaperPreset("slate", R.string.tatrak_wallpaper_slate, listOf(Color(0xFFB9C3CC)), listOf(Color(0xFF151B21))),
            WallpaperPreset(
                "sunrise",
                R.string.tatrak_wallpaper_sunrise,
                listOf(Color(0xFFF6D1A7), Color(0xFFE9A3B4)),
                listOf(Color(0xFF2A1D24), Color(0xFF1B1424)),
            ),
            WallpaperPreset(
                "ocean",
                R.string.tatrak_wallpaper_ocean,
                listOf(Color(0xFFA7D3E8), Color(0xFFB5C7F0)),
                listOf(Color(0xFF0F2333), Color(0xFF141B33)),
            ),
            WallpaperPreset(
                "meadow",
                R.string.tatrak_wallpaper_meadow,
                listOf(Color(0xFFC6E3B0), Color(0xFFA9D6C9)),
                listOf(Color(0xFF13241A), Color(0xFF0F2421)),
            ),
        )

    fun presetSpec(preset: WallpaperPreset) =
        if (preset.id == DEFAULT) DEFAULT else PRESET + preset.id

    /** The preset of [spec]; the default one for unknown specs, null for photos. */
    fun preset(spec: String?): WallpaperPreset? {
        if (spec != null && spec.startsWith(PHOTO)) return null
        val id = spec?.substringBefore(PATTERN)?.removePrefix(PRESET) ?: DEFAULT
        return presets.firstOrNull { it.id == id } ?: presets.first()
    }

    private const val PATTERN = '#'

    /** The pattern of [spec]; none for photos and unknown ids. */
    fun pattern(spec: String?): WallpaperPattern {
        if (spec == null || spec.startsWith(PHOTO)) return WallpaperPattern.NONE
        val id = spec.substringAfter(PATTERN, "")
        return WallpaperPattern.entries.firstOrNull { it.id == id } ?: WallpaperPattern.NONE
    }

    /** [spec] (a preset spec, null = default) with its pattern replaced by [pattern]. */
    fun withPattern(spec: String?, pattern: WallpaperPattern): String {
        val base = spec?.substringBefore(PATTERN) ?: DEFAULT
        return if (pattern == WallpaperPattern.NONE) base else "$base$PATTERN${pattern.id}"
    }

    /** The file name of [spec]'s photo; null for presets and names that would leave the folder. */
    private fun photoName(spec: String?): String? {
        if (spec == null || !spec.startsWith(PHOTO)) return null
        val name = spec.removePrefix(PHOTO)
        return name.takeUnless { it.isEmpty() || it.contains('/') || it.startsWith(".") }
    }

    /**
     * The photo file of [spec] in the active generation's folder, else in another readable one
     * (the name is a content hash: every copy is the same picture); null when none has it.
     */
    fun photo(context: Context, spec: String?): File? {
        val name = photoName(spec) ?: return null
        return EncryptedMediaStore.get(context).readableGenerations()
            .firstNotNullOfOrNull { File(directory(context), "$it/$name").takeIf(File::isFile) }
    }

    private fun directory(context: Context) = File(context.filesDir, "wallpapers")

    /** Copies a picked picture (scaled down, JPEG) into the app and returns its spec. */
    suspend fun importPhoto(context: Context, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_PX) sample *= 2
                val decoded =
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: return@withContext null
                val degrees =
                    context.contentResolver.openInputStream(uri)?.use {
                        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f // shortcut: mirrored orientations are not handled, rare for photos
                        }
                    } ?: 0f
                val bitmap =
                    if (degrees == 0f) decoded
                    else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
                val bytes =
                    ByteArrayOutputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
                        it.toByteArray()
                    }
                val name =
                    MessageDigest.getInstance("SHA-256").digest(bytes).take(8)
                        .joinToString("") { "%02x".format(it) } + ".jpg"
                EncryptedMediaStore.get(context).create(MediaArea.WALLPAPERS, name).run {
                    try { outputStream.write(bytes); commit() } finally { abort() }
                }
                PHOTO + name
            } catch (e: IOException) {
                Log.d(Config.LOGTAG, "could not import wallpaper", e)
                null
            } catch (e: SecurityException) {
                Log.d(Config.LOGTAG, "could not import wallpaper", e)
                null
            }
        }

    /**
     * Deletes photos no wallpaper points to any more: neither the app-wide one nor any chat's
     * own, open or closed (closed chats are read from the database). Runs after a wallpaper
     * changes; replaced photos would pile up otherwise. Only the active generation's folder:
     * another one's photos belong to a database a recovery import may bring back.
     */
    suspend fun deleteUnused(context: Context, service: XmppConnectionService) =
        withContext(Dispatchers.IO) {
            val generation = DatabaseStorage.activeGenerationId() ?: return@withContext
            val inUse =
                buildList {
                    add(AppSettings(context).wallpaper)
                    service.conversations.mapTo(this) { it.tatrakWallpaper }
                    service.databaseBackend.getConversations(Conversation.STATUS_ARCHIVED).mapTo(this) { it.tatrakWallpaper }
                }
            deleteUnused(File(directory(context), generation), inUse.mapNotNullTo(HashSet(), ::photoName))
        }

    /** Deletes the files in [directory] (not in its sub-folders) whose names are not in [keep]. */
    internal fun deleteUnused(directory: File, keep: Set<String>) {
        // a linked folder may be another generation's: never cleaned through a link
        // only the folder itself is checked: the path above it has links on a device (/data/user/0)
        if (runCatching { directory.canonicalFile != File(directory.absoluteFile.parentFile!!.canonicalFile, directory.name) }.getOrDefault(true)) return
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keep && !file.delete()) {
                Log.d(Config.LOGTAG, "could not delete an unused wallpaper") // no store file name in the log
            }
        }
        cached?.let { (name, _) -> if (name !in keep) cached = null }
    }

    /** How much photo wallpapers are darkened at night, percent; [AppSettings.WALLPAPER_DIM]. */
    const val DEFAULT_DIM = 35

    // one decoded photo is kept, by file name (a content hash, the same picture in every
    // generation): switching between chats with the same wallpaper stays instant
    @Volatile private var cached: Pair<String, Bitmap>? = null

    /** The decoded photo for [spec] if it is the one kept in memory; no disk access. */
    internal fun cachedPhoto(spec: String?): Bitmap? {
        val name = photoName(spec) ?: return null
        return cached?.let { (cachedName, bitmap) -> bitmap.takeIf { cachedName == name } }
    }

    /** Null for a damaged or unreadable file: the chat background shows instead. */
    internal fun decode(file: File): Bitmap? {
        cached?.let { (name, bitmap) -> if (name == file.name) return bitmap }
        val bitmap = EncryptedMediaStore.get().decodeBitmap(file, null) ?: return null
        cached = file.name to bitmap
        return bitmap
    }
}

@Composable
fun ChatWallpaper(spec: String?, dark: Boolean, modifier: Modifier = Modifier) {
    val dim = rememberPreference(AppSettings.WALLPAPER_DIM) { it.getInt(AppSettings.WALLPAPER_DIM, Wallpapers.DEFAULT_DIM) }
    val preset = Wallpapers.preset(spec)
    if (preset != null) {
        val colors =
            if (preset.id == Wallpapers.DEFAULT) listOf(TatrakTheme.chatColors.chatBackground) // follows the theme (style and night background)
            else if (!dark) preset.day
            else preset.night
        val brush =
            remember(colors) {
                if (colors.size == 1) {
                    Brush.linearGradient(listOf(colors[0], colors[0]))
                } else {
                    Brush.linearGradient(colors, start = Offset.Zero, end = Offset.Infinite)
                }
            }
        val pattern = Wallpapers.pattern(spec)
        if (pattern == WallpaperPattern.NONE) {
            Box(modifier.background(brush))
            return
        }
        val tilePx = with(LocalDensity.current) { PATTERN_TILE.roundToPx() }
        // drawn once into a small tile and repeated by a shader: one draw call per frame
        val patternBrush =
            remember(pattern, dark, tilePx) {
                val tile = patternTiles.getOrPut(Triple(pattern, dark, tilePx)) { patternTile(pattern, tilePx, dark) }
                ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
            }
        Box(modifier.background(brush).background(patternBrush))
        return
    }
    val context = LocalContext.current
    // keyed by spec: produceState keeps the old value when only its key changes, so a second photo never showed
    var photo by remember(spec) { mutableStateOf(Wallpapers.cachedPhoto(spec)) }
    LaunchedEffect(spec) {
        if (photo == null) photo = withContext(Dispatchers.IO) { Wallpapers.photo(context, spec)?.let(Wallpapers::decode) }
    }
    Box(modifier.background(TatrakTheme.chatColors.chatBackground)) {
        photo?.let {
            Image(
                remember(it) { it.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // photos are picked in daylight; dim them at night so the bubbles keep their contrast
            if (dark && dim > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim / 100f)))
        }
    }
}

private val PATTERN_TILE = 110.dp

// one tile per pattern, theme and density, shared by the chat and every swatch of the wallpaper sheet
// (composition only, so main thread only); a few small bitmaps at most
private val patternTiles = HashMap<Triple<WallpaperPattern, Boolean, Int>, ImageBitmap>()

// where the shapes sit in one tile: x, y (fractions of the tile), scale, rotation in degrees
private val PATTERN_SPOTS =
    listOf(
        floatArrayOf(0.2f, 0.2f, 1f, -15f),
        floatArrayOf(0.7f, 0.32f, 0.7f, 25f),
        floatArrayOf(0.34f, 0.7f, 0.8f, 40f),
        floatArrayOf(0.8f, 0.82f, 0.6f, -30f),
    )

/** One tile of [pattern]: faint dark strokes by day, faint light ones at night. */
internal fun patternTile(pattern: WallpaperPattern, size: Int, dark: Boolean): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) android.graphics.Color.argb(22, 255, 255, 255) else android.graphics.Color.argb(30, 0, 0, 0)
            strokeWidth = size / 60f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    PATTERN_SPOTS.forEachIndexed { index, (x, y, scale, angle) ->
        val r = size * 0.1f * scale
        canvas.save()
        canvas.translate(x * size, y * size)
        canvas.rotate(angle)
        when (pattern) {
            WallpaperPattern.SPARKLES -> {
                paint.style = Paint.Style.FILL
                val star =
                    Path().apply {
                        moveTo(0f, -r)
                        quadTo(0f, 0f, r, 0f)
                        quadTo(0f, 0f, 0f, r)
                        quadTo(0f, 0f, -r, 0f)
                        quadTo(0f, 0f, 0f, -r)
                    }
                canvas.drawPath(star, paint)
                canvas.drawCircle(r * 1.1f, -r * 0.9f, r * 0.12f, paint)
            }
            WallpaperPattern.LEAVES -> {
                paint.style = Paint.Style.STROKE
                val leaf =
                    Path().apply {
                        moveTo(0f, r)
                        quadTo(r * 0.9f, 0f, 0f, -r)
                        quadTo(-r * 0.9f, 0f, 0f, r)
                        lineTo(0f, -r * 0.6f)
                    }
                canvas.drawPath(leaf, paint)
            }
            WallpaperPattern.BUBBLES -> {
                paint.style = Paint.Style.STROKE
                val shape =
                    if (index % 2 == 0) {
                        // chat bubble with a tail at the bottom left
                        Path().apply {
                            addRoundRect(-r, -r * 0.7f, r, r * 0.5f, r * 0.4f, r * 0.4f, Path.Direction.CW)
                            moveTo(-r * 0.5f, r * 0.5f)
                            lineTo(-r * 0.75f, r * 0.9f)
                            lineTo(-r * 0.15f, r * 0.5f)
                        }
                    } else {
                        Path().apply {
                            moveTo(0f, r * 0.8f)
                            cubicTo(-r * 1.3f, -r * 0.1f, -r * 0.5f, -r * 1.1f, 0f, -r * 0.35f)
                            cubicTo(r * 0.5f, -r * 1.1f, r * 1.3f, -r * 0.1f, 0f, r * 0.8f)
                        }
                    }
                canvas.drawPath(shape, paint)
            }
            WallpaperPattern.NONE -> Unit
        }
        canvas.restore()
    }
    return bitmap.asImageBitmap()
}
