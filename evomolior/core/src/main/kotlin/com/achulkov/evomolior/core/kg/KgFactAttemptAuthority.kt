@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.kg

import com.achulkov.evomolior.core.atlas.AtlasHashing
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Terminal classification assigned to one attempted `KG_FACT` submission. */
@Serializable
enum class KgFactAttemptStatus {
    /** All required line fields exist and the relation resolves against live schema authority. */
    SCHEMA_RESOLVED,

    /** A required field is absent or the requested relation is not schema-authorized. */
    SYNTAX_REJECTED
}

/** Typed reason why an attempted `KG_FACT` could not be schema-resolved. */
@Serializable
enum class KgFactAttemptFailureCode {
    MISSING_SUBJECT,
    MISSING_RELATION,
    MISSING_OBJECT,
    UNKNOWN_RELATION
}

/**
 * Exact fields parsed from the canonical `FIELD: value` KG fact line format.
 *
 * @param subject First non-blank `SUBJECT:` line value.
 * @param requestedRelation First non-blank `RELATION:` line value.
 * @param objectNode First non-blank `OBJECT:` line value.
 */
@Serializable
data class KgFactLineFields(
    val subject: String?,
    val requestedRelation: String?,
    val objectNode: String?
)

/**
 * Canonical parser for KG fact line fields shared by instrumentation and promotion.
 */
object KgFactLineParser {
    /**
     * Parses required KG fact fields without normalizing the submission body.
     *
     * Field names remain case-sensitive and the first matching line wins, preserving the
     * established staging and promotion semantics.
     *
     * @param content Exact staging submission content.
     * @return Parsed required fields.
     */
    fun parse(content: String): KgFactLineFields = KgFactLineFields(
        subject = extract(content, "SUBJECT"),
        requestedRelation = extract(content, "RELATION"),
        objectNode = extract(content, "OBJECT")
    )

    /**
     * Extracts one optional field using the canonical line semantics.
     *
     * @param content Exact staging submission content.
     * @param fieldName Case-sensitive field name without the trailing colon.
     * @return Trimmed non-blank value, or null.
     */
    fun extract(content: String, fieldName: String): String? =
        content.lines()
            .firstOrNull { it.startsWith("$fieldName:") }
            ?.removePrefix("$fieldName:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}

/**
 * Immutable authority record for one attempted `KG_FACT` submission.
 *
 * @param attemptId Deterministic identity derived from run, group, and submission identity.
 * @param runId Owning run.
 * @param groupId Owning per-group substrate.
 * @param submissionId Staging submission identity.
 * @param agentId Submitting agent.
 * @param generation Submission generation.
 * @param submissionContentHash SHA-256 of the exact, unnormalized submission content.
 * @param status Terminal syntax/schema classification.
 * @param requestedRelation Exact parsed relation requested by the agent, when present.
 * @param resolvedRelation Exact live-schema spelling, including alias resolution, when accepted.
 * @param failureCode Typed rejection reason, when rejected.
 * @param recordedAt Deterministic attempt timestamp supplied by the staging authority.
 * @param recordHash Deterministic self-hash over every other field.
 */
@Serializable
data class KgFactAttemptRecord(
    val attemptId: String,
    val runId: String,
    val groupId: String,
    val submissionId: String,
    val agentId: String,
    val generation: Int,
    val submissionContentHash: String,
    val status: KgFactAttemptStatus,
    val requestedRelation: String?,
    val resolvedRelation: String?,
    val failureCode: KgFactAttemptFailureCode?,
    val recordedAt: Instant,
    val recordHash: String = ""
) {
    init {
        require(runId.isNotBlank()) { "KG fact attempt runId must not be blank" }
        require(groupId.isNotBlank()) { "KG fact attempt groupId must not be blank" }
        require(submissionId.isNotBlank()) { "KG fact attempt submissionId must not be blank" }
        require(agentId.isNotBlank()) { "KG fact attempt agentId must not be blank" }
        require(generation >= 0) { "KG fact attempt generation must not be negative" }
        require(
            (status == KgFactAttemptStatus.SCHEMA_RESOLVED &&
                resolvedRelation != null && failureCode == null) ||
                (status == KgFactAttemptStatus.SYNTAX_REJECTED &&
                    resolvedRelation == null && failureCode != null)
        ) {
            "KG fact attempt resolution and failure must agree with status"
        }
    }

    /** Returns this record with its deterministic self-hash attached. */
    fun withComputedHash(): KgFactAttemptRecord =
        copy(recordHash = AtlasHashing.hashKgFactAttempt(this))

    /** Verifies the exact submission-content and record commitments. */
    fun hasValidHashes(content: String): Boolean =
        AtlasHashing.constantTimeEquals(
            submissionContentHash,
            AtlasHashing.hashText(content)
        ) && AtlasHashing.constantTimeEquals(
            recordHash,
            AtlasHashing.hashKgFactAttempt(this)
        )
}

/** Creates deterministic typed authority records for attempted KG facts. */
object KgFactAttemptAuthority {
    /**
     * Classifies one exact submission against the supplied live relation schema.
     *
     * An empty relation schema preserves the existing schema-less behavior by accepting the
     * requested relation unchanged. Non-empty schemas resolve exclusively through
     * [KgRelationResolver].
     *
     * @param runId Owning run.
     * @param groupId Owning group.
     * @param submissionId Staging submission identity.
     * @param agentId Submitting agent.
     * @param generation Submission generation.
     * @param content Exact staging content.
     * @param allowedRelations Live authorized relation names.
     * @param recordedAt Stable attempt timestamp.
     * @return Hashed immutable attempt record.
     */
    fun classify(
        runId: String,
        groupId: String,
        submissionId: String,
        agentId: String,
        generation: Int,
        content: String,
        allowedRelations: Collection<String>,
        recordedAt: Instant
    ): KgFactAttemptRecord {
        val fields = KgFactLineParser.parse(content)
        val failure = when {
            fields.subject == null -> KgFactAttemptFailureCode.MISSING_SUBJECT
            fields.requestedRelation == null -> KgFactAttemptFailureCode.MISSING_RELATION
            fields.objectNode == null -> KgFactAttemptFailureCode.MISSING_OBJECT
            else -> null
        }
        val resolvedRelation = if (failure != null) {
            null
        } else if (allowedRelations.isEmpty()) {
            fields.requestedRelation
        } else {
            KgRelationResolver.resolve(fields.requestedRelation!!, allowedRelations)
        }
        val finalFailure = failure ?: if (resolvedRelation == null) {
            KgFactAttemptFailureCode.UNKNOWN_RELATION
        } else {
            null
        }
        val identityMaterial = listOf(runId, groupId, submissionId).joinToString("\u0000")
        return KgFactAttemptRecord(
            attemptId = "kg_fact_attempt_${AtlasHashing.hashText(identityMaterial)}",
            runId = runId,
            groupId = groupId,
            submissionId = submissionId,
            agentId = agentId,
            generation = generation,
            submissionContentHash = AtlasHashing.hashText(content),
            status = if (finalFailure == null) {
                KgFactAttemptStatus.SCHEMA_RESOLVED
            } else {
                KgFactAttemptStatus.SYNTAX_REJECTED
            },
            requestedRelation = fields.requestedRelation,
            resolvedRelation = resolvedRelation,
            failureCode = finalFailure,
            recordedAt = recordedAt
        ).withComputedHash()
    }
}
