package com.achulkov.evokammer.agents.factory

import com.achulkov.evokammer.core.model.Role
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that the Genesis Council synthesis prompt still asks for the sealed suite
 * fields required by the run bootstrap path.
 */
class CouncilRolePromptsTest {

    @Test
    fun `Council synthesis prompt requires sealed test suite fields`() {
        val prompt = CouncilRolePrompts.forRole(Role.CouncilSynthesis)
        assertTrue(prompt.contains("sealedTestSuiteJson"), "Synthesis prompt must request the sealed test suite JSON")
        assertTrue(prompt.contains("sealedTestSuiteHash"), "Synthesis prompt must request the sealed test suite hash")
        assertTrue(prompt.contains("modelPools"), "Synthesis prompt must request model pools for per-agent sampling")
        assertTrue(
            prompt.contains("expectedOutputPattern"),
            "Synthesis prompt must show the sealed suite test shape that the evaluator expects"
        )
        assertTrue(
            prompt.contains("failureModes"),
            "Synthesis prompt must show that sealed suites can include failure modes"
        )
    }

    @Test
    fun `Council model ecologist prompt advertises DeepSeek pool members`() {
        val prompt = CouncilRolePrompts.forRole(Role.CouncilModelEcologist)
        assertTrue(prompt.contains("modelPools"), "Model Ecologist prompt must request model pools")
        assertTrue(prompt.contains("deepseek/deepseek-v3.2"), "Model Ecologist prompt must mention the DeepSeek pool")
        assertTrue(prompt.contains("deepseek/deepseek-v4-flash"), "Model Ecologist prompt must mention the DeepSeek pool")
        assertTrue(prompt.contains("deepseek/deepseek-v4-pro"), "Model Ecologist prompt must mention the DeepSeek pool")
    }
}
