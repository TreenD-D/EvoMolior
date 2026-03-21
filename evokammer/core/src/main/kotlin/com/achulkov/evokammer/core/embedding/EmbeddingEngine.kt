package com.achulkov.evokammer.core.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Lightweight in-process TF-IDF–style text embedding engine.
 *
 * Uses the **feature-hashing trick**: each token is hashed deterministically to a slot
 * in a fixed-size float array ([DIMS] = 512).  This eliminates the need for a shared
 * corpus vocabulary, enabling per-document embedding at insertion time.
 *
 * ### Algorithm
 * 1. Tokenise: lowercase → split on non-word chars → filter common English stop words
 * 2. Per token, compute `log(1 + tf)` weight
 * 3. Hash token to `[0, DIMS)` using unsigned modulo of `hashCode()`
 * 4. Accumulate weighted counts into the vector
 * 5. L2-normalise so all vectors lie on the unit hypersphere
 *
 * ### Storage
 * `FloatArray` ↔ `ByteArray` serialisation via [ByteBuffer] (little-endian).
 *
 * ### Similarity
 * Because vectors are L2-normalised, the dot product equals the cosine similarity.
 */
object EmbeddingEngine {

    /** Number of hash buckets (floats per embedding). Increase for more precision. */
    const val DIMS = 512

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Embeds [text] into a byte array suitable for storage in the `embedding` column of the KB entry table.
     */
    fun embed(text: String): ByteArray {
        return floatArrayToBytes(embedToFloats(text))
    }

    /**
     * Computes the cosine similarity between two embeddings.
     *
     * @return A value in [-1, 1]; 1 means identical, 0 means orthogonal.
     *         Returns 0.0 if either embedding is all-zero (empty document).
     */
    @Suppress("unused") // public utility — available for direct cross-entry comparisons by callers
    fun cosineSimilarity(a: ByteArray, b: ByteArray): Double {
        return dotProduct(bytesToFloatArray(a), bytesToFloatArray(b))
    }

    /**
     * Ranks [candidates] by cosine similarity to [query].
     *
     * @return Candidates sorted by descending similarity, with their scores.
     */
    fun <T> rank(
        query: ByteArray,
        candidates: List<T>,
        embeddingOf: (T) -> ByteArray?
    ): List<Pair<T, Double>> {
        if (candidates.isEmpty()) return emptyList()
        val queryVec = bytesToFloatArray(query)
        return candidates
            .mapNotNull { item ->
                val emb = embeddingOf(item) ?: return@mapNotNull null
                val score = dotProduct(queryVec, bytesToFloatArray(emb))
                item to score
            }
            .sortedByDescending { it.second }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    internal fun embedToFloats(text: String): FloatArray {
        val tokens = tokenise(text)
        if (tokens.isEmpty()) return FloatArray(DIMS)

        // Compute term frequencies
        val tfMap = mutableMapOf<String, Int>()
        for (token in tokens) tfMap[token] = (tfMap[token] ?: 0) + 1

        val totalTerms = tokens.size.toDouble()
        val vec = FloatArray(DIMS)
        for ((term, count) in tfMap) {
            val tf = ln(1.0 + count / totalTerms)
            // Feature hashing: map term → bucket in [0, DIMS)
            val bucket = (term.hashCode() and Int.MAX_VALUE) % DIMS
            vec[bucket] += tf.toFloat()
        }

        return l2Normalise(vec)
    }

    private fun tokenise(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[^a-zA-Z0-9]+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
    }

    private fun l2Normalise(vec: FloatArray): FloatArray {
        val norm = sqrt(vec.sumOf { (it * it).toDouble() })
        if (norm < 1e-10) return vec
        return FloatArray(vec.size) { (vec[it] / norm).toFloat() }
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Dimension mismatch: ${a.size} vs ${b.size}" }
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    internal fun floatArrayToBytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }

    internal fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.getFloat() }
    }

    // ── Common English stop words (extended with domain-neutral noise) ────────

    private val STOP_WORDS = setOf(
        "the", "a", "an", "is", "in", "it", "of", "to", "and", "or", "not",
        "for", "on", "with", "as", "be", "at", "by", "from", "this", "that",
        "are", "was", "were", "been", "has", "have", "had", "will", "would",
        "can", "could", "should", "may", "might", "do", "does", "did", "so",
        "if", "but", "no", "we", "you", "he", "she", "they", "its", "our",
        "their", "my", "your", "all", "more", "also", "than", "then", "when",
        "which", "who", "what", "how", "any", "each", "some", "one", "two",
        "about", "into", "up", "out", "like", "just", "only", "use", "used",
        "using", "new", "well", "get", "set", "let", "per", "based", "given",
        "between", "through", "over", "under", "because", "while", "after",
        "before", "during", "without", "within", "against", "across", "along"
    )
}
