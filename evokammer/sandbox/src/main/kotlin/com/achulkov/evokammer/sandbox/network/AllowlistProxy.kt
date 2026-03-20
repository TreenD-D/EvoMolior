package com.achulkov.evokammer.sandbox.network
import com.achulkov.evokammer.core.model.Role
import java.net.URI
/**
 * Role-aware egress allowlist for agent web fetches.
 *
 * This acts as the policy gate in front of remote content fetches. A request is allowed only
 * when the hostname is in the global allowlist and (if configured) in the role-specific list.
 *
 * @param globalAllowedHosts Hosts available to all roles.
 * @param roleAllowedHosts Optional per-role host constraints.
 */
class AllowlistProxy(
    private val globalAllowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
    private val roleAllowedHosts: Map<Role, Set<String>> = emptyMap()
) {
    /**
     * Evaluates whether [url] is allowed for [role].
     *
     * @param url Target URL.
     * @param role Agent role making the request.
     * @return Decision with reason.
     */
    fun evaluate(url: String, role: Role): AllowlistDecision {
        val host = parseHost(url)
            ?: return AllowlistDecision.Denied("Malformed URL")
        if (!isHostAllowed(host, globalAllowedHosts)) {
            return AllowlistDecision.Denied("Host '$host' not in global allowlist")
        }
        val roleHosts = roleAllowedHosts[role]
        if (roleHosts != null && !isHostAllowed(host, roleHosts)) {
            return AllowlistDecision.Denied("Host '$host' not allowed for role ${role.name}")
        }
        return AllowlistDecision.Allowed(host)
    }
    /**
     * True when [host] matches any allowlist entry.
     *
     * Entries may be exact (`example.com`) or suffix (`.example.com`).
     *
     * @param host Parsed request host.
     * @param allowlist Allowlist entries.
     */
    private fun isHostAllowed(host: String, allowlist: Set<String>): Boolean {
        val normalisedHost = host.lowercase()
        return allowlist.any { entry ->
            val e = entry.lowercase()
            normalisedHost == e || normalisedHost.endsWith(".$e")
        }
    }
    /**
     * Parses URL host safely.
     *
     * @param url Raw URL string.
     */
    private fun parseHost(url: String): String? =
        runCatching { URI(url).host }.getOrNull()
    companion object {
        /** Conservative default host allowlist for web research roles. */
        val DEFAULT_ALLOWED_HOSTS: Set<String> = setOf(
            "wikipedia.org",
            "arxiv.org",
            "docs.python.org",
            "developer.mozilla.org",
            "kotlinlang.org",
            "github.com"
        )
    }
}
/** Result of an allowlist policy evaluation. */
sealed class AllowlistDecision {
    /** Request is allowed. */
    data class Allowed(val host: String) : AllowlistDecision()
    /** Request is denied with explanation. */
    data class Denied(val reason: String) : AllowlistDecision()
}