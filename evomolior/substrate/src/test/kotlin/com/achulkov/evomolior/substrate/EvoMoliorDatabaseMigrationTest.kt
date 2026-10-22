package com.achulkov.evomolior.substrate

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.AgentLifecycleRegistration
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.AgentExitRecordEntity
import com.achulkov.evomolior.substrate.db.entity.AgentLifecycleRegistrationEntity
import com.achulkov.evomolior.substrate.db.entity.AgentRuntimeStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.GraderNodeStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.PublicationOutcomeEventEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
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
import kotlin.time.Instant

/**
 * Verifies checked-in historical schemas upgrade through Room's generated migrations.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class EvoMoliorDatabaseMigrationTest {

    @Test
    fun `v24 to v25 supplies default ArtifactOwner fields and preserves legacy registration hash`() =
        runBlocking {
            val databaseFile = createTempDirectory("evomolior-v24-v25-registration-")
                .resolve("substrate.db")
                .toFile()
            val legacy = AgentLifecycleRegistration(
                registrationId = "legacy-registration-v24",
                runId = "legacy-run-v24",
                groupId = AtlasGroupId("legacy-group-v24"),
                agentId = AtlasAgentId("legacy-builder-v24"),
                role = "Builder",
                generation = 2,
                classification = AgentLifecycleClassification.NON_DAEMON,
                registeredAt = Instant.parse("2026-08-02T12:00:00Z")
            ).withComputedHash()
            val legacyJson = Json {
                encodeDefaults = false
                explicitNulls = false
            }.encodeToString(AgentLifecycleRegistration.serializer(), legacy)

            createVersionFixture(databaseFile, 24)
            BundledSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
                connection.execute(
                    """
                    INSERT INTO agent_lifecycle_registrations(
                        registrationId, runId, groupId, agentId, role, generation,
                        classification, registrationHash, contentJson, registeredAtMs
                    ) VALUES (
                        '${legacy.registrationId}', '${legacy.runId}', '${legacy.groupId.value}',
                        '${legacy.agentId.value}', '${legacy.role}', ${legacy.generation},
                        '${legacy.classification.name}', '${legacy.registrationHash}',
                        '$legacyJson', ${legacy.registeredAt.toEpochMilliseconds()}
                    )
                    """.trimIndent()
                )
            }

            val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .build()
            try {
                val entity = requireNotNull(
                    database.workerExecutionDao().registrationForAgent(
                        legacy.runId,
                        legacy.groupId.value,
                        legacy.agentId.value
                    )
                )
                assertEquals("NONE", entity.duty)
                assertNull(entity.workspaceId)
                assertNull(entity.workspaceSourceHash)
                assertEquals(
                    legacy,
                    WorkerExecutionRepository(database).registration(
                        legacy.runId,
                        legacy.groupId.value,
                        legacy.agentId.value
                    )
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun `v21 to latest preserves compatible historical lifecycle exit and staging authority`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v21-latest-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersionFixture(databaseFile, 21)
        seedVersion21HistoricalAuthority(databaseFile)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            assertEquals(
                historicalRegistration(),
                database.workerExecutionDao().registrationForAgent(
                    "legacy-run",
                    "legacy-group",
                    "legacy-agent"
                )
            )
            assertEquals(
                historicalExit(),
                database.workerExecutionDao().exitForAgent(
                    "legacy-run",
                    "legacy-group",
                    "legacy-agent"
                )
            )
            assertEquals(
                historicalStagingSubmission(),
                database.stagingDao().getById("legacy-staging")
            )
            assertTrue(
                database.workerExecutionDao().runtimeStateEvents(
                    "legacy-run",
                    "legacy-group",
                    "legacy-agent",
                    7
                ).isEmpty()
            )
        } finally {
            database.close()
        }

        val runtimeEntity = loadSchema(24)
            .getValue("entities")
            .jsonArray
            .map { it.jsonObject }
            .single {
                it.getValue("tableName").jsonPrimitive.content ==
                    "agent_runtime_state_events"
            }
        val indexNames = runtimeEntity["indices"]
            ?.jsonArray
            .orEmpty()
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
            .toSet()
        assertTrue(
            "index_agent_runtime_state_events_runId_groupId_agentId_generation_revision" in
                indexNames
        )
        assertTrue(
            "index_agent_runtime_state_events_runId_groupId_agentId_generation_occurredAtMs" in
                indexNames
        )
    }

    @Test
    fun `v22 to v24 preserves runtime authority and exposes empty completion tables`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v22-v24-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersionFixture(databaseFile, 22)
        seedVersion22RuntimeAuthority(databaseFile)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            assertEquals(
                historicalRuntimeEvent(),
                database.workerExecutionDao().runtimeStateEventById("legacy-runtime")
            )
            val completion = database.completionPersistenceDao()
            assertNull(completion.diagnostic("missing-diagnostic"))
            assertNull(completion.attempt("missing-attempt"))
            assertNull(completion.blueprint("missing-blueprint"))
            assertNull(completion.snapshot("missing-run", "missing-group", 0))
            assertNull(completion.decisionPackage("missing-package"))
            assertTrue(
                completion.publicationOutcomes("missing-run", "ACCEPTED_DECISION_PACKAGE")
                    .isEmpty()
            )
            assertTrue(completion.roundPhaseHistory("missing-run", 0).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `v23 to v24 preserves completion authority and creates round phase ledger table`() = runBlocking {
        val databaseFile = createTempDirectory("evomolior-v23-v24-migration-")
            .resolve("substrate.db")
            .toFile()
        createVersionFixture(databaseFile, 23)
        seedVersion23CompletionAuthority(databaseFile)

        val database = Room.databaseBuilder<EvoMoliorDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val completion = database.completionPersistenceDao()
            assertEquals(
                historicalPublicationOutcome(),
                completion.publicationOutcome("legacy-publication")
            )
            assertTrue(completion.roundPhaseHistory("legacy-run", 7).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `v19 to latest migration exposes portfolios and KG fact attempt authority`() = runBlocking {
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

        val tableNames = loadSchema(24)
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

    private fun seedVersion21HistoricalAuthority(databaseFile: File) {
        BundledSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
            connection.execute(
                """
                INSERT INTO agent_lifecycle_registrations(
                    registrationId, runId, groupId, agentId, role, generation, classification,
                    registrationHash, contentJson, registeredAtMs
                ) VALUES (
                    'legacy-registration', 'legacy-run', 'legacy-group', 'legacy-agent',
                    'Historical worker', 7, 'NON_DAEMON',
                    '${"1".repeat(64)}', '{"legacy":"registration"}', 101
                )
                """.trimIndent()
            )
            connection.execute(
                """
                INSERT INTO agent_exit_records(
                    exitId, runId, groupId, agentId, nodeId, round, finalPhase, commitId,
                    commitType, acceptedContribution, acceptedStructuredDeadEnd,
                    acceptedNoveltyProposal, degradedExecution, wasKilled, contentJson, exitedAtMs
                ) VALUES (
                    'legacy-exit', 'legacy-run', 'legacy-group', 'legacy-agent', 'legacy-node',
                    7, 'CONTRIBUTION_STAGED', 'legacy-commit', 'POSITIVE_RESULT',
                    1, 0, 0, 0, 0, '{"legacy":"exit"}', 202
                )
                """.trimIndent()
            )
            connection.execute(
                """
                INSERT INTO staging_submissions(
                    id, agentId, role, type, content, wipTaskRef, speculative, outlier,
                    budgetSignature, status, rejectionReason, criticAgentId, initialTrustScore,
                    generation, groupId, runId, submittedAtMs, reviewedAtMs,
                    promotedArtifactRef, promotedArtifactHash
                ) VALUES (
                    'legacy-staging', 'legacy-agent', 'BUILDER', 'DOC',
                    '{"legacy":"staging"}', 'legacy-node', 1, 0, 'legacy-signature',
                    'promoted', 'historical-note', 'legacy-critic', 0.75, 7,
                    'legacy-group', 'legacy-run', 303, 404,
                    'kb:legacy-staging', '${"2".repeat(64)}'
                )
                """.trimIndent()
            )
        }
    }

    private fun seedVersion22RuntimeAuthority(databaseFile: File) {
        BundledSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
            connection.execute(
                """
                INSERT INTO agent_runtime_state_events(
                    eventId, runId, groupId, agentId, generation, revision, intent,
                    previousStateHash, stateHash, eventHash, contentJson, occurredAtMs
                ) VALUES (
                    'legacy-runtime', 'legacy-run', 'legacy-group', 'legacy-agent',
                    7, 3, 'START_EXECUTION', '${"3".repeat(64)}', '${"4".repeat(64)}',
                    '${"5".repeat(64)}', '{"legacy":"runtime"}', 505
                )
                """.trimIndent()
            )
        }
    }

    private fun seedVersion23CompletionAuthority(databaseFile: File) {
        BundledSQLiteDriver().open(databaseFile.absolutePath).use { connection ->
            connection.execute(
                """
                INSERT INTO publication_outcome_events(
                    eventId, runId, outcomeClass, state, sequence, previousEventHash,
                    eventHash, occurredAtMs, contentJson
                ) VALUES (
                    'legacy-publication', 'legacy-run', 'ACCEPTED_DECISION_PACKAGE',
                    'FINAL_CANDIDATES_SEALED', 0, NULL, '${"6".repeat(64)}', 606,
                    '{"legacy":"publication"}'
                )
                """.trimIndent()
            )
        }
    }

    private fun historicalRegistration() = AgentLifecycleRegistrationEntity(
        registrationId = "legacy-registration",
        runId = "legacy-run",
        groupId = "legacy-group",
        agentId = "legacy-agent",
        role = "Historical worker",
        generation = 7,
        classification = "NON_DAEMON",
        registrationHash = "1".repeat(64),
        contentJson = """{"legacy":"registration"}""",
        registeredAtMs = 101
    )

    private fun historicalExit() = AgentExitRecordEntity(
        exitId = "legacy-exit",
        runId = "legacy-run",
        groupId = "legacy-group",
        agentId = "legacy-agent",
        nodeId = "legacy-node",
        round = 7,
        finalPhase = "CONTRIBUTION_STAGED",
        commitId = "legacy-commit",
        commitType = "POSITIVE_RESULT",
        acceptedContribution = true,
        acceptedStructuredDeadEnd = false,
        acceptedNoveltyProposal = false,
        degradedExecution = false,
        wasKilled = false,
        contentJson = """{"legacy":"exit"}""",
        exitedAtMs = 202
    )

    private fun historicalStagingSubmission() = StagingSubmissionEntity(
        id = "legacy-staging",
        agentId = "legacy-agent",
        role = "BUILDER",
        type = "DOC",
        content = """{"legacy":"staging"}""",
        wipTaskRef = "legacy-node",
        speculative = true,
        outlier = false,
        budgetSignature = "legacy-signature",
        status = "promoted",
        rejectionReason = "historical-note",
        criticAgentId = "legacy-critic",
        initialTrustScore = 0.75,
        generation = 7,
        groupId = "legacy-group",
        runId = "legacy-run",
        submittedAtMs = 303,
        reviewedAtMs = 404,
        promotedArtifactRef = "kb:legacy-staging",
        promotedArtifactHash = "2".repeat(64)
    )

    private fun historicalRuntimeEvent() = AgentRuntimeStateEventEntity(
        eventId = "legacy-runtime",
        runId = "legacy-run",
        groupId = "legacy-group",
        agentId = "legacy-agent",
        generation = 7,
        revision = 3,
        intent = "START_EXECUTION",
        previousStateHash = "3".repeat(64),
        stateHash = "4".repeat(64),
        eventHash = "5".repeat(64),
        contentJson = """{"legacy":"runtime"}""",
        occurredAtMs = 505
    )

    private fun historicalPublicationOutcome() = PublicationOutcomeEventEntity(
        eventId = "legacy-publication",
        runId = "legacy-run",
        outcomeClass = "ACCEPTED_DECISION_PACKAGE",
        state = "FINAL_CANDIDATES_SEALED",
        sequence = 0,
        previousEventHash = null,
        eventHash = "6".repeat(64),
        occurredAtMs = 606,
        contentJson = """{"legacy":"publication"}"""
    )

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
