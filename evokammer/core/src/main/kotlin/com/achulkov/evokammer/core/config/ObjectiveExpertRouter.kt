package com.achulkov.evokammer.core.config

import com.achulkov.evokammer.core.model.Role

/**
 * Rule-based MoE baseline router for choosing a small expert bundle from the run objective.
 *
 * The router is intentionally cheap and deterministic: it maps objective keywords to one of a
 * few canonical expert profiles, then returns the per-role model assignments and pools for that
 * profile.
 */
object ObjectiveExpertRouter {
    data class ExpertBundle(
        val name: String,
        val modelAssignment: Map<String, String>,
        val modelPools: Map<String, List<String>>
    )

    /**
     * Selects a small expert bundle based on the objective text.
     *
     * @param objective The run objective to classify.
     * @return A deterministic expert bundle for that objective.
     */
    fun select(objective: String): ExpertBundle {
        val objectiveTokens = tokenize(objective)
        return when {
            objectiveTokens.any { it in COORDINATION_HINTS } -> coordinationHeavy()
            objectiveTokens.any { it in PERSPECTIVE_HINTS } -> perspectiveHeavy()
            objectiveTokens.any { it in RESEARCH_HINTS } -> researchHeavy()
            objectiveTokens.any { it in BUILD_HINTS } -> buildHeavy()
            objectiveTokens.any { it in SYNTHESIS_HINTS } -> synthesisHeavy()
            else -> balancedBaseline()
        }
    }

    private fun researchHeavy(): ExpertBundle = ExpertBundle(
        name = "research-heavy",
        modelAssignment = linkedMapOf(
            Role.Researcher.name to ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL,
            Role.Archivist.name to ModelSelectionConfig.DEFAULT_RUNTIME_ARCHIVIST_MODEL,
            Role.Educator.name to ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL,
            Role.Connector.name to ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
            Role.Planner.name to ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
            Role.Builder.name to ModelSelectionConfig.DEFAULT_RUNTIME_BUILDER_MODEL,
            Role.Critic.name to ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL,
            Role.Auditor.name to ModelSelectionConfig.DEFAULT_RUNTIME_AUDITOR_MODEL,
            Role.Outlier.name to ModelSelectionConfig.DEFAULT_RUNTIME_OUTLIER_MODEL,
            Role.MetaPlanner.name to ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL
        ),
        modelPools = linkedMapOf(
            Role.Researcher.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL
            ),
            Role.Archivist.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_ARCHIVIST_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL
            ),
            Role.Educator.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL
            ),
            Role.Connector.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL
            )
        )
    )

    private fun buildHeavy(): ExpertBundle = ExpertBundle(
        name = "build-heavy",
        modelAssignment = linkedMapOf(
            Role.Builder.name to ModelSelectionConfig.DEFAULT_RUNTIME_BUILDER_MODEL,
            Role.Planner.name to ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
            Role.Researcher.name to ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL,
            Role.Archivist.name to ModelSelectionConfig.DEFAULT_RUNTIME_ARCHIVIST_MODEL,
            Role.Connector.name to ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
            Role.Critic.name to ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL,
            Role.Auditor.name to ModelSelectionConfig.DEFAULT_RUNTIME_AUDITOR_MODEL,
            Role.Outlier.name to ModelSelectionConfig.DEFAULT_RUNTIME_OUTLIER_MODEL,
            Role.MetaPlanner.name to ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL
        ),
        modelPools = linkedMapOf(
            Role.Builder.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_BUILDER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL
            ),
            Role.Planner.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL
            ),
            Role.Connector.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL
            )
        )
    )

    private fun synthesisHeavy(): ExpertBundle = ExpertBundle(
        name = "synthesis-heavy",
        modelAssignment = linkedMapOf(
            Role.Planner.name to ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
            Role.Connector.name to ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
            Role.MetaPlanner.name to ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL,
            Role.Researcher.name to ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL,
            Role.Builder.name to ModelSelectionConfig.DEFAULT_RUNTIME_BUILDER_MODEL,
            Role.Archivist.name to ModelSelectionConfig.DEFAULT_RUNTIME_ARCHIVIST_MODEL,
            Role.Critic.name to ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL,
            Role.Auditor.name to ModelSelectionConfig.DEFAULT_RUNTIME_AUDITOR_MODEL,
            Role.Outlier.name to ModelSelectionConfig.DEFAULT_RUNTIME_OUTLIER_MODEL
        ),
        modelPools = linkedMapOf(
            Role.Planner.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL
            ),
            Role.Connector.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL
            ),
            Role.MetaPlanner.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL
            )
        )
    )

    private fun coordinationHeavy(): ExpertBundle = ExpertBundle(
        name = "coordination-heavy",
        modelAssignment = linkedMapOf(
            Role.MetaPlanner.name to ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL,
            Role.Planner.name to ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
            Role.Connector.name to ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
            Role.Archivist.name to ModelSelectionConfig.DEFAULT_RUNTIME_ARCHIVIST_MODEL,
            Role.Researcher.name to ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL,
            Role.Builder.name to ModelSelectionConfig.DEFAULT_RUNTIME_BUILDER_MODEL,
            Role.Critic.name to ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL,
            Role.Auditor.name to ModelSelectionConfig.DEFAULT_RUNTIME_AUDITOR_MODEL,
            Role.Outlier.name to ModelSelectionConfig.DEFAULT_RUNTIME_OUTLIER_MODEL
        ),
        modelPools = linkedMapOf(
            Role.MetaPlanner.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL
            ),
            Role.Planner.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL
            ),
            Role.Connector.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL
            )
        )
    )

    private fun perspectiveHeavy(): ExpertBundle = ExpertBundle(
        name = "perspective-heavy",
        modelAssignment = linkedMapOf(
            Role.PerspectiveTaker.name to ModelSelectionConfig.DEFAULT_RUNTIME_PERSPECTIVE_TAKER_MODEL,
            Role.Critic.name to ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL,
            Role.Inspector.name to ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL,
            Role.Auditor.name to ModelSelectionConfig.DEFAULT_RUNTIME_AUDITOR_MODEL,
            Role.Educator.name to ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL,
            Role.Planner.name to ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
            Role.Connector.name to ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
            Role.Researcher.name to ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL,
            Role.Builder.name to ModelSelectionConfig.DEFAULT_RUNTIME_BUILDER_MODEL
        ),
        modelPools = linkedMapOf(
            Role.PerspectiveTaker.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_PERSPECTIVE_TAKER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL
            ),
            Role.Critic.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL
            ),
            Role.Educator.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL
            ),
            Role.Connector.name to listOf(
                ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL,
                ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL
            )
        )
    )

    private fun balancedBaseline(): ExpertBundle = ExpertBundle(
        name = "balanced-baseline",
        modelAssignment = ModelSelectionConfig.defaultRuntimeAssignments(),
        modelPools = ModelSelectionConfig.defaultRuntimeModelPools()
    )

    private fun tokenize(text: String): Set<String> = text
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 4 }
        .toSet()

    private val RESEARCH_HINTS = setOf("research", "survey", "literature", "evidence", "analyze", "analysis")
    private val BUILD_HINTS = setOf("build", "implement", "tool", "code", "patch", "refactor", "ship")
    private val SYNTHESIS_HINTS = setOf("synthes", "plan", "strategy", "roadmap", "design", "orchestrate")
    private val COORDINATION_HINTS = setOf("coordinate", "coordination", "synchronize", "orchestrate", "integration", "manage")
    private val PERSPECTIVE_HINTS = setOf(
        "perspective",
        "stakeholder",
        "blind",
        "rubric",
        "evaluate",
        "evaluation",
        "assess",
        "assessment",
        "critique",
        "verify",
        "verification",
        "audit",
        "judging",
        "judge"
    )
}
