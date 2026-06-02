package com.achulkov.evokammer.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import kotlinx.coroutines.flow.Flow

/** Room DAO for [ToolRegistryEntryEntity]. */
@Dao
interface ToolRegistryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tool: ToolRegistryEntryEntity)

    @Update
    suspend fun update(tool: ToolRegistryEntryEntity)

    @Query("SELECT * FROM tool_registry_entries WHERE id = :id")
    suspend fun getById(id: String): ToolRegistryEntryEntity?

    /** Active tools by domain. */
    @Query("""
        SELECT * FROM tool_registry_entries 
        WHERE runId = :runId AND groupId = :groupId AND status = 'active'
          AND (:domain IS NULL OR domain = :domain)
        ORDER BY successRate DESC
    """)
    suspend fun getActive(runId: String, groupId: String, domain: String?): List<ToolRegistryEntryEntity>

    /** Get latest version of a tool by name. */
    @Query("""
        SELECT * FROM tool_registry_entries 
        WHERE runId = :runId AND groupId = :groupId AND name = :name AND status = 'active'
        ORDER BY version DESC LIMIT 1
    """)
    suspend fun getLatestByName(runId: String, groupId: String, name: String): ToolRegistryEntryEntity?

    /** Get the highest version of a tool by name, regardless of status. */
    @Query("""
        SELECT * FROM tool_registry_entries
        WHERE runId = :runId AND groupId = :groupId AND name = :name
        ORDER BY version DESC LIMIT 1
    """)
    suspend fun getLatestVersionByName(runId: String, groupId: String, name: String): ToolRegistryEntryEntity?

    @Query("""
        SELECT * FROM tool_registry_entries 
        WHERE runId = :runId AND groupId = :groupId AND status = 'active'
    """)
    fun observeActive(runId: String, groupId: String): Flow<List<ToolRegistryEntryEntity>>

    /** Update success rate and failure log after tool execution. */
    @Query("""
        UPDATE tool_registry_entries 
        SET successRate = :newSuccessRate, failureLogJson = :newFailureLogJson 
        WHERE id = :id
    """)
    suspend fun updateExecutionStats(id: String, newSuccessRate: Double, newFailureLogJson: String)

    /** Average tool failure rate (for Evolutionary Selector). */
    @Query("""
        SELECT AVG(1.0 - successRate) FROM tool_registry_entries 
        WHERE runId = :runId AND groupId = :groupId AND status = 'active'
    """)
    suspend fun getAverageFailureRate(runId: String, groupId: String): Double?

    /** Tools adopted by 2+ distinct agents (for fitness scoring). */
    @Query("""
        SELECT tool_name FROM (
            SELECT JSON_EXTRACT(payloadJson, '$.toolName') as tool_name,
                   COUNT(DISTINCT agentId) as adopter_count
            FROM event_log_entries 
            WHERE runId = :runId AND groupId = :groupId AND eventType = 'TOOL_EXEC'
            GROUP BY tool_name
        ) WHERE adopter_count >= 2
    """)
    suspend fun getAdoptedToolNames(runId: String, groupId: String): List<String>

    /** Count tools authored by [authorAgentId] that have been adopted by 2+ other agents. */
    @Query("""
        SELECT COUNT(*) FROM tool_registry_entries t
        WHERE t.runId = :runId AND t.groupId = :groupId AND t.authorAgentId = :authorAgentId
          AND t.name IN (
            SELECT JSON_EXTRACT(payloadJson, '$.toolName')
            FROM event_log_entries 
            WHERE runId = :runId AND groupId = :groupId AND eventType = 'TOOL_EXEC'
              AND agentId != :authorAgentId
            GROUP BY JSON_EXTRACT(payloadJson, '$.toolName')
            HAVING COUNT(DISTINCT agentId) >= 2
          )
    """)
    suspend fun getAdoptedToolCountForAgent(runId: String, groupId: String, authorAgentId: String): Int
}
