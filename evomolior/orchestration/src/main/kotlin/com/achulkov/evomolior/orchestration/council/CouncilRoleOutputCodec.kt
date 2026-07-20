package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.council.ContractPath
import com.achulkov.evomolior.core.council.EcologistOutput
import com.achulkov.evomolior.core.council.FieldCompileError
import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.council.IncentiveDesignerOutput
import com.achulkov.evomolior.core.council.KgArchitectOutput
import com.achulkov.evomolior.core.council.ModelEcologistOutput
import com.achulkov.evomolior.core.council.PromptArchitectOutput
import com.achulkov.evomolior.core.council.SeedCuratorOutput
import com.achulkov.evomolior.core.council.SynthesisOutput
import com.achulkov.evomolior.core.council.TaskAnatomistOutput
import com.achulkov.evomolior.core.council.CouncilSynthesisWireAuthority
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.serialization.CouncilSynthesisContextualOutputSerializer
import com.achulkov.evomolior.core.serialization.CouncilSynthesisProviderWireNormalization
import com.achulkov.evomolior.core.serialization.TaskAnatomistContextualOutputSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** One decoded, role-specific Genesis Council response. */
sealed interface DecodedCouncilRoleOutput {
    /** Council role that owns this output. */
    val role: Role

    data class TaskAnatomist(val value: TaskAnatomistOutput) : DecodedCouncilRoleOutput {
        override val role = Role.CouncilTaskAnatomist
    }

    data class IncentiveDesigner(val value: IncentiveDesignerOutput) : DecodedCouncilRoleOutput {
        override val role = Role.CouncilIncentiveDesigner
    }

    data class Ecologist(val value: EcologistOutput) : DecodedCouncilRoleOutput {
        override val role = Role.CouncilEcologist
    }

    data class PromptArchitect(val value: PromptArchitectOutput) : DecodedCouncilRoleOutput {
        override val role = Role.CouncilPromptArchitect
    }

    data class KgArchitect(val value: KgArchitectOutput) : DecodedCouncilRoleOutput {
        override val role = Role.CouncilKGArchitect
    }

    data class ModelEcologist(val value: ModelEcologistOutput) : DecodedCouncilRoleOutput {
        override val role = Role.CouncilModelEcologist
    }

    data class SeedCurator(val value: SeedCuratorOutput) : DecodedCouncilRoleOutput {
        override val role = Role.CouncilSeedCurator
    }

    data class Synthesis(
        val value: SynthesisOutput,
        val wireAuthority: CouncilSynthesisWireAuthority
    ) : DecodedCouncilRoleOutput {
        override val role = Role.CouncilSynthesis
    }
}

/** Strict response-decoding result used by selective Council repair. */
sealed interface CouncilRoleDecodeResult {
    data class Success(val output: DecodedCouncilRoleOutput) : CouncilRoleDecodeResult
    data class Failure(val error: FieldCompileError) : CouncilRoleDecodeResult
}

/**
 * Decodes each Council response against only the serializer owned by that role.
 *
 * The codec accepts only one complete JSON value. It does not strip provider prose or fences,
 * search for embedded JSON, ignore unknown fields, or recover partial objects.
 */
object CouncilRoleOutputCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
        classDiscriminator = CLASS_DISCRIMINATOR
    }

    /**
     * Strictly decodes [rawOutput] for [role].
     *
     * @return A typed output or a path-addressed error owned only by [role].
     */
    fun decode(
        role: Role,
        rawOutput: String,
        synthesisAuthority: CouncilSynthesisWireAuthority? = null,
        acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL
    ): CouncilRoleDecodeResult {
        require(role.isCouncilRole) { "Role $role is not a Council role" }
        require(
            (role == Role.CouncilSynthesis) == (synthesisAuthority != null)
        ) {
            if (role == Role.CouncilSynthesis) {
                "Council Synthesis decoding requires Task-derived sealed-test authority"
            } else {
                "Only Council Synthesis may receive sealed-test decoding authority"
            }
        }
        val element = runCatching { json.parseToJsonElement(rawOutput) }.getOrElse { cause ->
            return failure(role, emptyList(), "Response is not one complete JSON value: ${cause.message}")
        }
        return when (role) {
            Role.CouncilTaskAnatomist -> if (acceptanceProfile == AcceptanceProfile.NORMAL) {
                decode(role, element, TaskAnatomistOutput.serializer()) {
                    DecodedCouncilRoleOutput.TaskAnatomist(it)
                }
            } else {
                decode(
                    role,
                    element,
                    TaskAnatomistContextualOutputSerializer(acceptanceProfile)
                ) {
                    DecodedCouncilRoleOutput.TaskAnatomist(it)
                }
            }
            Role.CouncilIncentiveDesigner -> decode(
                role, element, IncentiveDesignerOutput.serializer()
            ) { DecodedCouncilRoleOutput.IncentiveDesigner(it) }
            Role.CouncilEcologist -> decode(
                role, element, EcologistOutput.serializer()
            ) { DecodedCouncilRoleOutput.Ecologist(it) }
            Role.CouncilPromptArchitect -> decode(
                role, element, PromptArchitectOutput.serializer()
            ) { DecodedCouncilRoleOutput.PromptArchitect(it) }
            Role.CouncilKGArchitect -> decode(
                role, element, KgArchitectOutput.serializer()
            ) { DecodedCouncilRoleOutput.KgArchitect(it) }
            Role.CouncilModelEcologist -> decode(
                role, element, ModelEcologistOutput.serializer()
            ) { DecodedCouncilRoleOutput.ModelEcologist(it) }
            Role.CouncilSeedCurator -> decode(
                role, element, SeedCuratorOutput.serializer()
            ) { DecodedCouncilRoleOutput.SeedCurator(it) }
            Role.CouncilSynthesis -> {
                val authority = requireNotNull(synthesisAuthority)
                val contextualSerializer =
                    CouncilSynthesisContextualOutputSerializer(authority)
                val normalized = when (
                    val normalization = contextualSerializer.normalizeProviderWire(element)
                ) {
                    is CouncilSynthesisProviderWireNormalization.Failure ->
                        return failure(
                            role,
                            normalization.issue.path,
                            normalization.issue.message
                        )
                    is CouncilSynthesisProviderWireNormalization.Success ->
                        normalization.element
                }
                contextualSerializer.validateAuthority(normalized)?.let { issue ->
                    return failure(role, issue.path, issue.message)
                }
                decode(
                    role = role,
                    element = normalized,
                    serializer = contextualSerializer
                ) {
                    DecodedCouncilRoleOutput.Synthesis(it, authority)
                }
            }
            else -> error("Role $role is not a Council role")
        }
    }

    private fun <T> decode(
        role: Role,
        element: JsonElement,
        serializer: KSerializer<T>,
        wrap: (T) -> DecodedCouncilRoleOutput
    ): CouncilRoleDecodeResult {
        validateShape(element, serializer.descriptor, emptyList())?.let { issue ->
            return failure(role, issue.path, issue.message)
        }
        return runCatching {
            CouncilRoleDecodeResult.Success(
                wrap(json.decodeFromJsonElement(serializer, element))
            )
        }.getOrElse { cause ->
            failure(
                role,
                emptyList(),
                "Response violates ${serializer.descriptor.serialName}: ${cause.message}"
            )
        }
    }

    private fun failure(
        role: Role,
        relativePath: List<String>,
        message: String
    ): CouncilRoleDecodeResult.Failure = CouncilRoleDecodeResult.Failure(
        FieldCompileError(
            code = FieldCompileErrorCode.MALFORMED_ROLE_OUTPUT,
            path = ContractPath(listOf(role.name) + relativePath),
            message = message,
            responsibleCouncilRoles = setOf(role)
        )
    )

    private data class ShapeIssue(val path: List<String>, val message: String)

    @OptIn(ExperimentalSerializationApi::class)
    private fun validateShape(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: List<String>
    ): ShapeIssue? {
        if (element is JsonNull) {
            return if (descriptor.isNullable) null else ShapeIssue(path, "Required value is null")
        }
        if (descriptor.isInline) {
            return validateShape(element, descriptor.getElementDescriptor(0), path)
        }
        return when (val kind = descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> validateObject(element, descriptor, path)
            StructureKind.LIST -> validateList(element, descriptor, path)
            StructureKind.MAP -> validateMap(element, descriptor, path)
            PolymorphicKind.SEALED -> validateSealed(element, descriptor, path)
            kotlinx.serialization.descriptors.SerialKind.ENUM -> validateEnum(element, descriptor, path)
            is PrimitiveKind -> validatePrimitive(element, kind, path)
            else -> null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun validateObject(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: List<String>
    ): ShapeIssue? {
        val value = element as? JsonObject
            ?: return ShapeIssue(path, "Expected a JSON object")
        val names = (0 until descriptor.elementsCount).associateBy(descriptor::getElementName)
        val unknown = value.keys.firstOrNull { it !in names }
        if (unknown != null) return ShapeIssue(path + unknown, "Unknown field")
        val missing = names.keys.firstOrNull { it !in value }
        if (missing != null) return ShapeIssue(path + missing, "Required field is missing")
        return value.entries.firstNotNullOfOrNull { (name, child) ->
            val index = names.getValue(name)
            validateShape(child, descriptor.getElementDescriptor(index), path + name)
        }
    }

    private fun validateList(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: List<String>
    ): ShapeIssue? {
        val value = element as? JsonArray
            ?: return ShapeIssue(path, "Expected a JSON array")
        val childDescriptor = descriptor.getElementDescriptor(0)
        return value.withIndex().firstNotNullOfOrNull { (index, child) ->
            validateShape(child, childDescriptor, path + index.toString())
        }
    }

    private fun validateMap(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: List<String>
    ): ShapeIssue? {
        val value = element as? JsonObject
            ?: return ShapeIssue(path, "Expected a JSON object map")
        val valueDescriptor = descriptor.getElementDescriptor(1)
        return value.entries.firstNotNullOfOrNull { (key, child) ->
            validateShape(child, valueDescriptor, path + key)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun validateSealed(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: List<String>
    ): ShapeIssue? {
        val value = element as? JsonObject
            ?: return ShapeIssue(path, "Expected a JSON object")
        val discriminator = value[CLASS_DISCRIMINATOR]
            ?: return ShapeIssue(
                path + CLASS_DISCRIMINATOR,
                "Required discriminator is missing"
            )
        val serialName = (discriminator as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: return ShapeIssue(
                path + CLASS_DISCRIMINATOR,
                "Expected a discriminator string"
            )
        val branchContainer = descriptor.getElementDescriptor(1)
        val branchDescriptor = (0 until branchContainer.elementsCount)
            .firstNotNullOfOrNull { index ->
                branchContainer.getElementDescriptor(index).takeIf { branch ->
                    branchContainer.getElementName(index) == serialName ||
                        branch.serialName == serialName
                }
            }
            ?: return ShapeIssue(
                path + CLASS_DISCRIMINATOR,
                "Unknown discriminator value '$serialName'"
            )
        return validateShape(
            element = JsonObject(value - CLASS_DISCRIMINATOR),
            descriptor = branchDescriptor,
            path = path
        )
    }

    private fun validateEnum(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: List<String>
    ): ShapeIssue? {
        val value = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: return ShapeIssue(path, "Expected an enum string")
        val permitted = (0 until descriptor.elementsCount).map(descriptor::getElementName)
        return if (value in permitted) null else ShapeIssue(path, "Unknown enum value '$value'")
    }

    private fun validatePrimitive(
        element: JsonElement,
        kind: PrimitiveKind,
        path: List<String>
    ): ShapeIssue? {
        val value = element as? JsonPrimitive
            ?: return ShapeIssue(path, "Expected a primitive value")
        val valid = when (kind) {
            PrimitiveKind.STRING -> value.isString
            PrimitiveKind.CHAR -> value.isString && value.content.length == 1
            PrimitiveKind.BOOLEAN -> !value.isString && value.booleanOrNull != null
            PrimitiveKind.BYTE,
            PrimitiveKind.SHORT,
            PrimitiveKind.INT,
            PrimitiveKind.LONG,
            PrimitiveKind.FLOAT,
            PrimitiveKind.DOUBLE -> !value.isString && value.doubleOrNull != null
        }
        return if (valid) null else ShapeIssue(path, "JSON primitive has the wrong type")
    }

    private const val CLASS_DISCRIMINATOR = "type"
}
