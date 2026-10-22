package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.atlas.ToolCandidateWireCompilation
import com.achulkov.evomolior.core.atlas.ToolContractWorkspace
import com.achulkov.evomolior.core.atlas.ToolContractWorkspaceFile
import com.achulkov.evomolior.core.atlas.ToolWorkspaceFileAccess
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolAuthoringWorkspaceToolTest {
    @Test
    fun `workspace tool reads declared contracts and edits only author files`() = runTest {
        val root = createTempDirectory("tool-author-workspace-").toAbsolutePath()
        val workspace = workspace()
        workspace.files.forEach { file ->
            val path = root.resolve(file.relativePath)
            Files.createDirectories(path.parent)
            Files.writeString(path, file.content)
        }
        val actions = mutableListOf<ActionEventType>()
        val tool = ToolAuthoringWorkspaceTool(
            runtime = ToolAuthoringRuntime(
                workspace = workspace,
                workspaceRoot = root,
                compileCandidate = {
                    ToolCandidateWireCompilation.Rejected("unused", "unused")
                }
            ),
            agentId = "agent",
            actionGate = { action -> actions += action.type }
        )

        val listing = tool.execute(ToolAuthoringWorkspaceTool.Args(operation = "LIST"))
        assertTrue("contract/tool-contract.json" in listing)
        assertTrue("src/main.py" in listing)
        assertEquals(
            """{"authority":"public"}""",
            tool.execute(
                ToolAuthoringWorkspaceTool.Args(
                    operation = "READ",
                    relativePath = "contract/tool-contract.json"
                )
            )
        )
        assertTrue(
            tool.execute(
                ToolAuthoringWorkspaceTool.Args(
                    operation = "WRITE",
                    relativePath = "src/main.py",
                    content = "def run_tool(value):\n    return value * 2\n"
                )
            ).startsWith("Workspace file updated")
        )
        assertEquals(
            "def run_tool(value):\n    return value * 2\n",
            root.resolve("src/main.py").readText()
        )
        assertTrue(
            tool.execute(
                ToolAuthoringWorkspaceTool.Args(
                    operation = "WRITE",
                    relativePath = "contract/tool-contract.json",
                    content = "{}"
                )
            ).startsWith("Error:")
        )
        assertEquals(
            """{"authority":"public"}""",
            root.resolve("contract/tool-contract.json").readText()
        )
        assertTrue(
            tool.execute(
                ToolAuthoringWorkspaceTool.Args(
                    operation = "WRITE",
                    relativePath = "../escape",
                    content = "bad"
                )
            ).startsWith("Error:")
        )
        assertEquals(
            listOf(ActionEventType.FILESYSTEM_READ, ActionEventType.FILESYSTEM_WRITE),
            actions
        )
    }

    private fun workspace(): ToolContractWorkspace {
        val files = listOf(
            readOnly("contract/tool-contract.json", """{"authority":"public"}"""),
            readOnly("contract/input-schema.json", "{}"),
            readOnly("contract/output-schema.json", "{}"),
            readOnly("contract/public-tests.json", """{"cases":[]}"""),
            readOnly("contract/README.md", "public contract"),
            ToolContractWorkspaceFile(
                relativePath = "src/main.py",
                content = "def run_tool(value):\n    return value\n",
                access = ToolWorkspaceFileAccess.AUTHOR_EDITABLE
            )
        )
        return ToolContractWorkspace(
            workspaceId = "workspace",
            catalogId = "catalog",
            opportunityHandle = "T1",
            opportunityCommitmentHash = "a".repeat(64),
            files = files
        ).withComputedHash()
    }

    private fun readOnly(relativePath: String, content: String) =
        ToolContractWorkspaceFile(
            relativePath = relativePath,
            content = content,
            access = ToolWorkspaceFileAccess.READ_ONLY
        )
}
