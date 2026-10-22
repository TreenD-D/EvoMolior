package com.achulkov.evomolior.orchestration.tooltransport

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityBinding
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCapability
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCatalog
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolProjectSkeleton
import com.achulkov.evomolior.core.atlas.ToolProjectSkeletonCompiler
import com.achulkov.evomolior.core.atlas.ToolRuntimeKind
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.NodeId

internal object ToolTransportTestFixture {
    val capability = ToolOpportunityBindingCapability(
        runId = "run-transport",
        groupId = "group-transport",
        round = 2,
        workerViewHash = "a".repeat(64)
    )
    val commitment = ToolOpportunityCommitment(
        runId = capability.runId,
        atlasHash = "atlas-origin-hash",
        nodeId = NodeId("node-tool"),
        opportunity = ToolOpportunity(
            opportunityId = "exact-opportunity-identifier",
            opportunityType = ToolOpportunityType.CALCULATION,
            repeatedQuestionPattern = "Calculate a canonical ratio.",
            requiredInputs = listOf(
                TypedField("count", TypedFieldType.INTEGER, "Exact count.")
            ),
            requiredOutputs = listOf(
                TypedField("ratio", TypedFieldType.NUMBER, "Canonical ratio.")
            ),
            validationStrategy = "Execute the public boundary case.",
            expectedReuseNodeIds = setOf(NodeId("node-later")),
            preferredImplementationMode = ToolImplementationMode.PYTHON,
            behavior = ToolBehavior.DETERMINISTIC,
            promotionTestIds = setOf("private-promotion-boundary"),
            maximumRuntimeMillis = 2_000,
            maximumOutputBytes = 4_096
        )
    ).withComputedHash()
    val skeleton: ToolProjectSkeleton = ToolProjectSkeletonCompiler.compile(commitment)
    val binding = ToolOpportunityBinding(
        handle = "T1",
        nodeId = commitment.nodeId.value,
        opportunityId = commitment.opportunity.opportunityId,
        opportunityHash = commitment.commitmentHash,
        inputSchemaHash = AtlasHashing.hashText(skeleton.inputSchemaJson),
        outputSchemaHash = AtlasHashing.hashText(skeleton.outputSchemaJson),
        publicTestIds = emptyList(),
        runtimeKind = ToolRuntimeKind.PYTHON,
        projectSkeletonHash = skeleton.skeletonHash
    )
    val catalog = ToolOpportunityBindingCatalog(
        catalogId = "catalog-transport",
        runId = capability.runId,
        groupId = capability.groupId,
        round = capability.round,
        workerViewHash = capability.workerViewHash,
        bindings = listOf(binding)
    ).withComputedHash()
    val publicTests = emptyList<com.achulkov.evomolior.core.atlas.ToolPublicContractTest>()
}
