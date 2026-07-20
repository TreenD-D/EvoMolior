package com.achulkov.evomolior.core.grader

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeId

/** Deterministic validation for competitive grader authority contracts. */
object GraderAtlasValidation {
    /**
     * Validates a sealed Grader Atlas and its directed acyclic prerequisite graph.
     *
     * @param atlas Atlas to validate.
     * @return Validation errors; empty means valid.
     */
    fun validate(atlas: GraderAtlas): List<String> = buildList {
        if (!atlas.hasValidHash()) add("Grader Atlas or node hash is invalid")
        if (atlas.version <= 0) add("Grader Atlas version must be positive")
        if (atlas.nodes.isEmpty()) add("Grader Atlas must contain nodes")
        if (!atlas.nodes.keys.containsAll(atlas.rootNodeIds)) {
            add("Grader Atlas root references an unknown node")
        }
        atlas.nodes.values.forEach { node ->
            if (node.title.isBlank() || node.evaluationQuestion.isBlank()) {
                add("Grader node ${node.nodeId.value} requires title and evaluation question")
            }
            if (node.receiverObligationRefs.isEmpty() || node.mappedWorkerNodeIds.isEmpty()) {
                add("Grader node ${node.nodeId.value} requires receiver and worker mappings")
            }
            if (node.requiredRequirementCount <= 0) {
                add("Grader node ${node.nodeId.value} requirement count must be positive")
            }
            if (!atlas.nodes.keys.containsAll(node.prerequisiteNodeIds)) {
                add("Grader node ${node.nodeId.value} references an unknown prerequisite")
            }
        }
        if (containsCycle(atlas)) add("Grader Atlas prerequisite graph contains a cycle")
    }

    /**
     * Validates one append-only Grader Atlas evolution against the exact worker Atlas transition.
     *
     * @param currentAtlas Current sealed Grader Atlas.
     * @param nextAtlas Proposed child Grader Atlas.
     * @param currentWorkerAtlas Current sealed worker Atlas.
     * @param nextWorkerAtlas Proposed child worker Atlas.
     * @return Validation errors; empty means the two authority planes evolve as one matched pair.
     */
    fun validateEvolution(
        currentAtlas: GraderAtlas,
        nextAtlas: GraderAtlas,
        currentWorkerAtlas: NodeAtlas,
        nextWorkerAtlas: NodeAtlas
    ): List<String> = buildList {
        addAll(validate(currentAtlas).map { "Current $it" })
        addAll(validate(nextAtlas).map { "Next $it" })
        if (currentAtlas.runId != currentWorkerAtlas.runId ||
            currentAtlas.workerAtlasHash != currentWorkerAtlas.atlasHash ||
            currentAtlas.version != currentWorkerAtlas.version
        ) {
            add("Current Grader Atlas does not match the current worker Atlas")
        }
        if (nextAtlas.runId != nextWorkerAtlas.runId ||
            nextAtlas.workerAtlasHash != nextWorkerAtlas.atlasHash ||
            nextAtlas.version != nextWorkerAtlas.version
        ) {
            add("Next Grader Atlas does not match the next worker Atlas")
        }
        if (nextWorkerAtlas.runId != currentWorkerAtlas.runId ||
            nextWorkerAtlas.atlasId != currentWorkerAtlas.atlasId ||
            nextWorkerAtlas.version != currentWorkerAtlas.version + 1 ||
            nextWorkerAtlas.parentAtlasHash != currentWorkerAtlas.atlasHash
        ) {
            add("Worker Atlas evolution is not an exact child version")
        }
        if (nextAtlas.graderAtlasId != currentAtlas.graderAtlasId ||
            nextAtlas.runId != currentAtlas.runId ||
            nextAtlas.version != currentAtlas.version + 1
        ) {
            add("Grader Atlas evolution must preserve identity and append exactly one version")
        }
        if (nextAtlas.rootNodeIds != currentAtlas.rootNodeIds ||
            nextAtlas.nodes.keys != currentAtlas.nodes.keys
        ) {
            add("Grader Atlas evolution cannot replace its sealed frontier")
        }
        currentAtlas.nodes.forEach { (nodeId, currentNode) ->
            val nextNode = nextAtlas.nodes[nodeId]
            if (nextNode == null) {
                add("Grader Atlas evolution removed node ${nodeId.value}")
                return@forEach
            }
            if (currentNode.nodeId != nextNode.nodeId ||
                currentNode.kind != nextNode.kind ||
                currentNode.title != nextNode.title ||
                currentNode.evaluationQuestion != nextNode.evaluationQuestion ||
                currentNode.receiverObligationRefs != nextNode.receiverObligationRefs ||
                currentNode.prerequisiteNodeIds != nextNode.prerequisiteNodeIds ||
                currentNode.requiredRequirementCount != nextNode.requiredRequirementCount
            ) {
                add("Grader Atlas evolution mutated node semantics for ${nodeId.value}")
            }
            if (currentNode.mappedWorkerNodeIds != currentWorkerAtlas.nodes.keys) {
                add("Current grader node ${nodeId.value} lacks complete worker coverage")
            }
            if (nextNode.mappedWorkerNodeIds != nextWorkerAtlas.nodes.keys) {
                add("Next grader node ${nodeId.value} lacks complete worker coverage")
            }
        }
    }

    /**
     * Validates one typed requirement against its Atlas and test registry.
     *
     * @param requirement Requirement to validate.
     * @param atlas Governing Grader Atlas.
     * @param hiddenTests Available hidden tests by ID.
     * @return Validation errors.
     */
    fun validate(
        requirement: GradingRequirement,
        atlas: GraderAtlas,
        hiddenTests: Map<HiddenTestId, HiddenTestDefinition>
    ): List<String> = buildList {
        if (!requirement.hasValidHash()) add("Requirement hash is invalid")
        if (requirement.runId != atlas.runId ||
            requirement.graderAtlasHash != atlas.atlasHash
        ) {
            add("Requirement does not match its Grader Atlas authority")
        }
        val node = atlas.nodes[requirement.graderAtlasNodeId]
        if (node == null) {
            add("Requirement references an unknown Grader Atlas node")
        } else {
            if (!node.mappedWorkerNodeIds.containsAll(requirement.mappedWorkerNodeIds)) {
                add("Requirement worker mappings exceed its Grader Atlas node")
            }
            if (!node.receiverObligationRefs.containsAll(
                    requirement.mappedReceiverObligationRefs
                )
            ) {
                add("Requirement receiver mappings exceed its Grader Atlas node")
            }
        }
        if (requirement.round < 0) add("Requirement round cannot be negative")
        if (requirement.mappedWorkerNodeIds.size != 1 ||
            requirement.mappedReceiverObligationRefs.isEmpty()
        ) {
            add(
                "Capture-governing requirement must map exactly one worker node and at least one " +
                    "receiver obligation"
            )
        }
        if (requirement.criterion.isBlank() ||
            requirement.observableEvidence.isBlank() ||
            requirement.passThreshold.isBlank() ||
            requirement.rationale.isBlank() ||
            requirement.noveltyFingerprint.isBlank()
        ) {
            add("Requirement text commitments cannot be blank")
        }
        if (!requirement.maximumEvaluationCost.isFinite() ||
            requirement.maximumEvaluationCost < 0.0
        ) {
            add("Requirement cost must be finite and non-negative")
        }
        if (requirement.failureLevels.isEmpty()) add("Requirement needs failure levels")
        val levels = requirement.failureLevels.map(FailureLevel::level)
        if (levels != levels.sorted() || levels.toSet().size != levels.size) {
            add("Requirement failure levels must be unique and ordered")
        }
        requirement.failureLevels.forEach { level ->
            if (level.description.isBlank() || level.scoreCeiling !in 0.0..1.0) {
                add("Requirement failure level is malformed")
            }
        }
        requirement.adversarialCases.forEach { case ->
            if (case.caseId.isBlank() || case.setup.isBlank() ||
                case.expectedDetection.isBlank() ||
                !case.boundedCost.isFinite() || case.boundedCost < 0.0
            ) {
                add("Requirement adversarial case is malformed")
            }
        }
        if (!hiddenTests.keys.containsAll(requirement.executableTestIds)) {
            add("Requirement references an unknown hidden test")
        }
    }

    /**
     * Validates a criteria freeze against exact requirements, tests, and worker snapshots.
     *
     * @param freeze Freeze to validate.
     * @param requirements Frozen requirements.
     * @param hiddenTests Frozen hidden tests.
     * @param expectedTargetSnapshotHashes Expected worker snapshot commitments.
     * @return Validation errors.
     */
    fun validate(
        freeze: GraderCriteriaFreeze,
        requirements: Collection<GradingRequirement>,
        hiddenTests: Collection<HiddenTestDefinition>,
        expectedTargetSnapshotHashes: Map<AtlasGroupId, String>
    ): List<String> = buildList {
        if (!freeze.hasValidHash()) add("Criteria freeze hash or ordering is invalid")
        val requirementHashes = requirements.mapTo(linkedSetOf()) { it.requirementHash }
        if (freeze.requirementHashes != requirementHashes) {
            add("Criteria freeze requirement cardinality or hashes do not match")
        }
        val testHashes = hiddenTests.mapTo(linkedSetOf()) { it.testHash }
        if (freeze.hiddenTestHashes != testHashes) {
            add("Criteria freeze hidden-test cardinality or hashes do not match")
        }
        if (freeze.targetSnapshotHashes != expectedTargetSnapshotHashes ||
            freeze.targetSnapshotHashes.values.any(String::isBlank)
        ) {
            add("Criteria freeze worker snapshot commitments do not match")
        }
        if (requirements.any {
                it.graderGroupId != freeze.graderGroupId || it.round != freeze.round
            }
        ) {
            add("Criteria freeze contains requirements from another group or round")
        }
        if (hiddenTests.any {
                it.graderGroupId != freeze.graderGroupId ||
                    it.runId != freeze.runId ||
                    it.graderAtlasHash != freeze.graderAtlasHash ||
                    it.status != HiddenTestStatus.SEALED
            }
        ) {
            add("Criteria freeze contains unsealed or foreign hidden tests")
        }
    }

    /**
     * Validates exact score-bundle cardinality and commitments.
     *
     * @param bundle Bundle to validate.
     * @param freeze Governing criteria freeze.
     * @param requiredNodeIds Exact required worker node IDs.
     * @param requiredDimensionIds Exact required broad dimension IDs.
     * @param requiredRequirementIds Exact frozen requirement IDs.
     * @return Validation errors.
     */
    fun validate(
        bundle: GraderScoreBundle,
        freeze: GraderCriteriaFreeze,
        requiredNodeIds: Set<NodeId>,
        requiredDimensionIds: Set<String>,
        requiredRequirementIds: Set<GradingRequirementId>
    ): List<String> = buildList {
        if (!bundle.hasValidHash()) add("Grader score bundle hash is invalid")
        if (bundle.runId != freeze.runId ||
            bundle.round != freeze.round ||
            bundle.graderGroupId != freeze.graderGroupId ||
            bundle.criteriaFreezeHash != freeze.freezeHash
        ) {
            add("Grader score bundle identity does not match its criteria freeze")
        }
        val expectedSnapshot = freeze.targetSnapshotHashes[bundle.targetGroupId]
        if (expectedSnapshot == null || bundle.targetSnapshotHash != expectedSnapshot) {
            add("Grader score bundle target snapshot hash does not match")
        }
        if (bundle.status == GraderScoreBundleStatus.VALID) {
            if (bundle.nodeScores.keys != requiredNodeIds) {
                add("Grader score bundle node cardinality does not match")
            }
            if (bundle.groupScores.keys != requiredDimensionIds) {
                add("Grader score bundle dimension cardinality does not match")
            }
            if (bundle.requirementResults.keys != requiredRequirementIds) {
                add("Grader score bundle requirement cardinality does not match")
            }
            bundle.nodeScores.values.forEach { vector ->
                val values = listOf(
                    vector.correctness,
                    vector.evidenceQuality,
                    vector.reproducibility,
                    vector.robustness,
                    vector.integrationUtility,
                    vector.receiverRelevance,
                    vector.novelty,
                    vector.efficiency,
                    vector.confidence
                )
                if (values.any { !it.isFinite() || it !in 0.0..1.0 }) {
                    add("Grader score bundle contains an out-of-range node score")
                }
            }
            bundle.groupScores.values.forEach { score ->
                val valid = when (score.status) {
                    BoundedScoreStatus.SCORED ->
                        score.value != null && score.value.isFinite() &&
                            score.minimum.isFinite() && score.maximum.isFinite() &&
                            score.minimum <= score.maximum &&
                            score.value in score.minimum..score.maximum
                    BoundedScoreStatus.NOT_APPLICABLE -> score.value == null
                }
                if (!valid) add("Grader score bundle contains a malformed bounded score")
            }
            if (bundle.normalizationTrace.isEmpty()) {
                add("Valid grader score bundle requires a normalization replay trace")
            }
        }
    }

    private fun containsCycle(atlas: GraderAtlas): Boolean {
        val visiting = mutableSetOf<GraderNodeId>()
        val visited = mutableSetOf<GraderNodeId>()

        fun visit(nodeId: GraderNodeId): Boolean {
            if (nodeId in visiting) return true
            if (!visited.add(nodeId)) return false
            visiting += nodeId
            val cyclic = atlas.nodes.getValue(nodeId).prerequisiteNodeIds.any(::visit)
            visiting -= nodeId
            return cyclic
        }

        return atlas.nodes.keys.any(::visit)
    }
}

/**
 * Deterministically derives the declared evaluation-independence label from execution metadata.
 */
object EvaluationIndependenceProfiler {
    /**
     * Produces one hash-bound profile.
     *
     * @param workerFamilies Worker model families.
     * @param graderFamilies Grader model families.
     * @param providers All actual worker/grader providers.
     * @param sharedPromptTemplateFraction Shared prompt-template fraction.
     * @param externallyCalibrated Whether an external calibration subset was used.
     * @return Derived profile.
     */
    fun profile(
        workerFamilies: Set<String>,
        graderFamilies: Set<String>,
        providers: Set<String>,
        sharedPromptTemplateFraction: Double,
        externallyCalibrated: Boolean = false
    ): EvaluationIndependenceProfile {
        require(workerFamilies.isNotEmpty() && graderFamilies.isNotEmpty()) {
            "Worker and grader model-family sets cannot be empty"
        }
        require(sharedPromptTemplateFraction in 0.0..1.0) {
            "Shared prompt-template fraction must be normalized"
        }
        val allFamilies = workerFamilies + graderFamilies
        val overlap = workerFamilies.intersect(graderFamilies).size.toDouble() /
            workerFamilies.union(graderFamilies).size
        val label = when {
            externallyCalibrated -> IndependenceLabel.CALIBRATED_EXTERNAL
            allFamilies.size == 1 -> IndependenceLabel.LOW_SINGLE_FAMILY
            providers.size >= 2 && overlap < 0.5 -> IndependenceLabel.HIGH_CROSS_FAMILY
            else -> IndependenceLabel.MODERATE_PROMPT_DIVERSE
        }
        return EvaluationIndependenceProfile(
            distinctModelFamilies = allFamilies.size,
            distinctProviders = providers.size,
            sharedPromptTemplateFraction = sharedPromptTemplateFraction,
            workerGraderFamilyOverlap = overlap,
            independenceLabel = label
        ).withComputedHash()
    }
}
