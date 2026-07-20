package com.achulkov.evomolior.core.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Tier0ExactAnswerScorerTest {

    @Test
    fun `scores normalized exact match and evidence path`() {
        val task = Tier0BenchmarkTask(
            id = "frames-1",
            benchmark = Tier0BenchmarkKind.FRAMES,
            question = "Who wrote the novel?",
            acceptedAnswers = listOf("Mary Shelley")
        )
        val prediction = Tier0BenchmarkPrediction(
            taskId = "frames-1",
            finalAnswer = " mary shelley. ",
            evidencePath = listOf(Tier0EvidenceStep(source = "Wikipedia: Frankenstein", claim = "Lists Mary Shelley as author.")),
            totalCost = 1.25
        )

        val score = Tier0ExactAnswerScorer.score(task, prediction)

        assertTrue(score.exactCorrect)
        assertEquals("Mary Shelley", score.matchedGoldAnswer)
        assertTrue(score.evidencePathPresent)
        assertEquals(1.25, score.costPerCorrect)
    }

    @Test
    fun `scores numeric exact match independent of thousands separators`() {
        val task = Tier0BenchmarkTask(
            id = "frames-5",
            benchmark = Tier0BenchmarkKind.FRAMES,
            question = "What is the population rounded to the nearest thousand?",
            acceptedAnswers = listOf("506000")
        )
        val prediction = Tier0BenchmarkPrediction(
            taskId = "frames-5",
            finalAnswer = "506,000",
            evidencePath = listOf(Tier0EvidenceStep(source = "Wikipedia: Oklahoma City", claim = "2000 census population rounds to 506,000."))
        )

        val score = Tier0ExactAnswerScorer.score(task, prediction)

        assertTrue(score.exactCorrect)
        assertEquals("506000", score.normalizedPrediction)
        assertEquals("506000", score.matchedGoldAnswer)
    }

    @Test
    fun `scores exact match independent of leading articles`() {
        val task = Tier0BenchmarkTask(
            id = "frames-9",
            benchmark = Tier0BenchmarkKind.FRAMES,
            question = "Which battle?",
            acceptedAnswers = listOf("The Battle of Hastings.")
        )
        val prediction = Tier0BenchmarkPrediction(
            taskId = "frames-9",
            finalAnswer = "Battle of Hastings",
            evidencePath = listOf(Tier0EvidenceStep(source = "Wikipedia: Battle of Hastings", claim = "The conflict took place in 1066."))
        )

        val score = Tier0ExactAnswerScorer.score(task, prediction)

        assertTrue(score.exactCorrect)
        assertEquals("battle of hastings", score.normalizedPrediction)
        assertEquals("The Battle of Hastings.", score.matchedGoldAnswer)
    }

    @Test
    fun `preserves mid phrase articles during exact match normalization`() {
        assertEquals(
            "war of the roses",
            Tier0ExactAnswerScorer.normalizeAnswer("War of the Roses")
        )
    }

    @Test
    fun `scores exact match independent of diacritics`() {
        val task = Tier0BenchmarkTask(
            id = "frames-14",
            benchmark = Tier0BenchmarkKind.FRAMES,
            question = "Which player?",
            acceptedAnswers = listOf("Mesut Ozil")
        )
        val prediction = Tier0BenchmarkPrediction(
            taskId = "frames-14",
            finalAnswer = "Mesut Özil",
            evidencePath = listOf(Tier0EvidenceStep(source = "Wikipedia: Mesut Özil", claim = "The player later joined Arsenal."))
        )

        val score = Tier0ExactAnswerScorer.score(task, prediction)

        assertTrue(score.exactCorrect)
        assertEquals("mesut ozil", score.normalizedPrediction)
        assertEquals("Mesut Ozil", score.matchedGoldAnswer)
    }

    @Test
    fun `scores frames concise entity match inside sentence shaped gold answer`() {
        val task = Tier0BenchmarkTask(
            id = "frames-14",
            benchmark = Tier0BenchmarkKind.FRAMES,
            question = "Which player later played for Arsenal?",
            acceptedAnswers = listOf("This was Mesut Ozil.")
        )
        val prediction = Tier0BenchmarkPrediction(
            taskId = "frames-14",
            finalAnswer = "Mesut Özil",
            evidencePath = listOf(Tier0EvidenceStep(source = "Wikipedia: Mesut Özil", claim = "The player later joined Arsenal."))
        )

        val score = Tier0ExactAnswerScorer.score(task, prediction)

        assertTrue(score.exactCorrect)
        assertEquals("mesut ozil", score.normalizedPrediction)
        assertEquals("This was Mesut Ozil.", score.matchedGoldAnswer)
    }

    @Test
    fun `scores frames concise numeric match inside expanded gold answer`() {
        val task = Tier0BenchmarkTask(
            id = "frames-18",
            benchmark = Tier0BenchmarkKind.FRAMES,
            question = "How many years?",
            acceptedAnswers = listOf("98 Years (Arlington, TX & Rubik's Cube)")
        )
        val prediction = Tier0BenchmarkPrediction(
            taskId = "frames-18",
            finalAnswer = "98",
            evidencePath = listOf(Tier0EvidenceStep(source = "calculation", claim = "1974 - 1876 = 98 years."))
        )

        val score = Tier0ExactAnswerScorer.score(task, prediction)

        assertTrue(score.exactCorrect)
        assertEquals("98", score.normalizedPrediction)
        assertEquals("98 Years (Arlington, TX & Rubik's Cube)", score.matchedGoldAnswer)
    }

    @Test
    fun `does not score frames partial entity span inside longer gold answer`() {
        val task = Tier0BenchmarkTask(
            id = "frames-partial-entity",
            benchmark = Tier0BenchmarkKind.FRAMES,
            question = "Which battle?",
            acceptedAnswers = listOf("This was Battle of Hastings.")
        )
        val prediction = Tier0BenchmarkPrediction(
            taskId = "frames-partial-entity",
            finalAnswer = "Battle of",
            evidencePath = listOf(Tier0EvidenceStep(source = "source", claim = "claim"))
        )

        val score = Tier0ExactAnswerScorer.score(task, prediction)

        assertFalse(score.exactCorrect)
    }

    @Test
    fun `does not score frames numeric metadata span inside longer gold answer`() {
        val task = Tier0BenchmarkTask(
            id = "frames-numeric-metadata",
            benchmark = Tier0BenchmarkKind.FRAMES,
            question = "Which answer option?",
            acceptedAnswers = listOf("Option 4 is not the answer")
        )
        val prediction = Tier0BenchmarkPrediction(
            taskId = "frames-numeric-metadata",
            finalAnswer = "4",
            evidencePath = listOf(Tier0EvidenceStep(source = "source", claim = "claim"))
        )

        val score = Tier0ExactAnswerScorer.score(task, prediction)

        assertFalse(score.exactCorrect)
    }

    @Test
    fun `does not apply frames sentence shaped gold matching to other benchmarks`() {
        val task = Tier0BenchmarkTask(
            id = "browse-1",
            benchmark = Tier0BenchmarkKind.BROWSECOMP,
            question = "Which player?",
            acceptedAnswers = listOf("This was Mesut Ozil.")
        )
        val prediction = Tier0BenchmarkPrediction(
            taskId = "browse-1",
            finalAnswer = "Mesut Ozil",
            evidencePath = listOf(Tier0EvidenceStep(source = "source", claim = "claim"))
        )

        val score = Tier0ExactAnswerScorer.score(task, prediction)

        assertFalse(score.exactCorrect)
    }

    @Test
    fun `batch score computes accuracy evidence coverage and cost per correct`() {
        val tasks = listOf(
            Tier0BenchmarkTask("browse-1", Tier0BenchmarkKind.BROWSECOMP, "Q1", listOf("alpha")),
            Tier0BenchmarkTask("browse-2", Tier0BenchmarkKind.BROWSECOMP, "Q2", listOf("beta"))
        )
        val predictions = listOf(
            Tier0BenchmarkPrediction(
                taskId = "browse-1",
                finalAnswer = "alpha",
                evidencePath = listOf(Tier0EvidenceStep("source", "claim")),
                totalCost = 2.0
            ),
            Tier0BenchmarkPrediction(taskId = "browse-2", finalAnswer = "wrong", totalCost = 3.0)
        )

        val score = Tier0ExactAnswerScorer.scoreBatch(tasks, predictions)

        assertEquals(2, score.totalTasks)
        assertEquals(1, score.exactCorrectCount)
        assertEquals(0.5, score.exactAccuracy)
        assertEquals(0.5, score.evidencePathCoverage)
        assertEquals(5.0, score.measuredCostTotal)
        assertEquals(5.0, score.costPerCorrect)
        assertFalse(score.taskScores[1].exactCorrect)
        assertNull(score.taskScores[1].costPerCorrect)
    }

    @Test
    fun `prompt contract requires exact answer and evidence path json`() {
        val prompt = Tier0BenchmarkPromptContract.buildObjective(
            Tier0BenchmarkTask(
                id = "gaia-1",
                benchmark = Tier0BenchmarkKind.GAIA,
                question = "Find the requested fact.",
                requiresBrowsing = true,
                requiresMultimodal = true
            )
        )

        assertTrue(prompt.contains("final_answer"))
        assertTrue(prompt.contains("evidence_path"))
        assertTrue(prompt.contains("single JSON object"))
        assertTrue(prompt.contains("Do not omit evidence_path"))
    }

    @Test
    fun `prompt contract includes source candidates when available`() {
        val prompt = Tier0BenchmarkPromptContract.buildObjective(
            Tier0BenchmarkTask(
                id = "frames-1",
                benchmark = Tier0BenchmarkKind.FRAMES,
                question = "Find the requested answer.",
                relevantSources = listOf("https://en.wikipedia.org/wiki/Jane_Eyre"),
                requiresBrowsing = true
            )
        )

        assertTrue(prompt.contains("Benchmark source candidates"))
        assertTrue(prompt.contains("https://en.wikipedia.org/wiki/Jane_Eyre"))
        assertTrue(prompt.contains("Do not use vague labels"))
        assertTrue(prompt.contains("Dewey Decimal Classification"))
        assertTrue(prompt.contains("name-composition questions"))
        assertTrue(prompt.contains("outside the top 20"))
    }

    @Test
    fun `litqa2 prompt contract requires literature evidence fields`() {
        val prompt = Tier0BenchmarkPromptContract.buildObjective(
            Tier0BenchmarkTask(
                id = "litqa2-1",
                benchmark = Tier0BenchmarkKind.LITQA2,
                question = "Which option is supported by the paper?\n\nAnswer choices:\n- alpha\n- beta",
                acceptedAnswers = listOf("alpha"),
                relevantSources = listOf("https://doi.org/10.1234/example"),
                requiresBrowsing = true
            )
        )

        assertTrue(prompt.contains("supporting_passages"))
        assertTrue(prompt.contains("paper_ids"))
        assertTrue(prompt.contains("why_alternatives_wrong"))
        assertTrue(prompt.contains("https://doi.org/10.1234/example"))
        assertTrue(prompt.contains("exact answer-choice text"))
    }

    @Test
    fun `scholar qa prompt contract requires citation backed synthesis fields`() {
        val prompt = Tier0BenchmarkPromptContract.buildObjective(
            Tier0BenchmarkTask(
                id = "scholarqa-1",
                benchmark = Tier0BenchmarkKind.SCHOLAR_QA,
                question = "Synthesize how two papers explain mechanism X.",
                relevantSources = listOf("10.1234/a", "10.1234/b"),
                sourcePassages = listOf(Tier0SourcePassage("10.1234/a", "mechanism X increases signal Y in treated cells.")),
                requiredClaims = listOf("mechanism X increases signal Y"),
                answerFormat = BenchmarkAnswerFormat.SYNTHESIS,
                requiresBrowsing = false
            )
        )

        assertTrue(prompt.contains("supporting_passages"))
        assertTrue(prompt.contains("paper_ids"))
        assertTrue(prompt.contains("required claim targets"))
        assertTrue(prompt.contains("mechanism X increases signal Y"))
        assertTrue(prompt.contains("Fixed source excerpts"))
        assertTrue(prompt.contains("paper_id: 10.1234/a"))
        assertTrue(prompt.contains("synthesis answer"))
        assertTrue(prompt.contains("citation-backed synthesis answer"))
        assertTrue(prompt.contains("Do not collapse the answer to a short entity list"))
        assertFalse(prompt.contains("concise exact answer string; no explanation"))
    }

    @Test
    fun `scholar qa exact label prompt keeps concise final answer contract`() {
        val prompt = Tier0BenchmarkPromptContract.buildObjective(
            Tier0BenchmarkTask(
                id = "scholarqabench-pubmed-1",
                benchmark = Tier0BenchmarkKind.SCHOLAR_QABENCH,
                question = "Is monitoring useful?\n\nReturn the exact concise answer label when the task is yes/no or true/false.",
                acceptedAnswers = listOf("yes"),
                relevantSources = listOf("pubmed-ctx-1"),
                requiredClaims = listOf("yes"),
                answerFormat = BenchmarkAnswerFormat.EXACT_LABEL,
                requiresBrowsing = false
            )
        )

        assertTrue(prompt.contains("concise exact answer string; no explanation"))
        assertTrue(prompt.contains("exact-label instruction"))
        assertFalse(prompt.contains("Do not collapse the answer to a short entity list"))
    }

    @Test
    fun `literature discovery scorer computes deep exact found and cost`() {
        val task = Tier0BenchmarkTask(
            id = "arb-deep-1",
            benchmark = Tier0BenchmarkKind.AUTO_RESEARCH_BENCH,
            question = "Find the target paper.",
            acceptedAnswers = listOf("https://doi.org/10.1234/Target"),
            literatureDiscoveryTaskType = LiteratureDiscoveryTaskType.DEEP
        )
        val prediction = Tier0BenchmarkPrediction(
            taskId = "arb-deep-1",
            finalAnswer = "",
            targetPaperIds = listOf("doi:10.1234/target"),
            totalCost = 0.42
        )

        val score = LiteratureDiscoveryScorer.score(task, prediction)

        assertTrue(score.exactTargetFound == true)
        assertEquals(1, score.foundGoldPaperCount)
        assertEquals(1.0, score.precision)
        assertEquals(1.0, score.recall)
        assertEquals(1.0, score.iou)
        assertEquals(0.42, score.costPerFoundGoldPaper)
    }

    @Test
    fun `literature discovery scorer computes wide set metrics`() {
        val task = Tier0BenchmarkTask(
            id = "arb-wide-1",
            benchmark = Tier0BenchmarkKind.AUTO_RESEARCH_BENCH,
            question = "Find all qualifying papers.",
            acceptedAnswers = listOf("10.1/a", "10.1/b", "10.1/c"),
            literatureDiscoveryTaskType = LiteratureDiscoveryTaskType.WIDE
        )
        val prediction = Tier0BenchmarkPrediction(
            taskId = "arb-wide-1",
            finalAnswer = "",
            targetPaperIds = listOf("10.1/a", "10.1/b", "10.1/x")
        )

        val score = LiteratureDiscoveryScorer.score(task, prediction)

        assertNull(score.exactTargetFound)
        assertEquals(2, score.foundGoldPaperCount)
        assertEquals(2.0 / 3.0, score.precision)
        assertEquals(2.0 / 3.0, score.recall)
        assertEquals(0.5, score.iou)
    }

    @Test
    fun `autoresearchbench prompt contract requires discovery artifacts`() {
        val prompt = Tier0BenchmarkPromptContract.buildObjective(
            Tier0BenchmarkTask(
                id = "arb-1",
                benchmark = Tier0BenchmarkKind.AUTO_RESEARCH_BENCH,
                question = "Find the target paper matching constraints.",
                acceptedAnswers = listOf("10.1234/example"),
                literatureDiscoveryTaskType = LiteratureDiscoveryTaskType.DEEP,
                relevantSources = listOf("https://doi.org/10.1234/seed")
            )
        )

        assertTrue(prompt.contains("target_paper_ids.json"))
        assertTrue(prompt.contains("candidate_paper_ranking.csv"))
        assertTrue(prompt.contains("evidence_for_each_candidate.md"))
        assertTrue(prompt.contains("rejected_candidates.md"))
        assertTrue(prompt.contains("search_strategy.md"))
        assertTrue(prompt.contains("Deep Research tasks"))
        assertTrue(prompt.contains("Wide Research tasks"))
    }
}
