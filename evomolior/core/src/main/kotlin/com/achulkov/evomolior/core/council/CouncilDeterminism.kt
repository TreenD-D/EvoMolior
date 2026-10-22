package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.deliverable.ReceiverDeliverableBlueprintCompiler
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.atlas.NodeAtlas
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Deterministic IDs derived from stable semantic inputs rather than runtime randomness. */
object CouncilDeterministicIds {
    /**
     * Produces a namespaced lowercase SHA-256 identifier.
     *
     * Length-prefixed parts prevent delimiter ambiguity.
     *
     * @param namespace Stable ID namespace.
     * @param parts Ordered semantic identity parts.
     * @return Identifier in `namespace_sha256` form.
     */
    fun create(namespace: String, vararg parts: String): String {
        require(namespace.matches(Regex("[a-z][a-z0-9-]*"))) { "Invalid ID namespace: $namespace" }
        val material = buildString {
            append(namespace.length).append(':').append(namespace)
            parts.forEach { part -> append('|').append(part.length).append(':').append(part) }
        }
        return "${namespace}_${CouncilContractHashing.sha256(material)}"
    }

    /**
     * Returns a deterministic Atlas node ID.
     *
     * @param runId Owning run.
     * @param key Semantic node key.
     * @return Stable node ID.
     */
    fun nodeId(runId: String, key: LocalNodeKey): String = create("node", runId, key.value)

    /**
     * Returns a deterministic worker or grader group ID.
     *
     * @param runId Owning run.
     * @param fieldKind Either `worker` or `grader`.
     * @param semanticKey Council-local group key.
     * @return Stable runtime group ID.
     */
    fun groupId(runId: String, fieldKind: String, semanticKey: String): String {
        require(fieldKind == "worker" || fieldKind == "grader") {
            "fieldKind must be worker or grader"
        }
        return create("$fieldKind-group", runId, semanticKey)
    }

    /**
     * Returns a deterministic Atlas ID.
     *
     * @param runId Owning run.
     * @return Stable Atlas ID.
     */
    fun atlasId(runId: String): String = create("atlas", runId)

    /**
     * Returns a deterministic field-change declaration ID.
     *
     * @param parentAtlasHash Parent Atlas commitment.
     * @param rationale Declared change rationale.
     * @return Stable declaration ID.
     */
    fun changeDeclarationId(parentAtlasHash: String, rationale: String): String =
        create("field-change", parentAtlasHash, rationale)
}

/** Canonical serialization and hashing for Council compilation contracts. */
object CouncilContractHashing {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "resultType"
    }

    /**
     * Hashes an execution envelope independently of map and set insertion order.
     *
     * @param envelope Envelope to commit.
     * @return Lowercase SHA-256 digest.
     */
    fun hashExecutionEnvelope(envelope: ExecutionEnvelope): String =
        hashSerializable(ExecutionEnvelope.serializer(), envelope)

    /**
     * Hashes all typed role outputs independently of map and set insertion order.
     *
     * @param outputs Complete role-output bundle.
     * @return Lowercase SHA-256 digest.
     */
    fun hashRoleOutputs(outputs: CouncilRoleOutputBundle): String =
        hashSerializable(CouncilRoleOutputBundle.serializer(), outputs)

    /**
     * Hashes a consistency report independently of map and set insertion order.
     *
     * @param report Report to commit.
     * @return Lowercase SHA-256 digest.
     */
    fun hashConsistencyReport(report: FieldConsistencyReport): String =
        hashSerializable(FieldConsistencyReport.serializer(), report)

    /**
     * Hashes a StartingField after removing its seal to avoid self-reference.
     *
     * @param field Field to commit.
     * @return Lowercase SHA-256 digest.
     */
    fun hashStartingField(field: StartingField): String {
        val element = json.encodeToJsonElement(StartingField.serializer(), field)
        val withoutSeal = JsonObject(element.jsonObject.filterKeys { it != "seal" })
        return sha256(canonicalJson(withoutSeal))
    }

    /**
     * Produces canonical JSON with lexicographically ordered object keys and normalized set fields.
     *
     * @param element JSON element to canonicalize.
     * @return Canonical JSON text.
     */
    fun canonicalJson(element: JsonElement): String = canonicalize(element).toString()

    /**
     * Returns a lowercase SHA-256 digest.
     *
     * @param value Exact UTF-8 input.
     * @return Lowercase digest.
     */
    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * Compares two commitments without content-dependent early exit.
     *
     * @param expected Expected lowercase digest.
     * @param actual Recomputed lowercase digest.
     * @return Whether the digests are byte-for-byte equal.
     */
    fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.encodeToByteArray(), actual.encodeToByteArray())

    private fun <T> hashSerializable(serializer: KSerializer<T>, value: T): String =
        sha256(canonicalJson(json.encodeToJsonElement(serializer, value)))

    private fun canonicalize(
        element: JsonElement,
        fieldName: String? = null,
        arrayIsSet: Boolean = false
    ): JsonElement = when (element) {
        is JsonObject -> {
            JsonObject(
                element.entries.sortedBy { it.key }.associate { (key, value) ->
                    key to canonicalize(value, key)
                }
            )
        }
        is JsonArray -> {
            val values = element.map { canonicalize(it) }
            JsonArray(
                if (arrayIsSet || fieldName in SET_FIELDS) {
                    values.sortedBy(JsonElement::toString)
                } else {
                    values
                }
            )
        }
        is JsonPrimitive -> element
    }

    private val SET_FIELDS = setOf(
        "permittedProfiles", "allowedTopologyKinds", "capabilities", "allowedDomains",
        "availableArtifactHashes", "capabilityIds", "parentKeys", "prerequisiteKeys",
        "alternativeToKeys", "rootNodeKeys", "affectedNodeKeys", "acceptedArtifactTypes",
        "requiredSections", "acceptedEvidenceKinds", "requiredSourceDomains",
        "acceptanceCriteria", "requiredEvidenceKinds", "suggestedSourceDomains",
        "executableTestIds", "requiredFields", "mediaTypes", "capabilityYields",
        "promotionTestIds",
        "visibleFields", "forbiddenFields", "requiredProperties", "sourceEntityTypeIds",
        "targetEntityTypeIds", "requiredCapabilities", "nodeKeys", "mandatoryNodeKeys",
        "responsibleCouncilRoles", "relatedLocalNodeKeys", "changeKinds", "affectedCapabilities"
    )

}

/**
 * Creates and verifies complete Council field seals using an explicit canonicalization contract.
 *
 * The worker and grader commitments are hashes of their canonical serialized JSON with the
 * nullable `seal` property omitted. The report and envelope remain typed inputs rather than
 * undocumented embedded strings, so persisted compiler artifacts can reproduce the seal.
 */
object CouncilFieldSealing {
    /** Digest algorithm used by the version-one seal contract. */
    const val HASH_ALGORITHM = "SHA-256"

    /** Canonical JSON contract used by the version-one seal contract. */
    const val CANONICALIZATION_VERSION = "evomolior-canonical-json-v1"

    /** Current field seal schema version. */
    const val SEAL_SCHEMA_VERSION = "4"

    /**
     * Creates a seal for one compiled worker/grader field pair.
     *
     * @param workerField Worker field before or after attaching a seal.
     * @param graderField Grader field before or after attaching a seal.
     * @param atlas Authoritative compiled Atlas.
     * @param graderAtlas Authoritative compiled Grader Atlas.
     * @param receiverContract Exact receiver authority used to compile the deliverable blueprint.
     * @param envelope Exact execution envelope supplied to the Council.
     * @param report Typed compiler consistency report.
     * @return Reproducible seal commitment.
     */
    fun createSeal(
        workerField: StartingField,
        graderField: StartingField,
        atlas: NodeAtlas,
        graderAtlas: GraderAtlas,
        receiverContract: ReceiverContract,
        envelope: ExecutionEnvelope,
        report: FieldConsistencyReport
    ): StartingFieldSeal = StartingFieldSeal(
        schemaVersion = SEAL_SCHEMA_VERSION,
        workerFieldHash = CouncilContractHashing.hashStartingField(workerField),
        graderFieldHash = CouncilContractHashing.hashStartingField(graderField),
        atlasHash = atlas.atlasHash,
        graderAtlasHash = graderAtlas.atlasHash,
        deliverableBlueprintHash =
            ReceiverDeliverableBlueprintCompiler.compile(receiverContract).blueprintHash,
        envelopeHash = CouncilContractHashing.hashExecutionEnvelope(envelope),
        consistencyReportHash = CouncilContractHashing.hashConsistencyReport(report)
    )

    /**
     * Verifies a persisted seal against all committed typed payloads.
     *
     * @param seal Persisted seal.
     * @param workerField Persisted worker field.
     * @param graderField Persisted grader field.
     * @param atlas Persisted authoritative Atlas.
     * @param graderAtlas Persisted authoritative Grader Atlas.
     * @param receiverContract Persisted exact receiver authority.
     * @param envelope Persisted execution envelope.
     * @param report Persisted typed consistency report.
     * @return `true` only when the version contract and every commitment match.
     */
    fun verifySeal(
        seal: StartingFieldSeal,
        workerField: StartingField,
        graderField: StartingField,
        atlas: NodeAtlas,
        graderAtlas: GraderAtlas,
        receiverContract: ReceiverContract,
        envelope: ExecutionEnvelope,
        report: FieldConsistencyReport
    ): Boolean =
        seal.schemaVersion == SEAL_SCHEMA_VERSION &&
            seal.hashAlgorithm == HASH_ALGORITHM &&
            seal.canonicalizationVersion == CANONICALIZATION_VERSION &&
            CouncilContractHashing.constantTimeEquals(
                seal.workerFieldHash,
                CouncilContractHashing.hashStartingField(workerField)
            ) &&
            CouncilContractHashing.constantTimeEquals(
                seal.graderFieldHash,
                CouncilContractHashing.hashStartingField(graderField)
            ) &&
            CouncilContractHashing.constantTimeEquals(seal.atlasHash, atlas.atlasHash) &&
            CouncilContractHashing.constantTimeEquals(
                seal.graderAtlasHash,
                graderAtlas.atlasHash
            ) &&
            CouncilContractHashing.constantTimeEquals(
                atlas.receiverContractHash,
                com.achulkov.evomolior.core.atlas.AtlasHashing
                    .hashReceiverContract(receiverContract)
            ) &&
            CouncilContractHashing.constantTimeEquals(
                seal.deliverableBlueprintHash,
                ReceiverDeliverableBlueprintCompiler.compile(receiverContract).blueprintHash
            ) &&
            CouncilContractHashing.constantTimeEquals(
                seal.envelopeHash,
                CouncilContractHashing.hashExecutionEnvelope(envelope)
            ) &&
            CouncilContractHashing.constantTimeEquals(
                seal.consistencyReportHash,
                CouncilContractHashing.hashConsistencyReport(report)
            )
}
