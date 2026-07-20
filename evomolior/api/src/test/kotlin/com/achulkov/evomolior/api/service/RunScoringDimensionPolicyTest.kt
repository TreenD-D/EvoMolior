package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.ScoringDimensionCatalog
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunScoringDimensionPolicyTest {
    @Test
    fun `implementation runs enable feasibility and robustness`() {
        val dimensions = RunScoringDimensionPolicy.select(
            config(
                objective = "Implement a safe API patch with tests",
                finalOutputSpec = FinalOutputSpec(
                    relativePath = "patch.diff",
                    mediaType = "text/plain",
                    kind = FinalOutputKind.PATCH_BUNDLE
                )
            )
        )
        val ids = dimensions.map { it.id }.toSet()

        assertTrue(ScoringDimensionCatalog.IMPLEMENTATION_FEASIBILITY in ids)
        assertTrue(ScoringDimensionCatalog.ROBUSTNESS in ids)
        assertTrue(dimensions.first { it.id == ScoringDimensionCatalog.CORRECTNESS }.weight > 1.0)
    }

    @Test
    fun `evidence runs enable evidence and novelty but not implementation-only axes`() {
        val ids = RunScoringDimensionPolicy.select(
            config(
                objective = "Write a sourced research article with citations",
                finalOutputSpec = FinalOutputSpec(
                    relativePath = "article.html",
                    mediaType = "text/html",
                    kind = FinalOutputKind.HTML_ARTICLE
                )
            )
        ).map { it.id }.toSet()

        assertTrue(ScoringDimensionCatalog.EVIDENCE_QUALITY in ids)
        assertTrue(ScoringDimensionCatalog.NOVELTY in ids)
        assertFalse(ScoringDimensionCatalog.IMPLEMENTATION_FEASIBILITY in ids)
    }

    @Test
    fun `policy preserves bespoke dimensions alongside selected canonical dimensions`() {
        val dimensions = RunScoringDimensionPolicy.select(
            config(
                objective = "Assess a launch proposal",
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "brand_fit",
                        name = "Brand Fit",
                        description = "Brand fit",
                        weight = 2.0
                    )
                )
            )
        )

        assertTrue(dimensions.any { it.id == "brand_fit" && it.weight == 2.0 })
        assertTrue(dimensions.any { it.id == ScoringDimensionCatalog.USER_RELEVANCE })
    }

    private fun config(
        objective: String,
        finalOutputSpec: FinalOutputSpec? = null,
        scoringDimensions: List<ScoringDimension> = listOf(
            ScoringDimension(
                id = ScoringDimensionCatalog.CORRECTNESS,
                name = "Correctness",
                description = "Correctness",
                weight = 1.0
            )
        )
    ): RunConfig = RunConfig(
        runId = "policy-test",
        objective = objective,
        groupCount = 1,
        groupProfiles = listOf(GroupProfileType.Library),
        scoringDimensions = scoringDimensions,
        openRouterApiKey = "test-key",
        llmBackend = LlmBackend.OPENROUTER,
        finalOutputSpec = finalOutputSpec,
        isTestRun = true
    )
}
