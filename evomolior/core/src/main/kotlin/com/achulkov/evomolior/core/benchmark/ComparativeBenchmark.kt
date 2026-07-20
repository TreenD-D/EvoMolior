@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.benchmark

import com.achulkov.evomolior.core.atlas.AtlasHashing
import kotlinx.serialization.Serializable
import kotlin.math.sqrt
import kotlin.time.Instant

@Serializable
enum class ComparativeBenchmarkArm(val id: String) {
    ITERATIVE_SINGLE_AGENT("iterative-single-agent"),
    ONE_GROUP_SOCIETY("one-group-society"),
    FULL_SOCIETY("full-society")
}

@Serializable
enum class ComparativeConstraintMode {
    ISO_COST,
    ISO_LATENCY
}

/** Terminal state retained for every task, seed, and arm cell. */
@Serializable
enum class ComparativeCellStatus {
    SUCCEEDED,
    FAILED,
    ABSTAINED
}

/** Whether a paired cell can contribute a numeric quality difference. */
@Serializable
enum class PairedCellStatus {
    COMPLETE,
    BASELINE_NON_SUCCESS,
    COMPARATOR_NON_SUCCESS,
    BOTH_NON_SUCCESS
}

/**
 * One immutable benchmark task commitment.
 *
 * @param taskId Stable task identity.
 * @param taskContractHash Hash of the exact prompt, gold/evaluator boundary, and input task.
 */
@Serializable
data class ComparativeTaskCommitment(
    val taskId: String,
    val taskContractHash: String
) {
    init {
        require(taskId.isNotBlank() && taskContractHash.isNotBlank()) {
            "Comparative task identity and contract hash must not be blank"
        }
    }
}

/**
 * Shared inputs that must be identical across all matched arms.
 *
 * @param sourceBundleHash Exact source bundle commitment.
 * @param toolBundleHash Exact tool bundle commitment.
 * @param atlasHash Exact sealed Atlas commitment.
 * @param outputContractHash Exact output contract commitment.
 * @param workerModelFamily Worker model family held constant across arms.
 */
@Serializable
data class ComparativeInputCommitments(
    val sourceBundleHash: String,
    val toolBundleHash: String,
    val atlasHash: String,
    val outputContractHash: String,
    val workerModelFamily: String
) {
    init {
        require(
            listOf(
                sourceBundleHash,
                toolBundleHash,
                atlasHash,
                outputContractHash,
                workerModelFamily
            ).all(String::isNotBlank)
        ) { "Every comparative input commitment must be present" }
    }

    /** Returns the deterministic aggregate commitment hash. */
    fun commitmentHash(): String = ComparativeBenchmarkHashing.hashCommitments(this)
}

/**
 * Matched resource policy.
 *
 * ISO_COST requires known monetary cost for every cell. ISO_LATENCY requires every cell to remain
 * within the same wall-clock cap.
 */
@Serializable
data class ComparativeConstraintPolicy(
    val mode: ComparativeConstraintMode,
    val maximumCostUsd: Double? = null,
    val maximumLatencyMillis: Long? = null
) {
    init {
        when (mode) {
            ComparativeConstraintMode.ISO_COST -> require(
                maximumCostUsd != null && maximumCostUsd.isFinite() && maximumCostUsd > 0.0
            ) { "ISO_COST requires a positive known monetary cap" }
            ComparativeConstraintMode.ISO_LATENCY -> require(
                maximumLatencyMillis != null && maximumLatencyMillis > 0L
            ) { "ISO_LATENCY requires a positive latency cap" }
        }
    }
}

/**
 * Independent evaluator commitment shared by all arms.
 *
 * @param evaluatorId Stable evaluator identity.
 * @param evaluatorVersion Version of the evaluator contract.
 * @param evaluatorContractHash Hash of the exact scoring contract.
 * @param modelFamily Evaluator model family, which must differ from the worker family.
 * @param independent Whether evaluator execution is independent from every arm.
 * @param calibrated Whether calibration evidence was frozen before execution.
 */
@Serializable
data class ComparativeEvaluatorContract(
    val evaluatorId: String,
    val evaluatorVersion: Int,
    val evaluatorContractHash: String,
    val modelFamily: String,
    val independent: Boolean,
    val calibrated: Boolean
) {
    init {
        require(
            evaluatorId.isNotBlank() &&
                evaluatorVersion >= 1 &&
                evaluatorContractHash.isNotBlank() &&
                modelFamily.isNotBlank()
        ) { "Evaluator identity, version, contract hash, and model family are required" }
    }
}

/**
 * Runtime shape for one explicit experiment arm.
 *
 * The single-agent arm has no worker/grader groups, one-group society has exactly one worker group,
 * and full society retains at least two worker and two grader groups.
 */
@Serializable
data class ComparativeArmDefinition(
    val arm: ComparativeBenchmarkArm,
    val workerGroupCount: Int,
    val graderGroupCount: Int,
    val workerModelFamily: String
) {
    init {
        require(workerGroupCount >= 0 && graderGroupCount >= 0) {
            "Comparative arm group counts must not be negative"
        }
        require(workerModelFamily.isNotBlank()) { "Comparative arm model family is required" }
        when (arm) {
            ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT -> require(
                workerGroupCount == 0 && graderGroupCount == 0
            ) { "Iterative single-agent arm cannot contain society groups" }
            ComparativeBenchmarkArm.ONE_GROUP_SOCIETY -> require(
                workerGroupCount == 1 && graderGroupCount >= 1
            ) { "One-group society requires one worker group and at least one grader group" }
            ComparativeBenchmarkArm.FULL_SOCIETY -> require(
                workerGroupCount >= 2 && graderGroupCount >= 2
            ) { "Full society requires at least two worker and two grader groups" }
        }
    }
}

/**
 * Immutable matched comparative experiment plan.
 *
 * @param artifactRoot Root below which each task/seed/arm receives a disjoint namespace.
 */
@Serializable
data class ComparativeExperimentPlan(
    val experimentId: String,
    val experimentVersion: Int,
    val benchmarkId: String,
    val benchmarkVersion: Int,
    val tasks: List<ComparativeTaskCommitment>,
    val seeds: List<Long>,
    val arms: List<ComparativeArmDefinition>,
    val commitments: ComparativeInputCommitments,
    val constraintPolicy: ComparativeConstraintPolicy,
    val evaluator: ComparativeEvaluatorContract,
    val artifactRoot: String,
    val createdAt: Instant,
    val planHash: String = ""
) {
    init {
        require(
            experimentId.isNotBlank() &&
                experimentVersion >= 1 &&
                benchmarkId.isNotBlank() &&
                benchmarkVersion >= 1
        ) { "Comparative experiment and benchmark identity are required" }
        require(tasks.isNotEmpty() && tasks.map { it.taskId }.distinct().size == tasks.size) {
            "Comparative experiments require unique tasks"
        }
        require(seeds.size >= 2 && seeds.distinct().size == seeds.size) {
            "Comparative experiments require at least two distinct seeds"
        }
        require(artifactRoot.isNotBlank() && !artifactRoot.startsWith("/")) {
            "Comparative artifact root must be a relative path"
        }
        val definitions = arms.associateBy { it.arm }
        require(definitions.size == arms.size && definitions.keys.containsAll(ComparativeBenchmarkArm.entries)) {
            "Comparative experiments require exactly one definition for every required arm"
        }
        require(arms.all { it.workerModelFamily == commitments.workerModelFamily }) {
            "Every arm must use the committed worker model family"
        }
        require(evaluator.independent && evaluator.calibrated) {
            "Comparative evaluator must be independent and calibrated"
        }
        require(evaluator.modelFamily != commitments.workerModelFamily) {
            "Comparative evaluator model family must be independent from the worker family"
        }
    }

    /** Returns this plan with its deterministic hash attached. */
    fun withComputedHash(): ComparativeExperimentPlan =
        copy(planHash = ComparativeBenchmarkHashing.hashPlan(this))

    /** Verifies the immutable plan commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(planHash, ComparativeBenchmarkHashing.hashPlan(this))

    /** Expands the complete task/seed/arm assignment matrix. */
    fun assignments(): List<ComparativeExperimentAssignment> {
        require(hasValidHash()) { "Comparative experiment plan hash is invalid" }
        return tasks.flatMap { task ->
            seeds.flatMap { seed ->
                arms.map { arm ->
                    val namespace = listOf(
                        artifactRoot.trim('/'),
                        task.taskId,
                        "seed-$seed",
                        arm.arm.id
                    ).joinToString("/")
                    ComparativeExperimentAssignment(
                        assignmentId = AtlasHashing.hashText(
                            "$planHash|${task.taskId}|$seed|${arm.arm.name}"
                        ),
                        experimentId = experimentId,
                        planHash = planHash,
                        taskId = task.taskId,
                        taskContractHash = task.taskContractHash,
                        seed = seed,
                        arm = arm.arm,
                        artifactNamespace = namespace,
                        commitmentsHash = commitments.commitmentHash(),
                        constraintMode = constraintPolicy.mode
                    ).withComputedHash()
                }
            }
        }.also(ComparativeExperimentValidator::requireDisjointNamespaces)
    }
}

/** One exact task/seed/arm assignment generated from an immutable plan. */
@Serializable
data class ComparativeExperimentAssignment(
    val assignmentId: String,
    val experimentId: String,
    val planHash: String,
    val taskId: String,
    val taskContractHash: String,
    val seed: Long,
    val arm: ComparativeBenchmarkArm,
    val artifactNamespace: String,
    val commitmentsHash: String,
    val constraintMode: ComparativeConstraintMode,
    val assignmentHash: String = ""
) {
    init {
        require(
            listOf(
                assignmentId,
                experimentId,
                planHash,
                taskId,
                taskContractHash,
                artifactNamespace,
                commitmentsHash
            ).all(String::isNotBlank)
        ) { "Comparative assignment authority fields must not be blank" }
        require(!artifactNamespace.startsWith("/")) {
            "Comparative artifact namespace must be relative"
        }
    }

    /** Returns this assignment with its deterministic hash attached. */
    fun withComputedHash(): ComparativeExperimentAssignment =
        copy(assignmentHash = ComparativeBenchmarkHashing.hashAssignment(this))

    /** Verifies the assignment commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            assignmentHash,
            ComparativeBenchmarkHashing.hashAssignment(this)
        )
}

/** Exact independent evaluator output for one successful cell. */
@Serializable
data class ComparativeEvaluation(
    val evaluatorContractHash: String,
    val scoreComponents: Map<String, Double>,
    val overallScore: Double,
    val maximumScore: Double
) {
    init {
        require(evaluatorContractHash.isNotBlank() && scoreComponents.isNotEmpty()) {
            "Comparative evaluation contract and score components are required"
        }
        require(
            maximumScore.isFinite() &&
                maximumScore > 0.0 &&
                overallScore.isFinite() &&
                overallScore in 0.0..maximumScore &&
                scoreComponents.all { (id, score) -> id.isNotBlank() && score.isFinite() }
        ) { "Comparative evaluation scores are invalid" }
    }

    /** Normalized quality in the closed range 0.0 to 1.0. */
    val normalizedQuality: Double get() = overallScore / maximumScore
}

/**
 * Immutable result for one assignment.
 *
 * Society results must bind the exact final-deliverable reference used by benchmark scoring.
 */
@Serializable
data class ComparativeExperimentResult(
    val resultId: String,
    val assignment: ComparativeExperimentAssignment,
    val status: ComparativeCellStatus,
    val evaluation: ComparativeEvaluation? = null,
    val costUsd: Double? = null,
    val latencyMillis: Long,
    val finalDeliverableReferenceId: String? = null,
    val finalDeliverableReferenceHash: String? = null,
    val failureReason: String? = null,
    val observedCommitments: ComparativeInputCommitments,
    val observedWorkerGroupCount: Int,
    val observedGraderGroupCount: Int,
    val completedAt: Instant,
    val resultHash: String = ""
) {
    init {
        require(resultId.isNotBlank() && latencyMillis >= 0L) {
            "Comparative result identity and latency are invalid"
        }
        require(costUsd == null || costUsd.isFinite() && costUsd >= 0.0) {
            "Comparative result cost must be non-negative when known"
        }
        require(observedWorkerGroupCount >= 0 && observedGraderGroupCount >= 0) {
            "Observed comparative group counts must be non-negative"
        }
        if (status == ComparativeCellStatus.SUCCEEDED) {
            require(evaluation != null && failureReason == null) {
                "Successful comparative results require evaluation and no failure reason"
            }
        } else {
            require(evaluation == null && !failureReason.isNullOrBlank()) {
                "Failed or abstained comparative results require an explicit reason"
            }
        }
        if (assignment.arm != ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT && status == ComparativeCellStatus.SUCCEEDED) {
            require(
                !finalDeliverableReferenceId.isNullOrBlank() &&
                    !finalDeliverableReferenceHash.isNullOrBlank()
            ) { "Successful society results require exact final-deliverable authority" }
        }
    }

    /** Returns this result with its deterministic hash attached. */
    fun withComputedHash(): ComparativeExperimentResult =
        copy(resultHash = ComparativeBenchmarkHashing.hashResult(this))

    /** Verifies the immutable result commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(resultHash, ComparativeBenchmarkHashing.hashResult(this))
}

/** One retained matched-cell outcome between the baseline and a comparator arm. */
@Serializable
data class PairedCellComparison(
    val taskId: String,
    val seed: Long,
    val comparatorArm: ComparativeBenchmarkArm,
    val status: PairedCellStatus,
    val baselineStatus: ComparativeCellStatus,
    val comparatorStatus: ComparativeCellStatus,
    val qualityDifference: Double?
)

/** Deterministic confidence interval over complete paired quality differences. */
@Serializable
data class PairedConfidenceInterval(
    val sampleCount: Int,
    val meanDifference: Double?,
    val lower95: Double?,
    val upper95: Double?
)

/** Aggregate arm metrics that retain failure and abstention rates. */
@Serializable
data class ComparativeArmMetrics(
    val arm: ComparativeBenchmarkArm,
    val cellCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val abstentionCount: Int,
    val meanQuality: Double?,
    val meanCostUsd: Double?,
    val meanLatencyMillis: Double
)

/** Verifiable paired report for one complete matched experiment. */
@Serializable
data class ComparativeExperimentReport(
    val experimentId: String,
    val planHash: String,
    val armMetrics: List<ComparativeArmMetrics>,
    val pairedCells: List<PairedCellComparison>,
    val confidenceByComparator: Map<ComparativeBenchmarkArm, PairedConfidenceInterval>,
    val resultHashes: List<String>,
    val generatedAt: Instant,
    val reportHash: String = ""
) {
    /** Returns this report with its deterministic hash attached. */
    fun withComputedHash(): ComparativeExperimentReport =
        copy(reportHash = ComparativeBenchmarkHashing.hashReport(this))

    /** Verifies the report commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(reportHash, ComparativeBenchmarkHashing.hashReport(this))
}

/** Fail-closed validation for complete matched experiment results. */
object ComparativeExperimentValidator {
    /** Validates a full result matrix against [plan]. */
    fun validate(
        plan: ComparativeExperimentPlan,
        results: List<ComparativeExperimentResult>
    ) {
        require(plan.hasValidHash()) { "Comparative experiment plan hash is invalid" }
        val assignments = plan.assignments()
        require(results.size == assignments.size) {
            "Comparative result matrix must retain every task/seed/arm cell"
        }
        require(results.all { it.hasValidHash() && it.assignment.hasValidHash() }) {
            "Comparative result or assignment hash is invalid"
        }
        val expected = assignments.associateBy { it.assignmentId }
        require(results.map { it.assignment.assignmentId }.distinct().size == results.size) {
            "Comparative result assignments must be unique"
        }
        results.forEach { result ->
            require(expected[result.assignment.assignmentId] == result.assignment) {
                "Comparative result assignment does not match the sealed plan"
            }
            require(result.observedCommitments == plan.commitments) {
                "Comparative runtime commitments differ from the sealed plan"
            }
            val arm = plan.arms.single { it.arm == result.assignment.arm }
            require(result.observedWorkerGroupCount == arm.workerGroupCount) {
                "Comparative runtime worker group count differs from the sealed arm"
            }
            require(result.observedGraderGroupCount == arm.graderGroupCount) {
                "Comparative runtime grader group count differs from the sealed arm"
            }
            require(
                result.evaluation?.evaluatorContractHash == null ||
                    result.evaluation.evaluatorContractHash == plan.evaluator.evaluatorContractHash
            ) { "Comparative result used a different evaluator contract" }
            when (plan.constraintPolicy.mode) {
                ComparativeConstraintMode.ISO_COST -> require(
                    result.costUsd != null &&
                        result.costUsd <= requireNotNull(plan.constraintPolicy.maximumCostUsd)
                ) { "ISO_COST requires known in-cap cost for every result" }
                ComparativeConstraintMode.ISO_LATENCY -> require(
                    result.latencyMillis <= requireNotNull(plan.constraintPolicy.maximumLatencyMillis)
                ) { "ISO_LATENCY result exceeded the matched latency cap" }
            }
        }
        requireDisjointNamespaces(results.map { it.assignment })
    }

    /** Rejects duplicate, parent, or child artifact namespaces across assignments. */
    fun requireDisjointNamespaces(assignments: List<ComparativeExperimentAssignment>) {
        val normalized = assignments.map { it.artifactNamespace.trim('/') }
        require(normalized.distinct().size == normalized.size) {
            "Comparative artifact namespaces must be unique"
        }
        normalized.forEachIndexed { index, path ->
            require(normalized.withIndex().none { (otherIndex, other) ->
                index != otherIndex && (path.startsWith("$other/") || other.startsWith("$path/"))
            }) { "Comparative artifact namespaces must not overlap" }
        }
    }
}

/** Builds deterministic paired metrics without dropping failures or abstentions. */
object ComparativeExperimentReportBuilder {
    /** Validates [results] and builds a hash-bound paired report. */
    fun build(
        plan: ComparativeExperimentPlan,
        results: List<ComparativeExperimentResult>,
        generatedAt: Instant
    ): ComparativeExperimentReport {
        ComparativeExperimentValidator.validate(plan, results)
        val byArm = results.groupBy { it.assignment.arm }
        val metrics = ComparativeBenchmarkArm.entries.map { arm ->
            val cells = byArm.getValue(arm)
            val successes = cells.filter { it.status == ComparativeCellStatus.SUCCEEDED }
            ComparativeArmMetrics(
                arm = arm,
                cellCount = cells.size,
                successCount = successes.size,
                failureCount = cells.count { it.status == ComparativeCellStatus.FAILED },
                abstentionCount = cells.count { it.status == ComparativeCellStatus.ABSTAINED },
                meanQuality = successes.map { requireNotNull(it.evaluation).normalizedQuality }
                    .averageOrNull(),
                meanCostUsd = cells.mapNotNull { it.costUsd }.averageOrNull(),
                meanLatencyMillis = cells.map { it.latencyMillis.toDouble() }.average()
            )
        }
        val resultIndex = results.associateBy {
            Triple(it.assignment.taskId, it.assignment.seed, it.assignment.arm)
        }
        val paired = plan.tasks.flatMap { task ->
            plan.seeds.flatMap { seed ->
                ComparativeBenchmarkArm.entries
                    .filterNot { it == ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT }
                    .map { comparator ->
                        pairedCell(
                            task.taskId,
                            seed,
                            comparator,
                            resultIndex.getValue(
                                Triple(task.taskId, seed, ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT)
                            ),
                            resultIndex.getValue(Triple(task.taskId, seed, comparator))
                        )
                    }
            }
        }
        val confidence = paired.groupBy { it.comparatorArm }.mapValues { (_, cells) ->
            confidenceInterval(cells.mapNotNull { it.qualityDifference })
        }
        return ComparativeExperimentReport(
            experimentId = plan.experimentId,
            planHash = plan.planHash,
            armMetrics = metrics,
            pairedCells = paired,
            confidenceByComparator = confidence,
            resultHashes = results.map { it.resultHash }.sorted(),
            generatedAt = generatedAt
        ).withComputedHash()
    }

    private fun pairedCell(
        taskId: String,
        seed: Long,
        comparator: ComparativeBenchmarkArm,
        baseline: ComparativeExperimentResult,
        candidate: ComparativeExperimentResult
    ): PairedCellComparison {
        val status = when {
            baseline.status == ComparativeCellStatus.SUCCEEDED &&
                candidate.status == ComparativeCellStatus.SUCCEEDED -> PairedCellStatus.COMPLETE
            baseline.status != ComparativeCellStatus.SUCCEEDED &&
                candidate.status != ComparativeCellStatus.SUCCEEDED -> PairedCellStatus.BOTH_NON_SUCCESS
            baseline.status != ComparativeCellStatus.SUCCEEDED -> PairedCellStatus.BASELINE_NON_SUCCESS
            else -> PairedCellStatus.COMPARATOR_NON_SUCCESS
        }
        return PairedCellComparison(
            taskId = taskId,
            seed = seed,
            comparatorArm = comparator,
            status = status,
            baselineStatus = baseline.status,
            comparatorStatus = candidate.status,
            qualityDifference = if (status == PairedCellStatus.COMPLETE) {
                requireNotNull(candidate.evaluation).normalizedQuality -
                    requireNotNull(baseline.evaluation).normalizedQuality
            } else {
                null
            }
        )
    }

    private fun confidenceInterval(values: List<Double>): PairedConfidenceInterval {
        if (values.isEmpty()) return PairedConfidenceInterval(0, null, null, null)
        val mean = values.average()
        if (values.size == 1) return PairedConfidenceInterval(1, mean, null, null)
        val variance = values.sumOf { value -> (value - mean) * (value - mean) } /
            (values.size - 1)
        val margin = 1.96 * sqrt(variance / values.size)
        return PairedConfidenceInterval(values.size, mean, mean - margin, mean + margin)
    }
}

private fun List<Double>.averageOrNull(): Double? = takeIf(List<Double>::isNotEmpty)?.average()
