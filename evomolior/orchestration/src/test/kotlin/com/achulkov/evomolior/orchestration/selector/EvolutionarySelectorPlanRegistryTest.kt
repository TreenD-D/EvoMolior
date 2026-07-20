package com.achulkov.evomolior.orchestration.selector

import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the selector consumes the plan registry freshness signal.
 */
class EvolutionarySelectorPlanRegistryTest {

    @Test
    fun `stale plan updates bias the selector toward a Planner allocation`() = runTest {
        val dataRoot = createTempDirectory("selector-plan-registry-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-plan-registry"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "plan-1",
                agentId = "agent-meta",
                role = Role.MetaPlanner.name,
                type = "PLAN_UPDATE",
                content = "rebalance toward planning",
                budgetSignature = "system:plan",
                status = "promoted",
                generation = 1,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_000L
            )
        )

        val selector = EvolutionarySelector(
            db = db,
            profile = GroupProfile.default(GroupProfileType.Library),
            runId = runId,
            groupId = groupId
        )

        val allocation = selector.computeAllocation(
            generation = 4,
            globalBudgetCeiling = 500.0,
            stasisDetected = false
        )

        assertTrue(
            allocation.entries.any { it.role == Role.Planner },
            "A stale plan registry should bias allocation toward a Planner role"
        )
    }
}
