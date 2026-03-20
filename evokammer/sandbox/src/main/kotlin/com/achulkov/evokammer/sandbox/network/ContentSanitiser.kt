package com.achulkov.evokammer.sandbox.network

/**
 * Sanitises web-fetched content before it enters any agent context.
 *
 * Applies three passes:
 * 1. HTML stripping (comments, scripts)
 * 2. Injection pattern detection
 * 3. Semantic truncation to [maxTokens]
 */
class ContentSanitiser(
    private val maxTokens: Int = 4_000
) {
    /**
     * Sanitises the given content string.
     *
     * @param content Raw web page content.
     * @param sourceUrl Source URL (for logging purposes only).
     * @return Sanitised, truncated content safe to inject into agent context.
     */
    fun sanitise(content: String, sourceUrl: String = ""): SanitisedContent {
        var processed = content

        // 1. Strip HTML comments
        processed = processed.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")

        // 2. Strip script and style tags
        processed = processed.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
        processed = processed.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")

        // 3. Strip remaining HTML tags
        processed = processed.replace(Regex("<[^>]+>"), " ")

        // 4. Normalise whitespace
        processed = processed.replace(Regex("\\s+"), " ").trim()

        // 5. Check for injection patterns
        val injectionDetected = INJECTION_PATTERNS.any { pattern ->
            pattern.containsMatchIn(processed)
        }

        if (injectionDetected) {
            return SanitisedContent(
                content = "[CONTENT_SANITISED: potential prompt injection detected from $sourceUrl]",
                injectionDetected = true,
                truncated = false,
                originalLength = content.length
            )
        }

        // 6. Truncate to maxTokens (approximate: 4 chars per token)
        val maxChars = maxTokens * 4
        val truncated = processed.length > maxChars
        if (truncated) {
            processed = processed.take(maxChars) + "\n[CONTENT_TRUNCATED: source has more content]"
        }

        return SanitisedContent(
            content = processed,
            injectionDetected = false,
            truncated = truncated,
            originalLength = content.length
        )
    }

    companion object {
        /** Patterns that indicate potential prompt injection attempts. */
        private val INJECTION_PATTERNS = listOf(
            Regex("ignore (previous|prior|all) instructions", RegexOption.IGNORE_CASE),
            Regex("your new (task|instruction|goal|system prompt)", RegexOption.IGNORE_CASE),
            Regex("system prompt", RegexOption.IGNORE_CASE),
            Regex("you are now", RegexOption.IGNORE_CASE),
            Regex("disregard (everything|all|prior)", RegexOption.IGNORE_CASE),
            Regex("act as (a|an|your)", RegexOption.IGNORE_CASE),
            Regex("new instructions:", RegexOption.IGNORE_CASE),
            Regex("\\[INST\\]|<\\|im_start\\|>|<\\|system\\|>", RegexOption.IGNORE_CASE),
            Regex("STOP. New instructions follow", RegexOption.IGNORE_CASE)
        )
    }
}

/** Result of a content sanitisation operation. */
data class SanitisedContent(
    val content: String,
    val injectionDetected: Boolean,
    val truncated: Boolean,
    val originalLength: Int
)
