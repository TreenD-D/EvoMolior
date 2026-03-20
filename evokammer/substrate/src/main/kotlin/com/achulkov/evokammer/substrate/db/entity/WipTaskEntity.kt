package com.achulkov.evokammer.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Room entity for a Work-in-Progress task node.
 *
 * WiP tasks are the atomic units of work in the EvoKammer system. Agents claim tasks at
 * spawn time; Outlier agents never claim tasks. A task can be claimed and abandoned
 * multiple times before completion.
 *
 * Composite primary key: (nodeId, runId, groupId) — same node can exist in multiple groups.
 */
@Entity(
    tableName = "wip_tasks",
    primaryKeys = ["nodeId", "runId", "groupId"],
    indices = [
        Index("groupId"),
        Index("runId"),
        Index("status"),
        Index("claimedByAgentId"),
        Index(value = ["runId", "groupId", "status"])
    ]
)
data class WipTaskEntity(
    val nodeId: String,
    val runId: String,
    val groupId: String,
    val description: String,
    val claimedByAgentId: String? = null,
    /** "unclaimed" | "claimed" | "completed" | "abandoned" */
    val status: String = "unclaimed",
    /** Generation when status last changed. */
    val lastChangedGeneration: Int,
    /** Number of times this task has been claimed and abandoned (for Inspector anomaly detection). */
    val abandonCount: Int = 0,
    /** Knowledge node this task is linked to (for ResourceMapEngine integration). */
    val linkedNodeId: String? = null
)
