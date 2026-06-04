package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.MetaArchiveEntryEntity
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
     */
    suspend fun archiveRun(
        runId: String,
        groupIds: List<String>,
        winnerId: String?,
        objective: String,
        startingFieldJson: String,
        finalGeneration: Int,
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
            }

            runCatching {
                writeResultsBundle(runId, completionRecord)
            }.onFailure { err ->
                Napier.w("RunArchiveService: failed to write results bundle for $runId", err)
            }

            runCatching {
                writeFinalArticleHtml(runId, completionRecord)
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
     * @return ZIP bytes, or null if the run has no sealed StartingField yet.
     */
    suspend fun buildResultsBundle(runId: String, completionRecord: RunCompletionRecord? = null): ByteArray? = withContext(Dispatchers.IO) {
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
            resolvedCompletionRecord?.let { record ->
                addEntry("outputs/final-article.html", buildFinalArticleHtml(startingField, record).toByteArray(Charsets.UTF_8))
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
     * @return Path to the written ZIP file, or null if the bundle could not be built.
     */
    suspend fun writeResultsBundle(runId: String, completionRecord: RunCompletionRecord? = null): File? = withContext(Dispatchers.IO) {
        val bytes = buildResultsBundle(runId, completionRecord) ?: return@withContext null
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
        const val ENTRY_ROUND_SUMMARY    = "ROUND_SUMMARY"
        const val ENTRY_GRADING_SHEET    = "GRADING_SHEET"
        const val ENTRY_RUN_COMPLETED    = "RUN_COMPLETED"
    }

    private fun resolveRunRoot(runId: String): File = dbFactory.dbFilePath(runId, "group-0")
        .parentFile?.parentFile?.parentFile
        ?: error("Unable to resolve run root for $runId")

    private fun resultsBundleFile(runId: String): File = File(resolveRunRoot(runId), "results/results.zip")

    private fun finalArticleFile(runId: String): File = File(resolveRunRoot(runId), "results/final-article.html")

    private suspend fun writeFinalArticleHtml(runId: String, completionRecord: RunCompletionRecord? = null): File? = withContext(Dispatchers.IO) {
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
        val file = finalArticleFile(runId)
        file.parentFile?.mkdirs()
        file.writeText(buildFinalArticleHtml(startingField, resolvedCompletionRecord), Charsets.UTF_8)
        file
    }

    private fun buildFinalArticleHtml(
        startingField: StartingField,
        completionRecord: RunCompletionRecord
    ): String {
        completionRecord.finalDeliverableHtml
            ?.takeIf { it.isNotBlank() }
            ?.let { authoredHtml ->
                return normalizeStandaloneHtml(authoredHtml)
            }
        extractEmbeddedFinalDeliverableHtml(completionRecord)
            ?.let { authoredHtml ->
                return normalizeStandaloneHtml(authoredHtml)
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
