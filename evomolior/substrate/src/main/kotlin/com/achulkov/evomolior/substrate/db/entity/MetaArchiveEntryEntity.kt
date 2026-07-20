package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a meta-archive entry — stores cross-run artifacts that
 * future Genesis Councils can learn from.
 *
 * Entry types:
 * - `WINNING_KB_SNAPSHOT` — compressed JSON snapshot of a winning group's KB
 * - `DEAD_ENDS_EXPORT`    — full Dead Ends Registry export from a run
 * - `STARTING_FIELD`      — Genesis Council output used to initialise a run
 * - `GRADING_SHEET`       — sealed evaluation rubric from a Grader civilisation
 * - `LEGACY_PROTOCOLS`    — top-fitness agent protocols extracted at run end
 */
@Entity(
    tableName = "meta_archive",
    indices = [
        Index("runId"),
        Index("entryType"),
        Index("archivedAtMs")
    ]
)
data class MetaArchiveEntryEntity(
    @PrimaryKey val id: String,

    /** Unique run identifier this archive entry belongs to. */
    val runId: String,

    /**
     * Entry type tag. One of:
     * WINNING_KB_SNAPSHOT, DEAD_ENDS_EXPORT, STARTING_FIELD, GRADING_SHEET, LEGACY_PROTOCOLS
     */
    val entryType: String,

    /**
     * Group ID that produced this entry (null for run-level entries like StartingField).
     */
    val groupId: String?,

    /**
     * Human-readable label for this archive entry.
     * E.g. "Run abc123 — Group Factory — Winner KB"
     */
    val label: String,

    /**
     * JSON-serialised content (compressed snapshot, rubric, etc.).
     * For large blobs this is chunked via [chunkIndex] / [totalChunks].
     */
    val contentJson: String,

    /**
     * Objective text of the run — enables Council agents to filter relevant archives.
     */
    val runObjective: String,

    /** Final weighted score of this group in the run (null for non-group entries). */
    val finalScore: Double?,

    /** True if this entry came from the overall run winner. */
    val isFromWinner: Boolean,

    /** Generation count at which this entry was archived. */
    val generation: Int,

    /** Chunk index for paginated large entries (0-based). */
    val chunkIndex: Int = 0,

    /** Total chunk count for this entry. 1 = not chunked. */
    val totalChunks: Int = 1,

    val archivedAtMs: Long
)
