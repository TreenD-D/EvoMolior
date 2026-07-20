package com.achulkov.evomolior.core.kg

/**
 * Resolves agent-supplied KG relation names to the exact canonical spelling authorized by Council.
 */
object KgRelationResolver {
    private val aliases = mapOf(
        "requires_rollout_gate" to "requires",
        "risks" to "concerns"
    )

    /**
     * Resolves [requested] against [allowedRelations] without extending schema authority.
     *
     * Exact names and case variants resolve to the spelling present in [allowedRelations].
     * Aliases resolve only when their canonical target is also present in that set.
     *
     * @param requested Agent-supplied relation.
     * @param allowedRelations Active Council-authorized relation names.
     * @return Canonical relation, or null when the schema does not authorize the request.
     */
    fun resolve(requested: String, allowedRelations: Collection<String>): String? {
        val normalizedRequested = requested.trim().lowercase()
        if (normalizedRequested.isEmpty()) return null
        val byNormalizedName = allowedRelations
            .filter { it.isNotBlank() }
            .associateBy { it.trim().lowercase() }
        byNormalizedName[normalizedRequested]?.let { return it }
        val aliasTarget = aliases[normalizedRequested] ?: return null
        return byNormalizedName[aliasTarget]
    }
}
