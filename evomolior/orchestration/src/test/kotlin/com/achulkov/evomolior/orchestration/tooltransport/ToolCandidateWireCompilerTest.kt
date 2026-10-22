package com.achulkov.evomolior.orchestration.tooltransport

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ToolCandidateWireCompilation
import com.achulkov.evomolior.core.atlas.ToolCandidateWireDraft
import com.achulkov.evomolior.orchestration.lifecycle.ToolSubmissionValidationResult
import com.achulkov.evomolior.orchestration.lifecycle.ToolSubmissionValidator
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolCandidateWireCompilerTest {
    @Test
    fun `post-materialization contract tamper rejects wire before validation`() {
        val fixture = ToolTransportTestFixture
        val workspace = ToolContractWorkspaceCompiler.compile(
            fixture.catalog,
            fixture.capability,
            fixture.commitment,
            fixture.skeleton,
            fixture.publicTests
        )
        val root = createTempDirectory("tool-wire-contract-tamper-")
        ToolContractWorkspaceMaterializer.materialize(workspace, root)
        writeAuthorTests(root)
        Files.writeString(root.resolve("src/main.py"), "print('{}')")
        val immutableContract = root.resolve("contract/input-schema.json")
        immutableContract.toFile().setWritable(true, false)
        Files.writeString(immutableContract, """{"type":"object"}""")

        val rejected = assertIs<ToolCandidateWireCompilation.Rejected>(
            ToolCandidateWireCompiler(
                fixture.catalog,
                fixture.capability,
                workspace,
                fixture.commitment,
                root
            ).compile(
                ToolCandidateWireDraft(
                    opportunityHandle = "T1",
                    sourceArtifactPath = "src/main.py",
                    entryPoint = "run_tool",
                    supplementalTestsPath = "tests/author-tests.json"
                )
            )
        )

        assertEquals("tool_wire_workspace_tampered", rejected.code)
        assertTrue(rejected.detail.contains("fresh generated workspace"))
    }

    @Test
    fun `exact ids schemas tests and hashes come only from runtime authority`() {
        val fixture = ToolTransportTestFixture
        val workspace = ToolContractWorkspaceCompiler.compile(
            fixture.catalog,
            fixture.capability,
            fixture.commitment,
            fixture.skeleton,
            fixture.publicTests
        )
        val root = createTempDirectory("tool-wire-compile-")
        ToolContractWorkspaceMaterializer.materialize(workspace, root)
        writeAuthorTests(root)
        Files.writeString(
            root.resolve("src/main.py"),
            """
            import json
            import sys
            value = json.load(sys.stdin)
            sys.stdout.write(json.dumps({"ratio": float(value["count"])}, separators=(",", ":")))
            """.trimIndent()
        )
        val compiler = ToolCandidateWireCompiler(
            fixture.catalog,
            fixture.capability,
            workspace,
            fixture.commitment,
            root
        )

        val result = assertIs<ToolCandidateWireCompilation.Compiled>(
            compiler.compile(
                ToolCandidateWireDraft(
                    opportunityHandle = "T1",
                    sourceArtifactPath = "src/main.py",
                    entryPoint = "run_tool",
                    supplementalTestsPath = "tests/author-tests.json",
                    declaredAssumptions = listOf("Count is non-negative.")
                )
            )
        ).candidate
        val payload = Json.parseToJsonElement(result.content) as JsonObject

        assertEquals(
            fixture.commitment.opportunity.opportunityId,
            payload.getValue("opportunityId").jsonPrimitive.content
        )
        assertEquals(
            AtlasHashing.canonicalJson(
                Json.parseToJsonElement(fixture.skeleton.inputSchemaJson)
            ),
            AtlasHashing.canonicalJson(payload.getValue("inputSchema"))
        )
        assertEquals(
            AtlasHashing.canonicalJson(
                Json.parseToJsonElement(fixture.skeleton.outputSchemaJson)
            ),
            AtlasHashing.canonicalJson(payload.getValue("outputSchema"))
        )
        val tests = payload.getValue("tests") as JsonArray
        val publicTestId = tests.single().jsonObject["testId"]!!.jsonPrimitive.content
        assertTrue(publicTestId.startsWith("author-case-"))
        assertFalse(publicTestId.contains("private-promotion-boundary"))
        assertEquals(
            "2.0",
            tests.single().jsonObject["expectedOutput"]!!
                .jsonObject["ratio"]!!.jsonPrimitive.content
        )
        assertEquals(fixture.commitment.nodeId.value, result.wipTaskRef)
        assertEquals(fixture.commitment.commitmentHash, result.opportunityCommitmentHash)
        assertTrue(result.sourceHash.matches(Regex("[0-9a-f]{64}")))
        assertTrue(result.candidateHash.matches(Regex("[0-9a-f]{64}")))
        assertFalse(result.content.contains("Count is non-negative."))
        assertFalse(result.content.contains("private-fixture"))
        assertFalse(result.content.contains("private-promotion-boundary"))
    }

    @Test
    fun `public semantic cardinality is independent of private promotion fixtures`() {
        val fixture = ToolTransportTestFixture
        val multiPrivateCommitment = fixture.commitment.copy(
            opportunity = fixture.commitment.opportunity.copy(
                promotionTestIds = setOf(
                    "private-promotion-a",
                    "private-promotion-b",
                    "private-promotion-c"
                )
            ),
            commitmentHash = ""
        ).withComputedHash()
        val binding = fixture.binding.copy(
            opportunityHash = multiPrivateCommitment.commitmentHash
        )
        val catalog = fixture.catalog.copy(
            bindings = listOf(binding),
            catalogHash = ""
        ).withComputedHash()
        val skeleton = com.achulkov.evomolior.core.atlas.ToolProjectSkeletonCompiler.compile(
            multiPrivateCommitment
        )
        val exactBinding = binding.copy(
            inputSchemaHash = AtlasHashing.hashText(skeleton.inputSchemaJson),
            outputSchemaHash = AtlasHashing.hashText(skeleton.outputSchemaJson),
            projectSkeletonHash = skeleton.skeletonHash
        )
        val exactCatalog = catalog.copy(
            bindings = listOf(exactBinding),
            catalogHash = ""
        ).withComputedHash()
        val workspace = ToolContractWorkspaceCompiler.compile(
            exactCatalog,
            fixture.capability,
            multiPrivateCommitment,
            skeleton,
            emptyList()
        )
        val root = createTempDirectory("tool-wire-private-cardinality-")
        ToolContractWorkspaceMaterializer.materialize(workspace, root)
        writeAuthorTests(root)
        Files.writeString(root.resolve("src/main.py"), "print('{}')")

        val candidate = assertIs<ToolCandidateWireCompilation.Compiled>(
            ToolCandidateWireCompiler(
                exactCatalog,
                fixture.capability,
                workspace,
                multiPrivateCommitment,
                root
            ).compile(
                ToolCandidateWireDraft(
                    "T1",
                    "src/main.py",
                    "run_tool",
                    "tests/author-tests.json"
                )
            )
        ).candidate

        val payload = Json.parseToJsonElement(candidate.content).jsonObject
        assertEquals(1, payload.getValue("tests").jsonArray.size)
        multiPrivateCommitment.opportunity.promotionTestIds.forEach { privateId ->
            assertFalse(candidate.content.contains(privateId))
        }
    }

    @Test
    fun `compiled candidate passes the existing exact production validator unchanged`() = runTest {
        val fixture = ToolTransportTestFixture
        val workspace = ToolContractWorkspaceCompiler.compile(
            fixture.catalog,
            fixture.capability,
            fixture.commitment,
            fixture.skeleton,
            fixture.publicTests
        )
        val root = createTempDirectory("tool-wire-production-validator-")
        ToolContractWorkspaceMaterializer.materialize(workspace, root)
        writeAuthorTests(root)
        Files.writeString(
            root.resolve("src/main.py"),
            """
            import json
            import sys
            value = json.load(sys.stdin)
            sys.stdout.write(json.dumps({"ratio": float(value["count"])}, separators=(",", ":")))
            """.trimIndent()
        )
        val candidate = assertIs<ToolCandidateWireCompilation.Compiled>(
            ToolCandidateWireCompiler(
                fixture.catalog,
                fixture.capability,
                workspace,
                fixture.commitment,
                root
            ).compile(
                ToolCandidateWireDraft(
                    "T1",
                    "src/main.py",
                    "run_tool",
                    "tests/author-tests.json"
                )
            )
        ).candidate
        val validatorRoot = createTempDirectory("tool-wire-validator-root-")
        val validator = ToolSubmissionValidator(
            runId = fixture.capability.runId,
            groupId = fixture.capability.groupId,
            toolsMountHostPath = validatorRoot.resolve("tools").toString(),
            opportunityResolver = { fixture.commitment }
        )

        val result = validator.validate(
            StagingSubmissionEntity(
                id = "wire-candidate",
                agentId = "agent-wire",
                role = "Builder",
                type = "TOOL",
                content = candidate.content,
                wipTaskRef = candidate.wipTaskRef,
                budgetSignature = "test",
                generation = fixture.capability.round,
                groupId = fixture.capability.groupId,
                runId = fixture.capability.runId,
                submittedAtMs = 1L
            )
        )

        assertEquals(ToolSubmissionValidationResult.Valid, result)
    }

    @Test
    fun `v21 missing schema draft compiles or returns one actionable wire error before review`() {
        val fixture = ToolTransportTestFixture
        val workspace = ToolContractWorkspaceCompiler.compile(
            fixture.catalog,
            fixture.capability,
            fixture.commitment,
            fixture.skeleton,
            fixture.publicTests
        )
        val root = createTempDirectory("tool-wire-v21-")
        ToolContractWorkspaceMaterializer.materialize(workspace, root)
        writeAuthorTests(root)
        Files.writeString(root.resolve("src/main.py"), "print('{}')")
        val compiler = ToolCandidateWireCompiler(
            fixture.catalog,
            fixture.capability,
            workspace,
            fixture.commitment,
            root
        )

        assertIs<ToolCandidateWireCompilation.Compiled>(
            compiler.compile(
                ToolCandidateWireDraft(
                    "T1",
                    "src/main.py",
                    "run_tool",
                    "tests/author-tests.json"
                )
            )
        )
        val rejected = assertIs<ToolCandidateWireCompilation.Rejected>(
            compiler.compile(
                ToolCandidateWireDraft(
                    "T1",
                    "src/other.py",
                    "run_tool",
                    "tests/author-tests.json"
                )
            )
        )
        assertEquals("tool_wire_source_path_mismatch", rejected.code)
        assertTrue(rejected.detail.contains("src/main.py"))
    }

    private fun writeAuthorTests(root: java.nio.file.Path) {
        Files.writeString(
            root.resolve("tests/author-tests.json"),
            """{"cases":[{"input":{"count":2},"expectedOutput":{"ratio":2}}]}"""
        )
    }
}
