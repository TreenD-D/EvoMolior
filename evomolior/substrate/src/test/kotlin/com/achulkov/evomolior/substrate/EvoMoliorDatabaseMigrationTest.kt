package com.achulkov.evomolior.substrate

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.GraderNodeStateEventEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies checked-in historical schemas upgrade through Room's generated migrations.
 */
class EvoMoliorDatabaseMigrationTest {

    @Test
    fun `v19 to v20 migration exposes portfolios and KG fact attempt authority`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v19-v20-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersionFixture(databaseFile, 19)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            assertTrue(database.workerExecutionDao().nodePortfolios("run", "group").isEmpty())
            assertTrue(database.kgFactAttemptDao().forGroup("run", "group").isEmpty())
        } finally {
            database.close()
        }

        val tableNames = loadSchema(20)
            .getValue("entities")
            .jsonArray
            .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
            .toSet()
        assertTrue("node_portfolios" in tableNames)
        assertTrue("kg_fact_attempts" in tableNames)
    }

    @Test
    fun `v18 to v19 migration scopes grader node state by grader group`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v18-v19-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersionFixture(databaseFile, 18)
        BundledSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
            connection.prepare(
                """
                INSERT INTO grader_node_state_events(
                    eventId, runId, graderAtlasHash, nodeId, revision, status,
                    stateHash, contentJson, createdAtMs
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.bindText(1, "legacy-state")
                statement.bindText(2, "run")
                statement.bindText(3, "atlas")
                statement.bindText(4, "node")
                statement.bindLong(5, 0)
                statement.bindText(6, "OPEN")
                statement.bindText(7, "legacy-hash")
                statement.bindText(8, "{}")
                statement.bindLong(9, 1)
                statement.step()
            }
        }

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val dao = database.graderAuthorityDao()
            val migrated = dao.latestState("run", "atlas", "", "node")
            assertNotNull(migrated)
            assertEquals("legacy-state", migrated.eventId)
            assertEquals("", migrated.graderGroupId)

            val groupState = GraderNodeStateEventEntity(
                eventId = "group-state",
                runId = "run",
                graderAtlasHash = "atlas",
                graderGroupId = "grader-a",
                nodeId = "node",
                revision = 0,
                status = "OPEN",
                stateHash = "group-hash",
                contentJson = "{}",
                createdAtMs = 2
            )
            assertTrue(dao.insertStateEvent(groupState) > 0)
            assertEquals(groupState, dao.latestState("run", "atlas", "grader-a", "node"))
            assertEquals(migrated, dao.latestState("run", "atlas", "", "node"))
        } finally {
            database.close()
        }
    }

    @Test
    fun `v17 to v18 migration exposes final output and score lineage authority`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v17-v18-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersionFixture(databaseFile, 17)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val dao = database.contributionGraphDao()
            assertNull(dao.finalDeliverableReferenceById("missing-reference"))
            assertNull(dao.receiverScoreContributionById("missing-receiver-score"))
            assertNull(dao.benchmarkScoreContributionById("missing-benchmark-score"))
            assertNull(dao.controlInterventionById("missing-control-intervention"))
            assertNull(dao.summaryValidationById("missing-summary-validation"))
        } finally {
            database.close()
        }
    }

    @Test
    fun `v16 to v17 migration exposes publication state authority`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v16-v17-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersionFixture(databaseFile, 16)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            assertNull(database.publicationStateDao().latest("missing-run"))
        } finally {
            database.close()
        }
    }

    @Test
    fun `v15 to v16 migration exposes agent lifecycle registration authority`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v15-v16-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersionFixture(databaseFile, 15)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            assertTrue(
                database.workerExecutionDao()
                    .registrations("missing-run", "missing-group")
                    .isEmpty()
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `v14 to v15 migration exposes immutable integration node authority`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v14-v15-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersionFixture(databaseFile, 14)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            assertNull(
                database.contributionGraphDao()
                    .integrationNodeById("missing-integration")
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `v13 to v14 migration exposes immutable downstream use authority`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v13-v14-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersionFixture(databaseFile, 13)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            assertNull(
                database.contributionGraphDao()
                    .downstreamUseById("missing-downstream-use")
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `v12 to v13 migration exposes append only contribution graph`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v12-v13-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersionFixture(databaseFile, 12)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val dao = database.contributionGraphDao()
            assertNull(dao.byId("missing-edge"))
            assertNull(dao.criticDecisionById("missing-decision"))
            assertNull(
                dao.criticDecisionForSubmission(
                    "missing-run",
                    "missing-group",
                    "missing-submission"
                )
            )
            assertTrue(dao.forRun("missing-run", "missing-group").isEmpty())
            assertTrue(
                dao.fromAuthority(
                    "missing-run",
                    "missing-group",
                    "CONTRIBUTION_COMMIT",
                    "missing-commit"
                ).isEmpty()
            )
            assertTrue(
                dao.toAuthority(
                    "missing-run",
                    "missing-group",
                    "STAGING_SUBMISSION",
                    "missing-submission"
                ).isEmpty()
            )
            assertNull(database.causalFitnessDao().byId("missing-fitness"))
            assertTrue(
                database.causalFitnessDao().forPlane(
                    "missing-run",
                    "missing-group",
                    1,
                    "WORKER"
                ).isEmpty()
            )
            assertNull(database.costLedgerDao().byId("missing-cost-entry"))
            assertEquals(0.0, database.costLedgerDao().finalizedKnownUsd("missing-run"))
            assertEquals(0.0, database.costLedgerDao().activeReservedUsd("missing-run", null))
        } finally {
            database.close()
        }
    }

    @Test
    fun `v11 to v12 migration exposes append only tool infrastructure tables`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v11-v12-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersionFixture(databaseFile, 11)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val dao = database.toolInfrastructureDao()
            assertNull(dao.opportunity("missing-opportunity"))
            assertNull(dao.lifecycleEvent("missing-event"))
            assertTrue(dao.lifecycle("missing-version").isEmpty())
            assertNull(dao.execution("missing-execution"))
            assertTrue(dao.executions("missing-version").isEmpty())
            assertTrue(
                dao.executionsForAgentNode(
                    "missing-run",
                    "missing-group",
                    "missing-agent",
                    1,
                    "missing-node"
                ).isEmpty()
            )
            assertNull(dao.simulation("missing-simulation"))
            assertNull(dao.simulationForExecution("missing-execution"))
            assertNull(dao.reuse("missing-reuse"))
            assertTrue(dao.reuseForVersion("missing-version").isEmpty())
            assertNull(dao.fitness("missing-fitness"))
            assertTrue(dao.fitnessForVersion("missing-version").isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `v10 to v11 migration exposes all novelty tables`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v10-v11-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersion10Fixture(databaseFile)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val noveltyDao = database.outlierNoveltyDao()
            assertTrue(noveltyDao.proposalsForRound("missing-run", 1).isEmpty())
            assertNull(noveltyDao.proposalById("missing-proposal"))
            assertNull(noveltyDao.proposalByHash("missing-proposal-hash"))
            assertNull(noveltyDao.screeningByProposalHash("missing-proposal-hash"))
            assertTrue(noveltyDao.assessmentsForProposal("missing-proposal-hash").isEmpty())
            assertNull(noveltyDao.assessment("missing-proposal-hash", "missing-grader"))
            assertNull(noveltyDao.verdictByProposalHash("missing-proposal-hash"))
            assertTrue(noveltyDao.lifecycleForProposal("missing-proposal").isEmpty())
            assertNull(noveltyDao.lifecycleEventById("missing-event"))
            assertTrue(noveltyDao.downstreamLineage("missing-proposal").isEmpty())
            assertTrue(noveltyDao.fitnessForProposal("missing-proposal").isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `v6 to v10 migration preserves WIP and creates Atlas worker capture and grader tables`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v6-v10-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersion6Fixture(databaseFile)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val preservedTask = database.wipTaskDao().getByNodeId(
                nodeId = "legacy-node",
                runId = "legacy-run",
                groupId = "legacy-group"
            )
            assertNotNull(preservedTask)
            assertEquals("claimed", preservedTask.status)
            assertEquals("legacy-agent", preservedTask.claimedByAgentId)
            assertEquals(2, preservedTask.abandonCount)
            assertEquals("dst:legacy-node", preservedTask.dstNodeRef)

            val atlasDao = database.atlasDao()
            assertTrue(atlasDao.versionHistory("missing-atlas").isEmpty())
            assertTrue(atlasDao.amendmentHistory("missing-atlas").isEmpty())
            assertNull(atlasDao.latestCommit("missing-atlas"))
            assertTrue(atlasDao.currentStates("missing-atlas").isEmpty())
            assertNull(atlasDao.latestProjection("missing-atlas", "WIP", "legacy-group"))
            val workerDao = database.workerExecutionDao()
            assertTrue(workerDao.contributionsForAgent("legacy-run", "legacy-group", "legacy-agent").isEmpty())
            assertTrue(workerDao.degradedExecutionsForAgent("legacy-run", "legacy-group", "legacy-agent").isEmpty())
            assertTrue(workerDao.checkpointsForAgent("legacy-run", "legacy-group", "legacy-agent").isEmpty())
            assertTrue(workerDao.exits("legacy-run", "legacy-group").isEmpty())
            val captureDao = database.nodeCaptureDao()
            assertTrue(captureDao.evidencePacketsForNode("legacy-run", "legacy-node").isEmpty())
            assertTrue(captureDao.gradeBundlesForNode("legacy-run", "legacy-node").isEmpty())
            assertTrue(captureDao.captureDecisionsForNode("legacy-run", "legacy-node").isEmpty())
            assertTrue(
                captureDao.yieldSchedulesForActivation(
                    "legacy-run",
                    "legacy-group",
                    1
                ).isEmpty()
            )
            assertNull(captureDao.yieldApplicationByScheduleId("missing-schedule"))
            assertTrue(
                captureDao.rewardUseEventsForApplication(
                    "legacy-run",
                    "missing-application"
                ).isEmpty()
            )
            val graderDao = database.graderAuthorityDao()
            assertNull(graderDao.latestAtlas("missing-grader-atlas"))
            assertNull(
                graderDao.latestState(
                    "legacy-run",
                    "missing-atlas",
                    "missing-grader",
                    "missing-node"
                )
            )
            assertTrue(
                graderDao.requirementsForRound("legacy-run", 1, "missing-grader").isEmpty()
            )
            assertTrue(
                graderDao.hiddenTestsForRound("legacy-run", "missing-grader", 1).isEmpty()
            )
            assertNull(graderDao.freezeForRound("legacy-run", 1, "missing-grader"))
            assertTrue(graderDao.scoreBundlesForRound("legacy-run", 1).isEmpty())
            assertTrue(graderDao.fitnessHistory("legacy-run", "missing-grader").isEmpty())
            assertNull(graderDao.latestIndependenceProfile("legacy-run"))
        } finally {
            database.close()
        }
    }

    private fun createVersion6Fixture(databaseFile: File) {
        databaseFile.parentFile?.mkdirs()
        val schema = loadSchema(6)
        BundledSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
            schema.getValue("entities").jsonArray.forEach { entityElement ->
                val entity = entityElement.jsonObject
                val tableName = entity.getValue("tableName").jsonPrimitive.content
                connection.execute(
                    entity.getValue("createSql").jsonPrimitive.content
                        .replace("\${TABLE_NAME}", tableName)
                )
                entity["indices"]?.jsonArray.orEmpty().forEach { indexElement ->
                    connection.execute(
                        indexElement.jsonObject.getValue("createSql").jsonPrimitive.content
                            .replace("\${TABLE_NAME}", tableName)
                    )
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { query ->
                connection.execute(query.jsonPrimitive.content)
            }
            connection.execute("PRAGMA user_version = 6")
            connection.prepare(
                """
                INSERT INTO wip_tasks(
                    nodeId, runId, groupId, description, claimedByAgentId, status,
                    lastChangedGeneration, abandonCount, linkedNodeId, dstNodeRef
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.bindText(1, "legacy-node")
                statement.bindText(2, "legacy-run")
                statement.bindText(3, "legacy-group")
                statement.bindText(4, "Preserved legacy task")
                statement.bindText(5, "legacy-agent")
                statement.bindText(6, "claimed")
                statement.bindLong(7, 4)
                statement.bindLong(8, 2)
                statement.bindText(9, "knowledge:legacy-node")
                statement.bindText(10, "dst:legacy-node")
                statement.step()
            }
        }
    }

    private fun createVersion10Fixture(databaseFile: File) {
        createVersionFixture(databaseFile, 10)
    }

    private fun createVersionFixture(databaseFile: File, version: Int) {
        databaseFile.parentFile?.mkdirs()
        val schema = loadSchema(version)
        BundledSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
            schema.getValue("entities").jsonArray.forEach { entityElement ->
                val entity = entityElement.jsonObject
                val tableName = entity.getValue("tableName").jsonPrimitive.content
                connection.execute(
                    entity.getValue("createSql").jsonPrimitive.content
                        .replace("\${TABLE_NAME}", tableName)
                )
                entity["indices"]?.jsonArray.orEmpty().forEach { indexElement ->
                    connection.execute(
                        indexElement.jsonObject.getValue("createSql").jsonPrimitive.content
                            .replace("\${TABLE_NAME}", tableName)
                    )
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { query ->
                connection.execute(query.jsonPrimitive.content)
            }
            connection.execute("PRAGMA user_version = $version")
        }
    }

    private fun loadSchema(version: Int): JsonObject {
        val relativePath =
            "schemas/com.achulkov.evomolior.substrate.db.EvoMoliorDatabase/$version.json"
        val schemaFile = sequenceOf(
            File(relativePath),
            File("evomolior/substrate/$relativePath")
        ).firstOrNull(File::isFile)
        checkNotNull(schemaFile) { "Room schema fixture not found: $relativePath" }
        return Json.parseToJsonElement(schemaFile.readText())
            .jsonObject
            .getValue("database")
            .jsonObject
    }

    private fun SQLiteConnection.execute(sql: String) {
        prepare(sql).use { statement -> statement.step() }
    }
}
