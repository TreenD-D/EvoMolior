package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies that an exited agent's claim is settled exactly once by substantive Critic review.
 */
class CriticPipelineWipLifecycleTest {

    @Test
    fun `first promoted contribution completes retained WIP claim exactly once`() = runTest {
        val fixture = fixture("promote")
        fixture.insertClaimedTask()
        fixture.insertSubmission("doc-1")
        fixture.insertSubmission("doc-2", submittedAtMs = 2_000L)
        val completions = mutableListOf<String>()

        val summary = fixture.pipeline(
            outcome = CriticOutcome.PROMOTE,
            onCompleted = { _, nodeId, _, _ -> completions += nodeId }
        ).processPendingSubmissions()

        assertEquals(2, summary.promoted)
        assertEquals(listOf(fixture.nodeId), completions)
        val task = assertNotNull(fixture.db.wipTaskDao().getByNodeId(fixture.nodeId, fixture.runId, fixture.groupId))
        assertEquals("completed", task.status)
        assertEquals(0, task.abandonCount)
    }

    @Test
    fun `promoted deliverable reference without a WIP task does not emit completion`() = runTest {
        val fixture = fixture("deliverable-only")
        fixture.insertSubmission("doc-root", wipTaskRef = "dst:root")
        val completions = mutableListOf<String>()

        val summary = fixture.pipeline(
            outcome = CriticOutcome.PROMOTE,
            onCompleted = { _, nodeId, _, _ -> completions += nodeId }
        ).processPendingSubmissions()

        assertEquals(1, summary.promoted)
        assertEquals(emptyList(), completions)
        assertEquals(
            null,
            fixture.db.wipTaskDao().getByNodeId("dst:root", fixture.runId, fixture.groupId)
        )
    }

    @Test
    fun `last rejected contribution releases retained WIP claim`() = runTest {
        val fixture = fixture("reject")
        fixture.insertClaimedTask()
        fixture.insertSubmission("doc-1")
        fixture.insertSubmission("doc-2", submittedAtMs = 2_000L)

        val summary = fixture.pipeline(CriticOutcome.REJECT).processPendingSubmissions()

        assertEquals(2, summary.rejected)
        val task = assertNotNull(fixture.db.wipTaskDao().getByNodeId(fixture.nodeId, fixture.runId, fixture.groupId))
        assertEquals("unclaimed", task.status)
        assertEquals(null, task.claimedByAgentId)
        assertEquals(1, task.abandonCount)
    }

    @Test
    fun `canonical artifact denied tool-node authority leaves WIP retryable`() = runTest {
        val fixture = fixture("tool-node-doc")
        fixture.insertClaimedTask()
        fixture.insertSubmission("tool-shaped-doc")
        val completions = mutableListOf<String>()

        val summary = fixture.pipeline(
            outcome = CriticOutcome.PROMOTE,
            onCompleted = { _, nodeId, _, _ -> completions += nodeId },
            wipCompletionAuthorizer = { _, _ ->
                WipCompletionAuthorityDecision.denied(
                    "sealed tool-opportunity node lacks an active promoted version"
                )
            }
        ).processPendingSubmissions()

        assertEquals(1, summary.promoted)
        assertEquals(emptyList(), completions)
        val task = assertNotNull(
            fixture.db.wipTaskDao().getByNodeId(
                fixture.nodeId,
                fixture.runId,
                fixture.groupId
            )
        )
        assertEquals("unclaimed", task.status)
        assertEquals(null, task.claimedByAgentId)
        assertEquals(1, task.abandonCount)
    }

    @Test
    fun `automatic death note cannot complete a retained WIP claim`() = runTest {
        val fixture = fixture("death-note")
        fixture.insertClaimedTask()
        fixture.db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "death-note",
                agentId = fixture.agentId,
                role = "Researcher",
                type = "DEATH_NOTE",
                content = "AUTO-GENERATED terminal note.",
                wipTaskRef = fixture.nodeId,
                budgetSignature = "EMERGENCY:${fixture.agentId}",
                generation = 1,
                groupId = fixture.groupId,
                runId = fixture.runId,
                submittedAtMs = 1_000L
            )
        )
        val completions = mutableListOf<String>()

        fixture.pipeline(
            outcome = CriticOutcome.PROMOTE,
            onCompleted = { _, nodeId, _, _ -> completions += nodeId }
        ).processPendingSubmissions()

        assertEquals(emptyList(), completions)
        val task = assertNotNull(fixture.db.wipTaskDao().getByNodeId(fixture.nodeId, fixture.runId, fixture.groupId))
        assertEquals("claimed", task.status)
    }

    @Test
    fun `promoted dispatch is handed to the production consumer once`() = runTest {
        val fixture = fixture("dispatch")
        fixture.db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "dispatch-1",
                agentId = fixture.agentId,
                role = "Connector",
                type = "DISPATCH",
                content = "Share the validated finding with neighbouring groups.",
                budgetSignature = "sig:dispatch",
                generation = 1,
                groupId = fixture.groupId,
                runId = fixture.runId,
                submittedAtMs = 1_000L
            )
        )
        val delivered = mutableListOf<String>()

        val summary = CriticPipeline(
            db = fixture.db,
            runId = fixture.runId,
            groupId = fixture.groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticModel = "test-model",
            criticReviewRunner = { submission, _, _, criticId ->
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.8
                )
            },
            toolsMountHostPath = fixture.dataRoot.absolutePath,
            onSubmissionPromoted = { submission, _ -> delivered += submission.id }
        ).processPendingSubmissions()

        assertEquals(1, summary.promoted)
        assertEquals(listOf("dispatch-1"), delivered)
    }

    private fun fixture(suffix: String): Fixture {
        val dataRoot = createTempDirectory("critic-wip-$suffix-").toFile()
        val runId = "run-$suffix"
        val groupId = "group-0"
        return Fixture(
            dataRoot = dataRoot,
            db = SubstrateDatabaseFactory(dataRoot).get(runId, groupId),
            runId = runId,
            groupId = groupId
        )
    }

    private data class Fixture(
        val dataRoot: java.io.File,
        val db: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        val runId: String,
        val groupId: String,
        val nodeId: String = "node-1",
        val agentId: String = "agent-1"
    ) {
        suspend fun insertClaimedTask() {
            db.wipTaskDao().insertAll(
                listOf(
                    WipTaskEntity(
                        nodeId = nodeId,
                        runId = runId,
                        groupId = groupId,
                        description = "Produce a reviewed artifact.",
                        claimedByAgentId = agentId,
                        status = "claimed",
                        lastChangedGeneration = 1
                    )
                )
            )
        }

        suspend fun insertSubmission(
            id: String,
            submittedAtMs: Long = 1_000L,
            wipTaskRef: String = nodeId
        ) {
            db.stagingDao().insert(
                StagingSubmissionEntity(
                    id = id,
                    agentId = agentId,
                    role = "Researcher",
                    type = "DOC",
                    content = "Substantive artifact $id.",
                    wipTaskRef = wipTaskRef,
                    budgetSignature = "sig:$id",
                    generation = 1,
                    groupId = groupId,
                    runId = runId,
                    submittedAtMs = submittedAtMs
                )
            )
        }

        fun pipeline(
            outcome: CriticOutcome,
            onCompleted: suspend (String, String, String, Int) -> Unit = { _, _, _, _ -> },
            wipCompletionAuthorizer: suspend (
                StagingSubmissionEntity,
                String
            ) -> WipCompletionAuthorityDecision = { _, _ ->
                WipCompletionAuthorityDecision.AUTHORIZED
            }
        ): CriticPipeline = CriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticModel = "test-model",
            criticReviewRunner = { submission, _, _, criticId ->
                CriticReview(
                    submissionId = submission.id,
                    outcome = outcome,
                    criticAgentId = criticId,
                    initialTrustScore = if (outcome == CriticOutcome.PROMOTE) 0.8 else null,
                    rejectionReason = if (outcome == CriticOutcome.REJECT) "insufficient evidence" else null
                )
            },
            toolsMountHostPath = dataRoot.absolutePath,
            onWipTaskCompleted = onCompleted,
            wipCompletionAuthorizer = wipCompletionAuthorizer
        )
    }
}
