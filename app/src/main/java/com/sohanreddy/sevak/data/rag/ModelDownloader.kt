package com.sohanreddy.sevak.data.rag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the all-MiniLM-L6-v2 ONNX model on first launch.
 * Stores it in the app's internal files directory so it persists across sessions.
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"

    // Hugging Face direct download URL for the ONNX model
    private const val MODEL_URL =
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx"

    private const val MODEL_FILENAME = "model.onnx"
    private const val EXPECTED_SIZE_MB = 86 // approximate, for progress display

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(val progressPercent: Int) : DownloadState()
        data object Completed : DownloadState()
        data class Failed(val error: String) : DownloadState()
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    /**
     * Returns the model file if it exists and is valid.
     */
    fun getModelFile(context: Context): File? {
        val file = File(context.filesDir, MODEL_FILENAME)
        return if (file.exists() && file.length() > 1_000_000) file else null
    }

    /**
     * Download the model if not already present.
     * Returns the model File on success, null on failure.
     */
    suspend fun ensureModelDownloaded(context: Context): File? = withContext(Dispatchers.IO) {
        // Check if already downloaded
        val existing = getModelFile(context)
        if (existing != null) {
            Log.d(TAG, "Model already downloaded: ${existing.length()} bytes")
            _state.value = DownloadState.Completed
            return@withContext existing
        }

        // Download
        val targetFile = File(context.filesDir, MODEL_FILENAME)
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")

        try {
            _state.value = DownloadState.Downloading(0)
            Log.d(TAG, "Starting model download from $MODEL_URL")

            val url = URL(MODEL_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP $responseCode: ${connection.responseMessage}")
            }

            val totalBytes = connection.contentLength.toLong()
            val expectedTotal = if (totalBytes > 0) totalBytes else EXPECTED_SIZE_MB * 1024L * 1024L

            connection.inputStream.buffered().use { input ->
                tempFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var lastLoggedPercent = -1

                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val percent = ((downloaded * 100) / expectedTotal).toInt().coerceIn(0, 99)
                        if (percent != lastLoggedPercent) {
                            _state.value = DownloadState.Downloading(percent)
                            lastLoggedPercent = percent
                            if (percent % 10 == 0) {
                                Log.d(TAG, "Download progress: $percent% (${downloaded / 1024}KB)")
                            }
                        }
                    }
                }
            }

            // Rename temp to final
            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
            }

            Log.d(TAG, "Model downloaded successfully: ${targetFile.length()} bytes")
            _state.value = DownloadState.Completed
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed: ${e.message}", e)
            tempFile.delete()
            _state.value = DownloadState.Failed(e.message ?: "Download failed")
            null
        }
    }
}
