package com.sohanreddy.sevak.screen

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Compresses and base64-encodes a Bitmap for the Groq Vision API.
 */
object ImageEncoder {

    /**
     * Encode a bitmap to a base64 data URI suitable for Groq vision requests.
     * Scales down if needed, compresses to JPEG at given quality.
     *
     * @return "data:image/jpeg;base64,..." string ready for the API
     */
    fun encode(bitmap: Bitmap, quality: Int = 60): String {
        val scaled = scaleDown(bitmap, maxDimension = 1280)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    /**
     * Scale bitmap so neither dimension exceeds maxDimension,
     * maintaining aspect ratio. Returns the same bitmap if already small.
     */
    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDimension && h <= maxDimension) return bitmap

        val scale = min(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
