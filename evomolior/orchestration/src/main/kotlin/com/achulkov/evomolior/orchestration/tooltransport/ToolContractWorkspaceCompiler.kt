package com.achulkov.evomolior.orchestration.tooltransport

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ToolCanonicalTypedValueEncoder
import com.achulkov.evomolior.core.atlas.ToolContractWorkspace
import com.achulkov.evomolior.core.atlas.ToolContractWorkspaceFile
import com.achulkov.evomolior.core.atlas.ToolOpportunityBinding
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCapability
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCatalog
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingResolution
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolProjectSkeleton
import com.achulkov.evomolior.core.atlas.ToolPublicContractTest
import com.achulkov.evomolior.core.atlas.ToolRuntimeKind
import com.achulkov.evomolior.core.atlas.ToolWorkspaceFileAccess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Deterministically generates the immutable contract half of a tool-authoring workspace.
 *
 * Private promotion fixtures are deliberately not accepted by this compiler. Callers may supply
 * only explicitly public cases; the existing private promotion authority remains separate.
 */
object ToolContractWorkspaceCompiler {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    /**
     * Compiles one generated workspace manifest.
     *
     * @param catalog Exact lifecycle-local binding catalog.
     * @param capability Exact catalog capability.
     * @param commitment Historical origin-version opportunity commitment.
     * @param skeleton Exact generated implementation skeleton.
     * @param publicTests Explicitly public test cases only.
     */
    fun compile(
        catalog: ToolOpportunityBindingCatalog,
        capability: ToolOpportunityBindingCapability,
        commitment: ToolOpportunityCommitment,
        skeleton: ToolProjectSkeleton,
        publicTests: List<ToolPublicContractTest>
    ): ToolContractWorkspace {
        val binding = when (val resolution = catalog.resolve("T1", capability)) {
            is ToolOpportunityBindingResolution.Resolved -> resolution.binding
            is ToolOpportunityBindingResolution.Rejected ->
                error("Tool workspace binding rejected: ${resolution.reason}")
        }
        requireExactAuthority(binding, commitment, skeleton)
        require(
            publicTests.map(ToolPublicContractTest::testId).sorted() ==
                binding.publicTestIds.sorted()
        ) { "Public workspace tests must exactly match the bound public test IDs" }

        val inputSchema = json.parseToJsonElement(skeleton.inputSchemaJson) as JsonObject
        val outputSchema = json.parseToJsonElement(skeleton.outputSchemaJson) as JsonObject
        val canonicalTests = publicTests.sortedBy(ToolPublicContractTest::testId).map { test ->
            JsonObject(
                mapOf(
                    "testId" to JsonPrimitive(test.testId),
                    "input" to ToolCanonicalTypedValueEncoder.encode(test.input, inputSchema),
                    "expectedOutput" to ToolCanonicalTypedValueEncoder.encode(
                        test.expectedOutput,
                        outputSchema
                    )
                )
            )
        }
        val contractJson = JsonObject(
            mapOf(
                "opportunityHandle" to JsonPrimitive(binding.handle),
                "opportunityCommitmentHash" to JsonPrimitive(binding.opportunityHash),
                "inputSchemaHash" to JsonPrimitive(binding.inputSchemaHash),
                "outputSchemaHash" to JsonPrimitive(binding.outputSchemaHash),
                "projectSkeletonHash" to JsonPrimitive(binding.projectSkeletonHash),
                "runtimeKind" to JsonPrimitive(binding.runtimeKind.name),
                "sourceArtifactPath" to JsonPrimitive(binding.runtimeKind.sourcePath),
                "entryPoint" to JsonPrimitive(binding.runtimeKind.entryPoint),
                "maximumRuntimeMillis" to JsonPrimitive(
                    commitment.opportunity.maximumRuntimeMillis
                ),
                "maximumOutputBytes" to JsonPrimitive(
                    commitment.opportunity.maximumOutputBytes
                )
            )
        )
        val files = buildList {
            add(readOnly("contract/tool-contract.json", pretty(contractJson)))
            add(readOnly("contract/input-schema.json", pretty(inputSchema)))
            add(readOnly("contract/output-schema.json", pretty(outputSchema)))
            add(
                readOnly(
                    "contract/public-tests.json",
                    pretty(JsonObject(mapOf("cases" to JsonArray(canonicalTests))))
                )
            )
            add(readOnly("contract/README.md", readme(binding)))
            add(
                ToolContractWorkspaceFile(
                    relativePath = binding.runtimeKind.sourcePath,
                    content = sourceTemplate(binding.runtimeKind, skeleton),
                    access = ToolWorkspaceFileAccess.AUTHOR_EDITABLE
                )
            )
            add(
                ToolContractWorkspaceFile(
                    relativePath = "tests/author-tests.json",
                    content = pretty(JsonObject(mapOf("cases" to JsonArray(emptyList())))),
                    access = ToolWorkspaceFileAccess.AUTHOR_EDITABLE
                )
            )
            add(
                ToolContractWorkspaceFile(
                    relativePath = "artifacts/local-results.json",
                    content = pretty(JsonObject(mapOf("results" to JsonArray(emptyList())))),
                    access = ToolWorkspaceFileAccess.RUNTIME_OUTPUT
                )
            )
        }
        return ToolContractWorkspace(
            workspaceId = "tool-workspace-${catalog.catalogHash.take(20)}-${binding.handle}",
            catalogId = catalog.catalogId,
            opportunityHandle = binding.handle,
            opportunityCommitmentHash = binding.opportunityHash,
            files = files
        ).withComputedHash()
    }

    private fun requireExactAuthority(
        binding: ToolOpportunityBinding,
        commitment: ToolOpportunityCommitment,
        skeleton: ToolProjectSkeleton
    ) {
        require(
            commitment.hasValidHash() &&
                skeleton.hasValidHash() &&
                binding.nodeId == commitment.nodeId.value &&
                binding.opportunityId == commitment.opportunity.opportunityId &&
                binding.opportunityHash == commitment.commitmentHash &&
                binding.inputSchemaHash == AtlasHashing.hashText(skeleton.inputSchemaJson) &&
                binding.outputSchemaHash == AtlasHashing.hashText(skeleton.outputSchemaJson) &&
                binding.projectSkeletonHash == skeleton.skeletonHash
        ) { "Tool workspace authority does not reproduce the historical commitment" }
    }

    private fun readOnly(path: String, content: String) = ToolContractWorkspaceFile(
        relativePath = path,
        content = content,
        access = ToolWorkspaceFileAccess.READ_ONLY
    )

    private fun pretty(value: kotlinx.serialization.json.JsonElement): String =
        json.encodeToString(value)

    private fun readme(binding: ToolOpportunityBinding): String = """
        # Generated tool contract

        Use opportunity handle `${binding.handle}` in `write_staging`.
        Edit `${binding.runtimeKind.sourcePath}` and optional `tests/author-tests.json`.
        Files under `contract/` are runtime-owned and read-only. Do not copy schemas, exact
        opportunity identities, or committed test IDs into a staging payload. The runtime compiles
        the exact TOOL payload and canonical numeric representation.
    """.trimIndent()

    private fun sourceTemplate(
        kind: ToolRuntimeKind,
        skeleton: ToolProjectSkeleton
    ): String {
        val expectedName = when (kind) {
            ToolRuntimeKind.SHELL -> "tool.sh"
            ToolRuntimeKind.PYTHON -> "tool.py"
            ToolRuntimeKind.FORMULA_DSL -> "formula.json"
            ToolRuntimeKind.RULE_DSL -> "rules.json"
            ToolRuntimeKind.SIMULATION_DSL -> "simulation.json"
        }
        return requireNotNull(skeleton.files.singleOrNull { it.relativePath == expectedName }) {
            "Generated skeleton lacks $expectedName"
        }.content
    }

    private val ToolRuntimeKind.sourcePath: String
        get() = when (this) {
            ToolRuntimeKind.SHELL -> "src/main.sh"
            ToolRuntimeKind.PYTHON -> "src/main.py"
            ToolRuntimeKind.FORMULA_DSL -> "src/formula.json"
            ToolRuntimeKind.RULE_DSL -> "src/rules.json"
            ToolRuntimeKind.SIMULATION_DSL -> "src/simulation.json"
        }

    private val ToolRuntimeKind.entryPoint: String
        get() = when (this) {
            ToolRuntimeKind.SHELL -> "main.sh"
            ToolRuntimeKind.PYTHON -> "run_tool"
            ToolRuntimeKind.FORMULA_DSL -> "formula"
            ToolRuntimeKind.RULE_DSL -> "rules"
            ToolRuntimeKind.SIMULATION_DSL -> "simulation"
        }
}

/**
 * Materialises and verifies a generated workspace without trusting model-visible file contents.
 */
object ToolContractWorkspaceMaterializer {
    /**
     * Creates every generated file beneath an empty [root].
     *
     * @param workspace Exact generated workspace manifest.
     * @param root Empty workspace root.
     */
    fun materialize(workspace: ToolContractWorkspace, root: Path) {
        require(workspace.hasValidHash()) { "Cannot materialize a tampered tool workspace" }
        Files.createDirectories(root)
        workspace.files.forEach { file ->
            val target = safeResolve(root, file.relativePath)
            Files.createDirectories(target.parent)
            Files.writeString(
                target,
                file.content,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            if (file.access == ToolWorkspaceFileAccess.READ_ONLY) {
                target.toFile().setWritable(false, false)
            }
        }
    }

    /**
     * Returns one failure code when any immutable generated file changed.
     *
     * Editable source, author tests, and runtime output are intentionally excluded.
     */
    fun verifyImmutableFiles(workspace: ToolContractWorkspace, root: Path): String? {
        if (!workspace.hasValidHash()) return "tool_workspace_manifest_hash_mismatch"
        workspace.files
            .filter { it.access == ToolWorkspaceFileAccess.READ_ONLY }
            .forEach { file ->
                val target = safeResolve(root, file.relativePath)
                if (!Files.isRegularFile(target)) {
                    return "tool_workspace_contract_file_missing:${file.relativePath}"
                }
                if (AtlasHashing.hashText(Files.readString(target)) != file.contentHash) {
                    return "tool_workspace_contract_file_tampered:${file.relativePath}"
                }
            }
        return null
    }

    /** Resolves one manifest path while retaining [root] containment. */
    fun safeResolve(root: Path, relativePath: String): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(relativePath).normalize()
        require(resolved.startsWith(normalizedRoot)) {
            "Tool workspace path escapes its generated root"
        }
        return resolved
    }
}
