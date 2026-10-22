package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity

/** DAO for the meta-archive — cross-run artefact persistence. */
@Dao
interface MetaArchiveDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MetaArchiveEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MetaArchiveEntryEntity>)

    /** Inserts immutable completion authority without replacing an existing identity. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImmutable(entry: MetaArchiveEntryEntity): Long

    /** Returns one exact archive entry by stable identity. */
    @Query("SELECT * FROM meta_archive WHERE id = :id")
    suspend fun getById(id: String): MetaArchiveEntryEntity?

    /** Replaces the singleton run-level entry for [entry.entryType] atomically. */
    @Transaction
    suspend fun replaceRunSingleton(entry: MetaArchiveEntryEntity) {
        deleteByRunAndType(entry.runId, entry.entryType)
        insert(entry)
    }

    /** All archive entries for a specific run, ordered by archived time. */
    @Query("SELECT * FROM meta_archive WHERE runId = :runId ORDER BY archivedAtMs ASC")
    suspend fun getByRun(runId: String): List<MetaArchiveEntryEntity>

    /** Archive entries of a specific type across all runs. */
    @Query("SELECT * FROM meta_archive WHERE entryType = :type ORDER BY archivedAtMs DESC")
    suspend fun getByType(type: String): List<MetaArchiveEntryEntity>

    /** Only winner entries — useful for Council learning. */
    @Query("SELECT * FROM meta_archive WHERE isFromWinner = 1 ORDER BY archivedAtMs DESC LIMIT :limit")
    suspend fun getWinnerEntries(limit: Int): List<MetaArchiveEntryEntity>

    /** Entries relevant to a given objective keyword (simple LIKE search). */
    @Query("SELECT * FROM meta_archive WHERE runObjective LIKE '%' || :keyword || '%' ORDER BY archivedAtMs DESC")
    suspend fun searchByObjective(keyword: String): List<MetaArchiveEntryEntity>

    /** Total count of archived entries. */
    @Query("SELECT COUNT(*) FROM meta_archive")
    suspend fun count(): Int

    /** Delete all archive entries for a run (used during cleanup). */
    @Query("DELETE FROM meta_archive WHERE runId = :runId")
    suspend fun deleteByRun(runId: String)

    /** Deletes every entry of one type for a run before a canonical singleton is inserted. */
    @Query("DELETE FROM meta_archive WHERE runId = :runId AND entryType = :type")
    suspend fun deleteByRunAndType(runId: String, type: String)

    /** All entries across all runs, paged — for the Meta-Archive tab. */
    @Query("SELECT * FROM meta_archive ORDER BY archivedAtMs DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(limit: Int, offset: Int): List<MetaArchiveEntryEntity>
}
