package com.achulkov.evokammer.competition

import com.achulkov.evokammer.agents.factory.CouncilRunner
import com.achulkov.evokammer.competition.leaks.DifferentialPrivacyTransform
import com.achulkov.evokammer.core.config.ModelSelectionConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelSelectionConfigRuntimeDefaultsTest {
    @Test
    fun `runtime constructors use shared model defaults`() {
        val councilRunner = CouncilRunner(openRouterApiKey = "test-api-key")
        assertEquals(
            ModelSelectionConfig.DEFAULT_COUNCIL_MODEL,
            councilRunner.readPrivateString("councilModelId")
        )

        val leakTransform = DifferentialPrivacyTransform(openRouterApiKey = "test-api-key")
        assertEquals(
            ModelSelectionConfig.DEFAULT_LEAK_TRANSFORM_MODEL,
            leakTransform.readPrivateString("modelId")
        )

        assertEquals(
            "deepseek/deepseek-v4-flash",
            ModelSelectionConfig.DEFAULT_CRITIC_PIPELINE_MODEL
        )
    }

    private fun Any.readPrivateString(fieldName: String): String {
        val field = javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(this) as String
    }
}
