package com.achulkov.evokammer.orchestration.kg

import com.achulkov.evokammer.core.contract.CriticOutcome
import com.achulkov.evokammer.substrate.db.dao.KnowledgeBaseDao
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable

/**
 * KG Consistency Checker — validates staged KG facts and constraints against the
 * existing KG schema before they reach the full LLM-based Critic.
 *
 * Validation steps (per spec):
 * 1. **Schema compliance** — subject and object must be non-empty; relation must be known.
 * 2. **Allowed entity type check** — entity types must conform to the registered schema types.
 * 3. **Soft constraint check** — violation of soft constraints reduces trust score.
 * 4. **Conflict detection** — if a fact contradicts a higher-trust promoted fact → DISPUTE.
 *
 * This checker is invoked by the Critic pipeline before the LLM-based Critic review.
 * Death Notes and non-KG submissions bypass this checker.
 *
 * @param knowledgeBaseDao For querying existing promoted KG entries.
 * @param allowedRelations Optional set of relation types from KG schema. Empty = allow all.
 * @param allowedEntityTypes Optional set of entity types from KG schema. Empty = allow all.
 * @param softConstraints Named soft constraints: id → (description, penaltyReduction).
 */
class KGConsistencyChecker(
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val allowedRelations: Set<String> = emptySet(),
    private val allowedEntityTypes: Set<String> = emptySet(),
    private val softConstraints: Map<String, SoftConstraint> = emptyMap()
) {

    /**
     * Checks a staged [submission] of type KG_FACT or KG_CONSTRAINT.
     *
     * @return A [ConsistencyResult] indicating whether the submission should proceed,
     *         be rejected outright, or be flagged for arbitration.
     */
    suspend fun check(
        submission: StagingSubmissionEntity,
        runId: String,
        groupId: String
    ): ConsistencyResult {
        // Only applies to KG fact/constraint submissions
        if (submission.type !in setOf("KG_FACT", "KG_CONSTRAINT")) {
            return ConsistencyResult.Pass
        }

        val content = submission.content

        // ── 1. Parse fact fields ──────────────────────────────────────────────────────
        val subject = extractField(content, "SUBJECT") ?: return ConsistencyResult.Reject(
            "Missing SUBJECT field in ${submission.type} submission."
        )
        val relation = extractField(content, "RELATION") ?: return ConsistencyResult.Reject(
            "Missing RELATION field in ${submission.type} submission."
        )
        val obj = extractField(content, "OBJECT") ?: return ConsistencyResult.Reject(
            "Missing OBJECT field in ${submission.type} submission."
        )
        val confidence = extractField(content, "CONFIDENCE")?.toDoubleOrNull() ?: 0.5

        // ── 2. Schema compliance — relation type check ────────────────────────────────
        if (allowedRelations.isNotEmpty() && relation !in allowedRelations) {
            Napier.w("KGConsistencyChecker: REJECT — unknown relation '$relation' for ${submission.id}")
            return ConsistencyResult.Reject(
                "Relation '$relation' is not in the KG schema. Allowed: ${allowedRelations.take(10).joinToString()}"
            )
        }

        // ── 3. Entity type check ──────────────────────────────────────────────────────
        // Entity type can optionally be encoded as "entity:type" in the subject/object fields
        val subjectType = subject.substringAfterLast(":", "").takeIf { it.isNotBlank() }
        val objectType = obj.substringAfterLast(":", "").takeIf { it.isNotBlank() }
        if (allowedEntityTypes.isNotEmpty()) {
            if (subjectType != null && subjectType !in allowedEntityTypes) {
                return ConsistencyResult.Reject(
                    "Subject entity type '$subjectType' is not in the KG schema."
                )
            }
            if (objectType != null && objectType !in allowedEntityTypes) {
                return ConsistencyResult.Reject(
                    "Object entity type '$objectType' is not in the KG schema."
                )
            }
        }

        // ── 4. Soft constraint evaluation ─────────────────────────────────────────────
        val softViolations = mutableListOf<String>()
        var totalPenalty = 0.0
        for ((constraintId, constraint) in softConstraints) {
            if (constraint.matches(subject, relation, obj)) {
                softViolations.add(constraintId)
                totalPenalty += constraint.penaltyReduction
                Napier.d("KGConsistencyChecker: soft violation '$constraintId' for ${submission.id}")
            }
        }

        // ── 5. Conflict detection ─────────────────────────────────────────────────────
        val existingFacts = knowledgeBaseDao.search(runId, groupId, "$subject $relation", 10)
            .filter { it.domain == "kg" && it.trustScore > confidence }

        val conflictingIds = existingFacts
            .filter { existingEntry ->
                val existingObj = extractField(existingEntry.content, "OBJECT")
                val existingRelation = extractField(existingEntry.content, "RELATION")
                existingRelation == relation &&
                    existingObj != null &&
                    existingObj != obj
            }
            .map { it.id }

        if (conflictingIds.isNotEmpty()) {
            Napier.i("KGConsistencyChecker: DISPUTE — ${submission.id} conflicts with ${conflictingIds.size} higher-trust facts")
            return ConsistencyResult.Dispute(
                conflictingFactIds = conflictingIds,
                reason = "Proposed fact contradicts ${conflictingIds.size} higher-trust fact(s) with the same subject+relation."
            )
        }

        // ── 6. Pass ───────────────────────────────────────────────────────────────────
        return if (softViolations.isEmpty()) {
            ConsistencyResult.Pass
        } else {
            ConsistencyResult.PassWithPenalty(
                trustScoreReduction = totalPenalty.coerceAtMost(0.5),
                violatedConstraints = softViolations
            )
        }
    }

    private fun extractField(content: String, fieldName: String): String? =
        content.lines()
            .firstOrNull { it.startsWith("$fieldName:") }
            ?.removePrefix("$fieldName:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}

/**
 * A named soft constraint for KG validation.
 *
 * @param description Human-readable constraint description.
 * @param penaltyReduction Trust score reduction applied when violated (0.0–0.5).
 * @param requiredRelations If non-empty, the constraint triggers when the relation is in this set.
 * @param forbiddenRelations If non-empty, the constraint triggers when the relation is in this set.
 */
@Serializable
data class SoftConstraint(
    val description: String,
    val penaltyReduction: Double = 0.1,
    val requiredRelations: Set<String> = emptySet(),
    val forbiddenRelations: Set<String> = emptySet()
) {
    /** Returns true if this constraint is violated by the given (subject, relation, obj) triple. */
    fun matches(subject: String, relation: String, obj: String): Boolean {
        if (forbiddenRelations.isNotEmpty() && relation in forbiddenRelations) return true
        return false // Additional heuristic checks can be added here
    }
}

/**
 * Result of a [KGConsistencyChecker] check.
 */
@Serializable
sealed class ConsistencyResult {
    /** Submission passes all checks cleanly. */
    @Serializable data object Pass : ConsistencyResult()

    /** Submission passes but with a reduced initial trust score. */
    @Serializable data class PassWithPenalty(
        val trustScoreReduction: Double,
        val violatedConstraints: List<String>
    ) : ConsistencyResult()

    /** Submission contradicts a higher-trust fact — needs KG-Critic arbitration. */
    @Serializable data class Dispute(
        val conflictingFactIds: List<String>,
        val reason: String
    ) : ConsistencyResult()

    /** Submission violates a hard schema constraint — outright reject. */
    @Serializable data class Reject(val reason: String) : ConsistencyResult()

    /** Convenience: maps to the corresponding [CriticOutcome]. */
    fun toCriticOutcome(): CriticOutcome = when (this) {
        is Pass -> CriticOutcome.PROMOTE
        is PassWithPenalty -> CriticOutcome.PROMOTE
        is Dispute -> CriticOutcome.DISPUTE
        is Reject -> CriticOutcome.REJECT
    }
}
