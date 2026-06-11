package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.model.FinalOutputSpec
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.MetaArchiveEntryEntity
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Serialises a completed run's key artefacts into the meta-archive tables for
 * cross-run Council learning.
 *
 * Called by [EvoKammerRunService] once a run transitions to COMPLETED.
 *
 * Archived artefacts per run:
 * - **STARTING_FIELD** — the Genesis Council's generated config
 * - **WINNING_KB_SNAPSHOT** — the winning group's KB entries (summarised)
 * - **DEAD_ENDS_EXPORT** — all Dead Ends from all groups
 * - **LEGACY_PROTOCOLS** — top-fitness protocol packages from each group
 * - **ROUND_SUMMARY** — per-group end-of-round summary snapshots
 *
 * @param dbFactory Substrate database factory for opening per-group databases.
 */
class RunArchiveService(
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
    suspend fun archiveRun(
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

        // 1. Archive the StartingField (run-level)
        val archiveDb = dbFactory.get(runId, META_ARCHIVE_GROUP_ID)
        try {
            archiveDb.metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = UUID.randomUUID().toString(),
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

            runCatching {
                writeResultsBundle(runId, completionRecord, requestedOutputSpec)
            }.onFailure { err ->
                Napier.w("RunArchiveService: failed to write results bundle for $runId", err)
            }

            runCatching {
                writeGeneratedOutputFiles(runId, completionRecord, requestedOutputSpec)
            }.onFailure { err ->
                Napier.w("RunArchiveService: failed to write generated output files for $runId", err)
            }

            runCatching {
                writeFinalArticleHtml(runId, completionRecord, requestedOutputSpec)
            }.onFailure { err ->
                Napier.w("RunArchiveService: failed to write final article HTML for $runId", err)
            }

            Napier.i("RunArchiveService: run=$runId archiving complete")
        } catch (e: Exception) {
            Napier.e("RunArchiveService: failed to archive run=$runId", e)
        }
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
        val startingFieldEntry = entries.firstOrNull { it.entryType == ENTRY_STARTING_FIELD } ?: return@withContext null
        val startingField = runCatching {
            json.decodeFromString(StartingField.serializer(), startingFieldEntry.contentJson)
        }.getOrElse {
            return@withContext null
        }

        val runRoot = resolveRunRoot(runId)
        val artifactsRoot = File(runRoot, "artifacts")
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

        val manifestJson = buildJsonObject {
            put("runId", runId)
            put("objective", startingField.objective)
            put("groupCount", startingField.groupCount)
            put("generationCeiling", startingField.generationCeiling)
            put("startingFieldArchiveId", startingFieldEntry.id)
            put("artifactCount", startingField.inputArtifacts.size)
            put("archiveEntryCount", entries.size)
            put("inputArtifacts", buildJsonArray {
                startingField.inputArtifacts.forEach { artifact ->
                    add(buildJsonObject {
                        put("relativePath", artifact.relativePath)
                        put("sourceName", artifact.sourceName)
                        put("mimeType", artifact.mimeType)
                        put("sizeBytes", artifact.sizeBytes)
                        put("sha256", artifact.sha256)
                        put("kind", artifact.kind.name)
                    })
                }
            })
        }.toString()

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun addEntry(path: String, content: ByteArray) {
                val entry = ZipEntry(path)
                entry.time = 0L
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
            }

            addEntry("manifest.json", manifestJson.toByteArray(Charsets.UTF_8))
            addEntry("input-artifacts.json", startingField.inputArtifacts.toZipManifestJson().toByteArray(Charsets.UTF_8))
            addEntry("starting-field.json", startingFieldEntry.contentJson.toByteArray(Charsets.UTF_8))
            if (generatedOutputs.isNotEmpty()) {
                generatedOutputs.forEach { outputFile ->
                    addEntry("outputs/${outputFile.relativePath}", outputFile.content.toByteArray(Charsets.UTF_8))
                }
            } else {
                resolvedCompletionRecord?.let { record ->
                    buildFinalArticleHtml(startingField, record, resolvedRequestedOutputSpec)?.let { fallbackHtml ->
                        addEntry("outputs/final-article.html", fallbackHtml.toByteArray(Charsets.UTF_8))
                    }
                }
            }

            val treeOutputs = File(resolveRunRoot(runId), "results")
                .listFiles { file -> file.isFile && file.name.startsWith("deliverable-skeleton-tree-") && file.name.endsWith(".json") }
                ?.sortedBy { it.name }
                .orEmpty()
            treeOutputs.forEach { treeFile ->
                addEntry("outputs/${treeFile.name}", Files.readAllBytes(treeFile.toPath()))
            }

            if (artifactsRoot.exists()) {
                val files = artifactsRoot.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.relativeTo(artifactsRoot).path }
                    .toList()
                files.forEach { file ->
                    val relative = file.relativeTo(artifactsRoot).invariantSeparatorsPath
                    addEntry("inputs/$relative", Files.readAllBytes(file.toPath()))
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
                    entry.contentJson.toByteArray(Charsets.UTF_8)
                )
            }
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

        const val ENTRY_STARTING_FIELD   = "STARTING_FIELD"
        const val ENTRY_DEAD_ENDS        = "DEAD_ENDS_EXPORT"
        const val ENTRY_LEGACY_PROTOCOLS = "LEGACY_PROTOCOLS"
        const val ENTRY_WINNING_KB       = "WINNING_KB_SNAPSHOT"
        const val ENTRY_DELIVERABLE_TREE  = "DELIVERABLE_SKELETON_TREE"
        const val ENTRY_OVERSEER_AUDIT    = com.achulkov.evokammer.core.contract.OverseerKernelArchive.ENTRY_TYPE
        const val ENTRY_ROUND_SUMMARY    = "ROUND_SUMMARY"
        const val ENTRY_GRADING_SHEET    = "GRADING_SHEET"
        const val ENTRY_RUN_COMPLETED    = "RUN_COMPLETED"
    }

    private fun resolveRunRoot(runId: String): File = dbFactory.dbFilePath(runId, "group-0")
        .parentFile?.parentFile?.parentFile
        ?: error("Unable to resolve run root for $runId")

    private fun resultsBundleFile(runId: String): File = File(resolveRunRoot(runId), "results/results.zip")

    private fun finalArticleFile(runId: String): File = File(resolveRunRoot(runId), "results/final-article.html")

    private fun generatedOutputFile(runId: String, relativePath: String): File =
        File(resolveRunRoot(runId), "results/$relativePath")

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
        val generatedOutputs = resolveGeneratedOutputFiles(
            runId = runId,
            entries = entries,
            completionRecord = resolvedCompletionRecord,
            requestedOutputSpec = requestedOutputSpec
                ?: resolvedCompletionRecord?.requestedOutputSpec
                ?: resolveRequestedOutputSpec(runId)
        )
        generatedOutputs.map { output ->
            generatedOutputFile(runId, output.relativePath).also { file ->
                file.parentFile?.mkdirs()
                file.writeText(output.content, Charsets.UTF_8)
            }
        }
    }

    private suspend fun writeFinalArticleHtml(
        runId: String,
        completionRecord: RunCompletionRecord? = null,
        requestedOutputSpec: FinalOutputSpec? = null
    ): File? = withContext(Dispatchers.IO) {
        val archiveDb = dbFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val entries = archiveDb.metaArchiveDao().getByRun(runId)
        val startingFieldEntry = entries.firstOrNull { it.entryType == ENTRY_STARTING_FIELD } ?: return@withContext null
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
        if (resolvedRequestedOutputSpec != null &&
            !resolvedRequestedOutputSpec.mediaType.startsWith("text/html", ignoreCase = true)
        ) {
            return@withContext null
        }
        val file = finalArticleFile(runId)
        file.parentFile?.mkdirs()
        val html = buildFinalArticleHtml(startingField, resolvedCompletionRecord, resolvedRequestedOutputSpec) ?: return@withContext null
        file.writeText(html, Charsets.UTF_8)
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
            ?: "EvoKammer Fast Run Summary"
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
            append("        <p class=\"eyebrow\">EvoKammer / MindVortex fast-run article</p>\n")
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

    private fun resolveRequestedOutputSpec(runId: String): FinalOutputSpec? {
        val stateFile = File(resolveRunRoot(runId), "control/${PersistedRunStateStore.STATE_FILE_NAME}")
        return PersistedRunStateStore.read(stateFile)?.finalOutputSpec
    }

    private fun resolveGeneratedOutputFiles(
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
                val validatedOutputs = outputs.mapNotNull { output ->
                    runCatching {
                        val validated = validateGeneratedOutputFile(output)
                        VerifiedSourceCatalog.validate(validated, sourceCatalog)
                        validated
                    }.onFailure { err ->
                        if (firstFailure == null) firstFailure = err
                        Napier.w(
                            "RunArchiveService: skipping invalid generated output ${output.relativePath} for $runId: ${err.message}"
                        )
                    }.getOrNull()
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
            val validated = validateGeneratedOutputFile(recoveredSummaryOutput)
            VerifiedSourceCatalog.validate(validated, sourceCatalog)
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
                )
            )
            VerifiedSourceCatalog.validate(validated, sourceCatalog)
            return listOf(validated)
        }

        return emptyList()
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

private val PLACEHOLDER_AUTHOR_PATTERN = Regex("""(?i)\b[A-Z][\p{L}'’\-]+(?:\s+[A-Z](?:\.)?)+,\s*et al\.""")

internal fun validateGeneratedOutputFile(output: GeneratedOutputFile): GeneratedOutputFile {
    when {
        output.mediaType.startsWith("text/html", ignoreCase = true) -> {
            validateHtmlCitationTraceability(output.content, output.relativePath)
        }
        output.mediaType.equals("application/json", ignoreCase = true) ||
            output.mediaType.endsWith("+json", ignoreCase = true) -> {
            validateJsonCitationTraceability(output.content, output.relativePath)
        }
    }
    return output
}

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

private fun List<com.achulkov.evokammer.core.contract.RunInputArtifact>.toZipManifestJson(): String =
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
