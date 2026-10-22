package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.council.CompiledFieldState
import com.achulkov.evomolior.core.council.CompiledSealedTestAuthorityV3
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilFieldSealing
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.FieldConsistencyReport
import com.achulkov.evomolior.core.council.SealedTestSuiteV3
import com.achulkov.evomolior.core.council.SealedTestSuiteV3Codec
import com.achulkov.evomolior.core.council.SealedTestSuiteV3CommitmentView
import com.achulkov.evomolior.core.council.SealedTestSuiteV3CommitmentViewCodec
import com.achulkov.evomolior.core.council.SealedTestSuiteV3ValidationContext
import com.achulkov.evomolior.core.grader.GraderAtlas
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Immutable, hash-bound executable Council candidate retained before activation.
 *
 * @param schemaVersion Exact manifest ABI.
 * @param runId Owning run identity.
 * @param workerStartingField Sealed worker execution field.
 * @param graderStartingField Separately sealed grader execution field.
 * @param workerAtlas Authoritative worker limitation Atlas.
 * @param graderAtlas Separately sealed grader evaluation Atlas.
 * @param receiverContract Exact receiver contract governed by [workerAtlas].
 * @param executionEnvelope Exact operator-owned compiler envelope.
 * @param consistencyReport Exact compiler consistency report.
 * @param privateSuite Grader-private executable test suite.
 * @param workerCommitmentView Worker-safe commitment projection of [privateSuite].
 * @param validationContext External authority used to validate [privateSuite].
 * @param state Candidate lifecycle state; only executability-verified candidates are valid here.
 * @param manifestHash Commitment of this manifest excluding this field.
 */
@Serializable
data class CompiledCouncilCandidateManifestV3(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val workerStartingField: StartingField,
    val graderStartingField: StartingField,
    val workerAtlas: NodeAtlas,
    val graderAtlas: GraderAtlas,
    val receiverContract: ReceiverContract,
    val executionEnvelope: ExecutionEnvelope,
    val consistencyReport: FieldConsistencyReport,
    val privateSuite: SealedTestSuiteV3,
    val workerCommitmentView: SealedTestSuiteV3CommitmentView,
    val validationContext: SealedTestSuiteV3ValidationContext,
    val state: CompiledFieldState = CompiledFieldState.EXECUTABILITY_VERIFIED,
    val manifestHash: String = ""
) {
    /** Returns a copy carrying this manifest's canonical self-hash. */
    fun withComputedHash(): CompiledCouncilCandidateManifestV3 =
        copy(manifestHash = CompiledCouncilCandidateManifestV3Codec.hash(this))

    /** Returns whether the manifest satisfies every authority binding and self-hash invariant. */
    fun hasValidHash(): Boolean = runCatching {
        requireValid()
        true
    }.getOrDefault(false)

    /** Requires every candidate authority binding and the manifest self-hash to be valid. */
    fun requireValid() {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported compiled Council manifest schema" }
        require(runId.isNotBlank()) { "Compiled Council manifest runId must not be blank" }
        require(state == CompiledFieldState.EXECUTABILITY_VERIFIED) {
            "Compiled Council candidate must be EXECUTABILITY_VERIFIED"
        }
        require(workerStartingField.runId == runId) {
            "Worker StartingField does not belong to manifest run"
        }
        require(workerAtlas.runId == runId) { "Worker Atlas does not belong to manifest run" }
        require(graderAtlas.runId == runId) { "Grader Atlas does not belong to manifest run" }
        require(
            graderStartingField.runId ==
                com.achulkov.evomolior.core.council.CouncilDeterministicIds.create(
                    "grader-field",
                    runId
                )
        ) {
            "Grader StartingField does not carry the exact separate grader-field identity"
        }
        require(
            workerStartingField.objective == receiverContract.rawObjective &&
                graderStartingField.objective == receiverContract.rawObjective
        ) {
            "StartingFields do not preserve the exact receiver objective"
        }
        require(workerAtlas.validate().isValid) { "Worker Atlas is invalid" }
        require(graderAtlas.hasValidHash()) { "Grader Atlas self-hash is invalid" }
        require(graderAtlas.workerAtlasHash == workerAtlas.atlasHash) {
            "Grader Atlas is not linked to the exact worker Atlas"
        }
        require(workerAtlas.receiverContractHash == AtlasHashing.hashReceiverContract(receiverContract)) {
            "Worker Atlas is not linked to the exact receiver contract"
        }
        require(receiverContract.finalDeliverableContract == executionEnvelope.outputSpec) {
            "Receiver output contract does not match the execution envelope"
        }
        require(validationContext.finalOutputSpec == executionEnvelope.outputSpec) {
            "Sealed-test output authority does not match the execution envelope"
        }
        require(consistencyReport.atlasHash == workerAtlas.atlasHash) {
            "Consistency report does not commit the exact worker Atlas"
        }
        require(
            consistencyReport.envelopeHash ==
                CouncilContractHashing.hashExecutionEnvelope(executionEnvelope)
        ) {
            "Consistency report does not commit the exact execution envelope"
        }
        require(
            consistencyReport.workerFieldHash ==
                CouncilContractHashing.hashStartingField(workerStartingField) &&
                consistencyReport.graderFieldHash ==
                CouncilContractHashing.hashStartingField(graderStartingField)
        ) {
            "Consistency report does not commit the exact StartingFields"
        }

        val workerSeal = requireNotNull(workerStartingField.seal) {
            "Worker StartingField lacks the shared Council seal"
        }
        require(graderStartingField.seal == workerSeal) {
            "Worker and grader StartingFields do not share the exact Council seal"
        }
        require(
            CouncilFieldSealing.verifySeal(
                seal = workerSeal,
                workerField = workerStartingField,
                graderField = graderStartingField,
                atlas = workerAtlas,
                graderAtlas = graderAtlas,
                receiverContract = receiverContract,
                envelope = executionEnvelope,
                report = consistencyReport
            )
        ) { "Shared Council field seal is invalid" }

        CompiledSealedTestAuthorityV3(
            privateSuite = privateSuite,
            workerCommitmentView = workerCommitmentView,
            validationContext = validationContext
        ).requireValid()
        require(validationContext.atlasHash == workerAtlas.atlasHash) {
            "Sealed-test validation context is not linked to the worker Atlas"
        }
        val canonicalPrivateSuite = SealedTestSuiteV3Codec.encode(privateSuite, validationContext)
        val canonicalWorkerView = SealedTestSuiteV3CommitmentViewCodec.encode(workerCommitmentView)
        require(graderStartingField.sealedTestSuiteJson == canonicalPrivateSuite) {
            "Grader StartingField does not contain the exact canonical private suite"
        }
        require(workerStartingField.sealedTestSuiteJson == canonicalWorkerView) {
            "Worker StartingField does not contain the exact canonical commitment view"
        }
        require(
            graderStartingField.sealedTestSuiteHash ==
                CouncilContractHashing.sha256(canonicalPrivateSuite)
        ) { "Grader StartingField sealed-test hash is invalid" }
        require(
            workerStartingField.sealedTestSuiteHash ==
                CouncilContractHashing.sha256(canonicalWorkerView)
        ) { "Worker StartingField sealed-test hash is invalid" }
        require(manifestHash == CompiledCouncilCandidateManifestV3Codec.hash(this)) {
            "Compiled Council manifest self-hash is invalid"
        }
    }

    companion object {
        /** Exact candidate manifest ABI accepted by this authority. */
        const val SCHEMA_VERSION: String = "compiled-council-manifest-v3"
    }
}

/** Strict canonical codec and self-hash authority for [CompiledCouncilCandidateManifestV3]. */
object CompiledCouncilCandidateManifestV3Codec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    /**
     * Encodes [manifest] as canonical verified candidate-manifest JSON.
     *
     * @param manifest Candidate manifest to encode.
     * @return Canonical JSON.
     */
    fun encode(manifest: CompiledCouncilCandidateManifestV3): String {
        manifest.requireValid()
        return CouncilContractHashing.canonicalJson(
            json.encodeToJsonElement(CompiledCouncilCandidateManifestV3.serializer(), manifest)
        )
    }

    /**
     * Strictly decodes candidate-manifest JSON without granting authority.
     *
     * @param value Strict JSON value.
     * @return Structurally decoded candidate manifest.
     */
    fun decode(value: String): CompiledCouncilCandidateManifestV3 =
        json.decodeFromString(CompiledCouncilCandidateManifestV3.serializer(), value)

    /**
     * Strictly decodes and verifies one candidate manifest.
     *
     * @param value Strict JSON value.
     * @return Fully verified candidate manifest.
     */
    fun decodeVerified(value: String): CompiledCouncilCandidateManifestV3 =
        decode(value).also(CompiledCouncilCandidateManifestV3::requireValid)

    /**
     * Hashes [manifest] after excluding its self-referential `manifestHash` field.
     *
     * @param manifest Manifest to commit.
     * @return Lowercase SHA-256 digest.
     */
    fun hash(manifest: CompiledCouncilCandidateManifestV3): String {
        val element = json.encodeToJsonElement(CompiledCouncilCandidateManifestV3.serializer(), manifest)
        return CouncilContractHashing.sha256(
            CouncilContractHashing.canonicalJson(
                JsonObject(element.jsonObject.filterKeys { it != "manifestHash" })
            )
        )
    }
}

/**
 * Hash-bound active pointer to one archived verified Council candidate.
 *
 * @param schemaVersion Exact active-pointer ABI.
 * @param runId Owning run identity.
 * @param candidateArchiveEntryId Exact immutable archive entry containing the candidate.
 * @param candidateManifestHash Exact candidate-manifest commitment.
 * @param activatedAtEpochMs Activation timestamp in epoch milliseconds.
 * @param state Active lifecycle state; only [CompiledFieldState.ACTIVE] is valid.
 * @param activationHash Commitment of this pointer excluding this field.
 */
@Serializable
data class ActiveCompiledCouncilManifestV3(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val candidateArchiveEntryId: String,
    val candidateManifestHash: String,
    val activatedAtEpochMs: Long,
    val state: CompiledFieldState = CompiledFieldState.ACTIVE,
    val activationHash: String = ""
) {
    /** Returns a copy carrying this active pointer's canonical self-hash. */
    fun withComputedHash(): ActiveCompiledCouncilManifestV3 =
        copy(activationHash = ActiveCompiledCouncilManifestV3Codec.hash(this))

    /** Returns whether the active pointer has its exact schema, state, and self-hash. */
    fun hasValidHash(): Boolean = runCatching {
        requireValid()
        true
    }.getOrDefault(false)

    /** Requires this active pointer's exact schema, state, identities, and self-hash. */
    fun requireValid() {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported active Council manifest schema" }
        require(runId.isNotBlank()) { "Active Council manifest runId must not be blank" }
        require(candidateArchiveEntryId.isNotBlank()) {
            "Active Council manifest archive entry ID must not be blank"
        }
        require(candidateManifestHash.matches(Regex("[0-9a-f]{64}"))) {
            "Active Council manifest candidate hash must be a lowercase SHA-256 digest"
        }
        require(activatedAtEpochMs >= 0) { "Active Council manifest activation time is invalid" }
        require(state == CompiledFieldState.ACTIVE) {
            "Active Council manifest state must be ACTIVE"
        }
        require(activationHash == ActiveCompiledCouncilManifestV3Codec.hash(this)) {
            "Active Council manifest self-hash is invalid"
        }
    }

    companion object {
        /** Exact active pointer ABI accepted by this authority. */
        const val SCHEMA_VERSION: String = "compiled-council-active-v3"
    }
}

/** Strict canonical codec and self-hash authority for [ActiveCompiledCouncilManifestV3]. */
object ActiveCompiledCouncilManifestV3Codec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    /**
     * Encodes [manifest] as canonical verified active-pointer JSON.
     *
     * @param manifest Active pointer to encode.
     * @return Canonical JSON.
     */
    fun encode(manifest: ActiveCompiledCouncilManifestV3): String {
        manifest.requireValid()
        return CouncilContractHashing.canonicalJson(
            json.encodeToJsonElement(ActiveCompiledCouncilManifestV3.serializer(), manifest)
        )
    }

    /**
     * Strictly decodes active-pointer JSON without granting authority.
     *
     * @param value Strict JSON value.
     * @return Structurally decoded active pointer.
     */
    fun decode(value: String): ActiveCompiledCouncilManifestV3 =
        json.decodeFromString(ActiveCompiledCouncilManifestV3.serializer(), value)

    /**
     * Strictly decodes and verifies one active pointer.
     *
     * @param value Strict JSON value.
     * @return Fully verified active pointer.
     */
    fun decodeVerified(value: String): ActiveCompiledCouncilManifestV3 =
        decode(value).also(ActiveCompiledCouncilManifestV3::requireValid)

    /**
     * Hashes [manifest] after excluding its self-referential `activationHash` field.
     *
     * @param manifest Active pointer to commit.
     * @return Lowercase SHA-256 digest.
     */
    fun hash(manifest: ActiveCompiledCouncilManifestV3): String {
        val element = json.encodeToJsonElement(ActiveCompiledCouncilManifestV3.serializer(), manifest)
        return CouncilContractHashing.sha256(
            CouncilContractHashing.canonicalJson(
                JsonObject(element.jsonObject.filterKeys { it != "activationHash" })
            )
        )
    }
}
