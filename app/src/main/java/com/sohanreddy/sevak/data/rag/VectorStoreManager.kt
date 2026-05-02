package com.sohanreddy.sevak.data.rag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * File-backed vector store for conversation chunks.
 * Uses brute-force cosine similarity search — fast enough for
 * personal conversation history (hundreds to low thousands of chunks).
 *
 * Chunks are persisted to internal storage as a serialized list.
 * All operations are thread-safe via Mutex.
 */
object VectorStoreManager {

    private const val TAG = "VectorStoreManager"
    private const val STORE_FILE = "conversation_chunks.bin"

    private var chunks = mutableListOf<ConversationChunk>()
    private val idCounter = AtomicLong(0)
    private val mutex = Mutex()
    private var storeFile: File? = null

    /**
     * Initialize the vector store — load persisted chunks from disk.
     */
    fun init(context: Context) {
        storeFile = File(context.filesDir, STORE_FILE)
        loadFromDisk()
        Log.d(TAG, "VectorStoreManager initialized with ${chunks.size} chunks")
    }

    /**
     * Chunk, embed, and store both the user query and AI response.
     */
    suspend fun storeConversationTurn(
        userId: String,
        sessionId: String,
        userQuery: String,
        aiResponse: String
    ) = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()

            // Chunk and store user query
            val userChunks = ChunkingManager.chunk(userQuery)
            for (chunkText in userChunks) {
                try {
                    val embedding = EmbeddingManager.embed(chunkText)
                    val chunk = ConversationChunk(
                        id = idCounter.incrementAndGet(),
                        userId = userId,
                        text = chunkText,
                        sourceType = "user_query",
                        timestamp = timestamp,
                        sessionId = sessionId,
                        embedding = embedding
                    )
                    mutex.withLock { chunks.add(chunk) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to embed/store user chunk: ${e.message}")
                }
            }

            // Chunk and store AI response
            val aiChunks = ChunkingManager.chunk(aiResponse)
            for (chunkText in aiChunks) {
                try {
                    val embedding = EmbeddingManager.embed(chunkText)
                    val chunk = ConversationChunk(
                        id = idCounter.incrementAndGet(),
                        userId = userId,
                        text = chunkText,
                        sourceType = "ai_response",
                        timestamp = timestamp,
                        sessionId = sessionId,
                        embedding = embedding
                    )
                    mutex.withLock { chunks.add(chunk) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to embed/store AI chunk: ${e.message}")
                }
            }

            // Persist to disk
            saveToDisk()
            Log.d(TAG, "Stored ${userChunks.size} user + ${aiChunks.size} AI chunks (total: ${chunks.size})")
        } catch (e: Exception) {
            Log.e(TAG, "storeConversationTurn failed: ${e.message}", e)
        }
    }

    /**
     * Brute-force nearest neighbor search filtered by userId.
     * Computes cosine similarity (dot product of unit vectors) for all
     * user's chunks and returns the top K.
     */
    suspend fun retrieveTopChunks(
        userId: String,
        queryEmbedding: FloatArray,
        topK: Int = 50
    ): List<ConversationChunk> = withContext(Dispatchers.IO) {
        try {
            val userChunks = mutex.withLock {
                chunks.filter { it.userId == userId }
            }

            if (userChunks.isEmpty()) return@withContext emptyList()

            // Score all chunks by cosine similarity (dot product of unit vectors)
            val scored = userChunks.map { chunk ->
                chunk to dotProduct(queryEmbedding, chunk.embedding)
            }

            val results = scored
                .sortedByDescending { it.second }
                .take(topK)
                .map { it.first }

            Log.d(TAG, "Retrieved ${results.size} chunks for userId=$userId from ${userChunks.size} total")
            results
        } catch (e: Exception) {
            Log.e(TAG, "retrieveTopChunks failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Rerank chunks by cosine similarity with the query embedding.
     * Both vectors are already unit-normalized, so cosine = dot product.
     */
    fun rerankChunks(
        queryEmbedding: FloatArray,
        chunks: List<ConversationChunk>,
        topN: Int = 10
    ): List<ConversationChunk> {
        return chunks
            .map { chunk -> chunk to dotProduct(queryEmbedding, chunk.embedding) }
            .sortedByDescending { it.second }
            .take(topN)
            .map { it.first }
    }

    /**
     * Full RAG retrieval pipeline: embed query → retrieve top 50 → rerank to top 10.
     */
    suspend fun retrieveAndRerank(
        userId: String,
        query: String
    ): List<ConversationChunk> {
        return try {
            val queryEmbedding = EmbeddingManager.embed(query)
            val topChunks = retrieveTopChunks(userId, queryEmbedding, topK = 50)
            if (topChunks.isEmpty()) return emptyList()
            rerankChunks(queryEmbedding, topChunks, topN = 10)
        } catch (e: Exception) {
            Log.e(TAG, "retrieveAndRerank failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Delete all chunks for a given user — called on sign-out.
     */
    suspend fun deleteAllForUser(userId: String) = withContext(Dispatchers.IO) {
        try {
            val removed = mutex.withLock {
                val before = chunks.size
                chunks.removeAll { it.userId == userId }
                before - chunks.size
            }
            saveToDisk()
            Log.d(TAG, "Deleted $removed chunks for userId=$userId")
        } catch (e: Exception) {
            Log.e(TAG, "deleteAllForUser failed: ${e.message}", e)
        }
    }

    /**
     * Dot product of two float arrays (cosine similarity for unit vectors).
     */
    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            sum += a[i] * b[i]
        }
        return sum
    }

    /**
     * Persist all chunks to disk.
     */
    @Suppress("UNCHECKED_CAST")
    private fun saveToDisk() {
        try {
            val file = storeFile ?: return
            ObjectOutputStream(file.outputStream().buffered()).use { oos ->
                oos.writeLong(idCounter.get())
                oos.writeObject(ArrayList(chunks))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chunks to disk: ${e.message}")
        }
    }

    /**
     * Load chunks from disk.
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadFromDisk() {
        try {
            val file = storeFile ?: return
            if (!file.exists() || file.length() == 0L) return

            ObjectInputStream(file.inputStream().buffered()).use { ois ->
                val savedId = ois.readLong()
                val savedChunks = ois.readObject() as ArrayList<ConversationChunk>
                idCounter.set(savedId)
                chunks = savedChunks.toMutableList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chunks from disk: ${e.message}")
            // Start fresh if file is corrupted
            chunks = mutableListOf()
            idCounter.set(0)
        }
    }
}
