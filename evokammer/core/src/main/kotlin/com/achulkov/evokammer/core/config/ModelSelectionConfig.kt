package com.achulkov.evokammer.core.config

import com.achulkov.evokammer.core.model.Role
import kotlin.random.Random

/**
 * Canonical model-selection policy for EvoKammer.
 *
 * This object is the single source of truth for default model IDs and the set of
 * expensive models that should only appear through explicit opt-in. Runtime callers should read
 * from here instead of scattering hardcoded model strings across factories or services.
 */
object ModelSelectionConfig {
    const val DEFAULT_RUNTIME_RESEARCHER_MODEL = "deepseek/deepseek-v4-flash"
    const val DEFAULT_RUNTIME_BUILDER_MODEL = "deepseek/deepseek-v4-flash"
    const val DEFAULT_RUNTIME_PLANNER_MODEL = "qwen/qwen3-next-80b-a3b-instruct"
    const val DEFAULT_RUNTIME_CRITIC_MODEL = "deepseek/deepseek-v4-flash"
    const val DEFAULT_RUNTIME_ARCHIVIST_MODEL = "perplexity/sonar"
    const val DEFAULT_RUNTIME_OUTLIER_MODEL = "deepseek/deepseek-v4-flash"
    const val DEFAULT_RUNTIME_METAPLANNER_MODEL = "openai/gpt-5-mini"
    const val DEFAULT_RUNTIME_AUDITOR_MODEL = DEFAULT_RUNTIME_CRITIC_MODEL
    const val DEFAULT_RUNTIME_FALLBACK_MODEL = "openai/gpt-oss-20b"

    const val DEFAULT_COUNCIL_MODEL = "x-ai/grok-4.3"
    const val DEFAULT_GRADER_MODEL = "deepseek/deepseek-v4-flash"
    const val DEFAULT_LEAK_TRANSFORM_MODEL = "openai/gpt-oss-20b"
    const val DEFAULT_CRITIC_PIPELINE_MODEL = "deepseek/deepseek-v4-flash"

    private val DEFAULT_RUNTIME_MODEL_POOLS: Map<Role, List<String>> = mapOf(
        Role.Researcher to listOf(
            DEFAULT_RUNTIME_RESEARCHER_MODEL,
            "deepseek/deepseek-v3.2",
            DEFAULT_RUNTIME_FALLBACK_MODEL
        ),
        Role.Builder to listOf(
            DEFAULT_RUNTIME_BUILDER_MODEL,
            DEFAULT_RUNTIME_FALLBACK_MODEL,
            "deepseek/deepseek-v3.2"
        ),
        Role.Planner to listOf(
            DEFAULT_RUNTIME_PLANNER_MODEL,
            "deepseek/deepseek-v3.2",
            "deepseek/deepseek-v4-flash"
        ),
        Role.Critic to listOf(
            DEFAULT_RUNTIME_CRITIC_MODEL,
            "deepseek/deepseek-v4-flash",
            DEFAULT_RUNTIME_FALLBACK_MODEL
        ),
        Role.Archivist to listOf(
            DEFAULT_RUNTIME_ARCHIVIST_MODEL,
            DEFAULT_RUNTIME_FALLBACK_MODEL,
            "deepseek/deepseek-v4-flash"
        ),
        Role.Educator to listOf(
            DEFAULT_RUNTIME_RESEARCHER_MODEL,
            "deepseek/deepseek-v4-flash",
            DEFAULT_RUNTIME_FALLBACK_MODEL
        ),
        Role.Connector to listOf(
            DEFAULT_RUNTIME_PLANNER_MODEL,
            "deepseek/deepseek-v3.2",
            DEFAULT_RUNTIME_FALLBACK_MODEL
        ),
        Role.Outlier to listOf(
            DEFAULT_RUNTIME_OUTLIER_MODEL,
            "deepseek/deepseek-v4-flash",
            DEFAULT_RUNTIME_FALLBACK_MODEL
        ),
        Role.Inspector to listOf(
            DEFAULT_RUNTIME_CRITIC_MODEL,
            "deepseek/deepseek-v4-flash",
            DEFAULT_RUNTIME_FALLBACK_MODEL
        ),
        Role.Auditor to listOf(
            DEFAULT_RUNTIME_AUDITOR_MODEL,
            "deepseek/deepseek-v4-flash",
            DEFAULT_RUNTIME_FALLBACK_MODEL
        ),
        Role.MetaPlanner to listOf(
            DEFAULT_RUNTIME_METAPLANNER_MODEL,
            "deepseek/deepseek-v4-flash",
            DEFAULT_RUNTIME_PLANNER_MODEL
        )
    )

    /**
     * Models that are allowed only as explicit opt-ins.
     *
     * These are intentionally not used as defaults anywhere in the runtime. If a role needs one
     * of these models, the selection must be made in the canonical config and reviewed there.
     */
    val explicitOptInModelIds: Set<String> = setOf(
        "anthropic/claude-sonnet-4",
        "anthropic/claude-sonnet-4.5",
        "anthropic/claude-opus-4.5",
        "anthropic/claude-opus-4.6",
        "google/gemini-2.5-pro",
        "openai/gpt-5",
        "openai/gpt-5.2",
        "openai/gpt-5.2-pro"
    )

    /**
     * Returns the approved default model ID for a runtime role.
     *
     * @param role The role that is being spawned.
     * @return The default model ID to use for that role.
     */
    fun defaultModelFor(role: Role, random: Random = Random.Default): String =
        runtimeModelPoolFor(role).random(random)

    /**
     * Returns the approved candidate pool for a runtime role.
     *
     * @param role The role that is being spawned.
     * @return Ordered list of candidate model IDs for that role.
     */
    fun runtimeModelPoolFor(role: Role): List<String> = DEFAULT_RUNTIME_MODEL_POOLS[role]
        ?: listOf(DEFAULT_RUNTIME_FALLBACK_MODEL)

    /**
     * Returns the default role-to-model pool table for ordinary runtime groups.
     *
     * @return A map keyed by role name, suitable for `StartingField.modelPools` and
     *   `LifecycleManager.spawnGeneration(...)`.
     */
    fun defaultRuntimeModelPools(): Map<String, List<String>> = DEFAULT_RUNTIME_MODEL_POOLS
        .mapKeys { (role, _) -> role.name }
        .mapValues { (_, pool) -> pool.toList() }

    /**
     * Returns the default role-to-model assignment table for ordinary runtime groups.
     *
     * @return A map keyed by role name, suitable for `StartingField.modelAssignment` and
     *   `LifecycleManager.spawnGeneration(...)`.
     */
    fun defaultRuntimeAssignments(random: Random = Random.Default): Map<String, String> = linkedMapOf(
        Role.Planner.name to defaultModelFor(Role.Planner, random),
        Role.Critic.name to defaultModelFor(Role.Critic, random),
        Role.Auditor.name to defaultModelFor(Role.Auditor, random),
        Role.Researcher.name to defaultModelFor(Role.Researcher, random),
        Role.Builder.name to defaultModelFor(Role.Builder, random),
        Role.Archivist.name to defaultModelFor(Role.Archivist, random),
        Role.Outlier.name to defaultModelFor(Role.Outlier, random),
        Role.MetaPlanner.name to defaultModelFor(Role.MetaPlanner, random)
    )

    /**
     * Returns whether a model is considered expensive enough to require explicit opt-in.
     *
     * @param modelId OpenRouter model ID.
     * @return `true` if the model should not be used as a silent default.
     */
    fun isExplicitOptInModel(modelId: String): Boolean = modelId in explicitOptInModelIds
}
