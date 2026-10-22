package com.achulkov.evomolior.core.contract

import kotlinx.serialization.Serializable

/** Authority effect granted by a gate outcome. */
@Serializable
enum class AuthorityEffect {
    NONE,
    PROVISIONAL_ONLY,
    NODE_EVIDENCE,
    CANONICAL,
    RECEIVER_ACCEPTED,
}

/** Epistemic state retained after a gate evaluates one subject. */
@Serializable
enum class EpistemicState {
    UNASSESSED,
    STRUCTURALLY_INVALID,
    REPAIRABLE,
    PROVISIONAL,
    CONTESTED,
    FALSIFIED,
    VALIDATED,
    SUPERSEDED,
}

/** Retention level assigned to material that did not pass a gate. */
@Serializable
enum class RetentionDisposition {
    PRESERVE_FULL,
    PRESERVE_ATOMIZED,
    PRESERVE_DIAGNOSTIC_ONLY,
    QUARANTINE,
    SECRET_DROP,
}

/** Scope in which retained material may be reused. */
@Serializable
enum class ReuseScope {
    NONE,
    REPAIR_ONLY,
    SUCCESSOR_ONLY,
    INDEPENDENT_VALIDATION,
    RECEIVER_REVIEW,
}

/** Typed, receiver-safe diagnostic attached to a gate outcome. */
@Serializable
data class TypedDiagnostic(
    val code: String,
    val publicMessage: String,
    val authorityHash: String,
) {
    init {
        require(code.isNotBlank() && publicMessage.isNotBlank()) {
            "Typed diagnostic fields must not be blank"
        }
        require(authorityHash.matches(Regex("[0-9a-f]{64}"))) {
            "Typed diagnostic authority must be a SHA-256 hash"
        }
    }
}

/** Publicly reusable unit salvaged from a non-passed gate. */
@Serializable
data class SalvageUnit(
    val unitId: String,
    val kind: String,
    val contentHash: String,
    val publicSummary: String? = null,
) {
    init {
        require(unitId.isNotBlank() && kind.isNotBlank()) {
            "Salvage unit identity must not be blank"
        }
        require(contentHash.matches(Regex("[0-9a-f]{64}"))) {
            "Salvage unit content must be a SHA-256 hash"
        }
        require(publicSummary?.isNotBlank() != false) {
            "Salvage unit summary must be absent or non-blank"
        }
    }
}

/** Value-preserving result of one substantive gate evaluation. */
@Serializable
data class GateOutcomeV2(
    val gateId: String,
    val subjectRef: String,
    val authorityEffect: AuthorityEffect,
    val epistemicState: EpistemicState,
    val retentionDisposition: RetentionDisposition,
    val reuseScope: ReuseScope,
    val diagnostics: List<TypedDiagnostic> = emptyList(),
    val salvageUnits: List<SalvageUnit> = emptyList(),
    val recommendedNextAction: NextAction? = null,
) {
    init {
        require(gateId.isNotBlank() && subjectRef.isNotBlank()) {
            "Gate outcome identity must not be blank"
        }
        require(diagnostics.map { it.code }.distinct().size == diagnostics.size) {
            "Gate outcome diagnostic codes must be unique"
        }
        require(salvageUnits.map { it.unitId }.distinct().size == salvageUnits.size) {
            "Gate outcome salvage unit IDs must be unique"
        }
        require(
            retentionDisposition == RetentionDisposition.SECRET_DROP ||
                reuseScope != ReuseScope.RECEIVER_REVIEW ||
                authorityEffect == AuthorityEffect.RECEIVER_ACCEPTED ||
                epistemicState == EpistemicState.VALIDATED
        ) {
            "Receiver review reuse requires validated or accepted material"
        }
    }
}

/** Next action that can turn retained evidence into stronger authority. */
@Serializable
enum class NextAction {
    REPAIR_SAME_AGENT,
    SPAWN_SUCCESSOR,
    INDEPENDENT_VALIDATE,
    CONTRADICTION_TEST,
    ARCHIVE_DEAD_END,
    DEFER,
}
