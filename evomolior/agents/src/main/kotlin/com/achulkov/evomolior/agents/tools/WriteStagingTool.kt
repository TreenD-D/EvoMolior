package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.budget.BudgetDeductResult
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.atlas.ToolCandidateWireCompilation
import com.achulkov.evomolior.core.atlas.ToolCandidateWireDraft
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.contract.HypothesisStatus
import com.achulkov.evomolior.core.contract.ProvisionalNovelHypothesis
import com.achulkov.evomolior.core.model.ActionType
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.substrate.db.dao.StagingDao
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

private const val MAX_STAGING_CONTENT_CHARS = 256_000
private const val MAX_DOC_SOURCE_FEEDBACK_CHARS = 8_000
private val strictToolAuthorityJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
}

/** Exact worker authority required before a TOOL candidate may enter staging. */
data class AssignedToolSubmissionAuthority(
    val nodeId: String,
    val opportunityId: String,
    val opportunityCommitmentHash: String
) {
    init {
        require(nodeId.isNotBlank() && opportunityId.isNotBlank()) {
            "Assigned TOOL authority requires node and opportunity identities"
        }
        require(opportunityCommitmentHash.matches(Regex("[0-9a-f]{64}"))) {
            "Assigned TOOL authority requires a lowercase SHA-256 commitment"
        }
    }
}

/**
 * Koog tool: submit content to the Staging Area.
 *
 * Cost: 1 budget unit (WRITE_STAGING). Requires a valid budget deduction before writing.
 *
 * @param stagingDao DAO for the current group's staging area.
 * @param budgetLedger The agent's budget ledger for deduction.
 * @param runId The current run ID.
 * @param groupId The current group ID.
 * @param agentId The current agent ID.
 * @param role The agent's role name.
 * @param generation The current generation number.
 * @param isOutlier Whether the agent is an Outlier.
 * @param requireAssignedToolAuthority Whether TOOL staging requires exact assigned authority.
 * @param assignedToolAuthority Exact assigned worker tool opportunity, when present.
 * @param toolCandidateWireCompiler Runtime-owned compiler from a short wire draft to the exact
 * legacy TOOL payload.
 * @param toolSubmissionPreflight Optional deterministic TOOL validation before Critic review.
 * @param docSourceCheck Optional advisory source check after DOC persistence; never rejects or
 * rewrites the stored submission.
 */
class WriteStagingTool(
    private val stagingDao: StagingDao,
    private val budgetLedger: BudgetLedger,
    private val runId: String,
    private val groupId: String,
    private val agentId: String,
    private val role: String,
    private val generation: Int,
    private val defaultWipTaskRef: String? = null,
    private val isOutlier: Boolean = false,
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE,
    private val requireAssignedToolAuthority: Boolean = false,
    private val assignedToolAuthority: AssignedToolSubmissionAuthority? = null,
    private val toolCandidateWireCompiler:
        (suspend (ToolCandidateWireDraft) -> ToolCandidateWireCompilation)? = null,
    private val toolSubmissionPreflight:
        suspend (StagingSubmissionEntity) -> String? = { null },
    private val docSourceCheck: (suspend (String) -> String?)? = null
) : SimpleTool<WriteStagingTool.Args>(
    argsType = typeToken<Args>(),
    name = "write_staging",
    description = """
        Submit content to the Staging Area for Critic review.
        Submission types: DOC, TOOL, FACT, PLAN_UPDATE, HYPOTHESIS, DISPATCH.
        HYPOTHESIS content must be the exact JSON representation of a provisional novel
        hypothesis. It must carry this run/group/agent/round identity, status=PROPOSED, and
        speculative=true. Hypotheses propagate for later testing but are never receiver evidence.
        For a generated tool workspace, submit type=TOOL with toolCandidate containing only the
        short opportunityHandle, generated sourceArtifactPath, entryPoint, optional
        supplementalTestsPath, declaredAssumptions, and consumedRepairDiagnosticIds. Leave content
        empty. The runtime supplies exact opportunity IDs, schemas, committed public tests, and
        canonical number bytes before existing deterministic preflight. Direct TOOL JSON is only
        available to legacy or non-workspace callers.
        Cost: 1 budget unit.
        Returns: submission ID on success.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        val type: String,
        val content: String = "",
        val toolCandidate: ToolCandidateWireDraft? = null,
        val wipTaskRef: String? = null,
        val speculative: Boolean = false
    )

    override suspend fun execute(args: Args): String {
        val submissionType = try {
            SubmissionType.valueOf(args.type)
        } catch (e: IllegalArgumentException) {
            val validTypes = SubmissionType.entries
                .filter { it != SubmissionType.DEATH_NOTE && it != SubmissionType.FAILURE_TELEMETRY }
                .joinToString(", ")
            return "Error: Invalid submission type '${args.type}'. Valid: $validTypes"
        }

        if (submissionType != SubmissionType.TOOL && args.toolCandidate != null) {
            return "Error: toolCandidate is valid only for TOOL submissions."
        }
        val compiledCandidate = if (submissionType == SubmissionType.TOOL &&
            args.toolCandidate != null
        ) {
            if (args.content.isNotBlank()) {
                return "Error: TOOL wire submissions must leave content empty."
            }
            val compiler = toolCandidateWireCompiler
                ?: return "Error: TOOL wire transport is unavailable for this lifecycle."
            when (val compilation = compiler(args.toolCandidate)) {
                is ToolCandidateWireCompilation.Compiled -> compilation.candidate
                is ToolCandidateWireCompilation.Rejected -> {
                    return "Error: TOOL wire rejected (${compilation.code}): " +
                        compilation.detail.take(500)
                }
            }
        } else {
            null
        }
        if (submissionType == SubmissionType.TOOL &&
            requireAssignedToolAuthority &&
            assignedToolAuthority == null
        ) {
            return "Error: TOOL submission requires an assigned sealed tool opportunity."
        }
        if (submissionType == SubmissionType.TOOL &&
            requireAssignedToolAuthority &&
            compiledCandidate == null
        ) {
            return "Error: Assigned TOOL submissions require the generated toolCandidate wire."
        }
        val effectiveContent = compiledCandidate?.content ?: args.content
        val explicitWipTaskRef = args.wipTaskRef?.takeIf { it.isNotBlank() }
        if (compiledCandidate != null &&
            explicitWipTaskRef != null &&
            explicitWipTaskRef != compiledCandidate.wipTaskRef
        ) {
            return "Error: TOOL wire wipTaskRef does not match its resolved handle authority."
        }
        val resolvedWipTaskRef = compiledCandidate?.wipTaskRef
            ?: explicitWipTaskRef
            ?: defaultWipTaskRef

        actionGate.intercept(
            AgentAction(
                type = ActionEventType.STAGING_WRITE,
                agentId = agentId,
                detail = args.type,
                sizeBytes = effectiveContent.toByteArray(Charsets.UTF_8).size
            )
        )
        if (effectiveContent.length > MAX_STAGING_CONTENT_CHARS) {
            return "Error: Content exceeds 256KB limit (${effectiveContent.length} chars)."
        }
        if (submissionType == SubmissionType.HYPOTHESIS) {
            if (!args.speculative) {
                return "Error: HYPOTHESIS submissions must set speculative=true."
            }
            val hypothesis = try {
                strictToolAuthorityJson.decodeFromString(
                    ProvisionalNovelHypothesis.serializer(),
                    effectiveContent
                )
            } catch (error: Exception) {
                return "Error: HYPOTHESIS content is invalid structured JSON: " +
                    error.message.orEmpty().take(300)
            }
            if (hypothesis.runId != runId ||
                hypothesis.groupId.value != groupId ||
                hypothesis.agentId.value != agentId ||
                hypothesis.round != generation
            ) {
                return "Error: HYPOTHESIS identity must exactly match this run, group, agent, and round."
            }
            if (hypothesis.status != HypothesisStatus.PROPOSED) {
                return "Error: HYPOTHESIS status must be PROPOSED at staging."
            }
            if (!hypothesis.hasValidHash()) {
                return "Error: HYPOTHESIS self-hash is missing or invalid."
            }
        }
        if (submissionType == SubmissionType.TOOL && requireAssignedToolAuthority) {
            val authority = requireNotNull(assignedToolAuthority)
            if (resolvedWipTaskRef != authority.nodeId) {
                return "Error: TOOL submission must reference its exact assigned Atlas node."
            }
            if (compiledCandidate != null &&
                compiledCandidate.opportunityCommitmentHash !=
                authority.opportunityCommitmentHash
            ) {
                return "Error: TOOL wire commitment does not match assigned authority."
            }
            val opportunityId = runCatching {
                (strictToolAuthorityJson.parseToJsonElement(effectiveContent) as? JsonObject)
                    ?.get("opportunityId")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
            if (opportunityId != authority.opportunityId) {
                return "Error: TOOL submission opportunityId does not match assigned authority."
            }
        }

        val deductResult = budgetLedger.deduct(ActionType.WRITE_STAGING)
        if (deductResult == BudgetDeductResult.BELOW_FLOOR) {
            return "Error: Budget exhausted. Cannot submit to staging."
        }

        val budgetSignature = budgetLedger.generateStagingToken(submissionType.name)
        val submissionId = UUID.randomUUID().toString()
        val toolCandidateAuthorityJson = compiledCandidate?.let { candidate ->
            AtlasHashing.canonicalJson(
                JsonObject(
                    mapOf(
                        "candidateHash" to JsonPrimitive(candidate.candidateHash),
                        "opportunityCommitmentHash" to
                            JsonPrimitive(candidate.opportunityCommitmentHash),
                        "sourceHash" to JsonPrimitive(candidate.sourceHash),
                        "supplementalTestsHash" to (
                            candidate.supplementalTestsHash?.let(::JsonPrimitive) ?: JsonNull
                            ),
                        "consumedRepairDiagnosticIds" to JsonArray(
                            candidate.consumedRepairDiagnosticIds
                                .sorted()
                                .map(::JsonPrimitive)
                        ),
                        "repairApproachVector" to (
                            candidate.repairApproachVector?.let { vector ->
                                strictToolAuthorityJson.encodeToJsonElement(
                                    com.achulkov.evomolior.core.repair.ApproachVector.serializer(),
                                    vector
                                )
                            } ?: JsonNull
                            )
                    )
                )
            )
        }

        val entity = StagingSubmissionEntity(
            id = submissionId,
            agentId = agentId,
            role = role,
            type = submissionType.name,
            content = effectiveContent,
            wipTaskRef = resolvedWipTaskRef,
            speculative = args.speculative,
            outlier = isOutlier,
            budgetSignature = budgetSignature,
            status = "pending",
            generation = generation,
            groupId = groupId,
            runId = runId,
            submittedAtMs = System.currentTimeMillis(),
            toolCandidateAuthorityJson = toolCandidateAuthorityJson
        )
        val preflightFailure = if (submissionType == SubmissionType.TOOL) {
            runCatching { toolSubmissionPreflight(entity) }
                .getOrElse { error ->
                    "tool_preflight_execution_error: ${error.message.orEmpty().take(300)}"
                }
                ?.take(2_000)
        } else {
            null
        }
        if (preflightFailure != null) {
            val reviewedAtMs = System.currentTimeMillis()
            stagingDao.insert(
                entity.copy(
                    status = "rejected",
                    rejectionReason = preflightFailure,
                    criticAgentId = "system-tool-preflight",
                    reviewedAtMs = reviewedAtMs
                )
            )
            io.github.aakira.napier.Napier.w {
                "[$groupId] TOOL $submissionId rejected by system preflight for " +
                    "agent=$agentId node=${resolvedWipTaskRef ?: "none"}: $preflightFailure"
            }
            return "Error: TOOL preflight rejected candidate (ID: $submissionId): " +
                "$preflightFailure Fix the exact defect and submit a new TOOL candidate."
        }

        stagingDao.insert(entity)

        val accepted = if (deductResult == BudgetDeductResult.FLOOR_REACHED) {
            "Submission accepted (ID: $submissionId) — WARNING: budget floor reached."
        } else {
            "Submission accepted (ID: $submissionId, type: ${submissionType.name}). Awaiting Critic review."
        }
        // The DOC remains staged even if the advisory checker cannot inspect its sources.
        val feedback = if (submissionType == SubmissionType.DOC) {
            try {
                docSourceCheck?.invoke(effectiveContent)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                io.github.aakira.napier.Napier.w(error) {
                    "[$groupId] Source advisory unavailable for staged DOC $submissionId"
                }
                "Source fidelity advisory unavailable; the DOC remains staged unchanged."
            }
        } else {
            null
        }
        return feedback?.takeIf { it.isNotBlank() }?.let {
            "$accepted\n\n${it.take(MAX_DOC_SOURCE_FEEDBACK_CHARS)}"
        } ?: accepted
    }
}
