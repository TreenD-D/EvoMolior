package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.LegacyProtocolEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the legacy-protocol expiry sweep.
 */
class EvoKammerRunServiceLegacyExpiryTest {

    @Test
    fun `sweepExpiredLegacyProtocols promotes only expired failure maps into dead ends`() = runTest {
        val dataRoot = createTempDirectory("svc-legacy-expiry-test-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-legacy-expiry"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)

        db.legacyProtocolDao().insert(
            LegacyProtocolEntity(
                id = "expired-protocol",
                role = "Researcher",
                extractedFromGeneration = 1,
                expiresAtGeneration = 3,
                reasoningPosture = "posture",
                toolHeuristicsJson = """{"read_kb":4}""",
                failureMapJson = """{"tool_x":{"failures":2,"note":"retry with smaller batches"}}""",
                kgHabitsJson = """{"role":"Researcher","dominantRelations":[{"relation":"CAUSES","count":2}]}""",
                sourceAgentIds = "agent-a",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )
        db.legacyProtocolDao().insert(
            LegacyProtocolEntity(
                id = "active-protocol",
                role = "Builder",
                extractedFromGeneration = 2,
                expiresAtGeneration = 6,
                reasoningPosture = "posture",
                toolHeuristicsJson = """{"write_staging":3}""",
                failureMapJson = """{"tool_y":{"failures":1,"note":"avoid"}}""",
                kgHabitsJson = """{"role":"Builder","dominantRelations":[{"relation":"IMPLEMENTS","count":1}]}""",
                sourceAgentIds = "agent-b",
                groupId = groupId,
                runId = runId,
                createdAtMs = 2_000L
            )
        )

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in the legacy expiry test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner()
        )

        val result = service.sweepExpiredLegacyProtocols(db, runId, groupId, round = 3)

        assertEquals(1, result.expiredProtocols)
        assertEquals(1, result.deadEndsInserted)

        val deadEnds = db.knowledgeBaseDao().getDeadEnds(runId, groupId)
        assertEquals(1, deadEnds.size)
        val deadEnd = deadEnds.single()
        assertTrue(deadEnd.isDeadEnd)
        assertEquals("dead-end-expired-protocol", deadEnd.id)
        assertEquals("dead_ends", deadEnd.domain)
        assertEquals(3, deadEnd.generation)
        assertTrue(deadEnd.content.contains("Researcher"))
        assertTrue(deadEnd.content.contains("tool_x"))

        val stored = db.knowledgeBaseDao().getById(deadEnd.id)
        assertNotNull(stored)
        assertEquals(deadEnd.id, stored.id)
    }
}
