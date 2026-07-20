package com.achulkov.evomolior.evomolior.substrate

import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.api.service.RunArchiveService
import io.github.aakira.napier.Napier
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides read-only access to substrate SQLite databases for all discovered runs on disk.
 *
 * DB files are stored at:
 * `{dataRootDir}/evomolior/runs/{runId}/groups/{groupId}/substrate.db`
 *
 * This class is **read-only** from the browser's perspective — it never modifies substrate data.
 * It opens DB connections via [SubstrateDatabaseFactory.get] which caches open connections.
 *
 * @param factory The factory used to open or reuse [EvoMoliorDatabase] instances.
 * @param dataRootDir The root data directory (same one passed to [SubstrateDatabaseFactory]).
 */
class SubstrateRepository(
    private val factory: SubstrateDatabaseFactory,
    private val dataRootDir: File
) {

    /**
     * Scans the filesystem for runs that have at least one substrate DB file.
     *
     * Walks `{dataRootDir}/evomolior/runs/` and collects `groups/{groupId}/substrate.db` under
     * each run directory. Returns a list sorted by most-recently-modified first.
     */
    suspend fun discoverRuns(): List<RunInfo> = withContext(Dispatchers.IO) {
        val runsDir = File(dataRootDir, "evomolior/runs")
        if (!runsDir.exists() || !runsDir.isDirectory) {
            Napier.d("SubstrateRepository: runs dir not found at ${runsDir.absolutePath}")
            return@withContext emptyList()
        }

        val runs = mutableListOf<RunInfo>()
        runsDir.listFiles()?.filter { it.isDirectory }?.forEach { runDir ->
            val groupsDir = File(runDir, "groups")
            if (!groupsDir.exists() || !groupsDir.isDirectory) return@forEach

            val groupEntries = groupsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { groupDir ->
                    val dbFile = File(groupDir, "substrate.db")
                    if (dbFile.exists()) groupDir.name to dbFile.lastModified() else null
                }
                ?: return@forEach

            if (groupEntries.isEmpty()) return@forEach

            val isCompleted = runCatching {
                val archiveDb = factory.get(runDir.name, "__meta_archive__")
                archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_RUN_COMPLETED)
                    .any { it.runId == runDir.name }
            }.getOrElse { false }

            runs.add(
                RunInfo(
                    runId = runDir.name,
                    groupIds = groupEntries.map { it.first }.sorted(),
                    lastModifiedMs = groupEntries.maxOf { it.second },
                    isCompleted = isCompleted
                )
            )
        }

        runs.sortedByDescending { it.lastModifiedMs }
    }

    /**
     * Loads a [SubstrateSnapshot] for the given (runId, groupId) pair by querying all 9 substrate
     * layers. Must be called from a coroutine on [kotlinx.coroutines.Dispatchers.IO].
     *
     * @param runId The run identifier.
     * @param groupId The group identifier.
     * @param limit Maximum rows per layer (default 300). Prevents OOM on large runs.
     */
    suspend fun loadSnapshot(runId: String, groupId: String, limit: Int = 300): SubstrateSnapshot {
        val dbFile = factory.dbFilePath(runId, groupId)
        if (!dbFile.exists()) {
            Napier.w("SubstrateRepository: DB file not found for run=$runId group=$groupId")
            return SubstrateSnapshot(runId = runId, groupId = groupId, loaded = false)
        }

        return try {
            val db = factory.get(runId, groupId)

            // Derive the current generation from the event log to filter legacy protocols
            val currentGeneration = try {
                db.eventLogDao()
                    .getLatestByType(runId = runId, groupId = groupId, eventType = "RoundStarted", limit = 1)
                    .firstOrNull()?.generation ?: Int.MAX_VALUE
            } catch (_: Exception) { Int.MAX_VALUE }

            SubstrateSnapshot(
                runId = runId,
                groupId = groupId,
                loaded = true,

                kbEntries = try {
                    // search("") matches everything via 'LIKE '%%''
                    db.knowledgeBaseDao().search(runId = runId, groupId = groupId, query = "", limit = limit)
                } catch (e: Exception) {
                    Napier.w("SubstrateRepository[$groupId]: KB query failed — ${e.message}")
                    emptyList()
                },

                kgFacts = try {
                    db.knowledgeGraphFactDao().queryByTriplePattern(
                        runId = runId, groupId = groupId,
                        subjectPattern = "%", relationPattern = "%", objectPattern = "%",
                        limit = limit
                    )
                } catch (e: Exception) {
                    Napier.w("SubstrateRepository[$groupId]: KG query failed — ${e.message}")
                    emptyList()
                },

                tools = try {
                    db.toolRegistryDao().getActive(runId = runId, groupId = groupId, domain = null)
                        .take(limit)
                } catch (e: Exception) {
                    Napier.w("SubstrateRepository[$groupId]: tools query failed — ${e.message}")
                    emptyList()
                },

                stagingSubmissions = try {
                    db.stagingDao().getAll(runId = runId, groupId = groupId, limit = limit)
                } catch (e: Exception) {
                    Napier.w("SubstrateRepository[$groupId]: staging query failed — ${e.message}")
                    emptyList()
                },

                wipTasks = try {
                    // minAbandonCount = 0 returns ALL tasks (no abandon-count filter)
                    db.wipTaskDao().getFrequentlyAbandoned(
                        runId = runId, groupId = groupId, minAbandonCount = 0
                    ).take(limit)
                } catch (e: Exception) {
                    Napier.w("SubstrateRepository[$groupId]: WIP tasks query failed — ${e.message}")
                    emptyList()
                },

                eventLogEntries = try {
                    db.eventLogDao().queryFiltered(
                        runId = runId, groupId = groupId,
                        agentId = null, eventType = null,
                        minGeneration = null, maxGeneration = null,
                        limit = limit, offset = 0
                    )
                } catch (e: Exception) {
                    Napier.w("SubstrateRepository[$groupId]: event log query failed — ${e.message}")
                    emptyList()
                },

                deadEnds = try {
                    db.deadEndsDao().getAll(runId = runId, groupId = groupId).take(limit)
                } catch (e: Exception) {
                    Napier.w("SubstrateRepository[$groupId]: dead ends query failed — ${e.message}")
                    emptyList()
                },

                legacyProtocols = try {
                    db.legacyProtocolDao()
                        .getActive(runId = runId, groupId = groupId, currentGeneration = currentGeneration)
                        .take(limit)
                } catch (e: Exception) {
                    Napier.w("SubstrateRepository[$groupId]: legacy protocols query failed — ${e.message}")
                    emptyList()
                },

                trustScores = try {
                    db.trustScoreDao().getTrustedSources(
                        runId = runId, groupId = groupId, minScore = 0.0
                    ).take(limit)
                } catch (e: Exception) {
                    Napier.w("SubstrateRepository[$groupId]: trust scores query failed — ${e.message}")
                    emptyList()
                },

                kgSchema = try {
                    db.kgSchemaDao().get(runId = runId, groupId = groupId)
                } catch (e: Exception) {
                    Napier.w("SubstrateRepository[$groupId]: KG schema query failed — ${e.message}")
                    null
                }
            )
        } catch (e: Exception) {
            Napier.e("SubstrateRepository: failed to open DB for run=$runId group=$groupId: ${e.message}", e)
            SubstrateSnapshot(runId = runId, groupId = groupId, loaded = false)
        }
    }
}
