@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.achulkov.evokammer.agents.runner

import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.substrate.db.dao.StagingDao
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [DeathNoteProtocol].
 *
 * Verifies:
 * - Successful death note prevents emergency-fallback path
 * - Grace-period expiry triggers a single emergency note write
 * - [DeathNoteProtocol.isWritten] returns the correct state
 */
class DeathNoteProtocolTest {

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun bootContext(gracePeriodSeconds: Int = 5) = AgentBootContext(
        agentId = "agent-test-001",
        runId = "run-test",
        groupId = "group-test",
        role = Role.Researcher,
        rolePrompt = "test prompt",
        budgetToken = "token:test:0",
        substrateDbPath = "/tmp/test.db",
        stagingMountPath = "/staging",
        wipTaskClaim = null,
        legacyProtocol = null,
        stageDisclosure = "test stage",
        modelAssignment = "openai/gpt-4o-mini",
        ipcBaseUrl = "http://10.0.0.1:10000",
        generation = 1,
        gracePeriodSeconds = gracePeriodSeconds,
        allocatedBudget = 50.0
    )

    /** Minimal fake DAO that records inserts without a real Room database. */
    private class FakeStagingDao : StagingDao {
        val inserts = mutableListOf<StagingSubmissionEntity>()
        override suspend fun insert(submission: StagingSubmissionEntity) { inserts.add(submission) }
        override suspend fun update(submission: StagingSubmissionEntity) {}
        override suspend fun getById(id: String): StagingSubmissionEntity? = null
        override suspend fun getPending(runId: String, groupId: String) = emptyList<StagingSubmissionEntity>()
        override suspend fun getPendingByGeneration(runId: String, groupId: String, generation: Int) = emptyList<StagingSubmissionEntity>()
        override fun observePending(runId: String, groupId: String): Flow<List<StagingSubmissionEntity>> = flowOf(emptyList())
        override suspend fun getByAgent(runId: String, groupId: String, agentId: String) = emptyList<StagingSubmissionEntity>()
        override suspend fun countPendingByType(runId: String, groupId: String, type: String) = 0
        override suspend fun countPending(runId: String, groupId: String) = 0
        override suspend fun updateReview(id: String, status: String, criticId: String?, trustScore: Double?, reason: String?, reviewedAtMs: Long) {}
        override suspend fun getAcceptanceRateForAgent(runId: String, groupId: String, agentId: String): Double? = null
        override suspend fun getPromotedCountForAgent(runId: String, groupId: String, agentId: String) = 0
        override suspend fun getPromotedKgFactCountForAgent(runId: String, groupId: String, agentId: String) = 0
        override suspend fun getTotalCountForAgent(runId: String, groupId: String, agentId: String) = 0
        override suspend fun getAll(runId: String, groupId: String, limit: Int) = emptyList<StagingSubmissionEntity>()
        override suspend fun getMaxGeneration(runId: String, groupId: String): Int? = null
        override suspend fun getDistinctAgentIds(runId: String, groupId: String, generation: Int) = emptyList<String>()
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `isWritten is false before any death note action`() {
        val protocol = DeathNoteProtocol(bootContext(), FakeStagingDao())
        assertFalse(protocol.isWritten, "Protocol should start with isWritten=false")
    }

    @Test
    fun `markDeathNoteWritten sets isWritten to true`() {
        val protocol = DeathNoteProtocol(bootContext(), FakeStagingDao())
        protocol.markDeathNoteWritten()
        assertTrue(protocol.isWritten, "After markDeathNoteWritten, isWritten should be true")
    }

    @Test
    fun `grace period does not write emergency note when death note already written`() = runTest {
        val scheduler = TestCoroutineScheduler()
        val testDispatcher = StandardTestDispatcher(scheduler)
        val dao = FakeStagingDao()
        val protocol = DeathNoteProtocol(bootContext(gracePeriodSeconds = 1), dao)

        // Mark written before signalling grace
        protocol.markDeathNoteWritten()

        val scope = CoroutineScope(testDispatcher + Job())
        protocol.signalGrace(scope, reason = "test")

        // Advance well past the grace period
        scheduler.advanceTimeBy(5_000)

        assertEquals(0, dao.inserts.size, "Emergency note should not be written if death note was already written")
    }

    @Test
    fun `calling markDeathNoteWritten after signalGrace prevents emergency write`() = runTest {
        val scheduler = TestCoroutineScheduler()
        val testDispatcher = StandardTestDispatcher(scheduler)
        val dao = FakeStagingDao()
        val protocol = DeathNoteProtocol(bootContext(gracePeriodSeconds = 10), dao)

        val scope = CoroutineScope(testDispatcher + Job())
        protocol.signalGrace(scope, reason = "test")

        // Mark written before grace period expires (halfway through)
        scheduler.advanceTimeBy(5_000)
        protocol.markDeathNoteWritten()
        // Advance past the grace period end
        scheduler.advanceTimeBy(10_000)

        assertEquals(0, dao.inserts.size, "No emergency note should be written after markDeathNoteWritten")
    }
}
