package com.achulkov.evomolior.agents.runner

import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.substrate.db.dao.StagingDao
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Manages the Death Note protocol for an agent's graceful shutdown.
 *
 * When the LifecycleManager sends a kill signal (SIGTERM), the agent has
 * [AgentBootContext.gracePeriodSeconds] to write its Death Note via [WriteDeathNoteTool].
 * After the grace period, the process is hard-killed.
 *
 * This class:
 * 1. Registers a JVM shutdown hook to flush a minimal Death Note if the process
 *    is killed unexpectedly (OS kill, OOM, etc.).
 * 2. Exposes [signalGrace] to begin the graceful shutdown timer.
 * 3. Exposes [markDeathNoteWritten] so [WriteDeathNoteTool] can cancel the hard-kill timer.
 *
 * @param bootContext   The agent's boot context.
 * @param stagingDao    DAO to persist emergency Death Notes.
 */
class DeathNoteProtocol(
    private val bootContext: AgentBootContext,
    private val stagingDao: StagingDao
) {

    @Volatile
    private var deathNoteWritten = false

    /**
     * Registers the JVM shutdown hook.
     * Should be called once at agent startup.
     */
    fun register() {
        Runtime.getRuntime().addShutdownHook(Thread {
            if (!deathNoteWritten) {
                writeEmergencyDeathNote("process_killed_unexpectedly")
            }
        })
    }

    /**
     * Signals the start of the grace period.
     *
     * Launches a coroutine that waits [gracePeriodSeconds] seconds; if the death note
     * has not been written by then, it writes an emergency note and exits the process.
     */
    fun signalGrace(scope: CoroutineScope, reason: String = "budget_exhausted") {
        scope.launch(Dispatchers.IO) {
            Napier.i("DeathNoteProtocol: grace period started (${bootContext.gracePeriodSeconds}s) reason=$reason")
            delay(bootContext.gracePeriodSeconds * 1000L)

            if (!deathNoteWritten) {
                Napier.w("DeathNoteProtocol: grace period expired without Death Note — writing emergency note")
                writeEmergencyDeathNote(reason)
            }
        }
    }

    /**
     * Called by [WriteDeathNoteTool] after successfully persisting the Death Note.
     * Cancels the hard-kill timer.
     */
    fun markDeathNoteWritten() {
        deathNoteWritten = true
        Napier.i("DeathNoteProtocol: Death Note written for agent ${bootContext.agentId}")
    }

    /** Whether the Death Note has been committed. */
    val isWritten: Boolean get() = deathNoteWritten

    /**
     * Writes a minimal emergency Death Note when the graceful path is unavailable.
     * This runs synchronously because it may be called from a shutdown hook.
     */
    private fun writeEmergencyDeathNote(reason: String) {
        try {
            runBlocking {
                withTimeoutOrNull(5_000L) {
                    val entity = StagingSubmissionEntity(
                        id = UUID.randomUUID().toString(),
                        runId = bootContext.runId,
                        groupId = bootContext.groupId,
                        agentId = bootContext.agentId,
                        role = bootContext.role.name,
                        type = "DEATH_NOTE",
                        content = """{"emergency":true,"reason":"$reason","agentId":"${bootContext.agentId}"}""",
                        wipTaskRef = bootContext.wipTaskClaim?.nodeId,
                        speculative = false,
                        outlier = bootContext.role.name == "Outlier",
                        budgetSignature = "",
                        status = "pending",
                        generation = bootContext.generation,
                        submittedAtMs = System.currentTimeMillis()
                    )
                    stagingDao.insert(entity)
                    Napier.i("DeathNoteProtocol: emergency Death Note persisted for ${bootContext.agentId}")
                }
            }
        } catch (e: Exception) {
            Napier.e("DeathNoteProtocol: failed to write emergency Death Note: ${e.message}", e)
        }
    }
}
