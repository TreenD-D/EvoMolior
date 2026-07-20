package com.achulkov.evomolior.core.serialization

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaValidationCode
import com.achulkov.evomolior.core.atlas.TypedToolSchemaValidationFailure
import com.achulkov.evomolior.core.atlas.TypedToolSchemaValidator
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilSealedTestWireAuthority
import com.achulkov.evomolior.core.council.CouncilSealedTestWireKind
import com.achulkov.evomolior.core.council.CouncilSynthesisWireAuthority
import com.achulkov.evomolior.core.council.DisclosureStageDraft
import com.achulkov.evomolior.core.council.FieldChangeDeclaration
import com.achulkov.evomolior.core.council.GeneralTestDefinitionV1
import com.achulkov.evomolior.core.council.GeneralTestDefinitionV1Codec
import com.achulkov.evomolior.core.council.SealedTestDraft
import com.achulkov.evomolior.core.council.SynthesisOutput
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class ContextualStructuredSynthesisOutput(
    val atlasVersion: Int,
    val parentAtlasHash: String?,
    val stages: List<DisclosureStageDraft>,
    val sealedTests: List<ContextualStructuredSealedTestDraft>,
    val workerFieldNotes: List<String>,
    val graderFieldNotes: List<String>,
    val rubricSchemaVersion: String,
    val declaredChange: FieldChangeDeclaration?
)

@Serializable
private sealed interface ContextualStructuredSealedTestDraft {
    val authorityKey: String
    val acceptedArtifactTypes: List<ArtifactType>
    val commitmentHash: String

    @Serializable
    @SerialName("PROMOTION")
    data class Promotion(
        override val authorityKey: String,
        override val acceptedArtifactTypes: List<ArtifactType>,
        val testDefinitionJson: ContextualPromotionTestDefinition,
        override val commitmentHash: String
    ) : ContextualStructuredSealedTestDraft

    @Serializable
    @SerialName("GENERAL")
    data class General(
        override val authorityKey: String,
        override val acceptedArtifactTypes: List<ArtifactType>,
        val testDefinitionJson: GeneralTestDefinitionV1,
        override val commitmentHash: String
    ) : ContextualStructuredSealedTestDraft
}

@Serializable
private data class ContextualPromotionTestDefinition(
    @Serializable(with = TaggedJsonElementSerializer::class)
    val input: JsonElement,
    @Serializable(with = TaggedJsonElementSerializer::class)
    val expectedOutput: JsonElement
)

/**
 * One exact strict-wire issue found before contextual Synthesis projection.
 *
 * @param path Path relative to the Synthesis response root.
 * @param message Deterministic rejection reason.
 */
data class CouncilSynthesisWireIssue(
    val path: List<String>,
    val message: String
)

/**
 * Result of converting exact Synthesis provider wire into the serializer's generic tagged wire.
 */
sealed interface CouncilSynthesisProviderWireNormalization {
    /** Exact provider wire normalized successfully. */
    data class Success(val element: JsonElement) : CouncilSynthesisProviderWireNormalization

    /** Provider wire violated the exact promotion projection contract. */
    data class Failure(val issue: CouncilSynthesisWireIssue) :
        CouncilSynthesisProviderWireNormalization
}

/**
 * Exact generated-schema definition identities owned by the contextual Synthesis serializer.
 *
 * Schema binding uses these serializer-derived names instead of searching generated JSON by
 * coincidental property shapes. A generator layout change therefore fails closed before a
 * provider call.
 *
 * @param outputDefinitionSerialName Contextual Synthesis output definition.
 * @param promotionDefinitionSerialName Object-only promotion branch definition.
 * @param generalDefinitionSerialName Closed typed GENERAL branch definition.
 * @param taggedNullDefinitionSerialName Tagged null branch definition.
 * @param taggedStringDefinitionSerialName Tagged string branch definition.
 * @param taggedBooleanDefinitionSerialName Tagged boolean branch definition.
 * @param taggedIntegerDefinitionSerialName Tagged integer branch definition.
 * @param taggedNumberDefinitionSerialName Tagged number branch definition.
 * @param taggedArrayDefinitionSerialName Tagged array branch definition.
 * @param taggedObjectDefinitionSerialName Tagged object branch definition.
 * @param taggedObjectEntryDefinitionSerialName Tagged object-entry definition.
 */
data class CouncilSynthesisProviderSchemaContract(
    val outputDefinitionSerialName: String,
    val promotionDefinitionSerialName: String,
    val generalDefinitionSerialName: String,
    val taggedNullDefinitionSerialName: String,
    val taggedStringDefinitionSerialName: String,
    val taggedBooleanDefinitionSerialName: String,
    val taggedIntegerDefinitionSerialName: String,
    val taggedNumberDefinitionSerialName: String,
    val taggedArrayDefinitionSerialName: String,
    val taggedObjectDefinitionSerialName: String,
    val taggedObjectEntryDefinitionSerialName: String
)

/**
 * Task-contextual provider serializer for Genesis Council Synthesis.
 *
 * Promotion definitions use a dedicated object-only branch whose nested fixture values are
 * validated against the exact Task-authored executable schemas. General definitions use the
 * closed [GeneralTestDefinitionV1] serializer directly, so nulls, arbitrary maps, and unknown
 * assertion branches are unrepresentable on the active provider wire. Provider responses carry a
 * compiler-owned authority key, so a known promotion test cannot select the general branch,
 * another schema group, or a different Task-declared test/node identity. Persisted
 * [SynthesisOutput] and [SealedTestDraft.testDefinitionJson] remain unchanged.
 *
 * @param authority Exact current Task-derived test authority.
 */
class CouncilSynthesisContextualOutputSerializer(
    private val authority: CouncilSynthesisWireAuthority
) : KSerializer<SynthesisOutput> {
    private val delegate = ContextualStructuredSynthesisOutput.serializer()
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: SynthesisOutput) {
        encoder.encodeSerializableValue(delegate, value.toStructuredOutput())
    }

    override fun deserialize(decoder: Decoder): SynthesisOutput =
        decoder.decodeSerializableValue(delegate).toDomainOutput()

    /**
     * Encodes one domain output using the exact contextual provider response shape.
     *
     * General JSON retains deterministic tagged entry arrays. Promotion object fixtures use
     * schema-owned fixed property maps and explicit optional-property wrappers.
     *
     * @param value Domain Synthesis output.
     * @return Exact provider-facing JSON response.
     */
    fun encodeProviderWire(value: SynthesisOutput): JsonElement {
        val genericWire = json.encodeToJsonElement(delegate, value.toStructuredOutput())
        validateAuthority(genericWire)?.let { issue ->
            throw SerializationException(
                "${issue.path.joinToString(".")}: ${issue.message}"
            )
        }
        return when (val result = projectPromotionFixtures(genericWire, encode = true)) {
            is CouncilSynthesisProviderWireNormalization.Success -> result.element
            is CouncilSynthesisProviderWireNormalization.Failure ->
                throw SerializationException(
                    "${result.issue.path.joinToString(".")}: ${result.issue.message}"
                )
        }
    }

    /**
     * Normalizes exact contextual provider wire for strict descriptor and domain decoding.
     *
     * This method does not accept generic promotion object entry arrays as a compatibility shape.
     * General tagged JSON is unchanged.
     *
     * @param element Parsed provider response.
     * @return Normalized generic tagged wire or one path-addressed projection failure.
     */
    fun normalizeProviderWire(
        element: JsonElement
    ): CouncilSynthesisProviderWireNormalization =
        projectPromotionFixtures(element, encode = false)

    /**
     * Checks exact authority identity and every Task-derived promotion fixture schema.
     *
     * Structural fields are validated separately against [descriptor].
     *
     * @param element Parsed Synthesis response.
     * @return First identity issue or one conjunctive fixture-schema issue, or null.
     */
    fun validateAuthority(element: JsonElement): CouncilSynthesisWireIssue? {
        val tests = (element as? JsonObject)
            ?.get("sealedTests") as? JsonArray
            ?: return null
        if (tests.size != authority.entries.size) {
            return CouncilSynthesisWireIssue(
                path = listOf("sealedTests"),
                message = "Expected exactly ${authority.entries.size} Task-declared sealed tests"
            )
        }
        val seenAuthorityKeys = mutableSetOf<String>()
        val promotionFixtureIssues = mutableListOf<String>()
        tests.forEachIndexed { index, testElement ->
            val test = testElement as? JsonObject ?: return@forEachIndexed
            val authorityKey = (test["authorityKey"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?: return@forEachIndexed
            val entry = authority.findByAuthorityKey(authorityKey)
                ?: return CouncilSynthesisWireIssue(
                    path = listOf("sealedTests", index.toString(), "authorityKey"),
                    message = "Unknown Task-derived sealed-test authority"
                )
            val wireKind = (test["type"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
            if (wireKind != entry.kind.name) {
                return CouncilSynthesisWireIssue(
                    path = listOf("sealedTests", index.toString(), "type"),
                    message = "Sealed-test authority requires ${entry.kind.name} wire shape"
                )
            }
            if (!seenAuthorityKeys.add(authorityKey)) {
                return CouncilSynthesisWireIssue(
                    path = listOf("sealedTests", index.toString(), "authorityKey"),
                    message = "Task-derived sealed-test authority is repeated"
                )
            }
            if (entry.kind == CouncilSealedTestWireKind.PROMOTION) {
                val definition = test["testDefinitionJson"] ?: return@forEachIndexed
                val fixture = runCatching {
                    json.decodeFromJsonElement(
                        ContextualPromotionTestDefinition.serializer(),
                        definition
                    )
                }.getOrNull() ?: return@forEachIndexed
                val schema = authority.promotionSchemaFor(entry)
                TypedToolSchemaValidator.validationFailure(
                    fixture.input,
                    schema.inputSchema
                )?.let { failure ->
                    promotionFixtureIssues +=
                        "sealedTests.$index.testDefinitionJson.input: " +
                        failure.providerRepairDiagnostic()
                }
                TypedToolSchemaValidator.validationFailure(
                    fixture.expectedOutput,
                    schema.outputSchema
                )?.let { failure ->
                    promotionFixtureIssues +=
                        "sealedTests.$index.testDefinitionJson.expectedOutput: " +
                        failure.providerRepairDiagnostic()
                }
            }
        }
        authority.entries.firstOrNull { it.authorityKey !in seenAuthorityKeys }?.let {
            return CouncilSynthesisWireIssue(
                path = listOf("sealedTests"),
                message = "Task-derived sealed-test authority is missing"
            )
        }
        if (promotionFixtureIssues.isNotEmpty()) {
            return CouncilSynthesisWireIssue(
                path = listOf("sealedTests"),
                message = "Task-derived promotion fixture schema violations: " +
                    promotionFixtureIssues.joinToString(" | ")
            )
        }
        return null
    }

    private fun projectPromotionFixtures(
        element: JsonElement,
        encode: Boolean
    ): CouncilSynthesisProviderWireNormalization {
        val root = element as? JsonObject
            ?: return CouncilSynthesisProviderWireNormalization.Success(element)
        val tests = root["sealedTests"] as? JsonArray
            ?: return CouncilSynthesisProviderWireNormalization.Success(element)
        val projectedTests = tests.toMutableList()
        tests.forEachIndexed { index, testElement ->
            val test = testElement as? JsonObject ?: return@forEachIndexed
            val authorityKey = (test["authorityKey"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?: return@forEachIndexed
            val entry = authority.findByAuthorityKey(authorityKey) ?: return@forEachIndexed
            val wireKind = (test["type"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
            if (
                entry.kind != CouncilSealedTestWireKind.PROMOTION ||
                wireKind != CouncilSealedTestWireKind.PROMOTION.name
            ) {
                return@forEachIndexed
            }
            val definition = test["testDefinitionJson"] as? JsonObject
                ?: return@forEachIndexed
            definition.keys.sorted().firstOrNull { it !in PROMOTION_TEST_FIELDS }?.let {
                return CouncilSynthesisProviderWireNormalization.Failure(
                    CouncilSynthesisWireIssue(
                        path = listOf(
                            "sealedTests",
                            index.toString(),
                            "testDefinitionJson",
                            it
                        ),
                        message = "Unknown promotion test-definition field"
                    )
                )
            }
            val schema = authority.promotionSchemaFor(entry)
            val projectedDefinition = linkedMapOf<String, JsonElement>()
            listOf(
                "input" to schema.inputSchema,
                "expectedOutput" to schema.outputSchema
            ).forEach { (name, componentSchema) ->
                val component = definition[name] ?: return@forEach
                val path = listOf(
                    "sealedTests",
                    index.toString(),
                    "testDefinitionJson",
                    name
                )
                val projection = if (encode) {
                    PromotionFixtureProviderWireProjector.encode(
                        component,
                        componentSchema,
                        path
                    )
                } else {
                    PromotionFixtureProviderWireProjector.normalize(
                        component,
                        componentSchema,
                        path
                    )
                }
                when (projection) {
                    is PromotionFixtureWireProjection.Failure ->
                        return CouncilSynthesisProviderWireNormalization.Failure(
                            projection.issue
                        )
                    is PromotionFixtureWireProjection.Success ->
                        projectedDefinition[name] = projection.element
                }
            }
            if (projectedDefinition.size == PROMOTION_TEST_FIELDS.size) {
                projectedTests[index] = JsonObject(
                    test + (
                        "testDefinitionJson" to JsonObject(projectedDefinition)
                    )
                )
            }
        }
        return CouncilSynthesisProviderWireNormalization.Success(
            JsonObject(root + ("sealedTests" to JsonArray(projectedTests)))
        )
    }

    private fun TypedToolSchemaValidationFailure.providerRepairDiagnostic(): String {
        val cardinalityDetails = when (code) {
            TypedToolSchemaValidationCode.TOO_FEW_ITEMS ->
                "actualItems=${requireNotNull(actualItemCount)}, " +
                    "minimumItems=${requireNotNull(minimumItemCount)}, " +
                    "missingItems=${minimumItemCount - actualItemCount}"
            TypedToolSchemaValidationCode.TOO_MANY_ITEMS ->
                "actualItems=${requireNotNull(actualItemCount)}, " +
                    "maximumItems=${requireNotNull(maximumItemCount)}, " +
                    "excessItems=${actualItemCount - maximumItemCount}"
            else -> null
        }
        return cardinalityDetails?.let { "$stableCode ($it)" } ?: stableCode
    }

    private fun SynthesisOutput.toStructuredOutput(): ContextualStructuredSynthesisOutput {
        val structuredTests = sealedTests.map { it.toStructuredTest() }
        val emittedKeys = structuredTests.map(ContextualStructuredSealedTestDraft::authorityKey)
        val expectedKeys = authority.entries.map(
            CouncilSealedTestWireAuthority::authorityKey
        )
        if (emittedKeys.size != expectedKeys.size || emittedKeys.toSet() != expectedKeys.toSet()) {
            throw SerializationException(
                "Council Synthesis output must contain every Task-derived sealed-test authority " +
                    "exactly once"
            )
        }
        if (emittedKeys.toSet().size != emittedKeys.size) {
            throw SerializationException(
                "Council Synthesis output repeats a Task-derived sealed-test authority"
            )
        }
        return ContextualStructuredSynthesisOutput(
            atlasVersion = atlasVersion,
            parentAtlasHash = parentAtlasHash,
            stages = stages,
            sealedTests = structuredTests,
            workerFieldNotes = workerFieldNotes,
            graderFieldNotes = graderFieldNotes,
            rubricSchemaVersion = rubricSchemaVersion,
            declaredChange = declaredChange
        )
    }

    private fun SealedTestDraft.toStructuredTest(): ContextualStructuredSealedTestDraft {
        val entry = authority.findByDomainIdentity(testKey, nodeKey)
            ?: throw SerializationException(
                "Council sealed test '$testKey' is absent from current Task authority"
            )
        val artifactTypes = acceptedArtifactTypes.sortedBy(ArtifactType::name)
        return when (entry.kind) {
            CouncilSealedTestWireKind.PROMOTION -> {
                val definition = parsePromotionDefinition(testKey, testDefinitionJson)
                val promotion = definition as? JsonObject
                    ?: throw SerializationException(
                        "Council promotion test '$testKey' definition must be one JSON object"
                    )
                val unexpected = promotion.keys - PROMOTION_TEST_FIELDS
                if (unexpected.isNotEmpty() || promotion.keys != PROMOTION_TEST_FIELDS) {
                    throw SerializationException(
                        "Council promotion test '$testKey' definition must contain exactly " +
                            "input and expectedOutput"
                    )
                }
                ContextualStructuredSealedTestDraft.Promotion(
                    authorityKey = entry.authorityKey,
                    acceptedArtifactTypes = artifactTypes,
                    testDefinitionJson = ContextualPromotionTestDefinition(
                        input = promotion.getValue("input"),
                        expectedOutput = promotion.getValue("expectedOutput")
                    ),
                    commitmentHash = commitmentHash
                )
            }
            CouncilSealedTestWireKind.GENERAL ->
                ContextualStructuredSealedTestDraft.General(
                    authorityKey = entry.authorityKey,
                    acceptedArtifactTypes = artifactTypes,
                    testDefinitionJson = parseGeneralDefinition(
                        testKey,
                        testDefinitionJson
                    ),
                    commitmentHash = commitmentHash
                )
        }
    }

    private fun ContextualStructuredSynthesisOutput.toDomainOutput(): SynthesisOutput =
        SynthesisOutput(
            atlasVersion = atlasVersion,
            parentAtlasHash = parentAtlasHash,
            stages = stages,
            sealedTests = sealedTests.map { it.toDomainTest() },
            workerFieldNotes = workerFieldNotes,
            graderFieldNotes = graderFieldNotes,
            rubricSchemaVersion = rubricSchemaVersion,
            declaredChange = declaredChange
        )

    private fun ContextualStructuredSealedTestDraft.toDomainTest(): SealedTestDraft {
        val entry = authorityEntry()
        val definitionJson = when (this) {
            is ContextualStructuredSealedTestDraft.Promotion ->
                CouncilContractHashing.canonicalJson(
                    JsonObject(
                        linkedMapOf(
                            "input" to testDefinitionJson.input,
                            "expectedOutput" to testDefinitionJson.expectedOutput
                        )
                    )
                )
            is ContextualStructuredSealedTestDraft.General ->
                GeneralTestDefinitionV1Codec.encode(testDefinitionJson)
        }
        return SealedTestDraft(
            testKey = entry.testKey,
            nodeKey = entry.nodeKey,
            acceptedArtifactTypes = acceptedArtifactTypes.toUniqueArtifactTypes(),
            testDefinitionJson = definitionJson,
            commitmentHash = commitmentHash
        )
    }

    private fun ContextualStructuredSealedTestDraft.authorityEntry():
        CouncilSealedTestWireAuthority {
        val entry = authority.findByAuthorityKey(authorityKey)
            ?: throw SerializationException(
                "Unknown Council sealed-test authority '$authorityKey'"
            )
        val actualKind = when (this) {
            is ContextualStructuredSealedTestDraft.Promotion ->
                CouncilSealedTestWireKind.PROMOTION
            is ContextualStructuredSealedTestDraft.General -> CouncilSealedTestWireKind.GENERAL
        }
        if (entry.kind != actualKind) {
            throw SerializationException(
                "Council sealed-test authority '$authorityKey' requires ${entry.kind}"
            )
        }
        return entry
    }

    private fun parsePromotionDefinition(testKey: String, definition: String): JsonElement =
        runCatching { json.parseToJsonElement(definition) }.getOrElse { cause ->
            throw SerializationException(
                "Council promotion test '$testKey' has invalid testDefinitionJson",
                cause
            )
        }

    private fun parseGeneralDefinition(
        testKey: String,
        definition: String
    ): GeneralTestDefinitionV1 =
        runCatching { GeneralTestDefinitionV1Codec.decode(definition) }
            .getOrElse { cause ->
                throw SerializationException(
                    "Council general test '$testKey' must use GeneralTestDefinitionV1",
                    cause
                )
            }

    private fun List<ArtifactType>.toUniqueArtifactTypes(): Set<ArtifactType> {
        val values = linkedSetOf<ArtifactType>()
        forEach { value ->
            if (!values.add(value)) {
                throw SerializationException(
                    "Duplicate Council sealed-test artifact type '${value.name}'"
                )
            }
        }
        return values
    }

    companion object {
        /**
         * Serializer-derived definition names required by provider-schema binding.
         */
        val providerSchemaContract = CouncilSynthesisProviderSchemaContract(
            outputDefinitionSerialName =
                ContextualStructuredSynthesisOutput.serializer().descriptor.serialName,
            promotionDefinitionSerialName =
                ContextualStructuredSealedTestDraft.Promotion.serializer().descriptor.serialName,
            generalDefinitionSerialName =
                ContextualStructuredSealedTestDraft.General.serializer().descriptor.serialName,
            taggedNullDefinitionSerialName =
                TaggedJsonElementSerializer.providerSchemaContract.nullDefinitionSerialName,
            taggedStringDefinitionSerialName =
                TaggedJsonElementSerializer.providerSchemaContract.stringDefinitionSerialName,
            taggedBooleanDefinitionSerialName =
                TaggedJsonElementSerializer.providerSchemaContract.booleanDefinitionSerialName,
            taggedIntegerDefinitionSerialName =
                TaggedJsonElementSerializer.providerSchemaContract.integerDefinitionSerialName,
            taggedNumberDefinitionSerialName =
                TaggedJsonElementSerializer.providerSchemaContract.numberDefinitionSerialName,
            taggedArrayDefinitionSerialName =
                TaggedJsonElementSerializer.providerSchemaContract.arrayDefinitionSerialName,
            taggedObjectDefinitionSerialName =
                TaggedJsonElementSerializer.providerSchemaContract.objectDefinitionSerialName,
            taggedObjectEntryDefinitionSerialName =
                TaggedJsonElementSerializer.providerSchemaContract.objectEntryDefinitionSerialName
        )

        private val PROMOTION_TEST_FIELDS = setOf("input", "expectedOutput")
    }
}
