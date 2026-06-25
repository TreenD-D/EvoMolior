package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.config.ModelSelectionConfig
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvoKammerRunServiceObjectiveExpertRoutingTest {

    @Test
    fun `test-run fallback receives an objective expert bundle`() {
        runBlocking {
            val dataRoot = createTempDirectory("objective-expert-routing-").toFile()
            val service = EvoKammerRunService(
                lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
                groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
                graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
                substrateFactory = SubstrateDatabaseFactory(dataRoot),
                metaPlannerRunner = MetaPlannerRunner()
            )

            val routed = service.applyObjectiveExpertRouting(
                StartingField.default(
                    objective = "Implement a build pipeline for the toolchain",
                    runId = "run-objective-routing",
                    groupCount = 1,
                    groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
                )
            )

            assertEquals(ModelSelectionConfig.DEFAULT_RUNTIME_BUILDER_MODEL, routed.modelAssignment["Builder"])
            assertTrue(routed.modelPools["Builder"]!!.isNotEmpty())
            assertEquals(
                listOf(
                    ModelSelectionConfig.DEFAULT_RUNTIME_BUILDER_MODEL,
                    ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL
                ),
                routed.modelPools["Builder"]
            )
        }
    }

    @Test
    fun `council-authored expert selections remain unchanged`() {
        runBlocking {
            val dataRoot = createTempDirectory("objective-expert-routing-council-").toFile()
            val service = EvoKammerRunService(
                lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
                groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
                graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
                substrateFactory = SubstrateDatabaseFactory(dataRoot),
                metaPlannerRunner = MetaPlannerRunner()
            )

            val authored = StartingField.default(
                objective = "Implement a build pipeline for the toolchain",
                runId = "run-objective-routing-council",
                groupCount = 1,
                groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
            ).copy(
                modelAssignment = mapOf("Builder" to "custom/builder"),
                modelPools = mapOf("Builder" to listOf("custom/builder-a", "custom/builder-b"))
            )

            val routed = service.applyObjectiveExpertRouting(authored)

            assertEquals(authored.modelAssignment, routed.modelAssignment)
            assertEquals(authored.modelPools, routed.modelPools)
            assertEquals(authored.objective, routed.objective)
        }
    }

    @Test
    fun `coordination objectives receive the coordination heavy expert bundle`() {
        runBlocking {
            val dataRoot = createTempDirectory("objective-expert-routing-coordination-").toFile()
            val service = EvoKammerRunService(
                lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
                groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
                graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
                substrateFactory = SubstrateDatabaseFactory(dataRoot),
                metaPlannerRunner = MetaPlannerRunner()
            )

            val routed = service.applyObjectiveExpertRouting(
                StartingField.default(
                    objective = "Coordinate integration across multiple workstreams",
                    runId = "run-coordination-routing",
                    groupCount = 1,
                    groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
                )
            )

            assertEquals(ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL, routed.modelAssignment["MetaPlanner"])
            assertEquals(
                listOf(
                    ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
                    ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL,
                    ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL
                ),
                routed.modelPools["Connector"]
            )
        }
    }

    @Test
    fun `perspective objectives receive the perspective heavy expert bundle`() {
        runBlocking {
            val dataRoot = createTempDirectory("objective-expert-routing-perspective-").toFile()
            val service = EvoKammerRunService(
                lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
                groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
                graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
                substrateFactory = SubstrateDatabaseFactory(dataRoot),
                metaPlannerRunner = MetaPlannerRunner()
            )

            val routed = service.applyObjectiveExpertRouting(
                StartingField.default(
                    objective = "Evaluate stakeholder perspectives and blind spots",
                    runId = "run-perspective-routing",
                    groupCount = 1,
                    groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
                )
            )

            assertEquals(ModelSelectionConfig.DEFAULT_RUNTIME_PERSPECTIVE_TAKER_MODEL, routed.modelAssignment["PerspectiveTaker"])
            assertTrue(routed.modelPools["PerspectiveTaker"]!!.contains(ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL))
            assertEquals(ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL, routed.modelAssignment["Critic"])
        }
    }
}
