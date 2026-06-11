package com.achulkov.evokammer.substrate

import com.achulkov.evokammer.core.contract.OverseerKernelAuditEntry
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Append-only JSONL store for Overseer kernel audit events.
 *
 * @param substrateFactory Factory used to resolve the Overseer control directory.
 * @param json JSON codec used for serialization.
 */
class OverseerKernelAuditStore(
    private val substrateFactory: SubstrateDatabaseFactory,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false }
) {
    private val fileName = "overseer-kernel-audit.jsonl"

    /**
     * Resolves the audit log file.
     */
    fun resolveFile(): File = File(substrateFactory.overseerControlDir(), fileName)

    /**
     * Reads all audit entries currently on disk.
     */
    fun readAll(): List<OverseerKernelAuditEntry> {
        val file = resolveFile()
        if (!file.exists()) return emptyList()
        return file.readLines()
            .asSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) null else runCatching {
                    json.decodeFromString(OverseerKernelAuditEntry.serializer(), trimmed)
                }.getOrNull()
            }
            .toList()
    }

    /**
     * Appends an audit entry atomically enough for the file-backed control plane.
     *
     * @param entry Audit entry to persist.
     */
    fun append(entry: OverseerKernelAuditEntry) {
        val file = resolveFile()
        file.parentFile?.mkdirs()
        Files.writeString(
            file.toPath(),
            json.encodeToString(OverseerKernelAuditEntry.serializer(), entry) + System.lineSeparator(),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }
}
