package com.achulkov.evomolior.core.serialization

import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.repair.QuantitySpec
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
internal data class StringStringEntry(val key: String, val value: String)

@Serializable
internal data class StringDoubleEntry(val key: String, val value: Double)

@Serializable
internal data class RoleStringEntry(val key: Role, val value: String)

@Serializable
internal data class LocalNodeKeyStringSetEntry(
    val key: LocalNodeKey,
    val value: Set<String>
)

@Serializable
internal data class StringIntEntry(val key: String, val value: Int)

internal abstract class DeterministicMapEntriesSerializer<K, V, E>(
    entrySerializer: KSerializer<E>,
    private val keyComparator: Comparator<K>,
    private val toEntry: (K, V) -> E,
    private val entryKey: (E) -> K,
    private val entryValue: (E) -> V
) : KSerializer<Map<K, V>> {
    private val delegate = ListSerializer(entrySerializer)

    final override val descriptor: SerialDescriptor = delegate.descriptor

    final override fun serialize(encoder: Encoder, value: Map<K, V>) {
        val entries = value.entries
            .sortedWith { left, right -> keyComparator.compare(left.key, right.key) }
            .map { entry -> toEntry(entry.key, entry.value) }
        encoder.encodeSerializableValue(delegate, entries)
    }

    final override fun deserialize(decoder: Decoder): Map<K, V> {
        val entries = decoder.decodeSerializableValue(delegate)
        return buildMap(entries.size) {
            entries.forEach { entry ->
                val key = entryKey(entry)
                if (containsKey(key)) {
                    throw SerializationException("Duplicate structured-output map key '$key'")
                }
                put(key, entryValue(entry))
            }
        }
    }
}

/**
 * Encodes a string-to-string map as a deterministic array of `{key,value}` objects.
 */
internal object StringStringMapEntriesSerializer :
    DeterministicMapEntriesSerializer<String, String, StringStringEntry>(
        entrySerializer = StringStringEntry.serializer(),
        keyComparator = compareBy(String::toString),
        toEntry = ::StringStringEntry,
        entryKey = StringStringEntry::key,
        entryValue = StringStringEntry::value
    )

/**
 * Encodes a string-to-double map as a deterministic array of `{key,value}` objects.
 */
internal object StringDoubleMapEntriesSerializer :
    DeterministicMapEntriesSerializer<String, Double, StringDoubleEntry>(
        entrySerializer = StringDoubleEntry.serializer(),
        keyComparator = compareBy(String::toString),
        toEntry = ::StringDoubleEntry,
        entryKey = StringDoubleEntry::key,
        entryValue = StringDoubleEntry::value
    )

/**
 * Encodes a role-to-string map as a deterministic array of `{key,value}` objects.
 */
internal object RoleStringMapEntriesSerializer :
    DeterministicMapEntriesSerializer<Role, String, RoleStringEntry>(
        entrySerializer = RoleStringEntry.serializer(),
        keyComparator = compareBy(Role::name),
        toEntry = ::RoleStringEntry,
        entryKey = RoleStringEntry::key,
        entryValue = RoleStringEntry::value
    )

/**
 * Encodes local-node mappings as deterministic entry objects with sorted set values.
 */
internal object LocalNodeKeyStringSetMapEntriesSerializer :
    DeterministicMapEntriesSerializer<LocalNodeKey, Set<String>, LocalNodeKeyStringSetEntry>(
        entrySerializer = LocalNodeKeyStringSetEntry.serializer(),
        keyComparator = compareBy(LocalNodeKey::value),
        toEntry = { key, value -> LocalNodeKeyStringSetEntry(key, value.toSortedSet()) },
        entryKey = LocalNodeKeyStringSetEntry::key,
        entryValue = LocalNodeKeyStringSetEntry::value
    )

/**
 * Encodes a string-to-integer map as a deterministic array of `{key,value}` objects.
 */
internal object StringIntMapEntriesSerializer :
    DeterministicMapEntriesSerializer<String, Int, StringIntEntry>(
        entrySerializer = StringIntEntry.serializer(),
        keyComparator = compareBy(String::toString),
        toEntry = ::StringIntEntry,
        entryKey = StringIntEntry::key,
        entryValue = StringIntEntry::value
    )

@Serializable
private sealed interface StructuredTypedField {
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
        val enumValues: List<String>,
        override val validationRules: List<String>
    ) : StructuredTypedField

    @Serializable
    @SerialName("INTEGER")
    data class IntegerField(
        override val name: String,
        override val description: String,
        override val required: Boolean,
        override val validationRules: List<String>,
        val physicalDimension: PhysicalDimension?,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val quantitySpec: QuantitySpec? = null
    ) : StructuredTypedField

    @Serializable
    @SerialName("NUMBER")
    data class NumberField(
        override val name: String,
        override val description: String,
        override val required: Boolean,
        override val validationRules: List<String>,
        val physicalDimension: PhysicalDimension?,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val quantitySpec: QuantitySpec? = null
    ) : StructuredTypedField

    @Serializable
    @SerialName("BOOLEAN")
    data class BooleanField(
        override val name: String,
        override val description: String,
        override val required: Boolean,
        override val validationRules: List<String>
    ) : StructuredTypedField

    @Serializable
    @SerialName("JSON")
    data class JsonField(
        override val name: String,
        override val description: String,
        override val required: Boolean,
        override val validationRules: List<String>
    ) : StructuredTypedField

    @Serializable
    @SerialName("OBJECT")
    data class ObjectField(
        override val name: String,
        override val description: String,
        override val required: Boolean,
        override val validationRules: List<String>,
        val objectFields: List<TypedField>
    ) : StructuredTypedField

    @Serializable
    @SerialName("ARRAY")
    data class ArrayField(
        override val name: String,
        override val description: String,
        override val required: Boolean,
        override val validationRules: List<String>,
        val minimumItems: Int,
        val maximumItems: Int,
        val items: StructuredArrayItems
    ) : StructuredTypedField
}

@Serializable
private sealed interface StructuredArrayItems {
    @Serializable
    @SerialName("STRING_ITEMS")
    data object StringItems : StructuredArrayItems

    @Serializable
    @SerialName("INTEGER_ITEMS")
    data class IntegerItems(
        val physicalDimension: PhysicalDimension?,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val quantitySpec: QuantitySpec? = null
    ) : StructuredArrayItems

    @Serializable
    @SerialName("NUMBER_ITEMS")
    data class NumberItems(
        val physicalDimension: PhysicalDimension?,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val quantitySpec: QuantitySpec? = null
    ) : StructuredArrayItems

    @Serializable
    @SerialName("BOOLEAN_ITEMS")
    data object BooleanItems : StructuredArrayItems

    @Serializable
    @SerialName("JSON_ITEMS")
    data object JsonItems : StructuredArrayItems

    @Serializable
    @SerialName("OBJECT_ITEMS")
    data class ObjectItems(val objectFields: List<TypedField>) : StructuredArrayItems
}

/**
 * Projects [TypedField] into a closed provider wire union whose discriminators own nested shape.
 *
 * The Kotlin API remains one validated data class. The wire contract makes cardinality and a
 * closed item contract mandatory only for `ARRAY`, makes nested fields mandatory only for object
 * values, excludes nested arrays, and removes inapplicable scalar properties.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object TypedFieldStructuredOutputSerializer : KSerializer<TypedField> {
    private val delegate = StructuredTypedField.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: TypedField) {
        encoder.encodeSerializableValue(delegate, value.toStructuredField())
    }

    override fun deserialize(decoder: Decoder): TypedField =
        decoder.decodeSerializableValue(delegate).toTypedField()

    private fun TypedField.toStructuredField(): StructuredTypedField {
        val sortedRules = validationRules.sorted()
        return when (type) {
            TypedFieldType.STRING -> StructuredTypedField.StringField(
                name = name,
                description = description,
                required = required,
                enumValues = enumValues.sorted(),
                validationRules = sortedRules
            )
            TypedFieldType.INTEGER -> StructuredTypedField.IntegerField(
                name = name,
                description = description,
                required = required,
                validationRules = sortedRules,
                physicalDimension = physicalDimension,
                quantitySpec = quantitySpec
            )
            TypedFieldType.NUMBER -> StructuredTypedField.NumberField(
                name = name,
                description = description,
                required = required,
                validationRules = sortedRules,
                physicalDimension = physicalDimension,
                quantitySpec = quantitySpec
            )
            TypedFieldType.BOOLEAN -> StructuredTypedField.BooleanField(
                name = name,
                description = description,
                required = required,
                validationRules = sortedRules
            )
            TypedFieldType.JSON -> StructuredTypedField.JsonField(
                name = name,
                description = description,
                required = required,
                validationRules = sortedRules
            )
            TypedFieldType.OBJECT -> StructuredTypedField.ObjectField(
                name = name,
                description = description,
                required = required,
                validationRules = sortedRules,
                objectFields = objectFields.sortedBy(TypedField::name)
            )
            TypedFieldType.ARRAY -> StructuredTypedField.ArrayField(
                name = name,
                description = description,
                required = required,
                validationRules = sortedRules,
                minimumItems = requireNotNull(minimumItems),
                maximumItems = requireNotNull(maximumItems),
                items = toStructuredArrayItems()
            )
        }
    }

    private fun StructuredTypedField.toTypedField(): TypedField = when (this) {
        is StructuredTypedField.StringField -> TypedField(
            name = name,
            type = TypedFieldType.STRING,
            description = description,
            required = required,
            enumValues = enumValues.toUniqueSet("enum value"),
            validationRules = validationRules.toUniqueSet("validation rule")
        )
        is StructuredTypedField.IntegerField -> TypedField(
            name = name,
            type = TypedFieldType.INTEGER,
            description = description,
            required = required,
            validationRules = validationRules.toUniqueSet("validation rule"),
            physicalDimension = physicalDimension,
            quantitySpec = quantitySpec
        )
        is StructuredTypedField.NumberField -> TypedField(
            name = name,
            type = TypedFieldType.NUMBER,
            description = description,
            required = required,
            validationRules = validationRules.toUniqueSet("validation rule"),
            physicalDimension = physicalDimension,
            quantitySpec = quantitySpec
        )
        is StructuredTypedField.BooleanField -> TypedField(
            name = name,
            type = TypedFieldType.BOOLEAN,
            description = description,
            required = required,
            validationRules = validationRules.toUniqueSet("validation rule")
        )
        is StructuredTypedField.JsonField -> TypedField(
            name = name,
            type = TypedFieldType.JSON,
            description = description,
            required = required,
            validationRules = validationRules.toUniqueSet("validation rule")
        )
        is StructuredTypedField.ObjectField -> TypedField(
            name = name,
            type = TypedFieldType.OBJECT,
            description = description,
            required = required,
            validationRules = validationRules.toUniqueSet("validation rule"),
            objectFields = objectFields.toUniqueObjectFields()
        )
        is StructuredTypedField.ArrayField -> TypedField(
            name = name,
            type = TypedFieldType.ARRAY,
            description = description,
            required = required,
            itemType = items.toTypedFieldType(),
            minimumItems = minimumItems,
            maximumItems = maximumItems,
            objectFields = (items as? StructuredArrayItems.ObjectItems)
                ?.objectFields
                ?.toUniqueObjectFields()
                .orEmpty(),
            validationRules = validationRules.toUniqueSet("validation rule"),
            physicalDimension = items.physicalDimension,
            quantitySpec = items.quantitySpec
        )
    }

    private fun TypedField.toStructuredArrayItems(): StructuredArrayItems =
        when (requireNotNull(itemType)) {
            TypedFieldType.STRING -> StructuredArrayItems.StringItems
            TypedFieldType.INTEGER ->
                StructuredArrayItems.IntegerItems(physicalDimension, quantitySpec)
            TypedFieldType.NUMBER ->
                StructuredArrayItems.NumberItems(physicalDimension, quantitySpec)
            TypedFieldType.BOOLEAN -> StructuredArrayItems.BooleanItems
            TypedFieldType.JSON -> StructuredArrayItems.JsonItems
            TypedFieldType.OBJECT -> StructuredArrayItems.ObjectItems(
                objectFields.sortedBy(TypedField::name)
            )
            TypedFieldType.ARRAY -> throw SerializationException(
                "Nested arrays require an explicit JSON field"
            )
        }

    private val StructuredArrayItems.physicalDimension: PhysicalDimension?
        get() = when (this) {
            is StructuredArrayItems.IntegerItems -> physicalDimension
            is StructuredArrayItems.NumberItems -> physicalDimension
            StructuredArrayItems.StringItems,
            StructuredArrayItems.BooleanItems,
            StructuredArrayItems.JsonItems,
            is StructuredArrayItems.ObjectItems -> null
        }

    private val StructuredArrayItems.quantitySpec: QuantitySpec?
        get() = when (this) {
            is StructuredArrayItems.IntegerItems -> quantitySpec
            is StructuredArrayItems.NumberItems -> quantitySpec
            StructuredArrayItems.StringItems,
            StructuredArrayItems.BooleanItems,
            StructuredArrayItems.JsonItems,
            is StructuredArrayItems.ObjectItems -> null
        }

    private fun StructuredArrayItems.toTypedFieldType(): TypedFieldType = when (this) {
        StructuredArrayItems.StringItems -> TypedFieldType.STRING
        is StructuredArrayItems.IntegerItems -> TypedFieldType.INTEGER
        is StructuredArrayItems.NumberItems -> TypedFieldType.NUMBER
        StructuredArrayItems.BooleanItems -> TypedFieldType.BOOLEAN
        StructuredArrayItems.JsonItems -> TypedFieldType.JSON
        is StructuredArrayItems.ObjectItems -> TypedFieldType.OBJECT
    }

    private fun List<String>.toUniqueSet(label: String): Set<String> {
        val values = linkedSetOf<String>()
        forEach { value ->
            if (!values.add(value)) {
                throw SerializationException("Duplicate typed-field $label '$value'")
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
                    "Duplicate typed-field object field '${field.name}'"
                )
            }
            fields.add(field)
        }
        return fields
    }
}

@Serializable
private sealed interface StructuredJsonValue {
    @Serializable
    @SerialName("null")
    data object NullValue : StructuredJsonValue

    @Serializable
    @SerialName("string")
    data class StringValue(val value: String) : StructuredJsonValue

    @Serializable
    @SerialName("boolean")
    data class BooleanValue(val value: Boolean) : StructuredJsonValue

    @Serializable
    @SerialName("integer")
    data class IntegerValue(val value: Long) : StructuredJsonValue

    @Serializable
    @SerialName("number")
    data class NumberValue(val value: Double) : StructuredJsonValue

    @Serializable
    @SerialName("array")
    data class ArrayValue(val values: List<StructuredJsonValue>) : StructuredJsonValue

    @Serializable
    @SerialName("object")
    data class ObjectValue(val entries: List<StructuredJsonObjectEntry>) : StructuredJsonValue
}

@Serializable
private data class StructuredJsonObjectEntry(
    val key: String,
    val value: StructuredJsonValue
)

internal data class TaggedJsonProviderSchemaContract(
    val nullDefinitionSerialName: String,
    val stringDefinitionSerialName: String,
    val booleanDefinitionSerialName: String,
    val integerDefinitionSerialName: String,
    val numberDefinitionSerialName: String,
    val arrayDefinitionSerialName: String,
    val objectDefinitionSerialName: String,
    val objectEntryDefinitionSerialName: String
)

/**
 * Encodes arbitrary JSON as a closed tagged union accepted by strict structured-output providers.
 *
 * The serializer is lossless for JSON values supported by the constrained tool DSL. Object keys
 * are represented as deterministic entry arrays because strict provider schemas cannot express
 * arbitrary-key objects.
 */
internal object TaggedJsonElementSerializer : KSerializer<JsonElement> {
    private val delegate = StructuredJsonValue.serializer()

    val providerSchemaContract = TaggedJsonProviderSchemaContract(
        nullDefinitionSerialName =
            StructuredJsonValue.NullValue.serializer().descriptor.serialName,
        stringDefinitionSerialName =
            StructuredJsonValue.StringValue.serializer().descriptor.serialName,
        booleanDefinitionSerialName =
            StructuredJsonValue.BooleanValue.serializer().descriptor.serialName,
        integerDefinitionSerialName =
            StructuredJsonValue.IntegerValue.serializer().descriptor.serialName,
        numberDefinitionSerialName =
            StructuredJsonValue.NumberValue.serializer().descriptor.serialName,
        arrayDefinitionSerialName =
            StructuredJsonValue.ArrayValue.serializer().descriptor.serialName,
        objectDefinitionSerialName =
            StructuredJsonValue.ObjectValue.serializer().descriptor.serialName,
        objectEntryDefinitionSerialName =
            StructuredJsonObjectEntry.serializer().descriptor.serialName
    )

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: JsonElement) {
        encoder.encodeSerializableValue(delegate, value.toStructuredValue())
    }

    override fun deserialize(decoder: Decoder): JsonElement =
        decoder.decodeSerializableValue(delegate).toJsonElement()

    private fun JsonElement.toStructuredValue(): StructuredJsonValue = when (this) {
        JsonNull -> StructuredJsonValue.NullValue
        is JsonArray -> StructuredJsonValue.ArrayValue(map { it.toStructuredValue() })
        is JsonObject -> StructuredJsonValue.ObjectValue(
            entries
                .sortedBy { it.key }
                .map { (key, value) ->
                    StructuredJsonObjectEntry(key, value.toStructuredValue())
                }
        )
        is JsonPrimitive -> when {
            isString -> StructuredJsonValue.StringValue(content)
            booleanOrNull != null -> StructuredJsonValue.BooleanValue(booleanOrNull!!)
            longOrNull != null -> StructuredJsonValue.IntegerValue(longOrNull!!)
            doubleOrNull?.isFinite() == true -> StructuredJsonValue.NumberValue(doubleOrNull!!)
            else -> throw SerializationException("Unsupported structured-output JSON primitive")
        }
    }

    private fun StructuredJsonValue.toJsonElement(): JsonElement = when (this) {
        StructuredJsonValue.NullValue -> JsonNull
        is StructuredJsonValue.StringValue -> JsonPrimitive(value)
        is StructuredJsonValue.BooleanValue -> JsonPrimitive(value)
        is StructuredJsonValue.IntegerValue -> JsonPrimitive(value)
        is StructuredJsonValue.NumberValue -> {
            if (!value.isFinite()) {
                throw SerializationException("Structured-output JSON number must be finite")
            }
            JsonPrimitive(value)
        }
        is StructuredJsonValue.ArrayValue -> JsonArray(values.map { it.toJsonElement() })
        is StructuredJsonValue.ObjectValue -> {
            val result = linkedMapOf<String, JsonElement>()
            entries.forEach { entry ->
                if (result.containsKey(entry.key)) {
                    throw SerializationException(
                        "Duplicate structured-output JSON object key '${entry.key}'"
                    )
                }
                result[entry.key] = entry.value.toJsonElement()
            }
            JsonObject(result)
        }
    }
}
