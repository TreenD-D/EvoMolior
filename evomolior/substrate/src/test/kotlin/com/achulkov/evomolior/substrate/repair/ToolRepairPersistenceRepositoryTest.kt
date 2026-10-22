package com.achulkov.evomolior.substrate.repair

import com.achulkov.evomolior.core.repair.ToolPromotionStatus
import com.achulkov.evomolior.core.repair.ToolRepairAttemptRecord
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1
import com.achulkov.evomolior.core.repair.ToolRepairDisclosureClass
import com.achulkov.evomolior.core.repair.ToolRepairSource
import com.achulkov.evomolior.core.repair.ToolSemanticVerdict
import com.achulkov.evomolior.core.repair.ToolValidationStatus
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolRepairPersistenceRepositoryTest {
    @Test
    fun `diagnostics and attempts round trip exactly and reject identity mutation`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("tool-repair-persistence-").toFile()
        ).get("run", "group")
        try {
            val repository = ToolRepairPersistenceRepository(database)
            val diagnostic = diagnostic()
            val attempt = attempt(1)

            assertEquals(diagnostic, repository.appendDiagnostic(diagnostic))
            assertEquals(diagnostic, repository.appendDiagnostic(diagnostic))
            assertEquals(diagnostic, repository.diagnostic(diagnostic.diagnosticId))
            assertEquals(
                emptyList(),
                repository.visibleDiagnostics("run", "group", "opportunity", 1)
            )
            assertEquals(
                listOf(diagnostic),
                repository.visibleDiagnostics("run", "group", "opportunity", 2)
            )

            assertEquals(attempt, repository.appendAttempt(attempt))
            assertEquals(attempt, repository.appendAttempt(attempt))
            assertEquals(attempt, repository.attempt(attempt.attemptId))
            assertEquals(listOf(attempt), repository.attempts("opportunity"))

            assertFailsWith<IllegalArgumentException> {
                repository.appendDiagnostic(
                    diagnostic.copy(code = ToolRepairCode.SCOPE_MISMATCH)
                        .withComputedHash()
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.appendAttempt(
                    attempt.copy(promotionResult = ToolPromotionStatus.REJECTED_PUBLIC_AUTHORITY)
                        .withComputedHash()
                )
            }

            val second = attempt(2)
            val third = attempt(3)
            assertEquals(second, repository.appendAttempt(second))
            assertEquals(third, repository.appendAttempt(third))
            assertFailsWith<IllegalArgumentException> {
                repository.appendAttempt(attempt(4))
            }
            assertEquals(listOf(attempt, second, third), repository.attempts("opportunity"))
        } finally {
            database.close()
        }
    }

    private fun diagnostic() = ToolRepairDiagnosticV1(
        diagnosticId = "diagnostic",
        runId = "run",
        groupId = "group",
        nodeId = "node",
        opportunityId = "opportunity",
        opportunityHash = "a".repeat(64),
        candidateHash = "b".repeat(64),
        source = ToolRepairSource.PUBLIC_PREFLIGHT,
        disclosureClass = ToolRepairDisclosureClass.PUBLIC_EXACT,
        code = ToolRepairCode.CONTRACT_SCHEMA_MISMATCH,
        createdRound = 1,
        visibleFromRound = 2,
        affectedPublicField = "result"
    ).withComputedHash()

    private fun attempt(number: Int) = ToolRepairAttemptRecord(
        attemptId = "attempt-$number",
        opportunityId = "opportunity",
        candidateHash = ('b' + number).toString().repeat(64),
        authorAgentId = "agent",
        round = number + 1,
        consumedDiagnosticIds = listOf("diagnostic"),
        preflightResult = ToolValidationStatus.PASSED,
        criticResult = ToolSemanticVerdict.PROMOTE,
        promotionResult = ToolPromotionStatus.PROMOTED
    ).withComputedHash()
}
