@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.deliverable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Current schema identifier for deliverable blueprints. */
const val DELIVERABLE_BLUEPRINT_SCHEMA_V1: String = "deliverable-blueprint-v1"

/** Current schema identifier for receiver decision packages. */
const val RECEIVER_DECISION_PACKAGE_SCHEMA_V1: String = "receiver-decision-package-v1"

/** Current schema identifier for interim deliverable snapshots. */
const val INTERIM_DELIVERABLE_SNAPSHOT_SCHEMA_V1: String = "interim-deliverable-snapshot-v1"

/** Prefix required on a provisional decision/use caveat. */
const val PROVISIONAL_DECISION_OR_USE_PREFIX: String = "Provisional output only:"

/** Deterministic output formats supported by the semantic deliverable foundation. */
@Serializable
enum class DeliverableFormat {
    MARKDOWN,
    HTML5,
    JSON,
    CSV_BUNDLE;

    /** Whether this format is intended primarily for a human receiver. */
    val isHumanReadable: Boolean
        get() = this == MARKDOWN || this == HTML5

    /** Whether this format preserves the complete machine-readable semantic package. */
    val isMachineReadable: Boolean
        get() = this == JSON || this == CSV_BUNDLE
}

/** Receiver-oriented semantic component kinds defined by the completion blueprint. */
@Serializable
enum class DeliverableComponentKind {
    EXECUTIVE_SUMMARY,
    DECISION_MATRIX,
    RECOMMENDATION,
    EVIDENCE_LEDGER,
    ASSUMPTIONS,
    UNCERTAINTIES,
    RISK_REGISTER,
    IMPLEMENTATION_PLAN,
    ACTION_CHECKLIST,
    TOOL_AND_SIMULATION_RESULTS,
    ALTERNATIVE_VIEWS,
    LIMITATIONS_AND_NEXT_EVIDENCE,
    TECHNICAL_APPENDIX,
}

/** Authority carried by an individual component or evidence record. */
@Serializable
enum class ComponentAuthorityClass {
    ACCEPTED,
    PROVISIONAL,
    WITHHELD;

    /**
     * Returns whether this observed authority satisfies [minimum].
     *
     * `WITHHELD` as a minimum means the blueprint permits a structural withheld placeholder.
     */
    fun satisfies(minimum: ComponentAuthorityClass): Boolean = when (minimum) {
        ACCEPTED -> this == ACCEPTED
        PROVISIONAL -> this == ACCEPTED || this == PROVISIONAL
        WITHHELD -> true
    }
}

/** Terminal receiver package classes; only the accepted class carries acceptance authority. */
@Serializable
enum class RunOutcomeClass {
    ACCEPTED_DECISION_PACKAGE,
    PROVISIONAL_DECISION_PACKAGE,
    STARTUP_DIAGNOSTIC_PACKAGE,
}

/** Disclosure state for evidence admitted to a receiver package. */
@Serializable
enum class DeliverableEvidenceDisclosure {
    PUBLIC_COMMITTED,
    HASH_ONLY_WITHHELD,
}

/** Origin of a semantic block supplied to the deterministic assembler. */
@Serializable
enum class DeliverableContentOrigin {
    DETERMINISTIC_SUPPLIED,
    MODEL_AUTHORED_SUPPLIED,
}

/** Exact status of a public test supporting a component. */
@Serializable
enum class DeliverableTestStatus {
    PASSED,
    FAILED,
    NOT_RUN,
    UNAVAILABLE,
}

/**
 * Blueprint rules for evidence admission.
 *
 * @param requireEvidenceForAcceptedComponents Whether accepted components need evidence.
 * @param allowProvisionalEvidence Whether provisional evidence may appear.
 * @param allowHashOnlyWithheldEvidence Whether redacted hash-only evidence may appear.
 */
@Serializable
data class DeliverableEvidencePolicy(
    val requireEvidenceForAcceptedComponents: Boolean = true,
    val allowProvisionalEvidence: Boolean = true,
    val allowHashOnlyWithheldEvidence: Boolean = true,
)

/**
 * Blueprint rules for producing a useful non-accepted package.
 *
 * @param allowProvisionalPackage Whether recovery publication is permitted.
 * @param requireKnownFailures Whether provisional output must declare failed gates.
 * @param requireMissingAuthority Whether authority gaps must be listed explicitly.
 */
@Serializable
data class DeliverableRecoveryPolicy(
    val allowProvisionalPackage: Boolean = true,
    val requireKnownFailures: Boolean = true,
    val requireMissingAuthority: Boolean = true,
)

/**
 * One required or optional semantic component in a deliverable blueprint.
 *
 * @param componentId Stable component identifier.
 * @param kind Semantic kind.
 * @param required Whether the package must contain this component or a withheld placeholder.
 * @param sourceNodeIds Nodes authorized to support the component; empty permits any supplied node.
 * @param minimumAuthority Minimum authority needed to satisfy the blueprint.
 * @param rendererHints Non-semantic presentation hints.
 */
@Serializable
data class DeliverableComponentSpec(
    val componentId: String,
    val kind: DeliverableComponentKind,
    val required: Boolean,
    val sourceNodeIds: Set<String> = emptySet(),
    val minimumAuthority: ComponentAuthorityClass,
    val rendererHints: Map<String, String> = emptyMap(),
) {
    init {
        require(componentId.isNotBlank()) { "Deliverable component ID must not be blank" }
        require(sourceNodeIds.none(String::isBlank)) { "Source node IDs must not be blank" }
        require(rendererHints.keys.none(String::isBlank)) { "Renderer hint keys must not be blank" }
    }
}

/**
 * Versioned receiver contract for semantic package assembly.
 *
 * @param schemaVersion Blueprint schema version.
 * @param blueprintId Stable blueprint identity.
 * @param receiverContractHash Exact receiver-contract commitment.
 * @param audience Intended receiver.
 * @param decisionOrUse Decision or operational use supported by the package.
 * @param primaryFormat Primary deterministic rendering format.
 * @param companionFormats Additional rendering formats.
 * @param componentSpecs Ordered component contract.
 * @param evidencePolicy Evidence admission policy.
 * @param recoveryPolicy Non-accepted package policy.
 * @param blueprintHash Self-hash excluding this field.
 */
@Serializable
data class DeliverableBlueprint(
    val schemaVersion: String = DELIVERABLE_BLUEPRINT_SCHEMA_V1,
    val blueprintId: String,
    val receiverContractHash: String,
    val audience: String,
    val decisionOrUse: String,
    val primaryFormat: DeliverableFormat,
    val companionFormats: Set<DeliverableFormat>,
    val componentSpecs: List<DeliverableComponentSpec>,
    val evidencePolicy: DeliverableEvidencePolicy = DeliverableEvidencePolicy(),
    val recoveryPolicy: DeliverableRecoveryPolicy = DeliverableRecoveryPolicy(),
    val blueprintHash: String = "",
) {
    /** Returns this blueprint with its deterministic self-hash attached. */
    fun withComputedHash(): DeliverableBlueprint =
        copy(blueprintHash = DeliverableHashing.hashBlueprint(this))

    /** Returns whether [blueprintHash] commits the exact blueprint semantics. */
    fun hasValidHash(): Boolean =
        DeliverableHashing.constantTimeEquals(
            blueprintHash,
            DeliverableHashing.hashBlueprint(this)
        )
}

/**
 * One key/value pair whose order is semantically significant.
 *
 * @param key Receiver-facing key.
 * @param value Supplied value.
 */
@Serializable
data class DeliverableKeyValue(
    val key: String,
    val value: String,
) {
    init {
        require(key.isNotBlank()) { "Deliverable key must not be blank" }
    }
}

/**
 * One supplied checklist row.
 *
 * @param text Exact receiver-facing text.
 * @param completed Whether supplied evidence marks the action complete.
 */
@Serializable
data class DeliverableChecklistItem(
    val text: String,
    val completed: Boolean,
) {
    init {
        require(text.isNotBlank()) { "Checklist text must not be blank" }
    }
}

/** Typed semantic content rendered without asking a model to choose representation. */
@Serializable
sealed class DeliverableSemanticBlock {
    /** Origin of the exact supplied content. */
    abstract val origin: DeliverableContentOrigin

    /**
     * Supplied receiver-language prose.
     *
     * @param text Exact prose; model-authored text is permitted only in this block type.
     * @param origin Supplied-content origin.
     */
    @Serializable
    @SerialName("narrative")
    data class Narrative(
        val text: String,
        override val origin: DeliverableContentOrigin,
    ) : DeliverableSemanticBlock() {
        init {
            require(text.isNotBlank()) { "Narrative content must not be blank" }
        }
    }

    /**
     * Supplied deterministic table.
     *
     * @param columns Ordered column labels.
     * @param rows Ordered row values.
     * @param origin Must be deterministic supplied content.
     */
    @Serializable
    @SerialName("table")
    data class Table(
        val columns: List<String>,
        val rows: List<List<String>>,
        override val origin: DeliverableContentOrigin =
            DeliverableContentOrigin.DETERMINISTIC_SUPPLIED,
    ) : DeliverableSemanticBlock() {
        init {
            require(origin == DeliverableContentOrigin.DETERMINISTIC_SUPPLIED) {
                "Model-authored content is limited to supplied narrative blocks"
            }
            require(columns.isNotEmpty() && columns.none(String::isBlank)) {
                "Table columns must be non-empty and non-blank"
            }
            require(rows.all { it.size == columns.size }) {
                "Every table row must match the declared column count"
            }
        }
    }

    /**
     * Supplied deterministic key/value data.
     *
     * @param entries Ordered entries.
     * @param origin Must be deterministic supplied content.
     */
    @Serializable
    @SerialName("key_value")
    data class KeyValue(
        val entries: List<DeliverableKeyValue>,
        override val origin: DeliverableContentOrigin =
            DeliverableContentOrigin.DETERMINISTIC_SUPPLIED,
    ) : DeliverableSemanticBlock() {
        init {
            require(origin == DeliverableContentOrigin.DETERMINISTIC_SUPPLIED) {
                "Model-authored content is limited to supplied narrative blocks"
            }
            require(entries.isNotEmpty()) { "Key/value content must not be empty" }
        }
    }

    /**
     * Supplied deterministic checklist.
     *
     * @param items Ordered checklist items.
     * @param origin Must be deterministic supplied content.
     */
    @Serializable
    @SerialName("checklist")
    data class Checklist(
        val items: List<DeliverableChecklistItem>,
        override val origin: DeliverableContentOrigin =
            DeliverableContentOrigin.DETERMINISTIC_SUPPLIED,
    ) : DeliverableSemanticBlock() {
        init {
            require(origin == DeliverableContentOrigin.DETERMINISTIC_SUPPLIED) {
                "Model-authored content is limited to supplied narrative blocks"
            }
            require(items.isNotEmpty()) { "Checklist content must not be empty" }
        }
    }
}

/**
 * Exact grade authority attached to a semantic component.
 *
 * @param gradeId Stable grade record ID.
 * @param gradeHash Exact grade commitment.
 * @param score Exact score when one was produced.
 */
@Serializable
data class DeliverableGradeReference(
    val gradeId: String,
    val gradeHash: String,
    val score: Double? = null,
) {
    init {
        require(gradeId.isNotBlank() && gradeHash.isSha256()) {
            "Grade references require a non-blank ID and SHA-256 hash"
        }
        require(score == null || score.isFinite()) { "Grade score must be finite" }
    }
}

/**
 * Exact public test authority attached to a semantic component.
 *
 * @param testId Stable test ID.
 * @param resultHash Exact result commitment.
 * @param status Typed test status.
 */
@Serializable
data class DeliverableTestReference(
    val testId: String,
    val resultHash: String,
    val status: DeliverableTestStatus,
) {
    init {
        require(testId.isNotBlank() && resultHash.isSha256()) {
            "Test references require a non-blank ID and SHA-256 hash"
        }
    }
}

/**
 * Evidence admitted to the semantic package.
 *
 * Hash-only withheld evidence carries no summary, preventing this contract from transporting
 * private fixture semantics into receiver output.
 *
 * @param evidenceId Stable evidence identity.
 * @param evidenceHash Exact evidence commitment.
 * @param authorityClass Evidence authority.
 * @param disclosure Receiver disclosure class.
 * @param sourceNodeId Source node when applicable.
 * @param evidencePacketId Source packet when applicable.
 * @param publicSummary Optional public committed summary.
 * @param provenanceHashes Additional exact lineage commitments.
 */
@Serializable
data class DeliverableEvidenceLink(
    val evidenceId: String,
    val evidenceHash: String,
    val authorityClass: ComponentAuthorityClass,
    val disclosure: DeliverableEvidenceDisclosure,
    val sourceNodeId: String? = null,
    val evidencePacketId: String? = null,
    val publicSummary: String? = null,
    val provenanceHashes: Set<String> = emptySet(),
) {
    init {
        require(evidenceId.isNotBlank() && evidenceHash.isSha256()) {
            "Evidence links require a non-blank ID and SHA-256 hash"
        }
        require(sourceNodeId?.isNotBlank() != false && evidencePacketId?.isNotBlank() != false) {
            "Evidence source identities must not be blank"
        }
        require(provenanceHashes.all(String::isSha256)) {
            "Evidence provenance values must be SHA-256 hashes"
        }
        require(
            disclosure != DeliverableEvidenceDisclosure.HASH_ONLY_WITHHELD ||
                (publicSummary == null && authorityClass == ComponentAuthorityClass.WITHHELD)
        ) {
            "Hash-only withheld evidence must contain no summary and carry WITHHELD authority"
        }
    }
}

/**
 * Complete supplied lineage for a component.
 *
 * @param evidenceIds Evidence-link IDs.
 * @param sourceNodeIds Exact source node IDs.
 * @param evidencePacketIds Exact evidence packet IDs.
 * @param grades Exact grade references.
 * @param tests Exact public test references.
 * @param toolExecutionIds Exact tool execution IDs.
 * @param simulationExecutionIds Exact simulation execution IDs.
 */
@Serializable
data class DeliverableComponentProvenance(
    val evidenceIds: Set<String> = emptySet(),
    val sourceNodeIds: Set<String> = emptySet(),
    val evidencePacketIds: Set<String> = emptySet(),
    val grades: List<DeliverableGradeReference> = emptyList(),
    val tests: List<DeliverableTestReference> = emptyList(),
    val toolExecutionIds: Set<String> = emptySet(),
    val simulationExecutionIds: Set<String> = emptySet(),
) {
    init {
        require(
            listOf(
                evidenceIds,
                sourceNodeIds,
                evidencePacketIds,
                toolExecutionIds,
                simulationExecutionIds,
            ).all { values -> values.none(String::isBlank) }
        ) { "Component provenance IDs must not be blank" }
    }
}

/**
 * Supplied component input. The assembler validates and hashes it but never writes new semantics.
 *
 * @param componentId Blueprint component ID.
 * @param kind Semantic kind.
 * @param title Supplied receiver-facing title.
 * @param authorityClass Observed authority.
 * @param blocks Exact supplied semantic blocks.
 * @param provenance Exact supplied lineage.
 * @param uncertainty Supplied uncertainty statement when applicable.
 */
@Serializable
data class SuppliedDeliverableComponent(
    val componentId: String,
    val kind: DeliverableComponentKind,
    val title: String,
    val authorityClass: ComponentAuthorityClass,
    val blocks: List<DeliverableSemanticBlock>,
    val provenance: DeliverableComponentProvenance,
    val uncertainty: String? = null,
) {
    init {
        require(componentId.isNotBlank() && title.isNotBlank()) {
            "Supplied component identity and title must not be blank"
        }
        require(uncertainty?.isNotBlank() != false) {
            "Component uncertainty must be absent or non-blank"
        }
        require(authorityClass == ComponentAuthorityClass.WITHHELD || blocks.isNotEmpty()) {
            "Non-withheld supplied components require semantic content"
        }
        require(authorityClass != ComponentAuthorityClass.WITHHELD || blocks.isEmpty()) {
            "Withheld supplied components cannot transport semantic content"
        }
    }
}

/**
 * One assembled component.
 *
 * @param componentId Blueprint component ID.
 * @param kind Semantic kind.
 * @param title Supplied title or deterministic withheld label.
 * @param authorityClass Observed authority.
 * @param blocks Exact supplied semantic blocks.
 * @param provenance Exact supplied lineage.
 * @param uncertainty Supplied uncertainty statement.
 * @param contentHash Self-hash of the component excluding this field.
 */
@Serializable
data class DeliverableComponent(
    val componentId: String,
    val kind: DeliverableComponentKind,
    val title: String,
    val authorityClass: ComponentAuthorityClass,
    val blocks: List<DeliverableSemanticBlock>,
    val provenance: DeliverableComponentProvenance,
    val uncertainty: String? = null,
    val contentHash: String = "",
) {
    init {
        require(componentId.isNotBlank() && title.isNotBlank()) {
            "Deliverable component identity and title must not be blank"
        }
        require(authorityClass == ComponentAuthorityClass.WITHHELD || blocks.isNotEmpty()) {
            "Non-withheld deliverable components require semantic content"
        }
        require(authorityClass != ComponentAuthorityClass.WITHHELD || blocks.isEmpty()) {
            "Withheld deliverable components cannot transport semantic content"
        }
        require(uncertainty?.isNotBlank() != false) {
            "Deliverable component uncertainty must be absent or non-blank"
        }
    }

    /** Returns this component with its deterministic content hash attached. */
    fun withComputedHash(): DeliverableComponent =
        copy(contentHash = DeliverableHashing.hashComponent(this))

    /** Returns whether [contentHash] commits this component. */
    fun hasValidHash(): Boolean =
        DeliverableHashing.constantTimeEquals(
            contentHash,
            DeliverableHashing.hashComponent(this)
        )
}

/**
 * One mandatory acceptance failure preserved in a non-accepted package.
 *
 * @param gateId Stable gate ID.
 * @param code Typed failure code.
 * @param publicMessage Receiver-safe supplied message.
 * @param authorityHash Exact failure authority.
 */
@Serializable
data class FailedAcceptanceGate(
    val gateId: String,
    val code: String,
    val publicMessage: String,
    val authorityHash: String,
) {
    init {
        require(gateId.isNotBlank() && code.isNotBlank() && publicMessage.isNotBlank()) {
            "Failed acceptance gate fields must not be blank"
        }
        require(authorityHash.isSha256()) { "Failed gate authority must be a SHA-256 hash" }
    }
}

/**
 * Explicit authority gap for one component.
 *
 * @param componentId Affected component.
 * @param requiredAuthority Blueprint requirement.
 * @param observedAuthority Observed authority, or null when no component was supplied.
 * @param missingEvidenceIds Supplied IDs of evidence known to be absent.
 * @param publicReason Deterministic structural reason or supplied public reason.
 */
@Serializable
data class MissingDeliverableAuthority(
    val componentId: String,
    val requiredAuthority: ComponentAuthorityClass,
    val observedAuthority: ComponentAuthorityClass?,
    val missingEvidenceIds: Set<String> = emptySet(),
    val publicReason: String,
) {
    init {
        require(componentId.isNotBlank() && publicReason.isNotBlank()) {
            "Missing-authority component and reason must not be blank"
        }
        require(missingEvidenceIds.none(String::isBlank)) {
            "Missing evidence IDs must not be blank"
        }
    }
}

/**
 * Exact winner/source/writer chain required for accepted publication.
 *
 * @param winnerAuthorityId Accepted winner or final-policy authority ID.
 * @param winnerAuthorityHash Exact winner commitment.
 * @param sourceAuthorityIds Exact selected source authority IDs.
 * @param writerAuthorityId Final writer authority ID.
 * @param writerOutputHash Exact writer output commitment.
 */
@Serializable
data class AcceptedDeliverableAuthority(
    val winnerAuthorityId: String,
    val winnerAuthorityHash: String,
    val sourceAuthorityIds: Set<String>,
    val writerAuthorityId: String,
    val writerOutputHash: String,
) {
    init {
        require(
            winnerAuthorityId.isNotBlank() &&
                sourceAuthorityIds.isNotEmpty() &&
                sourceAuthorityIds.none(String::isBlank) &&
                writerAuthorityId.isNotBlank()
        ) { "Accepted deliverable authority chain is incomplete" }
        require(winnerAuthorityHash.isSha256() && writerOutputHash.isSha256()) {
            "Accepted authority commitments must be SHA-256 hashes"
        }
    }
}

/**
 * Versioned receiver decision package independent of presentation format.
 *
 * @param schemaVersion Package schema version.
 * @param packageId Stable package ID.
 * @param runId Owning run.
 * @param outcomeClass Accepted, provisional, or startup-diagnostic outcome.
 * @param receiverContractHash Exact receiver contract commitment.
 * @param blueprintHash Exact blueprint commitment.
 * @param audience Intended receiver.
 * @param decisionOrUse Intended decision or use.
 * @param components Ordered semantic components.
 * @param evidenceLedger Ordered public or hash-only evidence ledger.
 * @param knownFailures Ordered mandatory failures.
 * @param missingAuthority Ordered authority gaps.
 * @param acceptedAuthority Winner/source/writer chain, accepted packages only.
 * @param generatedAt Caller-supplied package timestamp.
 * @param packageHash Self-hash excluding this field.
 */
@Serializable
data class ReceiverDecisionPackage(
    val schemaVersion: String = RECEIVER_DECISION_PACKAGE_SCHEMA_V1,
    val packageId: String,
    val runId: String,
    val outcomeClass: RunOutcomeClass,
    val receiverContractHash: String,
    val blueprintHash: String,
    val audience: String,
    val decisionOrUse: String,
    val components: List<DeliverableComponent>,
    val evidenceLedger: List<DeliverableEvidenceLink>,
    val knownFailures: List<FailedAcceptanceGate>,
    val missingAuthority: List<MissingDeliverableAuthority>,
    val acceptedAuthority: AcceptedDeliverableAuthority? = null,
    val generatedAt: Instant,
    val packageHash: String = "",
) {
    /** Returns this package with its deterministic self-hash attached. */
    fun withComputedHash(): ReceiverDecisionPackage =
        copy(packageHash = DeliverableHashing.hashPackage(this))

    /** Returns whether the package and every component carry valid hashes. */
    fun hasValidHashes(): Boolean =
        components.all(DeliverableComponent::hasValidHash) &&
            DeliverableHashing.constantTimeEquals(
                packageHash,
                DeliverableHashing.hashPackage(this)
            )
}

/**
 * Versioned receiver-oriented progress snapshot at a sealed round boundary.
 *
 * @param schemaVersion Snapshot schema version.
 * @param runId Owning run.
 * @param round Sealed round.
 * @param groupId Owning group.
 * @param blueprintHash Blueprint commitment.
 * @param acceptedComponentIds Accepted component IDs.
 * @param provisionalComponentIds Provisional component IDs.
 * @param withheldComponentIds Withheld component IDs.
 * @param failedGateIds Known failed gate IDs.
 * @param evidenceRefs Exact evidence IDs.
 * @param missingAuthorityComponentIds Components still missing required authority.
 * @param snapshotHash Self-hash excluding this field.
 */
@Serializable
data class InterimDeliverableSnapshot(
    val schemaVersion: String = INTERIM_DELIVERABLE_SNAPSHOT_SCHEMA_V1,
    val runId: String,
    val round: Int,
    val groupId: String,
    val blueprintHash: String,
    val acceptedComponentIds: List<String>,
    val provisionalComponentIds: List<String>,
    val withheldComponentIds: List<String>,
    val failedGateIds: List<String>,
    val evidenceRefs: List<String>,
    val missingAuthorityComponentIds: List<String>,
    val snapshotHash: String = "",
) {
    init {
        require(runId.isNotBlank() && groupId.isNotBlank() && round >= 0) {
            "Interim snapshot identity and round are invalid"
        }
        require(blueprintHash.isSha256()) { "Interim snapshot blueprint hash is invalid" }
        val lists = listOf(
            acceptedComponentIds,
            provisionalComponentIds,
            withheldComponentIds,
            failedGateIds,
            evidenceRefs,
            missingAuthorityComponentIds,
        )
        require(lists.all { it == it.distinct().sorted() && it.none(String::isBlank) }) {
            "Interim snapshot lists must be sorted, unique, and non-blank"
        }
        require(
            acceptedComponentIds.toSet()
                .intersect(provisionalComponentIds.toSet() + withheldComponentIds)
                .isEmpty() &&
                provisionalComponentIds.toSet().intersect(withheldComponentIds.toSet()).isEmpty()
        ) { "Interim component authority classes must be disjoint" }
    }

    /** Returns this snapshot with its deterministic self-hash attached. */
    fun withComputedHash(): InterimDeliverableSnapshot =
        copy(snapshotHash = DeliverableHashing.hashSnapshot(this))

    /** Returns whether [snapshotHash] commits the exact snapshot. */
    fun hasValidHash(): Boolean =
        DeliverableHashing.constantTimeEquals(
            snapshotHash,
            DeliverableHashing.hashSnapshot(this)
        )
}

internal fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))

internal fun String.isSafeProvisionalDecisionOrUse(): Boolean =
    startsWith(PROVISIONAL_DECISION_OR_USE_PREFIX) &&
        length <= 512 &&
        !contains("://") &&
        !contains('/') &&
        !contains('\\') &&
        listOf(
            "private",
            "secret",
            "critic",
            "rationale",
            "payload",
            "stack",
            "reasoning",
            "trace",
        ).none(lowercase()::contains)
