package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.achulkov.evomolior.substrate.db.entity.SimulationRunRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ToolExecutionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ToolFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ToolLifecycleEventEntity
import com.achulkov.evomolior.substrate.db.entity.ToolOpportunityCommitmentEntity
import com.achulkov.evomolior.substrate.db.entity.ToolReuseLineageEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity

@Dao
interface ToolInfrastructureDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOpportunity(value: ToolOpportunityCommitmentEntity)

    @Query("SELECT * FROM tool_opportunity_commitments WHERE opportunityId = :opportunityId")
    suspend fun opportunity(opportunityId: String): ToolOpportunityCommitmentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLifecycle(value: ToolLifecycleEventEntity)

    @Query("SELECT * FROM tool_lifecycle_events WHERE eventId = :eventId")
    suspend fun lifecycleEvent(eventId: String): ToolLifecycleEventEntity?

    @Query(
        """
        SELECT * FROM tool_lifecycle_events
        WHERE toolVersionId = :toolVersionId
        ORDER BY sequence ASC
        """
    )
    suspend fun lifecycle(toolVersionId: String): List<ToolLifecycleEventEntity>

    @Query(
        """
        SELECT * FROM tool_lifecycle_events
        WHERE toolVersionId = :toolVersionId
        ORDER BY sequence DESC
        LIMIT 1
        """
    )
    suspend fun latestLifecycle(toolVersionId: String): ToolLifecycleEventEntity?

    @Query(
        """
        SELECT COUNT(DISTINCT toolVersionId) FROM tool_lifecycle_events
        WHERE substr(toolVersionId, 1, length(:versionPrefix)) = :versionPrefix
        """
    )
    suspend fun lifecycleVersionCount(versionPrefix: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRegistryVersion(value: ToolRegistryEntryEntity)

    @Query("UPDATE tool_registry_entries SET status = 'archived' WHERE id = :toolVersionId AND status = 'active'")
    suspend fun archiveActiveRegistryVersion(toolVersionId: String): Int

    @Query(
        """
        SELECT DISTINCT registry.* FROM tool_registry_entries AS registry
        INNER JOIN tool_lifecycle_events AS lifecycle
          ON lifecycle.toolVersionId = registry.id
        WHERE registry.runId = :runId
          AND registry.groupId = :groupId
          AND registry.status = 'active'
          AND lifecycle.opportunityId = :opportunityId
        ORDER BY registry.createdAtMs ASC, registry.id ASC
        """
    )
    suspend fun activeVersionsForOpportunity(
        runId: String,
        groupId: String,
        opportunityId: String
    ): List<ToolRegistryEntryEntity>

    @Query(
        """
        SELECT COUNT(*) FROM tool_lifecycle_events
        WHERE toolVersionId = :toolVersionId
          AND opportunityId = :opportunityId
          AND state = 'PROMOTED'
        """
    )
    suspend fun promotedLifecycleCountForOpportunity(
        toolVersionId: String,
        opportunityId: String
    ): Int

    @Transaction
    suspend fun activatePromotedVersion(
        opportunityId: String,
        expectedPreviousActiveVersionId: String?,
        expectedPreviousParentEventHash: String?,
        nextVersion: ToolRegistryEntryEntity,
        promotedEvent: ToolLifecycleEventEntity,
        parentTransitionEvent: ToolLifecycleEventEntity?
    ) {
        check(
            promotedEvent.opportunityId == opportunityId &&
                promotedEvent.toolVersionId == nextVersion.id &&
                promotedEvent.runId == nextVersion.runId &&
                promotedEvent.groupId == nextVersion.groupId &&
                promotedEvent.state == "PROMOTED"
        ) {
            "Promoted lifecycle does not match opportunity-scoped registry activation"
        }
        val activeVersions = activeVersionsForOpportunity(
            runId = nextVersion.runId,
            groupId = nextVersion.groupId,
            opportunityId = opportunityId
        )
        check(activeVersions.size <= 1) {
            "Council opportunity already has multiple active Atlas-bound versions: " +
                activeVersions.joinToString(",") { it.id }.take(400)
        }
        val actualPreviousActiveVersionId = activeVersions.singleOrNull()?.id
        check(actualPreviousActiveVersionId == expectedPreviousActiveVersionId) {
            "Active Atlas-bound tool version changed before opportunity-scoped activation"
        }
        if (actualPreviousActiveVersionId != null) {
            val previous = checkNotNull(latestLifecycle(actualPreviousActiveVersionId)) {
                "Active opportunity version lacks lifecycle authority"
            }
            check(
                previous.opportunityId == opportunityId &&
                    previous.state !in setOf("MAINTAINED", "FORKED", "RETIRED") &&
                    promotedLifecycleCountForOpportunity(
                        actualPreviousActiveVersionId,
                        opportunityId
                    ) > 0
            ) {
                "Active opportunity version lacks non-terminal promoted authority"
            }
            val transition = checkNotNull(parentTransitionEvent) {
                "Opportunity replacement requires typed parent lifecycle authority"
            }
            check(
                expectedPreviousParentEventHash == previous.eventHash &&
                    transition.toolVersionId == actualPreviousActiveVersionId &&
                    transition.opportunityId == opportunityId &&
                    transition.runId == nextVersion.runId &&
                    transition.groupId == nextVersion.groupId &&
                    transition.state in setOf("MAINTAINED", "FORKED") &&
                    transition.sequence == previous.sequence + 1
            ) {
                "Parent lifecycle changed before opportunity-scoped activation"
            }
            check(archiveActiveRegistryVersion(actualPreviousActiveVersionId) == 1) {
                "Previous active tool version changed before promotion"
            }
        } else {
            check(parentTransitionEvent == null && expectedPreviousParentEventHash == null) {
                "Initial opportunity activation cannot carry parent transition authority"
            }
        }
        insertRegistryVersion(nextVersion)
        insertLifecycle(promotedEvent)
        if (parentTransitionEvent != null) {
            insertLifecycle(parentTransitionEvent)
        }
    }

    @Transaction
    suspend fun archiveVersionWithLifecycle(
        toolVersionId: String,
        lifecycleEvent: ToolLifecycleEventEntity,
        expectedPreviousEventHash: String,
        expectedState: String
    ) {
        val previous = checkNotNull(latestLifecycle(toolVersionId)) {
            "Retirement requires existing lifecycle authority"
        }
        check(previous.eventHash == expectedPreviousEventHash) {
            "Tool lifecycle changed before retirement"
        }
        check(
            lifecycleEvent.toolVersionId == toolVersionId &&
                lifecycleEvent.state == expectedState &&
                expectedState in setOf("FORKED", "RETIRED") &&
                lifecycleEvent.sequence == previous.sequence + 1
        ) { "Archival lifecycle authority does not match the registry version" }
        check(archiveActiveRegistryVersion(toolVersionId) == 1) {
            "Only an active immutable version can be retired"
        }
        insertLifecycle(lifecycleEvent)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExecution(value: ToolExecutionRecordEntity)

    @Transaction
    suspend fun insertSuccessfulExecutionAndLifecycle(
        execution: ToolExecutionRecordEntity,
        lifecycleEvent: ToolLifecycleEventEntity,
        expectedPreviousEventHash: String
    ) {
        val previous = checkNotNull(latestLifecycle(execution.toolVersionId)) {
            "Successful execution requires existing lifecycle authority"
        }
        check(previous.eventHash == expectedPreviousEventHash) {
            "Tool lifecycle changed before successful execution commit"
        }
        check(
            execution.result == "SUCCEEDED" &&
                execution.purpose == "AGENT_USE" &&
                lifecycleEvent.toolVersionId == execution.toolVersionId &&
                lifecycleEvent.contextNodeId == execution.nodeId &&
                lifecycleEvent.state == "EXECUTED_BY_LATER_AGENT" &&
                lifecycleEvent.sequence == previous.sequence + 1
        ) { "Successful execution and lifecycle authority do not match" }
        insertExecution(execution)
        insertLifecycle(lifecycleEvent)
    }

    @Query("SELECT * FROM tool_execution_records WHERE executionId = :executionId")
    suspend fun execution(executionId: String): ToolExecutionRecordEntity?

    @Query(
        """
        SELECT * FROM tool_execution_records
        WHERE toolVersionId = :toolVersionId
        ORDER BY round ASC, executionId ASC
        """
    )
    suspend fun executions(toolVersionId: String): List<ToolExecutionRecordEntity>

    @Query(
        """
        SELECT * FROM tool_execution_records
        WHERE runId = :runId
          AND groupId = :groupId
          AND round <= :maximumRound
        ORDER BY round ASC, executionId ASC
        """
    )
    suspend fun executionsForGroupThroughRound(
        runId: String,
        groupId: String,
        maximumRound: Int
    ): List<ToolExecutionRecordEntity>

    @Query(
        """
        SELECT * FROM tool_execution_records
        WHERE runId = :runId
          AND groupId = :groupId
          AND agentId = :agentId
          AND round = :round
          AND nodeId = :nodeId
        ORDER BY executionId ASC
        """
    )
    suspend fun executionsForAgentNode(
        runId: String,
        groupId: String,
        agentId: String,
        round: Int,
        nodeId: String
    ): List<ToolExecutionRecordEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSimulation(value: SimulationRunRecordEntity)

    @Query("SELECT * FROM simulation_run_records WHERE simulationRunId = :simulationRunId")
    suspend fun simulation(simulationRunId: String): SimulationRunRecordEntity?

    @Query(
        """
        SELECT simulation.* FROM simulation_run_records AS simulation
        INNER JOIN tool_execution_records AS execution
          ON execution.executionId = simulation.executionId
        WHERE execution.runId = :runId
        ORDER BY execution.round ASC, simulation.simulationRunId ASC
        """
    )
    suspend fun simulationsForRun(runId: String): List<SimulationRunRecordEntity>

    @Query("SELECT * FROM simulation_run_records WHERE executionId = :executionId")
    suspend fun simulationForExecution(executionId: String): SimulationRunRecordEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReuse(value: ToolReuseLineageEntity)

    @Transaction
    suspend fun insertReuseAndLifecycle(
        reuse: ToolReuseLineageEntity,
        lifecycleEvent: ToolLifecycleEventEntity,
        expectedPreviousEventHash: String
    ) {
        val previous = checkNotNull(latestLifecycle(reuse.toolVersionId)) {
            "Tool reuse requires existing lifecycle authority"
        }
        check(previous.eventHash == expectedPreviousEventHash) {
            "Tool lifecycle changed before reuse commit"
        }
        check(
            lifecycleEvent.toolVersionId == reuse.toolVersionId &&
                lifecycleEvent.contextNodeId == reuse.reusedOnNodeId &&
                lifecycleEvent.state == "RESULT_REFERENCED_BY_NODE" &&
                lifecycleEvent.sequence == previous.sequence + 1
        ) { "Tool reuse and result-reference lifecycle authority do not match" }
        insertReuse(reuse)
        insertLifecycle(lifecycleEvent)
    }

    @Query("SELECT * FROM tool_reuse_lineage WHERE lineageId = :lineageId")
    suspend fun reuse(lineageId: String): ToolReuseLineageEntity?

    @Query(
        """
        SELECT * FROM tool_reuse_lineage
        WHERE toolVersionId = :toolVersionId
        ORDER BY reusedOnNodeId ASC, lineageId ASC
        """
    )
    suspend fun reuseForVersion(toolVersionId: String): List<ToolReuseLineageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFitness(value: ToolFitnessRecordEntity)

    @Query("SELECT * FROM tool_fitness_records WHERE recordId = :recordId")
    suspend fun fitness(recordId: String): ToolFitnessRecordEntity?

    @Query(
        """
        SELECT * FROM tool_fitness_records
        WHERE toolVersionId = :toolVersionId
        ORDER BY throughRound ASC
        """
    )
    suspend fun fitnessForVersion(toolVersionId: String): List<ToolFitnessRecordEntity>

    @Query("SELECT COUNT(*) FROM tool_opportunity_commitments WHERE runId = :runId")
    suspend fun opportunityCount(runId: String): Int

    @Query(
        "SELECT COUNT(*) FROM tool_lifecycle_events WHERE runId = :runId AND state = :state"
    )
    suspend fun lifecycleStateCount(runId: String, state: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM tool_execution_records
        WHERE runId = :runId AND purpose = :purpose
          AND (:result IS NULL OR result = :result)
        """
    )
    suspend fun executionCount(runId: String, purpose: String, result: String?): Int

    @Query(
        """
        SELECT COUNT(*) FROM simulation_run_records simulation
        INNER JOIN tool_execution_records execution
          ON execution.executionId = simulation.executionId
        WHERE execution.runId = :runId
        """
    )
    suspend fun simulationCount(runId: String): Int

    @Query("SELECT COUNT(*) FROM tool_reuse_lineage WHERE runId = :runId")
    suspend fun reuseCount(runId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM tool_reuse_lineage reuse
        INNER JOIN simulation_run_records simulation
          ON simulation.executionId = reuse.executionId
        WHERE reuse.runId = :runId
        """
    )
    suspend fun simulationReuseCount(runId: String): Int

    @Query(
        """
        SELECT COUNT(DISTINCT toolVersionId) FROM tool_fitness_records
        WHERE runId = :runId
        """
    )
    suspend fun fitnessVersionCount(runId: String): Int
}
