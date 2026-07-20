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
            if (entry.isDeadEnd) appendLine("DEAD END: This approach has been tried and failed.")
        }
    }

    fun formatFullEntries(entries: List<KnowledgeBaseEntryEntity>): String = buildString {
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
            appendLine("Content:")
            appendLine(entry.content.take(KB_BODY_MAX_CHARS).let { content ->
                if (entry.content.length > KB_BODY_MAX_CHARS) "$content\n[...truncated]" else content
            })
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
