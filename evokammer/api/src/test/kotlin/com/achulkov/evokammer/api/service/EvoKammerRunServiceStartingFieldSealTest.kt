package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
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

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
