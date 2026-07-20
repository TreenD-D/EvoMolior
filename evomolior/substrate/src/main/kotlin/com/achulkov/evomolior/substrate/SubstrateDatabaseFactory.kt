package com.achulkov.evomolior.substrate

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import java.io.File

/**
 * Creates [EvoMoliorDatabase] instances per (runId, groupId) pair.
 *
 * DB files are stored at:
 * `{dataRootDir}/evomolior/runs/{runId}/groups/{groupId}/substrate.db`
 *
 * This enforces Tier 1 (group) isolation at the storage level: each group's DB file
 * is physically separate and mounted exclusively to that group's agent VMs.
 *
 * @param dataRootDir The root directory for EvoMolior data storage (e.g. user home dir).
 */
class SubstrateDatabaseFactory(private val dataRootDir: File) {

    private val openDatabases = mutableMapOf<String, EvoMoliorDatabase>()

    /**
     * Returns (or creates) the [EvoMoliorDatabase] for the given (runId, groupId) pair.
     *
     * Thread-safe: uses synchronized block.
     *
     * @param runId The run identifier.
     * @param groupId The group identifier.
     * @return An open [EvoMoliorDatabase] instance.
     */
    fun get(runId: String, groupId: String): EvoMoliorDatabase {
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
        return File(dataRootDir, "evomolior/runs/$runId/groups/$groupId/substrate.db")
    }

    /** Returns the filesystem root for a run. */
    fun runRootDir(runId: String): File = File(dataRootDir, "evomolior/runs/$runId")

    /** Returns the control directory for durable non-DB run metadata. */
    fun runControlDir(runId: String): File = File(runRootDir(runId), "control")

    /** Returns the control directory for global Overseer state. */
    fun overseerControlDir(): File = File(dataRootDir, "evomolior/overseer")

    /** Returns the durable state file for the Overseer kernel. */
    fun overseerStateFile(): File = File(overseerControlDir(), "overseer-kernel-state.json")

    /** Returns the control directory for a specific group inside a run. */
    fun groupControlDir(runId: String, groupId: String): File =
        File(runControlDir(runId), "groups/$groupId")

    /**
     * Returns (or creates) the [EvoMoliorDatabase] for a Grader Group.
     *
     * Grader databases are stored at:
     * `{dataRootDir}/evomolior/runs/{runId}/graders/{graderGroupId}/substrate.db`
     *
     * Grader groups accumulate their own KB/KG/tools across rounds, enabling the
     * development of persistent evaluation methodology.
     *
     * @param runId The run identifier.
     * @param graderGroupId The grader group identifier (e.g. "grader-1").
     * @return An open [EvoMoliorDatabase] instance for the grader group.
     */
    fun getGrader(runId: String, graderGroupId: String): EvoMoliorDatabase {
        val key = "$runId/graders/$graderGroupId"
        synchronized(openDatabases) {
            return openDatabases.getOrPut(key) { createGrader(runId, graderGroupId) }
        }
    }

    /** Returns the DB file path for a grader group. */
    fun graderDbFilePath(runId: String, graderGroupId: String): File {
        return File(dataRootDir, "evomolior/runs/$runId/graders/$graderGroupId/substrate.db")
    }

    /**
     * Returns the run-global Atlas authority database.
     *
     * Unlike group substrates, this database is never mounted into an ordinary agent context.
     * All ownership and disclosure writes are mediated by the Atlas transaction repository.
     *
     * @param runId Run whose Atlas authority should be opened.
     * @return Open Atlas authority database.
     */
    fun getAtlas(runId: String): EvoMoliorDatabase {
        val key = "$runId/atlas-authority"
        synchronized(openDatabases) {
            return openDatabases.getOrPut(key) {
                createAt(atlasDbFilePath(runId))
            }
        }
    }

    /** Returns the run-global Atlas authority database path. */
    fun atlasDbFilePath(runId: String): File =
        File(dataRootDir, "evomolior/runs/$runId/control/atlas/substrate.db")

    private fun createGrader(runId: String, graderGroupId: String): EvoMoliorDatabase {
        val dbFile = graderDbFilePath(runId, graderGroupId)
        dbFile.parentFile?.mkdirs()
        return Room.databaseBuilder<EvoMoliorDatabase>(
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            // Allow Room to wipe and recreate whenever no migration path exists (both upgrade
            // and downgrade). EvoMolior DBs are ephemeral per-run stores — data loss is
            // acceptable and far preferable to crashing with a migration exception.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    private fun create(runId: String, groupId: String): EvoMoliorDatabase {
        return createAt(dbFilePath(runId, groupId))
    }

    private fun createAt(dbFile: File): EvoMoliorDatabase {
        dbFile.parentFile?.mkdirs()

        return Room.databaseBuilder<EvoMoliorDatabase>(
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            // Allow Room to wipe and recreate whenever no migration path exists (both upgrade
            // and downgrade). EvoMolior DBs are ephemeral per-run stores — data loss is
            // acceptable and far preferable to crashing with a migration exception.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
