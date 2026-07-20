package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.GenerationCreditPolicy
import com.achulkov.evomolior.core.council.InclusiveIntRange
import com.achulkov.evomolior.core.council.ModelCapabilityCard
import com.achulkov.evomolior.core.council.SandboxCapabilitySummary
import com.achulkov.evomolior.core.council.SourceAccessPolicySummary
import com.achulkov.evomolior.core.model.GroupProfileType

internal fun councilTestExecutionEnvelope(): ExecutionEnvelope = ExecutionEnvelope(
    requiredWorkerGroupCount = InclusiveIntRange(2, 2),
    requiredGraderGroupCount = InclusiveIntRange(2, 3),
    permittedProfiles = setOf(GroupProfileType.Library, GroupProfileType.Factory),
    availableModels = listOf(
        ModelCapabilityCard(
            modelId = "test/model",
            familyId = "test",
            capabilities = setOf("structured-output"),
            structuredOutputReliability = 1.0,
            toolCallReliability = 1.0,
            codeBuildReliability = 1.0,
            longContextSynthesisReliability = 1.0
        )
    ),
    maxAgentsPerGroupPerRound = 2,
    maxRounds = 2,
    perGenerationCreditPolicy = GenerationCreditPolicy(
        baseCredits = 100.0,
        maximumCredits = 120.0
    ),
    cumulativeCostCap = 500.0,
    allowedTopologyKinds = setOf(GroupTopologyKind.RING),
    sourceAccessPolicy = SourceAccessPolicySummary(networkAccessEnabled = false),
    sandboxCapabilities = SandboxCapabilitySummary(
        capabilityIds = setOf("shell"),
        maximumToolRuntimeSeconds = 30,
        maximumWorkspaceBytes = 1_000_000
    ),
    outputSpec = FinalOutputSpec(
        relativePath = "result.md",
        mediaType = "text/markdown"
    ),
    atomicNodeEffortBound = 2
)
