@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.deliverable

import com.achulkov.evomolior.api.service.StartupActivationFlags
import com.achulkov.evomolior.api.service.StartupRejectionArtifact
import com.achulkov.evomolior.api.service.StartupRejectionError
import com.achulkov.evomolior.api.service.StartupRejectionErrorKind
import com.achulkov.evomolior.api.service.StartupRejectionPhase
import com.achulkov.evomolior.api.service.StartupRejectionReason
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class StartupDiagnosticOutcomeCompilerTest {
    @Test
    fun `compiler emits diagnostic only authority without private rejection prose`() {
        val compilation = StartupDiagnosticOutcomeCompiler.compile(
            rejection = StartupRejectionArtifact(
                runId = "startup-run",
                phase = StartupRejectionPhase.TEST_EXECUTABILITY_PREFLIGHT,
                reasons = listOf(StartupRejectionReason.TEST_SUITE_NOT_EXECUTABLE),
                errors = listOf(
                    StartupRejectionError(
                        kind = StartupRejectionErrorKind.TEST_EXECUTABILITY,
                        path = "/private/fixture/secret.json",
                        message = "SECRET_PRIVATE_EXPECTED_VALUE",
                    )
                ),
                candidateFieldHash = "a".repeat(64),
                testSuiteHash = "b".repeat(64),
                declaredTestCount = 3,
                compiledTestCount = 2,
                declaredWorkerGroupCount = 2,
                compiledWorkerGroupCount = 2,
                declaredGraderGroupCount = 2,
                compiledGraderGroupCount = 2,
                distributionManifestHash = "c".repeat(64),
                activationFlags = StartupActivationFlags(
                    runStartedEmitted = false,
                    workerInfrastructureActivated = false,
                    graderInfrastructureActivated = false,
                    successArchiveCreated = false,
                    winnerAuthorityCreated = false,
                ),
                createdAt = "2026-07-24T12:00:00Z",
            ),
            generatedAt = Instant.parse("2026-07-24T12:00:00Z"),
        )

        assertEquals(
            RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE,
            compilation.decisionPackage.outcomeClass,
        )
        assertEquals(null, compilation.decisionPackage.acceptedAuthority)
        assertTrue(compilation.decisionPackage.components.all {
            it.authorityClass == ComponentAuthorityClass.WITHHELD
        })
        assertTrue(compilation.diagnosticPackage.hasValidHash())
        val publicText = compilation.diagnosticPackage.issues.joinToString("|") {
            "${it.code}|${it.publicMessage}|${it.remediation}"
        }
        assertFalse(publicText.contains("SECRET_PRIVATE_EXPECTED_VALUE"))
        assertFalse(publicText.contains("/private/fixture"))
    }
}
