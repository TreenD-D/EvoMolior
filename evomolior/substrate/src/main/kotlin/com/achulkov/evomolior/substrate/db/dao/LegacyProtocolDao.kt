package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evomolior.substrate.db.entity.LegacyProtocolEntity
import kotlinx.coroutines.flow.Flow

/** Room DAO for [LegacyProtocolEntity]. */
@Dao
interface LegacyProtocolDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(protocol: LegacyProtocolEntity)

    /** Latest non-expired protocol for a role. */
    @Query("""
        SELECT * FROM legacy_protocols 
        WHERE runId = :runId AND groupId = :groupId AND role = :role 
          AND expiresAtGeneration > :currentGeneration
        ORDER BY extractedFromGeneration DESC
        LIMIT 1
    """)
    suspend fun getLatestForRole(runId: String, groupId: String, role: String, currentGeneration: Int): LegacyProtocolEntity?

    @Query("""
        SELECT * FROM legacy_protocols 
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY extractedFromGeneration DESC
    """)
    fun observeAll(runId: String, groupId: String): Flow<List<LegacyProtocolEntity>>

    @Query("""
        SELECT * FROM legacy_protocols 
        WHERE runId = :runId AND groupId = :groupId AND role = :role
        ORDER BY extractedFromGeneration DESC
    """)
    suspend fun getAllForRole(runId: String, groupId: String, role: String): List<LegacyProtocolEntity>

    /** Expired protocols that need failure maps promoted to Dead Ends. */
    @Query("""
        SELECT * FROM legacy_protocols 
        WHERE runId = :runId AND groupId = :groupId AND expiresAtGeneration <= :currentGeneration
    """)
    suspend fun getExpired(runId: String, groupId: String, currentGeneration: Int): List<LegacyProtocolEntity>

    /** All non-expired (active) protocols — used for meta-archive export. */
    @Query("""
        SELECT * FROM legacy_protocols
        WHERE runId = :runId AND groupId = :groupId AND expiresAtGeneration > :currentGeneration
        ORDER BY extractedFromGeneration DESC
    """)
    suspend fun getActive(runId: String, groupId: String, currentGeneration: Int): List<LegacyProtocolEntity>
}
