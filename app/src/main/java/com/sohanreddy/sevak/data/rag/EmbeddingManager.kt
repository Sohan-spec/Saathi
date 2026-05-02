package com.sohanreddy.sevak.data.rag

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Singleton that loads the all-MiniLM-L6-v2 ONNX model and tokenizer,
 * and provides sentence embedding generation on-device.
 *
 * Produces 384-dimensional normalized float vectors.
 */
object EmbeddingManager {

    private const val TAG = "EmbeddingManager"
    private const val MODEL_FILE = "model.onnx"
    private const val TOKENIZER_FILE = "tokenizer.json"
    private const val EMBEDDING_DIM = 384
    private const val MAX_SEQ_LENGTH = 128

    // Special token IDs (from tokenizer.json)
    private const val CLS_TOKEN_ID = 101L
    private const val SEP_TOKEN_ID = 102L
    private const val UNK_TOKEN_ID = 100L
    private const val PAD_TOKEN_ID = 0L

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var vocab: Map<String, Long> = emptyMap()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /**
     * Initialize the ONNX model and tokenizer from assets.
     * Call this on a background thread — model loading takes 1-2 seconds.
     */
    fun init(context: Context) {
        try {
            Log.d(TAG, "Initializing ONNX model...")
            val startTime = System.currentTimeMillis()

            // Load tokenizer vocabulary
            val tokenizerJson = context.assets.open(TOKENIZER_FILE)
                .bufferedReader().use { it.readText() }
            val tokenizer = JSONObject(tokenizerJson)
            val model = tokenizer.getJSONObject("model")
            val vocabObj = model.getJSONObject("vocab")

            val vocabMap = mutableMapOf<String, Long>()
            val keys = vocabObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                vocabMap[key] = vocabObj.getLong(key)
            }
            vocab = vocabMap
            Log.d(TAG, "Loaded vocabulary: ${vocab.size} tokens")

            // Load ONNX model
            ortEnvironment = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
            ortSession = ortEnvironment!!.createSession(modelBytes)
            Log.d(TAG, "ONNX model loaded in ${System.currentTimeMillis() - startTime}ms")

            _isReady.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EmbeddingManager: ${e.message}", e)
            _isReady.value = false
        }
    }

    /**
     * Generate a 384-dimensional embedding for the given text.
     * Returns a unit-length normalized float vector.
     */
    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        val env = ortEnvironment ?: throw IllegalStateException("EmbeddingManager not initialized")
        val session = ortSession ?: throw IllegalStateException("ONNX session not initialized")

        // Tokenize
        val tokenIds = tokenize(text)

        // Create attention mask (1 for real tokens, 0 for padding)
        val attentionMask = LongArray(MAX_SEQ_LENGTH) { if (it < tokenIds.size) 1L else 0L }

        // Pad token IDs to max sequence length
        val paddedTokenIds = LongArray(MAX_SEQ_LENGTH) { if (it < tokenIds.size) tokenIds[it] else PAD_TOKEN_ID }

        // Token type IDs (all zeros for single-sentence)
        val tokenTypeIds = LongArray(MAX_SEQ_LENGTH) { 0L }

        // Create ONNX tensors — shape: [1, MAX_SEQ_LENGTH]
        val shape = longArrayOf(1, MAX_SEQ_LENGTH.toLong())
        val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(paddedTokenIds), shape)
        val attentionMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape)
        val tokenTypeIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape)

        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attentionMaskTensor,
            "token_type_ids" to tokenTypeIdsTensor
        )

        try {
            // Run inference
            val results = session.run(inputs)

            // Extract output — last_hidden_state shape: [1, seq_len, 384]
            @Suppress("UNCHECKED_CAST")
            val output = results[0].value as Array<Array<FloatArray>>
            val tokenEmbeddings = output[0] // [seq_len, 384]

            // Mean pooling over non-padding tokens
            val numRealTokens = tokenIds.size
            val pooled = FloatArray(EMBEDDING_DIM)
            for (i in 0 until numRealTokens) {
                for (j in 0 until EMBEDDING_DIM) {
                    pooled[j] += tokenEmbeddings[i][j]
                }
            }
            for (j in 0 until EMBEDDING_DIM) {
                pooled[j] /= numRealTokens.toFloat()
            }

            // L2 normalize
            normalize(pooled)
        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
            tokenTypeIdsTensor.close()
        }
    }

    /**
     * WordPiece tokenization following BERT conventions:
     * 1. Lowercase + strip accents
     * 2. Split on whitespace and punctuation
     * 3. Apply WordPiece subword splitting
     * 4. Wrap with [CLS] ... [SEP]
     * 5. Truncate to MAX_SEQ_LENGTH
     */
    private fun tokenize(text: String): List<Long> {
        // BertNormalizer: lowercase, clean text
        val normalized = text.lowercase().trim()

        // BertPreTokenizer: split on whitespace and punctuation
        val preTokens = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in normalized) {
            when {
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        preTokens.add(current.toString())
                        current.clear()
                    }
                }
                isPunctuation(ch) -> {
                    if (current.isNotEmpty()) {
                        preTokens.add(current.toString())
                        current.clear()
                    }
                    preTokens.add(ch.toString())
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            preTokens.add(current.toString())
        }

        // WordPiece tokenization
        val tokenIds = mutableListOf<Long>()
        tokenIds.add(CLS_TOKEN_ID)

        for (word in preTokens) {
            val subTokenIds = wordPieceTokenize(word)
            // Check if adding these would exceed max length (reserving 1 for [SEP])
            if (tokenIds.size + subTokenIds.size >= MAX_SEQ_LENGTH - 1) {
                // Add what we can
                val remaining = MAX_SEQ_LENGTH - 1 - tokenIds.size
                tokenIds.addAll(subTokenIds.take(remaining))
                break
            }
            tokenIds.addAll(subTokenIds)
        }

        tokenIds.add(SEP_TOKEN_ID)
        return tokenIds
    }

    /**
     * Apply WordPiece algorithm to a single word.
     * Uses "##" prefix for continuation subwords.
     */
    private fun wordPieceTokenize(word: String): List<Long> {
        if (word.length > 100) return listOf(UNK_TOKEN_ID) // max_input_chars_per_word

        val tokens = mutableListOf<Long>()
        var start = 0

        while (start < word.length) {
            var end = word.length
            var matched = false

            while (start < end) {
                val substr = if (start > 0) "##${word.substring(start, end)}" else word.substring(start, end)
                val id = vocab[substr]
                if (id != null) {
                    tokens.add(id)
                    start = end
                    matched = true
                    break
                }
                end--
            }

            if (!matched) {
                tokens.add(UNK_TOKEN_ID)
                start++
            }
        }

        return tokens
    }

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        // ASCII punctuation ranges
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
        // Unicode general punctuation
        return Character.getType(ch).toByte().let { type ->
            type == Character.CONNECTOR_PUNCTUATION.toByte() ||
            type == Character.DASH_PUNCTUATION.toByte() ||
            type == Character.END_PUNCTUATION.toByte() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toByte() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toByte() ||
            type == Character.OTHER_PUNCTUATION.toByte() ||
            type == Character.START_PUNCTUATION.toByte()
        }
    }

    /**
     * L2-normalize a vector in place, returns the same array.
     */
    private fun normalize(vector: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in vector) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm > 0f) {
            for (i in vector.indices) vector[i] /= norm
        }
        return vector
    }

    /**
     * Release ONNX resources.
     */
    fun close() {
        try {
            ortSession?.close()
            ortEnvironment?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX resources: ${e.message}")
        }
        ortSession = null
        ortEnvironment = null
        _isReady.value = false
    }
}
