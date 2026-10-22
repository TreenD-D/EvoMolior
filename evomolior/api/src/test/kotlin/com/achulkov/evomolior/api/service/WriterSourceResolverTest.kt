package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.deliverable.ArtifactRef
import com.achulkov.evomolior.core.deliverable.ArtifactSnapshot
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.WriterSourceAuthorityClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WriterSourceResolverTest {
    @Test
    fun `comparative leader produces a non-null source without accepted authority`() {
        val candidate = candidate("group-b", snapshot("snapshot-b", "b".repeat(64), 8, 10))
        val scaffold = scaffold()

        val authority = WriterSourceResolver.resolve(
            finalComparativeLeader = candidate,
            scaffold = scaffold,
        )

        assertEquals(
            WriterSourceAuthorityClass.FINAL_COMPARATIVE_LEADER,
            authority.authorityClass,
        )
        assertEquals("group-b", authority.primaryGroupId)
        assertEquals(listOf("snapshot-b"), authority.sourceSnapshotIds)
        assertTrue(authority.hasValidHash())
        assertNotNull(authority.donorArtifactRefs.single())
    }

    @Test
    fun `best valid and scaffold fallbacks are deterministic and non-null`() {
        val best = candidate("group-z", snapshot("snapshot-z", "b".repeat(64), 3, 4))
        val weaker = candidate("group-a", snapshot("snapshot-a", "a".repeat(64), 3, 3))
        val bestAuthority = WriterSourceResolver.resolve(
            bestValidCandidates = listOf(weaker, best),
            scaffold = scaffold(),
        )
        assertEquals(WriterSourceAuthorityClass.BEST_VALID_ARTIFACT, bestAuthority.authorityClass)
        assertEquals("group-z", bestAuthority.primaryGroupId)

        val scaffoldAuthority = WriterSourceResolver.resolve(scaffold = scaffold())
        assertEquals(WriterSourceAuthorityClass.SCAFFOLD_ONLY, scaffoldAuthority.authorityClass)
        assertEquals("group-a", scaffoldAuthority.primaryGroupId)
        assertTrue(scaffoldAuthority.sourceSnapshotIds.isEmpty())
        assertTrue(scaffoldAuthority.hasValidHash())
    }

    @Test
    fun `benchmarked artifact outranks a higher comparative leader`() {
        val benchmarked = candidate(
            groupId = "group-b",
            snapshot = snapshot("snapshot-b", "b".repeat(64), top1 = 4, anyTwo = 6),
        ).copy(comparativeScore = 0.10)
        val comparative = candidate(
            groupId = "group-a",
            snapshot = snapshot("snapshot-a", "a".repeat(64), top1 = 0, anyTwo = 0)
                .copy(executionReportHash = null, benchmarkReportHash = null)
                .withComputedHash(),
        ).copy(comparativeScore = 0.99)

        val authority = WriterSourceResolver.resolve(
            finalComparativeLeader = comparative,
            benchmarkedCandidates = listOf(benchmarked),
            scaffold = scaffold(),
        )

        assertEquals(WriterSourceAuthorityClass.BENCHMARKED_ARTIFACT, authority.authorityClass)
        assertEquals("group-b", authority.primaryGroupId)
        assertEquals(listOf("snapshot-b"), authority.sourceSnapshotIds)
    }

    private fun candidate(
        groupId: String,
        snapshot: ArtifactSnapshot,
    ): WriterSourceCandidate = WriterSourceCandidate(
        groupId = groupId,
        snapshot = snapshot,
        artifactRef = ArtifactRef(
            artifactId = "notebook-$groupId",
            relativePath = "primary/submission.ipynb",
            mediaType = "application/x-ipynb+json",
            artifactHash = snapshot.artifactHash,
        ),
        failedGateDiagnostics = listOf("strict-benchmark"),
    )

    private fun scaffold() = WriterScaffoldSource(
        primaryGroupId = "group-a",
        artifactRef = ArtifactRef(
            artifactId = "arc-scaffold",
            relativePath = "primary/submission.ipynb",
            mediaType = "application/x-ipynb+json",
            artifactHash = "c".repeat(64),
        ),
        failedGateDiagnostics = listOf("strict-gates"),
    )

    private fun snapshot(
        id: String,
        artifactHash: String,
        top1: Int,
        anyTwo: Int,
    ): ArtifactSnapshot = ArtifactSnapshot(
        snapshotId = id,
        runId = "run-1",
        groupId = id.substringAfterLast('-').let { "group-$it" },
        round = 1,
        artifactHash = artifactHash,
        sourceTreeHash = "d".repeat(64),
        structureReportHash = "e".repeat(64),
        executionReportHash = "f".repeat(64),
        benchmarkReportHash = "a".repeat(64),
        top1 = top1,
        anyTwo = anyTwo,
        runtimeMs = 10,
        authorityClass = ComponentAuthorityClass.PROVISIONAL,
        sourceNodeIds = setOf("notebook-scaffold"),
        patchLineageIds = emptySet(),
    ).withComputedHash()
}
