package com.achulkov.evomolior.core.contract

import kotlinx.serialization.Serializable

/**
 * Deterministic eligibility verdict for an Overseer experiment plan.
 *
 * @param allowed Whether the plan passed deterministic preflight checks.
 * @param reasons Stable, machine-readable reasons for any rejection or constraint violation.
 */
@Serializable
data class OverseerExperimentPlanEligibility(
    val allowed: Boolean,
    val reasons: List<String> = emptyList()
)
