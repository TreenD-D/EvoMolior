package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.grader.HiddenTestId
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Focused persistence coverage for immutable startup-rejection negative evidence. */
class StartupRejectionFileStoreTest {
    @Test
    fun `atomic store strictly reads back immutable startup rejection`() {
        val hash = "a".repeat(64)
        val root = createTempDirectory("startup-rejection-store-").toFile()
        val store = StartupRejectionFileStore { runId -> File(root, "$runId/control") }
        val artifact = StartupRejectionArtifact(
            runId = "run-11",
            phase = StartupRejectionPhase.TEST_EXECUTABILITY_PREFLIGHT,
            reasons = listOf(StartupRejectionReason.TEST_SUITE_NOT_EXECUTABLE),
            errors = listOf(
                StartupRejectionError(
                    kind = StartupRejectionErrorKind.TEST_EXECUTABILITY,
                    message = "test definition is not executable"
                )
            ),
            candidateFieldHash = hash,
            testSuiteHash = hash,
            declaredTestCount = 7,
            compiledTestCount = 0,
            declaredWorkerGroupCount = 2,
            compiledWorkerGroupCount = 2,
            declaredGraderGroupCount = 2,
            compiledGraderGroupCount = 2,
            sourceManifestHash = hash,
            distributionManifestHash = hash,
            providerExecutionRefs = emptyList(),
            providerExecutionCount = 0,
            activationFlags = StartupActivationFlags(
                runStartedEmitted = false,
                workerInfrastructureActivated = false,
                graderInfrastructureActivated = false,
                successArchiveCreated = false,
                winnerAuthorityCreated = false
            ),
            createdAt = "2026-07-19T00:00:00Z"
        )

        val file = store.persist(artifact)

        assertEquals(artifact, assertNotNull(store.read("run-11")))
        assertEquals(StartupRejectionFileStore.DIRECTORY_NAME, file.parentFile.name)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ),
            Files.getPosixFilePermissions(file.toPath())
        )
        assertFails {
            Files.writeString(file.toPath(), "tampered")
        }
        assertFailsWith<IllegalArgumentException> {
            store.persist(
                artifact.copy(
                    errors = listOf(
                        StartupRejectionError(
                            kind = StartupRejectionErrorKind.TEST_EXECUTABILITY,
                            message = "changed"
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `typed test identity and bounded logical Council reference are enforced`() {
        val hash = "b".repeat(64)
        val artifact = StartupRejectionArtifact(
            runId = "run-typed-test",
            phase = StartupRejectionPhase.COUNCIL_COMPILE,
            reasons = listOf(StartupRejectionReason.COUNCIL_COMPILE_FAILED),
            errors = listOf(
                StartupRejectionError(
                    kind = StartupRejectionErrorKind.COUNCIL_COMPILE,
                    message = "null source definition",
                    testId = HiddenTestId("test-7"),
                    definitionHash = hash
                )
            ),
            declaredTestCount = 1,
            declaredWorkerGroupCount = 2,
            declaredGraderGroupCount = 2,
            providerExecutionRefs = listOf("genesis-council:run-typed-test:$hash"),
            providerExecutionCount = 1,
            activationFlags = StartupActivationFlags(false, false, false, false, false),
            createdAt = "2026-07-19T00:00:00Z"
        )

        assertEquals(HiddenTestId("test-7"), artifact.errors.single().testId)
        assertFailsWith<IllegalArgumentException> {
            artifact.copy(
                providerExecutionRefs = listOf("one", "two"),
                providerExecutionCount = 2
            )
        }
        assertFailsWith<IllegalArgumentException> {
            artifact.copy(compiledTestCount = artifact.declaredTestCount + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            artifact.copy(providerExecutionRefs = listOf("genesis-council:foreign-run:$hash"))
        }
        assertFailsWith<IllegalArgumentException> {
            artifact.copy(providerExecutionRefs = listOf("genesis-council:run-typed-test:not-a-hash"))
        }
    }

    @Test
    fun `concurrent differing writers create exactly one immutable winner`() {
        val hash = "c".repeat(64)
        val root = createTempDirectory("startup-rejection-race-").toFile()
        val store = StartupRejectionFileStore { runId -> File(root, "$runId/control") }
        fun artifact(message: String) = StartupRejectionArtifact(
            runId = "race-run",
            phase = StartupRejectionPhase.ADMISSION,
            reasons = listOf(StartupRejectionReason.ADMISSION_REJECTED),
            errors = listOf(StartupRejectionError(StartupRejectionErrorKind.ADMISSION, message = message)),
            declaredWorkerGroupCount = 0,
            declaredGraderGroupCount = 0,
            sourceManifestHash = hash,
            providerExecutionCount = 0,
            activationFlags = StartupActivationFlags(false, false, false, false, false),
            createdAt = "2026-07-19T00:00:00Z"
        )
        val first = artifact("first")
        val second = artifact("second")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = pool.invokeAll(
                listOf(
                    Callable { runCatching { store.persist(first); first } },
                    Callable { runCatching { store.persist(second); second } }
                )
            ).map { it.get() }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(1, outcomes.count { it.isFailure })
            val winner = outcomes.single { it.isSuccess }.getOrThrow()
            assertEquals(winner, store.read("race-run"))
        } finally {
            pool.shutdownNow()
        }
    }
}
