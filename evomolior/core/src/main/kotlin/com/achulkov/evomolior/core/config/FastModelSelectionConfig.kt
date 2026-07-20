package com.achulkov.evomolior.core.config

import com.achulkov.evomolior.core.model.Role
import kotlin.random.Random

/**
 * Ultra-fast model profile for console runs.
 *
 * The fast profile intentionally constrains every runtime LLM call to a narrow,
 * high-throughput set of OpenRouter models.
 */
object FastModelSelectionConfig {
    const val FAST_QWEN_CLOUD_QWEN_MODEL = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
    const val FAST_QWEN_CLOUD_DEEPSEEK_MODEL = ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL

    const val FAST_COUNCIL_MODEL = "openai/gpt-oss-120b"
    const val FAST_RUNTIME_RESEARCHER_MODEL = "deepseek/deepseek-v4-flash"
    const val FAST_RUNTIME_BUILDER_MODEL = "openai/gpt-oss-120b"
    const val FAST_RUNTIME_PLANNER_MODEL = "deepseek/deepseek-v4-flash"
    const val FAST_RUNTIME_CRITIC_MODEL = "deepseek/deepseek-v4-flash"
    const val FAST_RUNTIME_ARCHIVIST_MODEL = "openai/gpt-oss-120b"
    const val FAST_RUNTIME_EDUCATOR_MODEL = "deepseek/deepseek-v4-flash"
    const val FAST_RUNTIME_CONNECTOR_MODEL = "deepseek/deepseek-v4-flash"
    const val FAST_RUNTIME_OUTLIER_MODEL = "z-ai/glm-4.7-flash"
    const val FAST_RUNTIME_INSPECTOR_MODEL = "deepseek/deepseek-v4-flash"
    const val FAST_RUNTIME_AUDITOR_MODEL = "deepseek/deepseek-v4-flash"
    const val FAST_RUNTIME_PERSPECTIVE_TAKER_MODEL = FAST_RUNTIME_PLANNER_MODEL
    const val FAST_RUNTIME_METAPLANNER_MODEL = "openai/gpt-oss-120b"
    const val FAST_GRADER_MODEL = "deepseek/deepseek-v4-flash"
    const val FAST_CRITIC_PIPELINE_MODEL = "deepseek/deepseek-v4-flash"
    const val FAST_LEAK_TRANSFORM_MODEL = "z-ai/glm-4.7-flash"

    val approvedModelIds: Set<String> = setOf(
        FAST_COUNCIL_MODEL,
        FAST_RUNTIME_RESEARCHER_MODEL,
        FAST_RUNTIME_BUILDER_MODEL,
        FAST_RUNTIME_PLANNER_MODEL,
        FAST_RUNTIME_CRITIC_MODEL,
        FAST_RUNTIME_ARCHIVIST_MODEL,
        FAST_RUNTIME_EDUCATOR_MODEL,
        FAST_RUNTIME_CONNECTOR_MODEL,
        FAST_RUNTIME_OUTLIER_MODEL,
        FAST_RUNTIME_INSPECTOR_MODEL,
        FAST_RUNTIME_AUDITOR_MODEL,
        FAST_RUNTIME_PERSPECTIVE_TAKER_MODEL,
        FAST_RUNTIME_METAPLANNER_MODEL,
        FAST_GRADER_MODEL,
        FAST_CRITIC_PIPELINE_MODEL,
        FAST_LEAK_TRANSFORM_MODEL
    )

    /**
     * Returns the ultra-fast runtime assignment table.
     *
     * @param random Reserved for future deterministic shuffling; currently unused.
     */
    fun fastRuntimeAssignments(random: Random = Random.Default): Map<String, String> = linkedMapOf(
        Role.Researcher.name to FAST_RUNTIME_RESEARCHER_MODEL,
        Role.Builder.name to FAST_RUNTIME_BUILDER_MODEL,
        Role.Planner.name to FAST_RUNTIME_PLANNER_MODEL,
        Role.Critic.name to FAST_RUNTIME_CRITIC_MODEL,
        Role.Archivist.name to FAST_RUNTIME_ARCHIVIST_MODEL,
        Role.Educator.name to FAST_RUNTIME_EDUCATOR_MODEL,
        Role.Connector.name to FAST_RUNTIME_CONNECTOR_MODEL,
        Role.Outlier.name to FAST_RUNTIME_OUTLIER_MODEL,
        Role.Inspector.name to FAST_RUNTIME_INSPECTOR_MODEL,
        Role.Auditor.name to FAST_RUNTIME_AUDITOR_MODEL,
        Role.PerspectiveTaker.name to FAST_RUNTIME_PERSPECTIVE_TAKER_MODEL,
        Role.MetaPlanner.name to FAST_RUNTIME_METAPLANNER_MODEL
    )

    /**
     * Returns the fast Qwen Cloud society assignment table.
     *
     * The profile deliberately mirrors the normal Qwen-backend policy: Qwen Plus is prioritized for
     * the majority of roles, while DashScope DeepSeek Flash remains in the mix for audit-style
     * diversity. Single-agent linear baselines may use only Qwen Plus, but society runs should not
     * silently collapse into a strict single-model profile.
     *
     * @param random Reserved for future deterministic shuffling; currently unused.
     */
    fun fastQwenCloudRuntimeAssignments(random: Random = Random.Default): Map<String, String> = linkedMapOf(
        Role.Researcher.name to FAST_QWEN_CLOUD_QWEN_MODEL,
        Role.Builder.name to FAST_QWEN_CLOUD_QWEN_MODEL,
        Role.Planner.name to FAST_QWEN_CLOUD_QWEN_MODEL,
        Role.Critic.name to FAST_QWEN_CLOUD_QWEN_MODEL,
        Role.Archivist.name to FAST_QWEN_CLOUD_QWEN_MODEL,
        Role.Educator.name to FAST_QWEN_CLOUD_QWEN_MODEL,
        Role.Connector.name to FAST_QWEN_CLOUD_QWEN_MODEL,
        Role.Outlier.name to FAST_QWEN_CLOUD_QWEN_MODEL,
        Role.Inspector.name to FAST_QWEN_CLOUD_QWEN_MODEL,
        Role.Auditor.name to FAST_QWEN_CLOUD_DEEPSEEK_MODEL,
        Role.PerspectiveTaker.name to FAST_QWEN_CLOUD_QWEN_MODEL,
        Role.MetaPlanner.name to FAST_QWEN_CLOUD_QWEN_MODEL
    )
}
