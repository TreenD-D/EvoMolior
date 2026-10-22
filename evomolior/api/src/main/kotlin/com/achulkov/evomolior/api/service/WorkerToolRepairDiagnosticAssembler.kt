package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.contract.WorkerToolRepairDiagnostic
import com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1
import com.achulkov.evomolior.core.repair.ToolRepairRemediationDictionary
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.serialization.json.Json

/**
 * Temporary typed adapter from diagnostic authority into the worker projection.
 *
 * Persistence remains intentionally outside this bounded slice. The row adapters accept only a
 * serialized [ToolRepairDiagnosticV1]; legacy rejection prose is ignored and never pattern-matched.
 */
internal object WorkerToolRepairDiagnosticAssembler {
    private const val MAX_DIAGNOSTICS = 3
    private const val PAGE_SIZE = 12
    private const val MAX_SCANNED_ROWS = 96
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    suspend fun load(
        pageLoader: suspend (limit: Int, offset: Int) -> List<StagingSubmissionEntity>,
        runId: String,
        groupId: AtlasGroupId,
        round: Int,
        commitment: ToolOpportunityCommitment
    ): List<WorkerToolRepairDiagnostic> {
        requireValidAuthority(runId, round, commitment)
        val diagnostics = mutableListOf<ToolRepairDiagnosticV1>()
        var offset = 0
        while (diagnostics.size < MAX_DIAGNOSTICS && offset < MAX_SCANNED_ROWS) {
            val requestLimit = minOf(PAGE_SIZE, MAX_SCANNED_ROWS - offset)
            val page = pageLoader(requestLimit, offset).take(requestLimit)
            if (page.isEmpty()) break
            diagnostics += page.mapNotNull(::decodeTypedDiagnostic)
            offset += page.size
            if (page.size < requestLimit) break
        }
        return project(diagnostics, runId, groupId, round, commitment)
    }

    fun assemble(
        rows: List<StagingSubmissionEntity>,
        runId: String,
        groupId: AtlasGroupId,
        round: Int,
        commitment: ToolOpportunityCommitment
    ): List<WorkerToolRepairDiagnostic> {
        requireValidAuthority(runId, round, commitment)
        return project(
            diagnostics = rows.mapNotNull(::decodeTypedDiagnostic),
            runId = runId,
            groupId = groupId,
            round = round,
            commitment = commitment
        )
    }

    /** Projects exact visible typed diagnostics into their prose-free worker form. */
    fun project(
        diagnostics: List<ToolRepairDiagnosticV1>,
        runId: String,
        groupId: AtlasGroupId,
        round: Int,
        commitment: ToolOpportunityCommitment
    ): List<WorkerToolRepairDiagnostic> {
        requireValidAuthority(runId, round, commitment)
        return diagnostics.asSequence()
            .filter(ToolRepairDiagnosticV1::hasValidHash)
            .filter { diagnostic ->
                diagnostic.runId == runId &&
                    diagnostic.groupId == groupId.value &&
                    diagnostic.nodeId == commitment.nodeId.value &&
                    diagnostic.opportunityId == commitment.opportunity.opportunityId &&
                    diagnostic.opportunityHash == commitment.commitmentHash &&
                    diagnostic.visibleFromRound <= round &&
                    diagnostic.isWorkerProjectable()
            }
            .sortedWith(
                compareByDescending<ToolRepairDiagnosticV1> { it.createdRound }
                    .thenBy { it.diagnosticId }
            )
            .distinctBy(ToolRepairDiagnosticV1::diagnosticId)
            .take(MAX_DIAGNOSTICS)
            .map { diagnostic ->
                WorkerToolRepairDiagnostic(
                    diagnosticId = diagnostic.diagnosticId,
                    runId = diagnostic.runId,
                    groupId = groupId,
                    opportunityId = diagnostic.opportunityId,
                    opportunityCommitmentHash = diagnostic.opportunityHash,
                    authoringNodeId = commitment.nodeId,
                    source = diagnostic.source,
                    code = diagnostic.code,
                    createdRound = diagnostic.createdRound,
                    visibleFromRound = diagnostic.visibleFromRound,
                    affectedPublicField = diagnostic.affectedPublicField,
                    publicQuantitySpec = diagnostic.publicQuantitySpec,
                    remediationKey =
                        ToolRepairRemediationDictionary.keyFor(diagnostic.code),
                    diagnosticHash = diagnostic.diagnosticHash
                )
            }
            .toList()
    }

    private fun decodeTypedDiagnostic(
        row: StagingSubmissionEntity
    ): ToolRepairDiagnosticV1? {
        if (row.type != "TOOL" || row.status != "rejected") return null
        return row.rejectionReason?.let { encoded ->
            runCatching {
                json.decodeFromString(ToolRepairDiagnosticV1.serializer(), encoded)
            }.getOrNull()
        }
    }

    private fun requireValidAuthority(
        runId: String,
        round: Int,
        commitment: ToolOpportunityCommitment
    ) {
        require(
            runId.isNotBlank() &&
                round >= 0 &&
                commitment.runId == runId &&
                commitment.hasValidHash()
        ) { "Tool-repair diagnostics require valid sealed opportunity authority" }
    }
}
