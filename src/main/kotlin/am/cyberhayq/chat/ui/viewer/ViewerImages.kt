package am.cyberhayq.chat.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import android.content.res.AssetFileDescriptor
import android.os.Build
import java.nio.ByteBuffer
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Tatrak: decodes a photo (or a video's first frame) for the viewer, no bigger than needed. */
object ViewerImages {

    fun decode(file: File, mime: String?, maxPx: Int): ViewerImage? =
        runCatching {
                when {
                    mime?.startsWith("video/") == true -> videoFrame(file, maxPx)?.let(ViewerImage::Still)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> decodeModern(file, mime, maxPx)
                    else -> decodeLegacy(file, maxPx)?.let(ViewerImage::Still)
                }
            }
            .getOrNull()

    /** The power of two that brings the larger side of [width] x [height] down to [maxPx]. */
    fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxPx) sample *= 2
        return sample
    }

    private fun decodeModern(file: File, mime: String?, maxPx: Int): ViewerImage {
        val store = EncryptedMediaStore.get()
        val source =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ImageDecoder.createSource { AssetFileDescriptor(store.descriptor(file), 0, AssetFileDescriptor.UNKNOWN_LENGTH) }
            } else {
                // shortcut: API 28 has no descriptor source, so the whole file is held in memory; drop with minSdk 29
                ImageDecoder.createSource(ByteBuffer.wrap(store.inputStream(file).use { it.readBytes() }))
            }
        val listener =
            ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, maxPx))
            }
        // GIF and WebP may be animated; ImageDecoder plays them, EXIF rotation is applied for us
        if (mime == "image/gif" || mime == "image/webp") {
            return when (val drawable = ImageDecoder.decodeDrawable(source, listener)) {
                is AnimatedImageDrawable -> ViewerImage.Animated(drawable)
                is BitmapDrawable -> ViewerImage.Still(drawable.bitmap)
                else -> ViewerImage.Animated(drawable)
            }
        }
        return ViewerImage.Still(ImageDecoder.decodeBitmap(source, listener))
    }

    private fun decodeLegacy(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val store = EncryptedMediaStore.get()
        store.decodeBitmap(file, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        val bitmap = store.decodeBitmap(file, options) ?: return null
        val degrees =
            when (store.inputStream(file).use { ExifInterface(it) }.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun videoFrame(file: File, maxPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(EncryptedMediaStore.get().dataSource(file))
            val frame = retriever.getFrameAtTime(0) ?: return null
            val sample = maxOf(frame.width, frame.height).toFloat() / maxPx
            if (sample <= 1f) return frame
            return Bitmap.createScaledBitmap(frame, (frame.width / sample).toInt(), (frame.height / sample).toInt(), true)
        } finally {
            runCatching { retriever.release() }
        }
    }
}
