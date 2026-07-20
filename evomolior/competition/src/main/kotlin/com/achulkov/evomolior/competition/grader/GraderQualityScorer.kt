package com.achulkov.evomolior.competition.grader

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.grader.GraderFitnessPlane
import com.achulkov.evomolior.core.grader.GraderFitnessRecord
import com.achulkov.evomolior.core.grader.GraderQualityVector
import com.achulkov.evomolior.core.grader.GradingRequirement
import com.achulkov.evomolior.core.grader.HiddenTestDefinition
import com.achulkov.evomolior.core.grader.HiddenTestStatus

/**
 * Evidence used to score one grader civilisation without rewarding rubric bloat.
 *
 * @param runId Owning run.
 * @param round Evaluation round.
 * @param graderGroupId Evaluated grader group.
 * @param receiverObligationUniverse Receiver obligations available for coverage.
 * @param workerNodeUniverse Worker nodes available for coverage.
 * @param requirements Typed requirements authored by the grader group.
 * @param hiddenTests Hidden tests authored by the grader group.
 * @param peerNoveltyFingerprints Requirement fingerprints authored by other groups.
 * @param calibrationScore Agreement with anchors or external calibration.
 * @param discriminationScore Ability to distinguish controlled artifact quality.
 * @param predictiveValidity Later integration-success correlation.
 * @param actionabilityScore Measured next-round improvement after feedback.
 * @param detectedFailureCount Distinct controlled failures detected.
 * @param totalEvaluationCost Actual bounded evaluation cost.
 */
data class GraderQualityEvidence(
    val runId: String,
    val round: Int,
    val graderGroupId: String,
    val receiverObligationUniverse: Set<String>,
    val workerNodeUniverse: Set<NodeId>,
    val requirements: List<GradingRequirement>,
    val hiddenTests: List<HiddenTestDefinition>,
    val peerNoveltyFingerprints: Set<String> = emptySet(),
    val calibrationScore: Double,
    val discriminationScore: Double,
    val predictiveValidity: Double,
    val actionabilityScore: Double,
    val detectedFailureCount: Int,
    val totalEvaluationCost: Double
)

/** Deterministic multi-objective grader-quality scorer. */
class GraderQualityScorer {
    /**
     * Produces plane-separated fitness from typed requirement and judgment evidence.
     *
     * @param evidence Grader-quality evidence.
     * @param plane Competition plane.
     * @return Hash-bound fitness record.
     */
    fun score(
        evidence: GraderQualityEvidence,
        plane: GraderFitnessPlane
    ): GraderFitnessRecord {
        require(evidence.receiverObligationUniverse.isNotEmpty()) {
            "Receiver-obligation universe cannot be empty"
        }
        require(evidence.workerNodeUniverse.isNotEmpty()) {
            "Worker-node universe cannot be empty"
        }
        require(evidence.totalEvaluationCost.isFinite() && evidence.totalEvaluationCost >= 0.0) {
            "Evaluation cost must be finite and non-negative"
        }
        require(evidence.detectedFailureCount >= 0) {
            "Detected failure count cannot be negative"
        }
        val authored = evidence.requirements.filter(GradingRequirement::isFitnessEligible)
        val deterministic = evidence.requirements.filterNot(GradingRequirement::isFitnessEligible)
        val authoredHashes = authored.mapTo(linkedSetOf()) { it.requirementHash }
        val deterministicHashes = deterministic.mapTo(linkedSetOf()) { it.requirementHash }
        val coveredObligations = authored
            .flatMapTo(linkedSetOf()) { it.mappedReceiverObligationRefs }
            .intersect(evidence.receiverObligationUniverse)
        val coveredNodes = authored
            .flatMapTo(linkedSetOf()) { it.mappedWorkerNodeIds }
            .intersect(evidence.workerNodeUniverse)
        val breadth = mean(
            coveredObligations.size.toDouble() / evidence.receiverObligationUniverse.size,
            coveredNodes.size.toDouble() / evidence.workerNodeUniverse.size
        )
        val depth = authored.map { requirement ->
            mean(
                normalizedCount(requirement.failureLevels.size, target = 4),
                normalizedCount(requirement.adversarialCases.size, target = 3),
                if (requirement.passThreshold.isBlank()) 0.0 else 1.0
            )
        }.averageOrZero()
        val robustness = authored.map { requirement ->
            val requirementTests = evidence.hiddenTests.filter {
                it.testId in requirement.executableTestIds &&
                    it.status == HiddenTestStatus.SEALED
            }
            val testDetection = requirementTests.mapNotNull { it.detectionRate }.averageOrZero()
            val falsePositiveResistance = requirementTests.mapNotNull { it.falsePositiveRate }
                .map { 1.0 - it }
                .averageOrZero()
            mean(
                normalizedCount(requirement.adversarialCases.size, target = 3),
                if (requirement.failureLevels.any { it.critical }) 1.0 else 0.0,
                testDetection,
                falsePositiveResistance
            )
        }.averageOrZero()
        val creativity = authored
            .map(GradingRequirement::noveltyFingerprint)
            .distinct()
            .count { it !in evidence.peerNoveltyFingerprints }
            .toDouble()
            .div(authored.size.coerceAtLeast(1))
            .coerceIn(0.0, 1.0)
        val sealedTestIds = evidence.hiddenTests
            .filter { it.status == HiddenTestStatus.SEALED }
            .mapTo(linkedSetOf()) { it.testId }
        val executability = authored.count { requirement ->
            requirement.executableTestIds.any { it in sealedTestIds }
        }.toDouble().div(authored.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val faultYield = evidence.detectedFailureCount.toDouble() /
            authored.size.coerceAtLeast(1)
        val costEfficiency = if (evidence.totalEvaluationCost == 0.0) {
            if (evidence.detectedFailureCount == 0) 0.0 else 1.0
        } else {
            (evidence.detectedFailureCount / evidence.totalEvaluationCost).coerceAtMost(1.0)
        }
        val parsimony = mean(faultYield.coerceAtMost(1.0), costEfficiency)
        val quality = GraderQualityVector(
            breadth = breadth,
            depth = depth,
            robustness = robustness,
            creativity = creativity,
            executability = executability,
            calibration = normalized(evidence.calibrationScore),
            discrimination = normalized(evidence.discriminationScore),
            predictiveValidity = normalized(evidence.predictiveValidity),
            parsimony = parsimony,
            actionability = normalized(evidence.actionabilityScore)
        )
        val aggregate = with(quality) {
            mean(
                breadth,
                depth,
                robustness,
                creativity,
                executability,
                calibration,
                discrimination,
                predictiveValidity,
                parsimony,
                actionability
            )
        }
        val policyHash = AtlasHashing.hashText(
            "grader-quality-v1:breadth,depth,robustness,creativity,executability," +
                "calibration,discrimination,predictiveValidity,parsimony,actionability"
        )
        return GraderFitnessRecord(
            recordId = "grader-fitness:${evidence.runId}:${evidence.round}:" +
                "${evidence.graderGroupId}:${plane.name}",
            runId = evidence.runId,
            round = evidence.round,
            graderGroupId = evidence.graderGroupId,
            plane = plane,
            quality = quality,
            eligibleRequirementHashes = authoredHashes,
            deterministicSeedHashes = deterministicHashes,
            aggregateScore = aggregate,
            policyHash = policyHash
        ).withComputedHash()
    }

    private fun normalized(value: Double): Double {
        require(value.isFinite() && value in 0.0..1.0) {
            "Observed grader-quality values must be normalized"
        }
        return value
    }

    private fun normalizedCount(value: Int, target: Int): Double =
        (value.toDouble() / target).coerceIn(0.0, 1.0)

    private fun mean(vararg values: Double): Double =
        values.average().coerceIn(0.0, 1.0)

    private fun List<Double>.averageOrZero(): Double =
        if (isEmpty()) 0.0 else average().coerceIn(0.0, 1.0)
}
