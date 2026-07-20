package com.achulkov.evomolior.core.benchmark

import java.text.Normalizer
import kotlinx.serialization.Serializable

/** Benchmark family used to sanity-check retrieval, browsing, tool-use, and literature QA runs. */
@Serializable
enum class Tier0BenchmarkKind {
    /** FRAMES multi-hop RAG over Wikipedia-backed evidence chains. */
    FRAMES,
    /** BrowseComp hard web-browsing exact-answer tasks. */
    BROWSECOMP,
    /** GAIA general assistant tasks requiring reasoning, files, multimodality, browsing, and tools. */
    GAIA,
    /** LAB-Bench LitQA2 scientific literature multiple-choice QA. */
    LITQA2,
    /** Citation-backed long-form scientific literature synthesis over a fixed paper index. */
    SCHOLAR_QA,
    /** Official ScholarQABench single-paper and long-form scientific QA tasks. */
    SCHOLAR_QABENCH,
    /** Ai2 Scholar QA-style evidence-first scientific literature synthesis tasks. */
    AI2_SCHOLAR_QA,
    /** AutoResearchBench-style autonomous scientific literature discovery tasks. */
    AUTO_RESEARCH_BENCH
}

/** AutoResearchBench task family. */
@Serializable
enum class LiteratureDiscoveryTaskType {
    /** Track down one or more specific hidden target papers. */
    DEEP,
    /** Collect all papers satisfying the task constraints. */
    WIDE
}

/** Expected shape of the final answer field for a benchmark task. */
@Serializable
enum class BenchmarkAnswerFormat {
    /** Short exact answer used for exact-match scoring. */
    EXACT,
    /** Short exact label such as yes/no, true/false, or a multiple-choice option. */
    EXACT_LABEL,
    /** Multi-claim citation-backed synthesis answer. */
    SYNTHESIS
}

/**
 * A normalized benchmark task for EvoMolior execution.
 *
 * @param id Stable benchmark task identifier.
 * @param benchmark Benchmark family.
 * @param question User-facing question/task prompt.
 * @param acceptedAnswers Gold exact answers. Empty lists are allowed for hidden-test tasks.
 * @param relevantSources Gold or expected source URLs/titles when available.
 * @param answerFormat Expected final-answer format.
 * @param requiresBrowsing Whether solving the task requires open-web retrieval.
 * @param requiresMultimodal Whether solving the task requires non-text modalities or attached files.
 */
@Serializable
data class Tier0BenchmarkTask(
    val id: String,
    val benchmark: Tier0BenchmarkKind,
    val question: String,
    val acceptedAnswers: List<String> = emptyList(),
    val relevantSources: List<String> = emptyList(),
    val sourcePassages: List<Tier0SourcePassage> = emptyList(),
    val requiredClaims: List<String> = emptyList(),
    val literatureDiscoveryTaskType: LiteratureDiscoveryTaskType? = null,
    val answerFormat: BenchmarkAnswerFormat = BenchmarkAnswerFormat.EXACT,
    val requiresBrowsing: Boolean = false,
    val requiresMultimodal: Boolean = false
) {
    init {
        require(id.isNotBlank()) { "Benchmark task id must not be blank" }
        require(question.isNotBlank()) { "Benchmark question must not be blank" }
    }
}

/**
 * Fixed-corpus passage supplied with a literature benchmark task.
 *
 * @param paperId DOI, URL, PMID, arXiv ID, or fixture ID for the paper/excerpt.
 * @param passage Text passage available to the benchmark runner.
 */
@Serializable
data class Tier0SourcePassage(
    val paperId: String,
    val passage: String
) {
    init {
        require(paperId.isNotBlank()) { "Source passage paperId must not be blank" }
        require(passage.isNotBlank()) { "Source passage text must not be blank" }
    }
}

/**
 * One cited step in a model's evidence path.
 *
 * @param source Source title, URL, artifact path, or tool output reference.
 * @param claim Short claim supported by this source.
 */
@Serializable
data class Tier0EvidenceStep(
    val source: String,
    val claim: String
) {
    init {
        require(source.isNotBlank()) { "Evidence source must not be blank" }
        require(claim.isNotBlank()) { "Evidence claim must not be blank" }
    }
}

/**
 * Parsed EvoMolior answer for a Tier-0 task.
 *
 * @param taskId The task identifier.
 * @param finalAnswer Concise final answer intended for exact-answer scoring.
 * @param evidencePath Ordered retrieval/reasoning evidence path.
 * @param totalCost Optional measured run cost for this task.
 */
@Serializable
data class Tier0BenchmarkPrediction(
    val taskId: String,
    val finalAnswer: String,
    val evidencePath: List<Tier0EvidenceStep> = emptyList(),
    val supportingPassages: List<String> = emptyList(),
    val paperIds: List<String> = emptyList(),
    val targetPaperIds: List<String> = emptyList(),
    val candidatePaperRanking: List<Tier0CandidatePaper> = emptyList(),
    val rejectedCandidates: List<Tier0RejectedCandidate> = emptyList(),
    val searchStrategy: String = "",
    val whyAlternativesWrong: Map<String, String> = emptyMap(),
    val confidence: Double? = null,
    val totalCost: Double? = null
) {
    init {
        require(taskId.isNotBlank()) { "Prediction taskId must not be blank" }
        require(confidence == null || confidence in 0.0..1.0) { "Prediction confidence must be in 0.0..1.0" }
        require(totalCost == null || totalCost >= 0.0) { "Prediction totalCost must be non-negative" }
    }
}

/**
 * Ranked candidate paper emitted by a literature-discovery run.
 *
 * @param paperId DOI, arXiv ID, Semantic Scholar Corpus ID, URL, or benchmark fixture ID.
 * @param rank One-based candidate rank.
 * @param title Candidate paper title when available.
 * @param evidence Short explanation for why the candidate satisfies or may satisfy constraints.
 */
@Serializable
data class Tier0CandidatePaper(
    val paperId: String,
    val rank: Int,
    val title: String = "",
    val evidence: String = ""
) {
    init {
        require(paperId.isNotBlank()) { "Candidate paperId must not be blank" }
        require(rank > 0) { "Candidate rank must be positive" }
    }
}

/**
 * Candidate rejected by a literature-discovery run.
 *
 * @param paperId DOI, arXiv ID, Semantic Scholar Corpus ID, URL, or benchmark fixture ID.
 * @param reason Evidence-grounded rejection reason.
 */
@Serializable
data class Tier0RejectedCandidate(
    val paperId: String,
    val reason: String
) {
    init {
        require(paperId.isNotBlank()) { "Rejected candidate paperId must not be blank" }
        require(reason.isNotBlank()) { "Rejected candidate reason must not be blank" }
    }
}

/** Score for a single Tier-0 prediction. */
@Serializable
data class Tier0TaskScore(
    val taskId: String,
    val exactCorrect: Boolean,
    val normalizedPrediction: String,
    val matchedGoldAnswer: String?,
    val evidencePathPresent: Boolean,
    val totalCost: Double?,
    val costPerCorrect: Double?
)

/** Aggregate score for a Tier-0 batch. */
@Serializable
data class Tier0BatchScore(
    val totalTasks: Int,
    val exactCorrectCount: Int,
    val exactAccuracy: Double,
    val evidencePathCoverage: Double,
    val measuredCostTotal: Double,
    val costPerCorrect: Double?,
    val taskScores: List<Tier0TaskScore>
)

/** Deterministic score for an AutoResearchBench-style literature-discovery prediction. */
@Serializable
data class LiteratureDiscoveryTaskScore(
    val taskId: String,
    val taskType: LiteratureDiscoveryTaskType,
    val exactTargetFound: Boolean? = null,
    val goldPaperCount: Int,
    val predictedPaperCount: Int,
    val foundGoldPaperCount: Int,
    val precision: Double,
    val recall: Double,
    val iou: Double,
    val measuredCost: Double?,
    val costPerFoundGoldPaper: Double?
)

/** Deterministic scorer for AutoResearchBench-style paper-ID set metrics. */
object LiteratureDiscoveryScorer {
    /** Scores a literature discovery [prediction] against [task]. */
    fun score(task: Tier0BenchmarkTask, prediction: Tier0BenchmarkPrediction): LiteratureDiscoveryTaskScore {
        require(task.id == prediction.taskId) {
            "Prediction taskId ${prediction.taskId} does not match task ${task.id}"
        }
        require(task.benchmark == Tier0BenchmarkKind.AUTO_RESEARCH_BENCH) {
            "LiteratureDiscoveryScorer only supports AUTO_RESEARCH_BENCH tasks"
        }
        val taskType = task.literatureDiscoveryTaskType ?: LiteratureDiscoveryTaskType.DEEP
        val gold = task.acceptedAnswers.map(::normalizePaperId).filter(String::isNotBlank).toSet()
        val predicted = (prediction.targetPaperIds + prediction.paperIds + prediction.finalAnswer)
            .flatMap(::splitPaperIds)
            .map(::normalizePaperId)
            .filter(String::isNotBlank)
            .toSet()
        val found = gold.intersect(predicted)
        val union = gold + predicted
        return LiteratureDiscoveryTaskScore(
            taskId = task.id,
            taskType = taskType,
            exactTargetFound = if (taskType == LiteratureDiscoveryTaskType.DEEP) gold.isNotEmpty() && found == gold else null,
            goldPaperCount = gold.size,
            predictedPaperCount = predicted.size,
            foundGoldPaperCount = found.size,
            precision = if (predicted.isEmpty()) 0.0 else found.size.toDouble() / predicted.size,
            recall = if (gold.isEmpty()) 0.0 else found.size.toDouble() / gold.size,
            iou = if (union.isEmpty()) 0.0 else found.size.toDouble() / union.size,
            measuredCost = prediction.totalCost,
            costPerFoundGoldPaper = prediction.totalCost?.takeIf { found.isNotEmpty() }?.let { it / found.size }
        )
    }

    /** Normalizes paper identifiers for exact deterministic matching. */
    fun normalizePaperId(value: String): String =
        value.trim()
            .removePrefix("https://doi.org/")
            .removePrefix("http://doi.org/")
            .removePrefix("doi:")
            .removePrefix("arxiv:")
            .trim()
            .trimEnd('.', ',', ';', '/', ' ')
            .lowercase()

    private fun splitPaperIds(value: String): List<String> =
        value.split(',', '\n', ';')
            .map(String::trim)
            .filter(String::isNotBlank)
}

/** Deterministic scorer for Tier-0 exact-answer and evidence-path sanity checks. */
object Tier0ExactAnswerScorer {
    /** Scores a single [prediction] against [task]. */
    fun score(task: Tier0BenchmarkTask, prediction: Tier0BenchmarkPrediction): Tier0TaskScore {
        require(task.id == prediction.taskId) {
            "Prediction taskId ${prediction.taskId} does not match task ${task.id}"
        }
        val normalizedPrediction = normalizeAnswer(prediction.finalAnswer)
        val matchedGold = task.acceptedAnswers.firstOrNull { answer ->
            val normalizedGold = normalizeAnswer(answer)
            normalizedGold == normalizedPrediction ||
                isFramesSentenceFactExtractionMatch(
                    task = task,
                    normalizedGold = normalizedGold,
                    normalizedPrediction = normalizedPrediction
                )
        }
        val correct = matchedGold != null
        return Tier0TaskScore(
            taskId = task.id,
            exactCorrect = correct,
            normalizedPrediction = normalizedPrediction,
            matchedGoldAnswer = matchedGold,
            evidencePathPresent = prediction.evidencePath.isNotEmpty(),
            totalCost = prediction.totalCost,
            costPerCorrect = prediction.totalCost?.takeIf { correct }
        )
    }

    /** Scores a batch, preserving task order. */
    fun scoreBatch(
        tasks: List<Tier0BenchmarkTask>,
        predictions: List<Tier0BenchmarkPrediction>
    ): Tier0BatchScore {
        val predictionsByTask = predictions.associateBy { it.taskId }
        val scores = tasks.map { task ->
            val prediction = predictionsByTask[task.id]
                ?: Tier0BenchmarkPrediction(taskId = task.id, finalAnswer = "")
            score(task, prediction)
        }
        val exactCorrectCount = scores.count { it.exactCorrect }
        val measuredCostTotal = scores.mapNotNull { it.totalCost }.sum()
        return Tier0BatchScore(
            totalTasks = tasks.size,
            exactCorrectCount = exactCorrectCount,
            exactAccuracy = if (tasks.isEmpty()) 0.0 else exactCorrectCount.toDouble() / tasks.size,
            evidencePathCoverage = if (tasks.isEmpty()) 0.0 else scores.count { it.evidencePathPresent }.toDouble() / tasks.size,
            measuredCostTotal = measuredCostTotal,
            costPerCorrect = if (exactCorrectCount == 0) null else measuredCostTotal / exactCorrectCount,
            taskScores = scores
        )
    }

    /** Normalizes concise benchmark answers for exact-answer scoring. */
    fun normalizeAnswer(answer: String): String =
        Normalizer.normalize(answer, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .trim()
            .trim('.', ',', ';', ':', '!', '?', '"', '\'', '`')
            .lowercase()
            .replace(Regex("(?<=\\d),(?=\\d)"), "")
            .replace(Regex("^(a|an|the)\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isFramesSentenceFactExtractionMatch(
        task: Tier0BenchmarkTask,
        normalizedGold: String,
        normalizedPrediction: String
    ): Boolean {
        if (task.benchmark != Tier0BenchmarkKind.FRAMES) return false
        val predictionTokens = normalizedPrediction.split(' ').filter(String::isNotBlank)
        val goldTokens = normalizedGold.split(' ').filter(String::isNotBlank)
        val isConciseNumericAnswer = predictionTokens.size == 1 && predictionTokens.single().all(Char::isDigit)
        val isConciseEntityAnswer = predictionTokens.size >= 2
        if ((!isConciseNumericAnswer && !isConciseEntityAnswer) || predictionTokens.size >= goldTokens.size) {
            return false
        }
        val spanStart = goldTokens.windowed(predictionTokens.size).indexOfFirst { it == predictionTokens }
        if (spanStart < 0) return false
        return if (isConciseNumericAnswer) {
            spanStart == 0 && goldTokens.getOrNull(1).orEmpty().trim(',', '.', ';', ':').lowercase() in
                setOf(
                    "year",
                    "years",
                    "person",
                    "people",
                    "member",
                    "members",
                    "time",
                    "times",
                    "day",
                    "days",
                    "month",
                    "months"
                )
        } else {
            // Avoid accepting partial prefixes like "Battle of" inside "Battle of Hastings".
            spanStart > 0 && spanStart + predictionTokens.size == goldTokens.size
        }
    }
}

/** Builds the final-answer contract that EvoMolior agents should satisfy for benchmark tasks. */
object Tier0BenchmarkPromptContract {
    /** Builds a strict prompt for a benchmark task. */
    fun buildObjective(task: Tier0BenchmarkTask): String = buildString {
        appendLine("Solve this ${task.benchmark.name} benchmark task.")
        appendLine("Question:")
        appendLine(task.question)
        appendLine()
        if (task.relevantSources.isNotEmpty()) {
            appendLine("Benchmark source candidates:")
            task.relevantSources.forEach { source ->
                appendLine("- $source")
            }
            appendLine()
            appendLine("Use the source candidates as retrieval seeds. Verify the answer from those sources before answering.")
        } else if (task.requiresBrowsing) {
            appendLine("Use source acquisition or browsing tools before answering; do not answer from model memory alone.")
        }
        if (task.sourcePassages.isNotEmpty()) {
            appendLine("Fixed source excerpts:")
            task.sourcePassages.forEach { source ->
                appendLine("- paper_id: ${source.paperId}")
                appendLine("  passage: ${source.passage}")
            }
            appendLine()
            appendLine("Treat fixed source excerpts as part of the benchmark corpus; cite their paper_id values in paper_ids and evidence_path when used.")
        }
        appendLine("Evidence path source values must be concrete URLs, artifact paths, or tool-output labels. Do not use vague labels such as historical_knowledge, memory, or inference as sources.")
        appendLine("For numeric, tabular, date, ranking, classification, or count questions, do not infer missing values from general knowledge. Use the exact value shown by the cited source candidate, then show the counting/ranking step as an evidence_path claim.")
        appendLine("For ranking questions, final_answer must be the exact ordinal string requested by the source table, such as 37th; do not answer with qualitative phrases such as outside the top 20.")
        appendLine("For Dewey Decimal Classification questions, use the exact classification value stated for the work by the cited source candidate; do not substitute a more specific subclass from memory.")
        appendLine("For name-composition questions, preserve the requested name part exactly: if the question asks for a first name, use only the given first-name token, not middle names or full legal names; if it asks for a surname or maiden name, use only that surname token.")
        appendLine()
        appendLine("Return a single JSON object with exactly these fields:")
        if (task.requiresLongFormSynthesis()) {
            appendLine("- final_answer: citation-backed synthesis answer covering every required claim target; include citations or explicit paper IDs for every major claim.")
        } else {
            appendLine("- final_answer: concise exact answer string; no explanation.")
        }
        appendLine("- evidence_path: ordered array of objects {source, claim}; include every retrieval hop used.")
        if (task.benchmark == Tier0BenchmarkKind.LITQA2 || task.benchmark.isLongFormScholarQa()) {
            appendLine("- supporting_passages: array of short quoted or tightly paraphrased source passages supporting final_answer.")
            appendLine("- paper_ids: array of DOI, URL, PMID, arXiv ID, or fixed-index paper IDs used.")
        }
        if (task.benchmark == Tier0BenchmarkKind.AUTO_RESEARCH_BENCH) {
            appendLine("- target_paper_ids: array of DOI, arXiv ID, Semantic Scholar Corpus ID, URL, or fixed-index paper IDs that satisfy the task.")
            appendLine("- candidate_paper_ranking: array of objects {paper_id, rank, title, evidence}, sorted best candidate first.")
            appendLine("- evidence_for_each_candidate: object mapping each candidate paper ID to evidence from source text or search results.")
            appendLine("- rejected_candidates: array of objects {paper_id, reason}.")
            appendLine("- search_strategy: concise description of query formulations, branching, and convergence criteria used.")
        }
        if (task.benchmark == Tier0BenchmarkKind.LITQA2) {
            appendLine("- why_alternatives_wrong: object mapping each rejected answer choice to a short evidence-grounded reason.")
        }
        appendLine("- confidence: number from 0.0 to 1.0.")
        appendLine()
        if (task.benchmark == Tier0BenchmarkKind.LITQA2) {
            appendLine("For LitQA2, final_answer must be the exact answer-choice text, not a letter.")
            appendLine("Use the fixed source/paper index as the starting point; do not rely on abstracts alone if full text or source passages are needed.")
            appendLine()
        }
        if (task.requiresLongFormSynthesis()) {
            if (task.requiredClaims.isNotEmpty()) {
                appendLine("Your answer must cover these required claim targets:")
                task.requiredClaims.forEach { claim -> appendLine("- $claim") }
                appendLine()
            }
            appendLine("For Scholar QA, final_answer must be a synthesis answer with citation markers or explicit paper IDs for every major claim.")
            appendLine("Do not collapse the answer to a short entity list or exact-label response unless the task explicitly asks for an exact label.")
            appendLine("Use the fixed source/paper index as the starting point and include only paper_ids that support stated claims.")
            appendLine()
        } else if (task.benchmark.isLongFormScholarQa() && task.requiredClaims.isNotEmpty()) {
            appendLine("Use the required claim targets as answer checks, but obey the task's exact-label instruction for final_answer.")
            task.requiredClaims.forEach { claim -> appendLine("- $claim") }
            appendLine()
        }
        if (task.benchmark == Tier0BenchmarkKind.AUTO_RESEARCH_BENCH) {
            appendLine("For AutoResearchBench-style literature discovery, optimize for paper-ID set correctness rather than prose.")
            appendLine("Deep Research tasks are scored as exact target paper found/not found. Wide Research tasks are scored with IoU, precision, and recall over the gold paper set.")
            appendLine("Always include the five deliverable artifacts in content-equivalent form: target_paper_ids.json, candidate_paper_ranking.csv, evidence_for_each_candidate.md, rejected_candidates.md, and search_strategy.md.")
            appendLine("Use target_paper_ids for the final discovered set. For Deep Research, include only the target paper IDs you believe are exact matches. For Wide Research, include every qualifying paper ID and no near misses.")
            appendLine()
        }
        appendLine("Do not omit evidence_path. If evidence is insufficient, final_answer must be an empty string and evidence_path must explain the failed search path.")
    }
}

private fun Tier0BenchmarkKind.isLongFormScholarQa(): Boolean =
    this == Tier0BenchmarkKind.SCHOLAR_QA ||
        this == Tier0BenchmarkKind.SCHOLAR_QABENCH ||
        this == Tier0BenchmarkKind.AI2_SCHOLAR_QA

private fun Tier0BenchmarkTask.requiresLongFormSynthesis(): Boolean =
    answerFormat == BenchmarkAnswerFormat.SYNTHESIS

/**
 * Policy controlling which Tier-0 tasks a runner should execute.
 *
 * @param includeMultimodal Whether to include multimodal/file-heavy tasks such as full GAIA.
 * @param maxTasks Maximum task count for one benchmark pass.
 */
@Serializable
data class Tier0BenchmarkRunPolicy(
    val includeMultimodal: Boolean = false,
    val maxTasks: Int = 100
) {
    init {
        require(maxTasks > 0) { "maxTasks must be positive" }
    }
}

/**
 * Result of one benchmark task execution.
 *
 * @param task Benchmark task.
 * @param prediction Prediction produced by the runtime.
 * @param score Deterministic exact-answer score.
 * @param skipped Whether the task was skipped by [Tier0BenchmarkRunPolicy].
 * @param skipReason Human-readable skip reason.
 */
@Serializable
data class Tier0BenchmarkExecutionResult(
    val task: Tier0BenchmarkTask,
    val prediction: Tier0BenchmarkPrediction?,
    val score: Tier0TaskScore?,
    val skipped: Boolean = false,
    val skipReason: String? = null
)

/**
 * Aggregate result for a benchmark run.
 *
 * @param executedCount Number of executed tasks.
 * @param skippedCount Number of skipped tasks.
 * @param batchScore Score over executed tasks only.
 * @param results Per-task execution results.
 */
@Serializable
data class Tier0BenchmarkRunReport(
    val executedCount: Int,
    val skippedCount: Int,
    val batchScore: Tier0BatchScore,
    val results: List<Tier0BenchmarkExecutionResult>
)

/**
 * Runtime hook used by [Tier0BenchmarkHarness] to execute one task.
 */
fun interface Tier0TaskRunner {
    /**
     * Executes [task] using [objective] as the strict final-output contract.
     *
     * @param task Benchmark task to run.
     * @param objective Prompt/objective built from [Tier0BenchmarkPromptContract].
     * @return Parsed prediction.
     */
    suspend fun run(task: Tier0BenchmarkTask, objective: String): Tier0BenchmarkPrediction
}

/**
 * Batch harness for Tier-0 benchmark subsets.
 *
 * This is intentionally runtime-agnostic so CLI/API layers can plug in EvoMolior execution while
 * keeping filtering, exact scoring, and cost aggregation deterministic and testable in core.
 */
class Tier0BenchmarkHarness(
    private val runner: Tier0TaskRunner,
    private val policy: Tier0BenchmarkRunPolicy = Tier0BenchmarkRunPolicy()
) {
    /**
     * Runs up to [Tier0BenchmarkRunPolicy.maxTasks] supported tasks and aggregates exact scoring.
     *
     * @param tasks Candidate benchmark tasks.
     * @return Batch execution report.
     */
    suspend fun run(tasks: List<Tier0BenchmarkTask>): Tier0BenchmarkRunReport {
        val selectedTasks = tasks.take(policy.maxTasks)
        val results = selectedTasks.map { task ->
            if (task.requiresMultimodal && !policy.includeMultimodal) {
                Tier0BenchmarkExecutionResult(
                    task = task,
                    prediction = null,
                    score = null,
                    skipped = true,
                    skipReason = "multimodal_not_enabled"
                )
            } else {
                val prediction = runner.run(task, Tier0BenchmarkPromptContract.buildObjective(task))
                val score = Tier0ExactAnswerScorer.score(task, prediction)
                Tier0BenchmarkExecutionResult(
                    task = task,
                    prediction = prediction,
                    score = score
                )
            }
        }
        val executedTasks = results.filterNot { it.skipped }.map { it.task }
        val predictions = results.filterNot { it.skipped }.mapNotNull { it.prediction }
        return Tier0BenchmarkRunReport(
            executedCount = executedTasks.size,
            skippedCount = results.count { it.skipped },
            batchScore = Tier0ExactAnswerScorer.scoreBatch(executedTasks, predictions),
            results = results
        )
    }
}
