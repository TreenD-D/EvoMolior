package com.achulkov.evomolior.orchestration.tooltransport

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CompiledToolCandidateWire
import com.achulkov.evomolior.core.atlas.ToolCandidateWireCompilation
import com.achulkov.evomolior.core.atlas.ToolCandidateWireDraft
import com.achulkov.evomolior.core.atlas.ToolCanonicalTypedValueEncoder
import com.achulkov.evomolior.core.atlas.ToolContractWorkspace
import com.achulkov.evomolior.core.atlas.ToolOpportunityBinding
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCapability
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCatalog
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingResolution
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolRuntimeKind
import com.achulkov.evomolior.core.repair.ApproachVector
import com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Deterministically compiles a short model wire into the exact legacy TOOL submission payload.
 *
 * Opportunity IDs, schemas, committed public tests, language, name, and domain are supplied only
 * by runtime authority. Public and author tests retain independent runtime-owned identities;
 * separately sealed private promotion fixtures are never synthesized from their count or values.
 * The resulting JSON is consumed unchanged by deterministic preflight and Critic review, while
 * private promotion executes its own sealed fixture authority against the same implementation.
 */
class ToolCandidateWireCompiler(
    private val catalog: ToolOpportunityBindingCatalog,
    private val capability: ToolOpportunityBindingCapability,
    private val workspace: ToolContractWorkspace,
    private val commitment: ToolOpportunityCommitment,
    private val workspaceRoot: Path,
    private val visibleRepairDiagnostics: List<ToolRepairDiagnosticV1> = emptyList(),
    private val repairApproachVector: ApproachVector? = null
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    init {
        require(
            (visibleRepairDiagnostics.isEmpty() && repairApproachVector == null) ||
                (
                    visibleRepairDiagnostics.isNotEmpty() &&
                        repairApproachVector != null &&
                        repairApproachVector.hasValidHash() &&
                        repairApproachVector.runId == commitment.runId &&
                        repairApproachVector.groupId == capability.groupId &&
                        repairApproachVector.opportunityId ==
                        commitment.opportunity.opportunityId &&
                        repairApproachVector.round == capability.round
                    )
        ) {
            "Tool repair diagnostics require one exact sealed approach vector"
        }
    }

    /**
     * Compiles [draft] or returns one bounded actionable wire error.
     *
     * @param draft Model-authored short wire.
     */
    fun compile(draft: ToolCandidateWireDraft): ToolCandidateWireCompilation = runCatching {
        compileExact(draft)
    }.getOrElse { error ->
        val wireError = error as? ToolWireCompileException
        ToolCandidateWireCompilation.Rejected(
            code = wireError?.code ?: "tool_wire_internal_error",
            detail = (wireError?.message ?: "Runtime could not compile the tool candidate.")
                .take(500)
        )
    }

    private fun compileExact(draft: ToolCandidateWireDraft): ToolCandidateWireCompilation.Compiled {
        val binding = when (val resolution = catalog.resolve(draft.opportunityHandle, capability)) {
            is ToolOpportunityBindingResolution.Resolved -> resolution.binding
            is ToolOpportunityBindingResolution.Rejected -> throw ToolWireCompileException(
                "tool_wire_handle_${resolution.reason.name.lowercase()}",
                "Opportunity handle is not valid for this worker lifecycle."
            )
        }
        verifyAuthority(binding)
        verifyConsumedRepairDiagnostics(draft)
        ToolContractWorkspaceMaterializer.verifyImmutableFiles(workspace, workspaceRoot)?.let {
            throw ToolWireCompileException(
                "tool_wire_workspace_tampered",
                "Runtime-owned contract files changed; request a fresh generated workspace."
            )
        }
        if (workspace.opportunityHandle != draft.opportunityHandle) {
            throw ToolWireCompileException(
                "tool_wire_workspace_foreign",
                "Generated workspace does not belong to this opportunity handle."
            )
        }
        val expectedSourcePath = binding.runtimeKind.sourcePath
        if (draft.sourceArtifactPath != expectedSourcePath) {
            throw ToolWireCompileException(
                "tool_wire_source_path_mismatch",
                "Use generated source path '$expectedSourcePath'."
            )
        }
        val expectedEntryPoint = binding.runtimeKind.entryPoint
        if (draft.entryPoint != expectedEntryPoint) {
            throw ToolWireCompileException(
                "tool_wire_entry_point_mismatch",
                "Use generated entry point '$expectedEntryPoint'."
            )
        }
        if (draft.supplementalTestsPath != null &&
            draft.supplementalTestsPath != "tests/author-tests.json"
        ) {
            throw ToolWireCompileException(
                "tool_wire_tests_path_mismatch",
                "Supplemental tests must use 'tests/author-tests.json'."
            )
        }
        val sourcePath = ToolContractWorkspaceMaterializer.safeResolve(
            workspaceRoot,
            draft.sourceArtifactPath
        )
        val source = if (Files.isRegularFile(sourcePath)) Files.readString(sourcePath) else null
        if (source.isNullOrBlank()) {
            throw ToolWireCompileException(
                "tool_wire_source_missing",
                "Generated source file is missing or empty."
            )
        }
        val canonicalSource = binding.runtimeKind.canonicalSource(source, json)
        val inputSchema = readContractObject("contract/input-schema.json")
        val outputSchema = readContractObject("contract/output-schema.json")
        val publicTests = readPublicTests(inputSchema, outputSchema, binding)
        val authorTests = draft.supplementalTestsPath?.let { relativePath ->
            canonicalSupplementalTests(relativePath, binding, inputSchema, outputSchema)
        } ?: CanonicalAuthorTests(emptyList(), null)
        val supplementalTestsHash = authorTests.contentHash
        val tests = compileSubmissionTests(publicTests, authorTests.cases)
        val payload = JsonObject(
            mapOf(
                "opportunityId" to JsonPrimitive(binding.opportunityId),
                "name" to JsonPrimitive("tool-${binding.opportunityHash.take(16)}"),
                "domain" to JsonPrimitive(
                    commitment.opportunity.opportunityType.name.lowercase()
                ),
                "language" to JsonPrimitive(binding.runtimeKind.language),
                "inputSchema" to inputSchema,
                "outputSchema" to outputSchema,
                "code" to JsonPrimitive(canonicalSource),
                "tests" to tests
            )
        )
        val content = AtlasHashing.canonicalJson(payload)
        val sourceHash = AtlasHashing.hashText(canonicalSource)
        val candidateAuthority = JsonObject(
            mapOf(
                "catalogHash" to JsonPrimitive(catalog.catalogHash),
                "workspaceHash" to JsonPrimitive(workspace.workspaceHash),
                "opportunityCommitmentHash" to JsonPrimitive(binding.opportunityHash),
                "sourceHash" to JsonPrimitive(sourceHash),
                "supplementalTestsHash" to (
                    supplementalTestsHash?.let(::JsonPrimitive) ?: JsonNull
                    ),
                "declaredAssumptions" to JsonArray(
                    draft.declaredAssumptions.map(::JsonPrimitive)
                ),
                "consumedRepairDiagnosticIds" to JsonArray(
                    draft.consumedRepairDiagnosticIds.sorted().map(::JsonPrimitive)
                ),
                "repairApproachVectorHash" to (
                    repairApproachVector?.vectorHash?.let(::JsonPrimitive) ?: JsonNull
                    ),
                "payload" to payload
            )
        )
        return ToolCandidateWireCompilation.Compiled(
            CompiledToolCandidateWire(
                content = content,
                wipTaskRef = binding.nodeId,
                opportunityCommitmentHash = binding.opportunityHash,
                sourceHash = sourceHash,
                supplementalTestsHash = supplementalTestsHash,
                candidateHash = AtlasHashing.hashText(
                    AtlasHashing.canonicalJson(candidateAuthority)
                ),
                consumedRepairDiagnosticIds = draft.consumedRepairDiagnosticIds.sorted(),
                repairApproachVector = repairApproachVector
            )
        )
    }

    private fun verifyConsumedRepairDiagnostics(draft: ToolCandidateWireDraft) {
        val visibleById = visibleRepairDiagnostics.associateBy(
            ToolRepairDiagnosticV1::diagnosticId
        )
        if (visibleById.size != visibleRepairDiagnostics.size ||
            visibleRepairDiagnostics.any { diagnostic ->
                !diagnostic.hasValidHash() ||
                    diagnostic.runId != commitment.runId ||
                    diagnostic.groupId != capability.groupId ||
                    diagnostic.nodeId != commitment.nodeId.value ||
                    diagnostic.opportunityId != commitment.opportunity.opportunityId ||
                    diagnostic.opportunityHash != commitment.commitmentHash ||
                    diagnostic.createdRound >= capability.round ||
                    diagnostic.visibleFromRound > capability.round
            }
        ) {
            throw ToolWireCompileException(
                "tool_wire_repair_authority_invalid",
                "Assigned repair diagnostics do not match this exact opportunity authority."
            )
        }
        if (draft.consumedRepairDiagnosticIds.any { it !in visibleById }) {
            throw ToolWireCompileException(
                "tool_wire_repair_diagnostic_not_visible",
                "A consumed repair diagnostic is not visible in this worker assignment."
            )
        }
    }

    private fun verifyAuthority(binding: ToolOpportunityBinding) {
        if (!workspace.hasValidHash() ||
            workspace.catalogId != catalog.catalogId ||
            workspace.opportunityCommitmentHash != binding.opportunityHash ||
            !commitment.hasValidHash() ||
            commitment.commitmentHash != binding.opportunityHash ||
            commitment.nodeId.value != binding.nodeId ||
            commitment.opportunity.opportunityId != binding.opportunityId
        ) {
            throw ToolWireCompileException(
                "tool_wire_authority_mismatch",
                "Generated catalog, workspace, and historical opportunity authority disagree."
            )
        }
    }

    private fun readContractObject(relativePath: String): JsonObject {
        val path = ToolContractWorkspaceMaterializer.safeResolve(workspaceRoot, relativePath)
        return runCatching { json.parseToJsonElement(Files.readString(path)) as JsonObject }
            .getOrElse {
                throw ToolWireCompileException(
                    "tool_wire_contract_invalid",
                    "Runtime-owned contract file '$relativePath' is invalid."
                )
            }
    }

    private fun readPublicTests(
        inputSchema: JsonObject,
        outputSchema: JsonObject,
        binding: ToolOpportunityBinding
    ): List<ToolSemanticTestCase> {
        val contract = readContractObject("contract/public-tests.json")
        val cases = contract["cases"] as? JsonArray ?: throw ToolWireCompileException(
            "tool_wire_public_tests_invalid",
            "Runtime-owned public tests are unavailable."
        )
        val canonical = cases.mapIndexed { index, element ->
            val test = element as? JsonObject ?: throw ToolWireCompileException(
                "tool_wire_public_tests_invalid",
                "Runtime-owned public test $index is invalid."
            )
            val id = test["testId"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolWireCompileException(
                    "tool_wire_public_tests_invalid",
                    "Runtime-owned public test $index lacks an ID."
                )
            val input = test["input"] ?: throw ToolWireCompileException(
                "tool_wire_public_tests_invalid",
                "Runtime-owned public test $index lacks input."
            )
            val expected = test["expectedOutput"] ?: throw ToolWireCompileException(
                "tool_wire_public_tests_invalid",
                "Runtime-owned public test $index lacks expected output."
            )
            ToolSemanticTestCase(
                testId = id,
                input = ToolCanonicalTypedValueEncoder.encode(input, inputSchema),
                expectedOutput = ToolCanonicalTypedValueEncoder.encode(
                    expected,
                    outputSchema
                )
            )
        }
        if (canonical.map(ToolSemanticTestCase::testId).sorted() !=
            binding.publicTestIds.sorted()
        ) {
            throw ToolWireCompileException(
                "tool_wire_public_tests_mismatch",
                "Runtime-owned public tests do not match bound test authority."
            )
        }
        if (canonical.any { it.testId in commitment.opportunity.promotionTestIds }) {
            throw ToolWireCompileException(
                "tool_wire_public_test_authority_invalid",
                "Runtime-owned public tests are not independent of private promotion authority."
            )
        }
        return canonical
    }

    private fun canonicalSupplementalTests(
        relativePath: String,
        binding: ToolOpportunityBinding,
        inputSchema: JsonObject,
        outputSchema: JsonObject
    ): CanonicalAuthorTests {
        val path = ToolContractWorkspaceMaterializer.safeResolve(workspaceRoot, relativePath)
        val root = runCatching {
            json.parseToJsonElement(Files.readString(path)) as JsonObject
        }.getOrElse {
            throw ToolWireCompileException(
                "tool_wire_author_tests_invalid",
                "Supplemental tests must be one strict JSON object."
            )
        }
        val cases = root["cases"] as? JsonArray ?: throw ToolWireCompileException(
            "tool_wire_author_tests_invalid",
            "Supplemental tests require a cases array."
        )
        val canonicalCases = cases.mapIndexed { index, element ->
            val case = element as? JsonObject ?: throw ToolWireCompileException(
                "tool_wire_author_tests_invalid",
                "Supplemental test $index must be an object."
            )
            val input = case["input"] ?: throw ToolWireCompileException(
                "tool_wire_author_tests_invalid",
                "Supplemental test $index lacks input."
            )
            val expected = case["expectedOutput"] ?: throw ToolWireCompileException(
                "tool_wire_author_tests_invalid",
                "Supplemental test $index lacks expectedOutput."
            )
            ToolSemanticTestCase(
                testId = "",
                input = ToolCanonicalTypedValueEncoder.encode(input, inputSchema),
                expectedOutput = ToolCanonicalTypedValueEncoder.encode(expected, outputSchema)
            )
        }
        val canonical = AtlasHashing.canonicalJson(
            JsonObject(
                mapOf(
                    "cases" to JsonArray(
                        canonicalCases.map { test ->
                            JsonObject(
                                mapOf(
                                    "input" to test.input,
                                    "expectedOutput" to test.expectedOutput
                                )
                            )
                        }
                    )
                )
            )
        )
        return CanonicalAuthorTests(
            cases = canonicalCases,
            contentHash = AtlasHashing.hashText(
                "${binding.opportunityHash}|$canonical"
            )
        )
    }

    private fun compileSubmissionTests(
        publicCases: List<ToolSemanticTestCase>,
        authorCases: List<ToolSemanticTestCase>
    ): JsonArray {
        if (publicCases.isEmpty() && authorCases.isEmpty()) {
            throw ToolWireCompileException(
                "tool_wire_test_cases_missing",
                "Provide at least one public or author semantic test case."
            )
        }
        val authored = authorCases.mapIndexed { index, test ->
            test.copy(
                testId = authorTestId(
                    index = index,
                    test = test,
                    reservedIds = publicCases.mapTo(linkedSetOf(), ToolSemanticTestCase::testId) +
                        commitment.opportunity.promotionTestIds
                )
            )
        }
        val cases = publicCases + authored
        if (cases.size > MAX_PUBLIC_SEMANTIC_CASES) {
            throw ToolWireCompileException(
                "tool_wire_test_case_limit_exceeded",
                "Provide at most $MAX_PUBLIC_SEMANTIC_CASES public or author semantic test cases."
            )
        }
        if (cases.map(ToolSemanticTestCase::testId).distinct().size != cases.size) {
            throw ToolWireCompileException(
                "tool_wire_test_ids_conflict",
                "Runtime could not assign independent public semantic test identities."
            )
        }
        return JsonArray(
            cases.map { test ->
                JsonObject(
                    mapOf(
                        "testId" to JsonPrimitive(test.testId),
                        "input" to test.input,
                        "expectedOutput" to test.expectedOutput
                    )
                )
            }
        )
    }

    private fun authorTestId(
        index: Int,
        test: ToolSemanticTestCase,
        reservedIds: Set<String>
    ): String {
        val canonicalCase = AtlasHashing.canonicalJson(
            JsonObject(
                mapOf(
                    "input" to test.input,
                    "expectedOutput" to test.expectedOutput
                )
            )
        )
        var salt = 0
        while (true) {
            val candidate = "author-case-" + AtlasHashing.hashText(
                "${commitment.commitmentHash}|$index|$salt|$canonicalCase"
            ).take(24)
            if (candidate !in reservedIds) return candidate
            salt += 1
        }
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

    private val ToolRuntimeKind.language: String
        get() = when (this) {
            ToolRuntimeKind.SHELL -> "shell"
            ToolRuntimeKind.PYTHON -> "python"
            ToolRuntimeKind.FORMULA_DSL,
            ToolRuntimeKind.RULE_DSL,
            ToolRuntimeKind.SIMULATION_DSL -> "dsl"
        }

    private fun ToolRuntimeKind.canonicalSource(source: String, json: Json): String =
        when (this) {
            ToolRuntimeKind.FORMULA_DSL,
            ToolRuntimeKind.RULE_DSL,
            ToolRuntimeKind.SIMULATION_DSL -> {
                val parsed = runCatching {
                    json.decodeFromString(
                        com.achulkov.evomolior.core.atlas.ToolDslProgram.serializer(),
                        source
                    )
                }.getOrElse {
                    throw ToolWireCompileException(
                        "tool_wire_source_invalid_dsl",
                        "Generated DSL source is not a valid closed typed program."
                    )
                }
                json.encodeToString(
                    com.achulkov.evomolior.core.atlas.ToolDslProgram.serializer(),
                    parsed
                )
            }
            else -> source
        }

    private companion object {
        const val MAX_PUBLIC_SEMANTIC_CASES = 32
    }
}

private data class ToolSemanticTestCase(
    val testId: String,
    val input: JsonElement,
    val expectedOutput: JsonElement
)

private data class CanonicalAuthorTests(
    val cases: List<ToolSemanticTestCase>,
    val contentHash: String?
)

private class ToolWireCompileException(
    val code: String,
    override val message: String
) : IllegalArgumentException(message)
