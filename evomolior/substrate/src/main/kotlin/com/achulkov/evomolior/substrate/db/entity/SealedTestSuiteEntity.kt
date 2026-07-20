package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the sealed test suite written by the Genesis Council.
 *
 * The suite is write-once and read-only after genesis. It is stored in the first
 * competing group's substrate DB before round 0 so scoring can access a canonical copy.
 */
@Entity(
    tableName = "sealed_test_suites",
    indices = [
        Index("runId"),
        Index("groupId"),
        Index(value = ["runId", "groupId"], unique = true)
    ]
)
data class SealedTestSuiteEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val groupId: String,
    val suiteJson: String,
    val suiteHash: String,
    /** True once the suite has been sealed and persisted. */
    val isLocked: Boolean = true,
    val createdAtMs: Long
)
