@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.publication

import com.achulkov.evomolior.core.contract.PublicationState
import com.achulkov.evomolior.core.contract.PublicationStateEvent
import com.achulkov.evomolior.core.contract.RecoveryPublicationState
import com.achulkov.evomolior.core.contract.RecoveryPublicationStateEvent
import com.achulkov.evomolior.core.contract.StartupDiagnosticState
import com.achulkov.evomolior.core.contract.StartupDiagnosticStateEvent
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class PublicationOutcomeRepositoryTest {
    @Test
    fun `outcome histories are exact isolated idempotent and immutable`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("publication-outcome-persistence-").toFile()
        ).get("run", "group")
        try {
            val repository = PublicationOutcomeRepository(database)
            val accepted = accepted()
            val recovery = recovery()
            val startup = startup()

            assertEquals(accepted, repository.appendAccepted(accepted))
            assertEquals(accepted, repository.appendAccepted(accepted))
            assertEquals(listOf(accepted), repository.acceptedHistory("run"))
            assertEquals(accepted, repository.acceptedEvent(accepted.eventId))

            assertEquals(recovery, repository.appendRecovery(recovery))
            assertEquals(recovery, repository.appendRecovery(recovery))
            assertEquals(listOf(recovery), repository.recoveryHistory("run"))
            assertEquals(recovery, repository.recoveryEvent(recovery.eventId))

            assertEquals(startup, repository.appendStartup(startup))
            assertEquals(startup, repository.appendStartup(startup))
            assertEquals(listOf(startup), repository.startupHistory("run"))
            assertEquals(startup, repository.startupEvent(startup.eventId))

            assertFailsWith<IllegalArgumentException> {
                repository.appendAccepted(
                    accepted.copy(authorityReferenceIds = setOf("changed"))
                        .withComputedHash()
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.appendRecovery(
                    recovery.copy(authorityReferenceIds = setOf("changed"))
                        .withComputedHash()
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.appendStartup(
                    startup.copy(authorityReferenceIds = setOf("changed"))
                        .withComputedHash()
                )
            }
        } finally {
            database.close()
        }
    }

    private fun accepted() = PublicationStateEvent(
        eventId = "accepted-event",
        runId = "run",
        state = PublicationState.FINAL_CANDIDATES_SEALED,
        sequence = 0,
        previousEventHash = null,
        authorityReferenceIds = setOf("candidate-set"),
        occurredAt = Instant.parse("2026-07-24T12:00:00Z")
    ).withComputedHash()

    private fun recovery() = RecoveryPublicationStateEvent(
        eventId = "recovery-event",
        runId = "run",
        state = RecoveryPublicationState.WORK_CYCLE_CLOSED_WITHOUT_ACCEPTANCE,
        sequence = 0,
        previousEventHash = null,
        authorityReferenceIds = setOf("failed-gate-set"),
        occurredAt = Instant.parse("2026-07-24T12:01:00Z")
    ).withComputedHash()

    private fun startup() = StartupDiagnosticStateEvent(
        eventId = "startup-event",
        runId = "run",
        state = StartupDiagnosticState.ADMISSION_OR_COUNCIL_FAILURE,
        sequence = 0,
        previousEventHash = null,
        authorityReferenceIds = setOf("admission-failure"),
        occurredAt = Instant.parse("2026-07-24T12:02:00Z")
    ).withComputedHash()
}
