package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.atlas.ToolCandidateWireCompilation
import com.achulkov.evomolior.core.atlas.ToolCandidateWireDraft
import com.achulkov.evomolior.core.atlas.ToolContractWorkspace
import com.achulkov.evomolior.core.atlas.ToolWorkspaceFileAccess
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_TOOL_WORKSPACE_FILE_BYTES = 256_000

/**
 * Runtime-owned tool-authoring session bound to one immutable worker lifecycle.
 *
 * @param workspace Exact generated workspace manifest.
 * @param workspaceRoot Host-visible root containing the materialized workspace.
 * @param compileCandidate Exact runtime compiler used by [WriteStagingTool].
 */
data class ToolAuthoringRuntime(
    val workspace: ToolContractWorkspace,
    val workspaceRoot: Path,
    val compileCandidate: suspend (ToolCandidateWireDraft) -> ToolCandidateWireCompilation
) {
    init {
        require(workspace.hasValidHash()) {
            "Tool authoring runtime requires an untampered workspace manifest"
        }
        require(workspaceRoot.isAbsolute) {
            "Tool authoring runtime requires an absolute workspace root"
        }
    }
}

/**
 * Lists, reads, and edits only files declared by a generated tool workspace manifest.
 *
 * Contract files remain read-only. The model can write only `AUTHOR_EDITABLE` files and cannot
 * create arbitrary paths, directories, or symbolic links.
 */
class ToolAuthoringWorkspaceTool(
    private val runtime: ToolAuthoringRuntime,
    private val agentId: String,
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE
) : SimpleTool<ToolAuthoringWorkspaceTool.Args>(
    argsType = typeToken<Args>(),
    name = "tool_workspace",
    description = """
        Work with the generated tool-authoring workspace.
        Operations:
        - LIST: list declared paths and access classes; omit relativePath/content.
        - READ: read one declared path.
        - WRITE: replace one AUTHOR_EDITABLE file with content (maximum 256KB).
        Contract files are runtime-owned and cannot be changed. After editing, submit the short
        toolCandidate wire through write_staging; do not copy schemas or authority IDs.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        val operation: String,
        val relativePath: String? = null,
        val content: String? = null
    )

    private val manifestFiles = runtime.workspace.files.associateBy { it.relativePath }

    override suspend fun execute(args: Args): String = when (args.operation.uppercase()) {
        "LIST" -> listFiles(args)
        "READ" -> readFile(args)
        "WRITE" -> writeFile(args)
        else -> "Error: operation must be LIST, READ, or WRITE."
    }

    private fun listFiles(args: Args): String {
        if (args.relativePath != null || args.content != null) {
            return "Error: LIST does not accept relativePath or content."
        }
        return json.encodeToString(
            runtime.workspace.files
                .sortedBy { it.relativePath }
                .map { WorkspaceFileCard(it.relativePath, it.access) }
        )
    }

    private suspend fun readFile(args: Args): String {
        if (args.content != null) return "Error: READ does not accept content."
        val relativePath = args.relativePath
            ?: return "Error: READ requires relativePath."
        val target = resolveDeclaredFile(relativePath)
            ?: return "Error: Path is not declared by this generated workspace."
        actionGate.intercept(
            AgentAction(
                type = ActionEventType.FILESYSTEM_READ,
                agentId = agentId,
                detail = relativePath
            )
        )
        if (!Files.isRegularFile(target)) {
            return "Error: Declared workspace file is missing."
        }
        return Files.readString(target)
    }

    private suspend fun writeFile(args: Args): String {
        val relativePath = args.relativePath
            ?: return "Error: WRITE requires relativePath."
        val content = args.content ?: return "Error: WRITE requires content."
        val manifestFile = manifestFiles[relativePath]
            ?: return "Error: Path is not declared by this generated workspace."
        if (manifestFile.access != ToolWorkspaceFileAccess.AUTHOR_EDITABLE) {
            return "Error: '$relativePath' is runtime-owned and read-only."
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TOOL_WORKSPACE_FILE_BYTES) {
            return "Error: Workspace file exceeds 256KB (${bytes.size} bytes)."
        }
        val target = resolveDeclaredFile(relativePath)
            ?: return "Error: Path is not declared by this generated workspace."
        actionGate.intercept(
            AgentAction(
                type = ActionEventType.FILESYSTEM_WRITE,
                agentId = agentId,
                detail = relativePath,
                sizeBytes = bytes.size
            )
        )
        val temporary = target.resolveSibling("${target.fileName}.tmp-${agentId.hashCode()}")
        return runCatching {
            Files.writeString(
                temporary,
                content,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            "Workspace file updated: $relativePath (${bytes.size} bytes)."
        }.getOrElse { error ->
            runCatching { Files.deleteIfExists(temporary) }
            "Error: Workspace write failed (${error::class.simpleName})."
        }
    }

    private fun resolveDeclaredFile(relativePath: String): Path? {
        if (relativePath !in manifestFiles) return null
        val root = runtime.workspaceRoot.toAbsolutePath().normalize()
        val target = root.resolve(relativePath).normalize()
        if (!target.startsWith(root)) return null
        var current = root
        root.relativize(target).forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return null
        }
        return target
    }

    @Serializable
    private data class WorkspaceFileCard(
        val relativePath: String,
        val access: ToolWorkspaceFileAccess
    )

    private companion object {
        val json = Json { encodeDefaults = true }
    }
}
