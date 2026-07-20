package com.achulkov.evomolior.core.contract

import kotlinx.serialization.Serializable

/**
 * Deterministic eligibility verdict for an Overseer hypothesis.
 *
 * This lets the kernel explain why a proposal is or is not allowed before any state mutation.
 *
 * @param allowed Whether the hypothesis passed deterministic preflight checks.
 * @param reasons Stable, machine-readable reasons for any rejection or constraint violation.
 *
 */
@Serializable
data class OverseerKernelEligibility(
    val allowed: Boolean,
    val reasons: List<String> = emptyList()
)
