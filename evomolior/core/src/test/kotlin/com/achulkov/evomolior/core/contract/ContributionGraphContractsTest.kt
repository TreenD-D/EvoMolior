@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ContributionGraphContractsTest {
    @Test
    fun `edge hash commits exact typed provenance`() {
        val edge = edge().withComputedHash()

        assertTrue(edge.hasValidHash())
        assertFalse(edge.copy(round = 2).hasValidHash())
        assertFalse(
            edge.copy(
                target = edge.target.copy(authorityHash = "different-staging-hash")
            ).hasValidHash()
        )
    }

    @Test
    fun `relation rejects authority kinds outside its closed endpoint contract`() {
        assertFailsWith<IllegalArgumentException> {
            edge().copy(
                source = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.AGENT,
                    authorityId = "agent-1"
                )
            )
        }
    }

    @Test
    fun `authority reference rejects blank identity and hash`() {
        assertFailsWith<IllegalArgumentException> {
            ContributionAuthorityRef(
                kind = ContributionAuthorityKind.CONTRIBUTION_COMMIT,
                authorityId = ""
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ContributionAuthorityRef(
                kind = ContributionAuthorityKind.CONTRIBUTION_COMMIT,
                authorityId = "commit-1",
                authorityHash = ""
            )
        }
    }

    @Test
    fun `final critic decision requires result authority only for promotion`() {
        val promoted = CriticDecisionRecord(
            decisionId = "decision-1",
            runId = "run-1",
            groupId = AtlasGroupId("group-1"),
            round = 1,
            submissionId = "submission-1",
            submissionContentHash = "submission-hash",
            resolvedReview = CriticReview(
                submissionId = "submission-1",
                outcome = CriticOutcome.PROMOTE,
                criticAgentId = "critic-1",
                initialTrustScore = 0.8
            ),
            resultAuthority = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                authorityId = "kb:artifact-1",
                authorityHash = "artifact-hash"
            ),
            decidedAt = Instant.fromEpochMilliseconds(2_000)
        ).withComputedHash()

        assertTrue(promoted.hasValidHash())
        assertFailsWith<IllegalArgumentException> {
            promoted.copy(resultAuthority = null)
        }
        assertFailsWith<IllegalArgumentException> {
            promoted.copy(
                resolvedReview = promoted.resolvedReview.copy(outcome = CriticOutcome.REJECT)
            )
        }
    }

    @Test
    fun `downstream use hash commits canonical source and exact context`() {
        val record = DownstreamUseRecord(
            useId = "read-1",
            runId = "run-1",
            groupId = AtlasGroupId("group-1"),
            round = 2,
            agentId = "agent-2",
            nodeId = "node-2",
            kind = DownstreamUseKind.READ,
            sourceAuthority = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                authorityId = "kb:artifact-1",
                authorityHash = "artifact-hash"
            ),
            contextHash = "read-context-hash",
            occurredAt = Instant.fromEpochMilliseconds(3_000)
        ).withComputedHash()

        assertTrue(record.hasValidHash())
        assertFalse(record.copy(contextHash = "different-context").hasValidHash())
        assertFailsWith<IllegalArgumentException> {
            record.copy(
                sourceAuthority = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.CONTRIBUTION_COMMIT,
                    authorityId = "commit-1",
                    authorityHash = "commit-hash"
                )
            )
        }
    }

    @Test
    fun `final output and score authorities commit exact lineage`() {
        val finalReference = FinalDeliverableReferenceRecord(
            referenceId = "final-ref-1",
            runId = "run-1",
            groupId = AtlasGroupId("group-1"),
            round = 3,
            relativePath = "answer.html",
            mediaType = "text/html",
            contentHash = "output-hash",
            writerAgentId = "writer-1",
            writerSubmissionId = "submission-final",
            nodeId = "node-integration",
            sourceAuthority = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.INTEGRATION_NODE,
                authorityId = "integration-1",
                authorityHash = "integration-hash"
            ),
            referencedAt = Instant.fromEpochMilliseconds(4_000),
            componentId = "answer"
        ).withComputedHash()
        val receiver = ReceiverScoreContributionRecord(
            contributionId = "receiver-score-1",
            runId = "run-1",
            groupId = AtlasGroupId("group-1"),
            round = 3,
            finalReference = ContributionAuthorityRef(
                ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE,
                finalReference.referenceId,
                finalReference.recordHash
            ),
            scoringContractId = "receiver-contract",
            scoringContractVersion = 1,
            scoringContractHash = "receiver-contract-hash",
            criterionId = "actionability",
            scoreContribution = 8.0,
            maximumContribution = 10.0,
            scoredAt = Instant.fromEpochMilliseconds(5_000)
        ).withComputedHash()
        val benchmark = BenchmarkScoreContributionRecord(
            contributionId = "benchmark-score-1",
            runId = "run-1",
            groupId = AtlasGroupId("group-1"),
            round = 3,
            finalReference = receiver.finalReference,
            benchmarkId = "benchmark-1",
            benchmarkVersion = 1,
            benchmarkContractHash = "benchmark-contract-hash",
            componentId = "exactness",
            scoreContribution = 1.0,
            maximumContribution = 1.0,
            scoredAt = Instant.fromEpochMilliseconds(6_000)
        ).withComputedHash()

        assertTrue(finalReference.hasValidHash())
        assertTrue(receiver.hasValidHash())
        assertTrue(benchmark.hasValidHash())
        assertFalse(finalReference.copy(contentHash = "tampered").hasValidHash())
        assertFalse(finalReference.copy(componentId = "evidence").hasValidHash())
        assertFalse(receiver.copy(scoreContribution = 7.0).hasValidHash())
        assertFalse(benchmark.copy(componentId = "other").hasValidHash())
    }

    private fun edge(): ContributionEdge = ContributionEdge(
        edgeId = "edge-1",
        runId = "run-1",
        groupId = AtlasGroupId("group-1"),
        round = 1,
        plane = ContributionPlane.WORKER,
        relation = ContributionRelation.STAGED_AS,
        source = ContributionAuthorityRef(
            kind = ContributionAuthorityKind.CONTRIBUTION_COMMIT,
            authorityId = "commit-1",
            authorityHash = "commit-hash"
        ),
        target = ContributionAuthorityRef(
            kind = ContributionAuthorityKind.STAGING_SUBMISSION,
            authorityId = "submission-1",
            authorityHash = "submission-hash"
        ),
        occurredAt = Instant.fromEpochMilliseconds(1_000)
    )
}
