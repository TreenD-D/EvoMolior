@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.core.contract.GeneratedImageAsset
import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.PublicationState
import com.achulkov.evomolior.core.contract.PublicationStateEvent
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.contract.StartingFieldWireContract
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.publication.PublicationStateRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.contract.ContributionEdge
import com.achulkov.evomolior.core.contract.FinalCandidateRecord
import com.achulkov.evomolior.core.contract.FinalDeliverableReferenceRecord
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Serialises a completed run's key artefacts into the meta-archive tables for
 * cross-run Council learning.
 *
 * Called by [EvoMoliorRunService] once a run transitions to COMPLETED.
 *
 * Archived artefacts per run:
 * - **STARTING_FIELD** — the Genesis Council's generated config
 * - **WINNING_KB_SNAPSHOT** — the winning group's KB entries (summarised)
 * - **DEAD_ENDS_EXPORT** — all Dead Ends from all groups
 * - **LEGACY_PROTOCOLS** — top-fitness protocol packages from each group
 * - **ROUND_SUMMARY** — per-group end-of-round summary snapshots
 * - **RUN_GRAPH_MESSAGE** — compact continuation-routing envelope for completed runs
 * - **ADVERSARIAL_DISCRIMINATOR_FEEDBACK** — per-group failure-focused critic brief
 *
 * @param dbFactory Substrate database factory for opening per-group databases.
 */
open class RunArchiveService(
    private val dbFactory: SubstrateDatabaseFactory,
    @Suppress("unused")
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false }
) {

    /**
     * Archives all relevant artefacts for a completed run.
     *
     * @param runId              The run identifier.
     * @param groupIds           List of group IDs that participated.
     * @param winnerId           The winning group (null if no winner was determined).
     * @param objective          The run's objective text (for future Council search).
     * @param startingFieldJson  The StartingField JSON used to seed the run.
     * @param finalGeneration    The last generation number that was run.
     * @param requestedOutputSpec Canonical output spec for the run's operator-facing generated file.
     *   When present, this is preferred over the persisted-state file for archive recovery.
     */
    open suspend fun archiveRun(
        runId: String,
        groupIds: List<String>,
        winnerId: String?,
        objective: String,
        startingFieldJson: String,
        finalGeneration: Int,
        requestedOutputSpec: FinalOutputSpec? = null,
        completionRecord: RunCompletionRecord? = null
    ) = withContext(Dispatchers.IO) {
        Napier.i("RunArchiveService: archiving run=$runId winner=$winnerId groups=${groupIds.size}")

        val resumedPublicationRepository = PublicationStateRepository(dbFactory.getAtlas(runId))
        val resumedPublication = resumedPublicationRepository.latest(runId)
        if (
            resumedPublication?.state == PublicationState.MANIFEST_HASHED ||
            resumedPublication?.state == PublicationState.ARCHIVED
        ) {
            writeGeneratedOutputFiles(runId, completionRecord, requestedOutputSpec)
            ensureDeterministicDeliverablesWritten(runId)
            runCatching {
                writeFinalArticleHtml(runId, completionRecord, requestedOutputSpec)
            }.onFailure { err ->
                Napier.w("RunArchiveService: failed to recover final article HTML for $runId", err)
            }
            val authorityPrefix = if (resumedPublication.state == PublicationState.ARCHIVED) {
                "archive:$runId:"
            } else {
                "results-bundle:"
            }
            val bundle = verifiedPublishedBundle(
                runId = runId,
                publicationEvent = resumedPublication,
                authorityPrefix = authorityPrefix
            )
            if (resumedPublication.state == PublicationState.MANIFEST_HASHED) {
                appendPublicationState(
                    repository = resumedPublicationRepository,
                    runId = runId,
                    state = PublicationState.ARCHIVED,
                    authorityReferenceIds = setOf(
                        "archive:$runId:${sha256Hex(bundle.readBytes())}"
                    )
                )
            }
            Napier.i(
                "RunArchiveService: recovered run=$runId from " +
                    "${resumedPublication.state}"
            )
            return@withContext
        }

        // 1. Canonicalise the StartingField as one deterministic run-level singleton.
        val archiveDb = dbFactory.get(runId, META_ARCHIVE_GROUP_ID)
        try {
            val incomingStartingField = MetaArchiveEntryEntity(
                id = "starting-field-$runId",
                runId = runId,
                entryType = ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = startingFieldJson,
                runObjective = objective,
                finalScore = null,
                isFromWinner = false,
                generation = finalGeneration,
                archivedAtMs = System.currentTimeMillis()
            )
            val canonicalStartingField = selectCanonicalStartingFieldEntry(
                archiveDb.metaArchiveDao().getByRun(runId) + incomingStartingField
            ) ?: incomingStartingField
            archiveDb.metaArchiveDao().replaceRunSingleton(
                canonicalStartingField.copy(
                    id = "starting-field-$runId",
                    entryType = ENTRY_STARTING_FIELD,
                    groupId = null,
                    label = "StartingField — $runId"
                )
            )

            // 2. Archive per-group artefacts
            groupIds.forEach { groupId ->
                val groupDb = dbFactory.get(runId, groupId)
                val isWinner = groupId == winnerId
                val now = System.currentTimeMillis()

                // 2a. Dead Ends export
                runCatching {
                    val deadEnds = groupDb.deadEndsDao().getAll(runId = runId, groupId = groupId)
                    if (deadEnds.isNotEmpty()) {
                        val deadEndsArray: JsonArray = buildJsonArray {
                            deadEnds.forEach { de ->
                                add(buildJsonObject {
                                    put("approach", de.approach)
                                    put("reason", de.reason)
                                    put("domain", de.domain)
                                })
                            }
                        }
                        archiveDb.metaArchiveDao().insert(
                            MetaArchiveEntryEntity(
                                id = UUID.randomUUID().toString(),
                                runId = runId,
                                entryType = ENTRY_DEAD_ENDS,
                                groupId = groupId,
                                label = "Dead Ends — $groupId — run $runId",
                                contentJson = deadEndsArray.toString(),
                                runObjective = objective,
                                finalScore = null,
                                isFromWinner = isWinner,
                                generation = finalGeneration,
                                archivedAtMs = now
                            )
                        )
                    }
                }.onFailure { err ->
                    Napier.w("RunArchiveService: failed to archive dead ends for $groupId", err)
                }

                // 2b. Legacy Protocols
                runCatching {
                    val protocols = groupDb.legacyProtocolDao().getActive(runId, groupId, finalGeneration)
                    if (protocols.isNotEmpty()) {
                        val protocolsArray: JsonArray = buildJsonArray {
                            protocols.forEach { proto ->
                                add(buildJsonObject {
                                    put("role", proto.role)
                                    put("reasoning", proto.reasoningPosture)
                                    put("tools", proto.toolHeuristicsJson)
                                })
                            }
                        }
                        archiveDb.metaArchiveDao().insert(
                            MetaArchiveEntryEntity(
                                id = UUID.randomUUID().toString(),
                                runId = runId,
                                entryType = ENTRY_LEGACY_PROTOCOLS,
                                groupId = groupId,
                                label = "Legacy Protocols — $groupId — run $runId",
                                contentJson = protocolsArray.toString(),
                                runObjective = objective,
                                finalScore = null,
                                isFromWinner = isWinner,
                                generation = finalGeneration,
                                archivedAtMs = now
                            )
                        )
                    }
                }.onFailure { err ->
                    Napier.w("RunArchiveService: failed to archive legacy protocols for $groupId", err)
                }

                // 2c. Winning KB Snapshot (winner only to save space)
                if (isWinner) {
                    runCatching {
                        val kbEntries: List<KnowledgeBaseEntryEntity> = groupDb.knowledgeBaseDao().search(
                            runId = runId,
                            groupId = groupId,
                            query = "",
                            limit = 200
                        )
                        if (kbEntries.isNotEmpty()) {
                            val kbArray: JsonArray = buildJsonArray {
                                kbEntries.forEach { entry: KnowledgeBaseEntryEntity ->
                                    add(buildJsonObject {
                                        put("id", entry.id)
                                        put("content", JsonPrimitive(entry.content.take(500)))
                                        put("domain", entry.domain)
                                    })
                                }
                            }
                            archiveDb.metaArchiveDao().insert(
                                MetaArchiveEntryEntity(
                                    id = UUID.randomUUID().toString(),
                                    runId = runId,
                                    entryType = ENTRY_WINNING_KB,
                                    groupId = groupId,
                                    label = "Winning KB Snapshot — $groupId — run $runId",
                                    contentJson = kbArray.toString(),
                                    runObjective = objective,
                                    finalScore = null,
                                    isFromWinner = true,
                                    generation = finalGeneration,
                                    archivedAtMs = now
                                )
                            )
                        }
                    }.onFailure { err ->
                        Napier.w("RunArchiveService: failed to archive winning KB for $groupId", err)
                    }
                }

                runCatching {
                    archiveDeliverableSkeletonTree(runId, groupId, objective, finalGeneration, now, isWinner)
                }.onFailure { err ->
                    Napier.w("RunArchiveService: failed to archive deliverable tree for $groupId", err)
                }
            }

            val publicationRepository = PublicationStateRepository(dbFactory.getAtlas(runId))
            val publicationBarrier = publicationRepository.latest(runId)
            writeGeneratedOutputFiles(runId, completionRecord, requestedOutputSpec)
            ensureDeterministicDeliverablesWritten(runId)

            runCatching {
                writeFinalArticleHtml(runId, completionRecord, requestedOutputSpec)
            }.onFailure { err ->
                Napier.w("RunArchiveService: failed to write final article HTML for $runId", err)
            }

            if (publicationBarrier != null) {
                groupIds.forEach { groupId ->
                    val workerRepository = WorkerExecutionRepository(dbFactory.get(runId, groupId))
                    val registered = workerRepository.registrations(runId, groupId)
                        .filter {
                            it.classification == AgentLifecycleClassification.NON_DAEMON
                        }
                        .mapTo(linkedSetOf()) { it.agentId.value }
                    val exited = workerRepository.exits(runId, groupId)
                        .mapTo(linkedSetOf()) { it.agentId.value }
                    check(registered == exited.intersect(registered)) {
                        "Archive blocked by unaccounted non-daemon agents in $groupId: " +
                            (registered - exited).sorted().joinToString()
                    }
                }
            }

            val metricsStore = RunMetricsV1ArtifactStore(dbFactory)
            val metricsArtifact: RunMetricsV1Artifact
            val publicationAfterMetrics = when (publicationBarrier?.state) {
                null -> {
                    metricsArtifact = exportRunMetrics(
                        runId = runId,
                        groupIds = groupIds,
                        startingFieldJson = startingFieldJson,
                        finalGeneration = finalGeneration,
                        requestedOutputSpec = requestedOutputSpec,
                        completionRecord = completionRecord
                    )
                    null
                }

                PublicationState.ALL_NON_DAEMON_AGENTS_TERMINATED -> {
                    metricsArtifact = exportRunMetrics(
                        runId = runId,
                        groupIds = groupIds,
                        startingFieldJson = startingFieldJson,
                        finalGeneration = finalGeneration,
                        requestedOutputSpec = requestedOutputSpec,
                        completionRecord = completionRecord
                    )
                    appendPublicationState(
                        repository = publicationRepository,
                        runId = runId,
                        state = PublicationState.METRICS_EXPORTED,
                        authorityReferenceIds = setOf(
                            "run-metrics-v1:${metricsArtifact.sha256}"
                        )
                    )
                }

                PublicationState.METRICS_EXPORTED -> {
                    val expectedMetricsHash = publicationBarrier.authorityReferenceIds
                        .singleOrNull { it.startsWith("run-metrics-v1:") }
                        ?.substringAfterLast(':')
                        ?.takeIf(String::isNotBlank)
                        ?: error(
                            "METRICS_EXPORTED state lacks exact run-metrics authority"
                        )
                    metricsArtifact = metricsStore.readRequired(runId, expectedMetricsHash)
                    publicationBarrier
                }

                else -> {
                    error("Archive blocked at publication state ${publicationBarrier.state}")
                }
            }

            val resultsBundle = if (publicationAfterMetrics != null) {
                when (publicationAfterMetrics.state) {
                    PublicationState.METRICS_EXPORTED ->
                        requireNotNull(
                            writeResultsBundle(runId, completionRecord, requestedOutputSpec)
                        ) {
                            "Publication manifest bundle could not be built"
                        }.also { bundle ->
                            verifyBundleBeforeManifestHash(
                                runId = runId,
                                bundle = bundle,
                                expectedMetricsHash = metricsArtifact.sha256
                            )
                            appendPublicationState(
                                repository = publicationRepository,
                                runId = runId,
                                state = PublicationState.MANIFEST_HASHED,
                                authorityReferenceIds = setOf(
                                    "results-bundle:${bundle.name}:${sha256Hex(bundle.readBytes())}"
                                )
                            )
                        }
                    else -> error(
                        "Archive blocked at publication state ${publicationAfterMetrics.state}"
                    )
                }
            } else {
                runCatching {
                    writeResultsBundle(runId, completionRecord, requestedOutputSpec)
                }.onFailure { err ->
                    Napier.w("RunArchiveService: failed to write results bundle for $runId", err)
                }.getOrNull()
            }

            if (publicationBarrier != null && publicationBarrier.state != PublicationState.ARCHIVED) {
                val bundle = requireNotNull(resultsBundle)
                appendPublicationState(
                    repository = publicationRepository,
                    runId = runId,
                    state = PublicationState.ARCHIVED,
                    authorityReferenceIds = setOf(
                        "archive:$runId:${sha256Hex(bundle.readBytes())}"
                    )
                )
            }
            Napier.i("RunArchiveService: run=$runId archiving complete")
        } catch (e: Exception) {
            Napier.e("RunArchiveService: failed to archive run=$runId", e)
            throw e
        }
    }

    private suspend fun exportRunMetrics(
        runId: String,
        groupIds: List<String>,
        startingFieldJson: String,
        finalGeneration: Int,
        requestedOutputSpec: FinalOutputSpec?,
        completionRecord: RunCompletionRecord?
    ): RunMetricsV1Artifact {
        val persistedState = PersistedRunStateStore.read(
            File(resolveRunRoot(runId), "control/${PersistedRunStateStore.STATE_FILE_NAME}")
        )
        val startingField = json.decodeFromString(
            StartingField.serializer(),
            startingFieldJson
        )
        val outputSpec = requestedOutputSpec
            ?: completionRecord?.requestedOutputSpec
            ?: persistedState?.finalOutputSpec
        val claimLedgerJsonl = if (
            outputSpec?.validation?.researchClaimSupportCoverage != null
        ) {
            val archiveEntries = dbFactory.get(runId, META_ARCHIVE_GROUP_ID)
                .metaArchiveDao()
                .getByRun(runId)
            val outputs = resolveGeneratedOutputFiles(
                runId = runId,
                entries = archiveEntries,
                completionRecord = completionRecord,
                requestedOutputSpec = outputSpec
            )
            val sourceIds = resolveResearchSourceIds(startingField, outputs)
            encodeResearchClaimLedger(
                extractResearchClaims(completionRecord, outputs, sourceIds)
            )
        } else {
            null
        }
        val atlasId = runCatching {
            Json.parseToJsonElement(startingField.nodeGraph)
                .jsonObject["atlasId"]
                ?.jsonPrimitive
                ?.content
                ?.takeIf(String::isNotBlank)
        }.getOrNull() ?: "atlas:$runId"
        val acceptanceProfile = persistedState?.acceptanceProfile
            ?: com.achulkov.evomolior.core.config.AcceptanceProfile.NORMAL
        val export = RunMetricsV1Exporter(
            RepositoryRunMetricCollector(dbFactory)
        ).export(
            RunMetricsExportContext(
                runId = runId,
                workerGroupIds = groupIds.distinct().sorted(),
                graderGroupIds = persistedState?.graderGroupIds.orEmpty()
                    .distinct()
                    .sorted(),
                atlasId = atlasId,
                completedThroughRound = (
                    (completionRecord?.completedRoundCount ?: finalGeneration) - 1
                    ).coerceAtLeast(-1),
                acceptanceProfile = acceptanceProfile,
                finalOutputSpec = outputSpec,
                completionRecord = completionRecord,
                claimLedgerJsonl = claimLedgerJsonl
            )
        )
        export.requirePublishable(acceptanceProfile)
        return RunMetricsV1ArtifactStore(dbFactory).persist(export.metrics)
    }

    private fun verifyBundleBeforeManifestHash(
        runId: String,
        bundle: File,
        expectedMetricsHash: String
    ) {
        val persistedState = PersistedRunStateStore.read(
            File(resolveRunRoot(runId), "control/${PersistedRunStateStore.STATE_FILE_NAME}")
        )
        val verification = RunManifestVerifier.verify(
            bundleBytes = bundle.readBytes(),
            expectedRunId = runId,
            expectedCouncilExecutionPurpose = persistedState?.councilExecutionPurpose,
            expectedAcceptanceProfile = persistedState?.acceptanceProfile
        )
        check(verification.isValid) {
            "Publication manifest verification failed: ${verification.errors.joinToString("; ")}"
        }
        val manifest = requireNotNull(verification.manifest)
        check(manifest.runMetricsSha256 == expectedMetricsHash) {
            "Publication manifest does not commit the exported run-metrics hash"
        }
    }

    private fun verifiedPublishedBundle(
        runId: String,
        publicationEvent: PublicationStateEvent,
        authorityPrefix: String
    ): File {
        val bundle = resultsBundleFile(runId)
        check(bundle.isFile) {
            "Publication state ${publicationEvent.state} has no durable results bundle"
        }
        val expectedHash = publicationEvent.authorityReferenceIds
            .singleOrNull { it.startsWith(authorityPrefix) }
            ?.substringAfterLast(':')
            ?.takeIf(String::isNotBlank)
            ?: error(
                "Publication state ${publicationEvent.state} lacks exact bundle authority"
            )
        val actualHash = sha256Hex(bundle.readBytes())
        check(actualHash == expectedHash) {
            "Publication state ${publicationEvent.state} bundle hash mismatch: " +
                "expected=$expectedHash actual=$actualHash"
        }
        val persistedState = PersistedRunStateStore.read(
            File(resolveRunRoot(runId), "control/${PersistedRunStateStore.STATE_FILE_NAME}")
        )
        val verification = RunManifestVerifier.verify(
            bundleBytes = bundle.readBytes(),
            expectedRunId = runId,
            expectedCouncilExecutionPurpose = persistedState?.councilExecutionPurpose,
            expectedAcceptanceProfile = persistedState?.acceptanceProfile
        )
        check(verification.isValid) {
            "Persisted publication bundle failed manifest verification: " +
                verification.errors.joinToString("; ")
        }
        return bundle
    }

    private suspend fun appendPublicationState(
        repository: PublicationStateRepository,
        runId: String,
        state: PublicationState,
        authorityReferenceIds: Set<String>
    ): PublicationStateEvent {
        val previous = repository.latest(runId)
        val event = PublicationStateEvent(
            eventId = CouncilDeterministicIds.create(
                "publication-state",
                runId,
                state.name
            ),
            runId = runId,
            state = state,
            sequence = (previous?.sequence ?: -1) + 1,
            previousEventHash = previous?.eventHash,
            authorityReferenceIds = authorityReferenceIds,
            occurredAt = kotlin.time.Clock.System.now()
        ).withComputedHash()
        return repository.append(event)
    }

    /**
     * Builds the downloadable results ZIP for a run.
     *
     * The bundle contains the sealed StartingField, uploaded run-start artifacts, and the
     * meta-archive entries captured during the run.
     *
     * @param runId The completed or partial run identifier.
     * @param completionRecord Optional pre-decoded completion record to avoid re-reading the archive.
     * @param requestedOutputSpec Canonical output spec for the run's operator-facing generated file.
     *   When present, this is preferred over the persisted-state file for archive recovery.
     * @return ZIP bytes, or null if the run has no sealed StartingField yet.
     */
    suspend fun buildResultsBundle(
        runId: String,
        completionRecord: RunCompletionRecord? = null,
        requestedOutputSpec: FinalOutputSpec? = null
    ): ByteArray? = withContext(Dispatchers.IO) {
        val archiveDb = dbFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val entries = archiveDb.metaArchiveDao().getByRun(runId)
        val startingFieldEntry = selectCanonicalStartingFieldEntry(entries) ?: return@withContext null
        val startingField = runCatching {
            json.decodeFromString(StartingField.serializer(), startingFieldEntry.contentJson)
        }.getOrElse {
            return@withContext null
        }

        val runRoot = resolveRunRoot(runId)
        val artifactsRoot = File(runRoot, "artifacts")
        val persistedStateFile = File(
            runRoot,
            "control/${PersistedRunStateStore.STATE_FILE_NAME}"
        ).takeIf(File::isFile)
        val persistedStateBytes = persistedStateFile?.let { Files.readAllBytes(it.toPath()) }
        val persistedState = persistedStateFile?.let { stateFile ->
            requireNotNull(PersistedRunStateStore.read(stateFile)) {
                "Run config authority is unreadable: ${stateFile.relativeTo(runRoot)}"
            }.also { state ->
                require(state.runId == runId) {
                    "Run config authority belongs to runId=${state.runId}, expected $runId"
                }
            }
        }
        val acceptanceAdmissionFile = File(
            runRoot,
            "control/${AcceptanceAdmissionArtifact.FILE_NAME}"
        ).takeIf(File::isFile)
        val acceptanceAdmissionBytes = acceptanceAdmissionFile?.let {
            Files.readAllBytes(it.toPath())
        }
        val acceptanceAdmission = acceptanceAdmissionBytes?.let { bytes ->
            json.decodeFromString(
                AcceptanceAdmissionArtifact.serializer(),
                bytes.toString(Charsets.UTF_8)
            ).also { admission ->
                require(admission.runId == runId) {
                    "Acceptance admission belongs to runId=${admission.runId}, expected $runId"
                }
                require(admission.acceptanceProfile == persistedState?.acceptanceProfile) {
                    "Acceptance admission profile does not match persisted run authority"
                }
            }
        }
        if (persistedState?.acceptanceProfile != null &&
            persistedState.acceptanceProfile !=
            com.achulkov.evomolior.core.config.AcceptanceProfile.NORMAL
        ) {
            requireNotNull(acceptanceAdmission) {
                "Non-normal acceptance archive requires ${AcceptanceAdmissionArtifact.FILE_NAME}"
            }
        }
        val runMetricsArtifact = RunMetricsV1ArtifactStore(dbFactory)
            .file(runId)
            .takeIf(File::isFile)
            ?.let {
                RunMetricsV1ArtifactStore(dbFactory).readRequired(runId)
            }
        if (
            persistedState?.acceptanceProfile != null &&
            persistedState.acceptanceProfile !=
            com.achulkov.evomolior.core.config.AcceptanceProfile.NORMAL
        ) {
            requireNotNull(runMetricsArtifact) {
                "Non-normal acceptance archive requires ${RunMetricsV1ArtifactStore.FILE_NAME}"
            }
        }
        val resolvedCompletionRecord = completionRecord ?: entries.firstOrNull { it.entryType == ENTRY_RUN_COMPLETED }?.let {
            runCatching {
                json.decodeFromString(RunCompletionRecord.serializer(), it.contentJson)
            }.getOrNull()
        }
        val archiveTimestampMs = (
            resolvedCompletionRecord?.completedAtMs
                ?: persistedState?.lastUpdatedAtMs
                ?: entries.maxOfOrNull(MetaArchiveEntryEntity::archivedAtMs)
                ?: System.currentTimeMillis()
            ).coerceAtLeast(ZIP_EPOCH_1980_MS)
        val resolvedRequestedOutputSpec = requestedOutputSpec
            ?: resolvedCompletionRecord?.requestedOutputSpec
            ?: resolveRequestedOutputSpec(runId)
        val generatedOutputs = resolveGeneratedOutputFiles(
            runId = runId,
            entries = entries,
            completionRecord = resolvedCompletionRecord,
            requestedOutputSpec = resolvedRequestedOutputSpec
        )
        val preserveActiveCompiledOutputBytes =
            hasActiveCompiledCouncilManifest(entries)
        val renderedSlideAssets = mutableListOf<RenderedSlideImageAsset>()
        val bundleGeneratedOutputs = mutableListOf<GeneratedOutputFile>()
        generatedOutputs.forEach { outputFile ->
            val rendered = if (preserveActiveCompiledOutputBytes) {
                outputFile
            } else {
                renderSlideImagesForGeneratedOutput(
                    runId = runId,
                    outputFile = outputFile,
                    renderedSlideAssets = renderedSlideAssets
                )
            }
            bundleGeneratedOutputs += validateRenderedArchiveOutput(
                runId = runId,
                entries = entries,
                completionRecord = resolvedCompletionRecord,
                requestedOutputSpec = resolvedRequestedOutputSpec,
                output = rendered
            )
        }
        val researchDeliverablePack = buildResearchDeliverablePack(
            runId = runId,
            startingField = startingField,
            completionRecord = resolvedCompletionRecord,
            generatedOutputs = bundleGeneratedOutputs,
            entries = entries
        )
        val finalReferenceRecords = resolvedCompletionRecord
            ?.generatedOutputFiles
            .orEmpty()
            .mapNotNull { it.sourceGroupId }
            .distinct()
            .flatMap { groupId ->
                ContributionGraphRepository(
                    database = dbFactory.get(runId, groupId),
                    atlasDatabase = dbFactory.getAtlas(runId)
                ).finalDeliverableReferencesForRun(runId, groupId)
            }
        val manifestFinalReferencesByPath = finalReferenceRecords
            .groupBy { it.relativePath }
            .mapValues { (_, records) ->
                records.map { record ->
                    RunManifestFinalReference(
                        referenceId = record.referenceId,
                        referenceHash = record.recordHash,
                        nodeId = record.nodeId,
                        componentId = record.componentId,
                        relativePath = record.relativePath,
                        mediaType = record.mediaType,
                        contentSha256 = record.contentHash
                    )
                }.distinct().sortedBy(RunManifestFinalReference::referenceId)
            }
        val workerGroupIds = resolvedCompletionRecord
            ?.submissionSummariesByGroup
            ?.keys
            ?.sorted()
            .orEmpty()
            .ifEmpty { (0 until startingField.groupCount).map { "group-$it" } }
        val contributionLineageByGroup = workerGroupIds.associateWith { groupId ->
            ContributionGraphRepository(
                database = dbFactory.get(runId, groupId),
                atlasDatabase = dbFactory.getAtlas(runId)
            ).forRun(runId, groupId)
        }
        val workerAtlasId = runCatching {
            Json.parseToJsonElement(startingField.nodeGraph)
                .jsonObject["atlasId"]
                ?.jsonPrimitive
                ?.content
                ?.takeIf(String::isNotBlank)
        }.getOrNull() ?: "atlas:$runId"
        val workerAtlas = AtlasTransactionRepository(dbFactory)
            .currentAtlas(runId, workerAtlasId)
        val graderAtlas = GraderAuthorityRepository(dbFactory).currentAtlas(runId)
        val publicationRepository = PublicationStateRepository(dbFactory.getAtlas(runId))
        val finalCandidates = publicationRepository.finalCandidates(runId)
        val publicationHistory = publicationRepository.history(runId)
        val eventLogsByGroup = workerGroupIds.associateWith { groupId ->
            val dao = dbFactory.get(runId, groupId).eventLogDao()
            buildList {
                var offset = 0
                while (true) {
                    val page = dao.forManifest(runId, MANIFEST_EVENT_PAGE_SIZE, offset)
                    if (page.isEmpty()) break
                    addAll(page.map { event ->
                        RunManifestEventLogRecord(
                            id = event.id,
                            timestampMs = event.timestampMs,
                            eventType = event.eventType,
                            agentId = event.agentId,
                            groupId = event.groupId,
                            runId = event.runId,
                            generation = event.generation,
                            payloadJson = event.payloadJson
                        )
                    })
                    offset += page.size
                }
            }
        }

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val emittedZipEntries = mutableSetOf<String>()
            val manifestEntries = mutableListOf<RunManifestEntry>()

            fun addEntry(
                path: String,
                content: ByteArray,
                category: RunManifestEntryCategory,
                mediaType: String? = null,
                attest: Boolean = true
            ) {
                val normalizedPath = path.replace('\\', '/')
                if (!emittedZipEntries.add(normalizedPath)) return
                val entry = ZipEntry(normalizedPath)
                entry.time = archiveTimestampMs
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
                if (attest) {
                    val outputRelativePath = normalizedPath.removePrefix("outputs/")
                    val finalReferences = if (category == RunManifestEntryCategory.OUTPUT) {
                        manifestFinalReferencesByPath[outputRelativePath].orEmpty()
                    } else {
                        emptyList()
                    }
                    manifestEntries += RunManifestEntry(
                        runId = runId,
                        path = normalizedPath,
                        category = category,
                        sizeBytes = content.size.toLong(),
                        sha256 = sha256Hex(content),
                        mediaType = mediaType,
                        finalDeliverableReferences = finalReferences
                    )
                }
            }

            val startingFieldBytes = startingFieldEntry.contentJson.toByteArray(Charsets.UTF_8)
            addEntry(
                "input-artifacts.json",
                startingField.inputArtifacts.toZipManifestJson().toByteArray(Charsets.UTF_8),
                RunManifestEntryCategory.INPUT
            )
            addEntry("starting-field.json", startingFieldBytes, RunManifestEntryCategory.STARTING_FIELD)
            runMetricsArtifact?.let { artifact ->
                addEntry(
                    RunMetricsV1ArtifactStore.FILE_NAME,
                    artifact.bytes,
                    RunManifestEntryCategory.RUN_METRICS
                )
            }
            acceptanceAdmissionBytes?.let { bytes ->
                addEntry(
                    AcceptanceAdmissionArtifact.FILE_NAME,
                    bytes,
                    RunManifestEntryCategory.ACCEPTANCE_ADMISSION
                )
            }
            addEntry(
                "authority/council-field.json",
                startingFieldBytes,
                RunManifestEntryCategory.COUNCIL_FIELD
            )
            workerAtlas?.let { atlas ->
                addEntry(
                    "authority/worker-atlas.json",
                    json.encodeToString(NodeAtlas.serializer(), atlas)
                        .toByteArray(Charsets.UTF_8),
                    RunManifestEntryCategory.WORKER_ATLAS
                )
            }
            graderAtlas?.let { atlas ->
                addEntry(
                    "authority/grader-atlas.json",
                    json.encodeToString(
                        com.achulkov.evomolior.core.grader.GraderAtlas.serializer(),
                        atlas
                    ).toByteArray(Charsets.UTF_8),
                    RunManifestEntryCategory.GRADER_ATLAS
                )
            }
            contributionLineageByGroup.forEach { (groupId, lineage) ->
                addEntry(
                    "authority/contribution-lineage/$groupId.json",
                    json.encodeToString(
                        ListSerializer(ContributionEdge.serializer()),
                        lineage
                    ).toByteArray(Charsets.UTF_8),
                    RunManifestEntryCategory.CONTRIBUTION_LINEAGE
                )
            }
            eventLogsByGroup.forEach { (groupId, events) ->
                addEntry(
                    "logs/$groupId.json",
                    json.encodeToString(
                        ListSerializer(RunManifestEventLogRecord.serializer()),
                        events
                    ).toByteArray(Charsets.UTF_8),
                    RunManifestEntryCategory.LOG
                )
            }
            addEntry(
                "authority/final-candidates.json",
                json.encodeToString(
                    ListSerializer(FinalCandidateRecord.serializer()),
                    finalCandidates
                ).toByteArray(Charsets.UTF_8),
                RunManifestEntryCategory.PUBLICATION_AUTHORITY
            )
            addEntry(
                "authority/final-deliverable-references.json",
                json.encodeToString(
                    ListSerializer(FinalDeliverableReferenceRecord.serializer()),
                    finalReferenceRecords
                ).toByteArray(Charsets.UTF_8),
                RunManifestEntryCategory.PUBLICATION_AUTHORITY
            )
            addEntry(
                "authority/publication-history.json",
                json.encodeToString(
                    ListSerializer(PublicationStateEvent.serializer()),
                    publicationHistory
                ).toByteArray(Charsets.UTF_8),
                RunManifestEntryCategory.PUBLICATION_AUTHORITY
            )
            val configSource = persistedStateFile
            val configSourceBytes = persistedStateBytes ?: startingFieldBytes
            if (configSource != null) {
                addEntry(
                    configSource.relativeTo(runRoot).invariantSeparatorsPath,
                    configSourceBytes,
                    RunManifestEntryCategory.RUN_CONFIG_METADATA
                )
            }
            val configAttestation = RunConfigAttestation(
                runId = runId,
                source = configSource?.relativeTo(runRoot)?.invariantSeparatorsPath ?: "starting-field.json",
                sourceSizeBytes = configSourceBytes.size.toLong(),
                sourceSha256 = sha256Hex(configSourceBytes)
            )
            addEntry(
                "metadata/run-config.json",
                json.encodeToString(RunConfigAttestation.serializer(), configAttestation)
                    .toByteArray(Charsets.UTF_8),
                RunManifestEntryCategory.RUN_CONFIG_METADATA
            )
            val codeSource = RunArchiveService::class.java.protectionDomain?.codeSource?.location?.toString()
                ?: "unknown"
            val binaryBytes = RunArchiveService::class.java.getResourceAsStream("RunArchiveService.class")
                ?.use { it.readBytes() }
                ?: codeSource.toByteArray(Charsets.UTF_8)
            val binaryAttestation = RunBinaryAttestation(
                runId = runId,
                component = "evomolior-api",
                implementationVersion = RunArchiveService::class.java.`package`?.implementationVersion
                    ?: "development",
                javaVersion = System.getProperty("java.version").orEmpty(),
                source = codeSource,
                sourceSha256 = sha256Hex(binaryBytes)
            )
            addEntry(
                "metadata/binary.json",
                json.encodeToString(RunBinaryAttestation.serializer(), binaryAttestation)
                    .toByteArray(Charsets.UTF_8),
                RunManifestEntryCategory.BINARY_METADATA
            )
            if (bundleGeneratedOutputs.isNotEmpty()) {
                bundleGeneratedOutputs.forEach { outputFile ->
                    addEntry(
                        "outputs/${outputFile.relativePath}",
                        outputFile.content.toByteArray(Charsets.UTF_8),
                        RunManifestEntryCategory.OUTPUT,
                        mediaType = outputFile.mediaType
                    )
                }
            } else {
                check(!preserveActiveCompiledOutputBytes) {
                    "ACTIVE compiled Council archive cannot synthesize a replacement final output"
                }
                resolvedCompletionRecord?.let { record ->
                    buildFinalArticleHtml(startingField, record, resolvedRequestedOutputSpec)?.let { fallbackHtml ->
                        val fallbackOutput = renderSlideImagesForGeneratedOutput(
                            runId = runId,
                            outputFile = GeneratedOutputFile(
                                relativePath = resolvedRequestedOutputSpec?.relativePath ?: "final-article.html",
                                mediaType = resolvedRequestedOutputSpec?.mediaType ?: "text/html",
                                content = fallbackHtml
                            ),
                            renderedSlideAssets = renderedSlideAssets
                        )
                        val validatedFallback = validateRenderedArchiveOutput(
                            runId = runId,
                            entries = entries,
                            completionRecord = resolvedCompletionRecord,
                            requestedOutputSpec = resolvedRequestedOutputSpec,
                            output = fallbackOutput
                        )
                        addEntry(
                            "outputs/${validatedFallback.relativePath}",
                            validatedFallback.content.toByteArray(Charsets.UTF_8),
                            RunManifestEntryCategory.OUTPUT,
                            mediaType = validatedFallback.mediaType
                        )
                    }
                }
            }
            resolvedCompletionRecord?.generatedImageAssets
                ?.filter { it.reviewStatus == "verified" }
                ?.forEach { asset ->
                    val assetFile = generatedImageAssetFile(runId, asset)
                    if (assetFile.exists() && assetFile.isFile) {
                        addEntry(
                            "outputs/${asset.relativePath}",
                            Files.readAllBytes(assetFile.toPath()),
                            RunManifestEntryCategory.OUTPUT
                        )
                    }
                }
            renderedSlideAssets.forEach { asset ->
                val assetFile = File(runRoot, "results/outputs/${asset.relativePath}")
                if (assetFile.exists() && assetFile.isFile) {
                    addEntry(
                        "outputs/${asset.relativePath}",
                        Files.readAllBytes(assetFile.toPath()),
                        RunManifestEntryCategory.OUTPUT
                    )
                }
            }

            val highLevelRunLog = buildHighLevelRunLog(startingField, resolvedCompletionRecord, bundleGeneratedOutputs)
            addEntry(
                "outputs/high-level-run-log.txt",
                highLevelRunLog.toByteArray(Charsets.UTF_8),
                RunManifestEntryCategory.LOG
            )
            researchDeliverablePack.files.forEach { file ->
                addEntry(
                    file.relativePath,
                    file.content.toByteArray(Charsets.UTF_8),
                    RunManifestEntryCategory.OUTPUT
                )
                addEntry(
                    "outputs/${file.relativePath}",
                    file.content.toByteArray(Charsets.UTF_8),
                    RunManifestEntryCategory.OUTPUT
                )
            }

            val treeOutputs = File(resolveRunRoot(runId), "results")
                .listFiles { file -> file.isFile && file.name.startsWith("deliverable-skeleton-tree-") && file.name.endsWith(".json") }
                ?.sortedBy { it.name }
                .orEmpty()
            treeOutputs.forEach { treeFile ->
                addEntry(
                    "outputs/${treeFile.name}",
                    Files.readAllBytes(treeFile.toPath()),
                    RunManifestEntryCategory.OUTPUT
                )
            }
            val resultsRoot = File(resolveRunRoot(runId), "results")
            if (resultsRoot.isDirectory) {
                resultsRoot.walkTopDown()
                    .filter {
                        it.isFile &&
                            it.name != "results.zip" &&
                            !it.name.endsWith(".tmp")
                    }
                    .sortedBy { it.relativeTo(resultsRoot).invariantSeparatorsPath }
                    .forEach { file ->
                        addEntry(
                            "run-results/" +
                                file.relativeTo(resultsRoot).invariantSeparatorsPath,
                            Files.readAllBytes(file.toPath()),
                            RunManifestEntryCategory.OUTPUT
                        )
                    }
            }

            if (artifactsRoot.exists()) {
                val files = artifactsRoot.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.relativeTo(artifactsRoot).path }
                    .toList()
                files.forEach { file ->
                    val relative = file.relativeTo(artifactsRoot).invariantSeparatorsPath
                    addEntry(
                        "inputs/$relative",
                        Files.readAllBytes(file.toPath()),
                        RunManifestEntryCategory.INPUT
                    )
                }
            }

            var startingFieldEmitted = false
            entries.forEachIndexed { index, entry ->
                if (entry.entryType == ENTRY_STARTING_FIELD) {
                    if (startingFieldEmitted) return@forEachIndexed
                    startingFieldEmitted = true
                }
                val safeType = entry.entryType.lowercase()
                val safeId = entry.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
                addEntry(
                    "outputs/$safeType/$index-$safeId.json",
                    entry.contentJson.toByteArray(Charsets.UTF_8),
                    RunManifestEntryCategory.ARCHIVE
                )
            }

            runRoot.walkTopDown()
                .filter {
                    it.isFile &&
                        (it.name == "substrate.db" || it.name == "substrate.db-wal")
                }
                .sortedBy { it.relativeTo(runRoot).invariantSeparatorsPath }
                .forEach { databaseFile ->
                    addEntry(
                        "databases/" +
                            databaseFile.relativeTo(runRoot).invariantSeparatorsPath,
                        Files.readAllBytes(databaseFile.toPath()),
                        RunManifestEntryCategory.DATABASE
                    )
                }
            val hasV2Authorities =
                workerAtlas != null &&
                    graderAtlas != null &&
                    finalReferenceRecords.isNotEmpty()
            val isNonNormalAcceptance = persistedState?.acceptanceProfile
                ?.let { it != com.achulkov.evomolior.core.config.AcceptanceProfile.NORMAL }
                ?: false
            if (isNonNormalAcceptance) {
                require(hasV2Authorities) {
                    "Non-normal acceptance archive requires schema-v2 final-deliverable authorities"
                }
                requireNotNull(runMetricsArtifact) {
                    "Non-normal acceptance archive requires ${RunMetricsV1ArtifactStore.FILE_NAME}"
                }
                requireNotNull(acceptanceAdmission) {
                    "Non-normal acceptance archive requires ${AcceptanceAdmissionArtifact.FILE_NAME}"
                }
            }
            val manifestSchemaVersion = when {
                isNonNormalAcceptance -> 4
                hasV2Authorities &&
                    runMetricsArtifact != null &&
                    acceptanceAdmission != null -> 4
                hasV2Authorities && runMetricsArtifact != null -> 3
                hasV2Authorities -> 2
                else -> 1
            }
            val manifest = RunManifest(
                schemaVersion = manifestSchemaVersion,
                runId = runId,
                councilExecutionPurpose = persistedState
                    ?.councilExecutionPurpose
                    ?: CouncilExecutionPurpose.STANDARD,
                acceptanceProfile = persistedState
                    ?.acceptanceProfile
                    ?: com.achulkov.evomolior.core.config.AcceptanceProfile.NORMAL,
                objective = startingField.objective,
                groupCount = startingField.groupCount,
                generationCeiling = startingField.generationCeiling,
                startingFieldArchiveId = startingFieldEntry.id,
                startingFieldSha256 = sha256Hex(startingFieldBytes),
                runMetricsSha256 = runMetricsArtifact?.sha256,
                acceptanceAdmissionSha256 = acceptanceAdmissionBytes?.let(::sha256Hex),
                artifactCount = startingField.inputArtifacts.size,
                archiveEntryCount = manifestEntries.count {
                    it.category == RunManifestEntryCategory.ARCHIVE
                },
                inputArtifacts = startingField.inputArtifacts,
                entries = manifestEntries.sortedBy { it.path }
            )
            addEntry(
                "manifest.json",
                json.encodeToString(RunManifest.serializer(), manifest).toByteArray(Charsets.UTF_8),
                RunManifestEntryCategory.ARCHIVE,
                attest = false
            )
        }

        output.toByteArray()
    }

    /**
     * Writes the downloadable results ZIP to the run's results directory.
     *
     * @param runId The run identifier.
     * @param completionRecord Optional pre-decoded completion record to avoid re-reading the archive.
     * @param requestedOutputSpec Canonical output spec for the run's operator-facing generated file.
     *   When present, this is preferred over the persisted-state file for archive recovery.
     * @return Path to the written ZIP file, or null if the bundle could not be built.
     */
    suspend fun writeResultsBundle(
        runId: String,
        completionRecord: RunCompletionRecord? = null,
        requestedOutputSpec: FinalOutputSpec? = null
    ): File? = withContext(Dispatchers.IO) {
        val bytes = buildResultsBundle(runId, completionRecord, requestedOutputSpec) ?: return@withContext null
        val bundleFile = resultsBundleFile(runId)
        bundleFile.parentFile?.mkdirs()
        bundleFile.writeBytes(bytes)
        bundleFile
    }

    companion object {
        /** Pseudo-group ID used for run-level meta-archive database. */
        private const val META_ARCHIVE_GROUP_ID = "__meta_archive__"
        private const val ZIP_EPOCH_1980_MS = 315_532_800_000L

        const val ENTRY_STARTING_FIELD   = "STARTING_FIELD"
        const val ENTRY_GRADER_STARTING_FIELD = "grader_starting_field"
        const val ENTRY_COUNCIL_EXECUTION_ENVELOPE = "council_execution_envelope"
        const val ENTRY_COUNCIL_CONSISTENCY_REPORT = "council_consistency_report"
        const val ENTRY_COUNCIL_RECEIVER_CONTRACT = "council_receiver_contract"
        const val ENTRY_GRADER_ATLAS = "grader_atlas"
        const val ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST =
            "compiled_council_candidate_manifest_v3"
        const val ENTRY_ACTIVE_COMPILED_COUNCIL_MANIFEST =
            "active_compiled_council_manifest_v3"
        const val ENTRY_DEAD_ENDS        = "DEAD_ENDS_EXPORT"
        const val ENTRY_LEGACY_PROTOCOLS = "LEGACY_PROTOCOLS"
        const val ENTRY_WINNING_KB       = "WINNING_KB_SNAPSHOT"
        const val ENTRY_DELIVERABLE_TREE  = "DELIVERABLE_SKELETON_TREE"
        const val ENTRY_OVERSEER_AUDIT    = com.achulkov.evomolior.core.contract.OverseerKernelArchive.ENTRY_TYPE
        const val ENTRY_ROUND_SUMMARY    = "ROUND_SUMMARY"
        const val ENTRY_GRADING_SHEET    = "GRADING_SHEET"
        const val ENTRY_RUN_COMPLETED    = "RUN_COMPLETED"
        const val ENTRY_RUN_GRAPH_MESSAGE = "RUN_GRAPH_MESSAGE"
        const val ENTRY_ADVERSARIAL_DISCRIMINATOR_FEEDBACK = "ADVERSARIAL_DISCRIMINATOR_FEEDBACK"
        const val ENTRY_ADVERSARIAL_DISCRIMINATOR_REWARD = "ADVERSARIAL_DISCRIMINATOR_REWARD"
        const val ENTRY_GRADER_GROUP_FEEDBACK = "GRADER_GROUP_FEEDBACK"
        private const val MANIFEST_EVENT_PAGE_SIZE = 1_000

        internal val REQUIRED_DETERMINISTIC_DELIVERABLES = listOf(
            "final_report.md",
            "claim_ledger.jsonl",
            "citation_table.csv",
            "benchmark_results.csv",
            "cost_report.json",
            "run_replay.html"
        )
    }

    private fun resolveRunRoot(runId: String): File = dbFactory.dbFilePath(runId, "group-0")
        .parentFile?.parentFile?.parentFile
        ?: error("Unable to resolve run root for $runId")

    private fun resultsBundleFile(runId: String): File = File(resolveRunRoot(runId), "results/results.zip")

    private fun finalArticleFile(runId: String): File = File(resolveRunRoot(runId), "results/final-article.html")

    private fun generatedOutputFile(runId: String, relativePath: String): File =
        File(resolveRunRoot(runId), "results/$relativePath")

    private fun deterministicOutputFile(runId: String, relativePath: String): File =
        File(resolveRunRoot(runId), "results/outputs/$relativePath")

    internal fun ensureDeterministicDeliverablesWritten(runId: String) {
        val missing = REQUIRED_DETERMINISTIC_DELIVERABLES.filterNot { relativePath ->
            deterministicOutputFile(runId, relativePath).isFile
        }
        check(missing.isEmpty()) {
            "Run $runId is missing deterministic deliverable file(s): ${missing.joinToString()}"
        }
    }

    private fun generatedImageAssetFile(runId: String, asset: GeneratedImageAsset): File {
        val normalized = asset.relativePath.replace('\\', '/').removePrefix("/")
        require(!normalized.startsWith("../") && !normalized.contains("/../")) {
            "Generated image asset path escapes output directory: ${asset.relativePath}"
        }
        return File(resolveRunRoot(runId), "results/outputs/$normalized")
    }

    private suspend fun writeGeneratedOutputFiles(
        runId: String,
        completionRecord: RunCompletionRecord? = null,
        requestedOutputSpec: FinalOutputSpec? = null
    ): List<File> = withContext(Dispatchers.IO) {
        val archiveDb = dbFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val entries = archiveDb.metaArchiveDao().getByRun(runId)
        val resolvedCompletionRecord = completionRecord ?: entries.firstOrNull { it.entryType == ENTRY_RUN_COMPLETED }?.let {
            runCatching {
                json.decodeFromString(RunCompletionRecord.serializer(), it.contentJson)
            }.getOrNull()
        }
        val resolvedRequestedOutputSpec = requestedOutputSpec
            ?: resolvedCompletionRecord?.requestedOutputSpec
            ?: resolveRequestedOutputSpec(runId)
        val generatedOutputs = resolveGeneratedOutputFiles(
            runId = runId,
            entries = entries,
            completionRecord = resolvedCompletionRecord,
            requestedOutputSpec = resolvedRequestedOutputSpec
        )
        val preserveActiveCompiledOutputBytes =
            hasActiveCompiledCouncilManifest(entries)
        val renderedOutputs = mutableListOf<GeneratedOutputFile>()
        generatedOutputs.forEach { output ->
            val rendered = if (preserveActiveCompiledOutputBytes) {
                output
            } else {
                renderSlideImagesForGeneratedOutput(
                    runId = runId,
                    outputFile = output,
                    renderedSlideAssets = mutableListOf()
                )
            }
            renderedOutputs += validateRenderedArchiveOutput(
                runId = runId,
                entries = entries,
                completionRecord = resolvedCompletionRecord,
                requestedOutputSpec = resolvedRequestedOutputSpec,
                output = rendered
            )
        }
        val startingField = runCatching {
            val startingFieldEntry = selectCanonicalStartingFieldEntry(entries) ?: return@runCatching null
            json.decodeFromString(StartingField.serializer(), startingFieldEntry.contentJson)
        }.getOrNull()
        checkNotNull(startingField) {
            "Cannot write deterministic deliverables for $runId because STARTING_FIELD is missing or invalid."
        }
        val researchDeliverablePack = buildResearchDeliverablePack(
            runId = runId,
            startingField = startingField,
            completionRecord = resolvedCompletionRecord,
            generatedOutputs = renderedOutputs,
            entries = entries
        )
        researchDeliverablePack.files.forEach { output ->
            listOf(
                generatedOutputFile(runId, output.relativePath),
                File(resolveRunRoot(runId), "results/outputs/${output.relativePath}")
            ).distinctBy { it.absolutePath }.forEach { file ->
                file.parentFile?.mkdirs()
                file.writeText(output.content, Charsets.UTF_8)
            }
        }
        generatedOutputFile(runId, "high-level-run-log.txt").also { file ->
            file.parentFile?.mkdirs()
            file.writeText(
                buildHighLevelRunLog(startingField, resolvedCompletionRecord, renderedOutputs),
                Charsets.UTF_8
            )
        }
        renderedOutputs.map { renderedOutput ->
            generatedOutputFile(runId, renderedOutput.relativePath).also { file ->
                file.parentFile?.mkdirs()
                file.writeText(renderedOutput.content, Charsets.UTF_8)
            }
        }
    }

    private fun renderSlideImagesForGeneratedOutput(
        runId: String,
        outputFile: GeneratedOutputFile,
        renderedSlideAssets: MutableList<RenderedSlideImageAsset>
    ): GeneratedOutputFile {
        if (!outputFile.mediaType.startsWith("text/html", ignoreCase = true)) return outputFile
        val outputsRoot = File(resolveRunRoot(runId), "results/outputs").also { it.mkdirs() }
        val rendered = GeneratedSlideImageRenderer.renderAndEmbed(
            html = outputFile.content,
            outputDirectory = outputsRoot
        )
        renderedSlideAssets += rendered.assets
        return outputFile.copy(
            content = rendered.html,
            contentHash = AtlasHashing.hashText(rendered.html)
        )
    }

    private suspend fun validateRenderedArchiveOutput(
        runId: String,
        entries: List<MetaArchiveEntryEntity>,
        completionRecord: RunCompletionRecord?,
        requestedOutputSpec: FinalOutputSpec?,
        output: GeneratedOutputFile
    ): GeneratedOutputFile {
        val validated = validateGeneratedOutputFile(
            output,
            normalizeHtmlStructure = !hasActiveCompiledCouncilManifest(entries)
        )
        requireRequestedOutputContract(validated, requestedOutputSpec)
        VerifiedSourceCatalog.validate(
            validated,
            VerifiedSourceCatalog.resolve(resolveRunRoot(runId))
        )
        requireActiveCompiledCouncilOutput(
            runId = runId,
            entries = entries,
            completionRecord = completionRecord,
            output = validated
        )
        return validated
    }

    private fun hasActiveCompiledCouncilManifest(
        entries: List<MetaArchiveEntryEntity>
    ): Boolean = entries.any {
        it.entryType == ENTRY_ACTIVE_COMPILED_COUNCIL_MANIFEST
    }

    private suspend fun writeFinalArticleHtml(
        runId: String,
        completionRecord: RunCompletionRecord? = null,
        requestedOutputSpec: FinalOutputSpec? = null
    ): File? = withContext(Dispatchers.IO) {
        val archiveDb = dbFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val entries = archiveDb.metaArchiveDao().getByRun(runId)
        val startingFieldEntry = selectCanonicalStartingFieldEntry(entries) ?: return@withContext null
        val startingField = runCatching {
            json.decodeFromString(StartingField.serializer(), startingFieldEntry.contentJson)
        }.getOrElse {
            return@withContext null
        }
        val resolvedCompletionRecord = completionRecord ?: entries.firstOrNull { it.entryType == ENTRY_RUN_COMPLETED }?.let {
            runCatching {
                json.decodeFromString(RunCompletionRecord.serializer(), it.contentJson)
            }.getOrNull()
        } ?: return@withContext null
        val resolvedRequestedOutputSpec = requestedOutputSpec
            ?: resolvedCompletionRecord?.requestedOutputSpec
            ?: resolveRequestedOutputSpec(runId)
        if (hasActiveCompiledCouncilManifest(entries)) {
            return@withContext null
        }
        if (resolvedRequestedOutputSpec != null &&
            !resolvedRequestedOutputSpec.mediaType.startsWith("text/html", ignoreCase = true)
        ) {
            return@withContext null
        }
        val file = finalArticleFile(runId)
        file.parentFile?.mkdirs()
        val html = buildFinalArticleHtml(startingField, resolvedCompletionRecord, resolvedRequestedOutputSpec) ?: return@withContext null
        val renderedHtml = renderSlideImagesForGeneratedOutput(
            runId = runId,
            outputFile = GeneratedOutputFile(
                relativePath = resolvedRequestedOutputSpec?.relativePath ?: "final-article.html",
                mediaType = resolvedRequestedOutputSpec?.mediaType ?: "text/html",
                content = html
            ),
            renderedSlideAssets = mutableListOf()
        ).content
        file.writeText(renderedHtml, Charsets.UTF_8)
        file
    }

    private suspend fun archiveDeliverableSkeletonTree(
        runId: String,
        groupId: String,
        objective: String,
        finalGeneration: Int,
        archivedAtMs: Long,
        isFromWinner: Boolean
    ) = withContext(Dispatchers.IO) {
        val treeFile = File(dbFactory.groupControlDir(runId, groupId), "deliverable-skeleton-tree.json")
        if (!treeFile.exists()) return@withContext

        val resultsTreeFile = File(resolveRunRoot(runId), "results/deliverable-skeleton-tree-$groupId.json")
        resultsTreeFile.parentFile?.mkdirs()
        resultsTreeFile.writeBytes(treeFile.readBytes())

        val archiveDb = dbFactory.get(runId, META_ARCHIVE_GROUP_ID)
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = ENTRY_DELIVERABLE_TREE,
                groupId = groupId,
                label = "Deliverable Skeleton Tree — $groupId — run $runId",
                contentJson = treeFile.readText(),
                runObjective = objective,
                finalScore = null,
                isFromWinner = isFromWinner,
                generation = finalGeneration,
                archivedAtMs = archivedAtMs
            )
        )
    }

    private fun buildFinalArticleHtml(
        startingField: StartingField,
        completionRecord: RunCompletionRecord,
        requestedOutputSpec: FinalOutputSpec? = null
    ): String? {
        val sourceCatalog = VerifiedSourceCatalog.resolve(resolveRunRoot(completionRecord.runId))
        completionRecord.generatedOutputFiles
            .firstOrNull { it.mediaType.startsWith("text/html", ignoreCase = true) }
            ?.let { authoredHtml ->
                return runCatching {
                    validateGeneratedOutputFile(authoredHtml)
                    VerifiedSourceCatalog.validate(authoredHtml, sourceCatalog)
                    normalizeStandaloneHtml(authoredHtml.content)
                }.getOrNull()
            }
        completionRecord.finalDeliverableHtml
            ?.takeIf { it.isNotBlank() }
            ?.let { authoredHtml ->
                val resolvedRequestedOutputSpec = requestedOutputSpec ?: resolveRequestedOutputSpec(completionRecord.runId)
                return runCatching {
                    val output = validateGeneratedOutputFile(
                        GeneratedOutputFile(
                            relativePath = resolvedRequestedOutputSpec?.relativePath ?: "final-article.html",
                            mediaType = resolvedRequestedOutputSpec?.mediaType ?: "text/html",
                            content = authoredHtml
                        )
                    )
                    VerifiedSourceCatalog.validate(output, sourceCatalog)
                    normalizeStandaloneHtml(authoredHtml)
                }.getOrNull()
            }
        extractEmbeddedFinalDeliverableHtml(completionRecord)
            ?.let { authoredHtml ->
                val resolvedRequestedOutputSpec = requestedOutputSpec ?: resolveRequestedOutputSpec(completionRecord.runId)
                return runCatching {
                    val output = validateGeneratedOutputFile(
                        GeneratedOutputFile(
                            relativePath = resolvedRequestedOutputSpec?.relativePath ?: "final-article.html",
                            mediaType = resolvedRequestedOutputSpec?.mediaType ?: "text/html",
                            content = authoredHtml
                        )
                    )
                    VerifiedSourceCatalog.validate(output, sourceCatalog)
                    normalizeStandaloneHtml(authoredHtml)
                }.getOrNull()
            }
        val resolvedRequestedOutputSpec = requestedOutputSpec ?: resolveRequestedOutputSpec(completionRecord.runId)
        if (resolvedRequestedOutputSpec != null &&
            !resolvedRequestedOutputSpec.mediaType.startsWith("text/html", ignoreCase = true)
        ) {
            return null
        }

        val title = startingField.objective
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(80)
            ?: "EvoMolior Fast Run Summary"
        val winnerLabel = completionRecord.winnerId?.let { escapeHtml(it) } ?: "Unresolved"
        val objectiveParagraphs = splitParagraphs(startingField.objective)
        val finalAnswerParagraphs = splitParagraphs(
            completionRecord.finalAnswerSummary.ifBlank { "No final answer summary was recorded." }
        )
        val judgeCards = completionRecord.judgeOutputsByGrader.entries.sortedBy { it.key }
        val submissionCards = completionRecord.submissionSummariesByGroup.entries.sortedBy { it.key }
        val checkinMilestones = if (startingField.checkinMilestones.isEmpty()) "None" else {
            startingField.checkinMilestones.joinToString(", ")
        }
        val ringTopology = if (startingField.ringTopology.isEmpty()) "None" else {
            startingField.ringTopology.joinToString(" -> ")
        }

        return buildString {
            append("<!doctype html>\n")
            append("<html lang=\"en\">\n")
            append("<head>\n")
            append("  <meta charset=\"utf-8\">\n")
            append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("  <meta name=\"color-scheme\" content=\"dark light\">\n")
            append("  <title>").append(escapeHtml(title)).append("</title>\n")
            append("  <meta name=\"description\" content=\"").append(escapeHtml(startingField.objective.take(160))).append("\">\n")
            append("  <style>\n")
            append("    :root{color-scheme:dark light;--bg:#0f1115;--panel:#171a21;--panel-soft:#1d212a;--text:#eef1f6;--muted:#a7afbd;--accent:#7cb3ff;--border:rgba(255,255,255,.08);--shadow:0 18px 50px rgba(0,0,0,.28);font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;}\n")
            append("    body{margin:0;background:linear-gradient(180deg,#0b0d11 0%,#10141a 100%);color:var(--text);}\n")
            append("    main{max-width:1080px;margin:0 auto;padding:32px 20px 56px;}\n")
            append("    article{background:rgba(10,12,16,.72);border:1px solid var(--border);border-radius:24px;box-shadow:var(--shadow);overflow:hidden;}\n")
            append("    header.hero{padding:40px 40px 28px;background:linear-gradient(135deg,rgba(124,179,255,.18),rgba(124,179,255,0));border-bottom:1px solid var(--border);}\n")
            append("    .eyebrow{margin:0 0 12px;color:var(--accent);text-transform:uppercase;letter-spacing:.12em;font-size:.8rem;font-weight:700;}\n")
            append("    h1{margin:0;font-size:clamp(2rem,4vw,3.5rem);line-height:1.05;}\n")
            append("    .lede{max-width:72ch;color:var(--muted);font-size:1.05rem;line-height:1.7;margin:16px 0 0;}\n")
            append("    .hero-grid,.metric-grid,.card-grid{display:grid;gap:16px;}\n")
            append("    .hero-grid{grid-template-columns:repeat(auto-fit,minmax(180px,1fr));margin-top:28px;}\n")
            append("    .metric-grid{grid-template-columns:repeat(auto-fit,minmax(170px,1fr));margin:0;}\n")
            append("    .card-grid{grid-template-columns:repeat(auto-fit,minmax(280px,1fr));}\n")
            append("    .metric-grid,.hero-grid{grid-template-columns:repeat(auto-fit,minmax(180px,1fr));}\n")
            append("    .hero-grid{margin-top:28px;}\n")
            append("    .metric-grid{margin:0;}\n")
            append("    .hero-grid dt,.hero-grid dd,.metric-grid dt,.metric-grid dd{margin:0;}\n")
            append("    .hero-grid dt,.metric-grid dt,.card h3,.section-title{color:var(--muted);text-transform:uppercase;letter-spacing:.08em;font-size:.78rem;}\n")
            append("    .hero-grid dt,.metric-grid dt{margin:0 0 10px;}\n")
            append("    .hero-grid dd,.metric-grid dd{margin:0;font-size:1.05rem;line-height:1.5;}\n")
            append("    .card,.panel{background:var(--panel);border:1px solid var(--border);border-radius:18px;padding:18px;}\n")
            append("    section.block{padding:28px 40px;border-top:1px solid var(--border);}\n")
            append("    .section-title{margin:0 0 14px;}\n")
            append("    p{margin:0 0 14px;line-height:1.75;color:var(--text);}\n")
            append("    p:last-child{margin-bottom:0;}\n")
            append("    .muted{color:var(--muted);}\n")
            append("    .tag-row{display:flex;flex-wrap:wrap;gap:10px;}\n")
            append("    .tag{display:inline-flex;align-items:center;padding:8px 12px;border-radius:999px;background:var(--panel-soft);border:1px solid var(--border);color:var(--text);font-size:.92rem;}\n")
            append("    pre{margin:0;white-space:pre-wrap;word-break:break-word;font:inherit;line-height:1.75;color:var(--text);background:var(--panel-soft);border:1px solid var(--border);border-radius:16px;padding:16px;}\n")
            append("    details{background:var(--panel);border:1px solid var(--border);border-radius:16px;padding:14px 16px;}\n")
            append("    details+details{margin-top:12px;}\n")
            append("    summary{cursor:pointer;font-weight:700;color:var(--text);}\n")
            append("    footer{padding:24px 40px 36px;color:var(--muted);border-top:1px solid var(--border);}\n")
            append("    @media (max-width:700px){header.hero,section.block,footer{padding-left:20px;padding-right:20px;}}\n")
            append("  </style>\n")
            append("</head>\n")
            append("<body>\n")
            append("  <main>\n")
            append("    <article>\n")
            append("      <header class=\"hero\">\n")
            append("        <p class=\"eyebrow\">EvoMolior / MindVortex fast-run article</p>\n")
            append("        <h1>").append(escapeHtml(title)).append("</h1>\n")
            append("        <p class=\"lede\">A durable HTML5 article generated from the completed run archive, suitable for handoff into the MindVortex publishing flow.</p>\n")
            append("        <dl class=\"hero-grid\">\n")
            appendMetric("Run ID", completionRecord.runId)
            appendMetric("Winner", winnerLabel)
            appendMetric("Status", if (completionRecord.winnerId != null) "Completed with a declared winner" else "Completed without a declared winner")
            append("        </dl>\n")
            append("      </header>\n")
            append("      <section class=\"block\">\n")
            append("        <h2 class=\"section-title\">Run Snapshot</h2>\n")
            append("        <dl class=\"metric-grid\">\n")
            appendMetric("Groups", startingField.groupCount.toString())
            appendMetric("Generation ceiling", startingField.generationCeiling.toString())
            appendMetric("Check-in milestones", checkinMilestones)
            appendMetric("Ring topology", ringTopology)
            appendMetric("Budget ceiling", startingField.globalBudgetCeiling.toString())
            appendMetric("Grader groups", startingField.graderGroupCount.toString())
            append("        </dl>\n")
            append("      </section>\n")
            append("      <section class=\"block\">\n")
            append("        <h2 class=\"section-title\">Objective</h2>\n")
            objectiveParagraphs.forEach { paragraph ->
                append("        <p>").append(escapeHtml(paragraph)).append("</p>\n")
            }
            append("      </section>\n")
            append("      <section class=\"block\">\n")
            append("        <h2 class=\"section-title\">Final Answer</h2>\n")
            append("        <div class=\"panel\">\n")
            finalAnswerParagraphs.forEach { paragraph ->
                append("          <p>").append(escapeHtml(paragraph)).append("</p>\n")
            }
            append("        </div>\n")
            append("      </section>\n")
            append("      <section class=\"block\">\n")
            append("        <h2 class=\"section-title\">Judgement Notes</h2>\n")
            if (judgeCards.isEmpty()) {
                append("        <p class=\"muted\">No grader outputs were recorded.</p>\n")
            } else {
                append("        <div class=\"card-grid\">\n")
                judgeCards.forEach { (graderId, output) ->
                    append("          <section class=\"card\">\n")
                    append("            <h3>").append(escapeHtml(graderId)).append("</h3>\n")
                    append("            <pre>").append(escapeHtml(output.ifBlank { "No grader output recorded." })).append("</pre>\n")
                    append("          </section>\n")
                }
                append("        </div>\n")
            }
            append("      </section>\n")
            append("      <section class=\"block\">\n")
            append("        <h2 class=\"section-title\">Group Summaries</h2>\n")
            if (submissionCards.isEmpty()) {
                append("        <p class=\"muted\">No submission summaries were recorded.</p>\n")
            } else {
                append("        <div class=\"card-grid\">\n")
                submissionCards.forEach { (groupId, summary) ->
                    append("          <details>\n")
                    append("            <summary>").append(escapeHtml(groupId)).append("</summary>\n")
                    append("            <pre>").append(escapeHtml(summary.orEmpty().ifBlank { "No summary recorded." })).append("</pre>\n")
                    append("          </details>\n")
                }
                append("        </div>\n")
            }
            append("      </section>\n")
            append("      <section class=\"block\">\n")
            append("        <h2 class=\"section-title\">Context</h2>\n")
            append("        <div class=\"tag-row\">\n")
            appendTag("Published from durable run metadata")
            appendTag("HTML5 article")
            appendTag("Archive-backed output")
            appendTag("Fast-mode compatible")
            append("        </div>\n")
            append("      </section>\n")
            append("      <footer>\n")
            append("        Generated at ").append(escapeHtml(Instant.ofEpochMilli(completionRecord.completedAtMs).toString())).append(" from the completed run archive.\n")
            append("      </footer>\n")
            append("    </article>\n")
            append("  </main>\n")
            append("</body>\n")
            append("</html>\n")
        }
    }

    private fun buildHighLevelRunLog(
        startingField: StartingField,
        completionRecord: RunCompletionRecord?,
        generatedOutputs: List<GeneratedOutputFile>
    ): String = buildString {
        appendLine("EvoMolior High-Level Run Log")
        appendLine("Run ID: ${startingField.runId}")
        appendLine("Objective: ${startingField.objective.take(240)}")
        appendLine("Winner: ${completionRecord?.winnerId ?: "unresolved"}")
        appendLine("Completed at: ${completionRecord?.completedAtMs?.let { java.time.Instant.ofEpochMilli(it) } ?: java.time.Instant.EPOCH}")
        appendLine("Final outputs: ${if (generatedOutputs.isEmpty()) "none" else generatedOutputs.joinToString { it.relativePath }}")
        appendLine("Judge outputs recorded: ${completionRecord?.judgeOutputsByGrader?.size ?: 0}")
        appendLine("Group summaries recorded: ${completionRecord?.submissionSummariesByGroup?.size ?: 0}")
        completionRecord?.substrateHealthByGroup
            ?.takeIf { it.isNotEmpty() }
            ?.let { healthByGroup ->
                appendLine("Substrate health:")
                healthByGroup.sortedBy { it.groupId }.forEach { health ->
                    appendLine(
                        "- ${health.groupId}: kb=${health.kbCount}, kg=${health.kgCount}, " +
                            "kgSubmissions=${health.kgFactSubmissionCount}, " +
                            "tools=${health.activeToolCount}, toolSubmissions=${health.toolSubmissionCount}, " +
                            "toolExec=${health.toolExecutionCount}, " +
                            "kgAbstain=${health.kgAbstentionRationaleCount}, " +
                            "toolAbstain=${health.toolAbstentionRationaleCount}, " +
                            "noActionFallback=${health.noActionFallbackSubmissionCount}, " +
                            "decayEvents=${health.trustDecayEventCount}, " +
                            "decayArchived=${health.lowTrustArchivedByDecayCount}, " +
                            "staged=${health.stagedSubmissionCount}, promoted=${health.promotedSubmissionCount}, " +
                            "pending=${health.pendingSubmissionCount}, rejected=${health.rejectedSubmissionCount}, " +
                            "outlierSubmissions=${health.outlierSubmissionCount}, " +
                            "roundSummary=${health.roundSubmissionSummaryPresent}"
                    )
                }
            }
        completionRecord?.finalAnswerSummary
            ?.takeIf { it.isNotBlank() }
            ?.let { summary ->
                appendLine()
                appendLine("Final answer summary:")
                appendLine(summary.lineSequence().firstOrNull().orEmpty().take(280))
            }
        completionRecord?.submissionSummariesByGroup
            ?.entries
            ?.sortedBy { it.key }
            ?.forEach { (groupId, summary) ->
                val headline = summary.orEmpty().lineSequence().firstOrNull().orEmpty().take(180)
                appendLine("- $groupId: ${headline.ifBlank { "no submission summary" }}")
            }
        appendLine()
        appendLine("Note: detailed event logs remain in the archive and run event stream.")
    }

    private suspend fun buildResearchDeliverablePack(
        runId: String,
        startingField: StartingField,
        completionRecord: RunCompletionRecord?,
        generatedOutputs: List<GeneratedOutputFile>,
        entries: List<MetaArchiveEntryEntity>
    ): ResearchDeliverablePack {
        val sourceIds = resolveResearchSourceIds(startingField, generatedOutputs)
        val claims = extractResearchClaims(
            completionRecord = completionRecord,
            generatedOutputs = generatedOutputs,
            sourceIds = sourceIds
        )
        val searchTraceEvents = collectSearchTraceEvents(runId, startingField, completionRecord)
        val costSignals = collectCostSignals(runId, startingField, completionRecord)
        val unknowns = buildResearchUnknowns(
            completionRecord = completionRecord,
            claims = claims,
            searchTraceEvents = searchTraceEvents,
            costSignals = costSignals,
            entries = entries
        )

        val finalReportMarkdown = buildFinalAnswerMarkdown(startingField, completionRecord, generatedOutputs, claims)
        val claimLedgerJsonl = encodeResearchClaimLedger(claims)
        val citationTableCsv = buildEvidenceTableCsv(startingField, generatedOutputs, sourceIds)
        val costReportJson = buildCostReportJson(
            runId = runId,
            startingField = startingField,
            completionRecord = completionRecord,
            entries = entries,
            claims = claims,
            searchTraceEvents = searchTraceEvents,
            costSignals = costSignals
        )

        return ResearchDeliverablePack(
            files = listOf(
                GeneratedOutputFile(
                    relativePath = "final_answer.md",
                    mediaType = "text/markdown",
                    content = finalReportMarkdown
                ),
                GeneratedOutputFile(
                    relativePath = "final_report.md",
                    mediaType = "text/markdown",
                    content = finalReportMarkdown
                ),
                GeneratedOutputFile(
                    relativePath = "claim_ledger.jsonl",
                    mediaType = "application/x-ndjson",
                    content = claimLedgerJsonl
                ),
                GeneratedOutputFile(
                    relativePath = "evidence_table.csv",
                    mediaType = "text/csv",
                    content = citationTableCsv
                ),
                GeneratedOutputFile(
                    relativePath = "citation_table.csv",
                    mediaType = "text/csv",
                    content = citationTableCsv
                ),
                GeneratedOutputFile(
                    relativePath = "citation_map.json",
                    mediaType = "application/json",
                    content = buildCitationMapJson(runId, claims, sourceIds)
                ),
                GeneratedOutputFile(
                    relativePath = "unknowns_and_contradictions.md",
                    mediaType = "text/markdown",
                    content = unknowns
                ),
                GeneratedOutputFile(
                    relativePath = "search_trace.jsonl",
                    mediaType = "application/x-ndjson",
                    content = searchTraceEvents.joinToString(separator = "\n", postfix = if (searchTraceEvents.isEmpty()) "" else "\n") {
                        it.toString()
                    }
                ),
                GeneratedOutputFile(
                    relativePath = "cost_report.json",
                    mediaType = "application/json",
                    content = costReportJson
                ),
                GeneratedOutputFile(
                    relativePath = "benchmark_results.csv",
                    mediaType = "text/csv",
                    content = buildBenchmarkResultsCsv(startingField, completionRecord, entries, costReportJson)
                ),
                GeneratedOutputFile(
                    relativePath = "run_replay.html",
                    mediaType = "text/html",
                    content = buildRunReplayHtml(startingField, completionRecord, generatedOutputs, entries)
                )
            )
        )
    }

    private fun resolveResearchSourceIds(
        startingField: StartingField,
        generatedOutputs: List<GeneratedOutputFile>
    ): List<String> {
        val artifactSources = startingField.inputArtifacts.map { artifact ->
            artifact.relativePath.takeIf { it.isNotBlank() } ?: artifact.sourceName
        }
        val outputSources = generatedOutputs.flatMap { output ->
            URL_PATTERN.findAll(output.content).map { normalizeSourceId(it.value) }.toList()
        }
        return (artifactSources + outputSources)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_RESEARCH_SOURCE_IDS)
    }

    private fun extractResearchClaims(
        completionRecord: RunCompletionRecord?,
        generatedOutputs: List<GeneratedOutputFile>,
        sourceIds: List<String>
    ): List<ResearchClaimRecord> {
        val textCandidates = buildList {
            completionRecord?.finalAnswerSummary?.takeIf { it.isNotBlank() }?.let { add(it) }
            generatedOutputs
                .filter { output ->
                    output.mediaType.startsWith("text/", ignoreCase = true) ||
                        output.mediaType.equals("application/json", ignoreCase = true)
                }
                .forEach { add(stripMarkupForClaims(it.content)) }
            completionRecord?.submissionSummariesByGroup
                ?.entries
                ?.sortedBy { it.key }
                ?.mapNotNull { it.value }
                ?.filter { it.isNotBlank() }
                ?.forEach { add(it) }
        }

        return textCandidates
            .asSequence()
            .flatMap { claimSentences(it).asSequence() }
            .map { sentence ->
                val directSources = URL_PATTERN.findAll(sentence).map { normalizeSourceId(it.value) }.toList()
                val supportingSources = directSources.ifEmpty {
                    sourceIds.filter { source -> sentence.contains(source, ignoreCase = true) }
                }.distinct()
                ResearchClaimRecord(
                    claim = sentence.take(MAX_CLAIM_LENGTH),
                    claim_type = inferClaimType(sentence),
                    supporting_sources = supportingSources,
                    quoted_or_paraphrased_evidence = supportingSources
                        .takeIf { it.isNotEmpty() }
                        ?.let { "Claim references traceable source id(s): ${it.joinToString()}" }
                        ?: "No traceable supporting source was extracted from the run archive for this claim.",
                    confidence = when {
                        supportingSources.size >= 2 -> 0.75
                        supportingSources.size == 1 -> 0.6
                        else -> 0.2
                    },
                    contradicting_sources = emptyList(),
                    used_in_final_answer = completionRecord?.finalAnswerSummary
                        ?.contains(sentence.take(80), ignoreCase = true)
                        ?: false
                )
            }
            .distinctBy { it.claim.lowercase() }
            .take(MAX_RESEARCH_CLAIMS)
            .toList()
    }

    private fun encodeResearchClaimLedger(
        claims: List<ResearchClaimRecord>
    ): String = claims.joinToString(
        separator = "\n",
        postfix = if (claims.isEmpty()) "" else "\n"
    ) {
        json.encodeToString(ResearchClaimRecord.serializer(), it)
    }

    private fun claimSentences(text: String): List<String> {
        return stripMarkupForClaims(text)
            .lineSequence()
            .flatMap { line ->
                line.trim()
                    .removePrefix("-")
                    .removePrefix("*")
                    .trim()
                    .split(Regex("(?<=[.!?])\\s+"))
            }
            .map { it.trim() }
            .filter { sentence ->
                sentence.length >= MIN_CLAIM_LENGTH &&
                    !sentence.startsWith("|") &&
                    !sentence.startsWith("```") &&
                    !sentence.contains("No traceable supporting source was extracted")
            }
            .toList()
    }

    private fun inferClaimType(claim: String): String {
        val lower = claim.lowercase()
        return when {
            listOf("uncertain", "unknown", "unclear", "contradict", "conflict", "not enough").any { it in lower } ->
                "uncertainty"
            listOf("recommend", "should", "best", "advise").any { it in lower } -> "recommendation"
            listOf("better than", "worse than", "compared", "versus", "vs.").any { it in lower } -> "comparison"
            listOf("result", "found", "shows", "demonstrates", "indicates", "winner").any { it in lower } -> "result"
            else -> "background"
        }
    }

    private suspend fun collectSearchTraceEvents(
        runId: String,
        startingField: StartingField,
        completionRecord: RunCompletionRecord?
    ): List<JsonObject> {
        return resolveArchiveGroupIds(startingField, completionRecord)
            .flatMap { groupId ->
                runCatching {
                    dbFactory.get(runId, groupId).eventLogDao().queryFiltered(
                        runId = runId,
                        groupId = groupId,
                        agentId = null,
                        eventType = EventTypes.TOOL_EXEC,
                        minGeneration = null,
                        maxGeneration = null,
                        limit = MAX_SEARCH_TRACE_EVENTS_PER_GROUP,
                        offset = 0
                    )
                }.getOrElse { emptyList() }
            }
            .filter { event -> event.searchTraceToolName() != null }
            .sortedBy { it.timestampMs }
            .take(MAX_SEARCH_TRACE_EVENTS)
            .map { it.toSearchTraceJson() }
    }

    private suspend fun collectCostSignals(
        runId: String,
        startingField: StartingField,
        completionRecord: RunCompletionRecord?
    ): ResearchCostSignals {
        val events = resolveArchiveGroupIds(startingField, completionRecord)
            .flatMap { groupId ->
                runCatching {
                    val db = dbFactory.get(runId, groupId)
                    db.eventLogDao().queryFiltered(runId, groupId, null, EventTypes.BUDGET_DEDUCT, null, null, 10_000, 0) +
                        db.eventLogDao().queryFiltered(runId, groupId, null, EventTypes.LLM_CALL, null, null, 10_000, 0)
                }.getOrElse { emptyList() }
            }
        val budgetCost = events.sumOf { event -> extractFirstNumeric(event.payloadJson, COST_FIELD_CANDIDATES) ?: 0.0 }
        val tokenCount = events.sumOf { event -> extractFirstNumeric(event.payloadJson, TOKEN_FIELD_CANDIDATES) ?: 0.0 }
        return ResearchCostSignals(
            budgetEventCount = events.count { it.eventType == EventTypes.BUDGET_DEDUCT },
            llmCallEventCount = events.count { it.eventType == EventTypes.LLM_CALL },
            observedBudgetCost = budgetCost.takeIf { it > 0.0 },
            observedTokenCount = tokenCount.takeIf { it > 0.0 }
        )
    }

    private fun resolveArchiveGroupIds(
        startingField: StartingField,
        completionRecord: RunCompletionRecord?
    ): List<String> {
        val healthIds = completionRecord?.substrateHealthByGroup.orEmpty().map { it.groupId }
        val generatedIds = (0 until startingField.groupCount).map { "group-$it" }
        return (healthIds + generatedIds)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun EventLogEntryEntity.toSearchTraceJson(): JsonObject = buildJsonObject {
        put("timestamp_ms", JsonPrimitive(timestampMs))
        put("run_id", runId)
        put("group_id", groupId ?: "")
        put("agent_id", agentId ?: "")
        put("generation", JsonPrimitive(generation))
        put("event_type", eventType)
        searchTraceToolName()?.let { put("tool_name", it) }
        put("payload", parseJsonObjectOrRaw(payloadJson))
    }

    private fun EventLogEntryEntity.searchTraceToolName(): String? {
        if (eventType != EventTypes.TOOL_EXEC) return null
        val payload = runCatching { json.parseToJsonElement(payloadJson) }.getOrNull() ?: return null
        return extractStringField(payload, SEARCH_TRACE_TOOL_NAME_FIELDS)
            ?.lowercase()
            ?.takeIf { it in SEARCH_TRACE_TOOL_NAMES }
    }

    private fun buildFinalAnswerMarkdown(
        startingField: StartingField,
        completionRecord: RunCompletionRecord?,
        generatedOutputs: List<GeneratedOutputFile>,
        claims: List<ResearchClaimRecord>
    ): String = buildString {
        appendLine("# Final Answer")
        appendLine()
        appendLine("Run ID: `${startingField.runId}`")
        appendLine()
        appendLine("Objective: ${startingField.objective}")
        appendLine()
        val summary = completionRecord?.finalAnswerSummary.orEmpty().trim()
        if (summary.isNotBlank()) {
            appendLine("## Summary")
            appendLine()
            appendLine(summary)
            appendLine()
        }
        if (generatedOutputs.isNotEmpty()) {
            appendLine("## Authored Outputs")
            appendLine()
            generatedOutputs.forEach { output ->
                appendLine("- `${output.relativePath}` (${output.mediaType})")
            }
            appendLine()
        }
        appendLine("## Claim Ledger")
        appendLine()
        if (claims.isEmpty()) {
            appendLine("No major claims were extracted from the archived final answer or generated outputs.")
        } else {
            claims.forEachIndexed { index, claim ->
                appendLine("${index + 1}. ${claim.claim}")
            }
        }
    }

    private fun buildEvidenceTableCsv(
        startingField: StartingField,
        generatedOutputs: List<GeneratedOutputFile>,
        sourceIds: List<String>
    ): String = buildString {
        appendLine("source_id,source_type,title_or_path,mime_type,used_in_final_answer,notes")
        startingField.inputArtifacts.forEach { artifact ->
            appendCsvRow(
                artifact.relativePath.ifBlank { artifact.sourceName },
                artifact.kind.name,
                artifact.sourceName.ifBlank { artifact.relativePath },
                artifact.mimeType,
                "false",
                artifact.textPreview.orEmpty().take(240)
            )
        }
        sourceIds
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .forEach { url ->
                appendCsvRow(url, "URL", url, "", "true", "Extracted from generated output or completion text.")
            }
        generatedOutputs.forEach { output ->
            appendCsvRow(
                output.relativePath,
                "GENERATED_OUTPUT",
                output.relativePath,
                output.mediaType,
                "true",
                "Operator-facing generated output."
            )
        }
    }

    private fun StringBuilder.appendCsvRow(vararg values: String) {
        appendLine(values.joinToString(",") { value ->
            "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""
        })
    }

    private fun buildBenchmarkResultsCsv(
        startingField: StartingField,
        completionRecord: RunCompletionRecord?,
        entries: List<MetaArchiveEntryEntity>,
        costReportJson: String
    ): String = buildString {
        appendLine("run_id,objective,winner_id,group_id,final_score,quality_score,status,notes")
        val costReport = runCatching { json.parseToJsonElement(costReportJson).jsonObject }.getOrNull()
        val qualityScore = costReport?.get("quality_score")?.jsonPrimitive?.contentOrNull.orEmpty()
        val scoredEntries = entries
            .filter { it.finalScore != null || it.groupId != null }
            .sortedWith(compareBy<MetaArchiveEntryEntity> { it.groupId.orEmpty() }.thenBy { it.entryType })
        if (scoredEntries.isEmpty()) {
            appendCsvRow(
                startingField.runId,
                startingField.objective,
                completionRecord?.winnerId.orEmpty(),
                completionRecord?.winnerId.orEmpty(),
                "",
                qualityScore,
                if (completionRecord == null) "NO_COMPLETION_RECORD" else "COMPLETED",
                "No per-group benchmark score rows were archived; deterministic placeholder row emitted."
            )
            return@buildString
        }
        scoredEntries.forEach { entry ->
            appendCsvRow(
                startingField.runId,
                startingField.objective,
                completionRecord?.winnerId.orEmpty(),
                entry.groupId.orEmpty(),
                entry.finalScore?.toString().orEmpty(),
                qualityScore,
                entry.entryType,
                entry.label
            )
        }
    }

    private fun buildRunReplayHtml(
        startingField: StartingField,
        completionRecord: RunCompletionRecord?,
        generatedOutputs: List<GeneratedOutputFile>,
        entries: List<MetaArchiveEntryEntity>
    ): String {
        fun String.escapeHtml(): String = replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        val timelineRows = entries
            .sortedWith(compareBy<MetaArchiveEntryEntity> { it.generation }.thenBy { it.archivedAtMs })
            .joinToString(separator = "\n") { entry ->
                "<tr><td>${entry.generation}</td><td>${Instant.ofEpochMilli(entry.archivedAtMs)}</td>" +
                    "<td>${entry.entryType.escapeHtml()}</td><td>${entry.groupId.orEmpty().escapeHtml()}</td>" +
                    "<td>${entry.label.escapeHtml()}</td></tr>"
            }
        val outputItems = generatedOutputs.joinToString(separator = "\n") { output ->
            "<li><code>${output.relativePath.escapeHtml()}</code> (${output.mediaType.escapeHtml()})</li>"
        }.ifBlank { "<li>No authored output files were archived.</li>" }
        val summary = completionRecord?.finalAnswerSummary.orEmpty().ifBlank {
            "No completion summary was archived."
        }

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>EvoMolior Run Replay ${startingField.runId.escapeHtml()}</title>
              <style>
                body { font-family: system-ui, sans-serif; margin: 2rem; line-height: 1.5; }
                table { border-collapse: collapse; width: 100%; }
                th, td { border: 1px solid #ddd; padding: 0.35rem 0.5rem; vertical-align: top; }
                th { background: #f5f5f5; text-align: left; }
                code { background: #f5f5f5; padding: 0.1rem 0.25rem; }
              </style>
            </head>
            <body>
              <main>
                <h1>EvoMolior Run Replay</h1>
                <p><strong>Run ID:</strong> <code>${startingField.runId.escapeHtml()}</code></p>
                <p><strong>Objective:</strong> ${startingField.objective.escapeHtml()}</p>
                <p><strong>Winner:</strong> <code>${completionRecord?.winnerId.orEmpty().escapeHtml()}</code></p>
                <h2>Final Summary</h2>
                <p>${summary.escapeHtml()}</p>
                <h2>Authored Outputs</h2>
                <ul>$outputItems</ul>
                <h2>Archived Timeline</h2>
                <table>
                  <thead><tr><th>Generation</th><th>Archived At</th><th>Type</th><th>Group</th><th>Label</th></tr></thead>
                  <tbody>$timelineRows</tbody>
                </table>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildCitationMapJson(
        runId: String,
        claims: List<ResearchClaimRecord>,
        sourceIds: List<String>
    ): String = buildJsonObject {
        put("run_id", runId)
        put("source_count", sourceIds.size)
        put("sources", buildJsonArray { sourceIds.forEach { add(JsonPrimitive(it)) } })
        put("claims", buildJsonArray {
            claims.forEachIndexed { index, claim ->
                add(buildJsonObject {
                    put("claim_index", index)
                    put("claim", claim.claim)
                    put("supporting_sources", buildJsonArray {
                        claim.supporting_sources.forEach { add(JsonPrimitive(it)) }
                    })
                    put("contradicting_sources", buildJsonArray {
                        claim.contradicting_sources.forEach { add(JsonPrimitive(it)) }
                    })
                    put("used_in_final_answer", claim.used_in_final_answer)
                })
            }
        })
    }.toString()

    private fun buildResearchUnknowns(
        completionRecord: RunCompletionRecord?,
        claims: List<ResearchClaimRecord>,
        searchTraceEvents: List<JsonObject>,
        costSignals: ResearchCostSignals,
        entries: List<MetaArchiveEntryEntity>
    ): String = buildString {
        appendLine("# Unknowns and Contradictions")
        appendLine()
        appendLine("## Unknowns")
        appendLine()
        if (completionRecord == null) {
            appendLine("- No `RunCompletionRecord` was available when this evidence pack was synthesized.")
        }
        if (claims.isEmpty()) {
            appendLine("- No major claims could be extracted from the final answer, generated outputs, or group summaries.")
        }
        if (claims.any { it.supporting_sources.isEmpty() }) {
            appendLine("- Some claims have no traceable supporting source in the archive; see `claim_ledger.jsonl` confidence values.")
        }
        if (searchTraceEvents.isEmpty()) {
            appendLine("- No search/read/fetch tool execution events were available for `search_trace.jsonl`.")
        }
        if (costSignals.observedBudgetCost == null) {
            appendLine("- No numeric budget or USD cost field was found in archived budget/LLM events.")
        }
        if (entries.none { it.entryType == ENTRY_WINNING_KB }) {
            appendLine("- No winning KB snapshot was archived for claim/evidence extraction.")
        }
        appendLine()
        appendLine("## Contradictions")
        appendLine()
        val contradictedClaims = claims.filter { it.contradicting_sources.isNotEmpty() }
        if (contradictedClaims.isEmpty()) {
            appendLine("- No contradicting sources were extracted automatically from the archive.")
        } else {
            contradictedClaims.forEach { claim ->
                appendLine("- ${claim.claim}: ${claim.contradicting_sources.joinToString()}")
            }
        }
    }

    private fun buildCostReportJson(
        runId: String,
        startingField: StartingField,
        completionRecord: RunCompletionRecord?,
        entries: List<MetaArchiveEntryEntity>,
        claims: List<ResearchClaimRecord>,
        searchTraceEvents: List<JsonObject>,
        costSignals: ResearchCostSignals
    ): String {
        val startedAtMs = entries
            .filter { it.entryType == ENTRY_STARTING_FIELD }
            .minOfOrNull { it.archivedAtMs }
            ?: entries.minOfOrNull { it.archivedAtMs }
        val completedAtMs = completionRecord?.completedAtMs ?: entries.maxOfOrNull { it.archivedAtMs }
        val wallClockMinutes = startedAtMs
            ?.let { started -> completedAtMs?.let { completed -> (completed - started).coerceAtLeast(0L) / 60_000.0 } }
        val qualityScore = resolveQualityScore(entries, completionRecord)
        val costNormalizedScore = qualityScore?.let { score ->
            costSignals.observedBudgetCost?.takeIf { it > 0.0 }?.let { cost -> score / cost }
        }
        val latencyNormalizedScore = qualityScore?.let { score ->
            wallClockMinutes?.takeIf { it > 0.0 }?.let { minutes -> score / minutes }
        }
        val supportedClaims = claims.count { it.supporting_sources.isNotEmpty() }
        val totalClaims = claims.size
        val citationPrecisionProxy = totalClaims.takeIf { it > 0 }?.let { supportedClaims.toDouble() / it }
        val contradictionCount = claims.count { it.contradicting_sources.isNotEmpty() }

        return buildJsonObject {
            put("run_id", runId)
            if (startedAtMs != null) put("started_at_ms", JsonPrimitive(startedAtMs)) else put("started_at_ms", JsonNull)
            if (completedAtMs != null) put("completed_at_ms", JsonPrimitive(completedAtMs)) else put("completed_at_ms", JsonNull)
            if (wallClockMinutes != null) put("wall_clock_minutes", JsonPrimitive(wallClockMinutes)) else put("wall_clock_minutes", JsonNull)
            put("group_count", startingField.groupCount)
            put("round_count", startingField.generationCeiling)
            put("budget_event_count", costSignals.budgetEventCount)
            put("llm_call_event_count", costSignals.llmCallEventCount)
            put("search_trace_event_count", searchTraceEvents.size)
            put("claim_count", totalClaims)
            put("source_supported_claim_count", supportedClaims)
            put("observed_budget_cost", costSignals.observedBudgetCost?.let(::JsonPrimitive) ?: JsonNull)
            put("observed_token_count", costSignals.observedTokenCount?.let(::JsonPrimitive) ?: JsonNull)
            put("usd_cost", JsonNull)
            put("quality_score", qualityScore?.let(::JsonPrimitive) ?: JsonNull)
            put("cost_normalized_score", costNormalizedScore?.let(::JsonPrimitive) ?: JsonNull)
            put("latency_normalized_score", latencyNormalizedScore?.let(::JsonPrimitive) ?: JsonNull)
            put("primary_metrics", buildJsonObject {
                put("answer_correctness", metricValueJson(qualityScore, "Existing blind/judge final score proxy from ROUND_SUMMARY.finalScore."))
                put("evidence_recall", metricValueJson(supportedClaims.toDouble(), "Archive proxy: count of extracted claims with at least one traceable source."))
                put("citation_precision", metricValueJson(citationPrecisionProxy, "Archive proxy: source-supported extracted claims divided by total extracted claims."))
                put("contradiction_handling", metricValueJson(contradictionCount.toDouble(), "Archive proxy: extracted claims with contradicting_sources."))
                put("novel_useful_synthesis", metricValueJson(qualityScore, "Existing judge score proxy until expert pairwise preference is available."))
                put("cost_normalized_score", metricValueJson(costNormalizedScore, "quality_score / observed_budget_cost."))
                put("latency_normalized_score", metricValueJson(latencyNormalizedScore, "quality_score / wall_clock_minutes."))
                put("substrate_compounding", buildJsonObject {
                    val health = completionRecord?.substrateHealthByGroup.orEmpty()
                    put("accepted_kg_facts", health.sumOf { it.kgCount })
                    put("active_tools", health.sumOf { it.activeToolCount })
                    put("tool_execution_count", health.sumOf { it.toolExecutionCount })
                    put("promoted_submissions", health.sumOf { it.promotedSubmissionCount })
                    put("later_generation_claim_reuse", JsonNull)
                    put("dead_end_avoidance", JsonNull)
                    put("citation_chains", supportedClaims)
                })
            })
            put("win_thresholds", buildJsonObject {
                put("frontier_plus_5pp_same_or_lower_cost", "quality_score >= frontier_single_agent_score + 5pp AND cost <= frontier_cost")
                put("near_frontier_low_cost", "quality_score >= 0.95 * frontier_single_agent_score AND cost <= 0.35 * frontier_cost")
                put("long_form", "+10% gold rubric items, +20% citation-verified claims, no worse hallucination rate")
            })
        }.toString()
    }

    private fun metricValueJson(value: Double?, scoringMethod: String): JsonObject = buildJsonObject {
        if (value != null) put("value", JsonPrimitive(value)) else put("value", JsonNull)
        put("scoring_method", scoringMethod)
        put("status", if (value == null) "not_available_from_archive" else "computed_from_archive")
    }

    private fun resolveQualityScore(
        entries: List<MetaArchiveEntryEntity>,
        completionRecord: RunCompletionRecord?
    ): Double? {
        val scoredSummaries = entries
            .filter { it.entryType == ENTRY_ROUND_SUMMARY && it.finalScore != null }
        val winnerId = completionRecord?.winnerId
        return scoredSummaries
            .filter { it.groupId == winnerId }
            .maxByOrNull { it.generation }
            ?.finalScore
            ?: scoredSummaries.maxByOrNull { it.finalScore ?: Double.NEGATIVE_INFINITY }?.finalScore
    }

    private fun parseJsonObjectOrRaw(payloadJson: String): JsonElement {
        return runCatching { json.parseToJsonElement(payloadJson) }
            .getOrElse { JsonPrimitive(payloadJson) }
    }

    private fun extractFirstNumeric(payloadJson: String, fieldCandidates: Set<String>): Double? {
        val element = runCatching { json.parseToJsonElement(payloadJson) }.getOrNull() ?: return null
        return extractFirstNumeric(element, fieldCandidates)
    }

    private fun extractFirstNumeric(element: JsonElement, fieldCandidates: Set<String>): Double? {
        return when (element) {
            is JsonObject -> {
                element.entries.firstNotNullOfOrNull { (key, value) ->
                    if (key in fieldCandidates) (value as? JsonPrimitive)?.doubleOrNull else null
                } ?: element.values.firstNotNullOfOrNull { value -> extractFirstNumeric(value, fieldCandidates) }
            }
            is JsonArray -> element.firstNotNullOfOrNull { value -> extractFirstNumeric(value, fieldCandidates) }
            else -> null
        }
    }

    private fun extractStringField(element: JsonElement, fieldCandidates: Set<String>): String? {
        return when (element) {
            is JsonObject -> {
                element.entries.firstNotNullOfOrNull { (key, value) ->
                    if (key in fieldCandidates) (value as? JsonPrimitive)?.contentOrNull else null
                } ?: element.values.firstNotNullOfOrNull { value -> extractStringField(value, fieldCandidates) }
            }
            is JsonArray -> element.firstNotNullOfOrNull { value -> extractStringField(value, fieldCandidates) }
            is JsonPrimitive -> null
        }
    }

    private fun stripMarkupForClaims(content: String): String =
        content
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(HTML_ANCHOR_PATTERN) { match ->
                val href = match.groupValues[1].trim()
                val label = match.groupValues[2]
                    .replace(Regex("(?is)<[^>]+>"), " ")
                    .trim()
                "$label ($href)"
            }
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun selectCanonicalStartingFieldEntry(
        entries: List<MetaArchiveEntryEntity>
    ): MetaArchiveEntryEntity? {
        val startingFieldEntries = entries.filter { it.entryType == ENTRY_STARTING_FIELD }
        val currentEntries = startingFieldEntries.filter { entry ->
            StartingFieldWireContract.validationError(
                entry.contentJson,
                "archived StartingField for run=${entry.runId}"
            ) == null
        }
        if (currentEntries.isEmpty() && startingFieldEntries.isNotEmpty()) {
            error(
                checkNotNull(
                    StartingFieldWireContract.validationError(
                        startingFieldEntries.first().contentJson,
                        "archived StartingField for run=${startingFieldEntries.first().runId}"
                    )
                )
            )
        }
        return currentEntries
            .asSequence()
            .mapNotNull { entry ->
                runCatching {
                    val field = json.decodeFromString(StartingField.serializer(), entry.contentJson)
                    CanonicalStartingFieldCandidate(
                        entry = entry,
                        field = field,
                        richness = startingFieldRichness(field)
                    )
                }.getOrNull()
            }
            .maxWithOrNull(
                compareBy<CanonicalStartingFieldCandidate> { it.field.seal != null }
                    .thenBy { it.richness }
                    .thenBy { it.entry.contentJson.length }
                    .thenByDescending { it.entry.archivedAtMs }
            )
            ?.entry
    }

    private data class CanonicalStartingFieldCandidate(
        val entry: MetaArchiveEntryEntity,
        val field: StartingField,
        val richness: Int
    )

    private fun startingFieldRichness(field: StartingField): Int =
        listOf(
            field.stageContents.size * 1_000,
            field.primordialKb.size * 100,
            field.primordialKgFacts.size * 50,
            field.primordialTools.size * 50,
            field.inputArtifacts.size * 25,
            field.resourceNodes.size * 25,
            field.rolePrompts.size * 10,
            field.modelAssignment.size * 10,
            if (field.nodeGraph.isNotBlank() && field.nodeGraph != "{}") 10 else 0,
            if (field.deliverableSkeletonTreeJson.isNotBlank() &&
                field.deliverableSkeletonTreeJson != "{}"
            ) 10 else 0,
            if (field.sealedTestSuiteJson.isNotBlank()) 10 else 0
        ).sum()

    private fun normalizeSourceId(value: String): String =
        value.trim().trimEnd('.', ',', ';', ':')

    private fun resolveRequestedOutputSpec(runId: String): FinalOutputSpec? {
        val stateFile = File(resolveRunRoot(runId), "control/${PersistedRunStateStore.STATE_FILE_NAME}")
        return PersistedRunStateStore.read(stateFile)?.finalOutputSpec
    }

    private suspend fun resolveGeneratedOutputFiles(
        runId: String,
        entries: List<MetaArchiveEntryEntity>,
        completionRecord: RunCompletionRecord?,
        requestedOutputSpec: FinalOutputSpec?
    ): List<GeneratedOutputFile> {
        val sourceCatalog = VerifiedSourceCatalog.resolve(resolveRunRoot(runId))
        completionRecord?.generatedOutputFiles
            ?.takeIf { it.isNotEmpty() }
            ?.let { outputs ->
                var firstFailure: Throwable? = null
                val validatedOutputs = mutableListOf<GeneratedOutputFile>()
                outputs.forEach { output ->
                    try {
                        val validated = validateGeneratedOutputFile(
                            output,
                            normalizeHtmlStructure =
                                !hasActiveCompiledCouncilManifest(entries)
                        )
                        requireRequestedOutputContract(validated, requestedOutputSpec)
                        VerifiedSourceCatalog.validate(validated, sourceCatalog)
                        requireActiveCompiledCouncilOutput(
                            runId = runId,
                            entries = entries,
                            completionRecord = completionRecord,
                            output = validated
                        )
                        validatedOutputs += validated
                    } catch (err: Throwable) {
                        if (firstFailure == null) firstFailure = err
                        Napier.w(
                            "RunArchiveService: skipping invalid generated output ${output.relativePath} for $runId: ${err.message}"
                        )
                    }
                }
                if (validatedOutputs.isEmpty() && firstFailure != null) {
                    throw IllegalStateException(
                        "All generated outputs for $runId were invalid: ${firstFailure?.message}"
                    )
                }
                return validatedOutputs
            }

        val recoveredSummaryOutput = requestedOutputSpec?.let { outputSpec ->
            extractGeneratedOutputFromRoundSummaries(runId, entries, outputSpec)
        }
        if (recoveredSummaryOutput != null) {
            val validated = validateGeneratedOutputFile(
                recoveredSummaryOutput,
                normalizeHtmlStructure = !hasActiveCompiledCouncilManifest(entries)
            )
            requireRequestedOutputContract(validated, requestedOutputSpec)
            VerifiedSourceCatalog.validate(validated, sourceCatalog)
            requireActiveCompiledCouncilOutput(runId, entries, completionRecord, validated)
            return listOf(validated)
        }

        val fallbackHtml = completionRecord?.let { record ->
            record.finalDeliverableHtml
                ?.takeIf { it.isNotBlank() }
                ?: extractEmbeddedFinalDeliverableHtml(record)
        }
        if (fallbackHtml != null &&
            (requestedOutputSpec == null || requestedOutputSpec.mediaType.startsWith("text/html", ignoreCase = true))
        ) {
            val validated = validateGeneratedOutputFile(
                GeneratedOutputFile(
                    relativePath = requestedOutputSpec?.relativePath ?: "final-article.html",
                    mediaType = requestedOutputSpec?.mediaType ?: "text/html",
                    content = normalizeStandaloneHtml(fallbackHtml)
                ),
                normalizeHtmlStructure = !hasActiveCompiledCouncilManifest(entries)
            )
            requireRequestedOutputContract(validated, requestedOutputSpec)
            VerifiedSourceCatalog.validate(validated, sourceCatalog)
            requireActiveCompiledCouncilOutput(runId, entries, completionRecord, validated)
            return listOf(validated)
        }

        return emptyList()
    }

    private fun requireRequestedOutputContract(
        output: GeneratedOutputFile,
        requestedOutputSpec: FinalOutputSpec?
    ) {
        val spec = requestedOutputSpec ?: return
        require(output.relativePath == spec.relativePath) {
            "Generated output path ${output.relativePath} does not match ${spec.relativePath}"
        }
        require(output.mediaType.equals(spec.mediaType, ignoreCase = true)) {
            "Generated output media type ${output.mediaType} does not match ${spec.mediaType}"
        }
        val failures = FinalOutputContractValidator.validate(output, spec)
        require(failures.isEmpty()) {
            "Generated output violates its requested contract: ${failures.joinToString("; ")}"
        }
    }

    private suspend fun requireActiveCompiledCouncilOutput(
        runId: String,
        entries: List<MetaArchiveEntryEntity>,
        completionRecord: RunCompletionRecord?,
        output: GeneratedOutputFile
    ) {
        val activeEntries = entries.filter {
            it.entryType == ENTRY_ACTIVE_COMPILED_COUNCIL_MANIFEST
        }
        if (activeEntries.isEmpty()) return
        require(activeEntries.size == 1) {
            "Archive recovery requires exactly one ACTIVE compiled Council manifest"
        }
        val active = ActiveCompiledCouncilManifestV3Codec.decodeVerified(
            activeEntries.single().contentJson
        )
        require(active.runId == runId) {
            "ACTIVE compiled Council manifest belongs to another run"
        }
        val candidateEntry = entries.singleOrNull {
            it.id == active.candidateArchiveEntryId &&
                it.entryType == ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST
        } ?: error("ACTIVE compiled Council candidate is missing during archive recovery")
        val candidate = CompiledCouncilCandidateManifestV3Codec.decodeVerified(
            candidateEntry.contentJson
        )
        require(candidate.runId == runId) {
            "Compiled Council candidate belongs to another run"
        }
        require(
            CouncilContractHashing.constantTimeEquals(
                active.candidateManifestHash,
                candidate.manifestHash
            )
        ) {
            "ACTIVE compiled Council manifest does not bind the exact candidate"
        }
        val finalOutputSpec = candidate.validationContext.finalOutputSpec
        require(output.relativePath == finalOutputSpec.relativePath) {
            "Archive output path does not match active sealed-tests-v3 authority"
        }
        require(
            output.mediaType.substringBefore(';').trim().equals(
                finalOutputSpec.mediaType.substringBefore(';').trim(),
                ignoreCase = true
            )
        ) {
            "Archive output media type does not match active sealed-tests-v3 authority"
        }
        val groupId = output.sourceGroupId ?: completionRecord?.winnerId
            ?: error("Archive v3 re-evaluation requires the exact winning group")
        val roundIndex = ((completionRecord?.completedRoundCount ?: 1) - 1).coerceAtLeast(0)
        val promotionExecutions = ToolInfrastructureRepository(
            dbFactory.get(runId, groupId)
        ).executionsForGroupThroughRound(runId, groupId, roundIndex)
        val scoringDimensions = candidate.workerStartingField.scoreNormalization
            .toSortedMap()
            .map { (dimensionId, maximum) ->
                ScoringDimension(
                    id = dimensionId,
                    name = dimensionId,
                    description = "Archive-time sealed-tests-v3 re-evaluation.",
                    weight = 1.0,
                    maxScore = maximum
                )
            }
            .ifEmpty {
                listOf(
                    ScoringDimension(
                        id = "sealed-tests-v3",
                        name = "Sealed tests v3",
                        description = "Archive-time sealed-tests-v3 re-evaluation.",
                        weight = 1.0,
                        maxScore = 1.0
                    )
                )
            }
        val assessment = SealedTestSuiteEvaluator().assessGroup(
            suiteJson = candidate.graderStartingField.sealedTestSuiteJson,
            validationContext = candidate.validationContext,
            input = SealedTestEvaluationInputV3(
                runId = runId,
                groupId = groupId,
                roundIndex = roundIndex,
                finalOutput = SealedTestOutputArtifactV3(
                    relativePath = output.relativePath,
                    mediaType = output.mediaType
                        .substringBefore(';')
                        .trim()
                        .lowercase(java.util.Locale.ROOT),
                    content = output.content,
                    contentHash = output.contentHash
                ),
                promotionExecutions = promotionExecutions
            ),
            scoringDimensions = scoringDimensions
        )
        require(
            assessment.automatedTestsPassed + assessment.automatedTestsFailed > 0 &&
                !assessment.mandatoryFailure
        ) {
            "Archive output failed active sealed-tests-v3 authority: " +
                assessment.failedTestIds.joinToString()
        }
    }

    private fun extractGeneratedOutputFromRoundSummaries(
        runId: String,
        entries: List<MetaArchiveEntryEntity>,
        outputSpec: FinalOutputSpec
    ): GeneratedOutputFile? {
        val roundSummaryEntries = entries
            .asSequence()
            .filter { it.entryType == ENTRY_ROUND_SUMMARY && it.runId == runId }
            .sortedWith(
                compareByDescending<MetaArchiveEntryEntity> { it.generation }
                    .thenByDescending { it.archivedAtMs }
            )
            .toList()

        roundSummaryEntries.forEach { entry ->
            extractSubmissionSummaries(entry).forEach { summary ->
                extractGeneratedOutputFromSummary(summary, outputSpec)?.let { return it }
            }
        }
        return null
    }

    private fun extractSubmissionSummaries(entry: MetaArchiveEntryEntity): List<String> {
        val payload = runCatching { json.parseToJsonElement(entry.contentJson).jsonObject }.getOrNull() ?: return emptyList()
        val winnerId = payload["roundWinnerId"]?.jsonPrimitive?.contentOrNull
        val summaries = payload["groupSummaries"]?.jsonArray.orEmpty()
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val groupId = obj["groupId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val submissionSummary = obj["submissionSummary"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Triple(groupId == winnerId, groupId, submissionSummary)
            }

        return summaries
            .sortedWith(compareByDescending<Triple<Boolean, String, String>> { it.first }.thenByDescending { it.second })
            .map { it.third }
    }

    private fun extractGeneratedOutputFromSummary(
        summary: String,
        outputSpec: FinalOutputSpec
    ): GeneratedOutputFile? {
        directBodyGeneratedOutputFile(summary, outputSpec)?.let { return validateGeneratedOutputFile(it) }

        if (outputSpec.mediaType.equals("application/json", ignoreCase = true) ||
            outputSpec.mediaType.endsWith("+json", ignoreCase = true)
        ) {
            extractFencedCodeBlock(summary, preferredLanguage = "json")
                ?.let { directBodyGeneratedOutputFile(it, outputSpec) }
                ?.let { return validateGeneratedOutputFile(it) }
        }

        extractFencedCodeBlock(summary)
            ?.let { directBodyGeneratedOutputFile(it, outputSpec) }
            ?.let { return validateGeneratedOutputFile(it) }

        return null
    }

    private fun directBodyGeneratedOutputFile(
        candidate: String,
        outputSpec: FinalOutputSpec
    ): GeneratedOutputFile? {
        val body = candidate.trim()
        if (body.isBlank()) return null

        val acceptsBody = when {
            outputSpec.mediaType.startsWith("text/html", ignoreCase = true) -> false
            outputSpec.mediaType.equals("application/json", ignoreCase = true) ||
                outputSpec.mediaType.endsWith("+json", ignoreCase = true) ->
                runCatching { json.parseToJsonElement(body) }.isSuccess
            outputSpec.mediaType.startsWith("text/", ignoreCase = true) -> true
            else -> false
        }
        if (!acceptsBody) return null

        return GeneratedOutputFile(
            relativePath = outputSpec.relativePath,
            mediaType = outputSpec.mediaType,
            content = body
        )
    }

    private fun extractFencedCodeBlock(
        text: String,
        preferredLanguage: String? = null
    ): String? {
        data class FenceBlock(val language: String?, val body: String)

        val lines = text.lines()
        val blocks = mutableListOf<FenceBlock>()
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (!trimmed.startsWith("```")) {
                index += 1
                continue
            }
            val language = trimmed.removePrefix("```").trim().takeIf { it.isNotBlank() }
            val bodyLines = mutableListOf<String>()
            index += 1
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                bodyLines += lines[index]
                index += 1
            }
            blocks += FenceBlock(language = language, body = bodyLines.joinToString("\n").trim())
            index += 1
        }

        preferredLanguage?.let { language ->
            blocks.firstOrNull { it.language.equals(language, ignoreCase = true) && it.body.isNotBlank() }
                ?.let { return it.body }
        }
        return blocks.firstOrNull { it.body.isNotBlank() }?.body
    }

    private fun extractEmbeddedFinalDeliverableHtml(
        completionRecord: RunCompletionRecord
    ): String? {
        val orderedSummaries = buildList {
            completionRecord.winnerId
                ?.let { completionRecord.submissionSummariesByGroup[it] }
                ?.let(::add)
            completionRecord.submissionSummariesByGroup
                .filterKeys { it != completionRecord.winnerId }
                .values
                .filterNotNull()
                .forEach(::add)
        }

        return orderedSummaries
            .asSequence()
            .mapNotNull(::extractHtmlBlock)
            .firstOrNull()
    }

    private fun extractHtmlBlock(summary: String): String? {
        val markerIndex = summary.indexOf("FINAL DELIVERABLE HTML", ignoreCase = true)
        if (markerIndex < 0) return null

        val htmlStart = summary.indexOf("<!doctype html", startIndex = markerIndex, ignoreCase = true)
            .takeIf { it >= 0 }
            ?: summary.indexOf("<html", startIndex = markerIndex, ignoreCase = true).takeIf { it >= 0 }
            ?: return null

        val htmlEnd = summary.indexOf("</html>", startIndex = htmlStart, ignoreCase = true)
            .takeIf { it >= 0 }
            ?.plus("</html>".length)

        return summary.substring(htmlStart, htmlEnd ?: summary.length).trim()
    }

    private fun normalizeStandaloneHtml(content: String): String {
        val trimmed = content.trim()
        if (trimmed.startsWith("<!doctype html>", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        ) {
            return trimmed
        }

        return buildString {
            append("<!doctype html>\n")
            append("<html lang=\"en\">\n")
            append("<head>\n")
            append("  <meta charset=\"utf-8\">\n")
            append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("</head>\n")
            append("<body>\n")
            append(trimmed)
            append('\n')
            append("</body>\n")
            append("</html>\n")
        }
    }

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private fun splitParagraphs(value: String): List<String> =
        value
            .lineSequence()
            .map { it.trim() }
            .joinToString("\n")
            .split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun StringBuilder.appendMetric(label: String, value: String) {
        append("          <dt>").append(escapeHtml(label)).append("</dt>\n")
        append("          <dd>").append(escapeHtml(value)).append("</dd>\n")
    }

    private fun StringBuilder.appendTag(value: String) {
        append("          <span class=\"tag\">").append(escapeHtml(value)).append("</span>\n")
    }
}

private data class ResearchDeliverablePack(
    val files: List<GeneratedOutputFile>
)

@Serializable
private data class ResearchClaimRecord(
    val claim: String,
    val claim_type: String,
    val supporting_sources: List<String>,
    val quoted_or_paraphrased_evidence: String,
    val confidence: Double,
    val contradicting_sources: List<String>,
    val used_in_final_answer: Boolean
)

private data class ResearchCostSignals(
    val budgetEventCount: Int,
    val llmCallEventCount: Int,
    val observedBudgetCost: Double?,
    val observedTokenCount: Double?
)

private val URL_PATTERN = Regex("""https?://[^\s\])}"'>]+""")
private val HTML_ANCHOR_PATTERN = Regex(
    """(?is)<a\b[^>]*\bhref\s*=\s*["'](https?://[^"']+)["'][^>]*>(.*?)</a>"""
)
private const val MIN_CLAIM_LENGTH = 40
private const val MAX_CLAIM_LENGTH = 1_000
private const val MAX_RESEARCH_CLAIMS = 200
private const val MAX_RESEARCH_SOURCE_IDS = 500
private const val MAX_SEARCH_TRACE_EVENTS = 2_000
private const val MAX_SEARCH_TRACE_EVENTS_PER_GROUP = 2_000
private val SEARCH_TRACE_TOOL_NAME_FIELDS = setOf(
    "tool",
    "toolName",
    "name",
    "action",
    "tool_name"
)
private val SEARCH_TRACE_TOOL_NAMES = setOf(
    "native_web_search",
    "search_kb",
    "read_kb",
    "read_kg",
    "read_run_artifact",
    "request_source_access",
    "web_fetch",
    "fetch_source",
    "read_source"
)
private val COST_FIELD_CANDIDATES = setOf(
    "cost",
    "costUsd",
    "usdCost",
    "usd",
    "amount",
    "deducted",
    "budgetCost",
    "estimatedCost"
)
private val TOKEN_FIELD_CANDIDATES = setOf(
    "tokens",
    "totalTokens",
    "total_tokens",
    "inputTokens",
    "outputTokens",
    "promptTokens",
    "completionTokens"
)

private val PLACEHOLDER_AUTHOR_PATTERN = Regex("""(?i)\b[A-Z][\p{L}'’\-]+(?:\s+[A-Z](?:\.)?)+,\s*et al\.""")

internal fun validateGeneratedOutputFile(
    output: GeneratedOutputFile,
    normalizeHtmlStructure: Boolean = true
): GeneratedOutputFile {
    require(
        AtlasHashing.constantTimeEquals(
            output.contentHash,
            AtlasHashing.hashText(output.content)
        )
    ) {
        "Generated output ${output.relativePath} content hash is invalid"
    }
    return when {
        output.mediaType.startsWith("text/html", ignoreCase = true) -> {
            validateCompleteHtmlEnvelope(output.content, output.relativePath)
            val normalizedContent = if (normalizeHtmlStructure) {
                normalizePublishedHtmlStructure(
                    content = output.content,
                    relativePath = output.relativePath
                )
            } else {
                require(Regex("(?is)<footer\\b").containsMatchIn(output.content)) {
                    "ACTIVE generated HTML output ${output.relativePath} requires an authored footer"
                }
                output.content
            }
            val normalizedOutput = output.copy(
                content = normalizedContent,
                contentHash = AtlasHashing.hashText(normalizedContent)
            )
            validateHtmlCitationTraceability(normalizedOutput.content, normalizedOutput.relativePath)
            normalizedOutput
        }
        output.mediaType.equals("application/json", ignoreCase = true) ||
            output.mediaType.endsWith("+json", ignoreCase = true) -> {
            validateJsonCitationTraceability(output.content, output.relativePath)
            output
        }
        else -> output
    }
}

private fun validateCompleteHtmlEnvelope(content: String, relativePath: String) {
    val hasHtmlClose = Regex("(?is)</html\\s*>").containsMatchIn(content)
    val hasBodyClose = Regex("(?is)</body\\s*>").containsMatchIn(content)
    if (!hasHtmlClose || !hasBodyClose) {
        throw IllegalStateException(
            "Published HTML output $relativePath is incomplete: expected closing </body> and </html> tags."
        )
    }
}

private fun normalizePublishedHtmlStructure(content: String, relativePath: String): String {
    if (Regex("(?is)<footer\\b").containsMatchIn(content)) return content

    val footer = """
        <footer class="evomolior-generated-footer" role="contentinfo">
          <p>Published artifact: ${escapeFooterHtml(relativePath)}.</p>
        </footer>
    """.trimIndent()

    return when {
        Regex("(?is)</body\\s*>").containsMatchIn(content) ->
            content.replaceFirst(Regex("(?is)</body\\s*>"), "$footer\n</body>")
        Regex("(?is)</html\\s*>").containsMatchIn(content) ->
            content.replaceFirst(Regex("(?is)</html\\s*>"), "$footer\n</html>")
        else -> "$content\n$footer"
    }
}

private fun escapeFooterHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun validateHtmlCitationTraceability(content: String, relativePath: String) {
    val referenceItems = Regex("(?is)<li[^>]*>(.*?)</li>")
        .findAll(content)
        .mapNotNull { match ->
            match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        }
        .toList()

    if (referenceItems.isEmpty()) return

    referenceItems.forEach { item ->
        val flattened = item
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val hasTraceableIdentifier = flattened.contains("https://", ignoreCase = true) ||
            flattened.contains("http://", ignoreCase = true) ||
            flattened.contains("doi:", ignoreCase = true) ||
            flattened.contains("PMID", ignoreCase = true)
        val hasPlaceholderCitation = PLACEHOLDER_AUTHOR_PATTERN.containsMatchIn(flattened) ||
            flattened.contains("et al.", ignoreCase = true)

        if (hasPlaceholderCitation && !hasTraceableIdentifier) {
            throw IllegalStateException(
                "Published HTML output $relativePath contains an untraceable placeholder citation: $flattened"
            )
        }
    }
}

private fun validateJsonCitationTraceability(content: String, relativePath: String) {
    val element = runCatching { Json.parseToJsonElement(content.trim()) }
        .getOrElse {
            throw IllegalStateException("Published JSON output $relativePath is not valid JSON: ${it.message}")
        }
    val obj = element as? JsonObject
        ?: throw IllegalStateException("Published JSON output $relativePath must be a JSON object")

    obj["sources"]?.jsonArray?.forEachIndexed { index, source ->
        val sourceObj = source as? JsonObject ?: return@forEachIndexed
        val values = sourceObj.values.mapNotNull { jsonValue ->
            when (jsonValue) {
                is JsonPrimitive -> jsonValue.contentOrNull
                else -> null
            }
        }
        val flattened = values.joinToString(" ")
        val hasTraceableIdentifier = values.any { value ->
            value.contains("https://", ignoreCase = true) ||
                value.contains("http://", ignoreCase = true) ||
                value.contains("doi:", ignoreCase = true) ||
                value.matches(Regex("""10\.\S+""")) ||
                value.matches(Regex("""\d{7,}"""))
        }
        val hasPlaceholderCitation = values.any { value ->
            PLACEHOLDER_AUTHOR_PATTERN.containsMatchIn(value) || value.contains("et al.", ignoreCase = true)
        }

        if (hasPlaceholderCitation && !hasTraceableIdentifier) {
            throw IllegalStateException(
                "Published JSON output $relativePath contains an untraceable placeholder source at index $index: $flattened"
            )
        }

        sourceObj["doi"]?.jsonPrimitive?.contentOrNull?.let { doi ->
            if (!doi.matches(Regex("""10\.\S+"""))) {
                throw IllegalStateException(
                    "Published JSON output $relativePath contains an invalid DOI value: $doi"
                )
            }
        }
        sourceObj["pmid"]?.jsonPrimitive?.contentOrNull?.let { pmid ->
            if (!pmid.matches(Regex("""\d+"""))) {
                throw IllegalStateException(
                    "Published JSON output $relativePath contains an invalid PMID value: $pmid"
                )
            }
        }
        sourceObj["url"]?.jsonPrimitive?.contentOrNull?.let { url ->
            if (!(url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))) {
                throw IllegalStateException(
                    "Published JSON output $relativePath contains an invalid URL value: $url"
                )
            }
        }
    }
}

private fun List<com.achulkov.evomolior.core.contract.RunInputArtifact>.toZipManifestJson(): String =
    kotlinx.serialization.json.buildJsonArray {
        forEach { artifact ->
            add(
                kotlinx.serialization.json.buildJsonObject {
                    put("relativePath", artifact.relativePath)
                    put("sourceName", artifact.sourceName)
                    put("mimeType", artifact.mimeType)
                    put("sizeBytes", artifact.sizeBytes)
                    put("sha256", artifact.sha256)
                    put("kind", artifact.kind.name)
                }
            )
        }
    }.toString()
