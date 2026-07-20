package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.kg.KgRelationResolver
import com.achulkov.evomolior.core.kg.KgFactLineParser
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validates `KG_FACT` staging submissions against the council-designed KG schema before
 * they are forwarded to the LLM Critic.
 *
 * Per spec §4, agents must use only relation types and entity types defined in the
 * Genesis Council's schema. Unknown relation types or hard constraint violations are caught
 * here (pre-LLM) to avoid wasting API budget on a Critic that would reject the fact anyway.
 *
 * Validation levels:
 * 1. **Relation type check** — RELATION field must be in `schemaRelationTypes` (if non-empty).
 * 2. **Hard constraint check** — submission must not match any hard constraint expression.
 * 3. **Soft constraint check** — submission may pass with a trust penalty when it matches a soft
 *    constraint expression. Full expression evaluation is a later hardening step.
 *
 * If `schemaJson == "{}"` or the schema has no recognised relation types, all facts pass
 * (backward-compatible with runs that have no Genesis Council schema).
 *
 * @param schemaJson JSON-serialised KG schema from the `StartingField`, expected shape:
 *   ```json
 *   {
 *     "relationTypes": ["CAUSES", "IMPLEMENTS", …],
 *     "entityTypes":   ["Concept", "Algorithm", …],
 *     "hardConstraints": [{"id":"HC-001","description":"…","expression":"…"}, …],
 *     "softConstraints": [{"id":"SC-001","description":"…","penaltyFactor":0.2}, …]
 *   }
 *   ```
 *   Also accepts the `KGSchemaEntity` JSON format with `relationTypesJson` /
 *   `hardConstraintsJson` keys (used when the live DB schema is serialised for this check).
 */
class KGConsistencyChecker(private val schemaJson: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Validates [submission] (must be type `KG_FACT`) against the KG schema.
     *
     * @param submission The staging submission to validate.
     * @return [ConsistencyResult.Valid] if the fact passes, or [ConsistencyResult.Invalid]
     *   with a human-readable reason string that the Critic will use as dispute context.
     */
    fun validate(submission: StagingSubmissionEntity): ConsistencyResult {
        // If no schema provided, all facts pass (backward compat)
        if (schemaJson.isBlank() || schemaJson.trim() == "{}") {
            return ConsistencyResult.Valid
        }

        val requestedRelation = extractField(submission.content, "RELATION")
            ?: return ConsistencyResult.Invalid(
                "kg_fact_missing_relation: KG_FACT submission must have a RELATION: field."
            )

        val subject = extractField(submission.content, "SUBJECT")
        val objectNode = extractField(submission.content, "OBJECT")

        val schemaObj: JsonObject = try {
            json.parseToJsonElement(schemaJson).jsonObject
        } catch (e: Exception) {
            Napier.w { "KGConsistencyChecker: failed to parse schema JSON — skipping validation: ${e.message}" }
            return ConsistencyResult.Valid
        }

        // ── 1. Relation type check ─────────────────────────────────────────
        val relationTypes: List<String> = parseStringArray(schemaObj, "relationTypes")
            ?: parseStringArray(schemaObj, "relationTypesJson")
            ?: emptyList()

        val relation = if (relationTypes.isEmpty()) {
            requestedRelation
        } else {
            KgRelationResolver.resolve(requestedRelation, relationTypes)
        }
        if (relation == null) {
            return ConsistencyResult.Invalid(
                "kg_schema_unknown_relation: Relation type \"$requestedRelation\" is not in the council-designed schema. " +
                "Allowed types: ${relationTypes.take(10).joinToString(", ")}${if (relationTypes.size > 10) "…" else ""}. " +
                "Use a schema-compliant relation type or submit a KG_CONSTRAINT to extend the schema."
            )
        }

        // ── 2. Hard constraint check ──────────────────────────────────────
        val hardConstraints: JsonArray? = parseConstraintArray(schemaObj, "hardConstraints", "hardConstraintsJson")

        if (hardConstraints != null) {
            for (constraintEl in hardConstraints) {
                try {
                    val constraint = constraintEl.jsonObject
                    val expression = constraint["expression"]?.jsonPrimitive?.content ?: continue
                    val constraintId = constraint["id"]?.jsonPrimitive?.content ?: "?"
                    val description = constraint["description"]?.jsonPrimitive?.content ?: expression

                    // Heuristic: check if expression contains SUBJECT:RELATION:OBJECT triplet markers
                    // Full expression evaluation (C-priority); for now use keyword matching.
                    if (matchesConstraintExpression(expression, subject, relation, objectNode)) {
                        return ConsistencyResult.Invalid(
                            "kg_hard_constraint_violation[$constraintId]: $description — " +
                            "triplet ($subject, $relation, $objectNode) matches a hard constraint. " +
                            "Revise the fact or raise a KG_CONSTRAINT to update the schema."
                        )
                    }
                } catch (_: Exception) { /* malformed constraint — skip */ }
            }
        }

        val softConstraints: JsonArray? = parseConstraintArray(schemaObj, "softConstraints", "softConstraintsJson")
        val softViolations = softConstraints
            ?.mapNotNull { constraintEl ->
                runCatching {
                    val constraint = constraintEl.jsonObject
                    val expression = constraint["expression"]?.jsonPrimitive?.content ?: return@runCatching null
                    if (!matchesConstraintExpression(expression, subject, relation, objectNode)) return@runCatching null
                    val constraintId = constraint["id"]?.jsonPrimitive?.content ?: "?"
                    val description = constraint["description"]?.jsonPrimitive?.content ?: expression
                    val penalty = (
                        constraint["penaltyFactor"]?.jsonPrimitive?.content?.toDoubleOrNull()
                            ?: constraint["violationPenalty"]?.jsonPrimitive?.content?.toDoubleOrNull()
                            ?: 0.1
                        ).coerceIn(0.0, 0.5)
                    SoftConstraintViolation(
                        constraintId = constraintId,
                        description = description,
                        penalty = penalty
                    )
                }.getOrNull()
            }
            .orEmpty()

        if (softViolations.isNotEmpty()) {
            val penalty = softViolations.sumOf { it.penalty }.coerceIn(0.0, 0.5)
            val reason = softViolations.joinToString("; ") { violation ->
                "kg_soft_constraint_violation[${violation.constraintId}]: ${violation.description}"
            }
            return ConsistencyResult.ValidWithPenalty(
                reason = reason,
                trustScoreReduction = penalty
            )
        }

        return ConsistencyResult.Valid
    }

    /**
     * Extracts a named field from content written in `FIELDNAME: value` line format.
     * Same helper used by [CriticPipeline].
     */
    private fun extractField(content: String, fieldName: String): String? =
        KgFactLineParser.extract(content, fieldName)

    /**
     * Parses a JSON array of strings from [obj] under [key].
     * Returns null if the key is absent or the value is not a JSON array.
     */
    private fun parseStringArray(obj: JsonObject, key: String): List<String>? {
        return try {
            obj[key]?.jsonArray?.mapNotNull {
                try { it.jsonPrimitive.content } catch (_: Exception) { null }
            }?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    private fun parseConstraintArray(obj: JsonObject, directKey: String, encodedKey: String): JsonArray? = try {
        // Accept both direct array and nested JSON-in-string (DB entity format).
        obj[directKey]?.jsonArray
            ?: obj[encodedKey]?.jsonPrimitive?.content?.let {
                json.parseToJsonElement(it).jsonArray
            }
    } catch (_: Exception) {
        null
    }

    /**
     * Heuristic: checks whether a constraint expression is triggered by a given triplet.
     *
     * Supports simple patterns like `RELATION:FORBIDDEN_RELATION_NAME`,
     * `SUBJECT:keyword`, and `OBJECT:keyword`. Full DSL evaluation is C-priority.
     *
     * @param expression The constraint expression string.
     * @param subject The KG fact subject (may be null if not present in submission).
     * @param relation The KG fact relation type.
     * @param objectNode The KG fact object (may be null if not present in submission).
     * @return true if the expression matches, false otherwise.
     */
    private fun matchesConstraintExpression(
        expression: String,
        subject: String?,
        relation: String,
        objectNode: String?
    ): Boolean {
        val expr = expression.trim()
        return when {
            expr.startsWith("RELATION:") -> {
                val forbidden = expr.removePrefix("RELATION:").trim()
                relation.equals(forbidden, ignoreCase = true)
            }
            expr.startsWith("SUBJECT:") -> {
                val keyword = expr.removePrefix("SUBJECT:").trim()
                subject?.contains(keyword, ignoreCase = true) == true
            }
            expr.startsWith("OBJECT:") -> {
                val keyword = expr.removePrefix("OBJECT:").trim()
                objectNode?.contains(keyword, ignoreCase = true) == true
            }
            else -> false // unsupported expression format — do not block
        }
    }
}

private data class SoftConstraintViolation(
    val constraintId: String,
    val description: String,
    val penalty: Double
)

/** Result of a [KGConsistencyChecker.validate] call. */
sealed class ConsistencyResult {
    /** The KG fact passed all schema consistency checks. */
    object Valid : ConsistencyResult()

    /**
     * The KG fact passed hard validation but violated one or more soft constraints.
     *
     * @param reason Explanation persisted with the review context.
     * @param trustScoreReduction Amount subtracted from the Critic's initial trust score.
     */
    data class ValidWithPenalty(
        val reason: String,
        val trustScoreReduction: Double
    ) : ConsistencyResult()

    /**
     * The KG fact violates a schema constraint.
     *
     * @param reason Human-readable explanation to be included as Critic dispute context.
     */
    data class Invalid(val reason: String) : ConsistencyResult()
}
