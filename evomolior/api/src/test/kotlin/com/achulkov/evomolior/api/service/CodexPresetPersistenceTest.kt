package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.CodexRuntimePreset
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodexPresetPersistenceTest {
    @Test
    fun `resume retains preset and effort ceiling and rejects a changed policy`() {
        val config = RunConfig(
            runId = "codex-preset-persistence",
            objective = "Produce the requested artifact.",
            groupCount = 2,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            scoringDimensions = listOf(ScoringDimension("quality", "Quality", "Quality", 1.0)),
            openRouterApiKey = "provider-free",
            llmBackend = LlmBackend.CODEX_CHATGPT,
            codexRuntimePreset = CodexRuntimePreset.MIXED_FAMILIES,
            codexReasoningEffort = CodexReasoningEffort.MEDIUM,
            budgetOverride = 100.0,
        )
        val persisted = PersistedRunState.fromRunConfig(
            config,
            groupIds = listOf("group-0", "group-1"),
            graderGroupIds = emptyList(),
            allowedHosts = emptySet(),
        )
        val decoded = Json.decodeFromString<PersistedRunState>(Json.encodeToString(persisted))
        val resumed = decoded.toRunConfig("replacement-credential")
        decoded.requireCompatibleRecoveryIntent(resumed)
        assertEquals(config.codexRuntimePreset, resumed.codexRuntimePreset)
        assertEquals(config.codexReasoningEffort, resumed.codexReasoningEffort)
        assertEquals(config.budgetOverride, resumed.budgetOverride)
        assertFailsWith<IllegalArgumentException> {
            decoded.requireCompatibleRecoveryIntent(resumed.copy(codexRuntimePreset = CodexRuntimePreset.FULL_LUNA))
        }
    }
}
