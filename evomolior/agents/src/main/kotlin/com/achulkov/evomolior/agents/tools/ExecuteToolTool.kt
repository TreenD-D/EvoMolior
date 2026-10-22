@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.budget.BudgetDeductResult
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.ActionType
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.SimulationRunRecord
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolExecutionFailure
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolLifecycleEvent
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.TypedToolSchemaValidator
import com.achulkov.evomolior.core.contract.CostLedgerEntry
import com.achulkov.evomolior.core.contract.CostLedgerEntryKind
import com.achulkov.evomolior.core.contract.CostSubjectType
import com.achulkov.evomolior.core.contract.MonetaryAmount
import com.achulkov.evomolior.core.contract.MonetaryAmountStatus
import com.achulkov.evomolior.core.contract.WorkerToolCard
import com.achulkov.evomolior.sandbox.network.ContentSanitiser
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.db.dao.ToolRegistryDao
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import java.util.UUID
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate

/**
 * Koog tool: execute a tool from the Tool Registry in the tool sub-sandbox.
 *
 * Cost: 2 on success, 4 on failure, 8 on retry (same tool + args).
 *
 * @param toolRegistryDao DAO for tool registry lookups.
 * @param toolSubSandbox The sub-sandbox for nested process execution.
 * @param budgetLedger The agent's budget ledger.
 * @param runId The current run ID.
 * @param groupId The current group ID.
 * @param toolsMountPath Path to the /tools mount inside the agent VM.
 */
class ExecuteToolTool(
    private val toolRegistryDao: ToolRegistryDao,
    private val toolSubSandbox: ToolSubSandbox,
    private val budgetLedger: BudgetLedger,
    private val runId: String,
    private val groupId: String,
    private val toolsMountPath: String = "/tools",
    /** Sanitises tool stdout/stderr before returning to agent context (prevents prompt injection). */
    private val contentSanitiser: ContentSanitiser = ContentSanitiser(),
    private val agentId: String = "",
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE,
    private val assignedNodeId: NodeId? = null,
    private val availableToolCards: List<WorkerToolCard> = emptyList(),
    private val round: Int = 0,
    private val infrastructureRepository: ToolInfrastructureRepository? = null,
    private val costLedgerRepository: CostLedgerRepository? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val executionIdFactory: () -> String = { UUID.randomUUID().toString() }
) : SimpleTool<ExecuteToolTool.Args>(
    argsType = typeToken<Args>(),
    name = "execute_tool",
    description = """
        Execute a tool from the Tool Registry in an isolated sub-sandbox.
        Cost: 2 on success, 4 on failure, 8 for retry with identical arguments.
        Output capped at 50KB. Hard timeout: 30 seconds.
    """.trimIndent()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    data class Args(
        val toolName: String,
        val toolVersion: Int? = null,
        val argsJson: String
    )

    override suspend fun execute(args: Args): String {
        val tool = if (args.toolVersion != null) {
            toolRegistryDao.getById("${args.toolName}-v${args.toolVersion}")
        } else {
            toolRegistryDao.getLatestByName(runId, groupId, args.toolName)
        }

        if (tool == null) {
            return "Error: Tool '${args.toolName}' not found in Tool Registry."
        }
        if (tool.runId != runId || tool.groupId != groupId || tool.status != "active") {
            return "Error: Tool '${tool.name}' lacks active same-group registry authority."
        }
        val lifecycle = infrastructureRepository?.lifecycle(ToolVersionId(tool.id)).orEmpty()
        if (infrastructureRepository != null &&
            lifecycle.none { it.state == ToolLifecycleState.PROMOTED }
        ) {
            return "Error: Tool '${tool.name}' has no promoted execution authority."
        }
        val origin = lifecycle.firstOrNull()
        val nodeId = assignedNodeId
        if (infrastructureRepository != null && (origin == null || nodeId == null)) {
            return "Error: Tool execution requires exact opportunity and assigned-node authority."
        }
        val opportunityCommitment = if (infrastructureRepository != null) {
            origin?.opportunityId?.let { infrastructureRepository.opportunity(it) }
        } else {
            null
        }
        if (infrastructureRepository != null && opportunityCommitment == null) {
            return "Error: Tool '${tool.name}' has no sealed opportunity authority."
        }
        val executionCard = if (infrastructureRepository == null) {
            null
        } else {
            val exactNodeId = requireNotNull(nodeId)
            val exactCommitment = requireNotNull(opportunityCommitment)
            val matches = availableToolCards.filter { card ->
                card.toolId == tool.id && card.requiredForNodeId == exactNodeId
            }
            if (matches.size != 1) {
                return "Error: Tool '${tool.name}' lacks one exact worker-view execution card " +
                    "for assigned node '${exactNodeId.value}'."
            }
            val card = matches.single()
            if (
                !exactCommitment.hasValidHash() ||
                card.name != tool.name ||
                card.version != tool.version ||
                card.opportunityId != exactCommitment.opportunity.opportunityId ||
                !AtlasHashing.constantTimeEquals(
                    card.opportunityCommitmentHash,
                    exactCommitment.commitmentHash
                )
            ) {
                return "Error: Tool '${tool.name}' worker-view execution card does not match " +
                    "its exact promoted authority."
            }
            card
        }
        val executionPurpose =
            executionCard?.executionPurpose ?: ToolExecutionPurpose.AGENT_USE
        if (infrastructureRepository != null) {
            val exactCommitment = requireNotNull(opportunityCommitment)
            val exactNodeId = requireNotNull(nodeId)
            val authorized = when (executionPurpose) {
                ToolExecutionPurpose.VALIDATION_TEST ->
                    exactNodeId != exactCommitment.nodeId &&
                        exactNodeId !in exactCommitment.opportunity.expectedReuseNodeIds
                ToolExecutionPurpose.AGENT_USE ->
                    exactNodeId in exactCommitment.opportunity.expectedReuseNodeIds
                ToolExecutionPurpose.PROMOTION_TEST -> false
            }
            if (!authorized) {
                return "Error: Tool '${tool.name}' is not Council-authorized for " +
                    "${executionPurpose.name} on assigned node '${exactNodeId.value}'."
            }
        }
        if (infrastructureRepository != null &&
            (agentId.isBlank() || agentId == tool.authorAgentId)
        ) {
            return "Error: Tool '${tool.name}' worker execution requires an independent agent."
        }
        if (infrastructureRepository != null && round <= tool.generation) {
            return "Error: Tool '${tool.name}' worker execution requires a later round."
        }
        val parsedInput = runCatching { json.parseToJsonElement(args.argsJson) }.getOrNull()
        val inputSchema = runCatching {
            json.parseToJsonElement(tool.inputSchemaJson) as? kotlinx.serialization.json.JsonObject
        }.getOrNull()
        if (infrastructureRepository != null && (
                inputSchema == null ||
                    parsedInput == null ||
                    TypedToolSchemaValidator.validationError(parsedInput, inputSchema) != null
                )
        ) {
            return "Error: Tool '${tool.name}' input does not satisfy its promoted schema."
        }
        val simulationInputError = opportunityCommitment
            ?.opportunity
            ?.takeIf { it.opportunityType == com.achulkov.evomolior.core.atlas.ToolOpportunityType.SIMULATION }
            ?.let { opportunity ->
                validateSimulationInput(opportunity, parsedInput)
            }
        if (simulationInputError != null) {
            return "Error: Tool '${tool.name}' simulation input is invalid: $simulationInputError."
        }

        val argsHash = AtlasHashing.hashText(args.argsJson)
        val toolKey = "${args.toolName}:$argsHash"
        val executionId = executionIdFactory()
        val subjectType = if (
            opportunityCommitment?.opportunity?.opportunityType ==
            com.achulkov.evomolior.core.atlas.ToolOpportunityType.SIMULATION
        ) {
            CostSubjectType.SIMULATION
        } else {
            CostSubjectType.TOOL_EXECUTION
        }
        val reservationId = "tool-cost:$executionId"
        val reservationError = runCatching {
            reserveCost(
                executionId = executionId,
                reservationId = reservationId,
                subjectType = subjectType
            )
        }.exceptionOrNull()
        if (reservationError != null) {
            return "Error: Tool '${tool.name}' execution cost could not be reserved: " +
                reservationError.message.orEmpty().take(300)
        }

        val toolExecutablePath = "$toolsMountPath/${tool.name}-v${tool.version}"
        actionGate.intercept(
            AgentAction(
                type = ActionEventType.PROCESS_FORK,
                agentId = agentId,
                detail = toolExecutablePath,
                inToolSandbox = true
            )
        )

        // Wrap sandbox invocation so unexpected exceptions (e.g. process launch failure)
        // are still accounted for and returned as a structured error rather than a tool crash.
        val startedAt = Instant.fromEpochMilliseconds(nowEpochMillis())
        val result = try {
            toolSubSandbox.execute(toolExecutablePath, args.argsJson)
        } catch (e: Exception) {
            // Deduct failure budget for a sandbox-level exception, then surface as structured error.
            budgetLedger.deduct(ActionType.TOOL_EXEC_FAILURE, toolKey = toolKey)
            updateToolExecutionStats(
                tool = tool,
                success = false,
                failureDescription = "launch_failed: ${e.message?.take(300).orEmpty()}"
            )
            val completedAt = Instant.fromEpochMilliseconds(nowEpochMillis())
            val costFinalizationError = runCatching {
                finalizeCost(
                    executionId = executionId,
                    reservationId = reservationId,
                    subjectType = subjectType,
                    startedAt = startedAt,
                    completedAt = completedAt
                )
            }.exceptionOrNull()
            val persisted = persistExecution(
                executionId = executionId,
                toolId = tool.id,
                opportunityId = origin?.opportunityId,
                nodeId = nodeId,
                purpose = executionPurpose,
                inputHash = argsHash,
                output = null,
                outputJson = null,
                startedAt = startedAt,
                completedAt = completedAt,
                exitCode = null,
                result = ToolExecutionResult.FAILED,
                failure = ToolExecutionFailure.SANDBOX_FAILURE,
                sandboxRecordHash = AtlasHashing.hashText(e.message.orEmpty())
            )
            return "Error: Tool '${tool.name}' failed to launch" +
                persisted?.let { " (executionId=${it.executionId})" }.orEmpty() +
                ": ${e.message?.take(300)}" +
                costFinalizationError?.let {
                    "; cost finalization failed: ${it.message.orEmpty().take(300)}"
                }.orEmpty()
        }
        val completedAt = Instant.fromEpochMilliseconds(nowEpochMillis())
        val costFinalizationError = runCatching {
            finalizeCost(
                executionId = executionId,
                reservationId = reservationId,
                subjectType = subjectType,
                startedAt = startedAt,
                completedAt = completedAt
            )
        }.exceptionOrNull()
        val parsedOutput = runCatching {
            json.parseToJsonElement(result.stdout.trim())
        }.getOrNull()
        val outputSchema = runCatching {
            json.parseToJsonElement(tool.outputSchemaJson) as? kotlinx.serialization.json.JsonObject
        }.getOrNull()
        val outputSchemaFailure = if (infrastructureRepository == null) {
            false
        } else if (parsedOutput == null || outputSchema == null) {
            true
        } else {
            TypedToolSchemaValidator.validationError(parsedOutput, outputSchema) != null
        }
        val executionFailure = when {
            result.timedOut -> ToolExecutionFailure.TIMEOUT
            result.truncated -> ToolExecutionFailure.OUTPUT_LIMIT_EXCEEDED
            result.exitCode != 0 -> ToolExecutionFailure.NON_ZERO_EXIT
            outputSchemaFailure -> ToolExecutionFailure.INVALID_OUTPUT_SCHEMA
            else -> null
        }
        val executionSucceeded = executionFailure == null
        val executionRecord = persistExecution(
            executionId = executionId,
            toolId = tool.id,
            opportunityId = origin?.opportunityId,
            nodeId = nodeId,
            purpose = executionPurpose,
            inputHash = argsHash,
            output = result.stdout,
            outputJson = parsedOutput,
            startedAt = startedAt,
            completedAt = completedAt,
            exitCode = result.exitCode,
            result = if (executionSucceeded) {
                ToolExecutionResult.SUCCEEDED
            } else {
                ToolExecutionResult.FAILED
            },
            failure = executionFailure,
            sandboxRecordHash = AtlasHashing.hashText(
                "${result.exitCode}|${result.timedOut}|${result.truncated}|" +
                    AtlasHashing.hashText(result.stderr)
            )
        )
        if (infrastructureRepository != null && executionRecord == null) {
            budgetLedger.deduct(ActionType.TOOL_EXEC_FAILURE, toolKey = toolKey)
            return "Error: Tool '${tool.name}' execution authority could not be persisted."
        }
        if (costFinalizationError != null) {
            budgetLedger.deduct(ActionType.TOOL_EXEC_FAILURE, toolKey = toolKey)
            return "Error: Tool '${tool.name}' execution cost could not be finalized" +
                executionRecord?.let { " (executionId=${it.executionId})" }.orEmpty() +
                ": ${costFinalizationError.message.orEmpty().take(300)}"
        }
        if (executionSucceeded &&
            opportunityCommitment?.opportunity?.opportunityType ==
            com.achulkov.evomolior.core.atlas.ToolOpportunityType.SIMULATION
        ) {
            val simulationPersisted = appendSimulationRecord(
                repository = requireNotNull(infrastructureRepository),
                execution = requireNotNull(executionRecord),
                opportunity = opportunityCommitment.opportunity,
                input = parsedInput,
                output = parsedOutput
            )
            if (!simulationPersisted) {
                budgetLedger.deduct(ActionType.TOOL_EXEC_FAILURE, toolKey = toolKey)
                return "Error: Tool '${tool.name}' simulation authority could not be persisted."
            }
        }

        val actionType = when {
            executionSucceeded -> ActionType.TOOL_EXEC_SUCCESS
            else -> ActionType.TOOL_EXEC_FAILURE
        }

        val deductResult = budgetLedger.deduct(actionType, toolKey = toolKey)
        updateToolExecutionStats(
            tool = tool,
            success = executionSucceeded,
            failureDescription = if (executionSucceeded) null else {
                buildString {
                    append("exitCode=${result.exitCode}")
                    if (result.timedOut) append("; timedOut=true")
                    if (result.stderr.isNotBlank()) append("; stderr=${result.stderr.take(300)}")
                }
            }
        )

        // Sanitise stdout/stderr to prevent prompt injection from external tool output
        val sanitisedStdout = contentSanitiser.sanitise(result.stdout, sourceUrl = "tool:${tool.name}")
        val sanitisedStderr = if (result.stderr.isNotBlank()) {
            contentSanitiser.sanitise(result.stderr, sourceUrl = "tool-stderr:${tool.name}")
        } else null

        val response = buildString {
            appendLine("Tool: ${tool.name} v${tool.version}")
            executionRecord?.executionId?.let { appendLine("Execution ID: $it") }
            appendLine("Status: ${if (executionSucceeded) "SUCCESS" else "FAILURE"}")
            if (result.timedOut) appendLine("WARNING: Timed out after 30s")
            if (result.truncated) appendLine("WARNING: Output truncated to 50KB")
            if (sanitisedStdout.injectionDetected) appendLine("WARNING: Potential prompt injection detected in tool output — content sanitised.")
            if (sanitisedStdout.truncated) appendLine("WARNING: Tool output truncated by content sanitiser.")
            appendLine("Exit code: ${result.exitCode}")
            if (sanitisedStderr != null) appendLine("Stderr: ${sanitisedStderr.content}")
            appendLine("Output:")
            appendLine(sanitisedStdout.content)
        }

        return if (deductResult == BudgetDeductResult.FLOOR_REACHED) {
            "$response\nWARNING: Budget floor reached."
        } else {
            response
        }
    }

    private suspend fun persistExecution(
        executionId: String,
        toolId: String,
        opportunityId: String?,
        nodeId: NodeId?,
        purpose: ToolExecutionPurpose,
        inputHash: String,
        output: String?,
        outputJson: kotlinx.serialization.json.JsonElement?,
        startedAt: Instant,
        completedAt: Instant,
        exitCode: Int?,
        result: ToolExecutionResult,
        failure: ToolExecutionFailure?,
        sandboxRecordHash: String
    ): ToolExecutionRecord? {
        val repository = infrastructureRepository ?: return null
        val exactOpportunityId = opportunityId ?: return null
        val exactNodeId = nodeId ?: return null
        val record = ToolExecutionRecord(
            executionId = executionId,
            runId = runId,
            toolVersionId = ToolVersionId(toolId),
            opportunityId = exactOpportunityId,
            groupId = AtlasGroupId(groupId),
            agentId = AtlasAgentId(agentId),
            round = round,
            nodeId = exactNodeId,
            purpose = purpose,
            inputHash = inputHash,
            outputHash = output?.takeIf(String::isNotBlank)?.let(AtlasHashing::hashText),
            outputJson = outputJson,
            outputArtifactId = outputJson?.let { "execution-output:$executionId" },
            startedAt = startedAt,
            completedAt = completedAt,
            durationMillis = (completedAt - startedAt).inWholeMilliseconds.coerceAtLeast(0),
            exitCode = exitCode,
            result = result,
            failure = failure,
            sandboxRecordHash = sandboxRecordHash
        ).withComputedHash()
        return runCatching {
            if (
                result == ToolExecutionResult.SUCCEEDED &&
                purpose == ToolExecutionPurpose.AGENT_USE
            ) {
                repository.appendSuccessfulAgentExecution(
                    value = record,
                    lifecycleEventId = "execution-lifecycle:$executionId",
                    recordedAt = completedAt
                )
            } else {
                repository.appendExecution(record)
            }
            record
        }.getOrNull()
    }

    private suspend fun reserveCost(
        executionId: String,
        reservationId: String,
        subjectType: CostSubjectType
    ) {
        val repository = costLedgerRepository ?: return
        repository.reserve(
            CostLedgerEntry(
                entryId = "$reservationId:reserved",
                reservationId = reservationId,
                runId = runId,
                groupId = AtlasGroupId(groupId),
                agentId = agentId.takeIf(String::isNotBlank),
                subjectType = subjectType,
                subjectId = executionId,
                kind = CostLedgerEntryKind.RESERVED,
                reservedMaximumUsd = 0.0,
                occurredAt = Instant.fromEpochMilliseconds(nowEpochMillis())
            ).withComputedHash()
        )
    }

    private suspend fun finalizeCost(
        executionId: String,
        reservationId: String,
        subjectType: CostSubjectType,
        startedAt: Instant,
        completedAt: Instant
    ) {
        val repository = costLedgerRepository ?: return
        val elapsedMillis = (completedAt - startedAt).inWholeMilliseconds.coerceAtLeast(0)
        repository.finalize(
            CostLedgerEntry(
                entryId = "$reservationId:finalized",
                reservationId = reservationId,
                runId = runId,
                groupId = AtlasGroupId(groupId),
                agentId = agentId.takeIf(String::isNotBlank),
                subjectType = subjectType,
                subjectId = executionId,
                kind = CostLedgerEntryKind.FINALIZED,
                toolCost = MonetaryAmount(MonetaryAmountStatus.KNOWN, 0.0),
                toolComputeMillis = elapsedMillis,
                wallTimeMillis = elapsedMillis,
                occurredAt = completedAt
            ).withComputedHash()
        )
    }

    private fun validateSimulationInput(
        opportunity: com.achulkov.evomolior.core.atlas.ToolOpportunity,
        input: kotlinx.serialization.json.JsonElement?
    ): String? {
        val parameters = input as? JsonObject ?: return "parameters must be a JSON object"
        val bindings = opportunity.simulationRecordBindings
            ?: return "sealed simulation bindings are missing"
        return when (opportunity.behavior) {
            ToolBehavior.STOCHASTIC -> {
                val seedField = bindings.randomSeedInputField
                    ?: return "stochastic seed binding is missing"
                if (parameters[seedField]?.jsonPrimitive?.longOrNull == null) {
                    "field '$seedField' must contain an explicit integer seed"
                } else {
                    null
                }
            }
            ToolBehavior.DETERMINISTIC -> {
                if (bindings.randomSeedInputField != null) {
                    "deterministic simulation claims a seed binding"
                } else {
                    null
                }
            }
        }
    }

    private suspend fun appendSimulationRecord(
        repository: ToolInfrastructureRepository,
        execution: ToolExecutionRecord,
        opportunity: com.achulkov.evomolior.core.atlas.ToolOpportunity,
        input: kotlinx.serialization.json.JsonElement?,
        output: kotlinx.serialization.json.JsonElement?
    ): Boolean {
        val parameters = input as? JsonObject ?: return false
        val outputObject = output as? JsonObject ?: return false
        val bindings = opportunity.simulationRecordBindings ?: return false
        val summary = outputObject[bindings.resultSummaryOutputField]
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?.take(SIMULATION_TEXT_LIMIT)
            ?.takeIf(String::isNotBlank)
            ?: return false
        val uncertainty = outputObject[bindings.uncertaintyOutputField]
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?.take(SIMULATION_TEXT_LIMIT)
            ?.takeIf(String::isNotBlank)
            ?: return false
        val randomSeed = bindings.randomSeedInputField?.let { field ->
            parameters[field]?.jsonPrimitive?.longOrNull ?: return false
        }
        val outputArtifactId = execution.outputArtifactId ?: return false
        val simulation = SimulationRunRecord(
            simulationRunId = "simulation:${execution.executionId}",
            executionId = execution.executionId,
            toolVersionId = execution.toolVersionId,
            inputHash = execution.inputHash,
            parameters = parameters,
            assumptions = opportunity.assumptions,
            randomSeed = randomSeed,
            stochastic = opportunity.behavior == ToolBehavior.STOCHASTIC,
            outputArtifactId = outputArtifactId,
            resultSummary = summary,
            uncertainty = uncertainty,
            validationRefs = opportunity.promotionTestIds.sorted(),
            executedBy = execution.agentId
        ).withComputedHash()
        return runCatching { repository.appendSimulation(simulation) }.isSuccess
    }

    private suspend fun updateToolExecutionStats(
        tool: com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity,
        success: Boolean,
        failureDescription: String?
    ) {
        val sample = if (success) 1.0 else 0.0
        val updatedSuccessRate = ((tool.successRate * 0.8) + (sample * 0.2)).coerceIn(0.0, 1.0)
        val updatedFailures = if (success) {
            tool.failureLogJson
        } else {
            appendFailure(tool.failureLogJson, failureDescription.orEmpty())
        }
        toolRegistryDao.updateExecutionStats(
            id = tool.id,
            newSuccessRate = updatedSuccessRate,
            newFailureLogJson = updatedFailures
        )
    }

    private fun appendFailure(existingJson: String, failureDescription: String): String {
        val existing = runCatching {
            (json.parseToJsonElement(existingJson) as? JsonArray)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val sanitized = failureDescription
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(500)
        return buildJsonArray {
            (existing.takeLast(9) + JsonPrimitive(sanitized)).forEach { add(it) }
        }.toString()
    }

    private companion object {
        const val SIMULATION_TEXT_LIMIT = 2_000
    }
}
