package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.council.CouncilAgentRunner
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.CouncilRoleExecutionRequest
import com.achulkov.evomolior.core.council.CouncilSynthesisWireAuthority
import com.achulkov.evomolior.core.council.EcologistOutput
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.FieldCompileError
import com.achulkov.evomolior.core.council.IncentiveDesignerOutput
import com.achulkov.evomolior.core.council.KgArchitectOutput
import com.achulkov.evomolior.core.council.LocalLimitationNode
import com.achulkov.evomolior.core.council.ModelEcologistOutput
import com.achulkov.evomolior.core.council.PromptArchitectOutput
import com.achulkov.evomolior.core.council.SeedCuratorOutput
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.council.SynthesisOutput
import com.achulkov.evomolior.core.council.TaskAnatomistOutput
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.serialization.CouncilSynthesisContextualOutputSerializer
import com.achulkov.evomolior.core.serialization.TaskAnatomistContextualOutputSerializer
import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Runs the eight-role Genesis Council as a typed compiler front end.
 *
 * Task anatomy is established first, dependent specialist roles execute next, and synthesis runs
 * only after all seven specialist outputs decode and close under deterministic cross-role
 * validation. Closure and compiler failures retry only the roles named by typed errors. No
 * malformed output is rescued into a partial or default field.
 *
 * @param objective Exact operator objective.
 * @param executionEnvelope Immutable runtime constraints supplied before Council design.
 * @param compiler Deterministic field compiler.
 */
class GenesisCouncil(
    private val objective: String,
    private val executionEnvelope: ExecutionEnvelope,
    private val compiler: StartingFieldCompiler = StartingFieldCompiler()
) {
    /** Ordered Council roles used for progress reporting and deterministic execution. */
    val councilRoles: List<Role> = listOf(
        Role.CouncilTaskAnatomist,
        Role.CouncilIncentiveDesigner,
        Role.CouncilEcologist,
        Role.CouncilPromptArchitect,
        Role.CouncilKGArchitect,
        Role.CouncilModelEcologist,
        Role.CouncilSeedCurator,
        Role.CouncilSynthesis
    )

    /**
     * Runs, decodes, validates, compiles, and seals the Genesis Council output.
     *
     * Provider/runtime exceptions propagate to the startup boundary. Typed parse and compiler
     * failures are returned as [StartingFieldCompileResult.Invalid].
     *
     * @param runId Run identity used for deterministic Atlas and field IDs.
     * @param agentRunner Executes one role with a strict JSON task context.
     * @param inputArtifacts Operator-owned immutable run inputs committed into the fields.
     * @param onProgress Optional operator progress callback.
     * @param maxRetries Maximum malformed-role attempts and shared closure/compiler repair cycles.
     * @return A sealed worker/grader field pair or typed invalid result.
     */
    suspend fun runCompiled(
        runId: String,
        agentRunner: CouncilAgentRunner,
        inputArtifacts: List<RunInputArtifact> = emptyList(),
        onProgress: ((String) -> Unit)? = null,
        maxRetries: Int = DEFAULT_MAX_RETRIES
    ): StartingFieldCompileResult {
        require(maxRetries >= 0) { "maxRetries must be non-negative" }
        Napier.i("GenesisCouncil: starting typed field compilation for run=$runId")

        val anatomy = executeRole(
            role = Role.CouncilTaskAnatomist,
            outputs = PartialCouncilOutputs(),
            agentRunner = agentRunner,
            onProgress = onProgress,
            maxRetries = maxRetries
        )
        if (anatomy is RoleExecution.Failure) return invalid(anatomy.error)
        var outputs = PartialCouncilOutputs().with((anatomy as RoleExecution.Success).output)

        val specialists = coroutineScope {
            dependentSpecialistRoles.map { role ->
                async {
                    executeRole(
                        role = role,
                        outputs = outputs,
                        agentRunner = agentRunner,
                        onProgress = onProgress,
                        maxRetries = maxRetries
                    )
                }
            }.awaitAll()
        }
        val specialistErrors = specialists.filterIsInstance<RoleExecution.Failure>().map { it.error }
        if (specialistErrors.isNotEmpty()) return invalid(specialistErrors)
        specialists.filterIsInstance<RoleExecution.Success>().forEach { result ->
            outputs = outputs.with(result.output)
        }

        var repairCyclesUsed = 0
        when (
            val closure = closeSpecialistOutputs(
                runId = runId,
                initialOutputs = outputs,
                agentRunner = agentRunner,
                onProgress = onProgress,
                maxRetries = maxRetries,
                initialRepairCyclesUsed = repairCyclesUsed
            )
        ) {
            is SpecialistClosureResolution.Failure -> return closure.invalid
            is SpecialistClosureResolution.Closed -> {
                outputs = closure.outputs
                repairCyclesUsed = closure.repairCyclesUsed
            }
        }

        val synthesis = executeRole(
            role = Role.CouncilSynthesis,
            outputs = outputs,
            agentRunner = agentRunner,
            onProgress = onProgress,
            maxRetries = maxRetries
        )
        if (synthesis is RoleExecution.Failure) return invalid(synthesis.error)
        outputs = outputs.with((synthesis as RoleExecution.Success).output)

        var compiled = compileCurrentOutputs(runId, outputs, inputArtifacts)
        while (true) {
            val compilerInvalid =
                compiled as? StartingFieldCompileResult.Invalid ?: return compiled
            val roles = compilerInvalid.responsibleCouncilRoles.intersect(councilRoles.toSet())
            if (roles.isEmpty()) return compilerInvalid
            if (repairCyclesUsed >= maxRetries) return compilerInvalid
            repairCyclesUsed += 1
            onProgress?.invoke(
                "Council compiler repair $repairCyclesUsed/$maxRetries: " +
                    roles.joinToString { role ->
                        val errors = compilerInvalid.errorsFor(role)
                            .joinToString(separator = " | ") { error ->
                                "${error.path}: ${error.message}"
                            }
                        "${role.name} [$errors]"
                    }
            )

            val closureBeforeRepair = CouncilSpecialistClosureValidator.validate(
                runId = runId,
                envelope = executionEnvelope,
                bundle = outputs.toSpecialistBundle()
            )
            if (!closureBeforeRepair.isClosed) {
                return invalid(closureBeforeRepair.errors)
            }
            if (Role.CouncilTaskAnatomist in roles) {
                when (val repaired = executeRole(
                    role = Role.CouncilTaskAnatomist,
                    outputs = outputs,
                    agentRunner = agentRunner,
                    onProgress = onProgress,
                    maxRetries = maxRetries,
                    compilerErrors = compilerInvalid.errorsFor(Role.CouncilTaskAnatomist)
                )) {
                    is RoleExecution.Failure -> return invalid(repaired.error)
                    is RoleExecution.Success -> outputs = outputs.with(repaired.output)
                }
            }
            val refreshedCompilerInvalid = if (Role.CouncilTaskAnatomist in roles) {
                compileCurrentOutputs(runId, outputs, inputArtifacts) as?
                    StartingFieldCompileResult.Invalid
            } else {
                null
            }

            val specialistRolesToRepair = if (Role.CouncilTaskAnatomist in roles) {
                dependentSpecialistRoles
            } else {
                dependentSpecialistRoles.filter(roles::contains)
            }
            val rolesBeingRepaired = buildSet {
                if (Role.CouncilTaskAnatomist in roles) {
                    add(Role.CouncilTaskAnatomist)
                }
                addAll(specialistRolesToRepair)
            }
            val frozenRoleHashes = closureBeforeRepair.frozenValidRoleHashes
                .filterKeys { it !in rolesBeingRepaired }
            val repairedSpecialists = coroutineScope {
                specialistRolesToRepair.map { role ->
                    async {
                        executeRole(
                            role = role,
                            outputs = outputs,
                            agentRunner = agentRunner,
                            onProgress = onProgress,
                            maxRetries = maxRetries,
                            compilerErrors = if (Role.CouncilTaskAnatomist in roles) {
                                refreshedCompilerInvalid?.errorsFor(role).orEmpty()
                            } else {
                                compilerInvalid.errorsFor(role)
                            }
                        )
                    }
                }.awaitAll()
            }
            val repairErrors = repairedSpecialists
                .filterIsInstance<RoleExecution.Failure>()
                .map { it.error }
            if (repairErrors.isNotEmpty()) return invalid(repairErrors)
            repairedSpecialists.filterIsInstance<RoleExecution.Success>().forEach { result ->
                outputs = outputs.with(result.output)
            }

            val upstreamOutputsChanged =
                Role.CouncilTaskAnatomist in roles || repairedSpecialists.isNotEmpty()
            if (upstreamOutputsChanged) {
                val frozenErrors = CouncilSpecialistClosureValidator.verifyFrozenRoleHashes(
                    bundle = outputs.toSpecialistBundle(),
                    expectedHashes = frozenRoleHashes
                )
                if (frozenErrors.isNotEmpty()) return invalid(frozenErrors)
                when (
                    val closure = closeSpecialistOutputs(
                        runId = runId,
                        initialOutputs = outputs,
                        agentRunner = agentRunner,
                        onProgress = onProgress,
                        maxRetries = maxRetries,
                        initialRepairCyclesUsed = repairCyclesUsed
                    )
                ) {
                    is SpecialistClosureResolution.Failure -> return closure.invalid
                    is SpecialistClosureResolution.Closed -> {
                        outputs = closure.outputs
                        repairCyclesUsed = closure.repairCyclesUsed
                    }
                }
            }
            if (Role.CouncilSynthesis in roles || upstreamOutputsChanged) {
                val synthesisErrors = if (upstreamOutputsChanged) {
                    (
                        compileCurrentOutputs(runId, outputs, inputArtifacts) as?
                            StartingFieldCompileResult.Invalid
                        )
                        ?.errorsFor(Role.CouncilSynthesis)
                        .orEmpty()
                } else {
                    compilerInvalid.errorsFor(Role.CouncilSynthesis)
                }
                val contextMode = if (
                    Role.CouncilSynthesis in roles &&
                    !upstreamOutputsChanged
                ) {
                    CouncilContextMode.FOCUSED_SYNTHESIS_REPAIR
                } else {
                    CouncilContextMode.FULL_DEPENDENCIES
                }
                when (val repaired = executeRole(
                    role = Role.CouncilSynthesis,
                    outputs = outputs,
                    agentRunner = agentRunner,
                    onProgress = onProgress,
                    maxRetries = maxRetries,
                    compilerErrors = synthesisErrors,
                    contextMode = contextMode
                )) {
                    is RoleExecution.Failure -> return invalid(repaired.error)
                    is RoleExecution.Success -> outputs = outputs.with(repaired.output)
                }
            }

            compiled = compileCurrentOutputs(runId, outputs, inputArtifacts)
        }
    }

    /**
     * Compatibility wrapper returning only the worker field.
     *
     * Invalid Council material throws and must be handled by the startup failure boundary.
     */
    suspend fun run(
        runId: String,
        agentRunner: CouncilAgentRunner,
        inputArtifacts: List<RunInputArtifact> = emptyList(),
        onProgress: ((String) -> Unit)? = null,
        maxRetries: Int = DEFAULT_MAX_RETRIES
    ): StartingField = when (
        val result = runCompiled(runId, agentRunner, inputArtifacts, onProgress, maxRetries)
    ) {
        is StartingFieldCompileResult.Valid -> result.workerField
        is StartingFieldCompileResult.Invalid -> throw GenesisCouncilCompilationException(result)
    }

    private suspend fun executeRole(
        role: Role,
        outputs: PartialCouncilOutputs,
        agentRunner: CouncilAgentRunner,
        onProgress: ((String) -> Unit)?,
        maxRetries: Int,
        compilerErrors: List<FieldCompileError> = emptyList(),
        contextMode: CouncilContextMode = CouncilContextMode.FULL_DEPENDENCIES
    ): RoleExecution = executeRoleAttempt(
        role = role,
        outputs = outputs,
        agentRunner = agentRunner,
        onProgress = onProgress,
        maxRetries = maxRetries,
        attempt = 0,
        previousOutput = outputs.encodedElement(
            role,
            executionEnvelope.acceptanceProfile
        ),
        invalidOutput = null,
        compilerErrors = compilerErrors,
        latestDecodeError = null,
        contextMode = contextMode
    )

    private suspend fun closeSpecialistOutputs(
        runId: String,
        initialOutputs: PartialCouncilOutputs,
        agentRunner: CouncilAgentRunner,
        onProgress: ((String) -> Unit)?,
        maxRetries: Int,
        initialRepairCyclesUsed: Int
    ): SpecialistClosureResolution {
        var outputs = initialOutputs
        var repairCyclesUsed = initialRepairCyclesUsed
        var report = CouncilSpecialistClosureValidator.validate(
            runId = runId,
            envelope = executionEnvelope,
            bundle = outputs.toSpecialistBundle()
        )
        while (!report.isClosed) {
            if (repairCyclesUsed >= maxRetries) {
                return SpecialistClosureResolution.Failure(invalid(report.errors))
            }
            val roles = report.errorsByResponsibleRole.keys
                .intersect(specialistRoles.toSet())
            if (roles.isEmpty()) {
                return SpecialistClosureResolution.Failure(invalid(report.errors))
            }
            repairCyclesUsed += 1
            onProgress?.invoke(
                "Council specialist closure repair $repairCyclesUsed/$maxRetries: " +
                    roles.joinToString { role ->
                        val errors = report.errorsFor(role)
                            .joinToString(separator = " | ") { error ->
                                "${error.path}: ${error.message}"
                            }
                        "${role.name} [$errors]"
                    }
            )

            val taskRepair = Role.CouncilTaskAnatomist in roles
            val dependentRolesToRepair = if (taskRepair) {
                dependentSpecialistRoles
            } else {
                dependentSpecialistRoles.filter(roles::contains)
            }
            val rolesBeingRepaired = buildSet {
                if (taskRepair) add(Role.CouncilTaskAnatomist)
                addAll(dependentRolesToRepair)
            }
            val frozenRoleHashes = report.frozenValidRoleHashes
                .filterKeys { it !in rolesBeingRepaired }

            if (taskRepair) {
                when (val repaired = executeRole(
                    role = Role.CouncilTaskAnatomist,
                    outputs = outputs,
                    agentRunner = agentRunner,
                    onProgress = onProgress,
                    maxRetries = maxRetries,
                    compilerErrors = report.errorsFor(Role.CouncilTaskAnatomist)
                )) {
                    is RoleExecution.Failure ->
                        return SpecialistClosureResolution.Failure(invalid(repaired.error))
                    is RoleExecution.Success -> outputs = outputs.with(repaired.output)
                }
            }
            val refreshedReport = if (taskRepair) {
                CouncilSpecialistClosureValidator.validate(
                    runId = runId,
                    envelope = executionEnvelope,
                    bundle = outputs.toSpecialistBundle()
                )
            } else {
                report
            }

            val repairedSpecialists = coroutineScope {
                dependentRolesToRepair.map { role ->
                    async {
                        executeRole(
                            role = role,
                            outputs = outputs,
                            agentRunner = agentRunner,
                            onProgress = onProgress,
                            maxRetries = maxRetries,
                            compilerErrors = refreshedReport.errorsFor(role)
                        )
                    }
                }.awaitAll()
            }
            val repairErrors = repairedSpecialists
                .filterIsInstance<RoleExecution.Failure>()
                .map { it.error }
            if (repairErrors.isNotEmpty()) {
                return SpecialistClosureResolution.Failure(invalid(repairErrors))
            }
            repairedSpecialists.filterIsInstance<RoleExecution.Success>().forEach { result ->
                outputs = outputs.with(result.output)
            }

            val frozenErrors = CouncilSpecialistClosureValidator.verifyFrozenRoleHashes(
                bundle = outputs.toSpecialistBundle(),
                expectedHashes = frozenRoleHashes
            )
            if (frozenErrors.isNotEmpty()) {
                return SpecialistClosureResolution.Failure(invalid(frozenErrors))
            }
            report = CouncilSpecialistClosureValidator.validate(
                runId = runId,
                envelope = executionEnvelope,
                bundle = outputs.toSpecialistBundle()
            )
        }
        return SpecialistClosureResolution.Closed(outputs, repairCyclesUsed)
    }

    private suspend fun executeRoleAttempt(
        role: Role,
        outputs: PartialCouncilOutputs,
        agentRunner: CouncilAgentRunner,
        onProgress: ((String) -> Unit)?,
        maxRetries: Int,
        attempt: Int,
        previousOutput: JsonElement?,
        invalidOutput: String?,
        compilerErrors: List<FieldCompileError>,
        latestDecodeError: FieldCompileError?,
        contextMode: CouncilContextMode
    ): RoleExecution {
        val errors = compilerErrors + listOfNotNull(latestDecodeError)
        val repairing = previousOutput != null || invalidOutput != null || errors.isNotEmpty()
        onProgress?.invoke(
            "${role.name} ${if (repairing) "repair ${attempt + 1}" else "starting"}"
        )
        val synthesisAuthority = outputs.synthesisAuthorityFor(role)
        val rawOutput = agentRunner.run(
            CouncilRoleExecutionRequest(
                role = role,
                contextJson = buildContext(
                    role = role,
                    outputs = outputs,
                    previousOutput = previousOutput,
                    invalidOutput = invalidOutput,
                    errors = errors,
                    contextMode = contextMode
                ),
                acceptanceProfile = executionEnvelope.acceptanceProfile,
                synthesisWireAuthority = synthesisAuthority
            )
        )
        return when (
            val decoded = CouncilRoleOutputCodec.decode(
                role = role,
                rawOutput = rawOutput,
                synthesisAuthority = synthesisAuthority,
                acceptanceProfile = executionEnvelope.acceptanceProfile
            )
        ) {
            is CouncilRoleDecodeResult.Success -> {
                onProgress?.invoke("${role.name} complete")
                RoleExecution.Success(decoded.output)
            }
            is CouncilRoleDecodeResult.Failure -> {
                onProgress?.invoke(
                    "${role.name} output invalid: ${decoded.error.path}: ${decoded.error.message}"
                )
                if (attempt >= maxRetries) {
                    RoleExecution.Failure(decoded.error)
                } else {
                    executeRoleAttempt(
                        role = role,
                        outputs = outputs,
                        agentRunner = agentRunner,
                        onProgress = onProgress,
                        maxRetries = maxRetries,
                        attempt = attempt + 1,
                        previousOutput = previousOutput,
                        invalidOutput = rawOutput,
                        compilerErrors = compilerErrors,
                        latestDecodeError = decoded.error,
                        contextMode = contextMode
                    )
                }
            }
        }
    }

    private fun buildContext(
        role: Role,
        outputs: PartialCouncilOutputs,
        previousOutput: JsonElement?,
        invalidOutput: String?,
        errors: List<FieldCompileError>,
        contextMode: CouncilContextMode
    ): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            require(
                contextMode != CouncilContextMode.FOCUSED_SYNTHESIS_REPAIR ||
                    role == Role.CouncilSynthesis
            ) { "Focused Council context is valid only for Synthesis repair" }
            put("objective", JsonPrimitive(objective))
            put(
                "executionEnvelope",
                json.encodeToJsonElement(ExecutionEnvelope.serializer(), executionEnvelope)
            )
            if (role == Role.CouncilSynthesis) {
                put(
                    "sealedTestAuthorities",
                    json.encodeToJsonElement(
                        CouncilSynthesisWireAuthority.serializer(),
                        outputs.synthesisAuthorityFor(role)
                            ?: error("Council Synthesis requires Task-derived test authority")
                    )
                )
            }
            if (contextMode == CouncilContextMode.FULL_DEPENDENCIES) {
                put("dependencyOutputs", outputs.dependenciesFor(role))
            }
            if (previousOutput != null || invalidOutput != null || errors.isNotEmpty()) {
                put(
                    "repairRequest",
                    buildJsonObject {
                        put("responsibleRole", JsonPrimitive(role.name))
                        previousOutput?.let { put("previousOutput", it) }
                        invalidOutput?.let { put("invalidOutput", JsonPrimitive(it)) }
                        put(
                            "errors",
                            buildJsonArray {
                                errors.forEach { error ->
                                    add(
                                        json.encodeToJsonElement(
                                            FieldCompileError.serializer(),
                                            error
                                        )
                                    )
                                }
                            }
                        )
                        if (role == Role.CouncilSynthesis && errors.isNotEmpty()) {
                            val relatedNodes = outputs.relatedNodes(errors)
                            put(
                                "relatedNodeContracts",
                                buildJsonArray {
                                    relatedNodes.forEach { node ->
                                        add(
                                            json.encodeToJsonElement(
                                                LocalLimitationNode.serializer(),
                                                node
                                            )
                                        )
                                    }
                                }
                            )
                            put(
                                "generatedToolSchemas",
                                buildJsonArray {
                                    relatedNodes.forEach { node ->
                                        node.toolOpportunity?.let { opportunity ->
                                            add(
                                                buildJsonObject {
                                                    put("nodeKey", JsonPrimitive(node.key.value))
                                                    put(
                                                        "opportunityId",
                                                        JsonPrimitive(opportunity.opportunityId)
                                                    )
                                                    put(
                                                        "inputSchema",
                                                        json.parseToJsonElement(
                                                            TypedToolSchemaCompiler.compile(
                                                                opportunity.requiredInputs
                                                            )
                                                        )
                                                    )
                                                    put(
                                                        "outputSchema",
                                                        json.parseToJsonElement(
                                                            TypedToolSchemaCompiler.compile(
                                                                opportunity.requiredOutputs
                                                            )
                                                        )
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                )
            }
        }
    )

    private fun invalid(error: FieldCompileError): StartingFieldCompileResult.Invalid =
        invalid(listOf(error))

    private fun compileCurrentOutputs(
        runId: String,
        outputs: PartialCouncilOutputs,
        inputArtifacts: List<RunInputArtifact>
    ): StartingFieldCompileResult = compiler.compile(
        runId = runId,
        rawObjective = objective,
        envelope = executionEnvelope,
        outputs = outputs.toBundle().projectDeterministicCompilerAuthority(
            runId,
            executionEnvelope
        ),
        inputArtifacts = inputArtifacts
    )

    private fun invalid(errors: List<FieldCompileError>): StartingFieldCompileResult.Invalid =
        StartingFieldCompileResult.Invalid(
            errors = errors,
            responsibleCouncilRoles = errors.flatMapTo(linkedSetOf()) {
                it.responsibleCouncilRoles
            }
        )

    private fun StartingFieldCompileResult.Invalid.errorsFor(role: Role): List<FieldCompileError> =
        errors.filter { role in it.responsibleCouncilRoles }

    private sealed interface RoleExecution {
        data class Success(val output: DecodedCouncilRoleOutput) : RoleExecution
        data class Failure(val error: FieldCompileError) : RoleExecution
    }

    private sealed interface SpecialistClosureResolution {
        data class Closed(
            val outputs: PartialCouncilOutputs,
            val repairCyclesUsed: Int
        ) : SpecialistClosureResolution

        data class Failure(
            val invalid: StartingFieldCompileResult.Invalid
        ) : SpecialistClosureResolution
    }

    private enum class CouncilContextMode {
        FULL_DEPENDENCIES,
        FOCUSED_SYNTHESIS_REPAIR
    }

    private data class PartialCouncilOutputs(
        val taskAnatomist: TaskAnatomistOutput? = null,
        val incentiveDesigner: IncentiveDesignerOutput? = null,
        val ecologist: EcologistOutput? = null,
        val promptArchitect: PromptArchitectOutput? = null,
        val kgArchitect: KgArchitectOutput? = null,
        val modelEcologist: ModelEcologistOutput? = null,
        val seedCurator: SeedCuratorOutput? = null,
        val synthesis: SynthesisOutput? = null,
        val synthesisWireAuthority: CouncilSynthesisWireAuthority? = null
    ) {
        fun with(output: DecodedCouncilRoleOutput): PartialCouncilOutputs = when (output) {
            is DecodedCouncilRoleOutput.TaskAnatomist -> copy(taskAnatomist = output.value)
            is DecodedCouncilRoleOutput.IncentiveDesigner -> copy(incentiveDesigner = output.value)
            is DecodedCouncilRoleOutput.Ecologist -> copy(ecologist = output.value)
            is DecodedCouncilRoleOutput.PromptArchitect -> copy(promptArchitect = output.value)
            is DecodedCouncilRoleOutput.KgArchitect -> copy(kgArchitect = output.value)
            is DecodedCouncilRoleOutput.ModelEcologist -> copy(modelEcologist = output.value)
            is DecodedCouncilRoleOutput.SeedCurator -> copy(seedCurator = output.value)
            is DecodedCouncilRoleOutput.Synthesis -> copy(
                synthesis = output.value,
                synthesisWireAuthority = output.wireAuthority
            )
        }

        fun toBundle(): CouncilRoleOutputBundle = CouncilRoleOutputBundle(
            taskAnatomist = requireNotNull(taskAnatomist),
            incentiveDesigner = requireNotNull(incentiveDesigner),
            ecologist = requireNotNull(ecologist),
            promptArchitect = requireNotNull(promptArchitect),
            kgArchitect = requireNotNull(kgArchitect),
            modelEcologist = requireNotNull(modelEcologist),
            seedCurator = requireNotNull(seedCurator),
            synthesis = requireNotNull(synthesis)
        )

        fun toSpecialistBundle(): CouncilSpecialistBundle = CouncilSpecialistBundle(
            taskAnatomist = requireNotNull(taskAnatomist),
            incentiveDesigner = requireNotNull(incentiveDesigner),
            ecologist = requireNotNull(ecologist),
            promptArchitect = requireNotNull(promptArchitect),
            kgArchitect = requireNotNull(kgArchitect),
            modelEcologist = requireNotNull(modelEcologist),
            seedCurator = requireNotNull(seedCurator)
        )

        fun dependenciesFor(role: Role) = buildJsonObject {
            if (role != Role.CouncilTaskAnatomist) {
                taskAnatomist?.let {
                    putTyped(Role.CouncilTaskAnatomist, TaskAnatomistOutput.serializer(), it)
                }
            }
            if (role == Role.CouncilSynthesis) {
                incentiveDesigner?.let {
                    putTyped(Role.CouncilIncentiveDesigner, IncentiveDesignerOutput.serializer(), it)
                }
                ecologist?.let {
                    putTyped(Role.CouncilEcologist, EcologistOutput.serializer(), it)
                }
                promptArchitect?.let {
                    putTyped(Role.CouncilPromptArchitect, PromptArchitectOutput.serializer(), it)
                }
                kgArchitect?.let {
                    putTyped(Role.CouncilKGArchitect, KgArchitectOutput.serializer(), it)
                }
                modelEcologist?.let {
                    putTyped(Role.CouncilModelEcologist, ModelEcologistOutput.serializer(), it)
                }
                seedCurator?.let {
                    putTyped(Role.CouncilSeedCurator, SeedCuratorOutput.serializer(), it)
                }
            }
        }

        fun encodedElement(
            role: Role,
            acceptanceProfile: AcceptanceProfile
        ): JsonElement? =
            when (role) {
                Role.CouncilTaskAnatomist -> taskAnatomist?.let {
                    if (acceptanceProfile == AcceptanceProfile.NORMAL) {
                        json.encodeToJsonElement(TaskAnatomistOutput.serializer(), it)
                    } else {
                        TaskAnatomistContextualOutputSerializer(
                            acceptanceProfile
                        ).encodeProviderWire(it)
                    }
                }
                Role.CouncilIncentiveDesigner -> incentiveDesigner?.let {
                    json.encodeToJsonElement(IncentiveDesignerOutput.serializer(), it)
                }
                Role.CouncilEcologist -> ecologist?.let {
                    json.encodeToJsonElement(EcologistOutput.serializer(), it)
                }
                Role.CouncilPromptArchitect -> promptArchitect?.let {
                    json.encodeToJsonElement(PromptArchitectOutput.serializer(), it)
                }
                Role.CouncilKGArchitect -> kgArchitect?.let {
                    json.encodeToJsonElement(KgArchitectOutput.serializer(), it)
                }
                Role.CouncilModelEcologist -> modelEcologist?.let {
                    json.encodeToJsonElement(ModelEcologistOutput.serializer(), it)
                }
                Role.CouncilSeedCurator -> seedCurator?.let {
                    json.encodeToJsonElement(SeedCuratorOutput.serializer(), it)
                }
                Role.CouncilSynthesis -> synthesis?.let {
                    CouncilSynthesisContextualOutputSerializer(
                        requireNotNull(synthesisWireAuthority) {
                            "Decoded Council Synthesis output requires its wire authority"
                        }
                    ).encodeProviderWire(it)
                }
                else -> null
            }

        fun relatedNodes(errors: List<FieldCompileError>): List<LocalLimitationNode> {
            val nodesByKey = taskAnatomist?.nodes.orEmpty().associateBy(LocalLimitationNode::key)
            return errors.asSequence()
                .flatMap { it.relatedLocalNodeKeys.asSequence() }
                .distinct()
                .sortedBy { it.value }
                .mapNotNull(nodesByKey::get)
                .toList()
        }

        fun synthesisAuthorityFor(role: Role): CouncilSynthesisWireAuthority? =
            if (role == Role.CouncilSynthesis) {
                taskAnatomist?.let(CouncilSynthesisWireAuthority::from)
            } else {
                null
            }

        private fun <T> kotlinx.serialization.json.JsonObjectBuilder.putTyped(
            role: Role,
            serializer: KSerializer<T>,
            value: T
        ) {
            put(role.name, json.encodeToJsonElement(serializer, value))
        }
    }

    private companion object {
        const val DEFAULT_MAX_RETRIES = 5
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }
        val dependentSpecialistRoles = listOf(
            Role.CouncilIncentiveDesigner,
            Role.CouncilEcologist,
            Role.CouncilPromptArchitect,
            Role.CouncilKGArchitect,
            Role.CouncilModelEcologist,
            Role.CouncilSeedCurator
        )
    }
}

/**
 * Fail-closed compatibility exception for callers that request only a worker field.
 *
 * @param invalid Typed compiler failure.
 */
class GenesisCouncilCompilationException(
    val invalid: StartingFieldCompileResult.Invalid
) : IllegalStateException(
    invalid.errors.joinToString(
        prefix = "Genesis Council compilation failed: ",
        separator = "; "
    ) { "${it.path}: ${it.message}" }
)
