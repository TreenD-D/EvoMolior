package com.achulkov.evomolior.api.acceptance

import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.dispatch.DispatchMessage
import com.achulkov.evomolior.core.contract.RoleAllocation
import com.achulkov.evomolior.core.contract.RoleAllocationEntry
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase4MultiGroupCompetitionAcceptanceTest {

    @Test
    fun `three isolated groups complete five generations and receive a lagged formal dispatch`() = runBlocking {
        val dataRoot = createTempDirectory("phase-4-multi-group-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-phase-4-acceptance"
        val groupIds = listOf("group-0", "group-1", "group-2")
        val profile = GroupProfile.default(GroupProfileType.Library)
        val dispatchDeliveries = mutableListOf<DispatchDelivery>()
        val coordinator = GroupCoordinator(
            groupIds = groupIds,
            dimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Reward evidence-backed output.",
                    weight = 1.0
                )
            ),
            dispatchLagGenerations = 2
        )
        coordinator.configureDeliveryConsumer { sourceGroupId, targetGroupId, message, trustScore ->
            if (message is DispatchMessage.Text) {
                val targetDb = substrateFactory.get(runId, targetGroupId)
                targetDb.knowledgeBaseDao().insert(
                    KnowledgeBaseEntryEntity(
                        id = "dispatch-${UUID.randomUUID()}",
                        content = message.content,
                        sourceAgentId = "dispatch:$sourceGroupId",
                        generation = 2,
                        trustScore = trustScore,
                        domain = "dispatch_inbox",
                        status = "active",
                        groupId = targetGroupId,
                        runId = runId,
                        createdAtMs = System.currentTimeMillis()
                    )
                )
                dispatchDeliveries += DispatchDelivery(sourceGroupId, targetGroupId, 2)
            }
        }

        val managers = groupIds.associateWith { groupId ->
            val db = substrateFactory.get(runId, groupId)
            db.knowledgeBaseDao().insert(
                KnowledgeBaseEntryEntity(
                    id = "private-$groupId",
                    content = "PRIVATE_CANON_MARKER_$groupId",
                    sourceAgentId = "seed-$groupId",
                    generation = 0,
                    trustScore = 1.0,
                    domain = "private_canon",
                    status = "active",
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = 1_000L
                )
            )
            LifecycleManager(
                runId = runId,
                groupId = groupId,
                shuruVmManager = ShuruVmManager(),
                substrateDatabaseFactory = substrateFactory,
                agentJarPath = "/unused/in-process-agent.jar",
                openRouterApiKey = "sk-test",
                groupProfile = profile,
                localAgentRunner = { _, _ -> },
                budgetPollingIntervalMs = 10L
            )
        }

        for (generation in 0 until 5) {
            coordinator.onRoundStart(generation)
            managers.forEach { (groupId, manager) ->
                manager.spawnGeneration(
                    allocation = RoleAllocation(
                        runId = runId,
                        groupId = groupId,
                        generation = generation,
                        entries = listOf(RoleAllocationEntry(Role.Researcher, 1, 20.0)),
                        totalBudgetCeiling = 20.0,
                        rationale = "Phase 4 generation $generation"
                    ),
                    stageDisclosure = "Complete isolated generation $generation.",
                    modelAssignments = emptyMap(),
                    rolePrompts = emptyMap(),
                    scope = this
                )
            }
            withTimeout(5_000L) {
                while (managers.values.any { it.getLiveAgentCount() != 0 }) {
                    kotlinx.coroutines.yield()
                }
            }

            if (generation == 0) {
                coordinator.dispatchLeak(
                    sourceGroupId = "group-0",
                    message = DispatchMessage.Text(
                        content = "FORMAL_DISPATCH_MARKER: share only this validated coordination summary.",
                        metadata = mapOf("kind" to "formal")
                    )
                )
            }
            val released = coordinator.flushPendingDispatches(generation)
            if (generation < 2) assertEquals(0, released)
            if (generation == 2) assertEquals(1, released)
        }

        assertTrue(dispatchDeliveries.isNotEmpty())
        assertTrue(dispatchDeliveries.all { it.sourceGroupId == "group-0" && it.deliveredGeneration == 2 })
        assertFalse(dispatchDeliveries.any { it.targetGroupId == "group-0" })

        groupIds.forEach { groupId ->
            val db = substrateFactory.get(runId, groupId)
            val privateCanon = db.knowledgeBaseDao().queryByDomain(runId, groupId, "private_canon", 10)
            assertEquals(listOf("PRIVATE_CANON_MARKER_$groupId"), privateCanon.map { it.content })
            assertTrue(
                privateCanon.none { entry ->
                    groupIds.filterNot { it == groupId }.any { other -> entry.content.contains(other) }
                }
            )
            assertEquals(
                5,
                db.eventLogDao().getLatestByType(runId, groupId, EventTypes.AGENT_SPAWN, 10).size
            )
            assertEquals(
                5,
                db.eventLogDao().getLatestByType(runId, groupId, EventTypes.AGENT_DEATH, 10).size
            )
            assertEquals(4, db.eventLogDao().getMaxGeneration(runId, groupId))
        }

        assertTrue(
            substrateFactory.get(runId, "group-1").knowledgeBaseDao()
                .queryByDomain(runId, "group-1", "dispatch_inbox", 10)
                .single()
                .content
                .contains("FORMAL_DISPATCH_MARKER")
        )
        managers.values.forEach { it.terminateAll() }
    }

    private data class DispatchDelivery(
        val sourceGroupId: String,
        val targetGroupId: String,
        val deliveredGeneration: Int
    )
}
