package com.achulkov.evomolior.core.config

import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * One weighted model choice within an operator-owned role policy.
 *
 * @param modelId Exact Codex model identifier.
 * @param weight Positive relative sampling weight, not a monetary budget.
 */
data class CodexModelWeight(val modelId: String, val weight: Int) {
    init {
        require(modelId.isNotBlank()) { "A model identifier is required" }
        require(weight > 0) { "Model weight must be positive" }
    }
}

/**
 * Explicit, persisted Codex role policies. Neither policy increases worker counts or budgets.
 * Full Luna is the control; both policies share reasoning tiers to isolate model-selection effects.
 * These identifiers carry stable policy semantics; future weight changes require a new policy ID.
 *
 * @param id Stable CLI and persisted identifier.
 */
@Serializable
enum class CodexRuntimePreset(val id: String) {
    @SerialName("full-luna")
    FULL_LUNA("full-luna"),

    @SerialName("mixed-families")
    MIXED_FAMILIES("mixed-families");

    /** Exact models that may execute under this preset, including utility calls. */
    val supportedModelIds: Set<String>
        get() = if (this == FULL_LUNA) setOf(LUNA) else setOf(LUNA, SOL, TERRA, ASTRA)

    /**
     * Returns the immutable role distribution. Weights never create additional spawn slots.
     *
     * @param role Role whose existing spawn is being assigned.
     */
    fun modelWeights(role: Role): List<CodexModelWeight> = when {
        this == FULL_LUNA -> listOf(CodexModelWeight(LUNA, 100))
        role.isCouncilRole || role.isGraderRole || role == Role.MetaPlanner ||
            role == Role.Auditor -> listOf(CodexModelWeight(ASTRA, 100))
        role in setOf(Role.Planner, Role.Critic, Role.Inspector, Role.PerspectiveTaker) ->
            listOf(CodexModelWeight(SOL, 50), CodexModelWeight(TERRA, 50))
        role == Role.Outlier -> listOf(
            CodexModelWeight(LUNA, 85),
            CodexModelWeight(SOL, 7),
            CodexModelWeight(TERRA, 7),
            CodexModelWeight(ASTRA, 1),
        )
        else -> listOf(CodexModelWeight(LUNA, 100))
    }

    /**
     * Returns unique model candidates in primary-first order.
     *
     * @param role Requested role.
     */
    fun modelPool(role: Role): List<String> = modelWeights(role).map(CodexModelWeight::modelId)

    /**
     * Returns the primary model for a role or a single-model utility call.
     *
     * @param role Requested role.
     */
    fun defaultModel(role: Role): String = modelWeights(role).first().modelId

    /** Runtime role pools; transient Council roles are not worker assignments. */
    fun runtimeModelPools(): Map<String, List<String>> = Role.entries
        .filterNot(Role::isCouncilRole)
        .associate { it.name to modelPool(it) }

    /** Runtime primary assignments; transient Council roles are not worker assignments. */
    fun runtimeAssignments(): Map<String, String> = Role.entries
        .filterNot(Role::isCouncilRole)
        .associate { it.name to defaultModel(it) }

    /**
     * Samples only from the sealed pool. A singleton explicit override stays deterministic.
     *
     * @param role Spawned role.
     * @param candidates Sealed, unique eligible models.
     * @param random Injectable random source for deterministic boundary tests.
     */
    fun selectModel(role: Role, candidates: List<String>, random: Random = Random.Default): String {
        require(candidates.isNotEmpty() && candidates.distinct().size == candidates.size) {
            "A nonempty unique sealed model pool is required"
        }
        require(supportedModelIds.containsAll(candidates)) { "Model pool exceeds preset authority" }
        if (candidates.size == 1) return candidates.single()
        val weighted = modelWeights(role).filter { it.modelId in candidates }
        require(weighted.size == candidates.size) { "Model pool conflicts with the role distribution" }
        var remaining = random.nextInt(weighted.sumOf(CodexModelWeight::weight))
        for (choice in weighted) {
            if (remaining < choice.weight) return choice.modelId
            remaining -= choice.weight
        }
        error("Weighted model selection exhausted its distribution")
    }

    /**
     * Applies the common role tier, bounded by the operator's per-run effort ceiling.
     * The closed effort type excludes xhigh, max, and ultra.
     *
     * @param role Executing role.
     * @param ceiling Maximum operator-approved reasoning effort.
     */
    fun reasoningEffort(
        role: Role,
        ceiling: CodexReasoningEffort = CodexReasoningEffort.HIGH,
    ): CodexReasoningEffort {
        val requested = when {
            role.isCouncilRole || role.isGraderRole ||
                role in setOf(Role.MetaPlanner, Role.Auditor, Role.Critic) ->
                CodexReasoningEffort.HIGH
            role in setOf(Role.Archivist, Role.Educator, Role.Connector, Role.Inspector) ->
                CodexReasoningEffort.LOW
            else -> CodexReasoningEffort.MEDIUM
        }
        return if (requested.ordinal > ceiling.ordinal) ceiling else requested
    }

    companion object {
        const val LUNA = "gpt-5.6-luna"
        const val SOL = "gpt-5.6-sol"
        const val TERRA = "gpt-5.6-terra"
        const val ASTRA = "gpt-6-astra"

        /** Models confirmed in the local Codex catalog, not inferred from provider names. */
        val ALL_MODEL_IDS: Set<String> = setOf(LUNA, SOL, TERRA, ASTRA)

        /**
         * Parses an explicit preset identifier.
         *
         * @param value CLI or configuration value.
         */
        fun parse(value: String): CodexRuntimePreset = entries
            .firstOrNull { it.id == value.trim().lowercase() }
            ?: throw IllegalArgumentException("Codex preset must be full-luna or mixed-families")
    }
}
