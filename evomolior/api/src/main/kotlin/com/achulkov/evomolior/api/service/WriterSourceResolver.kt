package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.deliverable.ArtifactSnapshot
import com.achulkov.evomolior.core.deliverable.ArtifactRef
import com.achulkov.evomolior.core.deliverable.WriterSourceAuthority
import com.achulkov.evomolior.core.deliverable.WriterSourceAuthorityClass

/**
 * Candidate source available to the main writer before strict acceptance is resolved.
 *
 * @param groupId Group owning the candidate.
 * @param snapshot Artifact snapshot to donate.
 * @param artifactRef Primary artifact reference.
 * @param failedGateDiagnostics Public strict-gate diagnostics bound to this source.
 * @param donorArtifactRefs Additional public artifact references.
 */
data class WriterSourceCandidate(
    val groupId: String,
    val snapshot: ArtifactSnapshot,
    val artifactRef: ArtifactRef,
    val failedGateDiagnostics: List<String> = emptyList(),
    val donorArtifactRefs: List<String> = emptyList(),
    val comparativeScore: Double? = null,
) {
    init {
        require(groupId.isNotBlank()) { "Writer source group ID must not be blank" }
        require(snapshot.groupId == groupId) {
            "Writer source snapshot does not belong to its source group"
        }
        require(snapshot.hasValidHash()) { "Writer source snapshot hash is invalid" }
        require(artifactRef.artifactHash == snapshot.artifactHash) {
            "Writer source artifact hash does not match its snapshot"
        }
        require(failedGateDiagnostics.none(String::isBlank)) {
            "Writer source failed-gate diagnostics must not be blank"
        }
        require(donorArtifactRefs.none(String::isBlank)) {
            "Writer donor artifact references must not be blank"
        }
        require(comparativeScore == null || comparativeScore.isFinite()) {
            "Writer comparative score must be finite when present"
        }
    }
}

/**
 * Optional accepted writer authority supplied after strict validation.
 *
 * @param authority Fully hash-bound accepted writer authority.
 * @param candidate Candidate whose snapshot remains the source lineage.
 */
data class AcceptedWriterSource(
    val authority: WriterSourceAuthority,
    val candidate: WriterSourceCandidate,
) {
    init {
        require(authority.hasValidHash()) { "Accepted writer source authority hash is invalid" }
        require(authority.isAcceptedWriterAuthority) {
            "Accepted writer source must already satisfy the accepted authority contract"
        }
        require(authority.primaryGroupId == candidate.groupId) {
            "Accepted writer source group does not match its authority"
        }
        require(authority.sourceSnapshotIds.contains(candidate.snapshot.snapshotId)) {
            "Accepted writer source does not reference its candidate snapshot"
        }
    }
}

/**
 * Host fallback input that guarantees a writer source even when no group has a valid candidate.
 *
 * @param primaryGroupId Stable source-only group identity, when a group exists.
 * @param artifactRef Host-generated scaffold artifact.
 * @param failedGateDiagnostics Public strict-gate diagnostics.
 */
data class WriterScaffoldSource(
    val primaryGroupId: String?,
    val artifactRef: ArtifactRef,
    val failedGateDiagnostics: List<String> = emptyList(),
) {
    init {
        require(primaryGroupId == null || primaryGroupId.isNotBlank()) {
            "Scaffold source group ID must not be blank when present"
        }
        require(failedGateDiagnostics.none(String::isBlank)) {
            "Scaffold source diagnostics must not be blank"
        }
    }
}

/**
 * Selects a non-null writer source ladder independently of accepted-winner authority.
 *
 * The resolver's result is only a source commitment. It cannot create accepted publication
 * authority; the accepted writer upgrade requires later artifact, validator, benchmark, and strict
 * gate evidence.
 */
object WriterSourceResolver {
    /**
     * Resolves the strongest currently available writer source.
     *
     * @param acceptedWriter Optional already-upgraded accepted writer source.
     * @param finalComparativeLeader Candidate leading the current comparative score.
     * @param bestValidCandidates Historical valid candidates retained for compatibility.
     * @param validExecutableCandidates Structurally and execution-valid candidates.
     * @param benchmarkedCandidates Candidates with completed benchmark facts.
     * @param cumulativeComparativeLeader Best historical comparative candidate.
     * @param multiSourceCandidates Public donor candidates from several groups.
     * @param scaffold Host-generated scaffold fallback.
     * @return Always non-null writer source authority.
     */
    fun resolve(
        acceptedWriter: AcceptedWriterSource? = null,
        finalComparativeLeader: WriterSourceCandidate? = null,
        bestValidCandidates: List<WriterSourceCandidate> = emptyList(),
        validExecutableCandidates: List<WriterSourceCandidate> = emptyList(),
        benchmarkedCandidates: List<WriterSourceCandidate> = emptyList(),
        cumulativeComparativeLeader: WriterSourceCandidate? = null,
        multiSourceCandidates: List<WriterSourceCandidate> = emptyList(),
        scaffold: WriterScaffoldSource,
    ): WriterSourceAuthority {
        acceptedWriter?.let { return it.authority }

        val executable = (
            validExecutableCandidates +
                bestValidCandidates.filter { it.snapshot.executionReportHash != null }
            ).distinctBy { it.snapshot.snapshotId }
            .sortedWith(candidateComparator)
            .firstOrNull()
        executable?.let { candidate ->
            return authority(
                candidate = candidate,
                authorityClass = WriterSourceAuthorityClass.BEST_VALID_ARTIFACT,
                candidates = listOf(candidate),
            )
        }

        (
            benchmarkedCandidates +
                bestValidCandidates.filter { it.snapshot.benchmarkReportHash != null }
            ).distinctBy { it.snapshot.snapshotId }
            .sortedWith(candidateComparator)
            .firstOrNull()
            ?.let { candidate ->
                return authority(
                    candidate = candidate,
                    authorityClass = WriterSourceAuthorityClass.BENCHMARKED_ARTIFACT,
                    candidates = listOf(candidate),
                )
            }

        finalComparativeLeader?.let { candidate ->
            return authority(
                candidate = candidate,
                authorityClass = WriterSourceAuthorityClass.FINAL_COMPARATIVE_LEADER,
                candidates = listOf(candidate),
            )
        }

        cumulativeComparativeLeader?.let { candidate ->
            return authority(
                candidate = candidate,
                authorityClass = WriterSourceAuthorityClass.CUMULATIVE_COMPARATIVE_LEADER,
                candidates = listOf(candidate),
            )
        }

        multiSourceCandidates
            .sortedWith(candidateComparator)
            .takeIf { it.isNotEmpty() }
            ?.let { candidates ->
                return authority(
                    candidate = candidates.first(),
                    authorityClass = WriterSourceAuthorityClass.MULTI_SOURCE_PROVISIONAL,
                    candidates = candidates,
                )
            }

        return WriterSourceAuthority(
            primaryGroupId = scaffold.primaryGroupId,
            authorityClass = WriterSourceAuthorityClass.SCAFFOLD_ONLY,
            sourceSnapshotIds = emptyList(),
            donorArtifactRefs = listOf(scaffold.artifactRef.relativePath),
            failedGateDiagnostics = scaffold.failedGateDiagnostics.distinct().sorted(),
        ).withComputedHash()
    }

    private fun authority(
        candidate: WriterSourceCandidate,
        authorityClass: WriterSourceAuthorityClass,
        candidates: List<WriterSourceCandidate>,
    ): WriterSourceAuthority = WriterSourceAuthority(
        primaryGroupId = candidate.groupId,
        authorityClass = authorityClass,
        sourceSnapshotIds = candidates
            .map { it.snapshot.snapshotId }
            .distinct()
            .sorted(),
        donorArtifactRefs = (
            candidates.flatMap { listOf(it.artifactRef.relativePath) + it.donorArtifactRefs } +
                candidate.artifactRef.relativePath
            ).distinct().sorted(),
        failedGateDiagnostics = candidates
            .flatMap(WriterSourceCandidate::failedGateDiagnostics)
            .distinct()
            .sorted(),
    ).withComputedHash()

    private val candidateComparator = compareByDescending<WriterSourceCandidate> {
        it.snapshot.top1 ?: -1
    }.thenByDescending {
        it.snapshot.anyTwo ?: -1
    }.thenByDescending {
        if (it.snapshot.executionReportHash != null) 1 else 0
    }.thenByDescending {
        if (it.snapshot.benchmarkReportHash != null) 1 else 0
    }.thenByDescending {
        it.comparativeScore ?: Double.NEGATIVE_INFINITY
    }.thenBy {
        it.snapshot.runtimeMs ?: Long.MAX_VALUE
    }.thenBy {
        it.groupId
    }.thenBy {
        it.snapshot.snapshotId
    }
}
