package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Inclusive integer range used in serializable execution constraints.
 *
 * @param minimum Smallest permitted value.
 * @param maximum Largest permitted value.
 */
@Serializable
data class InclusiveIntRange(val minimum: Int, val maximum: Int) {
    init {
        require(minimum >= 0) { "minimum must be non-negative" }
        require(maximum >= minimum) { "maximum must be at least minimum" }
    }

    /**
     * Returns whether a value is inside this inclusive range.
     *
     * @param value Candidate value.
     * @return Whether the value is permitted.
     */
    operator fun contains(value: Int): Boolean = value in minimum..maximum
}

/**
 * Capability and operational metadata for one available model.
 *
 * @param modelId Exact runtime backend model identifier.
 * @param providerId Structured runtime provider/backend identity.
 * @param familyId Provider-independent model family.
 * @param capabilities Declared capability labels.
 * @param structuredOutputReliability Reliability in the inclusive zero-to-one range.
 * @param toolCallReliability Reliability in the inclusive zero-to-one range.
 * @param codeBuildReliability Reliability in the inclusive zero-to-one range.
 * @param longContextSynthesisReliability Reliability in the inclusive zero-to-one range.
 * @param judgeCalibrationScore Optional grader calibration score.
 * @param observedCostPerThousandTokens Optional observed cost in operator accounting units.
 * @param evidenceSampleCount Number of observations supporting the card.
 */
@Serializable
data class ModelCapabilityCard(
    val modelId: String,
    val providerId: String = "",
    val familyId: String,
    val capabilities: Set<String> = emptySet(),
    val structuredOutputReliability: Double,
    val toolCallReliability: Double,
    val codeBuildReliability: Double,
    val longContextSynthesisReliability: Double,
    val judgeCalibrationScore: Double? = null,
    val observedCostPerThousandTokens: Double? = null,
    val evidenceSampleCount: Int = 0
)

/**
 * Credits available to each group generation.
 *
 * @param baseCredits Base credits granted per generation.
 * @param maximumCredits Maximum credits after rewards.
 * @param carryoverFraction Fraction of unused credits carried into the next generation.
 */
@Serializable
data class GenerationCreditPolicy(
    val baseCredits: Double,
    val maximumCredits: Double,
    val carryoverFraction: Double = 0.0
)

/**
 * Summary of source access available to workers.
 *
 * @param networkAccessEnabled Whether external source access exists.
 * @param allowedDomains Domain allowlist; empty means no domain-specific grant.
 * @param availableArtifactHashes Immutable input artifact commitments.
 * @param notes Additional operator constraints.
 */
@Serializable
data class SourceAccessPolicySummary(
    val networkAccessEnabled: Boolean,
    val allowedDomains: Set<String> = emptySet(),
    val availableArtifactHashes: Set<String> = emptySet(),
    val notes: List<String> = emptyList()
)

/**
 * Summary of sandbox capabilities available during the run.
 *
 * @param capabilityIds Stable capability identifiers.
 * @param outboundNetworkEnabled Whether sandboxed tools may use approved egress.
 * @param maximumToolRuntimeSeconds Hard runtime bound for one tool invocation.
 * @param maximumWorkspaceBytes Workspace storage bound.
 */
@Serializable
data class SandboxCapabilitySummary(
    val capabilityIds: Set<String> = emptySet(),
    val outboundNetworkEnabled: Boolean = false,
    val maximumToolRuntimeSeconds: Int,
    val maximumWorkspaceBytes: Long
)

/**
 * Immutable runtime boundary supplied before any Council role designs a field.
 *
 * @param requiredWorkerGroupCount Exact permitted worker-group range.
 * @param requiredGraderGroupCount Exact permitted grader-group range.
 * @param permittedProfiles Group profiles the runtime can instantiate.
 * @param availableModels Models the runtime can resolve.
 * @param maxAgentsPerGroupPerRound Agent concurrency/effort bound per group and round.
 * @param maxRounds Maximum number of execution rounds.
 * @param perGenerationCreditPolicy Generation credit policy.
 * @param cumulativeCostCap Optional total run cost cap.
 * @param enforceProviderMonetaryCostCap Whether unknown provider pricing fails the monetary cap.
 * @param allowedTopologyKinds Supported group topologies.
 * @param sourceAccessPolicy Effective source access.
 * @param sandboxCapabilities Effective sandbox capabilities.
 * @param outputSpec Final receiver-facing output contract.
 * @param atomicNodeEffortBound Maximum effort allocated to one atomic node.
 * @param councilExecutionPurpose Persisted operator purpose projected into runtime authority.
 * @param acceptanceProfile Explicit executable-topology and acceptance-obligation profile.
 * @param codexReasoningEffort Per-run Codex reasoning authority.
 * @param codexRuntimePreset Optional operator-owned model distribution and reasoning tiers.
 * @param quantitativeToolObligation Optional operator-owned typed quantitative-tool authority.
 * @param operatorOverrides Explicit operator-owned configuration.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExecutionEnvelope(
    val requiredWorkerGroupCount: InclusiveIntRange,
    val requiredGraderGroupCount: InclusiveIntRange,
    val permittedProfiles: Set<GroupProfileType>,
    val availableModels: List<ModelCapabilityCard>,
    val maxAgentsPerGroupPerRound: Int,
    val maxRounds: Int,
    val perGenerationCreditPolicy: GenerationCreditPolicy,
    val cumulativeCostCap: Double? = null,
    val enforceProviderMonetaryCostCap: Boolean = true,
    val allowedTopologyKinds: Set<GroupTopologyKind>,
    val sourceAccessPolicy: SourceAccessPolicySummary,
    val sandboxCapabilities: SandboxCapabilitySummary,
    val outputSpec: FinalOutputSpec,
    val atomicNodeEffortBound: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val councilExecutionPurpose: CouncilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val codexReasoningEffort: com.achulkov.evomolior.core.config.CodexReasoningEffort =
        com.achulkov.evomolior.core.config.CodexReasoningEffort.HIGH,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val quantitativeToolObligation: QuantitativeToolObligation? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val codexRuntimePreset: com.achulkov.evomolior.core.config.CodexRuntimePreset? = null,
    val operatorOverrides: Map<String, String> = emptyMap()
) {
    init {
        require(
            quantitativeToolObligation == null ||
                acceptanceProfile != AcceptanceProfile.RUNTIME_USABILITY_SMOKE
        ) {
            "Runtime-usability-smoke forbids tool opportunities and cannot carry a " +
                "quantitative-tool obligation"
        }
    }
}

/**
 * Upper bound on ordinary workers one group can actually fund per round.
 *
 * Mirrors the runtime allocator: each worker is granted at least the cheapest permitted profile's
 * spawn budget, and a per-round credit share below two such grants funds a single worker. The
 * nominal [ExecutionEnvelope.maxAgentsPerGroupPerRound] overstated capacity 3x in the October 2026
 * mixed run (300 credits over 2 groups x 4 rounds funded one Builder per group-round), so
 * feasibility planning must use this bound instead. It never exceeds the nominal slot count.
 */
val ExecutionEnvelope.creditAffordableWorkerSlots: Int
    get() {
        val nominal = maxAgentsPerGroupPerRound
        if (nominal <= 1) return nominal.coerceAtLeast(0)
        val cheapestGrant = permittedProfiles
            .minOfOrNull { GroupProfile.default(it).spawnBudget }
            ?.takeIf { it > 0.0 }
            ?: return nominal
        val groups = requiredWorkerGroupCount.maximum.coerceAtLeast(1)
        val perGroupRoundCredits = cumulativeCostCap
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.div(groups)
            ?.div(maxRounds.coerceAtLeast(1))
            ?: return nominal
        if (perGroupRoundCredits < 2 * cheapestGrant) return 1
        return minOf(nominal, (perGroupRoundCredits / cheapestGrant).toInt()).coerceAtLeast(1)
    }

/** Monetary provider cap enforced by runtime cost authority, or null in explicit iso-latency mode. */
val ExecutionEnvelope.providerCostCapUsd: Double?
    get() = cumulativeCostCap.takeIf { enforceProviderMonetaryCostCap }
