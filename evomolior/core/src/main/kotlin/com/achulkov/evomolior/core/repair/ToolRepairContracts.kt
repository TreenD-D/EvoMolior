package com.achulkov.evomolior.core.repair

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Version identifier for typed tool-repair diagnostics. */
const val TOOL_REPAIR_DIAGNOSTIC_SCHEMA_V1: String = "tool-repair-diagnostic-v1"

/** Authority that produced a typed repair diagnostic. */
@Serializable
enum class ToolRepairSource {
    PUBLIC_PREFLIGHT,
    CRITIC_SEMANTIC_REVIEW,
    PRIVATE_PROMOTION_AUTHORITY
}

/** Maximum information that may be projected from a repair diagnostic. */
@Serializable
enum class ToolRepairDisclosureClass {
    PUBLIC_EXACT,
    SAFE_SEMANTIC_CATEGORY,
    PRIVATE_FAILURE_ONLY
}

/** Closed semantic and contract repair taxonomy from the completion blueprint. */
@Serializable
enum class ToolRepairCode {
    CONTRACT_SCHEMA_MISMATCH,
    MISSING_REQUIRED_CONTRACT_FIELD,
    NON_CANONICAL_NUMBER,
    PUBLIC_TEST_EXPECTED_OUTPUT_MISMATCH,
    INPUT_NOT_INFLUENCING_OUTPUT,
    POWER_TO_ENERGY_UNIT_CONVERSION,
    RATE_VERSUS_TOTAL_CONFUSION,
    AGGREGATION_WINDOW_MISMATCH,
    DIMENSIONAL_MISMATCH,
    PERCENT_NORMALIZATION_ERROR,
    SIGN_CONVENTION_ERROR,
    TEMPORAL_INDEX_ALIGNMENT_ERROR,
    BOUNDARY_CONDITION_ERROR,
    SCOPE_MISMATCH,
    OUTPUT_SEMANTICS_MISMATCH,
    PRIVATE_TEST_FAILED_UNCLASSIFIED
}

/** Closed aggregation meaning for a numeric quantity. */
@Serializable
enum class AggregationSemantics {
    INSTANTANEOUS,
    PER_INTERVAL_RATE,
    SUM_OVER_INTERVALS,
    AVERAGE_OVER_INTERVALS,
    MAXIMUM
}

/** Closed public time basis used by quantity metadata. */
@Serializable
enum class TimeBasis {
    SECOND,
    MINUTE,
    HOUR,
    DAY
}

/**
 * Public unit and aggregation semantics for one numeric field.
 *
 * @param dimension Canonical physical dimension.
 * @param unitId Closed public unit identifier.
 * @param scaleToSi Multiplicative scale to the catalog SI representation.
 * @param aggregation How values aggregate over observations.
 * @param timeBasis Public interval basis when aggregation is interval-dependent.
 */
@Serializable
data class QuantitySpec(
    val dimension: PhysicalDimension,
    val unitId: String,
    val scaleToSi: Double,
    val aggregation: AggregationSemantics,
    val timeBasis: TimeBasis? = null
) {
    init {
        require(scaleToSi.isFinite() && scaleToSi > 0.0) {
            "Quantity scaleToSi must be finite and positive"
        }
        require(
            (aggregation in INTERVAL_AGGREGATIONS) == (timeBasis != null)
        ) {
            "Exactly interval-based quantities must declare a time basis"
        }
        ToolUnitCatalog.requireCompatible(this)
    }

    private companion object {
        val INTERVAL_AGGREGATIONS = setOf(
            AggregationSemantics.PER_INTERVAL_RATE,
            AggregationSemantics.SUM_OVER_INTERVALS,
            AggregationSemantics.AVERAGE_OVER_INTERVALS
        )
    }
}

/** Closed public unit catalog used by deterministic semantic compatibility checks. */
object ToolUnitCatalog {
    private val power = PhysicalDimension(mapOf("power" to 1))
    private val energy = PhysicalDimension(mapOf("energy" to 1))
    private val temperature = PhysicalDimension(mapOf("temperature" to 1))
    private val time = PhysicalDimension(mapOf("time" to 1))
    private val area = PhysicalDimension(mapOf("length" to 2))
    private val heatTransferCoefficient = PhysicalDimension(
        mapOf("power" to 1, "length" to -2, "temperature" to -1)
    )
    private val dimensionless = PhysicalDimension()

    private val entries = mapOf(
        "1" to UnitDefinition(dimensionless, 1.0),
        "%" to UnitDefinition(dimensionless, 0.01),
        "W" to UnitDefinition(power, 1.0),
        "kW" to UnitDefinition(power, 1_000.0),
        "Wh" to UnitDefinition(energy, 3_600.0),
        "kWh" to UnitDefinition(energy, 3_600_000.0),
        "m2" to UnitDefinition(area, 1.0),
        "W/(m2*K)" to UnitDefinition(heatTransferCoefficient, 1.0),
        "s" to UnitDefinition(time, 1.0),
        "min" to UnitDefinition(time, 60.0),
        "h" to UnitDefinition(time, 3_600.0),
        "C" to UnitDefinition(temperature, 1.0),
        "K" to UnitDefinition(temperature, 1.0)
    )

    /** Returns whether [quantity] exactly agrees with the checked-in unit catalog. */
    fun isCompatible(quantity: QuantitySpec): Boolean =
        entries[quantity.unitId]?.let { definition ->
            definition.dimension == quantity.dimension &&
                definition.scaleToSi == quantity.scaleToSi
        } == true

    /** Fails closed when [quantity] uses unknown or contradictory unit metadata. */
    fun requireCompatible(quantity: QuantitySpec) {
        require(isCompatible(quantity)) {
            "Quantity unit '${quantity.unitId}' contradicts the closed unit catalog"
        }
    }

    private data class UnitDefinition(
        val dimension: PhysicalDimension,
        val scaleToSi: Double
    )
}

/** Typed Critic verdict for a validated tool candidate. */
@Serializable
enum class ToolSemanticVerdict {
    PROMOTE,
    REJECT,
    DISPUTE
}

/**
 * Publicly projectable finding containing no free-form prose or private values.
 *
 * @param code Closed repair category.
 * @param affectedPublicField Optional already-public field handle.
 * @param remediationKey Checked-in remediation dictionary key.
 * @param quantitySpec Optional already-public quantity metadata for the affected field.
 */
@Serializable
data class PublicToolFinding(
    val code: ToolRepairCode,
    val affectedPublicField: String? = null,
    val remediationKey: String = ToolRepairRemediationDictionary.keyFor(code),
    val quantitySpec: QuantitySpec? = null
) {
    init {
        require(
            affectedPublicField == null ||
                affectedPublicField.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
        ) { "Repair findings may reference only public field handles" }
        require(remediationKey == ToolRepairRemediationDictionary.keyFor(code)) {
            "Repair findings must use the checked-in remediation dictionary"
        }
    }
}

/**
 * Typed semantic review. Private rationale is represented only by a commitment.
 *
 * @param verdict Closed semantic verdict.
 * @param publicFindings Bounded findings safe for public projection.
 * @param safeRepairCodes Exact closed codes asserted by the review.
 * @param privateRationaleCommitment Hash of private review material.
 */
@Serializable
data class ToolSemanticReview(
    val verdict: ToolSemanticVerdict,
    val publicFindings: List<PublicToolFinding>,
    val safeRepairCodes: Set<ToolRepairCode>,
    val privateRationaleCommitment: String
) {
    init {
        require(privateRationaleCommitment.isSha256()) {
            "Semantic review requires a private rationale commitment"
        }
        require(publicFindings.size <= MAX_PUBLIC_FINDINGS) {
            "Semantic review exceeds the bounded public finding count"
        }
        require(publicFindings.map { it.code to it.affectedPublicField }.distinct().size ==
            publicFindings.size) {
            "Semantic review public findings must be unique"
        }
        require(publicFindings.mapTo(linkedSetOf(), PublicToolFinding::code) == safeRepairCodes) {
            "Semantic review safe codes must exactly match its public findings"
        }
    }

    companion object {
        const val MAX_PUBLIC_FINDINGS = 8
    }
}

/**
 * Versioned typed repair authority. It deliberately contains no rationale prose, fixture identity,
 * path, URL, stack trace, private value, or implementation body.
 */
@Serializable
data class ToolRepairDiagnosticV1(
    val schemaVersion: String = TOOL_REPAIR_DIAGNOSTIC_SCHEMA_V1,
    val diagnosticId: String,
    val runId: String,
    val groupId: String,
    val nodeId: String,
    val opportunityId: String,
    val opportunityHash: String,
    val candidateHash: String,
    val source: ToolRepairSource,
    val disclosureClass: ToolRepairDisclosureClass,
    val code: ToolRepairCode,
    val createdRound: Int,
    val visibleFromRound: Int,
    val affectedPublicField: String? = null,
    val publicQuantitySpec: QuantitySpec? = null,
    val rationaleCommitment: String? = null,
    val diagnosticHash: String = ""
) {
    init {
        require(schemaVersion == TOOL_REPAIR_DIAGNOSTIC_SCHEMA_V1) {
            "Unsupported tool-repair diagnostic schema"
        }
        require(
            diagnosticId.isNotBlank() && runId.isNotBlank() && groupId.isNotBlank() &&
                nodeId.isNotBlank() && opportunityId.isNotBlank()
        ) { "Tool-repair diagnostic identity must not be blank" }
        require(opportunityHash.isSha256() && candidateHash.isSha256()) {
            "Tool-repair diagnostic authority hashes are invalid"
        }
        require(createdRound >= 0 && visibleFromRound > createdRound) {
            "Tool-repair diagnostics become visible only in a later round"
        }
        require(
            affectedPublicField == null ||
                affectedPublicField.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
        ) { "Tool-repair diagnostics may reference only public field handles" }
        require(rationaleCommitment == null || rationaleCommitment.isSha256()) {
            "Tool-repair rationale commitment is invalid"
        }
        require(
            source != ToolRepairSource.PRIVATE_PROMOTION_AUTHORITY ||
                disclosureClass != ToolRepairDisclosureClass.PUBLIC_EXACT
        ) { "Private promotion authority cannot produce an exact public diagnostic" }
        require(
            disclosureClass != ToolRepairDisclosureClass.PRIVATE_FAILURE_ONLY ||
                (
                    affectedPublicField == null &&
                        publicQuantitySpec == null &&
                        code == ToolRepairCode.PRIVATE_TEST_FAILED_UNCLASSIFIED
                    )
        ) { "Private-only failures cannot carry projected semantic detail" }
    }

    /** Returns this diagnostic with its deterministic self-hash attached. */
    fun withComputedHash(): ToolRepairDiagnosticV1 =
        copy(diagnosticHash = RepairHashing.hashDiagnostic(this))

    /** Returns whether [diagnosticHash] commits this exact typed diagnostic. */
    fun hasValidHash(): Boolean =
        diagnosticHash.isSha256() &&
            AtlasHashing.constantTimeEquals(
                diagnosticHash,
                RepairHashing.hashDiagnostic(this)
            )

    /**
     * Returns whether this diagnostic may enter a mortal worker's bounded repair context.
     *
     * Private-only failures remain durable audit authority but cannot influence worker behavior.
     */
    fun isWorkerProjectable(): Boolean =
        disclosureClass != ToolRepairDisclosureClass.PRIVATE_FAILURE_ONLY
}

/** Deterministic preflight status retained in repair-attempt lineage. */
@Serializable
enum class ToolValidationStatus {
    PASSED,
    REJECTED
}

/** Structured public/private promotion result retained in repair-attempt lineage. */
@Serializable
enum class ToolPromotionStatus {
    NOT_ATTEMPTED,
    PROMOTED,
    REJECTED_PUBLIC_AUTHORITY,
    REJECTED_PRIVATE_AUTHORITY
}

/**
 * Causal lineage for one bounded repair candidate.
 *
 * @param attemptId Stable attempt identity.
 * @param opportunityId Sealed opportunity identity.
 * @param candidateHash Exact Workstream B candidate commitment.
 * @param authorAgentId Mortal author identity.
 * @param round Candidate round.
 * @param consumedDiagnosticIds Exact visible diagnostic IDs claimed by the candidate.
 * @param preflightResult Deterministic public preflight result.
 * @param criticResult Optional typed semantic Critic result.
 * @param promotionResult Structured promotion outcome.
 * @param approachVector Exact sealed strategy authority used before this repair candidate.
 * @param attemptHash Deterministic self-hash.
 */
@Serializable
data class ToolRepairAttemptRecord(
    val attemptId: String,
    val opportunityId: String,
    val candidateHash: String,
    val authorAgentId: String,
    val round: Int,
    val consumedDiagnosticIds: List<String>,
    val preflightResult: ToolValidationStatus,
    val criticResult: ToolSemanticVerdict?,
    val promotionResult: ToolPromotionStatus,
    val approachVector: ApproachVector? = null,
    val attemptHash: String = ""
) {
    init {
        require(
            attemptId.isNotBlank() && opportunityId.isNotBlank() &&
                candidateHash.isSha256() && authorAgentId.isNotBlank()
        ) { "Tool-repair attempt authority is incomplete" }
        require(round >= 0) { "Tool-repair attempt round must not be negative" }
        require(consumedDiagnosticIds.none(String::isBlank)) {
            "Consumed repair diagnostic IDs must not be blank"
        }
        require(consumedDiagnosticIds == consumedDiagnosticIds.distinct().sorted()) {
            "Consumed repair diagnostic IDs must be unique and sorted"
        }
        require(consumedDiagnosticIds.size <= MAX_CONSUMED_DIAGNOSTICS) {
            "Repair attempts may consume at most three diagnostics"
        }
        require(
            preflightResult == ToolValidationStatus.PASSED ||
                (
                    criticResult == null &&
                        promotionResult == ToolPromotionStatus.NOT_ATTEMPTED
                    )
        ) { "Rejected preflight cannot have Critic or promotion authority" }
        require(
            approachVector == null ||
                (
                    approachVector.hasValidHash() &&
                        approachVector.opportunityId == opportunityId &&
                        approachVector.round == round &&
                        consumedDiagnosticIds.isNotEmpty()
                    )
        ) {
            "Repair attempt approach authority must match its opportunity, round, and diagnostics"
        }
    }

    /** Returns this attempt with its deterministic self-hash attached. */
    fun withComputedHash(): ToolRepairAttemptRecord =
        copy(attemptHash = RepairHashing.hashAttempt(this))

    /** Returns whether [attemptHash] commits this exact repair attempt. */
    fun hasValidHash(): Boolean =
        attemptHash.isSha256() &&
            AtlasHashing.constantTimeEquals(attemptHash, RepairHashing.hashAttempt(this))

    companion object {
        const val MAX_CONSUMED_DIAGNOSTICS = 3
    }
}

/** Sealed strategy families used to prevent correlated retries between competing groups. */
@Serializable
enum class RepairStrategy {
    DIMENSIONAL_ANALYSIS_FIRST,
    PROPERTY_TEST_FIRST,
    REFERENCE_CALCULATION_FIRST
}

/**
 * Pre-candidate strategy commitment for one group and repair lineage.
 *
 * @param vectorId Stable vector identity.
 * @param runId Owning run.
 * @param groupId Assigned group.
 * @param opportunityId Sealed opportunity.
 * @param round Repair round.
 * @param lineageAttempt Attempt number in `1..3`.
 * @param strategy Closed strategy family.
 * @param vectorHash Deterministic self-hash.
 */
@Serializable
data class ApproachVector(
    val vectorId: String,
    val runId: String,
    val groupId: String,
    val opportunityId: String,
    val round: Int,
    val lineageAttempt: Int,
    val strategy: RepairStrategy,
    val vectorHash: String = ""
) {
    init {
        require(
            vectorId.isNotBlank() && runId.isNotBlank() &&
                groupId.isNotBlank() && opportunityId.isNotBlank()
        ) { "Repair approach-vector identity must not be blank" }
        require(round >= 0 && lineageAttempt in 1..MAX_LINEAGE_ATTEMPTS) {
            "Repair approach vectors are bounded to three lineage attempts"
        }
    }

    /** Returns this vector with its deterministic self-hash attached. */
    fun withComputedHash(): ApproachVector =
        copy(vectorHash = RepairHashing.hashApproach(this))

    /** Returns whether [vectorHash] commits this exact strategy vector. */
    fun hasValidHash(): Boolean =
        vectorHash.isSha256() &&
            AtlasHashing.constantTimeEquals(vectorHash, RepairHashing.hashApproach(this))

    companion object {
        /** Maximum number of repair attempts for one opportunity lineage. */
        const val MAX_LINEAGE_ATTEMPTS: Int = 3
    }
}

/** Checked-in remediation keys; worker prompts render keys, never arbitrary diagnostic prose. */
object ToolRepairRemediationDictionary {
    private val keys = ToolRepairCode.entries.associateWith { code ->
        "tool_repair.${code.name.lowercase()}"
    }

    /** Returns the stable worker-visible remediation key for [code]. */
    fun keyFor(code: ToolRepairCode): String = keys.getValue(code)
}

/** Canonical hashing for typed repair contracts. */
object RepairHashing {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    /** Hashes a diagnostic while excluding its self-referential hash. */
    fun hashDiagnostic(diagnostic: ToolRepairDiagnosticV1): String =
        hashWithoutField(
            ToolRepairDiagnosticV1.serializer(),
            diagnostic,
            "diagnosticHash"
        )

    /** Hashes a repair attempt while excluding its self-referential hash. */
    fun hashAttempt(attempt: ToolRepairAttemptRecord): String =
        hashWithoutField(ToolRepairAttemptRecord.serializer(), attempt, "attemptHash")

    /** Hashes a strategy vector while excluding its self-referential hash. */
    fun hashApproach(vector: ApproachVector): String =
        hashWithoutField(ApproachVector.serializer(), vector, "vectorHash")

    private fun <T> hashWithoutField(
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
        field: String
    ): String {
        val element = json.encodeToJsonElement(serializer, value)
        val withoutHash = JsonObject(element.jsonObject.filterKeys { it != field })
        return AtlasHashing.hashText(AtlasHashing.canonicalJson(withoutHash))
    }
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
