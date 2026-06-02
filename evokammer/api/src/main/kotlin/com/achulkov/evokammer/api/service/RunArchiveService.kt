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
        finalGeneration: Int
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
                writeResultsBundle(runId)
            }.onFailure { err ->
                Napier.w("RunArchiveService: failed to write results bundle for $runId", err)
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
    suspend fun buildResultsBundle(runId: String): ByteArray? = withContext(Dispatchers.IO) {
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
    suspend fun writeResultsBundle(runId: String): File? = withContext(Dispatchers.IO) {
        val bytes = buildResultsBundle(runId) ?: return@withContext null
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
