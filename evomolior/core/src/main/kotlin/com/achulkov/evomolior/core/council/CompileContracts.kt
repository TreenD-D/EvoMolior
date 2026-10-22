package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.Serializable

/** Stable typed path into a Council output or compiled field. */
@Serializable
data class ContractPath(val segments: List<String>) {
    /** Dot-separated human-readable representation. */
    override fun toString(): String = segments.joinToString(".")
}

/** Compiler error category suitable for selective role retry. */
@Serializable
enum class FieldCompileErrorCode {
    MALFORMED_ROLE_OUTPUT,
    OBJECTIVE_CHANGED,
    ENVELOPE_VIOLATION,
    UNKNOWN_MODEL,
    INVALID_NODE_REFERENCE,
    INVALID_DAG,
    INVALID_STAGE,
    NON_ATOMIC_NODE,
    INCOMPLETE_POLICY,
    REWARD_CAP_EXCEEDED,
    INVALID_KG_ALIAS,
    FUTURE_STAGE_DISCLOSURE,
    MISSING_TEST_COMMITMENT,
    INVALID_TOOL_SEMANTICS,
    SCHEMA_VERSION_MISMATCH,
    UNDECLARED_MATERIAL_CHANGE,
    HASH_MISMATCH,
    GENERAL_TEST_NULL,
    GENERAL_TEST_UNKNOWN_VERSION,
    GENERAL_TEST_EMPTY_EXPECTED_TEXT,
    GENERAL_TEST_PATH_OUTSIDE_OUTPUT_SPEC,
    GENERAL_TEST_TARGET_TYPE_MISMATCH,
    GENERAL_TEST_INVALID_JSON_POINTER,
    GENERAL_TEST_INVALID_RE2,
    GENERAL_TEST_PATTERN_TOO_LARGE,
    GENERAL_TEST_RANGE_NON_FINITE,
    GENERAL_TEST_RANGE_REVERSED,
    GENERAL_TEST_UNSUPPORTED_ASSERTION,
    GENERAL_TEST_NOT_EXECUTABLE
}

/**
 * One deterministic field compilation error.
 *
 * @param code Machine-readable error category.
 * @param path Exact invalid contract path.
 * @param message Operator-facing explanation.
 * @param responsibleCouncilRoles Roles eligible for selective retry.
 * @param relatedLocalNodeKeys Semantic nodes involved in the failure.
 */
@Serializable
data class FieldCompileError(
    val code: FieldCompileErrorCode,
    val path: ContractPath,
    val message: String,
    val responsibleCouncilRoles: Set<Role>,
    val relatedLocalNodeKeys: Set<LocalNodeKey> = emptySet()
)

/** Severity of a consistency report finding. */
@Serializable
enum class ConsistencySeverity { INFO, WARNING, ERROR }

/** One typed consistency finding. */
@Serializable
data class ConsistencyFinding(
    val code: String,
    val severity: ConsistencySeverity,
    val path: ContractPath,
    val message: String
)

/**
 * Deterministic compiler consistency report.
 *
 * @param compilerVersion Contract compiler version.
 * @param checksPerformed Stable check identifiers.
 * @param findings Typed findings.
 * @param workerFieldHash Hash of the worker field excluding its own seal.
 * @param graderFieldHash Hash of the grader field excluding its own seal.
 * @param atlasHash Compiled Atlas hash.
 * @param envelopeHash Input envelope hash.
 * @param roleOutputBundleHash Input role-output hash.
 */
@Serializable
data class FieldConsistencyReport(
    val compilerVersion: String,
    val checksPerformed: List<String>,
    val findings: List<ConsistencyFinding>,
    val workerFieldHash: String,
    val graderFieldHash: String,
    val atlasHash: String,
    val envelopeHash: String,
    val roleOutputBundleHash: String
)

/** Kind of material field change requiring an explicit declaration. */
@Serializable
enum class MaterialFieldChangeKind {
    GROUP_COUNT, TOPOLOGY, MODEL_FAMILY, STAGE_COUNT, NODE_LINEAGE, OTHER
}

/**
 * Explicit declaration authorizing a material field revision.
 *
 * @param declarationId Deterministic declaration identifier.
 * @param parentAtlasHash Hash of the field version being changed.
 * @param changeKinds Material categories changed.
 * @param rationale Reason the change is necessary.
 * @param requestedBy Operator or authority requesting the change.
 */
@Serializable
data class FieldChangeDeclaration(
    val declarationId: String,
    val parentAtlasHash: String,
    val changeKinds: Set<MaterialFieldChangeKind>,
    val rationale: String,
    val requestedBy: String
)

/**
 * Explicit record of quality or capability loss caused by a revision.
 *
 * @param recordId Deterministic record identifier.
 * @param declarationId Authorizing declaration.
 * @param affectedCapabilities Capabilities reduced or removed.
 * @param reason Cause of degradation.
 * @param mitigations Applied mitigations.
 */
@Serializable
data class FieldDegradationRecord(
    val recordId: String,
    val declarationId: String,
    val affectedCapabilities: Set<String>,
    val reason: String,
    val mitigations: List<String> = emptyList()
)

/**
 * Cryptographic commitment attached to a compiled StartingField.
 *
 * @param schemaVersion Seal schema version.
 * @param hashAlgorithm Digest algorithm used for every commitment.
 * @param canonicalizationVersion Canonical JSON algorithm version used for field and contract
 *   payloads.
 * @param workerFieldHash Worker field hash excluding its own seal.
 * @param graderFieldHash Grader field hash excluding its own seal.
 * @param atlasHash Atlas hash.
 * @param graderAtlasHash Grader Atlas hash.
 * @param deliverableBlueprintHash Council-sealed receiver deliverable blueprint hash.
 * @param envelopeHash Execution envelope hash.
 * @param consistencyReportHash Consistency report hash.
 */
@Serializable
data class StartingFieldSeal(
    val schemaVersion: String,
    val hashAlgorithm: String = CouncilFieldSealing.HASH_ALGORITHM,
    val canonicalizationVersion: String = CouncilFieldSealing.CANONICALIZATION_VERSION,
    val workerFieldHash: String,
    val graderFieldHash: String,
    val atlasHash: String,
    val graderAtlasHash: String = "",
    val deliverableBlueprintHash: String = "",
    val envelopeHash: String,
    val consistencyReportHash: String
)

/** Explicit lifecycle of one compiled field candidate before and after activation. */
@Serializable
enum class CompiledFieldState {
    CANDIDATE,
    EXECUTABILITY_VERIFIED,
    ACTIVATABLE,
    ACTIVE,
    REJECTED_BEFORE_ACTIVATION
}

/**
 * Complete v3 executable-test authority retained by a valid field compilation.
 *
 * @param privateSuite One common grader-private suite.
 * @param workerCommitmentView Exact worker-safe projection derived from [privateSuite].
 * @param validationContext External receiver, Atlas, test-identity, and promotion-schema authority.
 */
@Serializable
data class CompiledSealedTestAuthorityV3(
    val privateSuite: SealedTestSuiteV3,
    val workerCommitmentView: SealedTestSuiteV3CommitmentView,
    val validationContext: SealedTestSuiteV3ValidationContext
) {
    /** Returns whether the suite and worker projection satisfy their complete external authority. */
    fun isValid(): Boolean =
        SealedTestSuiteV3Authority.isValid(privateSuite, validationContext) &&
            SealedTestSuiteV3Authority.verifyCommitmentView(
                workerCommitmentView,
                privateSuite,
                validationContext
            )

    /** Requires the suite and projection to satisfy their complete external authority. */
    fun requireValid() {
        SealedTestSuiteV3Authority.requireValid(privateSuite, validationContext)
        require(
            SealedTestSuiteV3Authority.verifyCommitmentView(
                workerCommitmentView,
                privateSuite,
                validationContext
            )
        ) {
            "Worker sealed-test commitment view is not derived from the private v3 suite"
        }
    }
}

/** Result of compiling all eight Council outputs against one execution envelope. */
@Serializable
sealed interface StartingFieldCompileResult {
    /**
     * Successfully compiled and sealed fields.
     *
     * @param workerField Worker execution field.
     * @param graderField Isolated grader field.
     * @param receiverContract Exact compiled receiver contract.
     * @param atlas Authoritative Atlas.
     * @param graderAtlas Authoritative competitive grader frontier.
     * @param sealedTests Complete executable v3 suite, projection, and external context.
     * @param report Deterministic consistency report.
     * @param degradationRecords Explicit degradation records for a revision.
     * @param state Candidate lifecycle state; valid compiler output is activatable.
     */
    @Serializable
    data class Valid(
        val workerField: StartingField,
        val graderField: StartingField,
        val receiverContract: ReceiverContract,
        val atlas: NodeAtlas,
        val graderAtlas: GraderAtlas,
        val sealedTests: CompiledSealedTestAuthorityV3,
        val report: FieldConsistencyReport,
        val degradationRecords: List<FieldDegradationRecord> = emptyList(),
        val state: CompiledFieldState = CompiledFieldState.ACTIVATABLE
    ) : StartingFieldCompileResult

    /**
     * Rejected compilation without default replacement.
     *
     * @param errors Complete typed error list.
     * @param responsibleCouncilRoles Roles eligible for retry.
     * @param declaredTestCount Exact Synthesis sealed-test record count when typed Synthesis
     * output was available; zero for pre-Synthesis/decode failures.
     * @param compiledTestCount Exact number of test records compiled before rejection.
     * @param definitionHashesByTestId Canonical source-definition commitments from typed Synthesis
     * output, keyed by sealed-test identity.
     * @param salvageableArtifactIds Valid artifacts that may be retained.
     * @param state Rejected lifecycle state.
     */
    @Serializable
    data class Invalid(
        val errors: List<FieldCompileError>,
        val responsibleCouncilRoles: Set<Role>,
        val declaredTestCount: Int = 0,
        val compiledTestCount: Int = 0,
        val definitionHashesByTestId: Map<String, String> = emptyMap(),
        val salvageableArtifactIds: List<String> = emptyList(),
        val state: CompiledFieldState = CompiledFieldState.REJECTED_BEFORE_ACTIVATION
    ) : StartingFieldCompileResult {
        init {
            require(declaredTestCount >= 0 && compiledTestCount >= 0) {
                "Rejected compile test counts must be non-negative"
            }
            require(compiledTestCount <= declaredTestCount) {
                "Rejected compile count cannot exceed declared count"
            }
            require(definitionHashesByTestId.values.all { it.matches(Regex("[0-9a-f]{64}")) }) {
                "Rejected compile definition hashes must be lowercase SHA-256"
            }
        }
    }
}
