@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Immutable reference to one exact append-only event observed by a control role. */
@Serializable
data class ControlObservationAuthority(
    val eventId: String,
    val eventHash: String
) {
    init {
        require(eventId.isNotBlank() && eventHash.isNotBlank()) {
            "Control observation identity and hash must not be blank"
        }
    }
}

/** Current evidence-backed outcome of one control-plane intervention. */
@Serializable
enum class ControlInterventionOutcome {
    TRIGGERED_UNASSESSED,
    TRUE_POSITIVE,
    FALSE_POSITIVE,
    NO_ACTION_REQUIRED
}

/** Exact target category for a control-plane intervention. */
@Serializable
enum class ControlInterventionTargetKind {
    GROUP,
    AGENT,
    SUBMISSION,
    NODE,
    COST_RESERVATION,
    PROVIDER_ACTION
}

/**
 * Immutable control-plane intervention authority.
 *
 * Unassessed records prove an intervention occurred but receive no causal fitness. Positive and
 * false-positive outcomes require exact downstream authority before they can affect selection.
 */
@Serializable
data class ControlInterventionRecord(
    val interventionId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val controllerAgentId: String,
    val controllerRole: Role,
    val targetKind: ControlInterventionTargetKind,
    val targetId: String,
    val observations: List<ControlObservationAuthority>,
    val outcome: ControlInterventionOutcome,
    val avoidedLossAuthorities: List<ContributionAuthorityRef> = emptyList(),
    val falsePositiveAuthorities: List<ControlObservationAuthority> = emptyList(),
    val costAuthorities: List<ContributionAuthorityRef> = emptyList(),
    val occurredAt: Instant,
    val recordHash: String = ""
) {
    init {
        require(
            interventionId.isNotBlank() && runId.isNotBlank() &&
                groupId.value.isNotBlank() && controllerAgentId.isNotBlank()
        ) { "Control-intervention identity fields must not be blank" }
        require(round >= 0) { "Control-intervention round must not be negative" }
        require(controllerRole.fitnessPlane() == FitnessPlane.CONTROL) {
            "Control intervention requires a control-plane role"
        }
        require(targetId.isNotBlank()) { "Control-intervention target must not be blank" }
        require(observations.isNotEmpty() && observations.distinct() == observations) {
            "Control intervention requires unique exact observations"
        }
        require(avoidedLossAuthorities.distinct() == avoidedLossAuthorities) {
            "Avoided-loss authorities must be unique"
        }
        require(avoidedLossAuthorities.all {
            it.authorityHash != null &&
                it.kind in setOf(
                    ContributionAuthorityKind.CANONICAL_ARTIFACT,
                    ContributionAuthorityKind.STRUCTURED_DEAD_END,
                    ContributionAuthorityKind.TOOL_EXECUTION,
                    ContributionAuthorityKind.INTEGRATION_NODE,
                    ContributionAuthorityKind.NODE_CAPTURE_DECISION,
                    ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE
                )
        }) { "Avoided-loss authority must name an exact downstream contribution" }
        require(falsePositiveAuthorities.distinct() == falsePositiveAuthorities) {
            "False-positive authorities must be unique"
        }
        require(
            costAuthorities.distinct() == costAuthorities &&
                costAuthorities.all { it.kind == ContributionAuthorityKind.COST_LEDGER_ENTRY }
        ) { "Control cost authorities must be unique exact cost-ledger entries" }
        when (outcome) {
            ControlInterventionOutcome.TRUE_POSITIVE ->
                require(avoidedLossAuthorities.isNotEmpty() && falsePositiveAuthorities.isEmpty()) {
                    "True-positive intervention requires avoided-loss authority only"
                }
            ControlInterventionOutcome.FALSE_POSITIVE ->
                require(falsePositiveAuthorities.isNotEmpty() && avoidedLossAuthorities.isEmpty()) {
                    "False-positive intervention requires false-positive authority only"
                }
            ControlInterventionOutcome.TRIGGERED_UNASSESSED,
            ControlInterventionOutcome.NO_ACTION_REQUIRED ->
                require(
                    avoidedLossAuthorities.isEmpty() && falsePositiveAuthorities.isEmpty()
                ) { "Unassessed/no-action intervention cannot claim assessed outcome authority" }
        }
    }

    /** Returns this intervention with its deterministic self-hash attached. */
    fun withComputedHash(): ControlInterventionRecord =
        copy(recordHash = AtlasHashing.hashControlIntervention(this))

    /** Verifies the exact immutable control-plane commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(recordHash, AtlasHashing.hashControlIntervention(this))
}
