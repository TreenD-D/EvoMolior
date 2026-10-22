package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import java.util.Locale

internal const val KB_HEADER_MAX_CHARS = 160
internal const val KB_BODY_MAX_CHARS = 6_000

internal object KnowledgeBaseContextFormatter {
    fun formatIndex(
        entries: List<KnowledgeBaseEntryEntity>,
        heading: String
    ): String = buildString {
        appendLine(heading)
        appendLine("Use read_kb(entryIds=[...]) only for entries you actually need in full.")
        entries.forEachIndexed { index, entry ->
            appendLine()
            appendLine("--- KB Header ${index + 1} ---")
            appendLine("ID: ${entry.id}")
            appendLine("Title/Header: ${entry.titleOrHeader()}")
            appendLine("Domain: ${entry.domain}")
            appendLine("Generation: ${entry.generation}")
            appendLine("Trust Score: ${String.format(Locale.US, "%.2f", entry.trustScore)}")
            appendLine("Citations: ${entry.citationCount}")
            appendLine("Length: ${entry.content.length} chars")
            if (entry.isDeadEnd) appendLine("DEAD END: This approach has been tried and failed.")
        }
    }

    /**
     * Formats selected entries, returning at most [KB_BODY_MAX_CHARS] of each body from [offset].
     *
     * @param entries Entries to render.
     * @param offset Character offset into each body; a page that ends early names the next offset.
     */
    fun formatFullEntries(entries: List<KnowledgeBaseEntryEntity>, offset: Int = 0): String = buildString {
        appendLine("Loaded ${entries.size} selected KB entr${if (entries.size == 1) "y" else "ies"} by ID:")
        entries.forEachIndexed { index, entry ->
            appendLine()
            appendLine("--- KB Entry ${index + 1} ---")
            appendLine("ID: ${entry.id}")
            appendLine("Title/Header: ${entry.titleOrHeader()}")
            appendLine("Domain: ${entry.domain}")
            appendLine("Generation: ${entry.generation}")
            appendLine("Trust Score: ${String.format(Locale.US, "%.2f", entry.trustScore)}")
            appendLine("Citations: ${entry.citationCount}")
            if (entry.isDeadEnd) appendLine("DEAD END: This approach has been tried and failed.")
            val start = offset.coerceIn(0, entry.content.length)
            val end = minOf(entry.content.length, start + KB_BODY_MAX_CHARS)
            appendLine(if (start == 0) "Content:" else "Content (characters $start-$end of ${entry.content.length}):")
            appendLine(entry.content.substring(start, end))
            if (end < entry.content.length) {
                appendLine(
                    "[...truncated: characters $end-${entry.content.length} remain; call " +
                        "read_kb(entryIds=[\"${entry.id}\"], offset=$end) to continue]"
                )
            }
        }
    }

    fun KnowledgeBaseEntryEntity.titleOrHeader(): String {
        val lines = content.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        val heading = lines.firstOrNull { line ->
            line.startsWith("#") ||
                line.endsWith(":") ||
                line.contains("title:", ignoreCase = true) ||
                line.contains("heading:", ignoreCase = true)
        } ?: lines.firstOrNull().orEmpty()
        return heading
            .removePrefix("#")
            .trim()
            .ifBlank { "(untitled KB entry)" }
            .take(KB_HEADER_MAX_CHARS)
    }
}
