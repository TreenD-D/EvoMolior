package com.achulkov.evomolior.substrate

import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that plan updates persisted in staging are exposed through the plan-registry DAO.
 */
class PlanRegistryDaoTest {

    @Test
    fun `plan registry dao reads staged plan updates`() = runTest {
        val dataRoot = createTempDirectory("plan-registry-dao-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val db = factory.get("run-1", "group-0")

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "plan-1",
                agentId = "agent-meta",
                role = "MetaPlanner",
                type = "PLAN_UPDATE",
                content = "Rebalance toward builders and critics",
                budgetSignature = "system:plan-update",
                status = "promoted",
                generation = 4,
                groupId = "group-0",
                runId = "run-1",
                submittedAtMs = 1234L,
                reviewedAtMs = 1234L
            )
        )

        val latest = db.planRegistryDao().getLatest("run-1", "group-0")
        assertEquals(1, latest.size)

        val entry = latest.first()
        assertEquals("PLAN_UPDATE", entry.type)
        assertEquals("MetaPlanner", entry.role)
        assertTrue(entry.content.contains("builders"))

        val observed = db.planRegistryDao().observeLatest("run-1", "group-0")
        assertNotNull(observed)
    }
}
