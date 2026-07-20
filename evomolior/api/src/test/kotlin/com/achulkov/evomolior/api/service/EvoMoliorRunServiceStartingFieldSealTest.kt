package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.contract.RunGraphEdge
import com.achulkov.evomolior.core.contract.ResourceNodeSeed
import com.achulkov.evomolior.core.contract.ResourceYieldType
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.RunAutoCompletionPolicy
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.SeedKBDocument
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the council StartingField is sealed into the run meta-archive before spawning.
 */
class EvoMoliorRunServiceStartingFieldSealTest {

    @Test
    fun `persistSealedStartingField stores and verifies the council output`() = runTest {
        val dataRoot = createTempDirectory("svc-starting-field-seal-test-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val service = EvoMoliorRunService(
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
                ),
                resourceNodes = listOf(
                    ResourceNodeSeed(
                        nodeId = "resource-market",
                        name = "Market evidence",
                        deliverableNodeRef = "dst:section:market",
                        scoringDimensionIds = listOf("market-quality"),
                        qualityThreshold = 0.72,
                        resourceType = ResourceYieldType.WEB_CACHE,
                        yieldMultiplier = 1.25,
                        floorGroupId = "group-a"
                    )
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
        assertEquals(startingField.resourceNodes, decoded.resourceNodes)
        assertEquals(startingField.objective, decoded.objective)
    }

    @Test
    fun `persistSealedStartingField preserves populated run graph edges`() = runTest {
        val dataRoot = createTempDirectory("svc-starting-field-graph-seal-test-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val service = EvoMoliorRunService(
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
        val service = EvoMoliorRunService(
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
    fun `canonicalizeWorkerStartingField seals the requested worker topology and active identity`() = runTest {
        val dataRoot = createTempDirectory("svc-worker-topology-canonicalization-test-").toFile()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this topology test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(dataRoot),
            metaPlannerRunner = MetaPlannerRunner()
        )
        val councilField = StartingField.default(
            objective = "canonical worker topology",
            runId = "default",
            groupCount = 4,
            groupProfilesMap = linkedMapOf(
                "group_a" to GroupProfileType.Library,
                "group_b" to GroupProfileType.Factory,
                "group_c" to GroupProfileType.Commune,
                "group_d" to GroupProfileType.Monastery
            )
        )

        val canonicalizeFn = service::class.declaredFunctions
            .first { it.name == "canonicalizeWorkerStartingField" }
            .apply { isAccessible = true }
        val canonicalized = canonicalizeFn.call(
            service,
            councilField,
            "active-run",
            5,
            List(5) { GroupProfileType.Mercenary }
        ) as StartingField

        assertEquals("active-run", canonicalized.runId)
        assertEquals(5, canonicalized.groupCount)
        assertEquals((0 until 5).map { "group-$it" }, canonicalized.ringTopology)
        assertEquals(
            linkedMapOf(
                "group-0" to GroupProfileType.Library,
                "group-1" to GroupProfileType.Factory,
                "group-2" to GroupProfileType.Commune,
                "group-3" to GroupProfileType.Monastery,
                "group-4" to GroupProfileType.Mercenary
            ),
            canonicalized.groupProfiles
        )
    }

    @Test
    fun `resolveGroupIds fails closed when a sealed field topology disagrees with its group count`() {
        val dataRoot = createTempDirectory("svc-worker-topology-fail-closed-test-").toFile()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this topology test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(dataRoot),
            metaPlannerRunner = MetaPlannerRunner()
        )
        val malformedField = StartingField.default(
            objective = "malformed sealed topology",
            runId = "malformed-run",
            groupCount = 4
        ).copy(groupCount = 5)
        val resolveFn = service::class.declaredFunctions
            .first { it.name == "resolveGroupIds" }
            .apply { isAccessible = true }

        val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
            resolveFn.call(service, malformedField, 5)
        }

        assertTrue(failure.cause is IllegalArgumentException)
        assertTrue(failure.cause?.message.orEmpty().contains("ring size=4"))
    }

    @Test
    fun `persisted run state stores canonical worker profiles instead of CLI fallbacks`() {
        val config = RunConfig(
            runId = "canonical-profile-state",
            objective = "persist canonical profiles",
            groupCount = 5,
            groupProfiles = List(5) { GroupProfileType.Library },
            rounds = 12,
            roundDurationMinutes = 3,
            maxAgentsPerGroup = 6,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Evidence fidelity",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "test"
        )
        val canonicalProfiles = listOf(
            GroupProfileType.Library,
            GroupProfileType.Factory,
            GroupProfileType.Commune,
            GroupProfileType.Monastery,
            GroupProfileType.Mercenary
        )

        val state = PersistedRunState.fromRunConfig(
            config = config,
            groupIds = (0 until 5).map { "group-$it" },
            graderGroupIds = (0 until 3).map { "grader-$it" },
            allowedHosts = emptySet(),
            groupProfiles = canonicalProfiles
        )

        assertEquals(canonicalProfiles, state.groupProfiles)
        assertEquals(canonicalProfiles, state.toRunConfig("runtime-key").groupProfiles)
    }

    @Test
    fun `persisted run state round trips purpose convergence policy and streak with historical defaults`() {
        val config = RunConfig(
            runId = "acceptance-purpose-state",
            objective = "Persist explicit acceptance intent",
            groupCount = 2,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            rounds = 6,
            roundDurationMinutes = 3,
            maxAgentsPerGroup = 2,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Evidence fidelity",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "/tmp/codex",
            llmBackend = LlmBackend.CODEX_CHATGPT,
            councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            autoCompletionPolicy = RunAutoCompletionPolicy(consecutiveAcceptedRounds = 2)
        )
        val state = PersistedRunState.fromRunConfig(
            config = config,
            groupIds = listOf("group-0", "group-1"),
            graderGroupIds = listOf("grader-0", "grader-1"),
            allowedHosts = emptySet()
        ).copy(
            autoCompletionCandidateWinnerId = "group-1",
            autoCompletionAcceptedRoundStreak = 1
        )
        val dataRoot = createTempDirectory("persisted-purpose-state-").toFile()

        PersistedRunStateStore.writeForDataRoot(dataRoot, state)

        val restored = requireNotNull(PersistedRunStateStore.read(dataRoot, state.runId))
        val restoredConfig = restored.toRunConfig("runtime-codex")
        assertEquals(CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE, restoredConfig.councilExecutionPurpose)
        assertEquals(RunAutoCompletionPolicy(2), restoredConfig.autoCompletionPolicy)
        assertEquals("group-1", restored.autoCompletionCandidateWinnerId)
        assertEquals(1, restored.autoCompletionAcceptedRoundStreak)
        restored.requireCompatibleRecoveryIntent(restoredConfig)

        val purposeMismatch = assertFailsWith<IllegalArgumentException> {
            restored.requireCompatibleRecoveryIntent(
                restoredConfig.copy(councilExecutionPurpose = CouncilExecutionPurpose.STANDARD)
            )
        }
        assertTrue(purposeMismatch.message.orEmpty().contains("does not match persisted purpose"))
        val policyMismatch = assertFailsWith<IllegalArgumentException> {
            restored.requireCompatibleRecoveryIntent(
                restoredConfig.copy(autoCompletionPolicy = null)
            )
        }
        assertTrue(policyMismatch.message.orEmpty().contains("does not match persisted policy"))

        val encoded = Json.encodeToString(PersistedRunState.serializer(), state)
        val historicalJson = JsonObject(
            Json.parseToJsonElement(encoded).jsonObject - setOf(
                "councilExecutionPurpose",
                "autoCompletionPolicy",
                "autoCompletionCandidateWinnerId",
                "autoCompletionAcceptedRoundStreak"
            )
        ).toString()
        val historical = Json.decodeFromString(PersistedRunState.serializer(), historicalJson)

        assertEquals(CouncilExecutionPurpose.STANDARD, historical.councilExecutionPurpose)
        assertNull(historical.autoCompletionPolicy)
        assertNull(historical.autoCompletionCandidateWinnerId)
        assertEquals(0, historical.autoCompletionAcceptedRoundStreak)
    }

    @Test
    fun `Appendix B purpose rejects diagnostic test runs`() {
        val error = assertFailsWith<IllegalArgumentException> {
            RunConfig(
                runId = "acceptance-test-run",
                objective = "Do not label a Council-bypassing run as acceptance",
                groupCount = 1,
                groupProfiles = listOf(GroupProfileType.Library),
                rounds = 6,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "accuracy",
                        name = "Accuracy",
                        description = "Evidence fidelity",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "/tmp/codex",
                llmBackend = LlmBackend.CODEX_CHATGPT,
                councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
                isTestRun = true
            )
        }

        assertTrue(error.message.orEmpty().contains("restricted to production runs"))
    }

    @Test
    fun `persistSealedStartingField preserves the canonical sealed suite hash`() = runTest {
        val dataRoot = createTempDirectory("svc-starting-field-canonical-hash-test-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val service = EvoMoliorRunService(
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
    fun `qwen model normalization preserves explicit override singleton pools`() = runTest {
        val dataRoot = createTempDirectory("svc-qwen-override-normalization-test-").toFile()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this normalization test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(dataRoot),
            metaPlannerRunner = MetaPlannerRunner()
        )
        val baseField = StartingField.default("qwen override normalization", runId = "run-qwen-normalize")
            .copy(
                modelAssignment = mapOf(
                    "Builder" to ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
                ),
                modelPools = mapOf(
                    "Builder" to listOf(
                        ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL,
                        ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL
                    )
                )
            )

        val applyModelOverridesFn = service::class.declaredFunctions.first { it.name == "applyModelOverrides" }.apply {
            isAccessible = true
        }
        val normalizeFn = service::class.declaredFunctions.first { it.name == "normalizeStartingFieldModelsForBackend" }.apply {
            isAccessible = true
        }
        val overridden = applyModelOverridesFn.call(
            service,
            baseField,
            mapOf("Builder" to ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL)
        ) as StartingField
        val normalized = normalizeFn.call(service, overridden, LlmBackend.QWEN_CLOUD) as StartingField

        assertEquals(
            ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL,
            normalized.modelAssignment["Builder"]
        )
        assertEquals(
            listOf(ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL),
            normalized.modelPools["Builder"],
            "An explicit Qwen model override must not retain qwen3.7-plus as a secondary pool candidate"
        )
    }

    @Test
    fun `qwen model normalization keeps default society profile mixed without explicit overrides`() = runTest {
        val dataRoot = createTempDirectory("svc-qwen-mixed-normalization-test-").toFile()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this normalization test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(dataRoot),
            metaPlannerRunner = MetaPlannerRunner()
        )
        val startingField = StartingField.default(
            "qwen default society normalization",
            runId = "run-qwen-mixed-normalize"
        ).copy(
            modelAssignment = emptyMap(),
            modelPools = emptyMap()
        )

        val normalizeFn = service::class.declaredFunctions.first { it.name == "normalizeStartingFieldModelsForBackend" }.apply {
            isAccessible = true
        }
        val normalized = normalizeFn.call(service, startingField, LlmBackend.QWEN_CLOUD) as StartingField
        val assignments = normalized.modelAssignment
        val qwenCount = assignments.values.count { it == ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL }
        val deepSeekCount = assignments.values.count { it == ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL }

        assertEquals(
            ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL,
            assignments["Auditor"],
            "Normal Qwen society runs should retain DeepSeek for at least one audit-style role."
        )
        assertTrue(qwenCount > deepSeekCount, "Qwen Plus should remain the majority model in the Qwen society profile.")
        assertTrue(deepSeekCount > 0, "Qwen society normalization must not collapse to a strict qwen3.7-plus-only profile.")
        assertTrue(
            assignments.values.all {
                it in setOf(
                    ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL,
                    ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL
                )
            }
        )
        assertTrue(
            normalized.modelPools.values.any { ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL in it },
            "Normalized runtime pools should preserve DeepSeek availability for normal Qwen society runs."
        )
    }

    @Test
    fun `resolveGraderStartingField uses deterministic fallback outside compiled production path`() = runTest {
        val dataRoot = createTempDirectory("svc-grader-starting-field-test-").toFile()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this grader seed test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(dataRoot),
            metaPlannerRunner = MetaPlannerRunner()
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

        assertEquals(3, resolved?.groupCount)
        assertEquals(listOf("grader-0", "grader-1", "grader-2"), resolved?.ringTopology)
        assertTrue(
            resolved?.primordialKb.orEmpty().any {
                it.content.contains("adversarial", ignoreCase = true)
            }
        )
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
