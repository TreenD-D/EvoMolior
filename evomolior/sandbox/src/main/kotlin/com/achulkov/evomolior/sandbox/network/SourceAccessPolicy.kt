package com.achulkov.evomolior.sandbox.network

import java.net.IDN
import java.net.URI

/**
 * Deterministic policy for run source access.
 *
 * The policy has two jobs:
 * - infer conservative, subject-specific source hosts at run start,
 * - evaluate in-run source-access requests before expanding the allowlist.
 */
object SourceAccessPolicy {
    private val unsafeHostSuffixes = listOf(
        ".local",
        ".internal",
        ".localhost",
        ".lan"
    )

    private val privateIpv4Ranges = listOf(
        Regex("""^10\."""),
        Regex("""^127\."""),
        Regex("""^169\.254\."""),
        Regex("""^172\.(1[6-9]|2\d|3[0-1])\."""),
        Regex("""^192\.168\.""")
    )

    /**
     * Extracts hosts explicitly present in the prompt and adds narrow subject-based hosts.
     */
    fun initialAllowedHosts(objective: String, artifactHosts: Set<String> = emptySet()): Set<String> =
        normalizeHosts(
            artifactHosts +
                ContextUrlAllowlistExtractor.extractHosts(objective) +
                inferSubjectHosts(objective)
        )

    /**
     * Infers likely source hosts from the run objective.
     */
    fun inferSubjectHosts(objective: String): Set<String> {
        val normalized = objective.lowercase()
        return buildSet {
            if (containsAny(normalized, "qwen", "dashscope", "alibaba cloud", "aliyun")) {
                addAll(
                    listOf(
                        "qwenlm.github.io",
                        "dashscope.aliyuncs.com",
                        "help.aliyun.com",
                        "alibabacloud.com"
                    )
                )
            }
            if (containsAny(normalized, "hackathon", "devpost")) {
                addAll(listOf("devpost.com", "qwencloud-hackathon.devpost.com"))
            }
            if (containsAny(normalized, "kotlin", "gradle", "koog", "jetbrains")) {
                addAll(listOf("kotlinlang.org", "docs.gradle.org", "gradle.org", "jetbrains.com", "github.com"))
            }
            if (containsAny(normalized, "android", "compose multiplatform", "compose desktop")) {
                addAll(listOf("developer.android.com", "jetbrains.com", "kotlinlang.org", "play.google.com"))
            }
            if (containsAny(normalized, "micro-saas", "micro saas", "app product", "product hypoth", "app store", "play store", "mobile app", "product discovery", "indie hacker", "startup idea")) {
                addAll(
                    listOf(
                        "reddit.com",
                        "www.reddit.com",
                        "old.reddit.com",
                        "producthunt.com",
                        "www.producthunt.com",
                        "indiehackers.com",
                        "www.indiehackers.com",
                        "play.google.com",
                        "apps.apple.com",
                        "developer.apple.com",
                        "chromewebstore.google.com"
                    )
                )
            }
            if (containsAny(normalized, "python", "pypi")) {
                addAll(listOf("docs.python.org", "pypi.org", "files.pythonhosted.org"))
            }
            if (containsAny(normalized, "npm", "javascript", "typescript", "node")) {
                addAll(listOf("npmjs.com", "registry.npmjs.org", "nodejs.org", "developer.mozilla.org"))
            }
            if (containsAny(normalized, "rust", "cargo")) {
                addAll(listOf("doc.rust-lang.org", "docs.rs", "crates.io"))
            }
            if (containsAny(normalized, "medical", "clinical", "health", "disease", "pubmed", "cdc")) {
                addAll(listOf("pubmed.ncbi.nlm.nih.gov", "ncbi.nlm.nih.gov", "cdc.gov", "who.int"))
            }
            if (containsAny(normalized, "paper", "research", "arxiv", "benchmark", "evaluation")) {
                addAll(listOf("arxiv.org", "paperswithcode.com", "github.com"))
            }
            if (containsAny(normalized, "prediction market", "prediction-market", "event market", "kalshi", "polymarket", "predictit", "manifold")) {
                addAll(
                    listOf(
                        "kalshi.com",
                        "api.elections.kalshi.com",
                        "polymarket.com",
                        "clob.polymarket.com",
                        "gamma-api.polymarket.com",
                        "predictit.org",
                        "manifold.markets"
                    )
                )
            }
        }.let(::normalizeHosts)
    }

    /**
     * Evaluates an in-run source access request.
     */
    fun evaluateRequest(
        rawUrl: String,
        purpose: String,
        runObjective: String,
        currentlyAllowedHosts: Set<String>
    ): SourceAccessDecision {
        val normalizedUrl = normalizeUrl(rawUrl)
            ?: return SourceAccessDecision.Blocked(
                url = rawUrl,
                host = null,
                reason = "Malformed URL. Provide a full http(s) URL."
            )
        val host = normalizedUrl.host
        if (!isSafePublicHost(host)) {
            return SourceAccessDecision.Blocked(
                url = normalizedUrl.toString(),
                host = host,
                reason = "Host is local, private, or otherwise unsafe for sandbox egress."
            )
        }

        val allowedHosts = normalizeHosts(currentlyAllowedHosts)
        if (hostMatchesAny(host, allowedHosts)) {
            return SourceAccessDecision.Allowed(
                url = normalizedUrl.toString(),
                host = host,
                reason = "Host is already in the run allowlist."
            )
        }

        val inferredHosts = inferSubjectHosts("$runObjective\n$purpose")
        if (hostMatchesAny(host, inferredHosts)) {
            return SourceAccessDecision.Allowed(
                url = normalizedUrl.toString(),
                host = host,
                reason = "Host matches the run subject and request purpose."
            )
        }

        return SourceAccessDecision.Blocked(
            url = normalizedUrl.toString(),
            host = host,
            reason = "Host is not in the current allowlist and was not inferred as relevant to the run subject."
        )
    }

    /**
     * Evaluates whether a URL may be fetched now using only already-approved hosts.
     *
     * This deliberately does not infer subject-relevant hosts. Agents must call
     * `request_source_access` first for new out-of-whitelist links so each inspected URL is
     * recorded through the approval path before content enters context.
     */
    fun evaluateApprovedFetch(
        rawUrl: String,
        currentlyAllowedHosts: Set<String>
    ): SourceAccessDecision {
        val normalizedUrl = normalizeUrl(rawUrl)
            ?: return SourceAccessDecision.Blocked(
                url = rawUrl,
                host = null,
                reason = "Malformed URL. Provide a full http(s) URL."
            )
        val host = normalizedUrl.host
        if (!isSafePublicHost(host)) {
            return SourceAccessDecision.Blocked(
                url = normalizedUrl.toString(),
                host = host,
                reason = "Host is local, private, or otherwise unsafe for sandbox egress."
            )
        }
        val approvedHosts = normalizeHosts(currentlyAllowedHosts)
        if (!hostMatchesAny(host, approvedHosts)) {
            return SourceAccessDecision.Blocked(
                url = normalizedUrl.toString(),
                host = host,
                reason = "Host is not approved for this run. Call request_source_access(url, purpose) first."
            )
        }
        return SourceAccessDecision.Allowed(
            url = normalizedUrl.toString(),
            host = host,
            reason = "Host is already approved for this run."
        )
    }

    fun normalizeHosts(hosts: Iterable<String>): Set<String> =
        hosts.mapNotNull { normalizeHost(it) }.toSet()

    fun normalizeHost(rawHost: String): String? {
        val trimmed = rawHost.trim().trimEnd('.').lowercase()
        if (trimmed.isBlank()) return null
        val host = runCatching { URI(if (trimmed.contains("://")) trimmed else "https://$trimmed").host }
            .getOrNull()
            ?: trimmed
        return runCatching { IDN.toASCII(host.trim().trimEnd('.').lowercase()) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizeUrl(rawUrl: String): URI? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https")) return null
        val host = uri.host?.let(::normalizeHost) ?: return null
        return runCatching {
            URI(
                scheme,
                uri.userInfo,
                host,
                uri.port,
                uri.rawPath?.takeIf { it.isNotBlank() } ?: "/",
                uri.rawQuery,
                null
            )
        }.getOrNull()
    }

    private fun isSafePublicHost(host: String): Boolean {
        val normalized = normalizeHost(host) ?: return false
        if (normalized == "localhost") return false
        if (unsafeHostSuffixes.any { normalized.endsWith(it) }) return false
        if (privateIpv4Ranges.any { it.containsMatchIn(normalized) }) return false
        if (normalized.contains(":")) return false
        return true
    }

    private fun hostMatchesAny(host: String, allowedHosts: Set<String>): Boolean {
        val normalizedHost = normalizeHost(host) ?: return false
        return allowedHosts.any { allowed ->
            val normalizedAllowed = normalizeHost(allowed) ?: return@any false
            normalizedHost == normalizedAllowed || normalizedHost.endsWith(".$normalizedAllowed")
        }
    }

    private fun containsAny(text: String, vararg needles: String): Boolean =
        needles.any { needle -> text.contains(needle) }
}

/** Result of a source-access policy decision. */
sealed class SourceAccessDecision {
    abstract val url: String
    abstract val host: String?
    abstract val reason: String

    data class Allowed(
        override val url: String,
        override val host: String,
        override val reason: String
    ) : SourceAccessDecision()

    data class Blocked(
        override val url: String,
        override val host: String?,
        override val reason: String
    ) : SourceAccessDecision()
}
