package com.achulkov.evokammer.sandbox.network

import java.net.URI

/**
 * Extracts hostnames from human-authored run context text so the sandbox can allow links that
 * were explicitly embedded into the seed prompt/context.
 */
object ContextUrlAllowlistExtractor {

    private val urlPattern = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")

    /**
     * Extracts normalized hostnames from the supplied text fragments.
     *
     * Bare links like `www.example.com/path` are supported in addition to fully qualified URLs.
     * Trailing punctuation from markdown and prose is stripped before parsing.
     */
    fun extractHosts(vararg textFragments: String?): Set<String> =
        textFragments.asSequence()
            .filterNotNull()
            .flatMap { extractHosts(it).asSequence() }
            .toSet()

    /**
     * Extracts normalized hostnames from a single text fragment.
     */
    fun extractHosts(text: String): Set<String> =
        urlPattern.findAll(text)
            .mapNotNull { match -> parseHost(match.value.trimTrailingUrlPunctuation()) }
            .toSet()

    private fun parseHost(rawUrl: String): String? {
        val normalisedUrl = if (rawUrl.startsWith("www.", ignoreCase = true)) {
            "https://$rawUrl"
        } else {
            rawUrl
        }
        return runCatching { URI(normalisedUrl).host?.lowercase() }.getOrNull()
    }

    private fun String.trimTrailingUrlPunctuation(): String =
        trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
}
