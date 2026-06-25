package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.contract.RunGraphEdge
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.contract.SeedKBDocument
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the council StartingField is sealed into the run meta-archive before spawning.
 */
class EvoKammerRunServiceStartingFieldSealTest {

    @Test
    fun `persistSealedStartingField stores and verifies the council output`() = runTest {
        val dataRoot = createTempDirectory("svc-starting-field-seal-test-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this seal test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val startingField = StartingField.default("seal stage content", runId = "run-seal")
            .copy(
                stageContents = listOf("stage-a", "stage-b"),
                modelPools = mapOf(
                    "Researcher" to listOf("deepseek/deepseek-v4-flash", "deepseek/deepseek-v3.2")
                )
            )

        val sealFn = service::class.declaredFunctions.first { it.name == "persistSealedStartingField" }.apply {
            isAccessible = true
        }
        sealFn.callSuspend(service, "run-seal", startingField)

        val archiveDb = factory.get("run-seal", "__meta_archive__")
        val sealedEntries = archiveDb.metaArchiveDao().getByType("STARTING_FIELD")
            .filter { it.runId == "run-seal" }

        assertEquals(1, sealedEntries.size)
        val stored = sealedEntries.single()
        assertTrue(stored.contentJson.contains("stage-a"))
        assertTrue(stored.contentJson.contains("stage-b"))

        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(StartingField.serializer(), stored.contentJson)
        assertEquals(startingField.stageContents, decoded.stageContents)
        assertEquals(startingField.modelPools, decoded.modelPools)
        assertEquals(startingField.objective, decoded.objective)
    }

    @Test
    fun `persistSealedStartingField preserves populated run graph edges`() = runTest {
        val dataRoot = createTempDirectory("svc-starting-field-graph-seal-test-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this graph seal test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val startingField = StartingField.default("seal graph content", runId = "run-graph-seal")
            .copy(
                runGraphEdges = listOf(
                    RunGraphEdge(
                        sourceRunId = "ancestor-run",
                        targetRunId = "run-graph-seal",
                        edgeType = "continuation",
                        weight = 0.75
                    )
                ),
                routedContextSummaries = listOf("high-level-run-log: ancestor headline")
            )

        val sealFn = service::class.declaredFunctions.first { it.name == "persistSealedStartingField" }.apply {
            isAccessible = true
        }
        sealFn.callSuspend(service, "run-graph-seal", startingField)

        val archiveDb = factory.get("run-graph-seal", "__meta_archive__")
        val stored = archiveDb.metaArchiveDao().getByType("STARTING_FIELD")
            .first { it.runId == "run-graph-seal" }
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(StartingField.serializer(), stored.contentJson)

        assertEquals(startingField.runGraphEdges, decoded.runGraphEdges)
        assertEquals(startingField.routedContextSummaries, decoded.routedContextSummaries)
    }

    @Test
    fun `canonicalizeStartingField rewrites an incorrect sealed suite hash before sealing`() = runTest {
        val dataRoot = createTempDirectory("svc-starting-field-hash-test-").toFile()
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this hash test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(dataRoot),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val startingField = StartingField.default("seal hash content", runId = "run-hash")
            .copy(
                sealedTestSuiteJson = """{"tests":[{"id":"alpha","expectedOutputPattern":"alpha"}]}""",
                sealedTestSuiteHash = "incorrect-hash"
            )

        val canonicalizeFn = service::class.declaredFunctions.first { it.name == "canonicalizeStartingField" }.apply {
            isAccessible = true
        }
        val canonicalized = canonicalizeFn.call(service, startingField) as StartingField

        assertEquals(
            sha256(startingField.sealedTestSuiteJson),
            canonicalized.sealedTestSuiteHash
        )
        assertEquals(startingField.sealedTestSuiteJson, canonicalized.sealedTestSuiteJson)
    }

    @Test
    fun `persistSealedStartingField preserves the canonical sealed suite hash`() = runTest {
        val dataRoot = createTempDirectory("svc-starting-field-canonical-hash-test-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this canonical hash test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val startingField = StartingField.default("seal canonical hash content", runId = "run-canonical-hash")
            .copy(
                sealedTestSuiteJson = """{"tests":[{"id":"alpha","expectedOutputPattern":"alpha"}]}""",
                sealedTestSuiteHash = "incorrect-hash"
            )

        val canonicalizeFn = service::class.declaredFunctions.first { it.name == "canonicalizeStartingField" }.apply {
            isAccessible = true
        }
        val persistFn = service::class.declaredFunctions.first { it.name == "persistSealedStartingField" }.apply {
            isAccessible = true
        }

        val canonicalized = canonicalizeFn.call(service, startingField) as StartingField
        persistFn.callSuspend(service, "run-canonical-hash", canonicalized)

        val archiveDb = factory.get("run-canonical-hash", "__meta_archive__")
        val stored = archiveDb.metaArchiveDao().getByType("STARTING_FIELD")
            .first { it.runId == "run-canonical-hash" }
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(StartingField.serializer(), stored.contentJson)

        assertEquals(canonicalized.sealedTestSuiteHash, decoded.sealedTestSuiteHash)
        assertEquals(sha256(startingField.sealedTestSuiteJson), decoded.sealedTestSuiteHash)
    }

    @Test
    fun `resolveGraderStartingField runs a separate grader council and normalizes grader groups`() = runTest {
        val dataRoot = createTempDirectory("svc-grader-starting-field-test-").toFile()
        val capturedObjectives = mutableListOf<String>()
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this grader seed test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(dataRoot),
            metaPlannerRunner = MetaPlannerRunner(),
            genesisCouncilRunner = { runId, objective, _, _ ->
                capturedObjectives += objective
                StartingField.default(
                    objective = "grader-council-output",
                    runId = runId,
                    groupCount = 2,
                    groupProfilesMap = mapOf(
                        "group-0" to GroupProfileType.Monastery,
                        "group-1" to GroupProfileType.Mercenary
                    )
                ).copy(
                    primordialKb = listOf(
                        SeedKBDocument(
                            id = "grader-council-seed",
                            domain = "grader_seed",
                            content = "Adversarial grader seed from separate council."
                        )
                    ),
                    norms = "Prefer adversarial, evidence-based evaluation."
                )
            }
        )

        val resolveFn = service::class.declaredFunctions.first { it.name == "resolveGraderStartingField" }.apply {
            isAccessible = true
        }

        val resolved = resolveFn.callSuspend(
            service,
            RunConfig(
                runId = "run-grader-seed",
                objective = "worker objective",
                groupCount = 2,
                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "accuracy",
                        name = "Accuracy",
                        description = "Reward evidence-based evaluation.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "sk-test",
                isTestRun = false
            ),
            StartingField.default("worker objective", runId = "run-grader-seed", groupCount = 2)
        ) as StartingField?

        assertEquals(1, capturedObjectives.size)
        assertTrue(capturedObjectives.single().contains("Design a grader civilisation"))
        assertEquals(3, resolved?.groupCount)
        assertEquals(listOf("grader-0", "grader-1", "grader-2"), resolved?.ringTopology)
        assertTrue(resolved?.primordialKb?.any { it.content.contains("Adversarial grader seed") } == true)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
