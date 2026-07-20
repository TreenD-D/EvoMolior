package com.achulkov.evomolior.sandbox.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val sourceAccessJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Append-only file store for source access decisions.
 */
class SourceAccessDecisionStore(
    private val file: File
) {
    fun append(record: SourceAccessDecisionRecord) {
        file.parentFile?.mkdirs()
        file.appendText(sourceAccessJson.encodeToString(SourceAccessDecisionRecord.serializer(), record) + "\n")
    }

    fun readAll(): List<SourceAccessDecisionRecord> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .mapNotNull { line ->
                line.takeIf { it.isNotBlank() }?.let {
                    runCatching {
                        sourceAccessJson.decodeFromString(SourceAccessDecisionRecord.serializer(), it)
                    }.getOrNull()
                }
            }
    }

    fun approvedHosts(): Set<String> =
        SourceAccessPolicy.normalizeHosts(
            readAll()
                .asSequence()
                .filter { it.allowed }
                .mapNotNull { it.host }
                .asIterable()
        )

    companion object {
        const val FILE_NAME: String = "source-access-decisions.jsonl"

        fun forRunRoot(runRoot: File): SourceAccessDecisionStore =
            SourceAccessDecisionStore(File(runRoot, "control/$FILE_NAME"))
    }
}

/**
 * Durable audit record for one source access decision.
 */
@Serializable
data class SourceAccessDecisionRecord(
    val id: String = UUID.randomUUID().toString(),
    val runId: String,
    val groupId: String,
    val agentId: String,
    val role: String,
    val generation: Int,
    val url: String,
    val host: String?,
    val purpose: String,
    val allowed: Boolean,
    val reason: String,
    val decidedAtMs: Long = System.currentTimeMillis()
)
