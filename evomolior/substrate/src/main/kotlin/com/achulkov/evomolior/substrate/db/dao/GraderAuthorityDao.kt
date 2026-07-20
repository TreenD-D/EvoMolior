package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.achulkov.evomolior.substrate.db.entity.EvaluationIndependenceProfileEntity
import com.achulkov.evomolior.substrate.db.entity.GraderAtlasVersionEntity
import com.achulkov.evomolior.substrate.db.entity.GraderCriteriaFreezeEntity
import com.achulkov.evomolior.substrate.db.entity.GraderFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.GraderNodeLeaseEntity
import com.achulkov.evomolior.substrate.db.entity.GraderNodeStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.GraderScoreBundleEntity
import com.achulkov.evomolior.substrate.db.entity.GradingRequirementEntity
import com.achulkov.evomolior.substrate.db.entity.HiddenTestDefinitionEntity

/** Internal append-only persistence boundary for competitive grader authority. */
@Dao
internal interface GraderAuthorityDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAtlasVersion(entity: GraderAtlasVersionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStateEvent(entity: GraderNodeStateEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLease(entity: GraderNodeLeaseEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRequirement(entity: GradingRequirementEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHiddenTest(entity: HiddenTestDefinitionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCriteriaFreeze(entity: GraderCriteriaFreezeEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertScoreBundle(entity: GraderScoreBundleEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFitnessRecord(entity: GraderFitnessRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIndependenceProfile(entity: EvaluationIndependenceProfileEntity): Long

    @Transaction
    suspend fun insertCriteriaBatch(
        requirements: List<GradingRequirementEntity>,
        hiddenTests: List<HiddenTestDefinitionEntity>
    ) {
        requirements.forEach { insertRequirement(it) }
        hiddenTests.forEach { insertHiddenTest(it) }
    }

    @Query(
        """
        SELECT * FROM grader_atlas_versions
        WHERE graderAtlasId = :graderAtlasId
        ORDER BY version DESC LIMIT 1
        """
    )
    suspend fun latestAtlas(graderAtlasId: String): GraderAtlasVersionEntity?

    @Query(
        """
        SELECT * FROM grader_atlas_versions
        WHERE runId = :runId
        ORDER BY version DESC, createdAtMs DESC LIMIT 1
        """
    )
    suspend fun latestAtlasForRun(runId: String): GraderAtlasVersionEntity?

    @Query(
        "SELECT * FROM grader_atlas_versions WHERE graderAtlasId = :graderAtlasId AND version = :version"
    )
    suspend fun atlasVersion(graderAtlasId: String, version: Int): GraderAtlasVersionEntity?

    @Query("SELECT * FROM grader_atlas_versions WHERE atlasHash = :atlasHash LIMIT 1")
    suspend fun atlasByHash(atlasHash: String): GraderAtlasVersionEntity?

    @Query("SELECT * FROM grader_node_state_events WHERE eventId = :eventId LIMIT 1")
    suspend fun stateById(eventId: String): GraderNodeStateEventEntity?

    @Query("SELECT * FROM grader_node_state_events WHERE stateHash = :stateHash LIMIT 1")
    suspend fun stateByHash(stateHash: String): GraderNodeStateEventEntity?

    @Query(
        """
        SELECT * FROM grader_node_state_events
        WHERE runId = :runId AND graderAtlasHash = :graderAtlasHash
            AND graderGroupId = :graderGroupId AND nodeId = :nodeId
        ORDER BY revision DESC LIMIT 1
        """
    )
    suspend fun latestState(
        runId: String,
        graderAtlasHash: String,
        graderGroupId: String,
        nodeId: String
    ): GraderNodeStateEventEntity?

    @Query(
        """
        SELECT * FROM grader_node_state_events
        WHERE runId = :runId AND graderAtlasHash = :graderAtlasHash AND nodeId = :nodeId
        ORDER BY createdAtMs DESC, revision DESC LIMIT 1
        """
    )
    suspend fun latestStateAcrossGroups(
        runId: String,
        graderAtlasHash: String,
        nodeId: String
    ): GraderNodeStateEventEntity?

    @Query("SELECT * FROM grader_node_leases WHERE leaseId = :leaseId LIMIT 1")
    suspend fun leaseById(leaseId: String): GraderNodeLeaseEntity?

    @Query("SELECT * FROM grader_node_leases WHERE leaseHash = :leaseHash LIMIT 1")
    suspend fun leaseByHash(leaseHash: String): GraderNodeLeaseEntity?

    @Query("SELECT * FROM grading_requirements WHERE requirementId = :requirementId LIMIT 1")
    suspend fun requirementById(requirementId: String): GradingRequirementEntity?

    @Query("SELECT * FROM grading_requirements WHERE requirementHash = :requirementHash LIMIT 1")
    suspend fun requirementByHash(requirementHash: String): GradingRequirementEntity?

    @Query(
        """
        SELECT * FROM grading_requirements
        WHERE runId = :runId AND round = :round AND graderGroupId = :graderGroupId
        ORDER BY requirementId ASC
        """
    )
    suspend fun requirementsForRound(
        runId: String,
        round: Int,
        graderGroupId: String
    ): List<GradingRequirementEntity>

    @Query(
        """
        SELECT * FROM grading_requirements
        WHERE runId = :runId
        ORDER BY round ASC, graderGroupId ASC, requirementId ASC
        """
    )
    suspend fun requirementsForRun(runId: String): List<GradingRequirementEntity>

    @Query("SELECT * FROM hidden_test_definitions WHERE testId = :testId LIMIT 1")
    suspend fun hiddenTestById(testId: String): HiddenTestDefinitionEntity?

    @Query("SELECT * FROM hidden_test_definitions WHERE testHash = :testHash LIMIT 1")
    suspend fun hiddenTestByHash(testHash: String): HiddenTestDefinitionEntity?

    @Query(
        """
        SELECT * FROM hidden_test_definitions
        WHERE runId = :runId AND graderGroupId = :graderGroupId AND roundCreated <= :round
        ORDER BY roundCreated ASC, testId ASC
        """
    )
    suspend fun hiddenTestsForRound(
        runId: String,
        graderGroupId: String,
        round: Int
    ): List<HiddenTestDefinitionEntity>

    @Query(
        """
        SELECT * FROM hidden_test_definitions
        WHERE runId = :runId AND graderGroupId = :graderGroupId AND testHash IN (:testHashes)
        ORDER BY testId ASC
        """
    )
    suspend fun hiddenTestsByHashes(
        runId: String,
        graderGroupId: String,
        testHashes: Set<String>
    ): List<HiddenTestDefinitionEntity>

    @Query("SELECT * FROM grader_criteria_freezes WHERE freezeId = :freezeId LIMIT 1")
    suspend fun freezeById(freezeId: String): GraderCriteriaFreezeEntity?

    @Query("SELECT * FROM grader_criteria_freezes WHERE freezeHash = :freezeHash LIMIT 1")
    suspend fun freezeByHash(freezeHash: String): GraderCriteriaFreezeEntity?

    @Query(
        """
        SELECT * FROM grader_criteria_freezes
        WHERE runId = :runId AND round = :round AND graderGroupId = :graderGroupId
        LIMIT 1
        """
    )
    suspend fun freezeForRound(
        runId: String,
        round: Int,
        graderGroupId: String
    ): GraderCriteriaFreezeEntity?

    @Query(
        """
        SELECT * FROM grader_criteria_freezes
        WHERE runId = :runId
        ORDER BY round ASC, graderGroupId ASC, freezeId ASC
        """
    )
    suspend fun criteriaFreezesForRun(runId: String): List<GraderCriteriaFreezeEntity>

    @Query("SELECT * FROM grader_score_bundles WHERE bundleId = :bundleId LIMIT 1")
    suspend fun scoreBundleById(bundleId: String): GraderScoreBundleEntity?

    @Query("SELECT * FROM grader_score_bundles WHERE payloadHash = :payloadHash LIMIT 1")
    suspend fun scoreBundleByHash(payloadHash: String): GraderScoreBundleEntity?

    @Query(
        """
        SELECT * FROM grader_score_bundles
        WHERE runId = :runId AND round = :round
        ORDER BY graderGroupId ASC, targetGroupId ASC
        """
    )
    suspend fun scoreBundlesForRound(runId: String, round: Int): List<GraderScoreBundleEntity>

    @Query(
        """
        SELECT * FROM grader_score_bundles
        WHERE runId = :runId
        ORDER BY round ASC, graderGroupId ASC, targetGroupId ASC, bundleId ASC
        """
    )
    suspend fun scoreBundlesForRun(runId: String): List<GraderScoreBundleEntity>

    @Query("SELECT * FROM grader_fitness_records WHERE recordId = :recordId LIMIT 1")
    suspend fun fitnessById(recordId: String): GraderFitnessRecordEntity?

    @Query("SELECT * FROM grader_fitness_records WHERE recordHash = :recordHash LIMIT 1")
    suspend fun fitnessByHash(recordHash: String): GraderFitnessRecordEntity?

    @Query(
        """
        SELECT * FROM grader_fitness_records
        WHERE runId = :runId AND graderGroupId = :graderGroupId
        ORDER BY round ASC, plane ASC, recordId ASC
        """
    )
    suspend fun fitnessHistory(
        runId: String,
        graderGroupId: String
    ): List<GraderFitnessRecordEntity>

    @Query(
        """
        SELECT * FROM grader_fitness_records
        WHERE runId = :runId
        ORDER BY round ASC, graderGroupId ASC, plane ASC, recordId ASC
        """
    )
    suspend fun fitnessHistoryForRun(runId: String): List<GraderFitnessRecordEntity>

    @Query(
        """
        SELECT * FROM evaluation_independence_profiles
        WHERE runId = :runId
        ORDER BY createdAtMs DESC, profileId DESC LIMIT 1
        """
    )
    suspend fun latestIndependenceProfile(
        runId: String
    ): EvaluationIndependenceProfileEntity?
}
