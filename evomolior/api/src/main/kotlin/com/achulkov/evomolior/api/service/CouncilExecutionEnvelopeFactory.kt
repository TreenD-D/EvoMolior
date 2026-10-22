package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.GenerationCreditPolicy
import com.achulkov.evomolior.core.council.InclusiveIntRange
import com.achulkov.evomolior.core.council.ModelCapabilityCard
import com.achulkov.evomolior.core.council.SandboxCapabilitySummary
import com.achulkov.evomolior.core.council.SourceAccessPolicySummary
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.sandbox.network.SourceAccessPolicy
import java.security.MessageDigest
import java.util.Base64

/**
 * Builds the immutable runtime envelope supplied to every Genesis Council role.
 *
 * This factory is the production boundary between operator-owned [RunConfig] values and Council
 * design freedom. Counts are exact, model cards contain only backend-resolvable IDs, and model
 * overrides are included before field design rather than applied after compilation.
 */
/** Envelope operator-override key carrying [RunConfig.ordinaryAgentTtlSeconds]. */
const val ORDINARY_AGENT_TTL_OVERRIDE_KEY = "ordinaryAgentTtlSeconds"

object CouncilExecutionEnvelopeFactory {
    /**
     * Converts [config] into deterministic executable constraints.
     *
     * @throws IllegalArgumentException when a backend-specific model override cannot execute.
     */
    fun create(config: RunConfig): ExecutionEnvelope {
        require(config.maxAgentsPerGroup > 0) {
            "maxAgentsPerGroup must be positive"
        }
        val baseGenerationCredits = config.groupProfiles
            .maxOf { GroupProfile.default(it).spawnBudget } *
            config.maxAgentsPerGroup
        val maximumGenerationCredits = baseGenerationCredits * REWARDED_GENERATION_MULTIPLIER
        val cumulativeCap = config.budgetOverride
            ?: maximumGenerationCredits * config.rounds * config.groupCount
        val sourceContext = buildString {
            append(config.objective)
            config.contextArtifacts.mapNotNull { it.textPreview }
                .filter(String::isNotBlank)
                .forEach {
                    append('\n')
                    append(it)
                }
        }
        val availableModels = modelIds(config).map { modelId ->
            capabilityCard(config.llmBackend, modelId)
        }
        return ExecutionEnvelope(
            requiredWorkerGroupCount = InclusiveIntRange(config.groupCount, config.groupCount),
            requiredGraderGroupCount = InclusiveIntRange(
                config.graderGroupCountOverride ?: PRODUCTION_GRADER_GROUP_COUNT,
                config.graderGroupCountOverride ?: PRODUCTION_GRADER_GROUP_COUNT
            ),
            permittedProfiles = config.groupProfiles.toSet(),
            availableModels = availableModels,
            maxAgentsPerGroupPerRound = config.maxAgentsPerGroup,
            maxRounds = config.rounds,
            perGenerationCreditPolicy = GenerationCreditPolicy(
                baseCredits = baseGenerationCredits,
                maximumCredits = maximumGenerationCredits,
                carryoverFraction = 0.0
            ),
            cumulativeCostCap = cumulativeCap,
            enforceProviderMonetaryCostCap = config.enforceProviderMonetaryCostCap,
            allowedTopologyKinds = GroupTopologyKind.entries.toSet(),
            sourceAccessPolicy = SourceAccessPolicySummary(
                networkAccessEnabled = true,
                allowedDomains = SourceAccessPolicy.initialAllowedHosts(sourceContext),
                availableArtifactHashes = config.contextArtifacts.mapTo(sortedSetOf()) {
                    hashArtifact(it.contentBase64)
                },
                notes = listOf(
                    "Source egress remains subject to the run allowlist and constitutional gate."
                )
            ),
            sandboxCapabilities = SandboxCapabilitySummary(
                capabilityIds = setOf(
                    "browser",
                    "python",
                    "shell",
                    "source-fetch",
                    "tool-subprocess",
                    "workspace"
                ),
                outboundNetworkEnabled = true,
                maximumToolRuntimeSeconds = TOOL_SUB_SANDBOX_TIMEOUT_SECONDS,
                maximumWorkspaceBytes = SHURU_DEFAULT_DISK_BYTES
            ),
            outputSpec = config.toAtlasOutputSpec(),
            atomicNodeEffortBound = config.maxAgentsPerGroup,
            councilExecutionPurpose = config.councilExecutionPurpose,
            acceptanceProfile = config.acceptanceProfile,
            codexReasoningEffort = config.codexReasoningEffort,
            codexRuntimePreset = config.codexRuntimePreset,
            quantitativeToolObligation = config.quantitativeToolObligation,
            operatorOverrides = buildMap {
                put("llmBackend", config.llmBackend.id)
                put("codexReasoningEffort", config.codexReasoningEffort.configValue)
                config.codexRuntimePreset?.let { put("codexRuntimePreset", it.id) }
                put("workerGroupCount", config.groupCount.toString())
                put(
                    "workerProfiles",
                    config.groupProfiles.joinToString(",") { it.name }
                )
                put("rounds", config.rounds.toString())
                put("maxAgentsPerGroup", config.maxAgentsPerGroup.toString())
                config.ordinaryAgentTtlSeconds?.let {
                    put(ORDINARY_AGENT_TTL_OVERRIDE_KEY, it.toString())
                }
                config.modelOverrides.toSortedMap().forEach { (role, modelId) ->
                    put("model.$role", modelId)
                }
            }
        )
    }

    private fun modelIds(config: RunConfig): List<String> {
        val supported = when (config.llmBackend) {
            LlmBackend.OPENROUTER -> (
                ModelSelectionConfig.defaultRuntimeModelPools().values.flatten() +
                    ModelSelectionConfig.DEFAULT_GRADER_MODEL
                ).toSet()
            LlmBackend.QWEN_CLOUD -> setOf(
                ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL,
                ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL,
                ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL
            )
            LlmBackend.CODEX_CHATGPT -> config.codexRuntimePreset?.supportedModelIds
                ?: setOf(ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL)
        }
        val overrides = config.modelOverrides.values.toSet()
        if (config.llmBackend != LlmBackend.OPENROUTER) {
            val unsupported = overrides - supported
            require(unsupported.isEmpty()) {
                "${config.llmBackend.id} cannot execute model overrides: " +
                    unsupported.sorted().joinToString()
            }
        }
        return (supported + overrides).sorted()
    }

    private fun capabilityCard(
        backend: LlmBackend,
        modelId: String
    ): ModelCapabilityCard {
        val capabilities = buildSet {
            addAll(
                setOf(
                    "structured-output",
                    "research",
                    "planning",
                    "critique",
                    "judge",
                    "long-context"
                )
            )
            add("tool-use")
            if (backend == LlmBackend.CODEX_CHATGPT) {
                add("code-mode")
                add("native-search")
            } else {
                add("function-tools")
            }
            if (modelId.contains("deepseek", ignoreCase = true) ||
                modelId.contains("qwen", ignoreCase = true) ||
                backend == LlmBackend.CODEX_CHATGPT
            ) {
                add("coding")
            }
        }
        val codeReliability = if ("coding" in capabilities) 0.8 else 0.6
        return ModelCapabilityCard(
            modelId = modelId,
            providerId = backend.id,
            familyId = familyId(backend, modelId),
            capabilities = capabilities,
            structuredOutputReliability = 0.75,
            toolCallReliability = 0.75,
            codeBuildReliability = codeReliability,
            longContextSynthesisReliability = 0.75,
            judgeCalibrationScore = 0.7,
            evidenceSampleCount = 0
        )
    }

    private fun familyId(backend: LlmBackend, modelId: String): String = when (backend) {
        LlmBackend.CODEX_CHATGPT -> "openai-codex"
        LlmBackend.OPENROUTER -> modelId.substringBefore('/').ifBlank { "openrouter" }
        LlmBackend.QWEN_CLOUD -> modelId.substringBefore('-').ifBlank { "qwen-cloud" }
    }

    private fun RunConfig.toAtlasOutputSpec(): FinalOutputSpec {
        val requested = finalOutputSpec
        return FinalOutputSpec(
            relativePath = requested?.relativePath ?: "final-deliverable.md",
            mediaType = requested?.mediaType ?: "text/markdown",
            description = requested?.description.orEmpty(),
            requiredSections = requested?.validation
                ?.requiredMarkdownSectionSignals
                .orEmpty()
                .toSet(),
            minimumEvidenceItems = requested?.validation?.minimumPublicUrlCount ?: 0,
            maximumWordCount = requested?.validation?.maximumWordCount,
            exactMarkdownHeadings = requested?.validation?.exactMarkdownHeadings.orEmpty(),
            requiredTextStatements = requested?.validation?.requiredTextStatements.orEmpty(),
            minimumWordCount = requested?.validation?.minimumWordCount,
        )
    }

    private fun hashArtifact(contentBase64: String): String {
        val bytes = runCatching { Base64.getDecoder().decode(contentBase64) }
            .getOrElse { cause ->
                throw IllegalArgumentException("Run context artifact contains invalid base64", cause)
            }
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private const val PRODUCTION_GRADER_GROUP_COUNT = 3
    private const val REWARDED_GENERATION_MULTIPLIER = 1.5
    private const val TOOL_SUB_SANDBOX_TIMEOUT_SECONDS = 30
    private const val SHURU_DEFAULT_DISK_BYTES = 2L * 1024L * 1024L * 1024L
}
