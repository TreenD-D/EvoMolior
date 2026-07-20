package com.achulkov.evomolior.core.atlas

import com.achulkov.evomolior.core.serialization.TaggedJsonElementSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Closed constrained-DSL program kinds. */
@Serializable
enum class ToolDslKind {
    FORMULA,
    RULE,
    SIMULATION
}

/** Closed unary operations supported by the deterministic DSL runtime. */
@Serializable
enum class ToolDslUnaryOperation {
    NEGATE,
    NOT,
    ABSOLUTE,
    LENGTH
}

/** Closed binary operations supported by the deterministic DSL runtime. */
@Serializable
enum class ToolDslBinaryOperation {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    MINIMUM,
    MAXIMUM,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    EQUAL,
    NOT_EQUAL,
    AND,
    OR
}

/** Closed aggregate operations over typed input arrays. */
@Serializable
enum class ToolDslAggregateOperation {
    SUM,
    MEAN,
    MINIMUM,
    MAXIMUM,
    COUNT
}

/** One output field computed by a constrained DSL program. */
@Serializable
data class ToolDslOutput(
    val field: String,
    val expression: ToolDslExpression
) {
    init {
        require(field.matches(DSL_FIELD_NAME)) { "DSL output field is invalid" }
    }
}

/** Closed expression tree. No free-form source or operator names are accepted. */
@Serializable
sealed class ToolDslExpression {
    /** Reads one exact top-level typed input field. */
    @Serializable
    @SerialName("input")
    data class Input(val field: String) : ToolDslExpression() {
        init {
            require(field.matches(DSL_FIELD_NAME)) { "DSL input field is invalid" }
        }
    }

    /** Emits one bounded JSON literal. */
    @Serializable
    @SerialName("literal")
    data class Literal(
        @Serializable(with = TaggedJsonElementSerializer::class)
        val value: JsonElement
    ) : ToolDslExpression()

    /** Applies one closed unary operator. */
    @Serializable
    @SerialName("unary")
    data class Unary(
        val operation: ToolDslUnaryOperation,
        val operand: ToolDslExpression
    ) : ToolDslExpression()

    /** Applies one closed binary operator. */
    @Serializable
    @SerialName("binary")
    data class Binary(
        val operation: ToolDslBinaryOperation,
        val left: ToolDslExpression,
        val right: ToolDslExpression
    ) : ToolDslExpression()

    /** Selects one of two same-typed branches from a boolean condition. */
    @Serializable
    @SerialName("conditional")
    data class Conditional(
        val condition: ToolDslExpression,
        val whenTrue: ToolDslExpression,
        val whenFalse: ToolDslExpression
    ) : ToolDslExpression()

    /** Applies one closed aggregate to an array expression. */
    @Serializable
    @SerialName("aggregate")
    data class Aggregate(
        val operation: ToolDslAggregateOperation,
        val values: ToolDslExpression
    ) : ToolDslExpression()

    /** Draws one seeded uniform value; valid only in stochastic simulation programs. */
    @Serializable
    @SerialName("random_uniform")
    data class RandomUniform(
        val minimum: ToolDslExpression,
        val maximum: ToolDslExpression
    ) : ToolDslExpression()
}

/**
 * One complete, bounded constrained-DSL program.
 *
 * @param kind Program family matching the sealed opportunity implementation mode.
 * @param outputs Exact typed output bindings.
 * @param randomSeedInputField Explicit seed input for stochastic simulations.
 * @param version DSL contract version.
 */
@Serializable
data class ToolDslProgram(
    val kind: ToolDslKind,
    val outputs: List<ToolDslOutput>,
    val randomSeedInputField: String? = null,
    val version: Int = 1
) {
    init {
        require(version == 1) { "Unsupported DSL program version" }
        require(outputs.isNotEmpty()) { "DSL program requires outputs" }
        require(outputs.map(ToolDslOutput::field).distinct().size == outputs.size) {
            "DSL output bindings must be unique"
        }
        require(randomSeedInputField == null || randomSeedInputField.matches(DSL_FIELD_NAME)) {
            "DSL seed input field is invalid"
        }
    }
}

/** Validates closed DSL structure and static types against one sealed opportunity. */
object ToolDslValidator {
    /**
     * Validates the optional Council-authored reference formula on [opportunity].
     *
     * Absence remains valid at the core-contract layer for legacy/direct opportunities.
     * Orchestration is responsible for requiring this commitment before Atlas sealing.
     *
     * @param opportunity Opportunity whose optional reference formula is validated.
     * @return Stable validation error, or null when absent or valid.
     */
    fun referenceProgramValidationError(opportunity: ToolOpportunity): String? =
        opportunity.formulaDslReferenceProgram?.let { validationError(it, opportunity) }

    /** Returns a stable validation error or null when [program] matches [opportunity]. */
    fun validationError(program: ToolDslProgram, opportunity: ToolOpportunity): String? {
        if (program.kind != opportunity.preferredImplementationMode.dslKind) {
            return "wrong_program_kind"
        }
        if (program.randomSeedInputField !=
            opportunity.simulationRecordBindings?.randomSeedInputField
        ) {
            return "wrong_seed_binding"
        }
        val expectedOutputs = opportunity.requiredOutputs.associateBy(TypedField::name)
        if (program.outputs.mapTo(linkedSetOf(), ToolDslOutput::field) != expectedOutputs.keys) {
            return "wrong_output_bindings"
        }
        val inputTypes = opportunity.requiredInputs.associate { it.name to it.dslType }
        var visited = 0
        fun infer(expression: ToolDslExpression, depth: Int): DslType? {
            visited += 1
            if (visited > MAX_DSL_NODES || depth > MAX_DSL_DEPTH) return null
            return when (expression) {
                is ToolDslExpression.Input -> inputTypes[expression.field]
                is ToolDslExpression.Literal -> expression.value.dslType
                is ToolDslExpression.Unary -> {
                    val operand = infer(expression.operand, depth + 1) ?: return null
                    when (expression.operation) {
                        ToolDslUnaryOperation.NEGATE,
                        ToolDslUnaryOperation.ABSOLUTE -> operand.takeIf(DslType::isNumeric)
                        ToolDslUnaryOperation.NOT -> DslType.BOOLEAN.takeIf { operand == it }
                        ToolDslUnaryOperation.LENGTH -> DslType.DIMENSIONLESS_INTEGER.takeIf {
                            operand.type == TypedFieldType.ARRAY ||
                                operand.type == TypedFieldType.STRING
                        }
                    }
                }
                is ToolDslExpression.Binary -> {
                    val left = infer(expression.left, depth + 1) ?: return null
                    val right = infer(expression.right, depth + 1) ?: return null
                    when (expression.operation) {
                        ToolDslBinaryOperation.ADD,
                        ToolDslBinaryOperation.SUBTRACT,
                        ToolDslBinaryOperation.MINIMUM,
                        ToolDslBinaryOperation.MAXIMUM ->
                            numericResultWithEqualDimensions(left, right)
                        ToolDslBinaryOperation.MULTIPLY ->
                            numericResult(left, right, combineDimensions(left, right, 1))
                        ToolDslBinaryOperation.DIVIDE ->
                            numericResult(left, right, combineDimensions(left, right, -1))
                        ToolDslBinaryOperation.LESS_THAN,
                        ToolDslBinaryOperation.LESS_THAN_OR_EQUAL,
                        ToolDslBinaryOperation.GREATER_THAN,
                        ToolDslBinaryOperation.GREATER_THAN_OR_EQUAL ->
                            DslType.BOOLEAN.takeIf {
                                left.isNumeric() &&
                                    right.isNumeric() &&
                                    equalDimensionsWhenKnown(left.dimension, right.dimension)
                            }
                        ToolDslBinaryOperation.EQUAL,
                        ToolDslBinaryOperation.NOT_EQUAL -> DslType.BOOLEAN.takeIf {
                            left.sameRuntimeTypeAs(right) &&
                                (!left.isNumeric() ||
                                    equalDimensionsWhenKnown(left.dimension, right.dimension))
                        }
                        ToolDslBinaryOperation.AND,
                        ToolDslBinaryOperation.OR -> DslType.BOOLEAN.takeIf {
                            left == it && right == it
                        }
                    }
                }
                is ToolDslExpression.Conditional -> {
                    val condition = infer(expression.condition, depth + 1) ?: return null
                    val whenTrue = infer(expression.whenTrue, depth + 1) ?: return null
                    val whenFalse = infer(expression.whenFalse, depth + 1) ?: return null
                    whenTrue
                        .mergeEqualDimensions(whenFalse)
                        ?.takeIf { condition == DslType.BOOLEAN }
                }
                is ToolDslExpression.Aggregate -> {
                    val values = infer(expression.values, depth + 1) ?: return null
                    if (values.type != TypedFieldType.ARRAY) return null
                    when (expression.operation) {
                        ToolDslAggregateOperation.COUNT -> DslType.DIMENSIONLESS_INTEGER
                        ToolDslAggregateOperation.SUM,
                        ToolDslAggregateOperation.MEAN,
                        ToolDslAggregateOperation.MINIMUM,
                        ToolDslAggregateOperation.MAXIMUM ->
                            values.itemType
                                ?.let { DslType(it, dimension = values.itemDimension) }
                                ?.takeIf(DslType::isNumeric)
                    }
                }
                is ToolDslExpression.RandomUniform -> {
                    val minimum = infer(expression.minimum, depth + 1) ?: return null
                    val maximum = infer(expression.maximum, depth + 1) ?: return null
                    DslType.NUMBER.takeIf {
                        program.kind == ToolDslKind.SIMULATION &&
                            opportunity.behavior == ToolBehavior.STOCHASTIC &&
                            minimum.isNumeric() &&
                            maximum.isNumeric() &&
                            equalDimensionsWhenKnown(minimum.dimension, maximum.dimension)
                    }?.copy(
                        dimension = commonDimension(minimum.dimension, maximum.dimension)
                    )
                }
            }
        }
        program.outputs.forEach { output ->
            val actual = infer(output.expression, 0) ?: return "invalid_expression_${output.field}"
            val expectedField = expectedOutputs.getValue(output.field)
            val expected = expectedField.dslType
            if (!actual.assignableTo(expected)) return "wrong_output_type_${output.field}"
            expectedField.physicalDimension?.let { expectedDimension ->
                if (actual.effectiveDimension != expectedDimension) {
                    return "wrong_output_dimension_${output.field}"
                }
            }
        }
        return null
    }

    private fun numericResultWithEqualDimensions(left: DslType, right: DslType): DslType? {
        if (!equalDimensionsWhenKnown(left.dimension, right.dimension)) return null
        return numericResult(left, right, commonDimension(left.dimension, right.dimension))
    }

    private fun numericResult(
        left: DslType,
        right: DslType,
        dimension: PhysicalDimension?
    ): DslType? =
        if (!left.isNumeric() || !right.isNumeric()) null
        else if (left.type == TypedFieldType.INTEGER && right.type == TypedFieldType.INTEGER) {
            DslType(TypedFieldType.INTEGER, dimension = dimension)
        } else {
            DslType(TypedFieldType.NUMBER, dimension = dimension)
        }

    private fun combineDimensions(
        left: DslType,
        right: DslType,
        rightSign: Int
    ): PhysicalDimension? {
        if (!left.isNumeric() || !right.isNumeric()) return null
        val leftDimension = left.dimension ?: return null
        val rightDimension = right.dimension ?: return null
        val names = leftDimension.exponents.keys + rightDimension.exponents.keys
        val exponents = linkedMapOf<String, Int>()
        for (name in names.sorted()) {
            val exponent = leftDimension.exponents.getOrDefault(name, 0).toLong() +
                rightSign.toLong() * rightDimension.exponents.getOrDefault(name, 0).toLong()
            if (exponent !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
            if (exponent != 0L) exponents[name] = exponent.toInt()
        }
        return PhysicalDimension(exponents)
    }

    private fun equalDimensionsWhenKnown(
        left: PhysicalDimension?,
        right: PhysicalDimension?
    ): Boolean =
        // Null remains an unknown dimension for legacy/direct opportunities until Atlas sealing.
        left == null || right == null || left == right

    private fun commonDimension(
        left: PhysicalDimension?,
        right: PhysicalDimension?
    ): PhysicalDimension? = if (left != null && left == right) left else null

    private data class DslType(
        val type: TypedFieldType,
        val itemType: TypedFieldType? = null,
        val dimension: PhysicalDimension? = null,
        val itemDimension: PhysicalDimension? = null
    ) {
        fun isNumeric(): Boolean =
            type == TypedFieldType.INTEGER || type == TypedFieldType.NUMBER

        fun assignableTo(expected: DslType): Boolean =
            (type == expected.type && itemType == expected.itemType) ||
                (type == TypedFieldType.INTEGER &&
                    expected.type == TypedFieldType.NUMBER &&
                    expected.itemType == null)

        fun sameRuntimeTypeAs(other: DslType): Boolean =
            type == other.type && itemType == other.itemType

        fun mergeEqualDimensions(other: DslType): DslType? {
            if (!sameRuntimeTypeAs(other)) return null
            if (!equalDimensionsWhenKnown(dimension, other.dimension)) return null
            if (!equalDimensionsWhenKnown(itemDimension, other.itemDimension)) return null
            return copy(
                dimension = commonDimension(dimension, other.dimension),
                itemDimension = commonDimension(itemDimension, other.itemDimension)
            )
        }

        val effectiveDimension: PhysicalDimension?
            get() = when {
                isNumeric() -> dimension
                type == TypedFieldType.ARRAY &&
                    (itemType == TypedFieldType.INTEGER ||
                        itemType == TypedFieldType.NUMBER) -> itemDimension
                else -> null
            }

        companion object {
            val BOOLEAN = DslType(TypedFieldType.BOOLEAN)
            val INTEGER = DslType(TypedFieldType.INTEGER)
            val NUMBER = DslType(TypedFieldType.NUMBER)
            val DIMENSIONLESS_INTEGER = INTEGER.copy(dimension = PhysicalDimension())
        }
    }

    private val TypedField.dslType: DslType
        get() = if (type == TypedFieldType.ARRAY) {
            DslType(type, itemType, itemDimension = physicalDimension)
        } else {
            DslType(type, itemType, dimension = physicalDimension)
        }

    private val JsonElement.dslType: DslType?
        get() = when (this) {
            JsonNull -> null
            is JsonObject -> DslType(TypedFieldType.OBJECT)
            is JsonArray -> {
                val elementTypes = mapNotNull { it.dslType }.distinct()
                DslType(
                    TypedFieldType.ARRAY,
                    elementTypes.singleOrNull()?.type,
                    itemDimension = elementTypes
                        .singleOrNull()
                        ?.dimension
                )
            }
            is JsonPrimitive -> when {
                isString -> DslType(TypedFieldType.STRING)
                booleanOrNull != null -> DslType.BOOLEAN
                longOrNull != null -> DslType.DIMENSIONLESS_INTEGER
                doubleOrNull != null -> DslType.NUMBER.copy(dimension = PhysicalDimension())
                else -> null
            }
        }

    private val ToolImplementationMode.dslKind: ToolDslKind?
        get() = when (this) {
            ToolImplementationMode.FORMULA_DSL -> ToolDslKind.FORMULA
            ToolImplementationMode.RULE_DSL -> ToolDslKind.RULE
            ToolImplementationMode.SIMULATION_DSL -> ToolDslKind.SIMULATION
            ToolImplementationMode.SHELL,
            ToolImplementationMode.PYTHON -> null
        }

    private const val MAX_DSL_NODES = 1_000
    private const val MAX_DSL_DEPTH = 32
}

private val DSL_FIELD_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
