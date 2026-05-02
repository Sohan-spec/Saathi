package com.sohanreddy.sevak.data.rag

/**
 * Splits text into sentence-level chunks with word-count limits and overlap.
 */
object ChunkingManager {

    private const val MAX_WORDS_PER_CHUNK = 100
    private const val OVERLAP_WORDS = 20
    private const val MIN_CHUNK_WORDS = 10
    private const val SHORT_TEXT_THRESHOLD = 50

    /**
     * Split [text] into overlapping chunks based on sentence boundaries.
     * Short texts (< 50 words) are returned as a single chunk.
     */
    fun chunk(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()

        val words = trimmed.split("\\s+".toRegex())
        if (words.size < SHORT_TEXT_THRESHOLD) {
            // Short text — return as single chunk if it meets minimum
            return if (words.size >= MIN_CHUNK_WORDS) listOf(trimmed) else listOf(trimmed)
        }

        // Split on sentence boundaries first
        val sentences = trimmed.split(Regex("(?<=[.?!])\\s+|(?<=\\n)"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val chunks = mutableListOf<String>()
        val currentChunkWords = mutableListOf<String>()

        for (sentence in sentences) {
            val sentenceWords = sentence.split("\\s+".toRegex())

            // If adding this sentence would exceed max, flush current chunk
            if (currentChunkWords.size + sentenceWords.size > MAX_WORDS_PER_CHUNK && currentChunkWords.isNotEmpty()) {
                val chunkText = currentChunkWords.joinToString(" ")
                chunks.add(chunkText)

                // Keep the last OVERLAP_WORDS for overlap
                val overlapStart = (currentChunkWords.size - OVERLAP_WORDS).coerceAtLeast(0)
                val overlapWords = currentChunkWords.subList(overlapStart, currentChunkWords.size).toList()
                currentChunkWords.clear()
                currentChunkWords.addAll(overlapWords)
            }

            currentChunkWords.addAll(sentenceWords)
        }

        // Flush remaining words
        if (currentChunkWords.isNotEmpty()) {
            val chunkText = currentChunkWords.joinToString(" ")
            // Only add if it meets minimum size, or merge with previous chunk
            if (chunkText.split("\\s+".toRegex()).size >= MIN_CHUNK_WORDS) {
                chunks.add(chunkText)
            } else if (chunks.isNotEmpty()) {
                // Merge with previous chunk
                val prev = chunks.removeAt(chunks.lastIndex)
                chunks.add("$prev $chunkText")
            } else {
                // Only chunk — add even if small
                chunks.add(chunkText)
            }
        }

        return chunks
    }
}
