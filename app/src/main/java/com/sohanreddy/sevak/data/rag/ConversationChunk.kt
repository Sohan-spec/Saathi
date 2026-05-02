package com.sohanreddy.sevak.data.rag

import java.io.Serializable

/**
 * Represents a single chunk of conversation stored in the vector store.
 * Persisted to disk via Java serialization.
 */
data class ConversationChunk(
    val id: Long,
    val userId: String,
    val text: String,
    val sourceType: String,     // "user_query" or "ai_response"
    val timestamp: Long,
    val sessionId: String,
    val embedding: FloatArray
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ConversationChunk
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
