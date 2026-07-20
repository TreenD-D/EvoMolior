package com.achulkov.evomolior.sandbox.network

import com.achulkov.evomolior.core.model.Role

/**
 * Role-aware egress allowlist for agent web fetches.
 *
 * This acts as the policy gate in front of remote content fetches. A request is allowed only
 * when the hostname is approved for the run and (if configured) in the role-specific list.
 *
 * @param roleAllowedHosts Optional per-role host constraints.
 */
class AllowlistProxy(
    private val roleAllowedHosts: Map<Role, Set<String>> = emptyMap()
) {
    /**
     * Evaluates whether [url] is allowed for [role].
     *
     * @param url Target URL.
     * @param role Agent role making the request.
     * @param approvedHosts Hosts approved by the run source-access policy.
     * @return Decision with reason.
     */
    fun evaluate(url: String, role: Role, approvedHosts: Set<String>): AllowlistDecision {
        val sourceDecision = SourceAccessPolicy.evaluateApprovedFetch(url, approvedHosts)
        if (sourceDecision is SourceAccessDecision.Blocked) {
            return AllowlistDecision.Denied(sourceDecision.reason)
        }
        sourceDecision as SourceAccessDecision.Allowed
        val host = sourceDecision.host
        val roleHosts = roleAllowedHosts[role]
        if (roleHosts != null && !isHostAllowed(host, roleHosts)) {
            return AllowlistDecision.Denied("Host '$host' not allowed for role ${role.name}")
        }
        return AllowlistDecision.Allowed(sourceDecision.url, host)
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
}

/** Result of an allowlist policy evaluation. */
sealed class AllowlistDecision {
    /** Request is allowed. */
    data class Allowed(val url: String, val host: String) : AllowlistDecision()
    /** Request is denied with explanation. */
    data class Denied(val reason: String) : AllowlistDecision()
}
