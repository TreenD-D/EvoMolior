@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.achulkov.evomolior.core.serialization

import com.achulkov.evomolior.core.atlas.FormulaDslReferenceSemantics
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.SimulationRecordBindings
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolDslKind
import com.achulkov.evomolior.core.atlas.ToolDslOutput
import com.achulkov.evomolior.core.atlas.ToolDslProgram
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
private data class StructuredToolOpportunity(
    val opportunityId: String,
    val repeatedQuestionPattern: String,
    val requiredInputs: List<ExecutableTypedField>,
    val requiredOutputs: List<ExecutableTypedField>,
    val validationStrategy: String,
    val expectedReuseNodeIds: List<NodeId>,
    val implementation: StructuredToolImplementation,
    val execution: StructuredToolExecution,
    val requiredCapabilities: List<String>,
    val promotionTestIds: List<String>,
    val maximumRuntimeMillis: Long,
    val maximumOutputBytes: Long,
    val assumptions: List<String>,
    val knownInvalidDomains: List<String>
)

@Serializable
private sealed interface StructuredToolImplementation {
    @Serializable
    @SerialName("SHELL")
    data object Shell : StructuredToolImplementation

    @Serializable
    @SerialName("PYTHON")
    data object Python : StructuredToolImplementation

    @Serializable
    @SerialName("FORMULA_DSL")
    data class FormulaDsl(
        val formulaDslReferenceSemantics: FormulaDslReferenceSemantics,
        val formulaDslReferenceProgram: StructuredFormulaDslProgram
    ) : StructuredToolImplementation

    @Serializable
    @SerialName("RULE_DSL")
    data object RuleDsl : StructuredToolImplementation

    @Serializable
    @SerialName("SIMULATION_DSL")
    data object SimulationDsl : StructuredToolImplementation
}

@Serializable
private enum class StructuredFormulaDslKind {
    FORMULA
}

@Serializable
private data class StructuredFormulaDslProgram(
    val kind: StructuredFormulaDslKind,
    val outputs: List<ToolDslOutput>,
    val randomSeedInputField: String?,
    val version: Int
)

@Serializable
private sealed interface StructuredToolExecution {
    @Serializable
    @SerialName("CALCULATION")
    data class Calculation(val behavior: ToolBehavior) : StructuredToolExecution

    @Serializable
    @SerialName("EXTRACTION")
    data class Extraction(val behavior: ToolBehavior) : StructuredToolExecution

    @Serializable
    @SerialName("CONSISTENCY_CHECK")
    data class ConsistencyCheck(val behavior: ToolBehavior) : StructuredToolExecution

    @Serializable
    @SerialName("SIMULATION")
    data class Simulation(
        val simulationConfiguration: StructuredSimulationConfiguration
    ) : StructuredToolExecution

    @Serializable
    @SerialName("TRANSFORMATION")
    data class Transformation(val behavior: ToolBehavior) : StructuredToolExecution

    @Serializable
    @SerialName("VALIDATION")
    data class Validation(val behavior: ToolBehavior) : StructuredToolExecution

    @Serializable
    @SerialName("FORMULA")
    data class Formula(val behavior: ToolBehavior) : StructuredToolExecution

    @Serializable
    @SerialName("RULE")
    data class Rule(val behavior: ToolBehavior) : StructuredToolExecution
}

@Serializable
private sealed interface StructuredSimulationConfiguration {
    val resultSummaryOutputField: String
    val uncertaintyOutputField: String

    @Serializable
    @SerialName("DETERMINISTIC")
    data class Deterministic(
        override val resultSummaryOutputField: String,
        override val uncertaintyOutputField: String
    ) : StructuredSimulationConfiguration

    @Serializable
    @SerialName("STOCHASTIC")
    data class Stochastic(
        override val resultSummaryOutputField: String,
        override val uncertaintyOutputField: String,
        val randomSeedInputField: String
    ) : StructuredSimulationConfiguration
}

/**
 * Projects a Council-authored [ToolOpportunity] into closed conditional provider structures.
 *
 * The provider wire separates implementation and execution authority. Formula commitments exist
 * only in the `FORMULA_DSL` branch. Simulation bindings exist only in the `SIMULATION` branch,
 * whose deterministic and stochastic configurations respectively omit or require a seed field.
 * The domain type and its established Atlas/tool-commitment serialization remain unchanged.
 */
internal object ToolOpportunityStructuredOutputSerializer : KSerializer<ToolOpportunity> {
    private val delegate = StructuredToolOpportunity.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ToolOpportunity) {
        encoder.encodeSerializableValue(delegate, value.toStructuredOpportunity())
    }

    override fun deserialize(decoder: Decoder): ToolOpportunity =
        decoder.decodeSerializableValue(delegate).toToolOpportunity()

    private fun ToolOpportunity.toStructuredOpportunity(): StructuredToolOpportunity =
        StructuredToolOpportunity(
            opportunityId = opportunityId,
            repeatedQuestionPattern = repeatedQuestionPattern,
            requiredInputs = requiredInputs.map(::ExecutableTypedField),
            requiredOutputs = requiredOutputs.map(::ExecutableTypedField),
            validationStrategy = validationStrategy,
            expectedReuseNodeIds = expectedReuseNodeIds.sortedBy(NodeId::value),
            implementation = toStructuredImplementation(),
            execution = toStructuredExecution(),
            requiredCapabilities = requiredCapabilities.sorted(),
            promotionTestIds = promotionTestIds.sorted(),
            maximumRuntimeMillis = maximumRuntimeMillis,
            maximumOutputBytes = maximumOutputBytes,
            assumptions = assumptions,
            knownInvalidDomains = knownInvalidDomains
        )

    private fun ToolOpportunity.toStructuredImplementation(): StructuredToolImplementation =
        when (preferredImplementationMode) {
            ToolImplementationMode.SHELL -> StructuredToolImplementation.Shell
            ToolImplementationMode.PYTHON -> StructuredToolImplementation.Python
            ToolImplementationMode.FORMULA_DSL -> {
                val semantics = requireNotNull(formulaDslReferenceSemantics) {
                    "Council FORMULA_DSL opportunities require committed reference semantics"
                }
                val program = requireNotNull(formulaDslReferenceProgram) {
                    "Council FORMULA_DSL opportunities require a committed reference program"
                }
                StructuredToolImplementation.FormulaDsl(
                    formulaDslReferenceSemantics = semantics,
                    formulaDslReferenceProgram = program.toStructuredFormulaProgram()
                )
            }
            ToolImplementationMode.RULE_DSL -> StructuredToolImplementation.RuleDsl
            ToolImplementationMode.SIMULATION_DSL -> StructuredToolImplementation.SimulationDsl
        }

    private fun ToolDslProgram.toStructuredFormulaProgram(): StructuredFormulaDslProgram {
        require(kind == ToolDslKind.FORMULA) {
            "Council FORMULA_DSL reference programs must use the FORMULA kind"
        }
        return StructuredFormulaDslProgram(
            kind = StructuredFormulaDslKind.FORMULA,
            outputs = outputs,
            randomSeedInputField = randomSeedInputField,
            version = version
        )
    }

    private fun ToolOpportunity.toStructuredExecution(): StructuredToolExecution =
        when (opportunityType) {
            ToolOpportunityType.CALCULATION ->
                StructuredToolExecution.Calculation(behavior)
            ToolOpportunityType.EXTRACTION ->
                StructuredToolExecution.Extraction(behavior)
            ToolOpportunityType.CONSISTENCY_CHECK ->
                StructuredToolExecution.ConsistencyCheck(behavior)
            ToolOpportunityType.SIMULATION -> {
                val bindings = requireNotNull(simulationRecordBindings) {
                    "Council simulation opportunities require run-record bindings"
                }
                StructuredToolExecution.Simulation(
                    simulationConfiguration = when (behavior) {
                        ToolBehavior.DETERMINISTIC ->
                            StructuredSimulationConfiguration.Deterministic(
                                resultSummaryOutputField = bindings.resultSummaryOutputField,
                                uncertaintyOutputField = bindings.uncertaintyOutputField
                            )
                        ToolBehavior.STOCHASTIC ->
                            StructuredSimulationConfiguration.Stochastic(
                                resultSummaryOutputField = bindings.resultSummaryOutputField,
                                uncertaintyOutputField = bindings.uncertaintyOutputField,
                                randomSeedInputField = requireNotNull(
                                    bindings.randomSeedInputField
                                ) {
                                    "Council stochastic simulations require a seed binding"
                                }
                            )
                    }
                )
            }
            ToolOpportunityType.TRANSFORMATION ->
                StructuredToolExecution.Transformation(behavior)
            ToolOpportunityType.VALIDATION ->
                StructuredToolExecution.Validation(behavior)
            ToolOpportunityType.FORMULA ->
                StructuredToolExecution.Formula(behavior)
            ToolOpportunityType.RULE ->
                StructuredToolExecution.Rule(behavior)
        }

    private fun StructuredToolOpportunity.toToolOpportunity(): ToolOpportunity {
        val implementationProjection = implementation.toDomainProjection()
        val executionProjection = execution.toDomainProjection()
        return ToolOpportunity(
            opportunityId = opportunityId,
            opportunityType = executionProjection.opportunityType,
            repeatedQuestionPattern = repeatedQuestionPattern,
            requiredInputs = requiredInputs.map(ExecutableTypedField::value),
            requiredOutputs = requiredOutputs.map(ExecutableTypedField::value),
            validationStrategy = validationStrategy,
            expectedReuseNodeIds = expectedReuseNodeIds.toUniqueSet("expected reuse node ID"),
            preferredImplementationMode = implementationProjection.mode,
            behavior = executionProjection.behavior,
            requiredCapabilities = requiredCapabilities.toUniqueSet("required capability"),
            promotionTestIds = promotionTestIds.toUniqueSet("promotion test ID"),
            maximumRuntimeMillis = maximumRuntimeMillis,
            maximumOutputBytes = maximumOutputBytes,
            assumptions = assumptions,
            knownInvalidDomains = knownInvalidDomains,
            simulationRecordBindings = executionProjection.simulationRecordBindings,
            formulaDslReferenceSemantics =
                implementationProjection.formulaDslReferenceSemantics,
            formulaDslReferenceProgram = implementationProjection.formulaDslReferenceProgram
        )
    }

    private fun StructuredToolImplementation.toDomainProjection():
        ToolImplementationProjection = when (this) {
        StructuredToolImplementation.Shell -> ToolImplementationProjection(
            mode = ToolImplementationMode.SHELL
        )
        StructuredToolImplementation.Python -> ToolImplementationProjection(
            mode = ToolImplementationMode.PYTHON
        )
        is StructuredToolImplementation.FormulaDsl -> ToolImplementationProjection(
            mode = ToolImplementationMode.FORMULA_DSL,
            formulaDslReferenceSemantics = formulaDslReferenceSemantics,
            formulaDslReferenceProgram = formulaDslReferenceProgram.toDomainProgram()
        )
        StructuredToolImplementation.RuleDsl -> ToolImplementationProjection(
            mode = ToolImplementationMode.RULE_DSL
        )
        StructuredToolImplementation.SimulationDsl -> ToolImplementationProjection(
            mode = ToolImplementationMode.SIMULATION_DSL
        )
    }

    private fun StructuredFormulaDslProgram.toDomainProgram(): ToolDslProgram =
        ToolDslProgram(
            kind = when (kind) {
                StructuredFormulaDslKind.FORMULA -> ToolDslKind.FORMULA
            },
            outputs = outputs,
            randomSeedInputField = randomSeedInputField,
            version = version
        )

    private fun StructuredToolExecution.toDomainProjection(): ToolExecutionProjection =
        when (this) {
            is StructuredToolExecution.Calculation -> ToolExecutionProjection(
                opportunityType = ToolOpportunityType.CALCULATION,
                behavior = behavior
            )
            is StructuredToolExecution.Extraction -> ToolExecutionProjection(
                opportunityType = ToolOpportunityType.EXTRACTION,
                behavior = behavior
            )
            is StructuredToolExecution.ConsistencyCheck -> ToolExecutionProjection(
                opportunityType = ToolOpportunityType.CONSISTENCY_CHECK,
                behavior = behavior
            )
            is StructuredToolExecution.Simulation ->
                simulationConfiguration.toDomainProjection()
            is StructuredToolExecution.Transformation -> ToolExecutionProjection(
                opportunityType = ToolOpportunityType.TRANSFORMATION,
                behavior = behavior
            )
            is StructuredToolExecution.Validation -> ToolExecutionProjection(
                opportunityType = ToolOpportunityType.VALIDATION,
                behavior = behavior
            )
            is StructuredToolExecution.Formula -> ToolExecutionProjection(
                opportunityType = ToolOpportunityType.FORMULA,
                behavior = behavior
            )
            is StructuredToolExecution.Rule -> ToolExecutionProjection(
                opportunityType = ToolOpportunityType.RULE,
                behavior = behavior
            )
        }

    private fun StructuredSimulationConfiguration.toDomainProjection(): ToolExecutionProjection =
        when (this) {
            is StructuredSimulationConfiguration.Deterministic -> ToolExecutionProjection(
                opportunityType = ToolOpportunityType.SIMULATION,
                behavior = ToolBehavior.DETERMINISTIC,
                simulationRecordBindings = SimulationRecordBindings(
                    resultSummaryOutputField = resultSummaryOutputField,
                    uncertaintyOutputField = uncertaintyOutputField
                )
            )
            is StructuredSimulationConfiguration.Stochastic -> ToolExecutionProjection(
                opportunityType = ToolOpportunityType.SIMULATION,
                behavior = ToolBehavior.STOCHASTIC,
                simulationRecordBindings = SimulationRecordBindings(
                    resultSummaryOutputField = resultSummaryOutputField,
                    uncertaintyOutputField = uncertaintyOutputField,
                    randomSeedInputField = randomSeedInputField
                )
            )
        }

    private fun <T> List<T>.toUniqueSet(label: String): Set<T> {
        val values = linkedSetOf<T>()
        forEach { value ->
            if (!values.add(value)) {
                throw SerializationException("Duplicate tool-opportunity $label '$value'")
            }
        }
        return values
    }

    private data class ToolImplementationProjection(
        val mode: ToolImplementationMode,
        val formulaDslReferenceSemantics: FormulaDslReferenceSemantics? = null,
        val formulaDslReferenceProgram: ToolDslProgram? = null
    )

    private data class ToolExecutionProjection(
        val opportunityType: ToolOpportunityType,
        val behavior: ToolBehavior,
        val simulationRecordBindings: SimulationRecordBindings? = null
    )
}

/**
 * Nullable property serializer for the Council-local optional tool opportunity.
 */
internal object NullableToolOpportunityStructuredOutputSerializer : KSerializer<ToolOpportunity?> {
    private val delegate = ToolOpportunityStructuredOutputSerializer.nullable

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ToolOpportunity?) {
        encoder.encodeSerializableValue(delegate, value)
    }

    override fun deserialize(decoder: Decoder): ToolOpportunity? =
        decoder.decodeSerializableValue(delegate)
}
