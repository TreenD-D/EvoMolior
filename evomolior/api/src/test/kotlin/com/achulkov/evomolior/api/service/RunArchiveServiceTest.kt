@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.contract.GeneratedImageAsset
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.ContributionEdge
import com.achulkov.evomolior.core.contract.ContributionPlane
import com.achulkov.evomolior.core.contract.ContributionRelation
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.FinalCandidateRecord
import com.achulkov.evomolior.core.contract.FinalDeliverableReferenceRecord
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.AgentLifecycleRegistration
import com.achulkov.evomolior.core.contract.PublicationState
import com.achulkov.evomolior.core.contract.PublicationStateEvent
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.contract.RunInputArtifactKind
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.council.CouncilFieldSealing
import com.achulkov.evomolior.core.council.StartingFieldSeal
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.GenerationCreditPolicy
import com.achulkov.evomolior.core.council.InclusiveIntRange
import com.achulkov.evomolior.core.council.ModelCapabilityCard
import com.achulkov.evomolior.core.council.SandboxCapabilitySummary
import com.achulkov.evomolior.core.council.SourceAccessPolicySummary
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.metrics.RunMetricCatalog
import com.achulkov.evomolior.core.metrics.RunMetricsV1
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasProjectionSet
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.DisclosureProjection
import com.achulkov.evomolior.substrate.atlas.PreparedAtlasMigration
import com.achulkov.evomolior.substrate.atlas.ResourceProjection
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.substrate.publication.PublicationStateRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs
import kotlin.time.Instant as KotlinInstant

class RunArchiveServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun archiveFixtureEnvelope() = ExecutionEnvelope(
        requiredWorkerGroupCount = InclusiveIntRange(2, 2),
        requiredGraderGroupCount = InclusiveIntRange(2, 2),
        permittedProfiles = setOf(GroupProfileType.Library, GroupProfileType.Factory),
        availableModels = listOf(
            ModelCapabilityCard(
                modelId = "archive-fixture-model",
                providerId = "test",
                familyId = "fixture",
                structuredOutputReliability = 1.0,
                toolCallReliability = 1.0,
                codeBuildReliability = 1.0,
                longContextSynthesisReliability = 1.0
            )
        ),
        maxAgentsPerGroupPerRound = 2,
        maxRounds = 6,
        perGenerationCreditPolicy = GenerationCreditPolicy(1.0, 2.0),
        allowedTopologyKinds = setOf(com.achulkov.evomolior.core.contract.GroupTopologyKind.RING),
        sourceAccessPolicy = SourceAccessPolicySummary(networkAccessEnabled = false),
        sandboxCapabilities = SandboxCapabilitySummary(
            maximumToolRuntimeSeconds = 1,
            maximumWorkspaceBytes = 1L
        ),
        outputSpec = com.achulkov.evomolior.core.atlas.FinalOutputSpec(
            relativePath = "receiver/result.md",
            mediaType = "text/markdown"
        ),
        atomicNodeEffortBound = 2
    )

    @Test
    fun `manifest verifier accepts one run and rejects tampered missing and mixed-run bundles`() = runTest {
        val rootDir = createTempDirectory("run-manifest-verifier-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-manifest-verifier"
        val startingField = StartingField.default("Verify one-run provenance", runId = runId, groupCount = 1)
        service.archiveRun(
            runId = runId,
            groupIds = listOf("group-0"),
            winnerId = "group-0",
            objective = startingField.objective,
            startingFieldJson = json.encodeToString(StartingField.serializer(), startingField),
            finalGeneration = 1,
            completionRecord = RunCompletionRecord(
                runId = runId,
                winnerId = "group-0",
                finalAnswerSummary = "Verified output"
            )
        )
        val validBundle = assertNotNull(service.buildResultsBundle(runId))
        val valid = RunManifestVerifier.verify(validBundle, expectedRunId = runId)
        assertTrue(valid.isValid, valid.errors.joinToString())
        assertTrue(valid.manifest!!.entries.any { it.category == RunManifestEntryCategory.DATABASE })

        val tamperedEntries = unzipEntryBytes(validBundle).toMutableMap()
        tamperedEntries["outputs/high-level-run-log.txt"] = "tampered".toByteArray()
        val tampered = RunManifestVerifier.verify(zipEntries(tamperedEntries), expectedRunId = runId)
        assertFalse(tampered.isValid)
        assertTrue(tampered.errors.any { it.contains("Hash mismatch") })

        val missingEntries = unzipEntryBytes(validBundle).toMutableMap()
        missingEntries.remove("starting-field.json")
        val missing = RunManifestVerifier.verify(zipEntries(missingEntries), expectedRunId = runId)
        assertFalse(missing.isValid)
        assertTrue(missing.errors.any { it.contains("Missing attested entry: starting-field.json") })

        val mixedEntries = unzipEntryBytes(validBundle).toMutableMap()
        val manifest = json.decodeFromString<RunManifest>(
            mixedEntries.getValue("manifest.json").toString(Charsets.UTF_8)
        )
        val mixedManifest = manifest.copy(
            entries = manifest.entries.mapIndexed { index, entry ->
                if (index == 0) entry.copy(runId = "run-from-another-bundle") else entry
            }
        )
        mixedEntries["manifest.json"] = json.encodeToString(RunManifest.serializer(), mixedManifest).toByteArray()
        val mixed = RunManifestVerifier.verify(zipEntries(mixedEntries), expectedRunId = runId)
        assertFalse(mixed.isValid)
        assertTrue(mixed.errors.any { it.contains("Mixed-run entry") })

        val restampedMetadataEntries = unzipEntryBytes(validBundle).toMutableMap()
        val restampedMetadataManifest = json.decodeFromString<RunManifest>(
            restampedMetadataEntries.getValue("manifest.json").toString(Charsets.UTF_8)
        ).let { manifest ->
            manifest.copy(
                objective = "restamped objective",
                groupCount = manifest.groupCount + 1,
                generationCeiling = manifest.generationCeiling + 1,
                artifactCount = manifest.artifactCount + 1,
                archiveEntryCount = manifest.archiveEntryCount + 1
            )
        }
        restampedMetadataEntries["manifest.json"] = json.encodeToString(
            RunManifest.serializer(),
            restampedMetadataManifest
        ).toByteArray()
        val restampedMetadata = RunManifestVerifier.verify(
            zipEntries(restampedMetadataEntries),
            expectedRunId = runId
        )
        assertFalse(restampedMetadata.isValid)
        assertTrue(restampedMetadata.errors.any { it.contains("objective does not match") })
        assertTrue(restampedMetadata.errors.any { it.contains("groupCount does not match") })
        assertTrue(restampedMetadata.errors.any { it.contains("generationCeiling does not match") })
        assertTrue(restampedMetadata.errors.any { it.contains("artifactCount does not match") })
        assertTrue(restampedMetadata.errors.any { it.contains("archiveEntryCount") })

        val preV62Entries = unzipEntryBytes(validBundle).toMutableMap()
        val currentStartingField = json.parseToJsonElement(
            preV62Entries.getValue("starting-field.json").toString(Charsets.UTF_8)
        ).jsonObject
        val preV62StartingField = JsonObject(
            currentStartingField - "wireVersion"
        ).toString().toByteArray()
        val preV62Manifest = json.decodeFromString<RunManifest>(
            preV62Entries.getValue("manifest.json").toString(Charsets.UTF_8)
        ).let { manifest ->
            manifest.copy(
                startingFieldSha256 = sha256Hex(preV62StartingField),
                entries = manifest.entries.map { entry ->
                    if (entry.path == "starting-field.json") {
                        entry.copy(
                            sizeBytes = preV62StartingField.size.toLong(),
                            sha256 = sha256Hex(preV62StartingField)
                        )
                    } else {
                        entry
                    }
                }
            )
        }
        preV62Entries["starting-field.json"] = preV62StartingField
        preV62Entries["manifest.json"] = json.encodeToString(
            RunManifest.serializer(),
            preV62Manifest
        ).toByteArray()

        val preV62 = RunManifestVerifier.verify(
            zipEntries(preV62Entries),
            expectedRunId = runId
        )
        assertFalse(preV62.isValid)
        assertTrue(
            preV62.errors.any {
                it.contains("wireVersion=missing (pre-v62)") &&
                    it.contains("cannot be resumed, continued, or rebuilt")
            },
            preV62.errors.joinToString()
        )

        val staleSealEntries = unzipEntryBytes(validBundle).toMutableMap()
        val staleSealStartingField = JsonObject(
            currentStartingField + (
                "seal" to buildJsonObject {
                    put("schemaVersion", "2")
                }
                )
        ).toString().toByteArray()
        val staleSealManifest = json.decodeFromString<RunManifest>(
            staleSealEntries.getValue("manifest.json").toString(Charsets.UTF_8)
        ).let { manifest ->
            manifest.copy(
                startingFieldSha256 = sha256Hex(staleSealStartingField),
                entries = manifest.entries.map { entry ->
                    if (entry.path == "starting-field.json") {
                        entry.copy(
                            sizeBytes = staleSealStartingField.size.toLong(),
                            sha256 = sha256Hex(staleSealStartingField)
                        )
                    } else {
                        entry
                    }
                }
            )
        }
        staleSealEntries["starting-field.json"] = staleSealStartingField
        staleSealEntries["manifest.json"] = json.encodeToString(
            RunManifest.serializer(),
            staleSealManifest
        ).toByteArray()

        val staleSeal = RunManifestVerifier.verify(
            zipEntries(staleSealEntries),
            expectedRunId = runId
        )
        assertFalse(staleSeal.isValid)
        assertTrue(
            staleSeal.errors.any {
                it.contains("seal.schemaVersion=2") &&
                    it.contains("Restamped or stale sealed fields")
            },
            staleSeal.errors.joinToString()
        )
    }

    @Test
    fun `results bundle attests persisted acceptance purpose and uses the completion timestamp`() = runTest {
        val rootDir = createTempDirectory("run-purpose-archive-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-purpose-archive"
        val groupIds = listOf("group-0", "group-1")
        val winnerId = groupIds.first()
        val completedAtMs = Instant.parse("2026-07-17T03:00:00Z").toEpochMilli()
        val compiled = compiledCouncilTestFixture(
            runId = runId,
            objective = "Archive explicit acceptance authority",
            envelope = archiveFixtureEnvelope(),
            workerGroupKeys = groupIds,
            workerProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            topologyOrder = groupIds
        )
        val startingField = compiled.workerField
        AtlasTransactionRepository(dbFactory).initialize(
            PreparedAtlasMigration(
                receiverContract = compiled.receiverContract,
                atlas = compiled.atlas,
                executionStates = compiled.atlas.nodes.keys.map {
                    NodeExecutionState(
                        nodeId = it,
                        round = 0,
                        revealedToGroups = groupIds.map(::AtlasGroupId).toSet()
                    )
                },
                projections = AtlasProjectionSet(
                    wip = emptyList(),
                    deliverables = emptyList(),
                    resources = ResourceProjection(runId, emptyList()),
                    disclosure = DisclosureProjection(
                        runId = runId,
                        stages = compiled.atlas.stageDefinitions,
                        groupStageIndices = groupIds.associateWith { 0 }
                    ),
                    mappings = emptyList()
                )
            )
        )
        GraderAuthorityRepository(dbFactory).appendAtlas(compiled.graderAtlas)
        val outputPath = "receiver/result.md"
        val outputContent = "# Accepted result\n\nreceiver-delivery-accepted"
        val winnerDb = dbFactory.get(runId, winnerId)
        val sourceContent = "Accepted source evidence for receiver-delivery-accepted."
        val sourceSubmissionId = "submission:$runId:source"
        winnerDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = sourceSubmissionId,
                agentId = "source-writer",
                role = "Researcher",
                type = "DOC",
                content = sourceContent,
                budgetSignature = "test",
                generation = 6,
                groupId = winnerId,
                runId = runId,
                submittedAtMs = completedAtMs
            )
        )
        winnerDb.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "accepted-source",
                content = sourceContent,
                sourceAgentId = "source-writer",
                generation = 6,
                domain = "fixture",
                groupId = winnerId,
                runId = runId,
                createdAtMs = completedAtMs
            )
        )
        val source = ContributionAuthorityRef(
            ContributionAuthorityKind.CANONICAL_ARTIFACT,
            "kb:accepted-source",
            AtlasHashing.hashText(sourceContent)
        )
        val graph = ContributionGraphRepository(winnerDb, dbFactory.getAtlas(runId))
        graph.recordCriticDecision(
            CriticDecisionRecord(
                decisionId = "decision:$runId:source",
                runId = runId,
                groupId = AtlasGroupId(winnerId),
                round = 6,
                submissionId = sourceSubmissionId,
                submissionContentHash = AtlasHashing.hashText(sourceContent),
                resolvedReview = CriticReview(
                    submissionId = sourceSubmissionId,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = "fixture-critic",
                    initialTrustScore = 1.0
                ),
                resultAuthority = source,
                decidedAt = KotlinInstant.fromEpochMilliseconds(completedAtMs)
            ).withComputedHash()
        )
        val finalReference = FinalDeliverableReferenceRecord(
            referenceId = "final-reference:$runId",
            runId = runId,
            groupId = AtlasGroupId(winnerId),
            round = 6,
            relativePath = outputPath,
            mediaType = "text/markdown",
            contentHash = AtlasHashing.hashText(outputContent),
            writerAgentId = "final-writer",
            writerSubmissionId = "submission:$runId",
            nodeId = null,
            sourceAuthority = source,
            referencedAt = KotlinInstant.fromEpochMilliseconds(completedAtMs)
        ).withComputedHash()
        winnerDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = finalReference.writerSubmissionId,
                agentId = finalReference.writerAgentId,
                role = "Builder",
                type = "DOC",
                content = outputContent,
                budgetSignature = "test",
                generation = 6,
                groupId = winnerId,
                runId = runId,
                submittedAtMs = completedAtMs
            )
        )
        assertEquals(finalReference, graph.recordFinalDeliverableReference(finalReference))
        val publication = PublicationStateRepository(dbFactory.getAtlas(runId))
        publication.sealFinalCandidates(
            listOf(
                FinalCandidateRecord(
                    candidateId = "candidate:$runId", runId = runId, groupId = winnerId, round = 6,
                    outputContractHash = "output-contract:$runId",
                    sourceAuthorities = listOf(source),
                    sealedAt = KotlinInstant.fromEpochMilliseconds(completedAtMs)
                ).withComputedHash()
            )
        )
        var previousPublication: PublicationStateEvent? = null
        PublicationState.entries.takeWhile { it != PublicationState.METRICS_EXPORTED }
            .forEachIndexed { sequence, state ->
                val event = PublicationStateEvent(
                    eventId = "publication:$runId:${state.name}", runId = runId, state = state,
                    sequence = sequence, previousEventHash = previousPublication?.eventHash,
                    authorityReferenceIds = setOf("authority:${state.name}"),
                    occurredAt = KotlinInstant.fromEpochMilliseconds(completedAtMs)
                ).withComputedHash()
                publication.append(event)
                previousPublication = event
            }
        val config = RunConfig(
            runId = runId,
            objective = startingField.objective,
            groupCount = groupIds.size,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            rounds = 6,
            roundDurationMinutes = 3,
            maxAgentsPerGroup = 2,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Evidence fidelity",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "/tmp/codex",
            llmBackend = LlmBackend.CODEX_CHATGPT,
            councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL,
            finalOutputSpec = FinalOutputSpec(
                relativePath = outputPath,
                mediaType = "text/markdown"
            )
        )
        val persistedState = PersistedRunState.fromRunConfig(
            config = config,
            groupIds = groupIds,
            graderGroupIds = listOf("grader-0", "grader-1"),
            allowedHosts = emptySet()
        ).copy(lastUpdatedAtMs = completedAtMs - 60_000L)
        PersistedRunStateStore.writeForDataRoot(rootDir, persistedState)
        val stateFile = PersistedRunStateStore.resolveStateFile(rootDir, runId)
        val exactPersistedBytes = stateFile.readBytes()
        val acceptanceAdmission = AcceptanceAdmissionArtifact(
            runId = runId,
            acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL,
            admittedAtUtc = "2026-07-17T02:00:00Z",
            ownerPid = 42L,
            distributionManifestSha256 = "a".repeat(64),
            sourceRevision = "fixture-source-revision",
            configurationSha256 = "b".repeat(64),
            modelAssignmentsSha256 = "c".repeat(64),
            jvmVersion = "17.0.12",
            codexCliPath = "/tmp/codex",
            codexCliSha256 = "d".repeat(64),
            codexCliVersion = "codex fixture",
            estimatedMaximumRunArchiveBytes = 1024L,
            requiredFreeBytes = 10L * 1024L * 1024L * 1024L,
            checks = listOf(
                AcceptanceAdmissionCheck("daemon_disabled", "no daemon"),
                AcceptanceAdmissionCheck("jdk_17", "17.0.12")
            )
        )
        val acceptanceAdmissionFile = File(
            dbFactory.runControlDir(runId),
            AcceptanceAdmissionArtifact.FILE_NAME
        ).apply {
            parentFile.mkdirs()
            writeText(
                json.encodeToString(
                    AcceptanceAdmissionArtifact.serializer(),
                    acceptanceAdmission
                )
            )
        }
        val completionRecord = RunCompletionRecord(
            runId = runId,
            winnerId = winnerId,
            completedRoundCount = 6,
            finalAnswerSummary = "Accepted-purpose archive fixture",
            generatedOutputFiles = listOf(
                GeneratedOutputFile(
                    relativePath = outputPath,
                    mediaType = "text/markdown",
                    content = outputContent,
                    sourceGroupId = winnerId,
                    sourceAgentId = finalReference.writerAgentId,
                    sourceSubmissionId = finalReference.writerSubmissionId
                )
            ),
            submissionSummariesByGroup = mapOf(winnerId to "final output"),
            completedAtMs = completedAtMs
        )
        repeat(6) { round ->
            ScoreReplayAuthorityV1Store(dbFactory).append(
                runId = runId,
                observation = ScoreReplayObservationV1(
                    round = round,
                    liveVerdictSha256 = "a".repeat(64),
                    replayVerdictSha256 = "a".repeat(64),
                    maximumAbsoluteError = 0.0,
                    exactMatch = true
                )
            )
        }

        service.archiveRun(
            runId = runId,
            groupIds = groupIds,
            winnerId = completionRecord.winnerId,
            objective = startingField.objective,
            startingFieldJson = json.encodeToString(StartingField.serializer(), startingField),
            finalGeneration = 6,
            completionRecord = completionRecord
        )
        val bundle = assertNotNull(service.buildResultsBundle(runId, completionRecord))

        val verification = RunManifestVerifier.verify(
            bundleBytes = bundle,
            expectedRunId = runId,
            expectedCouncilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            expectedAcceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL
        )
        assertTrue(verification.isValid, verification.errors.joinToString())
        assertEquals(
            CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            verification.manifest?.councilExecutionPurpose
        )
        assertEquals(
            AcceptanceProfile.APPENDIX_B_FULL,
            verification.manifest?.acceptanceProfile
        )
        val entries = unzipEntryBytes(bundle)
        val metricsPath = RunMetricsV1ArtifactStore.FILE_NAME
        val metricsBytes = assertNotNull(entries[metricsPath])
        val admissionBytes = assertNotNull(entries[AcceptanceAdmissionArtifact.FILE_NAME])
        val manifest = assertNotNull(verification.manifest)
        assertEquals(4, manifest.schemaVersion)
        assertTrue(manifest.entries.any { it.path == "authority/worker-atlas.json" && it.category == RunManifestEntryCategory.WORKER_ATLAS })
        assertTrue(manifest.entries.any { it.path == "authority/grader-atlas.json" && it.category == RunManifestEntryCategory.GRADER_ATLAS })
        assertTrue(manifest.entries.any { it.path == "authority/final-candidates.json" && it.category == RunManifestEntryCategory.PUBLICATION_AUTHORITY })
        assertTrue(manifest.entries.any { it.path == "authority/publication-history.json" && it.category == RunManifestEntryCategory.PUBLICATION_AUTHORITY })
        assertTrue(manifest.entries.any { it.path == "authority/contribution-lineage/$winnerId.json" && it.category == RunManifestEntryCategory.CONTRIBUTION_LINEAGE })
        val outputEntry = assertNotNull(manifest.entries.singleOrNull { it.path == "outputs/$outputPath" })
        assertEquals(listOf(finalReference.referenceId), outputEntry.finalDeliverableReferences.map { it.referenceId })
        assertEquals("text/markdown", outputEntry.mediaType)
        assertEquals(sha256Hex(outputContent.toByteArray()), outputEntry.sha256)
        assertEquals(outputPath, outputEntry.finalDeliverableReferences.single().relativePath)
        assertEquals("text/markdown", outputEntry.finalDeliverableReferences.single().mediaType)
        assertEquals(
            sha256Hex(outputContent.toByteArray()),
            outputEntry.finalDeliverableReferences.single().contentSha256
        )
        assertTrue(
            manifest.entries
                .filter { it.category == RunManifestEntryCategory.OUTPUT && it.path != "outputs/$outputPath" }
                .all { it.finalDeliverableReferences.isEmpty() }
        )
        assertEquals(outputContent, entries.getValue("outputs/$outputPath").toString(Charsets.UTF_8))
        assertTrue(json.decodeFromString<List<FinalCandidateRecord>>(entries.getValue("authority/final-candidates.json").toString(Charsets.UTF_8)).isNotEmpty())
        assertTrue(json.decodeFromString<List<ContributionEdge>>(entries.getValue("authority/contribution-lineage/$winnerId.json").toString(Charsets.UTF_8)).isNotEmpty())
        assertTrue(json.decodeFromString<List<PublicationStateEvent>>(entries.getValue("authority/publication-history.json").toString(Charsets.UTF_8)).any { it.state == PublicationState.ALL_NON_DAEMON_AGENTS_TERMINATED })
        assertEquals(sha256Hex(metricsBytes), manifest.runMetricsSha256)
        assertEquals(sha256Hex(admissionBytes), manifest.acceptanceAdmissionSha256)

        val reclassifiedAuthorities = mapOf(
            "authority/council-field.json" to RunManifestEntryCategory.ARCHIVE,
            "authority/worker-atlas.json" to RunManifestEntryCategory.DATABASE,
            "authority/grader-atlas.json" to RunManifestEntryCategory.LOG,
            "authority/final-candidates.json" to RunManifestEntryCategory.ARCHIVE,
            "authority/final-deliverable-references.json" to RunManifestEntryCategory.OUTPUT,
            "authority/publication-history.json" to RunManifestEntryCategory.LOG,
            "authority/contribution-lineage/$winnerId.json" to RunManifestEntryCategory.ARCHIVE,
            metricsPath to RunManifestEntryCategory.OUTPUT,
            AcceptanceAdmissionArtifact.FILE_NAME to RunManifestEntryCategory.OUTPUT,
            "control/${PersistedRunStateStore.STATE_FILE_NAME}" to
                RunManifestEntryCategory.ARCHIVE,
            "metadata/run-config.json" to RunManifestEntryCategory.ARCHIVE,
            "metadata/binary.json" to RunManifestEntryCategory.ARCHIVE
        )
        reclassifiedAuthorities.forEach { (path, replacementCategory) ->
            val restamped = entries.toMutableMap()
            val restampedManifest = manifest.copy(
                entries = manifest.entries.map { entry ->
                    if (entry.path == path) entry.copy(category = replacementCategory) else entry
                }
            )
            restamped["manifest.json"] = json.encodeToString(
                RunManifest.serializer(),
                restampedManifest
            ).toByteArray()
            val verification = RunManifestVerifier.verify(zipEntries(restamped), expectedRunId = runId)
            assertFalse(verification.isValid, "Restamped category must fail: $path")
            assertTrue(
                verification.errors.any {
                    it.contains(path) && it.contains("expected")
                },
                verification.errors.joinToString()
            )
        }

        val detachedReference = entries.toMutableMap()
        val detachedReferenceManifest = manifest.copy(
            entries = manifest.entries.map { entry ->
                if (entry.path == "outputs/$outputPath") {
                    entry.copy(
                        mediaType = "text/plain",
                        finalDeliverableReferences = entry.finalDeliverableReferences.map {
                            it.copy(
                                relativePath = "unrelated-output.md",
                                mediaType = "text/plain",
                                contentSha256 = sha256Hex("unrelated".toByteArray())
                            )
                        }
                    )
                } else {
                    entry
                }
            }
        )
        detachedReference["manifest.json"] = json.encodeToString(
            RunManifest.serializer(),
            detachedReferenceManifest
        ).toByteArray()
        val detachedReferenceVerification = RunManifestVerifier.verify(
            zipEntries(detachedReference),
            expectedRunId = runId
        )
        assertFalse(detachedReferenceVerification.isValid)
        assertTrue(
            detachedReferenceVerification.errors.any {
                it.contains("does not bind exact output") ||
                    it.contains("not bound to its exact output")
            },
            detachedReferenceVerification.errors.joinToString()
        )

        val omittedV4Projection = entries.toMutableMap()
        val omittedV4ProjectionManifest = manifest.copy(
            entries = manifest.entries.map { entry ->
                if (entry.category == RunManifestEntryCategory.OUTPUT) {
                    entry.copy(
                        mediaType = null,
                        finalDeliverableReferences = entry.finalDeliverableReferences.map {
                            it.copy(
                                relativePath = null,
                                mediaType = null,
                                contentSha256 = null
                            )
                        }
                    )
                } else {
                    entry
                }
            }
        )
        omittedV4Projection["manifest.json"] = json.encodeToString(
            RunManifest.serializer(),
            omittedV4ProjectionManifest
        ).toByteArray()
        val omittedV4ProjectionVerification = RunManifestVerifier.verify(
            zipEntries(omittedV4Projection),
            expectedRunId = runId
        )
        assertFalse(omittedV4ProjectionVerification.isValid)
        assertTrue(
            omittedV4ProjectionVerification.errors.any {
                it.contains("does not bind exact output") ||
                    it.contains("not bound to its exact output")
            },
            omittedV4ProjectionVerification.errors.joinToString()
        )
        assertEquals(
            acceptanceAdmission,
            json.decodeFromString(
                AcceptanceAdmissionArtifact.serializer(),
                admissionBytes.toString(Charsets.UTF_8)
            )
        )
        assertTrue(
            manifest.entries.any {
                it.path == metricsPath &&
                    it.category == RunManifestEntryCategory.RUN_METRICS
            }
        )
        assertTrue(
            manifest.entries.any {
                it.path == AcceptanceAdmissionArtifact.FILE_NAME &&
                    it.category == RunManifestEntryCategory.ACCEPTANCE_ADMISSION
            }
        )
        val metrics = json.decodeFromString<RunMetricsV1>(
            metricsBytes.toString(Charsets.UTF_8)
        )
        assertEquals(RunMetricCatalog.orderedMetricIds, metrics.observations.map { it.metricId })

        val tamperedMetrics = entries.toMutableMap().apply {
            this[metricsPath] = "tampered".toByteArray()
        }
        val tamperedMetricsVerification = RunManifestVerifier.verify(
            zipEntries(tamperedMetrics),
            expectedRunId = runId
        )
        assertFalse(tamperedMetricsVerification.isValid)
        assertTrue(
            tamperedMetricsVerification.errors.any {
                it.contains("Hash mismatch for $metricsPath") ||
                    it.contains("Run metrics hash does not match manifest")
            }
        )

        val missingMetrics = entries.toMutableMap().apply { remove(metricsPath) }
        val missingMetricsVerification = RunManifestVerifier.verify(
            zipEntries(missingMetrics),
            expectedRunId = runId
        )

        val tamperedAdmission = entries.toMutableMap().apply {
            this[AcceptanceAdmissionArtifact.FILE_NAME] = "tampered".toByteArray()
        }
        val tamperedAdmissionVerification = RunManifestVerifier.verify(
            zipEntries(tamperedAdmission),
            expectedRunId = runId
        )
        assertFalse(tamperedAdmissionVerification.isValid)
        assertTrue(
            tamperedAdmissionVerification.errors.any {
                it.contains("Hash mismatch for ${AcceptanceAdmissionArtifact.FILE_NAME}") ||
                    it.contains("Acceptance admission hash does not match manifest")
            }
        )

        val missingAdmission = entries.toMutableMap().apply {
            remove(AcceptanceAdmissionArtifact.FILE_NAME)
        }
        val missingAdmissionVerification = RunManifestVerifier.verify(
            zipEntries(missingAdmission),
            expectedRunId = runId,
            expectedAcceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL
        )
        assertFalse(missingAdmissionVerification.isValid)
        assertTrue(
            missingAdmissionVerification.errors.any {
                it.contains("Missing attested entry: ${AcceptanceAdmissionArtifact.FILE_NAME}") ||
                    it.contains("requires exactly one ${AcceptanceAdmissionArtifact.FILE_NAME}")
            }
        )
        assertFalse(missingMetricsVerification.isValid)
        assertTrue(
            missingMetricsVerification.errors.any {
                it.contains("Missing attested entry: $metricsPath")
            }
        )

        val missingGraderAtlas = entries.toMutableMap().apply {
            remove("authority/grader-atlas.json")
        }
        val missingGraderAtlasVerification = RunManifestVerifier.verify(
            zipEntries(missingGraderAtlas),
            expectedRunId = runId
        )
        assertFalse(missingGraderAtlasVerification.isValid)
        assertTrue(
            missingGraderAtlasVerification.errors.any {
                it.contains("Missing attested entry: authority/grader-atlas.json") ||
                    it.contains("Missing required evidence category: GRADER_ATLAS")
            }
        )

        val renamedMetrics = entries.toMutableMap()
        renamedMetrics["renamed-run-metrics.json"] = renamedMetrics.remove(metricsPath)!!
        renamedMetrics["manifest.json"] = json.encodeToString(
            RunManifest.serializer(),
            manifest.copy(
                entries = manifest.entries.map { entry ->
                    if (entry.path == metricsPath) {
                        entry.copy(path = "renamed-run-metrics.json")
                    } else {
                        entry
                    }
                }
            )
        ).toByteArray()
        val renamedMetricsVerification = RunManifestVerifier.verify(
            zipEntries(renamedMetrics),
            expectedRunId = runId
        )
        assertFalse(renamedMetricsVerification.isValid)
        assertTrue(
            renamedMetricsVerification.errors.any {
                it.contains("requires exactly one $metricsPath")
            }
        )

        val malformedMetricsBytes = "{}".toByteArray()
        val restampedMetrics = entries.toMutableMap()
        restampedMetrics[metricsPath] = malformedMetricsBytes
        restampedMetrics["manifest.json"] = json.encodeToString(
            RunManifest.serializer(),
            manifest.copy(
                runMetricsSha256 = sha256Hex(malformedMetricsBytes),
                entries = manifest.entries.map { entry ->
                    if (entry.path == metricsPath) {
                        entry.copy(
                            sizeBytes = malformedMetricsBytes.size.toLong(),
                            sha256 = sha256Hex(malformedMetricsBytes)
                        )
                    } else {
                        entry
                    }
                }
            )
        ).toByteArray()
        val restampedMetricsVerification = RunManifestVerifier.verify(
            zipEntries(restampedMetrics),
            expectedRunId = runId
        )
        assertFalse(restampedMetricsVerification.isValid)
        assertTrue(
            restampedMetricsVerification.errors.any {
                it.contains("Invalid $metricsPath")
            }
        )

        val sourcePath = "control/${PersistedRunStateStore.STATE_FILE_NAME}"
        assertEquals(exactPersistedBytes.toList(), entries[sourcePath]?.toList())
        val archivedState = json.decodeFromString<PersistedRunState>(
            assertNotNull(entries[sourcePath]).toString(Charsets.UTF_8)
        )
        assertEquals(CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE, archivedState.councilExecutionPurpose)
        assertEquals(AcceptanceProfile.APPENDIX_B_FULL, archivedState.acceptanceProfile)

        val entryTimes = unzipEntryTimes(bundle)
        assertTrue(entryTimes.isNotEmpty())
        assertTrue(entryTimes.values.all { it > Instant.parse("2020-01-01T00:00:00Z").toEpochMilli() })
        assertTrue(
            entryTimes.values.all { abs(it - completedAtMs) <= 2_000L },
            entryTimes.entries.joinToString { "${it.key}=${Instant.ofEpochMilli(it.value)}" }
        )

        val removedSource = entries.toMutableMap().apply { remove(sourcePath) }
        val removedVerification = RunManifestVerifier.verify(
            zipEntries(removedSource),
            expectedRunId = runId,
            expectedCouncilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            expectedAcceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL
        )
        assertFalse(removedVerification.isValid)
        assertTrue(
            removedVerification.errors.any {
                it.contains("Missing attested entry: $sourcePath") ||
                    it.contains("Missing run-config attestation source: $sourcePath")
            }
        )

        val standardStateBytes = json.encodeToString(
            PersistedRunState.serializer(),
            archivedState.copy(councilExecutionPurpose = CouncilExecutionPurpose.STANDARD)
        ).toByteArray()
        val changedSource = entries.toMutableMap().apply {
            this[sourcePath] = standardStateBytes
        }
        val changedVerification = RunManifestVerifier.verify(
            zipEntries(changedSource),
            expectedRunId = runId,
            expectedCouncilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            expectedAcceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL
        )
        assertFalse(changedVerification.isValid)
        assertTrue(
            changedVerification.errors.any {
                it.contains("Run-config attestation source hash mismatch") ||
                    it.contains("Manifest Council execution purpose does not match persisted run config")
            },
            changedVerification.errors.joinToString()
        )

        val restamped = entries.toMutableMap()
        restamped[sourcePath] = standardStateBytes
        val restampedAttestation = json.decodeFromString<RunConfigAttestation>(
            restamped.getValue("metadata/run-config.json").toString(Charsets.UTF_8)
        ).copy(
            sourceSizeBytes = standardStateBytes.size.toLong(),
            sourceSha256 = sha256Hex(standardStateBytes)
        )
        val restampedAttestationBytes = json.encodeToString(
            RunConfigAttestation.serializer(),
            restampedAttestation
        ).toByteArray()
        restamped["metadata/run-config.json"] = restampedAttestationBytes
        val restampedManifest = json.decodeFromString<RunManifest>(
            restamped.getValue("manifest.json").toString(Charsets.UTF_8)
        ).let { manifest ->
            manifest.copy(
                councilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
                entries = manifest.entries.map { entry ->
                    when (entry.path) {
                        sourcePath -> entry.copy(
                            sizeBytes = standardStateBytes.size.toLong(),
                            sha256 = sha256Hex(standardStateBytes)
                        )
                        "metadata/run-config.json" -> entry.copy(
                            sizeBytes = restampedAttestationBytes.size.toLong(),
                            sha256 = sha256Hex(restampedAttestationBytes)
                        )
                        else -> entry
                    }
                }
            )
        }
        restamped["manifest.json"] = json.encodeToString(
            RunManifest.serializer(),
            restampedManifest
        ).toByteArray()
        val restampedVerification = RunManifestVerifier.verify(
            zipEntries(restamped),
            expectedRunId = runId,
            expectedCouncilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            expectedAcceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL
        )
        assertFalse(restampedVerification.isValid)
        assertTrue(
            restampedVerification.errors.any {
                it.contains("does not match expected purpose=appendix-b-acceptance")
            },
            restampedVerification.errors.joinToString()
        )
        assertFalse(
            restampedVerification.errors.any {
                it.contains("attestation source hash mismatch") ||
                    it.contains("does not match persisted run config")
            },
            restampedVerification.errors.joinToString()
        )

        val normalProfileStateBytes = json.encodeToString(
            PersistedRunState.serializer(),
            archivedState.copy(acceptanceProfile = AcceptanceProfile.NORMAL)
        ).toByteArray()
        val profileRestamped = entries.toMutableMap()
        profileRestamped[sourcePath] = normalProfileStateBytes
        val profileRestampedAttestationBytes = json.encodeToString(
            RunConfigAttestation.serializer(),
            json.decodeFromString<RunConfigAttestation>(
                profileRestamped.getValue("metadata/run-config.json").toString(Charsets.UTF_8)
            ).copy(
                sourceSizeBytes = normalProfileStateBytes.size.toLong(),
                sourceSha256 = sha256Hex(normalProfileStateBytes)
            )
        ).toByteArray()
        profileRestamped["metadata/run-config.json"] = profileRestampedAttestationBytes
        val profileRestampedManifest = json.decodeFromString<RunManifest>(
            profileRestamped.getValue("manifest.json").toString(Charsets.UTF_8)
        ).let { manifest ->
            manifest.copy(
                acceptanceProfile = AcceptanceProfile.NORMAL,
                entries = manifest.entries.map { entry ->
                    when (entry.path) {
                        sourcePath -> entry.copy(
                            sizeBytes = normalProfileStateBytes.size.toLong(),
                            sha256 = sha256Hex(normalProfileStateBytes)
                        )
                        "metadata/run-config.json" -> entry.copy(
                            sizeBytes = profileRestampedAttestationBytes.size.toLong(),
                            sha256 = sha256Hex(profileRestampedAttestationBytes)
                        )
                        else -> entry
                    }
                }
            )
        }
        profileRestamped["manifest.json"] = json.encodeToString(
            RunManifest.serializer(),
            profileRestampedManifest
        ).toByteArray()
        val profileRestampedVerification = RunManifestVerifier.verify(
            zipEntries(profileRestamped),
            expectedRunId = runId,
            expectedCouncilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            expectedAcceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL
        )
        assertFalse(profileRestampedVerification.isValid)
        assertTrue(
            profileRestampedVerification.errors.any {
                it.contains(
                    "acceptance profile=normal does not match expected profile=appendix-b-full"
                )
            },
            profileRestampedVerification.errors.joinToString()
        )
        assertFalse(
            profileRestampedVerification.errors.any {
                it.contains("attestation source hash mismatch") ||
                    it.contains("acceptance profile does not match persisted run config")
            },
            profileRestampedVerification.errors.joinToString()
        )

        listOf(2, 3).forEach { historicalSchemaVersion ->
            val historicalStateBytes = json.encodeToString(
                PersistedRunState.serializer(),
                archivedState.copy(
                    councilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
                    acceptanceProfile = AcceptanceProfile.NORMAL
                )
            ).toByteArray()
            val historicalAttestationBytes = json.encodeToString(
                RunConfigAttestation.serializer(),
                json.decodeFromString<RunConfigAttestation>(
                    entries.getValue("metadata/run-config.json").toString(Charsets.UTF_8)
                ).copy(
                    sourceSizeBytes = historicalStateBytes.size.toLong(),
                    sourceSha256 = sha256Hex(historicalStateBytes)
                )
            ).toByteArray()
            val historicalEntries = entries.toMutableMap().apply {
                this[sourcePath] = historicalStateBytes
                this["metadata/run-config.json"] = historicalAttestationBytes
                remove(AcceptanceAdmissionArtifact.FILE_NAME)
                if (historicalSchemaVersion == 2) {
                    remove(metricsPath)
                }
            }
            val historicalManifest = manifest.copy(
                schemaVersion = historicalSchemaVersion,
                councilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
                acceptanceProfile = AcceptanceProfile.NORMAL,
                runMetricsSha256 = if (historicalSchemaVersion == 3) {
                    manifest.runMetricsSha256
                } else {
                    null
                },
                acceptanceAdmissionSha256 = null,
                entries = manifest.entries
                    .filterNot {
                        it.path == AcceptanceAdmissionArtifact.FILE_NAME ||
                            (historicalSchemaVersion == 2 && it.path == metricsPath)
                    }
                    .map { entry ->
                        when {
                            entry.path == sourcePath -> entry.copy(
                                sizeBytes = historicalStateBytes.size.toLong(),
                                sha256 = sha256Hex(historicalStateBytes)
                            )
                            entry.path == "metadata/run-config.json" -> entry.copy(
                                sizeBytes = historicalAttestationBytes.size.toLong(),
                                sha256 = sha256Hex(historicalAttestationBytes)
                            )
                            entry.category == RunManifestEntryCategory.OUTPUT -> entry.copy(
                                mediaType = null,
                                finalDeliverableReferences =
                                    entry.finalDeliverableReferences.map {
                                        it.copy(
                                            relativePath = null,
                                            mediaType = null,
                                            contentSha256 = null
                                        )
                                    }
                            )
                            else -> entry
                        }
                    }
            )
            historicalEntries["manifest.json"] = json.encodeToString(
                RunManifest.serializer(),
                historicalManifest
            ).toByteArray()

            val historicalVerification = RunManifestVerifier.verify(
                zipEntries(historicalEntries),
                expectedRunId = runId
            )
            assertTrue(
                historicalVerification.isValid,
                "schema-v$historicalSchemaVersion: ${historicalVerification.errors.joinToString()}"
            )
            assertEquals(historicalSchemaVersion, historicalVerification.manifest?.schemaVersion)
            assertEquals(AcceptanceProfile.NORMAL, historicalVerification.manifest?.acceptanceProfile)
            assertTrue(
                historicalVerification.manifest
                    ?.entries
                    ?.filter { it.category == RunManifestEntryCategory.OUTPUT }
                    ?.all { entry ->
                        entry.mediaType == null &&
                            entry.finalDeliverableReferences.all {
                                it.relativePath == null &&
                                    it.mediaType == null &&
                                    it.contentSha256 == null
                            }
                    } == true
            )
        }

        assertTrue(RunMetricsV1ArtifactStore(dbFactory).file(runId).delete())
        val missingMetricsFailure = assertFailsWith<IllegalArgumentException> {
            service.buildResultsBundle(runId, completionRecord)
        }
        assertTrue(
            missingMetricsFailure.message.orEmpty().contains(
                "Non-normal acceptance archive requires ${RunMetricsV1ArtifactStore.FILE_NAME}"
            )
        )
    }

    @Test
    fun `buildResultsBundle includes starting field uploaded inputs and meta archive outputs`() = runTest {
        val rootDir = createTempDirectory("run-archive-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-test"
        val groupId = "group-0"

        dbFactory.get(runId, groupId)
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val artifactDir = File(rootDir, "evomolior/runs/$runId/artifacts/docs/specs")
        artifactDir.mkdirs()
        val inputFile = File(artifactDir, "brief.txt")
        inputFile.writeText("hello archive")

        val artifact = RunInputArtifact(
            relativePath = "docs/specs/brief.txt",
            sourceName = "brief.txt",
            mimeType = "text/plain",
            sizeBytes = inputFile.length(),
            sha256 = sha256Hex(inputFile.readBytes()),
            kind = RunInputArtifactKind.TEXT,
            textPreview = "hello archive"
        )

        val startingField = StartingField.default("archive the run", runId = runId).copy(
            inputArtifacts = listOf(artifact)
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_DEAD_ENDS,
                groupId = groupId,
                label = "Dead Ends — $groupId",
                contentJson = """[{"approach":"wrong","reason":"failed","domain":"test"}]""",
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_ROUND_SUMMARY,
                groupId = groupId,
                label = "Round summary — $groupId",
                contentJson = """{"groupId":"$groupId","roundIndex":0,"summary":"hello"}""",
                runObjective = startingField.objective,
                finalScore = 88.0,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = groupId,
                        finalAnswerSummary = "Round 1 final answer: winner=$groupId",
                        finalDeliverableHtml = """
                            <!doctype html>
                            <html lang="en">
                            <head><meta charset="utf-8"><title>MindVortex</title></head>
                            <body><article><h1>MindVortex</h1><p>hello archive article</p></article></body>
                            </html>
                        """.trimIndent(),
                        judgeOutputsByGrader = mapOf("grader-0" to "Judge output"),
                        submissionSummariesByGroup = mapOf(groupId to "<article>hello</article>"),
                        substrateHealthByGroup = listOf(
                            RunSubstrateHealthSummary(
                                groupId = groupId,
                                kbCount = 3,
                                kgCount = 1,
                                activeToolCount = 1,
                                toolExecutionCount = 2,
                                kgFactSubmissionCount = 3,
                                toolSubmissionCount = 1,
                                kgAbstentionRationaleCount = 1,
                                toolAbstentionRationaleCount = 2,
                                noActionFallbackSubmissionCount = 4,
                                trustDecayEventCount = 2,
                                lowTrustArchivedByDecayCount = 3,
                                stagedSubmissionCount = 4,
                                promotedSubmissionCount = 2,
                                pendingSubmissionCount = 1,
                                rejectedSubmissionCount = 1,
                                outlierSubmissionCount = 1,
                                roundSubmissionSummaryPresent = true
                            )
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertTrue(entries.keys.contains("manifest.json"))
        assertTrue(entries.keys.contains("input-artifacts.json"))
        assertTrue(entries.keys.contains("starting-field.json"))
        assertTrue(entries.keys.contains("inputs/docs/specs/brief.txt"))
        assertTrue(entries.keys.contains("outputs/final-article.html"))
        assertTrue(entries.keys.contains("outputs/high-level-run-log.txt"))
        assertTrue(entries.keys.any { it.startsWith("outputs/dead_ends_export") })
        assertTrue(entries.keys.any { it.startsWith("outputs/round_summary") })
        assertTrue(entries["manifest.json"]!!.contains("\"artifactCount\":1"))
        assertTrue(entries["input-artifacts.json"]!!.contains("\"docs/specs/brief.txt\""))
        assertTrue(entries["starting-field.json"]!!.contains("\"inputArtifacts\""))
        assertTrue(entries["outputs/final-article.html"]!!.contains("<!doctype html>"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("<h1>MindVortex</h1>"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("hello archive article"))
        assertTrue(entries["outputs/high-level-run-log.txt"]!!.contains("Final outputs: final-article.html"))
        assertTrue(entries["outputs/high-level-run-log.txt"]!!.contains("Substrate health:"))
        assertTrue(entries["outputs/high-level-run-log.txt"]!!.contains("kb=3, kg=1"))
        assertTrue(entries["outputs/high-level-run-log.txt"]!!.contains("kgSubmissions=3"))
        assertTrue(entries["outputs/high-level-run-log.txt"]!!.contains("toolSubmissions=1"))
        assertTrue(entries["outputs/high-level-run-log.txt"]!!.contains("kgAbstain=1"))
        assertTrue(entries["outputs/high-level-run-log.txt"]!!.contains("toolAbstain=2"))
        assertTrue(entries["outputs/high-level-run-log.txt"]!!.contains("noActionFallback=4"))
        assertTrue(entries["outputs/high-level-run-log.txt"]!!.contains("decayEvents=2"))
        assertTrue(entries["outputs/high-level-run-log.txt"]!!.contains("decayArchived=3"))
        assertTrue(entries["outputs/high-level-run-log.txt"]!!.contains("outlierSubmissions=1"))
        assertEquals("hello archive", entries["inputs/docs/specs/brief.txt"])
    }

    @Test
    fun `buildResultsBundle emits machine readable research deliverables with claim schema`() = runTest {
        val rootDir = createTempDirectory("run-archive-research-pack-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-research-pack"
        val groupId = "group-0"
        val groupDb = dbFactory.get(runId, groupId)
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        groupDb.eventLogDao().insert(
            EventLogEntryEntity(
                id = "search-event",
                timestampMs = 1_000L,
                eventType = EventTypes.TOOL_EXEC,
                agentId = "agent-0",
                groupId = groupId,
                runId = runId,
                generation = 0,
                payloadJson = """{"tool":"search_kb","query":"evidence recall baseline"}"""
            )
        )
        groupDb.eventLogDao().insert(
            EventLogEntryEntity(
                id = "artifact-read-event",
                timestampMs = 1_020L,
                eventType = EventTypes.TOOL_EXEC,
                agentId = "agent-0",
                groupId = groupId,
                runId = runId,
                generation = 0,
                payloadJson = """{"toolCall":{"toolName":"read_run_artifact"},"relativePath":"papers/paper-1.md"}"""
            )
        )
        groupDb.eventLogDao().insert(
            EventLogEntryEntity(
                id = "source-access-event",
                timestampMs = 1_040L,
                eventType = EventTypes.TOOL_EXEC,
                agentId = "agent-0",
                groupId = groupId,
                runId = runId,
                generation = 0,
                payloadJson = """{"name":"request_source_access","url":"https://example.com/paper-1"}"""
            )
        )
        groupDb.eventLogDao().insert(
            EventLogEntryEntity(
                id = "write-event-not-search-trace",
                timestampMs = 1_060L,
                eventType = EventTypes.TOOL_EXEC,
                agentId = "agent-0",
                groupId = groupId,
                runId = runId,
                generation = 0,
                payloadJson = """{"tool":"write_staging","type":"DOC"}"""
            )
        )
        groupDb.eventLogDao().insert(
            EventLogEntryEntity(
                id = "budget-event",
                timestampMs = 1_100L,
                eventType = EventTypes.BUDGET_DEDUCT,
                agentId = "agent-0",
                groupId = groupId,
                runId = runId,
                generation = 0,
                payloadJson = """{"cost":1.25,"tokens":42}"""
            )
        )

        val artifact = RunInputArtifact(
            relativePath = "papers/paper-1.md",
            sourceName = "paper-1.md",
            mimeType = "text/markdown",
            sizeBytes = 25,
            sha256 = sha256Hex("paper evidence".toByteArray()),
            kind = RunInputArtifactKind.TEXT,
            textPreview = "Paper one reports evidence recall gains."
        )
        val startingField = StartingField.default("Compare evidence recall for research synthesis", runId = runId).copy(
            groupCount = 1,
            inputArtifacts = listOf(artifact)
        )
        val completionRecord = RunCompletionRecord(
            runId = runId,
            winnerId = groupId,
            finalAnswerSummary = "The run found higher evidence recall than the baseline according to https://example.com/paper-1.",
            generatedOutputFiles = listOf(
                GeneratedOutputFile(
                    relativePath = "research-summary.md",
                    mediaType = "text/markdown",
                    content = "The best group produced a citation-verified synthesis using https://example.com/paper-1."
                )
            ),
            judgeOutputsByGrader = mapOf("grader-0" to "Judge accepted the evidence-linked claim."),
            submissionSummariesByGroup = mapOf(groupId to "The group reused paper evidence and cited https://example.com/paper-1."),
            substrateHealthByGroup = listOf(
                RunSubstrateHealthSummary(
                    groupId = groupId,
                    kbCount = 2,
                    kgCount = 3,
                    activeToolCount = 1,
                    toolExecutionCount = 4,
                    stagedSubmissionCount = 5,
                    promotedSubmissionCount = 3,
                    pendingSubmissionCount = 1,
                    rejectedSubmissionCount = 1,
                    outlierSubmissionCount = 0,
                    roundSubmissionSummaryPresent = true
                )
            ),
            completedAtMs = 61_000L
        )

        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField - $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = 1_000L
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "round-summary-score-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_ROUND_SUMMARY,
                groupId = groupId,
                label = "Round Summary - $groupId",
                contentJson = """{"groupId":"$groupId","roundIndex":0,"summary":"scored"}""",
                runObjective = startingField.objective,
                finalScore = 88.0,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = 60_000L
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed - $runId",
                contentJson = json.encodeToString(RunCompletionRecord.serializer(), completionRecord),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = 61_000L
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        val requiredOutputs = listOf(
            "final_answer.md",
            "final_report.md",
            "claim_ledger.jsonl",
            "evidence_table.csv",
            "citation_table.csv",
            "citation_map.json",
            "unknowns_and_contradictions.md",
            "search_trace.jsonl",
            "cost_report.json",
            "benchmark_results.csv",
            "run_replay.html",
            "outputs/final_answer.md",
            "outputs/final_report.md",
            "outputs/claim_ledger.jsonl",
            "outputs/evidence_table.csv",
            "outputs/citation_table.csv",
            "outputs/citation_map.json",
            "outputs/unknowns_and_contradictions.md",
            "outputs/search_trace.jsonl",
            "outputs/cost_report.json",
            "outputs/benchmark_results.csv",
            "outputs/run_replay.html"
        )
        requiredOutputs.forEach { path -> assertTrue(entries.containsKey(path), "$path missing from bundle") }

        val claimLedgerLines = entries["outputs/claim_ledger.jsonl"]!!.lineSequence().filter { it.isNotBlank() }.toList()
        assertTrue(claimLedgerLines.isNotEmpty(), "claim ledger should contain extracted major claims")
        val firstClaim = json.parseToJsonElement(claimLedgerLines.first()).jsonObject
        assertEquals("result", firstClaim["claim_type"]!!.jsonPrimitive.content)
        assertTrue(firstClaim["claim"]!!.jsonPrimitive.content.contains("evidence recall"))
        assertTrue(firstClaim["supporting_sources"]!!.jsonArray.any {
            it.jsonPrimitive.content == "https://example.com/paper-1"
        })
        assertTrue(firstClaim["quoted_or_paraphrased_evidence"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(firstClaim["confidence"]!!.jsonPrimitive.doubleOrNull!! > 0.0)
        assertTrue(firstClaim["used_in_final_answer"]!!.jsonPrimitive.content.toBoolean())

        assertTrue(entries["outputs/evidence_table.csv"]!!.contains("papers/paper-1.md"))
        assertEquals(entries["outputs/evidence_table.csv"], entries["outputs/citation_table.csv"])
        assertTrue(entries["outputs/citation_map.json"]!!.contains("https://example.com/paper-1"))
        assertTrue(entries["outputs/benchmark_results.csv"]!!.contains("run_id,objective,winner_id"))
        assertTrue(entries["outputs/run_replay.html"]!!.contains("<h1>EvoMolior Run Replay</h1>"))
        val searchTrace = entries["outputs/search_trace.jsonl"]!!
        assertTrue(searchTrace.contains("search_kb"))
        assertTrue(searchTrace.contains("read_run_artifact"))
        assertTrue(searchTrace.contains("request_source_access"))
        assertFalse(searchTrace.contains("write_staging"))
        val costReport = json.parseToJsonElement(entries["outputs/cost_report.json"]!!).jsonObject
        assertEquals(1.25, costReport["observed_budget_cost"]!!.jsonPrimitive.doubleOrNull)
        assertEquals(42.0, costReport["observed_token_count"]!!.jsonPrimitive.doubleOrNull)
        assertEquals(1.0, costReport["wall_clock_minutes"]!!.jsonPrimitive.doubleOrNull, entries["outputs/cost_report.json"])
        assertEquals(88.0, costReport["quality_score"]!!.jsonPrimitive.doubleOrNull)
        assertEquals(70.4, costReport["cost_normalized_score"]!!.jsonPrimitive.doubleOrNull)
        assertEquals(88.0, costReport["latency_normalized_score"]!!.jsonPrimitive.doubleOrNull)
        val primaryMetrics = costReport["primary_metrics"]!!.jsonObject
        assertTrue(primaryMetrics.containsKey("answer_correctness"))
        assertTrue(primaryMetrics.containsKey("evidence_recall"))
        assertTrue(primaryMetrics.containsKey("citation_precision"))
        assertTrue(primaryMetrics.containsKey("contradiction_handling"))
        assertTrue(primaryMetrics.containsKey("novel_useful_synthesis"))
        assertTrue(primaryMetrics.containsKey("cost_normalized_score"))
        assertTrue(primaryMetrics.containsKey("latency_normalized_score"))
        assertTrue(primaryMetrics.containsKey("substrate_compounding"))
        assertEquals(88.0, primaryMetrics["answer_correctness"]!!.jsonObject["value"]!!.jsonPrimitive.doubleOrNull)
        assertEquals(
            3,
            primaryMetrics["substrate_compounding"]!!.jsonObject["accepted_kg_facts"]!!.jsonPrimitive.content.toInt()
        )
        assertTrue(costReport["win_thresholds"]!!.jsonObject.containsKey("near_frontier_low_cost"))
    }

    @Test
    fun `buildResultsBundle preserves authored final answer when research pack uses same filename`() = runTest {
        val rootDir = createTempDirectory("run-archive-research-pack-collision-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-research-pack-collision"
        val groupId = "group-0"
        val metaDb = dbFactory.get(runId, "__meta_archive__")
        val startingField = StartingField.default("Write final_answer.md and evidence pack", runId = runId).copy(
            groupCount = 1
        )
        val completionRecord = RunCompletionRecord(
            runId = runId,
            winnerId = groupId,
            finalAnswerSummary = "The archive should preserve authored output and synthesized evidence pack.",
            generatedOutputFiles = listOf(
                GeneratedOutputFile(
                    relativePath = "final_answer.md",
                    mediaType = "text/markdown",
                    content = "AUTHORED FINAL ANSWER BODY"
                )
            )
        )

        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField - $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = 1_000L
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed - $runId",
                contentJson = json.encodeToString(RunCompletionRecord.serializer(), completionRecord),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = 2_000L
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertEquals("AUTHORED FINAL ANSWER BODY", entries["outputs/final_answer.md"])
        assertTrue(entries["final_answer.md"]!!.contains("# Final Answer"))
        assertTrue(entries["final_report.md"]!!.contains("# Final Answer"))
        assertTrue(entries["claim_ledger.jsonl"] != null)
        assertTrue(entries["outputs/claim_ledger.jsonl"] != null)
    }

    @Test
    fun `archiveRun writes machine readable deliverables to results outputs directory`() = runTest {
        val rootDir = createTempDirectory("run-archive-research-pack-write-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-research-pack-write"
        val groupId = "group-0"
        val startingField = StartingField.default("Write machine-readable research outputs", runId = runId).copy(
            groupCount = 1
        )
        val completionRecord = RunCompletionRecord(
            runId = runId,
            winnerId = groupId,
            finalAnswerSummary = "The run produced a measurable evidence ledger from https://example.com/source.",
            judgeOutputsByGrader = mapOf("grader-0" to "Evidence ledger is present."),
            submissionSummariesByGroup = mapOf(groupId to "The group provided source-linked claims.")
        )

        service.archiveRun(
            runId = runId,
            groupIds = listOf(groupId),
            winnerId = groupId,
            objective = startingField.objective,
            startingFieldJson = json.encodeToString(StartingField.serializer(), startingField),
            finalGeneration = 0,
            completionRecord = completionRecord
        )

        val outputDir = File(rootDir, "evomolior/runs/$runId/results/outputs")
        listOf(
            "final_answer.md",
            "final_report.md",
            "claim_ledger.jsonl",
            "evidence_table.csv",
            "citation_table.csv",
            "citation_map.json",
            "unknowns_and_contradictions.md",
            "search_trace.jsonl",
            "cost_report.json",
            "benchmark_results.csv",
            "run_replay.html"
        ).forEach { fileName ->
            assertTrue(File(outputDir, fileName).isFile, "$fileName missing from results outputs directory")
        }
        assertTrue(File(rootDir, "evomolior/runs/$runId/results/results.zip").isFile)
        assertTrue(File(outputDir, "claim_ledger.jsonl").readText().contains("supporting_sources"))
    }

    @Test
    fun `research claim extraction preserves sentence-local html anchor provenance`() = runTest {
        val rootDir = createTempDirectory("run-archive-anchor-provenance-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-anchor-provenance"
        val startingField = StartingField.default("Archive grounded market claims", runId = runId, groupCount = 1)

        service.archiveRun(
            runId = runId,
            groupIds = listOf("group-0"),
            winnerId = "group-0",
            objective = startingField.objective,
            startingFieldJson = json.encodeToString(StartingField.serializer(), startingField),
            finalGeneration = 1,
            completionRecord = RunCompletionRecord(
                runId = runId,
                winnerId = "group-0",
                generatedOutputFiles = listOf(
                    GeneratedOutputFile(
                        relativePath = "market-evidence.html",
                        mediaType = "text/html",
                        content = """
                            <!doctype html>
                            <html lang="en"><head><meta charset="utf-8"><title>Market evidence</title></head>
                            <body><article>
                            <p>Independent analysis documents sustained enterprise adoption in the 2025 market
                            <a href="https://example.com/reports/adoption">adoption report</a>.</p>
                            <p>A nearby unsupported projection says demand will double within one year.</p>
                            </article></body></html>
                        """.trimIndent()
                    )
                )
            )
        )

        val claims = File(rootDir, "evomolior/runs/$runId/results/outputs/claim_ledger.jsonl")
            .readLines()
            .filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }
        val supported = claims.first { it["claim"]!!.jsonPrimitive.content.contains("enterprise adoption") }
        val unsupported = claims.first { it["claim"]!!.jsonPrimitive.content.contains("unsupported projection") }

        assertEquals(
            listOf("https://example.com/reports/adoption"),
            supported["supporting_sources"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertTrue(unsupported["supporting_sources"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `archiveRun canonicalises duplicate starting fields and keeps the richest sealed snapshot`() = runTest {
        val rootDir = createTempDirectory("run-archive-starting-field-singleton-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-starting-field-singleton"
        val metaDao = dbFactory.get(runId, "__meta_archive__").metaArchiveDao()
        val sealed = StartingField.default("Preserve Council state", runId = runId, groupCount = 1).copy(
            stageContents = listOf("stage-0", "stage-1"),
            consistencyReport = "Council validation passed",
            seal = StartingFieldSeal(
                schemaVersion = CouncilFieldSealing.SEAL_SCHEMA_VERSION,
                workerFieldHash = "worker",
                graderFieldHash = "grader",
                atlasHash = "atlas",
                envelopeHash = "envelope",
                consistencyReportHash = "report"
            )
        )
        val richerUnsealed = StartingField.default(
            "Preserve Council state",
            runId = runId,
            groupCount = 1
        ).copy(stageContents = listOf("stage-0", "stage-1", "stage-2"))
        val degraded = StartingField.default("Preserve Council state", runId = runId, groupCount = 1)

        metaDao.insert(
            MetaArchiveEntryEntity(
                id = "legacy-degraded",
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "degraded",
                contentJson = json.encodeToString(StartingField.serializer(), richerUnsealed),
                runObjective = degraded.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 12,
                archivedAtMs = 1_000L
            )
        )
        metaDao.insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "sealed",
                contentJson = json.encodeToString(StartingField.serializer(), sealed),
                runObjective = sealed.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = 2_000L
            )
        )

        val preCanonicalBundle = service.buildResultsBundle(runId)
        assertNotNull(preCanonicalBundle)
        assertTrue(unzipEntries(preCanonicalBundle)["starting-field.json"]!!.contains("stage-1"))

        service.archiveRun(
            runId = runId,
            groupIds = listOf("group-0"),
            winnerId = "group-0",
            objective = degraded.objective,
            startingFieldJson = json.encodeToString(StartingField.serializer(), degraded),
            finalGeneration = 12,
            completionRecord = RunCompletionRecord(runId = runId, winnerId = "group-0")
        )

        val remaining = metaDao.getByRun(runId)
            .filter { it.entryType == RunArchiveService.ENTRY_STARTING_FIELD }
        assertEquals(1, remaining.size)
        assertEquals("starting-field-$runId", remaining.single().id)
        val decoded = json.decodeFromString(StartingField.serializer(), remaining.single().contentJson)
        assertEquals(listOf("stage-0", "stage-1"), decoded.stageContents)
        assertEquals(0, remaining.single().generation)
    }

    @Test
    fun `archiveRun fails instead of silently completing when deterministic deliverables cannot be written`() = runTest {
        val rootDir = createTempDirectory("run-archive-required-output-failure-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-required-output-failure"

        val failure = assertFailsWith<IllegalStateException> {
            service.archiveRun(
                runId = runId,
                groupIds = listOf("group-0"),
                winnerId = "group-0",
                objective = "invalid archive projection",
                startingFieldJson = """{"not":"a-starting-field"}""",
                finalGeneration = 0
            )
        }

        assertTrue(
            failure.message!!.contains("wireVersion=missing (pre-v62)"),
            failure.message
        )
    }

    @Test
    fun `archive rebuild rejects current wire bytes carrying a stale seal`() = runTest {
        val rootDir = createTempDirectory("run-archive-stale-seal-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-stale-seal"
        val current = StartingField.default("Reject stale seal", runId = runId)
        val encoded = json.parseToJsonElement(
            json.encodeToString(StartingField.serializer(), current)
        ).jsonObject
        val stale = JsonObject(
            encoded + (
                "seal" to buildJsonObject {
                    put("schemaVersion", "2")
                }
                )
        ).toString()
        dbFactory.get(runId, "__meta_archive__").metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField - $runId",
                contentJson = stale,
                runObjective = current.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.buildResultsBundle(runId)
        }

        assertTrue(failure.message.orEmpty().contains("seal.schemaVersion=2"))
        assertTrue(
            failure.message.orEmpty().contains(
                "Restamped or stale sealed fields cannot become current authority"
            )
        )
    }

    @Test
    fun `PublicationQuiescenceTest archive rejects a live grader without exit authority`() = runTest {
        val rootDir = createTempDirectory("run-archive-unaccounted-agent-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-unaccounted-agent"
        val groupId = "group-0"
        val group = AtlasGroupId(groupId)
        val agent = AtlasAgentId("grader-live")
        WorkerExecutionRepository(dbFactory.get(runId, groupId)).registerAgent(
            AgentLifecycleRegistration(
                registrationId = "registration:$runId:$groupId:${agent.value}",
                runId = runId,
                groupId = group,
                agentId = agent,
                role = "GraderCritic",
                generation = 1,
                classification = AgentLifecycleClassification.NON_DAEMON,
                registeredAt = KotlinInstant.parse("2026-07-12T10:00:00Z")
            ).withComputedHash()
        )
        val publication = PublicationStateRepository(dbFactory.getAtlas(runId))
        var previous: PublicationStateEvent? = null
        PublicationState.entries
            .takeWhile { it != PublicationState.MANIFEST_HASHED }
            .forEachIndexed { index, state ->
                val event = PublicationStateEvent(
                    eventId = "publication-${state.name}",
                    runId = runId,
                    state = state,
                    sequence = index,
                    previousEventHash = previous?.eventHash,
                    authorityReferenceIds = setOf("authority-${state.name}"),
                    occurredAt = KotlinInstant.parse("2026-07-12T10:00:00Z")
                ).withComputedHash()
                publication.append(event)
                previous = event
            }
        val startingField = StartingField.default(
            "Reject incomplete publication accounting",
            runId = runId,
            groupCount = 1
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.archiveRun(
                runId = runId,
                groupIds = listOf(groupId),
                winnerId = groupId,
                objective = startingField.objective,
                startingFieldJson = json.encodeToString(
                    StartingField.serializer(),
                    startingField
                ),
                finalGeneration = 1,
                completionRecord = RunCompletionRecord(runId = runId, winnerId = groupId)
            )
        }

        assertTrue(failure.message.orEmpty().contains("unaccounted non-daemon"))
    }

    @Test
    fun `PublicationQuiescenceTest archive rejects a live final writer without exit authority`() = runTest {
        val rootDir = createTempDirectory("run-archive-unaccounted-final-writer-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-unaccounted-final-writer"
        val groupId = "group-0"
        val group = AtlasGroupId(groupId)
        val agent = AtlasAgentId("final-writer")
        WorkerExecutionRepository(dbFactory.get(runId, groupId)).registerAgent(
            AgentLifecycleRegistration(
                registrationId = "registration:$runId:$groupId:${agent.value}",
                runId = runId,
                groupId = group,
                agentId = agent,
                role = "Builder",
                generation = 1,
                classification = AgentLifecycleClassification.NON_DAEMON,
                registeredAt = KotlinInstant.parse("2026-07-12T10:00:00Z")
            ).withComputedHash()
        )
        val publication = PublicationStateRepository(dbFactory.getAtlas(runId))
        var previous: PublicationStateEvent? = null
        PublicationState.entries
            .takeWhile { it != PublicationState.MANIFEST_HASHED }
            .forEachIndexed { index, state ->
                val event = PublicationStateEvent(
                    eventId = "publication-${state.name}",
                    runId = runId,
                    state = state,
                    sequence = index,
                    previousEventHash = previous?.eventHash,
                    authorityReferenceIds = setOf("authority-${state.name}"),
                    occurredAt = KotlinInstant.parse("2026-07-12T10:00:00Z")
                ).withComputedHash()
                publication.append(event)
                previous = event
            }
        val startingField = StartingField.default(
            "Reject incomplete publication accounting",
            runId = runId,
            groupCount = 1
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.archiveRun(
                runId = runId,
                groupIds = listOf(groupId),
                winnerId = groupId,
                objective = startingField.objective,
                startingFieldJson = json.encodeToString(
                    StartingField.serializer(),
                    startingField
                ),
                finalGeneration = 1,
                completionRecord = RunCompletionRecord(runId = runId, winnerId = groupId)
            )
        }

        assertTrue(failure.message.orEmpty().contains("unaccounted non-daemon"))
        assertTrue(failure.message.orEmpty().contains("final-writer"))
    }

    @Test
    fun `archive resumes exact bundle after manifest hash without rebuilding publication`() = runTest {
        val rootDir = createTempDirectory("run-archive-manifest-recovery-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-manifest-recovery"
        val groupId = "group-0"
        val startingField = StartingField.default(
            objective = "Recover an exact receiver archive after manifest persistence.",
            runId = runId,
            groupCount = 1
        )
        val completion = RunCompletionRecord(
            runId = runId,
            winnerId = groupId,
            completedRoundCount = 1,
            finalAnswerSummary = "Receiver-facing archive recovery fixture."
        )

        service.archiveRun(
            runId = runId,
            groupIds = listOf(groupId),
            winnerId = groupId,
            objective = startingField.objective,
            startingFieldJson = json.encodeToString(StartingField.serializer(), startingField),
            finalGeneration = 1,
            completionRecord = completion
        )
        val bundle = assertNotNull(service.writeResultsBundle(runId, completion))
        val exactHash = sha256Hex(bundle.readBytes())
        val publication = PublicationStateRepository(dbFactory.getAtlas(runId))
        var previous: PublicationStateEvent? = null
        PublicationState.entries
            .takeWhile { it != PublicationState.ARCHIVED }
            .forEachIndexed { index, state ->
                val authority = if (state == PublicationState.MANIFEST_HASHED) {
                    "results-bundle:${bundle.name}:$exactHash"
                } else {
                    "authority-${state.name}"
                }
                val event = PublicationStateEvent(
                    eventId = "recovery-publication-${state.name}",
                    runId = runId,
                    state = state,
                    sequence = index,
                    previousEventHash = previous?.eventHash,
                    authorityReferenceIds = setOf(authority),
                    occurredAt = KotlinInstant.parse("2026-07-18T07:00:00Z")
                ).withComputedHash()
                publication.append(event)
                previous = event
            }

        service.archiveRun(
            runId = runId,
            groupIds = listOf(groupId),
            winnerId = groupId,
            objective = startingField.objective,
            startingFieldJson = json.encodeToString(StartingField.serializer(), startingField),
            finalGeneration = 1,
            completionRecord = completion
        )

        assertEquals(exactHash, sha256Hex(bundle.readBytes()))
        assertEquals(PublicationState.ARCHIVED, publication.latest(runId)?.state)
    }

    @Test
    fun `deterministic deliverable guard rejects a valid archive with a missing required output file`() = runTest {
        val rootDir = createTempDirectory("run-archive-missing-required-output-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-missing-required-output"
        val groupId = "group-0"
        val startingField = StartingField.default("Write deterministic deliverables", runId = runId).copy(groupCount = 1)

        service.archiveRun(
            runId = runId,
            groupIds = listOf(groupId),
            winnerId = groupId,
            objective = startingField.objective,
            startingFieldJson = json.encodeToString(StartingField.serializer(), startingField),
            finalGeneration = 0,
            completionRecord = RunCompletionRecord(
                runId = runId,
                winnerId = groupId,
                finalAnswerSummary = "The run completed with deterministic output files."
            )
        )

        val missingFile = File(rootDir, "evomolior/runs/$runId/results/outputs/run_replay.html")
        assertTrue(missingFile.delete(), "test setup should remove one required deliverable")

        val failure = assertFailsWith<IllegalStateException> {
            service.ensureDeterministicDeliverablesWritten(runId)
        }

        assertTrue(failure.message!!.contains("run_replay.html"))
    }

    @Test
    fun `buildResultsBundle extracts embedded final deliverable html from submission summary`() = runTest {
        val rootDir = createTempDirectory("run-archive-summary-html-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-summary-html"
        val groupId = "group-0"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = groupId,
                        finalAnswerSummary = "Round 1 final answer: winner=$groupId",
                        submissionSummariesByGroup = mapOf(
                            groupId to """
                                ROUND SUBMISSION SUMMARY
                                - Completed article structure.

                                FINAL DELIVERABLE HTML
                                <!doctype html>
                                <html lang="en">
                                <head><meta charset="utf-8"><title>Embedded summary article</title></head>
                                <body><article><h1>Embedded summary article</h1><p>Publication-ready.</p></article></body>
                                </html>
                            """.trimIndent()
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertTrue(entries["outputs/final-article.html"]!!.contains("Embedded summary article"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("Publication-ready."))
    }

    @Test
    fun `buildResultsBundle falls back from winner summary to another summary with embedded html`() = runTest {
        val rootDir = createTempDirectory("run-archive-summary-fallback-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-summary-fallback"
        val winnerId = "group-0"
        val otherGroupId = "group-1"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive the run", runId = runId).copy(
            groupProfiles = mapOf(winnerId to com.achulkov.evomolior.core.model.GroupProfileType.Library, otherGroupId to com.achulkov.evomolior.core.model.GroupProfileType.Library),
            ringTopology = listOf(winnerId, otherGroupId)
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = winnerId,
                        finalAnswerSummary = "Round 1 final answer: winner=$winnerId",
                        submissionSummariesByGroup = mapOf(
                            winnerId to "ROUND SUBMISSION SUMMARY\n- Winner summary without embedded html.",
                            otherGroupId to """
                                ROUND SUBMISSION SUMMARY
                                - Non-winner summary carries the final deliverable.

                                FINAL DELIVERABLE HTML
                                <!doctype html>
                                <html lang="en">
                                <head><meta charset="utf-8"><title>Fallback article</title></head>
                                <body><article><h1>Fallback article</h1><p>Chosen from the first available embedded html block.</p></article></body>
                                </html>
                            """.trimIndent()
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertTrue(entries["outputs/final-article.html"]!!.contains("Fallback article"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("Chosen from the first available embedded html block."))
    }

    @Test
    fun `buildResultsBundle includes generated json output files when present`() = runTest {
        val rootDir = createTempDirectory("run-archive-generated-output-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-generated-output"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        generatedOutputFiles = listOf(
                            GeneratedOutputFile(
                                relativePath = "briefs/summary.json",
                                mediaType = "application/json",
                                content = """{"headline":"MindVortex","lang":"de"}"""
                            )
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertEquals("""{"headline":"MindVortex","lang":"de"}""", entries["outputs/briefs/summary.json"])
        assertTrue(entries["outputs/high-level-run-log.txt"]!!.contains("Final outputs: briefs/summary.json"))
        assertFalse(entries.keys.contains("outputs/final-article.html"))
    }

    @Test
    fun `buildResultsBundle includes patch bundle output with structured change set`() = runTest {
        val rootDir = createTempDirectory("run-archive-patch-bundle-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-patch-bundle"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        requestedOutputSpec = FinalOutputSpec(
                            relativePath = "self-improvement-patch-bundle.json",
                            mediaType = "application/json",
                            description = "Machine-readable patch bundle with edited files, diffs, and documentation.",
                            kind = FinalOutputKind.PATCH_BUNDLE
                        ),
                        generatedOutputFiles = listOf(
                            GeneratedOutputFile(
                                relativePath = "self-improvement-patch-bundle.json",
                                mediaType = "application/json",
                                content = """
                                    {
                                      "summary": "Update Example.kt",
                                      "changedFiles": [
                                        {
                                          "path": "Example.kt",
                                          "status": "modified",
                                          "diff": "--- a/Example.kt\n+++ b/Example.kt\n@@\n-fun message(): String = \"original\"\n+fun message(): String = \"updated\"",
                                          "rationale": "The message function must return the updated string."
                                        }
                                      ],
                                      "documentation": "Example.kt now returns the updated message.",
                                      "verification": [
                                        "Compile Example.kt",
                                        "Invoke message() and confirm updated output"
                                      ],
                                      "notes": "Rollback restores the original return value."
                                    }
                                """.trimIndent()
                            )
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        val patchBundle = entries["outputs/self-improvement-patch-bundle.json"]
        assertNotNull(patchBundle)

        val patchJson = json.parseToJsonElement(patchBundle).jsonObject
        assertEquals("Update Example.kt", patchJson["summary"]?.jsonPrimitive?.content)
        val changedFiles = patchJson["changedFiles"]?.jsonArray
        assertNotNull(changedFiles)
        assertEquals("Example.kt", changedFiles.first().jsonObject["path"]?.jsonPrimitive?.content)
        assertEquals("modified", changedFiles.first().jsonObject["status"]?.jsonPrimitive?.content)
        assertTrue(patchJson["documentation"]?.jsonPrimitive?.content.orEmpty().contains("Example.kt"))
        assertTrue(patchJson["verification"]?.jsonArray?.isNotEmpty() == true)
        assertTrue(patchJson["notes"]?.jsonPrimitive?.content.orEmpty().contains("Rollback"))
    }

    @Test
    fun `buildResultsBundle recovers configured json output from archived round summary when final writer failed`() = runTest {
        val rootDir = createTempDirectory("run-archive-summary-json-recovery-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-summary-json-recovery"
        val winnerId = "group-0"
        val otherGroupId = "group-1"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        PersistedRunStateStore.writeForDataRoot(
            rootDir,
            PersistedRunState(
                runId = runId,
                objective = "Produce a strict JSON evidence brief",
                groupCount = 2,
                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Library),
                rounds = 3,
                roundDurationMinutes = 1,
                maxAgentsPerGroup = 2,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "evidence_fidelity",
                        name = "Evidence Fidelity",
                        description = "Reward grounded evidence synthesis.",
                        weight = 1.0
                    )
                ),
                finalOutputSpec = FinalOutputSpec(
                    relativePath = "briefs/evidence-brief.json",
                    mediaType = "application/json",
                    description = "Machine-readable evidence brief"
                ),
                isTestRun = true,
                budgetOverride = 50.0,
                groupIds = listOf(winnerId, otherGroupId),
                graderGroupIds = emptyList(),
                status = PersistedRunStatus.COMPLETED,
                nextRoundIndex = 3
            )
        )

        val startingField = StartingField.default("archive the run", runId = runId).copy(
            groupProfiles = mapOf(
                winnerId to GroupProfileType.Library,
                otherGroupId to GroupProfileType.Library
            ),
            ringTopology = listOf(winnerId, otherGroupId)
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_ROUND_SUMMARY,
                groupId = null,
                label = "Round summary — $runId",
                contentJson = """
                    {
                      "runId":"$runId",
                      "roundIndex":1,
                      "roundWinnerId":"$winnerId",
                      "judgeOutputsByGrader":{},
                      "groupSummaries":[
                        {
                          "groupId":"$winnerId",
                          "criticPromoted":1,
                          "criticRejected":0,
                          "criticArbitrated":0,
                          "fitnessScoredAgents":0,
                          "fitnessTopEligibleAgents":0,
                          "legacyProtocolsExtracted":0,
                          "legacyProtocolsExpired":0,
                          "deadEndsInserted":0,
                          "submissionSummary":"ROUND SUBMISSION SUMMARY\n\nEVIDENCE BRIEF JSON\n```json\n{\"title\":\"Recovered brief\",\"generatedOn\":\"2026-06-05\",\"audience\":\"General public\",\"keyTakeaways\":[\"Recovered from archived round summary\"],\"medicationEvidence\":\"Recovered medication evidence\",\"nonPharmacologicalEvidence\":\"Recovered non-pharmacological evidence\",\"ageGroupNuances\":\"Recovered age nuance\",\"limitations\":[\"Recovered from persisted archive after final writer failure\"],\"sources\":[]}\n```",
                          "deterministicScore":{"overallScore":0.0,"automatedTestsPassed":0,"automatedTestsFailed":0,"mandatoryFailure":false,"gradingSheetApplied":"none","failedTestIds":[],"dimensionScores":{}},
                          "disclosureCheckin":{"overallScore":0.0,"passed":false,"testPassRate":0.0,"failedTestCount":0,"gradingSheetApplied":"none","stageAdvanced":false,"dimensionScores":{}},
                          "blindScore":{"weightedTotal":0.0,"rank":1,"dimensionScores":{}}
                        },
                        {
                          "groupId":"$otherGroupId",
                          "criticPromoted":0,
                          "criticRejected":0,
                          "criticArbitrated":0,
                          "fitnessScoredAgents":0,
                          "fitnessTopEligibleAgents":0,
                          "legacyProtocolsExtracted":0,
                          "legacyProtocolsExpired":0,
                          "deadEndsInserted":0,
                          "submissionSummary":"ROUND SUBMISSION SUMMARY\n- No valid final output",
                          "deterministicScore":{"overallScore":0.0,"automatedTestsPassed":0,"automatedTestsFailed":0,"mandatoryFailure":false,"gradingSheetApplied":"none","failedTestIds":[],"dimensionScores":{}},
                          "disclosureCheckin":{"overallScore":0.0,"passed":false,"testPassRate":0.0,"failedTestCount":0,"gradingSheetApplied":"none","stageAdvanced":false,"dimensionScores":{}},
                          "blindScore":{"weightedTotal":0.0,"rank":2,"dimensionScores":{}}
                        }
                      ],
                      "generatedAtMs":123
                    }
                """.trimIndent(),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = true,
                generation = 2,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = winnerId,
                        finalAnswerSummary = "Round 2 final answer: winner=$winnerId"
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 2,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertEquals(
            """{"title":"Recovered brief","generatedOn":"2026-06-05","audience":"General public","keyTakeaways":["Recovered from archived round summary"],"medicationEvidence":"Recovered medication evidence","nonPharmacologicalEvidence":"Recovered non-pharmacological evidence","ageGroupNuances":"Recovered age nuance","limitations":["Recovered from persisted archive after final writer failure"],"sources":[]}""",
            entries["outputs/briefs/evidence-brief.json"]
        )
        assertFalse(entries.keys.contains("outputs/final-article.html"))

        service.archiveRun(
            runId = runId,
            groupIds = listOf(winnerId, otherGroupId),
            winnerId = winnerId,
            objective = startingField.objective,
            startingFieldJson = json.encodeToString(StartingField.serializer(), startingField),
            finalGeneration = 2,
            completionRecord = RunCompletionRecord(
                runId = runId,
                winnerId = winnerId,
                finalAnswerSummary = "Round 2 final answer: winner=$winnerId"
            )
        )

        val recoveredOutputFile = File(rootDir, "evomolior/runs/$runId/results/briefs/evidence-brief.json")
        assertTrue(recoveredOutputFile.exists())
        assertEquals(
            """{"title":"Recovered brief","generatedOn":"2026-06-05","audience":"General public","keyTakeaways":["Recovered from archived round summary"],"medicationEvidence":"Recovered medication evidence","nonPharmacologicalEvidence":"Recovered non-pharmacological evidence","ageGroupNuances":"Recovered age nuance","limitations":["Recovered from persisted archive after final writer failure"],"sources":[]}""",
            recoveredOutputFile.readText()
        )
        assertFalse(File(rootDir, "evomolior/runs/$runId/results/final-article.html").exists())
    }

    @Test
    fun `buildResultsBundle can recover configured json output without persisted state`() = runTest {
        val rootDir = createTempDirectory("run-archive-explicit-output-spec-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-explicit-output-spec"
        val winnerId = "group-0"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = winnerId,
                        finalAnswerSummary = "Round 2 final answer: winner=$winnerId"
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 2,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_ROUND_SUMMARY,
                groupId = null,
                label = "Round summary — $runId",
                contentJson = """
                    {
                      "runId":"$runId",
                      "roundIndex":1,
                      "roundWinnerId":"$winnerId",
                      "groupSummaries":[
                        {
                          "groupId":"$winnerId",
                          "submissionSummary":"ROUND SUBMISSION SUMMARY\n\nBRIEF JSON\n```json\n{\"headline\":\"Recovered brief\",\"lang\":\"en\"}\n```"
                        }
                      ],
                      "generatedAtMs":123
                    }
                """.trimIndent(),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = true,
                generation = 2,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(
            runId = runId,
            requestedOutputSpec = FinalOutputSpec(
                relativePath = "briefs/evidence-brief.json",
                mediaType = "application/json",
                description = "Machine-readable evidence brief"
            )
        )
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertEquals("""{"headline":"Recovered brief","lang":"en"}""", entries["outputs/briefs/evidence-brief.json"])
        assertFalse(entries.keys.contains("outputs/final-article.html"))
    }

    @Test
    fun `validateGeneratedOutputFile rejects placeholder article references without traceable identifiers`() {
        val badHtml = GeneratedOutputFile(
            relativePath = "final-article.html",
            mediaType = "text/html",
            content = """
                <!doctype html>
                <html lang="en">
                <body>
                  <article>
                    <h2>References</h2>
                    <ol>
                      <li>Smith J, et al. Invented ADHD trial results.</li>
                    </ol>
                  </article>
                </body>
                </html>
            """.trimIndent()
        )

        assertFailsWith<IllegalStateException> {
            validateGeneratedOutputFile(badHtml)
        }
    }

    @Test
    fun `validateGeneratedOutputFile appends footer to html outputs that omit one`() {
        val htmlWithoutFooter = GeneratedOutputFile(
            relativePath = "final-article.html",
            mediaType = "text/html",
            content = """
                <!doctype html>
                <html lang="en">
                <body>
                  <main><article><h1>Article</h1><p>Publication body.</p></article></main>
                </body>
                </html>
            """.trimIndent()
        )

        val validated = validateGeneratedOutputFile(htmlWithoutFooter)

        assertTrue(validated.content.contains("<footer"))
        assertTrue(validated.content.contains("role=\"contentinfo\""))
        assertTrue(validated.content.contains("Published artifact: final-article.html."))
        assertTrue(validated.content.indexOf("<footer") < validated.content.indexOf("</body>"))
        assertEquals(AtlasHashing.hashText(validated.content), validated.contentHash)
        assertTrue(validated.contentHash != htmlWithoutFooter.contentHash)
    }

    @Test
    fun `validateGeneratedOutputFile rejects truncated html without closing envelope`() {
        val truncatedHtml = GeneratedOutputFile(
            relativePath = "final-article.html",
            mediaType = "text/html",
            content = """
                <!doctype html>
                <html lang="en">
                <body>
                  <main><article><h1>Article</h1><p>This paragraph was truncated
            """.trimIndent()
        )

        val error = assertFailsWith<IllegalStateException> {
            validateGeneratedOutputFile(truncatedHtml)
        }

        assertTrue(error.message.orEmpty().contains("</body>"))
        assertTrue(error.message.orEmpty().contains("</html>"))
    }

    @Test
    fun `validateGeneratedOutputFile rejects invalid json source metadata`() {
        val badJson = GeneratedOutputFile(
            relativePath = "briefs/evidence-brief.json",
            mediaType = "application/json",
            content = """{"sources":[{"title":"Invented source","doi":"not-a-doi","url":"https://example.invalid"}]}"""
        )

        assertFailsWith<IllegalStateException> {
            validateGeneratedOutputFile(badJson)
        }
    }

    @Test
    fun `buildResultsBundle rejects article references that do not match the verified source catalog`() = runTest {
        val rootDir = createTempDirectory("run-archive-source-catalog-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-source-catalog"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val artifactsDir = File(rootDir, "evomolior/runs/$runId/artifacts")
        artifactsDir.mkdirs()
        artifactsDir.resolve("current_sources.md").writeText(
            """
            Current-source anchor notes for the live ADHD article pipeline run

            1. Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis
            - Source: PubMed
            - URL: https://pubmed.ncbi.nlm.nih.gov/42134365/
            - Publication: 2025
            - Notes: large pharmacology synthesis spanning stimulant and non-stimulant monotherapy across ages 5+.
            """.trimIndent()
        )

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        finalDeliverableHtml = """
                            <!doctype html>
                            <html lang="en">
                            <body>
                              <article>
                                <h2>References</h2>
                                <ol>
                                  <li>Faraone SV, et al. Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis. <em>PubMed</em>. 2024.</li>
                                </ol>
                              </article>
                            </body>
                            </html>
                        """.trimIndent()
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        assertFailsWith<IllegalStateException> {
            service.buildResultsBundle(runId)
        }
    }

    @Test
    fun `buildResultsBundle rejects json evidence brief sources that do not match the verified source catalog`() = runTest {
        val rootDir = createTempDirectory("run-archive-source-catalog-json-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-source-catalog-json"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val artifactsDir = File(rootDir, "evomolior/runs/$runId/artifacts")
        artifactsDir.mkdirs()
        artifactsDir.resolve("current_sources.md").writeText(
            """
            Current-source anchor notes for the live ADHD article pipeline run

            1. Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis
            - Source: PubMed
            - URL: https://pubmed.ncbi.nlm.nih.gov/42134365/
            - Publication: 2025
            - Notes: large pharmacology synthesis spanning stimulant and non-stimulant monotherapy across ages 5+.
            """.trimIndent()
        )

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        generatedOutputFiles = listOf(
                            GeneratedOutputFile(
                                relativePath = "briefs/evidence-brief.json",
                                mediaType = "application/json",
                                content = """{"title":"Wrong title","publicationDate":"2024","sources":[{"title":"Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis","publicationDate":"2024","url":"https://pubmed.ncbi.nlm.nih.gov/42134365/"}]}"""
                            )
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        assertFailsWith<IllegalStateException> {
            service.buildResultsBundle(runId)
        }
    }

    @Test
    fun `buildResultsBundle keeps a valid recovered article when an unrelated brief is invalid`() = runTest {
        val rootDir = createTempDirectory("run-archive-source-catalog-mixed-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-source-catalog-mixed"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val artifactsDir = File(rootDir, "evomolior/runs/$runId/artifacts")
        artifactsDir.mkdirs()
        artifactsDir.resolve("current_sources.md").writeText(
            """
            Current-source anchor notes for the live ADHD article pipeline run

            1. Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis
            - Source: PubMed
            - URL: https://pubmed.ncbi.nlm.nih.gov/42134365/
            - Publication: 2025
            - Notes: large pharmacology synthesis spanning stimulant and non-stimulant monotherapy across ages 5+.
            """.trimIndent()
        )

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        generatedOutputFiles = listOf(
                            GeneratedOutputFile(
                                relativePath = "final-article.html",
                                mediaType = "text/html",
                                content = """
                                    <!doctype html>
                                    <html lang="en">
                                    <body>
                                      <article>
                                        <h2>References</h2>
                                        <ol>
                                          <li>Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis. <a href="https://pubmed.ncbi.nlm.nih.gov/42134365/">PMID 42134365</a>. 2025.</li>
                                        </ol>
                                      </article>
                                    </body>
                                    </html>
                                """.trimIndent()
                            ),
                            GeneratedOutputFile(
                                relativePath = "briefs/evidence-brief.json",
                                mediaType = "application/json",
                                content = """{"title":"Wrong title","publicationDate":"2024","sources":[{"title":"Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis","publicationDate":"2024","url":"https://pubmed.ncbi.nlm.nih.gov/42134365/"}]}"""
                            )
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)
        val entries = unzipEntries(bundle)
        assertTrue(entries.keys.contains("outputs/final-article.html"))
        assertFalse(entries.keys.contains("outputs/briefs/evidence-brief.json"))
    }

    @Test
    fun `buildResultsBundle includes verified generated image assets as binary output assets`() = runTest {
        val rootDir = createTempDirectory("run-archive-image-asset-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-image-asset"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val imageFile = File(rootDir, "evomolior/runs/$runId/results/outputs/assets/images/$runId-hero.png")
        imageFile.parentFile.mkdirs()
        ImageIO.write(BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB), "png", imageFile)

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        generatedOutputFiles = listOf(
                            GeneratedOutputFile(
                                relativePath = "final-article.html",
                                mediaType = "text/html",
                                content = """
                                    <!doctype html>
                                    <html lang="en">
                                    <body><figure><img src="assets/images/$runId-hero.png" alt="Generated hero"></figure></body>
                                    </html>
                                """.trimIndent()
                            )
                        ),
                        generatedImageAssets = listOf(
                            GeneratedImageAsset(
                                relativePath = "assets/images/$runId-hero.png",
                                mediaType = "image/png",
                                width = 300,
                                height = 300,
                                sizeBytes = imageFile.length(),
                                prompt = "Hero image",
                                model = "qwen-image-2.0-pro",
                                reviewStatus = "verified"
                            )
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntryBytes(bundle)
        assertTrue(entries.keys.contains("outputs/final-article.html"))
        assertEquals(imageFile.readBytes().toList(), entries["outputs/assets/images/$runId-hero.png"]?.toList())
    }

    @Test
    fun `buildResultsBundle renders slide-card html into post-ready vertical PNG assets`() = runTest {
        val rootDir = createTempDirectory("run-archive-slide-render-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-slide-render"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive slide cards", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        generatedOutputFiles = listOf(
                            GeneratedOutputFile(
                                relativePath = "final-article.html",
                                mediaType = "text/html",
                                content = """
                                    <!doctype html>
                                    <html lang="en">
                                    <head><meta charset="utf-8"><title>Slide Cards</title></head>
                                    <body>
                                      <article>
                                        <h1>Slide Cards</h1>
                                        <h2>Post 1: The Tiny Task Trap</h2>
                                        <div class="slide-card">
                                          <span class="slide-number">1</span>
                                          <h5>Exact copy</h5>
                                          <p>Your ADHD brain did not ignore the tiny task. It opened 47 tabs to understand it.</p>
                                          <h5>Visual direction</h5>
                                          <p>Use a simple text-forward slide.</p>
                                        </div>
                                      </article>
                                    </body>
                                    </html>
                                """.trimIndent()
                            )
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntryBytes(bundle)
        val html = entries["outputs/final-article.html"]?.toString(Charsets.UTF_8).orEmpty()
        assertTrue(html.contains("id=\"generated-slide-assets\""))
        assertTrue(html.contains("<img src=\"assets/slides/post-01-slide-01.png\""))
        val slideBytes = entries["outputs/assets/slides/post-01-slide-01.png"]
        assertNotNull(slideBytes)

        val renderedSlide = ImageIO.read(ByteArrayInputStream(slideBytes))
        assertNotNull(renderedSlide)
        assertEquals(GeneratedSlideImageRenderer.SLIDE_WIDTH, renderedSlide.width)
        assertEquals(GeneratedSlideImageRenderer.SLIDE_HEIGHT, renderedSlide.height)
    }

    @Test
    fun `generated image asset validator accepts decodable images and rejects corrupt files`() {
        val rootDir = createTempDirectory("generated-image-validator-test-").toFile()
        val validImage = File(rootDir, "valid.png")
        ImageIO.write(BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB), "png", validImage)

        val asset = GeneratedImageAssetValidator.validate(
            file = validImage,
            relativePath = "assets/images/valid.png",
            prompt = "Validation prompt",
            model = "qwen-image-2.0-pro"
        )

        assertEquals("image/png", asset.mediaType)
        assertEquals(300, asset.width)
        assertEquals(300, asset.height)
        assertEquals("verified", asset.reviewStatus)

        val corrupt = File(rootDir, "corrupt.png").apply { writeText("not an image") }
        assertFailsWith<IllegalArgumentException> {
            GeneratedImageAssetValidator.validate(
                file = corrupt,
                relativePath = "assets/images/corrupt.png",
                prompt = "Validation prompt",
                model = "qwen-image-2.0-pro"
            )
        }
    }

    private fun unzipEntries(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val content = zip.readBytes().toString(Charsets.UTF_8)
                result[entry.name] = content
                zip.closeEntry()
            }
        }
        return result
    }

    private fun unzipEntryBytes(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun unzipEntryTimes(bytes: ByteArray): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                result[entry.name] = entry.time
                zip.closeEntry()
            }
        }
        return result
    }

    private fun zipEntries(entries: Map<String, ByteArray>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.toSortedMap().forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
