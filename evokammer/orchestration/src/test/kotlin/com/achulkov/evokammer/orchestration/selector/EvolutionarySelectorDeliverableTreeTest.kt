package com.achulkov.evokammer.orchestration.selector

import com.achulkov.evokammer.core.contract.DeliverableSkeletonTreeSummary
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvolutionarySelectorDeliverableTreeTest {

    @Test
    fun `deliverable tree gaps and low trust bias the selector toward planning`() = runTest {
        val dataRoot = createTempDirectory("selector-dst-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-dst-selector"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "plan-0",
                agentId = "agent-planner",
                role = Role.Planner.name,
                type = "PLAN_UPDATE",
                content = "fresh plan to keep the registry current",
                budgetSignature = "system:plan",
                status = "promoted",
                generation = 0,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_000L
            )
        )

        val baselineSelector = EvolutionarySelector(
            db = db,
            profile = GroupProfile.default(GroupProfileType.Library),
            runId = runId,
            groupId = groupId
        )
        val baselineAllocation = baselineSelector.computeAllocation(
            generation = 1,
            globalBudgetCeiling = 500.0,
            stasisDetected = false
        )
        assertFalse(
            baselineAllocation.entries.any { it.role == Role.Planner },
            "Without deliverable-tree pressure, generation 1 should not force a Planner"
        )

        val treeSummary = DeliverableSkeletonTreeSummary(
            runId = runId,
            groupId = groupId,
            totalNodes = 4,
            statusCounts = emptyMap(),
            openGapCount = 3,
            contestedNodeCount = 1,
            deadEndCount = 1,
            unownedNodeCount = 1,
            owningRoleCounts = mapOf(Role.Planner to 1),
            ownershipClarityScore = 0.75,
            averageTrustScore = 0.2,
            maxComplexityLevel = 4
        )

        val treeAwareSelector = EvolutionarySelector(
            db = db,
            profile = GroupProfile.default(GroupProfileType.Library),
            runId = runId,
            groupId = groupId,
            deliverableTreeSummary = treeSummary
        )
        val treeAwareAllocation = treeAwareSelector.computeAllocation(
            generation = 1,
            globalBudgetCeiling = 500.0,
            stasisDetected = false
        )

        assertTrue(
            treeAwareAllocation.entries.any { it.role == Role.Planner },
            "A low-trust deliverable tree should bias allocation toward Planner support"
        )
    }
}
