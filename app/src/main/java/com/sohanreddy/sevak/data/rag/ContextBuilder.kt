package com.sohanreddy.sevak.data.rag

/**
 * Builds a formatted context string from retrieved conversation chunks
 * for injection into the LLM system prompt.
 */
object ContextBuilder {

    private const val MAX_CONTEXT_LENGTH = 2000

    /**
     * Format [chunks] into a context string for the LLM.
     * Chunks are sorted chronologically, formatted with role labels,
     * and truncated to [MAX_CONTEXT_LENGTH] characters (oldest dropped first).
     */
    fun buildContext(chunks: List<ConversationChunk>): String {
        if (chunks.isEmpty()) return ""

        // Sort by timestamp ascending (chronological order)
        val sorted = chunks.sortedBy { it.timestamp }

        // Format each chunk with role label
        val formatted = sorted.map { chunk ->
            when (chunk.sourceType) {
                "user_query" -> "[User said: ${chunk.text}]"
                "ai_response" -> "[Assistant said: ${chunk.text}]"
                else -> "[${chunk.text}]"
            }
        }

        // Join and truncate to max length — drop oldest chunks first if exceeded
        var result = formatted.joinToString("\n")
        if (result.length > MAX_CONTEXT_LENGTH) {
            // Remove oldest chunks until we fit
            val truncated = formatted.toMutableList()
            while (truncated.size > 1) {
                truncated.removeAt(0) // remove oldest
                result = truncated.joinToString("\n")
                if (result.length <= MAX_CONTEXT_LENGTH) break
            }
            // If single chunk still too long, hard-truncate
            if (result.length > MAX_CONTEXT_LENGTH) {
                result = result.takeLast(MAX_CONTEXT_LENGTH)
            }
        }

        return result
    }
}
