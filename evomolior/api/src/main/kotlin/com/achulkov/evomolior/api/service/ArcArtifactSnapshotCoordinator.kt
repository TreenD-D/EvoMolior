package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.deliverable.ArcArtifactBranchLineage
import com.achulkov.evomolior.core.deliverable.ArtifactRescueRecord
import com.achulkov.evomolior.core.deliverable.ArtifactSnapshot
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.GroupArtifactHealth

/**
 * Seals one artifact-bearing round snapshot with all available runtime-owned evidence.
 *
 * The coordinator keeps artifact evidence separate from narrative/interim snapshots while
 * retaining exact report bytes, benchmark repeatability, source lineage, and rescue state.
 *
 * @param workspaceManager Persistent group artifact workspace.
 */
class ArcArtifactSnapshotCoordinator(
    private val workspaceManager: ArtifactWorkspaceManager,
) {
    /**
     * Records one exact artifact snapshot.
     *
     * @param handle Group workspace.
     * @param round Sealed round index.
     * @param artifact Host-compiled primary artifact.
     * @param structureReport Exact structural report bytes.
     * @param executionReport Exact execution report bytes, when available.
     * @param executionPassed Whether the execution report passed its stage.
     * @param benchmarkResult Exact repeated visible LOO result, when available.
     * @param branchLineage Common-backbone/divergent-branch lineage.
     * @param rescueReason Explicit public rescue reason when full candidate work is unavailable.
     * @param patchLineageIds Applied patch or work-item lineage IDs.
     * @return Persisted hash-valid artifact snapshot.
     */
    fun record(
        handle: ArtifactWorkspaceHandle,
        round: Int,
        artifact: GeneratedOutputFile,
        structureReport: String,
        executionReport: String? = null,
        executionPassed: Boolean = executionReport != null,
        benchmarkResult: ArcVisibleLooBenchmarkResult? = null,
        branchLineage: ArcArtifactBranchLineage? = null,
        rescueReason: String? = null,
        patchLineageIds: Set<String> = emptySet(),
        authorityClass: ComponentAuthorityClass = ComponentAuthorityClass.PROVISIONAL,
    ): ArtifactSnapshot {
        val rescue = rescueReason?.let { reason ->
            ArtifactRescueRecord(
                rescueId = "artifact-rescue:${handle.runId}:${handle.groupId}:$round",
                runId = handle.runId,
                groupId = handle.groupId,
                round = round,
                reason = reason,
                retainedSnapshotId = workspaceManager.bestSnapshot(handle)?.snapshotId,
                attemptedSourceHashes = listOf(workspaceManager.state(handle).baseSnapshotHash),
            ).withComputedHash()
        }
        val benchmarkReport = benchmarkResult?.reportBytes?.toString(Charsets.UTF_8)
        val health = when {
            benchmarkResult != null && benchmarkResult.report.top1Correct > 0 ->
                GroupArtifactHealth.IMPROVED
            benchmarkResult != null -> GroupArtifactHealth.BENCHMARKED
            executionReport != null && executionPassed -> GroupArtifactHealth.EXECUTABLE
            structureReport.isNotBlank() -> GroupArtifactHealth.STRUCTURALLY_VALID
            else -> GroupArtifactHealth.SCAFFOLDED
        }
        return workspaceManager.recordSnapshot(
            handle = handle,
            round = round,
            artifact = artifact,
            structureReport = structureReport,
            executionReport = executionReport,
            benchmarkReport = benchmarkReport,
            benchmarkCsv = benchmarkResult?.report?.canonicalCsvBytes()?.toString(Charsets.UTF_8),
            top1 = benchmarkResult?.report?.top1Correct,
            anyTwo = benchmarkResult?.report?.anyTwoCorrect,
            runtimeMs = null,
            sourceNodeIds = handle.profile.primaryArtifactSpec.sourceNodeIds +
                branchLineage?.let { setOf("solver-family:${it.solverFamily.familyId}") }.orEmpty(),
            patchLineageIds = patchLineageIds +
                branchLineage?.let { setOf(it.lineageHash) }.orEmpty(),
            authorityClass = authorityClass,
            artifactHealth = health,
            backboneHash = branchLineage?.backboneHash,
            branchHash = branchLineage?.branchHash,
            benchmarkRowCount = benchmarkResult?.report?.rowCount,
            benchmarkDefinitionHash = benchmarkResult?.report?.challengeDefinitionHash,
            benchmarkReportBytesHash = benchmarkResult?.reportBytesHash,
            benchmarkRepeatedBytesHash =
                benchmarkResult?.repeatedReportBytes?.let(
                    com.achulkov.evomolior.core.deliverable.ArcVisibleLooHashing::hashBytes
                ),
            benchmarkCsvBytesHash = benchmarkResult?.report?.canonicalCsvBytes()?.let(
                com.achulkov.evomolior.core.deliverable.ArcVisibleLooHashing::hashBytes
            ),
            benchmarkCandidateFamilyIds = benchmarkResult
                ?.report
                ?.candidateFamilyProvenance
                ?.map { it.familyId }
                .orEmpty()
                .distinct()
                .sorted(),
            benchmarkRepeatable = benchmarkResult?.report?.repeatability?.canonicalBytesEqual,
            rescueRecord = rescue,
        )
    }
}
