package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasNode
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind

/** Deterministically compiles the Council worker Atlas into a separate grader frontier. */
internal object GraderAtlasCompiler {
    /**
     * Builds the sealed Grader Atlas before any worker reveal.
     *
     * @param runId Owning run.
     * @param receiver Exact receiver contract.
     * @param workerAtlas Authoritative worker limitation Atlas.
     * @return Sealed grader frontier.
     */
    fun compile(
        runId: String,
        receiver: ReceiverContract,
        workerAtlas: NodeAtlas
    ): GraderAtlas {
        val receiverRefs = workerAtlas.nodes.values
            .mapTo(linkedSetOf()) { it.receiverWhyRef }
            .ifEmpty { receiver.successOutcomes.toCollection(linkedSetOf()) }
        val workerNodeIds = workerAtlas.nodes.keys
        val nodeIds = GraderNodeKind.entries.associateWith { kind ->
            GraderNodeId(
                CouncilDeterministicIds.create(
                    "grader-node",
                    runId,
                    kind.name.lowercase()
                )
            )
        }
        val rootId = nodeIds.getValue(GraderNodeKind.RECEIVER_COVERAGE)
        val nodes = GraderNodeKind.entries.associate { kind ->
            val nodeId = nodeIds.getValue(kind)
            nodeId to GraderAtlasNode(
                nodeId = nodeId,
                kind = kind,
                title = kind.name.lowercase().replace('_', ' '),
                evaluationQuestion = evaluationQuestion(kind),
                receiverObligationRefs = receiverRefs,
                mappedWorkerNodeIds = workerNodeIds,
                prerequisiteNodeIds = if (nodeId == rootId) emptySet() else setOf(rootId),
                requiredRequirementCount = 1
            ).withComputedHash()
        }
        return GraderAtlas(
            graderAtlasId = CouncilDeterministicIds.create("grader-atlas", runId),
            runId = runId,
            version = workerAtlas.version,
            workerAtlasHash = workerAtlas.atlasHash,
            rootNodeIds = setOf(rootId),
            nodes = nodes
        ).withComputedHash()
    }

    private fun evaluationQuestion(kind: GraderNodeKind): String = when (kind) {
        GraderNodeKind.RECEIVER_COVERAGE ->
            "Which observable requirements prove every receiver obligation?"
        GraderNodeKind.FAILURE_CHAIN_DEPTH ->
            "Which causal failure chains and severity thresholds can falsify the work?"
        GraderNodeKind.EVIDENCE_AUTHENTICITY ->
            "How will the evaluator detect unsupported, altered, or circular evidence?"
        GraderNodeKind.UNCERTAINTY_HANDLING ->
            "How will uncertainty, assumptions, and unresolved risk be evaluated?"
        GraderNodeKind.ADVERSARIAL_ROBUSTNESS ->
            "Which bounded adversarial cases resist superficial compliance?"
        GraderNodeKind.REPRODUCIBILITY ->
            "Which exact inputs and procedures make the result reproducible?"
        GraderNodeKind.INTEGRATION_COHERENCE ->
            "How will node outputs be tested for coherent final integration?"
        GraderNodeKind.TOOL_RESULT_VALIDITY ->
            "How will executed tool and simulation results be authenticated?"
        GraderNodeKind.COST_BENEFIT_REALISM ->
            "How will the evaluator test cost, effort, and benefit realism?"
        GraderNodeKind.ACCESSIBILITY ->
            "How will stakeholder effects and accessibility obligations be tested?"
        GraderNodeKind.HIDDEN_TEST_DESIGN ->
            "Which sealed executable tests expose material failures?"
        GraderNodeKind.RUBRIC_CALIBRATION ->
            "How will judgments be calibrated against anchors and controlled artifacts?"
        GraderNodeKind.FALSE_POSITIVE_REDUCTION ->
            "How will valid work be protected from arbitrary or noisy rejection?"
        GraderNodeKind.NOVEL_FAILURE_DISCOVERY ->
            "Which receiver-relevant failure modes are absent from existing requirements?"
    }
}
