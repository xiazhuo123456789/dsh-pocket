package dev.dshpocket.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Decode a QR image the user picked from the gallery.
 *
 * ### Why there is no camera pipeline
 *
 * Pairing happens once per computer. The desktop already renders the pairing QR on
 * screen, so the easiest thing a user can normally produce is a screenshot or a
 * photo of it. Building camera capture would add a `CAMERA` permission, a live
 * preview surface, and an analyser lifecycle — all to save that one screenshot.
 * Dropping it keeps the app permission-free of the camera, smaller, and easier to
 * review (`CAMERA` is a permission class that often draws scrutiny). The "扫码" flow
 * in the UI is honoured via the Android system **photo picker**, which needs **no
 * permission at all**.
 *
 * ### Memory handling
 *
 * Gallery photos from a modern phone are often 12 MP+, which is far larger than a QR
 * needs and would allocate tens of MB if decoded raw. [loadScaled] decodes in two
 * passes — bounds first, then a downsampled decode down to [MAX_EDGE] — both for
 * speed and to keep the pixel buffer small.
 */
object QrDecoder {

    /** Largest edge we decode; larger photos are downsampled to keep it quick and small. */
    private const val MAX_EDGE = 1600

    /**
     * Decode a QR from a content URI and release the bitmap immediately.
     *
     * The bitmap is local to this call and recycled in `finally`, so the caller never
     * sees it and it cannot linger in the GC heap.
     *
     * @return the decoded text, or `null` if the image cannot be read or holds no QR.
     */
    fun decode(context: Context, uri: Uri): String? {
        val bitmap = loadScaled(context, uri) ?: return null
        return try {
            decodeBitmap(bitmap)
        } finally {
            // Always release, even if decode throws.
            bitmap.recycle()
        }
    }

    /**
     * Decode a QR from an already-loaded [Bitmap].
     *
     * Exposed separately so tests can hand in a tiny synthetic bitmap without going
     * through the content resolver.
     */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    // We only ever want QRs here; restricting the format both speeds
                    // decoding and disambiguates multi-format readers.
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    // TRY_HARDER makes the reader tolerate the less-than-perfect
                    // binarisation of a real photo (glare, tilt) at a small CPU cost.
                    DecodeHintType.TRY_HARDER to true,
                )
            )
        }
        // runCatching + getOrNull: a non-QR image throws NotFoundException, which is
        // an ordinary outcome here, not a crash.
        return runCatching { reader.decode(binary).text }.getOrNull()
    }

    /**
     * Decode bounds then a downsampled bitmap, so we never hold a full-res image.
     *
     * `inJustDecodeBounds` reads only the header. The loop doubles [sample] until the
     * longest edge fits under [MAX_EDGE], then a second decode reads a fraction of
     * the pixels. `ARGB_8888` is required by [RGBLuminanceSource].
     */
    private fun loadScaled(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_EDGE) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}