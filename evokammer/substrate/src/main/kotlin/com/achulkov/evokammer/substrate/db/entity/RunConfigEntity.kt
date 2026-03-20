package com.achulkov.evokammer.substrate.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for run configuration and state.
 *
 * Stored in a global (not per-group) database so the desktop control panel can list all runs.
 */
@Entity(tableName = "run_configs")
data class RunConfigEntity(
    @PrimaryKey val id: String,
    val objective: String,
    val globalBudgetCeiling: Double,
    val generationCeiling: Int,
    val groupCount: Int,
    /** JSON: Map<groupId, profileType> */
    val groupProfilesJson: String,
    /** JSON: List<String> (ring topology order) */
    val ringTopologyJson: String,
    /** JSON: List<Int> (generation milestones) */
    val checkinMilestonesJson: String,
    val leakIntervalGenerations: Int,
    val dispatchLagGenerations: Int,
    val graderGroupCount: Int,
    /** RunStatus enum name: "Pending" | "Running" | "Paused" | "Completed" | "Terminated" | "Failed" */
    val status: String = "Pending",
    val createdAtMs: Long,
    val startedAtMs: Long? = null,
    val completedAtMs: Long? = null,
    /** The full StartingField JSON, stored for audit and re-run purposes. */
    val startingFieldJson: String? = null,
    /** Current generation (updated by LifecycleManager at each generation boundary). */
    val currentGeneration: Int = 0,
    /** Total spend across all groups so far. */
    val totalSpent: Double = 0.0,
    /** Per-group disclosure stage: JSON Map<groupId, Int>. */
    val groupStagesJson: String = "{}"
)
