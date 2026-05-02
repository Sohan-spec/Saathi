package com.sohanreddy.sevak.screen

import android.util.Log
import com.sohanreddy.sevak.Constants
import com.sohanreddy.sevak.network.GroqApi
import com.sohanreddy.sevak.network.GroqMessage
import com.sohanreddy.sevak.network.GroqRequest

/**
 * Handles Groq Vision API calls with screenshot context.
 * Uses the same endpoint as text-only Groq but with multimodal content blocks.
 */
object ScreenQueryRepository {

    private const val TAG = "ScreenQuery"
    private const val VISION_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"

    /**
     * Send a vision query with both text (transcript) and image (base64 screenshot).
     * Falls back to text-only if vision call fails.
     */
    suspend fun queryWithScreenContext(
        transcript: String,
        base64Image: String,
        langName: String,
        ragContext: String = ""
    ): String {
        Log.d(TAG, "══════ VISION API CALL ══════")
        Log.d(TAG, "Model: $VISION_MODEL")
        Log.d(TAG, "Transcript: $transcript")
        Log.d(TAG, "Image base64 length: ${base64Image.length}")
        Log.d(TAG, "Language: $langName")
        Log.d(TAG, "RAG context length: ${ragContext.length}")

        return try {
            val systemPrompt = buildString {
                append("You are Saathi, a helpful AI assistant for rural and semi-urban Indian users.\n")
                append("The user speaks $langName. Always respond in $langName only.\n")
                append("The user has shared their screen. Analyze what is visible and answer their question.\n")
                append("Describe what you see in simple terms first, then answer the question.\n")
                append("Focus on any text, numbers, or important information visible on screen.\n")
                append("Use extremely simple words. Speak like a helpful neighbor, not a government form.\n")
                append("Keep responses under 5 sentences. Be direct and actionable.\n")
                append("Never use English if the selected language is not English.")
                if (ragContext.isNotBlank()) {
                    append("\n\nPrevious conversation context:\n")
                    append(ragContext)
                }
            }

            // Build multimodal content for the user message
            // Groq vision expects content as an array of typed blocks
            val userContent = listOf(
                mapOf("type" to "text", "text" to transcript),
                mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf("url" to base64Image)
                )
            )

            val requestBody = mapOf(
                "model" to VISION_MODEL,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userContent)
                ),
                "temperature" to 0.4,
                "max_tokens" to 500
            )

            Log.d(TAG, "Sending request to Groq vision endpoint...")
            val response = GroqApi.service.chatRaw(
                auth = "Bearer ${Constants.GROQ_API_KEY}",
                body = requestBody
            )
            Log.d(TAG, "Raw response keys: ${response.keys}")

            // Check for error in response
            if (response.containsKey("error")) {
                val error = response["error"]
                Log.e(TAG, "Groq API error: $error")
                throw RuntimeException("Groq API returned error: $error")
            }

            val choices = response["choices"] as? List<*>
            Log.d(TAG, "Choices count: ${choices?.size ?: 0}")
            val firstChoice = choices?.firstOrNull() as? Map<*, *>
            val message = firstChoice?.get("message") as? Map<*, *>
            val content = message?.get("content") as? String

            if (content != null) {
                Log.d(TAG, "✓ Vision response received: ${content.take(150)}")
            } else {
                Log.w(TAG, "✗ No content in response. Full response: $response")
            }

            Log.d(TAG, "══════ END VISION API ══════")
            content ?: "Something went wrong, please try again"
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "Vision HTTP ${e.code()}: $errorBody", e)
            fallbackToTextOnly(transcript, langName)
        } catch (e: Exception) {
            Log.e(TAG, "Vision query failed: ${e.message}", e)
            fallbackToTextOnly(transcript, langName)
        }
    }

    private suspend fun fallbackToTextOnly(transcript: String, langName: String): String {
        return try {
            Log.d(TAG, "Falling back to text-only Groq")
            val fallbackRequest = GroqRequest(
                messages = listOf(
                    GroqMessage("system", "You are Saathi, a helpful AI assistant. The user speaks $langName. Respond in $langName."),
                    GroqMessage("user", transcript)
                )
            )
            val fallbackResponse = GroqApi.chat(request = fallbackRequest)
            fallbackResponse.choices.firstOrNull()?.message?.content
                ?: "Something went wrong, please try again"
        } catch (fallbackError: Exception) {
            Log.e(TAG, "Text fallback also failed: ${fallbackError.message}")
            "Something went wrong, please try again"
        }
    }
}
