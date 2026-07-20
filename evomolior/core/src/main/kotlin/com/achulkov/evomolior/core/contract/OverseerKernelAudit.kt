package com.achulkov.evomolior.core.contract

import kotlinx.serialization.Serializable

/**
 * Action types recorded in the deterministic Overseer audit trail.
 */
@Serializable
enum class OverseerKernelAuditActionType {
    FORMALIZE_HYPOTHESIS,
    REGISTER_EXPERIMENT_PLAN,
    SCHEDULE_EXPERIMENT_PLANS,
    LAUNCH_EXPERIMENT_RUN,
    RECORD_EXPERIMENT_RESULT,
    RECORD_DEAD_END,
    COMPACT_KERNEL_STATE
}

/**
 * Append-only audit entry for recursive self-improvement kernel mutations.
 *
 * @param entryId Stable audit identifier.
 * @param timestampMs Epoch milliseconds when the action was recorded.
 * @param actionType Type of kernel action that produced the entry.
 * @param hypothesisId Hypothesis identifier, if applicable.
 * @param experimentPlanId Experiment plan identifier, if applicable.
 * @param deadEndHypothesisId Dead-end hypothesis identifier, if applicable.
 * @param hypothesisCount Total hypothesis count after the action.
 * @param experimentPlanCount Total experiment-plan count after the action.
 * @param deadEndCount Total dead-end count after the action.
 * @param note Optional human-readable note.
 */
@Serializable
data class OverseerKernelAuditEntry(
    val entryId: String,
    val timestampMs: Long,
    val actionType: OverseerKernelAuditActionType,
    val hypothesisId: String? = null,
    val experimentPlanId: String? = null,
    val deadEndHypothesisId: String? = null,
    val hypothesisCount: Int,
    val experimentPlanCount: Int,
    val deadEndCount: Int,
    val note: String? = null
)
