package com.achulkov.evomolior.core.serialization

import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Provider-wire wrapper for a tool field that must compile to executable schema authority.
 *
 * Opaque JSON values remain available in the general [TypedField] domain contract for historical
 * and non-Council callers. Genesis tool opportunities use this narrower representation so an
 * invalid opaque field cannot pass strict role decoding and consume compiler repair cycles.
 */
@Serializable(with = ExecutableTypedFieldStructuredOutputSerializer::class)
internal data class ExecutableTypedField(val value: TypedField)

@Serializable
private sealed interface StructuredExecutableTypedField {
    val name: String
    val description: String
    val required: Boolean
    val validationRules: List<String>

    @Serializable
    @SerialName("STRING")
    data class StringField(
        override val name: String,
        override val description: String,
        override val required: Boolean,
        override val validationRules: List<String>,
        val enumValues: List<String>
    ) : StructuredExecutableTypedField

    @Serializable
    @SerialName("INTEGER")
    data class IntegerField(
        override val name: String,
        override val description: String,
        override val required: Boolean,
        override val validationRules: List<String>,
        val physicalDimension: PhysicalDimension?
    ) : StructuredExecutableTypedField

    @Serializable
    @SerialName("NUMBER")
    data class NumberField(
        override val name: String,
        override val description: String,
        override val required: Boolean,
        override val validationRules: List<String>,
        val physicalDimension: PhysicalDimension?
    ) : StructuredExecutableTypedField

    @Serializable
    @SerialName("BOOLEAN")
    data class BooleanField(
        override val name: String,
        override val description: String,
        override val required: Boolean,
        override val validationRules: List<String>
    ) : StructuredExecutableTypedField

    @Serializable
    @SerialName("OBJECT")
    data class ObjectField(
        override val name: String,
        override val description: String,
        override val required: Boolean,
        override val validationRules: List<String>,
        val objectFields: List<ExecutableTypedField>
    ) : StructuredExecutableTypedField

    @Serializable
    @SerialName("ARRAY")
    data class ArrayField(
        override val name: String,
        override val description: String,
        override val required: Boolean,
        override val validationRules: List<String>,
        val minimumItems: Int,
        val maximumItems: Int,
        val items: StructuredExecutableArrayItems
    ) : StructuredExecutableTypedField
}

@Serializable
private sealed interface StructuredExecutableArrayItems {
    @Serializable
    @SerialName("STRING_ITEMS")
    data object StringItems : StructuredExecutableArrayItems

    @Serializable
    @SerialName("INTEGER_ITEMS")
    data class IntegerItems(val physicalDimension: PhysicalDimension?) :
        StructuredExecutableArrayItems

    @Serializable
    @SerialName("NUMBER_ITEMS")
    data class NumberItems(val physicalDimension: PhysicalDimension?) :
        StructuredExecutableArrayItems

    @Serializable
    @SerialName("BOOLEAN_ITEMS")
    data object BooleanItems : StructuredExecutableArrayItems

    @Serializable
    @SerialName("OBJECT_ITEMS")
    data class ObjectItems(val objectFields: List<ExecutableTypedField>) :
        StructuredExecutableArrayItems
}

internal object ExecutableTypedFieldStructuredOutputSerializer :
    KSerializer<ExecutableTypedField> {
    private val delegate = StructuredExecutableTypedField.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ExecutableTypedField) {
        encoder.encodeSerializableValue(delegate, value.value.toStructuredField())
    }

    override fun deserialize(decoder: Decoder): ExecutableTypedField =
        ExecutableTypedField(
            decoder.decodeSerializableValue(delegate).toTypedField()
        )

    private fun TypedField.toStructuredField(): StructuredExecutableTypedField {
        val sortedRules = validationRules.sorted()
        return when (type) {
            TypedFieldType.STRING -> StructuredExecutableTypedField.StringField(
                name = name,
                description = description,
                required = required,
                validationRules = sortedRules,
                enumValues = enumValues.sorted()
            )
            TypedFieldType.INTEGER -> StructuredExecutableTypedField.IntegerField(
                name = name,
                description = description,
                required = required,
                validationRules = sortedRules,
                physicalDimension = physicalDimension
            )
            TypedFieldType.NUMBER -> StructuredExecutableTypedField.NumberField(
                name = name,
                description = description,
                required = required,
                validationRules = sortedRules,
                physicalDimension = physicalDimension
            )
            TypedFieldType.BOOLEAN -> StructuredExecutableTypedField.BooleanField(
                name = name,
                description = description,
                required = required,
                validationRules = sortedRules
            )
            TypedFieldType.OBJECT -> StructuredExecutableTypedField.ObjectField(
                name = name,
                description = description,
                required = required,
                validationRules = sortedRules,
                objectFields = objectFields
                    .sortedBy(TypedField::name)
                    .map(::ExecutableTypedField)
            )
            TypedFieldType.ARRAY -> StructuredExecutableTypedField.ArrayField(
                name = name,
                description = description,
                required = required,
                validationRules = sortedRules,
                minimumItems = requireNotNull(minimumItems),
                maximumItems = requireNotNull(maximumItems),
                items = toStructuredArrayItems()
            )
            TypedFieldType.JSON -> throw SerializationException(
                "Genesis tool fields require explicit executable structure"
            )
        }
    }

    private fun StructuredExecutableTypedField.toTypedField(): TypedField = when (this) {
        is StructuredExecutableTypedField.StringField -> TypedField(
            name = name,
            type = TypedFieldType.STRING,
            description = description,
            required = required,
            enumValues = enumValues.toUniqueSet("enum value"),
            validationRules = validationRules.toUniqueSet("validation rule")
        )
        is StructuredExecutableTypedField.IntegerField -> TypedField(
            name = name,
            type = TypedFieldType.INTEGER,
            description = description,
            required = required,
            validationRules = validationRules.toUniqueSet("validation rule"),
            physicalDimension = physicalDimension
        )
        is StructuredExecutableTypedField.NumberField -> TypedField(
            name = name,
            type = TypedFieldType.NUMBER,
            description = description,
            required = required,
            validationRules = validationRules.toUniqueSet("validation rule"),
            physicalDimension = physicalDimension
        )
        is StructuredExecutableTypedField.BooleanField -> TypedField(
            name = name,
            type = TypedFieldType.BOOLEAN,
            description = description,
            required = required,
            validationRules = validationRules.toUniqueSet("validation rule")
        )
        is StructuredExecutableTypedField.ObjectField -> TypedField(
            name = name,
            type = TypedFieldType.OBJECT,
            description = description,
            required = required,
            validationRules = validationRules.toUniqueSet("validation rule"),
            objectFields = objectFields.map(ExecutableTypedField::value).toUniqueObjectFields()
        )
        is StructuredExecutableTypedField.ArrayField -> TypedField(
            name = name,
            type = TypedFieldType.ARRAY,
            description = description,
            required = required,
            itemType = items.toTypedFieldType(),
            minimumItems = minimumItems,
            maximumItems = maximumItems,
            objectFields = (items as? StructuredExecutableArrayItems.ObjectItems)
                ?.objectFields
                ?.map(ExecutableTypedField::value)
                ?.toUniqueObjectFields()
                .orEmpty(),
            validationRules = validationRules.toUniqueSet("validation rule"),
            physicalDimension = items.physicalDimension
        )
    }

    private fun TypedField.toStructuredArrayItems(): StructuredExecutableArrayItems =
        when (requireNotNull(itemType)) {
            TypedFieldType.STRING -> StructuredExecutableArrayItems.StringItems
            TypedFieldType.INTEGER ->
                StructuredExecutableArrayItems.IntegerItems(physicalDimension)
            TypedFieldType.NUMBER ->
                StructuredExecutableArrayItems.NumberItems(physicalDimension)
            TypedFieldType.BOOLEAN -> StructuredExecutableArrayItems.BooleanItems
            TypedFieldType.OBJECT -> StructuredExecutableArrayItems.ObjectItems(
                objectFields.sortedBy(TypedField::name).map(::ExecutableTypedField)
            )
            TypedFieldType.JSON -> throw SerializationException(
                "Genesis tool array items require explicit executable structure"
            )
            TypedFieldType.ARRAY -> throw SerializationException(
                "Nested arrays require an explicit object field"
            )
        }

    private val StructuredExecutableArrayItems.physicalDimension: PhysicalDimension?
        get() = when (this) {
            is StructuredExecutableArrayItems.IntegerItems -> physicalDimension
            is StructuredExecutableArrayItems.NumberItems -> physicalDimension
            StructuredExecutableArrayItems.StringItems,
            StructuredExecutableArrayItems.BooleanItems,
            is StructuredExecutableArrayItems.ObjectItems -> null
        }

    private fun StructuredExecutableArrayItems.toTypedFieldType(): TypedFieldType = when (this) {
        StructuredExecutableArrayItems.StringItems -> TypedFieldType.STRING
        is StructuredExecutableArrayItems.IntegerItems -> TypedFieldType.INTEGER
        is StructuredExecutableArrayItems.NumberItems -> TypedFieldType.NUMBER
        StructuredExecutableArrayItems.BooleanItems -> TypedFieldType.BOOLEAN
        is StructuredExecutableArrayItems.ObjectItems -> TypedFieldType.OBJECT
    }

    private fun List<String>.toUniqueSet(label: String): Set<String> {
        val values = linkedSetOf<String>()
        forEach { value ->
            if (!values.add(value)) {
                throw SerializationException("Duplicate executable typed-field $label '$value'")
            }
        }
        return values
    }

    private fun List<TypedField>.toUniqueObjectFields(): Set<TypedField> {
        val fields = linkedSetOf<TypedField>()
        val names = linkedSetOf<String>()
        forEach { field ->
            if (!names.add(field.name)) {
                throw SerializationException(
                    "Duplicate executable typed-field object field '${field.name}'"
                )
            }
            fields.add(field)
        }
        return fields
    }
}
