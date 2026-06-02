package com.achulkov.evomolior.evokammer.substrate

import com.achulkov.evokammer.substrate.db.entity.DeadEndsEntity
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.KGSchemaEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evokammer.substrate.db.entity.LegacyProtocolEntity
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evokammer.substrate.db.entity.TrustScoreEntity
import com.achulkov.evokammer.substrate.db.entity.WipTaskEntity

/**
 * Metadata about a discovered run on disk.
 *
 * @param runId The run identifier.
 * @param groupIds The list of group IDs that have substrate DB files for this run.
 * @param lastModifiedMs The last-modified timestamp of the most recently touched DB file, in ms.
 * @param isCompleted True when the run has a finished results bundle on disk.
 */
data class RunInfo(
    val runId: String,
    val groupIds: List<String>,
    val lastModifiedMs: Long,
    val isCompleted: Boolean
)

/**
 * A snapshot of all substrate layers for a single (runId, groupId) pair.
 *
 * All lists are empty if not yet loaded. [loaded] is false until [SubstrateRepository.loadSnapshot]
 * completes at least once.
 */
data class SubstrateSnapshot(
    val runId: String,
    val groupId: String,
    val loaded: Boolean = false,

    // ── Core knowledge layers ──────────────────────────────────────────────
    val kbEntries: List<KnowledgeBaseEntryEntity> = emptyList(),
    val kgFacts: List<KnowledgeGraphFactEntity> = emptyList(),
    val tools: List<ToolRegistryEntryEntity> = emptyList(),

    // ── Pipeline layers ────────────────────────────────────────────────────
    val stagingSubmissions: List<StagingSubmissionEntity> = emptyList(),
    val wipTasks: List<WipTaskEntity> = emptyList(),

    // ── Audit / memory layers ──────────────────────────────────────────────
    val eventLogEntries: List<EventLogEntryEntity> = emptyList(),
    val deadEnds: List<DeadEndsEntity> = emptyList(),
    val legacyProtocols: List<LegacyProtocolEntity> = emptyList(),
    val trustScores: List<TrustScoreEntity> = emptyList(),

    // ── Schema layer ───────────────────────────────────────────────────────
    /** Current KG schema (latest version) for this group, or null if none yet. */
    val kgSchema: KGSchemaEntity? = null
) {
    /** Total number of records across all layers. Used for UI badges. */
    val totalRecords: Int get() =
        kbEntries.size + kgFacts.size + tools.size + stagingSubmissions.size +
        wipTasks.size + eventLogEntries.size + deadEnds.size + legacyProtocols.size +
        trustScores.size + (if (kgSchema != null) 1 else 0)
}
