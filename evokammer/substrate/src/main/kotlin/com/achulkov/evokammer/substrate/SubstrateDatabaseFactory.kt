package com.achulkov.evokammer.substrate

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.achulkov.evokammer.substrate.db.EvoKammerDatabase
import java.io.File

/**
 * Creates [EvoKammerDatabase] instances per (runId, groupId) pair.
 *
 * DB files are stored at:
 * `{dataRootDir}/evokammer/runs/{runId}/groups/{groupId}/substrate.db`
 *
 * This enforces Tier 1 (group) isolation at the storage level: each group's DB file
 * is physically separate and mounted exclusively to that group's agent VMs.
 *
 * @param dataRootDir The root directory for EvoKammer data storage (e.g. user home dir).
 */
class SubstrateDatabaseFactory(private val dataRootDir: File) {

    private val openDatabases = mutableMapOf<String, EvoKammerDatabase>()

    /**
     * Returns (or creates) the [EvoKammerDatabase] for the given (runId, groupId) pair.
     *
     * Thread-safe: uses synchronized block.
     *
     * @param runId The run identifier.
     * @param groupId The group identifier.
     * @return An open [EvoKammerDatabase] instance.
     */
    fun get(runId: String, groupId: String): EvoKammerDatabase {
        val key = "$runId/$groupId"
        synchronized(openDatabases) {
            return openDatabases.getOrPut(key) { create(runId, groupId) }
        }
    }

    /**
     * Closes and removes the database for the given (runId, groupId) pair.
     * Should be called when a run completes or a group is decommissioned.
     */
    fun close(runId: String, groupId: String) {
        val key = "$runId/$groupId"
        synchronized(openDatabases) {
            openDatabases.remove(key)?.close()
        }
    }

    /** Returns the DB file path for a (runId, groupId) pair. */
    fun dbFilePath(runId: String, groupId: String): File {
        return File(dataRootDir, "evokammer/runs/$runId/groups/$groupId/substrate.db")
    }

    /**
     * Returns (or creates) the [EvoKammerDatabase] for a Grader Group.
     *
     * Grader databases are stored at:
     * `{dataRootDir}/evokammer/runs/{runId}/graders/{graderGroupId}/substrate.db`
     *
     * Grader groups accumulate their own KB/KG/tools across rounds, enabling the
     * development of persistent evaluation methodology.
     *
     * @param runId The run identifier.
     * @param graderGroupId The grader group identifier (e.g. "grader-1").
     * @return An open [EvoKammerDatabase] instance for the grader group.
     */
    fun getGrader(runId: String, graderGroupId: String): EvoKammerDatabase {
        val key = "$runId/graders/$graderGroupId"
        synchronized(openDatabases) {
            return openDatabases.getOrPut(key) { createGrader(runId, graderGroupId) }
        }
    }

    /** Returns the DB file path for a grader group. */
    fun graderDbFilePath(runId: String, graderGroupId: String): File {
        return File(dataRootDir, "evokammer/runs/$runId/graders/$graderGroupId/substrate.db")
    }

    private fun createGrader(runId: String, graderGroupId: String): EvoKammerDatabase {
        val dbFile = graderDbFilePath(runId, graderGroupId)
        dbFile.parentFile?.mkdirs()
        return Room.databaseBuilder<EvoKammerDatabase>(
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            // Allow Room to wipe and recreate whenever no migration path exists (both upgrade
            // and downgrade). EvoKammer DBs are ephemeral per-run stores — data loss is
            // acceptable and far preferable to crashing with a migration exception.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    private fun create(runId: String, groupId: String): EvoKammerDatabase {
        val dbFile = dbFilePath(runId, groupId)
        dbFile.parentFile?.mkdirs()

        return Room.databaseBuilder<EvoKammerDatabase>(
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            // Allow Room to wipe and recreate whenever no migration path exists (both upgrade
            // and downgrade). EvoKammer DBs are ephemeral per-run stores — data loss is
            // acceptable and far preferable to crashing with a migration exception.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
