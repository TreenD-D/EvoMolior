package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.RunInputArtifactUpload
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceGroundingPreflightTest {
    private fun config(
        objective: String,
        backend: LlmBackend = LlmBackend.OPENROUTER,
        artifacts: List<RunInputArtifactUpload> = emptyList()
    ) = RunConfig(
        runId = "source-preflight",
        objective = objective,
        groupCount = 2,
        groupProfiles = List(2) { GroupProfileType.Library },
        rounds = 1,
        roundDurationMinutes = 1,
        maxAgentsPerGroup = 1,
        scoringDimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Evidence fidelity.",
                weight = 1.0
            )
        ),
        openRouterApiKey = "test",
        llmBackend = backend,
        contextArtifacts = artifacts
    )

    @Test
    fun `source-sensitive objective fails without supplied evidence or discovery`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            requireSourceGroundingPath(
                config("Produce current market intelligence with competitor pricing and public sources.")
            )
        }

        assertTrue(failure.message.orEmpty().contains("No source path is available"))
    }

    @Test
    fun `codex native discovery satisfies source-sensitive preflight`() {
        requireSourceGroundingPath(
            config(
                objective = "Produce current market intelligence with competitor pricing.",
                backend = LlmBackend.CODEX_CHATGPT
            )
        )
    }

    @Test
    fun `supplied evidence satisfies source-sensitive preflight`() {
        requireSourceGroundingPath(
            config(
                objective = "Assess the latest competitor pricing from the supplied evidence.",
                artifacts = listOf(
                    RunInputArtifactUpload(
                        relativePath = "sources/pricing.csv",
                        sourceName = "pricing.csv",
                        mimeType = "text/csv",
                        contentBase64 = "Y29tcGFueSxwcmljZQ==",
                        textPreview = "company,price"
                    )
                )
            )
        )
    }

    @Test
    fun `offline synthesis does not require a discovery path`() {
        requireSourceGroundingPath(
            config("Summarize the supplied conceptual framework into a decision memo.")
        )
    }
}
