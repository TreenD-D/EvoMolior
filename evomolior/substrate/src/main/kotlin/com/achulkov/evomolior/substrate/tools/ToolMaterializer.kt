package com.achulkov.evomolior.substrate.tools

import com.achulkov.evomolior.core.atlas.ToolDslProgram
import com.achulkov.evomolior.core.atlas.ToolDslPythonRuntime
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    /**
     * Writes the given tool entry as an executable file and returns the created path.
     *
     * @param tool Registry entry to materialise.
     * @return The executable file created on disk.
     */
    suspend fun materialize(tool: ToolRegistryEntryEntity): File = withContext(Dispatchers.IO) {
        materializeBlocking(tool)
    }

    /**
     * Writes the given tool entry synchronously using the same renderer as [materialize].
     *
     * This is used by deterministic Council compilation before a field can be sealed.
     *
     * @param tool Registry entry to materialise.
     * @return The executable file created on disk.
     */
    fun materializeBlocking(tool: ToolRegistryEntryEntity): File {
        toolsRoot.mkdirs()

        val executableFile = File(toolsRoot, "${tool.name}-v${tool.version}")
        executableFile.writeText(renderExecutable(tool))
        executableFile.setExecutable(true, false)
        return executableFile
    }

    private fun renderExecutable(tool: ToolRegistryEntryEntity): String {
        val rawCode = tool.code.trimEnd()
        val normalised = if (rawCode.startsWith("#!")) rawCode else when (tool.codeLanguage.lowercase()) {
            "shell" -> "#!/bin/sh\nset -eu\n$rawCode"
            "python" -> "#!/usr/bin/env python3\n$rawCode"
            "kotlin" -> "#!/usr/bin/env kotlin\n$rawCode"
            "dsl" -> {
                val program = json.decodeFromString(ToolDslProgram.serializer(), rawCode)
                ToolDslPythonRuntime.render(
                    json.encodeToString(ToolDslProgram.serializer(), program)
                )
            }
            else -> throw IllegalArgumentException("Unsupported tool language: ${tool.codeLanguage}")
        }

        return if (normalised.endsWith("\n")) normalised else "$normalised\n"
    }
}
