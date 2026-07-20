package com.achulkov.evomolior.core.serialization

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.DisclosureStageDraft
import com.achulkov.evomolior.core.council.FieldChangeDeclaration
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.SealedTestDraft
import com.achulkov.evomolior.core.council.SynthesisOutput
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
private data class StructuredSynthesisOutput(
    val atlasVersion: Int,
    val parentAtlasHash: String?,
    val stages: List<DisclosureStageDraft>,
    val sealedTests: List<StructuredSealedTestDraft>,
    val workerFieldNotes: List<String>,
    val graderFieldNotes: List<String>,
    val rubricSchemaVersion: String,
    val declaredChange: FieldChangeDeclaration?
)

@Serializable
private data class StructuredSealedTestDraft(
    val testKey: String,
    val nodeKey: LocalNodeKey,
    val acceptedArtifactTypes: List<ArtifactType>,
    @Serializable(with = TaggedJsonElementSerializer::class)
    val testDefinitionJson: JsonElement,
    val commitmentHash: String
)

/**
 * Legacy non-contextual tagged serializer retained for core compatibility tests.
 *
 * Active Council Synthesis provider generation and decoding must use
 * [CouncilSynthesisContextualOutputSerializer], whose GENERAL branch is
 * `GeneralTestDefinitionV1`. This serializer has no Task-derived kind authority and therefore
 * cannot be used as an active provider boundary.
 */
internal object CouncilSynthesisStructuredOutputSerializer : KSerializer<SynthesisOutput> {
    private val delegate = StructuredSynthesisOutput.serializer()
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

    private fun SynthesisOutput.toStructuredOutput(): StructuredSynthesisOutput =
        StructuredSynthesisOutput(
            atlasVersion = atlasVersion,
            parentAtlasHash = parentAtlasHash,
            stages = stages,
            sealedTests = sealedTests.map { it.toStructuredTest() },
            workerFieldNotes = workerFieldNotes,
            graderFieldNotes = graderFieldNotes,
            rubricSchemaVersion = rubricSchemaVersion,
            declaredChange = declaredChange
        )

    private fun SealedTestDraft.toStructuredTest(): StructuredSealedTestDraft {
        val definition = runCatching {
            json.parseToJsonElement(testDefinitionJson)
        }.getOrElse { cause ->
            throw SerializationException(
                "Council sealed test '$testKey' has invalid testDefinitionJson",
                cause
            )
        }
        return StructuredSealedTestDraft(
            testKey = testKey,
            nodeKey = nodeKey,
            acceptedArtifactTypes = acceptedArtifactTypes.sortedBy(ArtifactType::name),
            testDefinitionJson = definition,
            commitmentHash = commitmentHash
        )
    }

    private fun StructuredSynthesisOutput.toDomainOutput(): SynthesisOutput =
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

    private fun StructuredSealedTestDraft.toDomainTest(): SealedTestDraft =
        SealedTestDraft(
            testKey = testKey,
            nodeKey = nodeKey,
            acceptedArtifactTypes = acceptedArtifactTypes.toUniqueArtifactTypes(),
            testDefinitionJson = CouncilContractHashing.canonicalJson(testDefinitionJson),
            commitmentHash = commitmentHash
        )

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
}
