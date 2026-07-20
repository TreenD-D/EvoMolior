package com.achulkov.evomolior.orchestration.nested

import kotlinx.serialization.Serializable

/**
 * The result of a completed nested group instance.
 *
 * Used by the outer group to roll up inner results into its own substrate.
 *
 * @param nestedGroupId The nested group's ID.
 * @param outerGroupId The outer group that spawned this instance.
 * @param generationsCompleted Number of generations that ran.
 * @param kbDocumentIds IDs of promoted KB documents (for import into outer substrate).
 * @param kgFactIds IDs of promoted KG facts (for import into outer substrate).
 * @param promotedToolIds IDs of validated tools (for import into outer tool registry).
 * @param budgetConsumed Budget units actually consumed by the inner instance.
 * @param completedNormally Whether the run finished normally vs. budget-killed/error.
 * @param summaryJson JSON summary of the inner run's outputs (for outer Planner).
 */
@Serializable
data class NestedGroupResult(
    val nestedGroupId: String,
    val outerGroupId: String,
    val generationsCompleted: Int,
    val kbDocumentIds: List<String>,
    val kgFactIds: List<String>,
    val promotedToolIds: List<String>,
    val budgetConsumed: Double,
    val completedNormally: Boolean,
    val summaryJson: String = "{}",
    val importedKbDocumentIds: List<String> = emptyList(),
    val importedKgFactIds: List<String> = emptyList(),
    val importedToolIds: List<String> = emptyList()
)
