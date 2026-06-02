package com.achulkov.evokammer.substrate.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.achulkov.evokammer.substrate.db.dao.DeadEndsDao
import com.achulkov.evokammer.substrate.db.dao.EventLogDao
import com.achulkov.evokammer.substrate.db.dao.KGSchemaDao
import com.achulkov.evokammer.substrate.db.dao.KgSchemaConstraintDao
import com.achulkov.evokammer.substrate.db.dao.KnowledgeBaseDao
import com.achulkov.evokammer.substrate.db.dao.KnowledgeGraphFactDao
import com.achulkov.evokammer.substrate.db.dao.LegacyProtocolDao
import com.achulkov.evokammer.substrate.db.dao.MetaArchiveDao
import com.achulkov.evokammer.substrate.db.dao.PlanRegistryDao
import com.achulkov.evokammer.substrate.db.dao.RunConfigDao
import com.achulkov.evokammer.substrate.db.dao.SealedTestSuiteDao
import com.achulkov.evokammer.substrate.db.dao.StagingDao
import com.achulkov.evokammer.substrate.db.dao.ToolRegistryDao
import com.achulkov.evokammer.substrate.db.dao.TrustScoreDao
import com.achulkov.evokammer.substrate.db.dao.WipTaskDao
import com.achulkov.evokammer.substrate.db.entity.DeadEndsEntity
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.KGSchemaEntity
import com.achulkov.evokammer.substrate.db.entity.KgSchemaConstraintEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evokammer.substrate.db.entity.LegacyProtocolEntity
import com.achulkov.evokammer.substrate.db.entity.MetaArchiveEntryEntity
import com.achulkov.evokammer.substrate.db.entity.RunConfigEntity
import com.achulkov.evokammer.substrate.db.entity.SealedTestSuiteEntity
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evokammer.substrate.db.entity.TrustScoreEntity
import com.achulkov.evokammer.substrate.db.entity.WipTaskEntity

/**
 * Per-group Room database for EvoKammer substrate storage.
 *
 * One instance is created per (runId, groupId) pair. DB file path:
 * `{appDataDir}/evokammer/runs/{runId}/groups/{groupId}/substrate.db`
 *
 * The global [RunConfigDao] is also accessible here — `run_configs` is read/written by the
 * LifecycleManager and the desktop control panel.
 *
 * Created via [SubstrateDatabaseFactory].
 *
 * @see SubstrateDatabaseFactory
 */
@Database(
    entities = [
        KnowledgeBaseEntryEntity::class,
        KnowledgeGraphFactEntity::class,
        ToolRegistryEntryEntity::class,
        StagingSubmissionEntity::class,
        WipTaskEntity::class,
        EventLogEntryEntity::class,
        LegacyProtocolEntity::class,
        TrustScoreEntity::class,
        DeadEndsEntity::class,
        KGSchemaEntity::class,
        KgSchemaConstraintEntity::class,
        MetaArchiveEntryEntity::class,
        SealedTestSuiteEntity::class,
        RunConfigEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class EvoKammerDatabase : RoomDatabase() {
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao
    abstract fun knowledgeGraphFactDao(): KnowledgeGraphFactDao
    abstract fun stagingDao(): StagingDao
    abstract fun wipTaskDao(): WipTaskDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun legacyProtocolDao(): LegacyProtocolDao
    abstract fun toolRegistryDao(): ToolRegistryDao
    abstract fun trustScoreDao(): TrustScoreDao
    abstract fun deadEndsDao(): DeadEndsDao
    abstract fun kgSchemaDao(): KGSchemaDao
    abstract fun kgSchemaConstraintDao(): KgSchemaConstraintDao
    abstract fun metaArchiveDao(): MetaArchiveDao
    abstract fun planRegistryDao(): PlanRegistryDao
    abstract fun sealedTestSuiteDao(): SealedTestSuiteDao
    abstract fun runConfigDao(): RunConfigDao
}
