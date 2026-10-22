package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.CostLedgerEntry
import com.achulkov.evomolior.core.contract.CostLedgerEntryKind
import com.achulkov.evomolior.core.contract.CostSubjectType
import com.achulkov.evomolior.core.contract.MonetaryAmountStatus

/** Observational archival projection; the immutable ledger remains the only usage authority. */
internal object ProviderArchiveSummary {
    /** Summarizes finalized provider calls, retaining unknown usage and unmeasured role labels. */
    fun render(
        runId: String,
        entries: List<CostLedgerEntry>,
        rolesByGroupAndAgent: Map<Pair<String, String>, String> = emptyMap(),
    ): List<String> {
        val calls = entries.filter {
            it.runId == runId && it.subjectType == CostSubjectType.LLM_PROVIDER_CALL &&
                it.kind == CostLedgerEntryKind.FINALIZED
        }
        return listOf(
            "Provider archival summary run=$runId finalizedCalls=${calls.size} " +
                "(roles from immutable lifecycle registrations; inferred:<role> comes from " +
                "deterministic runtime agent-ID prefixes; remaining roles are UNKNOWN; " +
                "wall time is summed call duration, not run elapsed time)",
        ) + calls.groupBy {
            ProviderRole(
                role = it.groupId?.value?.let { group ->
                    it.agentId?.let { agent -> rolesByGroupAndAgent[group to agent] }
                } ?: inferredRole(it.agentId)?.let { role -> "inferred:$role" } ?: "UNKNOWN",
                provider = it.modelProviderId,
                model = it.modelId,
            )
        }
            .entries.sortedBy { it.key.toString() }.map { (identity, values) ->
                "Provider usage run=$runId " +
                    "provider=${identity.provider ?: "UNKNOWN"} model=${identity.model ?: "UNKNOWN"} " +
                    "role=${identity.role} calls=${values.size} " +
                    "agentIdentities=${values.map { it.groupId to it.agentId }.distinct().size} " +
                    "wallTimeMillis=${values.sumOf { it.wallTimeMillis ?: 0 }} " +
                    "unknownWallTimeCalls=${values.count { it.wallTimeMillis == null }} " +
                    "reportedInputTokens=${values.sumOf { it.inputTokens ?: 0 }} " +
                    "unknownInputUsageCalls=${values.count { it.inputTokens == null }} " +
                    "reportedOutputTokens=${values.sumOf { it.outputTokens ?: 0 }} " +
                    "unknownOutputUsageCalls=${values.count { it.outputTokens == null }} " +
                    "unknownProviderCostCalls=${values.count { it.providerCost.status == MonetaryAmountStatus.UNKNOWN }}"
            }
    }

    /**
     * Role implied by the runtime's deterministic agent-ID prefixes, or null when unrecognised.
     *
     * Grader, Critic, RoundJudge and Council calls are not lifecycle-registered, so 404 of 422
     * calls in the October 2026 mixed run were reported as UNKNOWN despite unambiguous IDs.
     *
     * @param agentId Ledger agent identity.
     */
    internal fun inferredRole(agentId: String?): String? {
        val id = agentId?.trim().orEmpty()
        return when {
            id.startsWith("council:") -> id.removePrefix("council:").substringBefore(':').ifBlank { null }
            id.startsWith("critic-grader-") -> "Critic/grader-criteria"
            id.startsWith("critic-review") -> "Critic/second-review"
            id.startsWith("critic-") -> "Critic"
            id.startsWith("criteria-structured-graderbuilder") -> "GraderBuilder/structured"
            id.startsWith("criteria-graderbuilder") -> "GraderBuilder"
            id.startsWith("criteria-graderresearcher") -> "GraderResearcher"
            id.startsWith("round-judge") -> "RoundJudge"
            id.startsWith("differential-privacy-transform") -> "LeakTransform"
            id.startsWith("agent-") -> id.removePrefix("agent-").substringBefore('-')
                .takeIf { it.isNotBlank() }
                ?.replaceFirstChar(Char::uppercaseChar)
            else -> null
        }
    }

    private data class ProviderRole(val role: String, val provider: String?, val model: String?)
}
