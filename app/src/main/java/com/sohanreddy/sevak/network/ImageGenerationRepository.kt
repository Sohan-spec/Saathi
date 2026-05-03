package com.sohanreddy.sevak.network

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Log
import com.sohanreddy.sevak.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Generates photorealistic images using HuggingFace FLUX.1-schnell.
 * Called only when Llama decides a visual would help the user understand better.
 */
object ImageGenerationRepository {

    private const val TAG = "ImageGen"
    private const val FLUX_URL =
        "https://router.huggingface.co/hf-inference/models/black-forest-labs/FLUX.1-schnell"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)  // image gen can be slow
        .build()

    /**
     * Generate a photorealistic image from an English prompt.
     * @return Bitmap on success, null on failure (never throws).
     */
    suspend fun generate(prompt: String): Bitmap? = withContext(Dispatchers.IO) {
        if (Constants.HF_TOKEN.isBlank()) {
            Log.w(TAG, "HF_TOKEN is blank, skipping image generation")
            return@withContext null
        }

        Log.d(TAG, "══════ IMAGE GENERATION ══════")
        Log.d(TAG, "Prompt: $prompt")

        try {
            val jsonBody = JSONObject().apply {
                put("inputs", prompt)
            }.toString()

            val request = Request.Builder()
                .url(FLUX_URL)
                .addHeader("Authorization", "Bearer ${Constants.HF_TOKEN}")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(500) ?: "no body"
                Log.e(TAG, "✗ HTTP ${response.code}: $errorBody")
                return@withContext null
            }

            val bytes = response.body?.bytes()
            if (bytes == null || bytes.isEmpty()) {
                Log.e(TAG, "✗ Empty response body")
                return@withContext null
            }

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                Log.d(TAG, "✓ Image generated: ${bitmap.width}x${bitmap.height}")
            } else {
                Log.e(TAG, "✗ Failed to decode bitmap from ${bytes.size} bytes")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "✗ Image generation failed: ${e.message}", e)
            null
        }
    }
}
