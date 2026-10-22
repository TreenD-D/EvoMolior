package com.achulkov.evomolior.orchestration.tooltransport

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolWorkspaceTamperTest {
    @Test
    fun `generated contract files are exact and edits fail verification`() {
        val fixture = ToolTransportTestFixture
        val workspace = ToolContractWorkspaceCompiler.compile(
            catalog = fixture.catalog,
            capability = fixture.capability,
            commitment = fixture.commitment,
            skeleton = fixture.skeleton,
            publicTests = fixture.publicTests
        )
        val root = createTempDirectory("tool-workspace-tamper-")
        ToolContractWorkspaceMaterializer.materialize(workspace, root)

        assertTrue(workspace.hasValidHash())
        assertNull(ToolContractWorkspaceMaterializer.verifyImmutableFiles(workspace, root))
        assertTrue(
            !Files.readString(root.resolve("contract/public-tests.json"))
                .contains("private-promotion-boundary")
        )
        val contract = root.resolve("contract/input-schema.json")
        contract.toFile().setWritable(true, false)
        Files.writeString(contract, """{"type":"object"}""")

        assertEquals(
            "tool_workspace_contract_file_tampered:contract/input-schema.json",
            ToolContractWorkspaceMaterializer.verifyImmutableFiles(workspace, root)
        )
    }
}
