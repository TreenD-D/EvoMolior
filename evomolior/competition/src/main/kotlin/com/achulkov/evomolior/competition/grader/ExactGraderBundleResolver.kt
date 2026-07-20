package com.achulkov.evomolior.competition.grader

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.grader.BoundedScoreStatus
import com.achulkov.evomolior.core.grader.GraderScoreBundle
import com.achulkov.evomolior.core.grader.GraderScoreBundleStatus
import com.achulkov.evomolior.core.grader.GradingRequirementId
import com.achulkov.evomolior.core.grader.RequirementResultStatus
import kotlinx.serialization.Serializable

/** Fail-closed status of one exact grader round. */
@Serializable
enum class ExactGraderRoundStatus {
    VALID,
    INVALID,
    ABSTAINED
}

/**
 * Deterministic exact-bundle round verdict.
 *
 * @param status Round authority status.
 * @param acceptedBundleHashes Exact accepted bundle commitments.
 * @param targetSnapshotHashes Verified worker snapshots.
 * @param nodeScores Quorum-aggregated node vectors by target.
 * @param groupScores Quorum-aggregated broad dimensions by target; null is not applicable.
 * @param contestedCriticalRequirements Critical failures raised by only part of the quorum.
 * @param maximumNodeDisagreement Maximum component spread across graders.
 * @param reason Fail-closed reason.
 */
@Serializable
data class ExactGraderRoundVerdict(
    val status: ExactGraderRoundStatus,
    val acceptedBundleHashes: Set<String>,
    val targetSnapshotHashes: Map<AtlasGroupId, String>,
    val nodeScores: Map<AtlasGroupId, Map<NodeId, NodeScoreVector>>,
    val groupScores: Map<AtlasGroupId, Map<String, Double?>>,
    val contestedCriticalRequirements:
        Map<AtlasGroupId, Set<GradingRequirementId>> = emptyMap(),
    val maximumNodeDisagreement: Double?,
    val reason: String? = null
)

/**
 * Validates exact `(round, graderGroup, targetGroup)` cardinality and aggregates only full quorum.
 */
class ExactGraderBundleResolver {
    /**
     * Resolves one exact grader round.
     *
     * @param runId Owning run.
     * @param round Round to resolve.
     * @param expectedGraderGroupIds Exact independent grader groups.
     * @param expectedTargetGroupIds Exact worker target groups.
     * @param bundles Candidate bundles.
     * @return Valid aggregate or fail-closed verdict.
     */
    fun resolve(
        runId: String,
        round: Int,
        expectedGraderGroupIds: Set<String>,
        expectedTargetGroupIds: Set<AtlasGroupId>,
        bundles: List<GraderScoreBundle>
    ): ExactGraderRoundVerdict {
        require(expectedGraderGroupIds.isNotEmpty()) { "Expected grader groups cannot be empty" }
        require(expectedTargetGroupIds.isNotEmpty()) { "Expected target groups cannot be empty" }
        val expectedKeys = expectedGraderGroupIds.flatMapTo(linkedSetOf()) { graderGroupId ->
            expectedTargetGroupIds.map { targetGroupId -> graderGroupId to targetGroupId }
        }
        val matching = bundles.filter { it.runId == runId && it.round == round }
        val grouped = matching.groupBy { it.graderGroupId to it.targetGroupId }
        val duplicateKeys = grouped.filterValues { it.size != 1 }.keys
        val unknownKeys = grouped.keys - expectedKeys
        val missingKeys = expectedKeys - grouped.keys
        if (duplicateKeys.isNotEmpty() || unknownKeys.isNotEmpty() || missingKeys.isNotEmpty()) {
            return invalid(
                "Exact grader bundle cardinality failed: missing=${missingKeys.size}, " +
                    "duplicates=${duplicateKeys.size}, unknown=${unknownKeys.size}"
            )
        }
        val exact = expectedKeys.map { grouped.getValue(it).single() }
        val invalidHashes = exact.filterNot(GraderScoreBundle::hasValidHash)
        if (invalidHashes.isNotEmpty()) {
            return invalid(
                "Exact grader bundle hashes are invalid: " +
                    invalidHashes.joinToString("; ") { it.diagnosticKey() }
            )
        }
        val invalidBundles = exact.filter { it.status == GraderScoreBundleStatus.INVALID }
        if (invalidBundles.isNotEmpty()) {
            return invalid(
                "Exact grader bundles are invalid: " +
                    invalidBundles.joinToString("; ") { bundle ->
                        "${bundle.diagnosticKey()} predicates=${bundle.invalidPredicates().joinToString(",")}"
                    }
            )
        }
        if (exact.any { it.status == GraderScoreBundleStatus.ABSTAINED }) {
            return ExactGraderRoundVerdict(
                status = ExactGraderRoundStatus.ABSTAINED,
                acceptedBundleHashes = emptySet(),
                targetSnapshotHashes = emptyMap(),
                nodeScores = emptyMap(),
                groupScores = emptyMap(),
                maximumNodeDisagreement = null,
                reason = "One or more independent grader groups abstained"
            )
        }
        val snapshotHashes = expectedTargetGroupIds.associateWith { targetGroupId ->
            val hashes = exact.filter { it.targetGroupId == targetGroupId }
                .mapTo(linkedSetOf()) { it.targetSnapshotHash }
            if (hashes.size != 1) return invalid(
                "Independent graders evaluated different target snapshots"
            )
            hashes.single()
        }
        val nodeCardinality = expectedTargetGroupIds.associateWith { targetGroupId ->
            exact.filter { it.targetGroupId == targetGroupId }
                .map { it.nodeScores.keys }
                .distinct()
        }
        if (nodeCardinality.values.any { it.size != 1 }) {
            return invalid("Independent grader node cardinality differs")
        }
        val dimensionCardinality = expectedTargetGroupIds.associateWith { targetGroupId ->
            exact.filter { it.targetGroupId == targetGroupId }
                .map { it.groupScores.keys }
                .distinct()
        }
        if (dimensionCardinality.values.any { it.size != 1 }) {
            return invalid("Independent grader dimension cardinality differs")
        }
        val contestedCriticalRequirements =
            expectedTargetGroupIds.associateWith { targetGroupId ->
                val targetBundles = exact.filter { it.targetGroupId == targetGroupId }
                targetBundles.flatMapTo(linkedSetOf()) { bundle ->
                    bundle.requirementResults.filterValues {
                        it.criticalFailure &&
                            it.status == RequirementResultStatus.FAIL
                    }
                        .keys
                }
            }.filterValues { it.isNotEmpty() }
        val aggregatedNodes = expectedTargetGroupIds.associateWith { targetGroupId ->
            val targetBundles = exact.filter { it.targetGroupId == targetGroupId }
            targetBundles.first().nodeScores.keys.associateWith { nodeId ->
                averageVector(targetBundles.map { it.nodeScores.getValue(nodeId) })
            }
        }
        val aggregatedGroups = expectedTargetGroupIds.associateWith { targetGroupId ->
            val targetBundles = exact.filter { it.targetGroupId == targetGroupId }
            targetBundles.first().groupScores.keys.associateWith { dimensionId ->
                val scores = targetBundles.map { it.groupScores.getValue(dimensionId) }
                if (scores.any { it.status == BoundedScoreStatus.NOT_APPLICABLE }) {
                    if (scores.any { it.status != BoundedScoreStatus.NOT_APPLICABLE }) {
                        return invalid("Independent graders disagree on dimension applicability")
                    }
                    null
                } else {
                    scores.map { requireNotNull(it.value) }.average()
                }
            }
        }
        val disagreement = expectedTargetGroupIds.flatMap { targetGroupId ->
            val targetBundles = exact.filter { it.targetGroupId == targetGroupId }
            targetBundles.first().nodeScores.keys.map { nodeId ->
                vectorDisagreement(targetBundles.map { it.nodeScores.getValue(nodeId) })
            }
        }.maxOrNull()
        return ExactGraderRoundVerdict(
            status = ExactGraderRoundStatus.VALID,
            acceptedBundleHashes = exact.mapTo(linkedSetOf()) { it.payloadHash },
            targetSnapshotHashes = snapshotHashes,
            nodeScores = aggregatedNodes,
            groupScores = aggregatedGroups,
            contestedCriticalRequirements = contestedCriticalRequirements,
            maximumNodeDisagreement = disagreement
        )
    }

    private fun invalid(reason: String) = ExactGraderRoundVerdict(
        status = ExactGraderRoundStatus.INVALID,
        acceptedBundleHashes = emptySet(),
        targetSnapshotHashes = emptyMap(),
        nodeScores = emptyMap(),
        groupScores = emptyMap(),
        maximumNodeDisagreement = null,
        reason = reason
    )

    private fun GraderScoreBundle.diagnosticKey(): String =
        "grader=$graderGroupId,target=${targetGroupId.value},bundle=$bundleId"

    private fun GraderScoreBundle.invalidPredicates(): List<String> = buildList {
        if (validationFailures.isNotEmpty()) {
            addAll(validationFailures.map { it.name.lowercase() }.sorted())
            return@buildList
        }
        if (nodeScores.isEmpty()) add("node_assignments_or_scores_missing")
        if (groupScores.isEmpty()) add("group_dimensions_missing")
        if (requirementResults.isEmpty()) add("requirement_results_missing")
        if (requirementResults.isNotEmpty() &&
            requirementResults.values.all { it.status == RequirementResultStatus.NOT_APPLICABLE }
        ) {
            add("all_requirements_not_applicable")
        }
        if (normalizationTrace.isEmpty()) add("normalization_trace_missing")
        if (rationaleHashes.isEmpty()) add("rationale_hashes_missing")
        if (isEmpty()) add("unspecified_invalid_bundle_state")
    }

    private fun averageVector(vectors: List<NodeScoreVector>) = NodeScoreVector(
        correctness = vectors.map(NodeScoreVector::correctness).average(),
        evidenceQuality = vectors.map(NodeScoreVector::evidenceQuality).average(),
        reproducibility = vectors.map(NodeScoreVector::reproducibility).average(),
        robustness = vectors.map(NodeScoreVector::robustness).average(),
        integrationUtility = vectors.map(NodeScoreVector::integrationUtility).average(),
        receiverRelevance = vectors.map(NodeScoreVector::receiverRelevance).average(),
        novelty = vectors.map(NodeScoreVector::novelty).average(),
        efficiency = vectors.map(NodeScoreVector::efficiency).average(),
        hardFailure = vectors.all(NodeScoreVector::hardFailure),
        confidence = vectors.map(NodeScoreVector::confidence).average()
    )

    private fun vectorDisagreement(vectors: List<NodeScoreVector>): Double {
        val dimensions = listOf<(NodeScoreVector) -> Double>(
            NodeScoreVector::correctness,
            NodeScoreVector::evidenceQuality,
            NodeScoreVector::reproducibility,
            NodeScoreVector::robustness,
            NodeScoreVector::integrationUtility,
            NodeScoreVector::receiverRelevance,
            NodeScoreVector::novelty,
            NodeScoreVector::efficiency
        )
        return dimensions.maxOf { dimension ->
            val values = vectors.map(dimension)
            values.max() - values.min()
        }
    }
}
