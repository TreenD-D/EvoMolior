package com.achulkov.evokammer.substrate.tools

import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Materialises versioned tool registry rows into executable files under the group tools mount.
 *
 * The registry remains the source of truth for metadata and versioning, while the host-side
 * `/tools` directory stores the runnable executable that `ExecuteToolTool` launches from inside
 * the agent VM.
 *
 * @param toolsRoot Host directory mirrored into the agent VM as read-only `/tools`.
 */
class ToolMaterializer(
    private val toolsRoot: File
) {
    /**
     * Writes the given tool entry as an executable file and returns the created path.
     *
     * @param tool Registry entry to materialise.
     * @return The executable file created on disk.
     */
    suspend fun materialize(tool: ToolRegistryEntryEntity): File = withContext(Dispatchers.IO) {
        toolsRoot.mkdirs()

        val executableFile = File(toolsRoot, "${tool.name}-v${tool.version}")
        executableFile.writeText(renderExecutable(tool))
        executableFile.setExecutable(true, false)
        executableFile
    }

    private fun renderExecutable(tool: ToolRegistryEntryEntity): String {
        val rawCode = tool.code.trimEnd()
        val normalised = if (rawCode.startsWith("#!")) rawCode else when (tool.codeLanguage.lowercase()) {
            "shell" -> "#!/bin/sh\nset -eu\n$rawCode"
            "python" -> "#!/usr/bin/env python3\n$rawCode"
            "kotlin" -> "#!/usr/bin/env kotlin\n$rawCode"
            else -> throw IllegalArgumentException("Unsupported tool language: ${tool.codeLanguage}")
        }

        return if (normalised.endsWith("\n")) normalised else "$normalised\n"
    }
}
