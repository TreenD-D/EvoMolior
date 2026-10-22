@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ToolExecutionFailure
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolLifecycleEvent
import com.achulkov.evomolior.core.atlas.ToolLifecyclePolicy
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.ToolVersionTransitionDetails
import com.achulkov.evomolior.core.atlas.TypedToolSchemaValidator
import com.achulkov.evomolior.core.contract.CostLedgerEntry
import com.achulkov.evomolior.core.contract.CostLedgerEntryKind
import com.achulkov.evomolior.core.contract.CostSubjectType
import com.achulkov.evomolior.core.contract.MonetaryAmount
import com.achulkov.evomolior.core.contract.MonetaryAmountStatus
import com.achulkov.evomolior.sandbox.toolsandbox.ExecutionResult
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import com.achulkov.evomolior.substrate.tools.ToolMaterializer
import com.achulkov.evomolior.orchestration.repair.PrivatePromotionFailure
import com.achulkov.evomolior.core.repair.ToolRepairCode
import io.github.aakira.napier.Napier
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/**
 * Exact private promotion fixture resolved from the separately sealed grader authority.
 *
 * @param id Precommitted promotion-test ID.
 * @param input Exact sandbox input.
 * @param expectedOutput Exact expected JSON output.
 */
data class SealedPromotionTestFixture(
    val id: String,
    val input: JsonElement,
    val expectedOutput: JsonElement
)

/** Result of exact Atlas-bound tool promotion. */
sealed class ToolPromotionResult {
    /** Exact registry version and lifecycle authority were committed. */
    data class Promoted(val toolVersionId: String) : ToolPromotionResult()

    /**
     * Promotion failed before registry activation.
     *
     * [reason] is a receiver-safe stable code. Private fixture identity, expected/actual values,
     * output, paths, and prose never cross this boundary.
     *
     * @param reason Receiver-safe stable rejection code.
     * @param authority Authority class that rejected promotion.
     * @param privateFailure Closed private failure class, only for private promotion authority.
     * @param publicRepairCode Closed repair code emitted by deterministic public validation.
     */
    data class Rejected(
        val reason: String,
        val authority: ToolPromotionRejectionAuthority =
            ToolPromotionRejectionAuthority.PUBLIC_CONTRACT,
        val privateFailure: PrivatePromotionFailure? = null,
        val publicRepairCode: ToolRepairCode? = null
    ) : ToolPromotionResult() {
        init {
            require(
                (authority == ToolPromotionRejectionAuthority.PRIVATE_PROMOTION) ==
                    (privateFailure != null)
            ) {
                "Private promotion rejection authority requires exactly one typed failure"
            }
            require(
                publicRepairCode == null ||
                    authority == ToolPromotionRejectionAuthority.PUBLIC_CONTRACT
            ) {
                "Only deterministic public rejection authority may carry a public repair code"
            }
            require(privateFailure == null || publicRepairCode == null) {
                "A promotion rejection cannot mix public and private failure authority"
            }
        }
    }
}

/** Authority class for a safe tool-promotion rejection. */
enum class ToolPromotionRejectionAuthority {
    PUBLIC_CONTRACT,
    PRIVATE_PROMOTION,
    INFRASTRUCTURE
}

/** Typed parent transition committed atomically with an explicitly linked child promotion. */
data class ToolPromotionLineageTransition(
    val state: ToolLifecycleState,
    val maintenanceCost: Double,
    val reason: String
) {
    init {
        require(state in setOf(ToolLifecycleState.MAINTAINED, ToolLifecycleState.FORKED)) {
            "Child promotion lineage must be maintenance or fork authority"
        }
        require(maintenanceCost >= 0.0 && reason.isNotBlank()) {
            "Child promotion lineage requires bounded cost and a reason"
        }
    }
}

/**
 * Promotes tools only after exact Council-contract validation and real bounded sandbox execution.
 *
 * Candidate tests are public deterministic preflight authority only. Promotion always resolves
 * and executes the separately sealed private fixtures and never infers them from candidate tests.
 *
 * @param database Group substrate database.
 * @param runId Owning run.
 * @param groupId Owning group.
 * @param toolsMountHostPath Host path for active executable versions.
 * @param opportunityResolver Sealed Atlas opportunity lookup for a staged submission.
 * @param sealedPromotionTestResolver Private grader-authority fixture lookup. Promotion requires
 * this authority and never substitutes worker-authored lookalike tests.
 * @param repository Append-only tool infrastructure authority.
 */
class ToolPromotionService(
    private val database: EvoMoliorDatabase,
    private val runId: String,
    private val groupId: String,
    private val toolsMountHostPath: String,
    private val opportunityResolver: suspend (StagingSubmissionEntity) -> ToolOpportunityCommitment?,
    private val sealedPromotionTestResolver:
        (suspend (ToolOpportunityCommitment) -> List<SealedPromotionTestFixture>?)? = null,
    private val repository: ToolInfrastructureRepository =
        ToolInfrastructureRepository(database),
    private val costLedgerRepository: CostLedgerRepository = CostLedgerRepository(database),
    private val now: () -> Instant = { Clock.System.now() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val contractValidator = ToolPromotionContractValidator()

    /** Resolves the same exact opportunity authority used by promotion. */
    suspend fun resolveOpportunityAuthority(
        submission: StagingSubmissionEntity
    ): ToolOpportunityCommitment? = opportunityResolver(submission)

    /** Validates, executes, records, and atomically activates one staged tool version. */
    suspend fun promote(
        submission: StagingSubmissionEntity,
        criticAgentId: String,
        lineageParentVersionId: String? = null,
        lineageTransition: ToolPromotionLineageTransition? = null
    ): ToolPromotionResult {
        val commitment = opportunityResolver(submission)
            ?: return rejected("tool_promotion_unknown_opportunity")
        if (!commitment.hasValidHash() || commitment.runId != runId ||
            commitment.nodeId.value != submission.wipTaskRef
        ) {
            return rejected("tool_promotion_invalid_atlas_provenance")
        }
        val payload = runCatching {
            StrictToolPayloadJson.parseToJsonElement(submission.content) as? JsonObject
        }.getOrNull() ?: return rejected(
            "tool_promotion_invalid_json",
            ToolRepairCode.MISSING_REQUIRED_CONTRACT_FIELD
        )
        val parsed = when (val contract = contractValidator.validate(payload, commitment)) {
            is ToolPromotionContractValidationResult.Accepted -> contract.tool
            is ToolPromotionContractValidationResult.Rejected ->
                return rejected(contract.reason, contract.repairCode)
        }
        val sealedResolver = sealedPromotionTestResolver
            ?: return rejected("tool_promotion_missing_sealed_test_authority")
        val promotionTests = sealedResolver(commitment)
            ?.let { fixtures ->
            if (
                fixtures.size != commitment.opportunity.promotionTestIds.size ||
                fixtures.mapTo(linkedSetOf(), SealedPromotionTestFixture::id) !=
                commitment.opportunity.promotionTestIds ||
                fixtures.any {
                    TypedToolSchemaValidator.validationError(it.input, parsed.inputSchema) != null ||
                        TypedToolSchemaValidator.validationError(
                            it.expectedOutput,
                            parsed.outputSchema
                        ) != null
                }
            ) {
                return rejected("tool_promotion_sealed_test_authority_mismatch")
            }
            fixtures
        } ?: return rejected("tool_promotion_missing_sealed_test_authority")
        if ((lineageParentVersionId == null) != (lineageTransition == null)) {
            return rejected("tool_promotion_lineage_transition_pair_required")
        }
        val activeOpportunityVersions = repository.activeVersionsForOpportunity(
            runId = runId,
            groupId = groupId,
            opportunityId = commitment.opportunity.opportunityId
        )
        if (activeOpportunityVersions.size > 1) {
            return rejected(
                "tool_promotion_opportunity_has_multiple_active_versions:" +
                    activeOpportunityVersions.joinToString(",") { it.id }.take(400)
            )
        }
        val activeOpportunityVersion = activeOpportunityVersions.singleOrNull()
        val activeOpportunityLifecycle = activeOpportunityVersion?.let { active ->
            repository.lifecycle(ToolVersionId(active.id))
        }
        if (
            activeOpportunityVersion != null &&
            !isExactActivePromotedLifecycle(
                registryEntry = activeOpportunityVersion,
                history = requireNotNull(activeOpportunityLifecycle),
                commitment = commitment
            )
        ) {
            return rejected(
                "tool_promotion_opportunity_active_version_lacks_exact_authority:" +
                    activeOpportunityVersion.id
            )
        }
        if (lineageParentVersionId == null && activeOpportunityVersion != null) {
            return rejected(
                "tool_promotion_opportunity_already_active:${activeOpportunityVersion.id}"
            )
        }
        val lineageParent = lineageParentVersionId?.let { parentId ->
            database.toolRegistryDao().getById(parentId)
                ?.takeIf { it.runId == runId && it.groupId == groupId }
                ?: return rejected("tool_promotion_unknown_lineage_parent")
        }
        var lineageParentTail: ToolLifecycleEvent? = null
        if (lineageParent != null) {
            val parentHistory = activeOpportunityLifecycle
                ?.takeIf { activeOpportunityVersion?.id == lineageParent.id }
                ?: repository.lifecycle(ToolVersionId(lineageParent.id))
            val parentOrigin = parentHistory.firstOrNull()
                ?: return rejected("tool_promotion_lineage_parent_lacks_authority")
            if (parentOrigin.opportunityId != commitment.opportunity.opportunityId) {
                return rejected("tool_promotion_lineage_crosses_opportunity")
            }
            if (activeOpportunityVersion?.id != lineageParent.id) {
                return rejected("tool_promotion_lineage_parent_not_sole_active_version")
            }
            lineageParentTail = parentHistory.last()
        }
        val latestByName = database.toolRegistryDao()
            .getLatestVersionByName(runId, groupId, parsed.name)
        val version = repository.nextVersionNumber(parsed.name, latestByName?.version)
        val toolVersionId = ToolVersionId("${parsed.name}-v$version")
        val registryEntry = ToolRegistryEntryEntity(
            id = toolVersionId.value,
            name = parsed.name,
            version = version,
            authorAgentId = submission.agentId,
            inputSchemaJson = parsed.inputSchema.toString(),
            outputSchemaJson = parsed.outputSchema.toString(),
            domain = parsed.domain,
            status = "active",
            code = parsed.code,
            codeLanguage = parsed.language,
            generation = submission.generation,
            groupId = groupId,
            runId = runId,
            createdAtMs = now().toEpochMilliseconds(),
            previousVersionId = lineageParent?.id
        )

        repository.appendOpportunity(commitment)
        var previousLifecycle: ToolLifecycleEvent? = null
        suspend fun append(
            state: ToolLifecycleState,
            authorityReferences: Set<String>,
            contextNodeId: com.achulkov.evomolior.core.atlas.NodeId = commitment.nodeId
        ): ToolLifecycleEvent {
            val event = ToolLifecycleEvent(
                eventId = idFactory(),
                runId = runId,
                opportunityId = commitment.opportunity.opportunityId,
                toolVersionId = toolVersionId,
                groupId = AtlasGroupId(groupId),
                originNodeId = commitment.nodeId,
                contextNodeId = contextNodeId,
                state = state,
                sequence = (previousLifecycle?.sequence ?: -1) + 1,
                previousEventHash = previousLifecycle?.eventHash,
                authorityReferenceIds = authorityReferences,
                recordedAt = now()
            ).withComputedHash()
            repository.appendLifecycle(event)
            previousLifecycle = event
            return event
        }

        append(ToolLifecycleState.TOOL_SPEC, setOf(commitment.commitmentHash))
        append(
            ToolLifecycleState.IMPLEMENTED,
            setOf(AtlasHashing.hashText(parsed.code))
        )
        append(
            ToolLifecycleState.STATIC_VALIDATED,
            setOf(
                AtlasHashing.hashText(parsed.inputSchema.toString()),
                AtlasHashing.hashText(parsed.outputSchema.toString())
            ) + promotionTests.map(SealedPromotionTestFixture::id)
        )

        val validationRoot = withContext(Dispatchers.IO) {
            Files.createTempDirectory(
                File(toolsMountHostPath).parentFile?.toPath(),
                "tool-promotion-"
            ).toFile()
        }
        try {
            val executable = ToolMaterializer(validationRoot).materialize(registryEntry)
            val sandbox = ToolSubSandbox(
                workspaceDir = File(validationRoot, "workspace"),
                maxOutputBytes = commitment.opportunity.maximumOutputBytes.toBoundedInt(),
                timeoutMs = commitment.opportunity.maximumRuntimeMillis
            )
            val successfulExecutionIds = linkedSetOf<String>()
            for (test in promotionTests) {
                val executionId = idFactory()
                val reservationId = "tool-cost:$executionId"
                val subjectType = if (
                    commitment.opportunity.opportunityType ==
                    com.achulkov.evomolior.core.atlas.ToolOpportunityType.SIMULATION
                ) {
                    CostSubjectType.SIMULATION
                } else {
                    CostSubjectType.TOOL_EXECUTION
                }
                val reservation = CostLedgerEntry(
                    entryId = "$reservationId:reserved",
                    reservationId = reservationId,
                    runId = runId,
                    groupId = AtlasGroupId(groupId),
                    agentId = criticAgentId,
                    subjectType = subjectType,
                    subjectId = executionId,
                    kind = CostLedgerEntryKind.RESERVED,
                    reservedMaximumUsd = 0.0,
                    occurredAt = now()
                ).withComputedHash()
                runCatching { costLedgerRepository.reserve(reservation) }.getOrElse { error ->
                    Napier.w(error) {
                        "[$groupId] Tool promotion cost reservation failed for ${submission.id}"
                    }
                    return rejected("tool_promotion_cost_reservation_failed")
                }
                val startedAt = now()
                val result = runCatching {
                    sandbox.execute(executable.absolutePath, test.input.toString())
                }.getOrElse { error ->
                    ExecutionResult(
                        stdout = "",
                        stderr = error.message.orEmpty(),
                        exitCode = -1,
                        truncated = false,
                        timedOut = false
                    )
                }
                val completedAt = now()
                val elapsedMillis =
                    (completedAt - startedAt).inWholeMilliseconds.coerceAtLeast(0)
                val finalization = CostLedgerEntry(
                    entryId = "$reservationId:finalized",
                    reservationId = reservationId,
                    runId = runId,
                    groupId = AtlasGroupId(groupId),
                    agentId = criticAgentId,
                    subjectType = subjectType,
                    subjectId = executionId,
                    kind = CostLedgerEntryKind.FINALIZED,
                    toolCost = MonetaryAmount(MonetaryAmountStatus.KNOWN, 0.0),
                    toolComputeMillis = elapsedMillis,
                    wallTimeMillis = elapsedMillis,
                    occurredAt = completedAt
                ).withComputedHash()
                runCatching { costLedgerRepository.finalize(finalization) }.getOrElse { error ->
                    Napier.w(error) {
                        "[$groupId] Tool promotion cost finalization failed for ${submission.id}"
                    }
                    return rejected("tool_promotion_cost_finalization_failed")
                }
                val output = result.stdout.trim()
                val outputValidation = ToolPromotionOutputValidator.validate(
                    stdout = output,
                    outputSchema = parsed.outputSchema,
                    expectedOutput = test.expectedOutput,
                    json = StrictToolPayloadJson
                )
                val outputElement = outputValidation.output
                val failure = classifyFailure(result, outputValidation.failure)
                val privateFailure = classifyPrivateFailure(result, outputValidation)
                val succeeded = failure == null
                val execution = ToolExecutionRecord(
                    executionId = executionId,
                    runId = runId,
                    toolVersionId = toolVersionId,
                    opportunityId = commitment.opportunity.opportunityId,
                    groupId = AtlasGroupId(groupId),
                    agentId = AtlasAgentId(criticAgentId),
                    round = submission.generation,
                    nodeId = commitment.nodeId,
                    purpose = ToolExecutionPurpose.PROMOTION_TEST,
                    inputHash = AtlasHashing.hashText(AtlasHashing.canonicalJson(test.input)),
                    outputHash = output.takeIf(String::isNotBlank)?.let(AtlasHashing::hashText),
                    outputJson = outputElement,
                    outputArtifactId = outputElement?.let {
                        "promotion-output:${toolVersionId.value}:${test.id}"
                    },
                    startedAt = startedAt,
                    completedAt = completedAt,
                    durationMillis = (completedAt - startedAt).inWholeMilliseconds.coerceAtLeast(0),
                    exitCode = result.exitCode,
                    result = if (succeeded) {
                        ToolExecutionResult.SUCCEEDED
                    } else {
                        ToolExecutionResult.FAILED
                    },
                    failure = failure,
                    sandboxRecordHash = AtlasHashing.hashText(
                        "${result.exitCode}|${result.timedOut}|${result.truncated}|" +
                            "${AtlasHashing.hashText(result.stderr)}"
                    )
                ).withComputedHash()
                repository.appendExecution(execution)
                if (!succeeded) {
                    return rejectedPrivate(requireNotNull(privateFailure))
                }
                successfulExecutionIds += execution.executionId
            }
            append(ToolLifecycleState.SANDBOX_TESTED, successfulExecutionIds)
            append(ToolLifecycleState.CRITIC_ACCEPTED, setOf(criticAgentId, submission.id))

            val finalExecutable = ToolMaterializer(File(toolsMountHostPath))
                .materialize(registryEntry)
            val promotedEvent = ToolLifecycleEvent(
                eventId = idFactory(),
                runId = runId,
                opportunityId = commitment.opportunity.opportunityId,
                toolVersionId = toolVersionId,
                groupId = AtlasGroupId(groupId),
                originNodeId = commitment.nodeId,
                contextNodeId = commitment.nodeId,
                state = ToolLifecycleState.PROMOTED,
                sequence = requireNotNull(previousLifecycle).sequence + 1,
                previousEventHash = requireNotNull(previousLifecycle).eventHash,
                authorityReferenceIds = setOf(
                    registryEntry.id,
                    AtlasHashing.hashText(registryEntry.code)
                ),
                recordedAt = now()
            ).withComputedHash()
            val parentTransitionEvent = lineageTransition?.let { transition ->
                val parentTail = requireNotNull(lineageParentTail)
                ToolLifecycleEvent(
                    eventId = idFactory(),
                    runId = parentTail.runId,
                    opportunityId = parentTail.opportunityId,
                    toolVersionId = parentTail.toolVersionId,
                    groupId = parentTail.groupId,
                    originNodeId = parentTail.originNodeId,
                    contextNodeId = parentTail.contextNodeId,
                    state = transition.state,
                    sequence = parentTail.sequence + 1,
                    previousEventHash = parentTail.eventHash,
                    authorityReferenceIds = setOf(toolVersionId.value),
                    transitionDetails = ToolVersionTransitionDetails(
                        childToolVersionId = toolVersionId,
                        maintenanceCost = transition.maintenanceCost,
                        reason = transition.reason
                    ),
                    recordedAt = now()
                ).withComputedHash().also { event ->
                    require(ToolLifecyclePolicy.allows(parentTail, event)) {
                        "Illegal parent transition for promoted child"
                    }
                }
            }
            return try {
                repository.activatePromotedVersion(
                    event = promotedEvent,
                    registryEntry = registryEntry,
                    expectedPreviousActiveVersionId = lineageParent?.id,
                    parentTransitionEvent = parentTransitionEvent
                )
                ToolPromotionResult.Promoted(registryEntry.id)
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { finalExecutable.delete() }
                Napier.w(error) {
                    "[$groupId] Tool promotion activation failed for ${submission.id}"
                }
                rejected("tool_promotion_activation_failed")
            }
        } finally {
            withContext(Dispatchers.IO) { validationRoot.deleteRecursively() }
        }
    }

    private fun classifyFailure(
        result: ExecutionResult,
        outputValidationFailure: String?
    ): ToolExecutionFailure? = when {
        result.timedOut -> ToolExecutionFailure.TIMEOUT
        result.truncated -> ToolExecutionFailure.OUTPUT_LIMIT_EXCEEDED
        result.exitCode != 0 -> ToolExecutionFailure.NON_ZERO_EXIT
        outputValidationFailure != null -> ToolExecutionFailure.INVALID_OUTPUT_SCHEMA
        else -> null
    }

    private fun classifyPrivateFailure(
        result: ExecutionResult,
        outputValidation: ToolPromotionOutputValidation
    ): PrivatePromotionFailure? = when {
        result.timedOut -> PrivatePromotionFailure.TIMEOUT
        result.truncated -> PrivatePromotionFailure.OUTPUT_LIMIT
        result.exitCode != 0 -> PrivatePromotionFailure.NON_ZERO_EXIT
        outputValidation.failureClass ==
            ToolPromotionOutputFailure.EXPECTED_OUTPUT_MISMATCH ->
            PrivatePromotionFailure.EXPECTED_OUTPUT_MISMATCH
        outputValidation.failureClass != null ->
            PrivatePromotionFailure.INVALID_OUTPUT_SCHEMA
        else -> null
    }

    private fun rejected(
        reason: String,
        publicRepairCode: ToolRepairCode? = null
    ): ToolPromotionResult.Rejected =
        ToolPromotionResult.Rejected(
            reason = reason,
            publicRepairCode = publicRepairCode
        )

    private fun rejectedPrivate(
        failure: PrivatePromotionFailure
    ): ToolPromotionResult.Rejected = ToolPromotionResult.Rejected(
        reason = "tool_promotion_private_test_failed",
        authority = ToolPromotionRejectionAuthority.PRIVATE_PROMOTION,
        privateFailure = failure
    )

    private fun isExactActivePromotedLifecycle(
        registryEntry: ToolRegistryEntryEntity,
        history: List<ToolLifecycleEvent>,
        commitment: ToolOpportunityCommitment
    ): Boolean {
        val first = history.firstOrNull() ?: return false
        return history.all(ToolLifecycleEvent::hasValidHash) &&
            ToolLifecyclePolicy.allows(null, first) &&
            history.zipWithNext().all { (previous, next) ->
                ToolLifecyclePolicy.allows(previous, next)
            } &&
            first.runId == runId &&
            first.opportunityId == commitment.opportunity.opportunityId &&
            first.toolVersionId.value == registryEntry.id &&
            first.groupId.value == groupId &&
            first.originNodeId == commitment.nodeId &&
            history.any { it.state == ToolLifecycleState.PROMOTED } &&
            history.last().state !in TERMINAL_INACTIVE_STATES
    }

    private fun Long.toBoundedInt(): Int =
        coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        val TERMINAL_INACTIVE_STATES = setOf(
            ToolLifecycleState.MAINTAINED,
            ToolLifecycleState.FORKED,
            ToolLifecycleState.RETIRED
        )
    }
}
