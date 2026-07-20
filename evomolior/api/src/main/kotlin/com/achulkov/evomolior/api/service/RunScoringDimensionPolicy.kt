package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.ScoringDimensionCatalog
import io.github.aakira.napier.Napier

/**
 * Selects enabled scoring dimensions for a run from its expected deliverable profile.
 */
internal object RunScoringDimensionPolicy {
    private val canonicalIds = ScoringDimensionCatalog.all.map(ScoringDimension::id).toSet()

    /**
     * Selects canonical and bespoke scoring dimensions for [config].
     *
     * @param config Requested run configuration.
     * @return Dimensions enabled for this specific run.
     */
    fun select(config: RunConfig): List<ScoringDimension> {
        val profile = ExpectedDeliverableProfile.from(config)
        val requestedById = config.scoringDimensions.associateBy { it.id }
        val canonical = ScoringDimensionCatalog.all
            .filter { it.id in profile.enabledDimensionIds }
            .map { dimension ->
                val requested = requestedById[dimension.id]
                val weighted = dimension.copy(weight = profile.weightFor(dimension.id))
                if (requested == null) {
                    weighted
                } else {
                    weighted.copy(
                        name = requested.name,
                        description = requested.description,
                        maxScore = requested.maxScore,
                        dstNodeRef = requested.dstNodeRef
                    )
                }
            }
        val bespoke = config.scoringDimensions.filter { it.id !in canonicalIds }
        val selected = canonical + bespoke
        Napier.i(
            "RunScoringDimensionPolicy: run=${config.runId} profile=${profile.kind} enabled=" +
                selected.joinToString(", ") { "${it.id}:${it.weight}" }
        )
        return selected
    }
}

private data class ExpectedDeliverableProfile(
    val kind: ExpectedDeliverableKind,
    val enabledDimensionIds: Set<String>,
    val weights: Map<String, Double>
) {
    fun weightFor(dimensionId: String): Double = weights[dimensionId] ?: 1.0

    companion object {
        fun from(config: RunConfig): ExpectedDeliverableProfile {
            val objective = config.objective.lowercase()
            val artifactText = config.contextArtifacts.joinToString(" ") { artifact ->
                listOf(artifact.relativePath, artifact.sourceName, artifact.mimeType, artifact.textPreview.orEmpty())
                    .joinToString(" ")
            }.lowercase()
            val evidenceExpected = listOf(
                "research", "cite", "citation", "source", "evidence", "article", "report", "analysis", "benchmark"
            ).any { it in objective || it in artifactText } || config.finalOutputSpec?.kind == FinalOutputKind.HTML_ARTICLE
            val implementationExpected = listOf(
                "implement", "code", "patch", "build", "fix", "refactor", "api", "test", "architecture"
            ).any { it in objective || it in artifactText } || config.finalOutputSpec?.kind == FinalOutputKind.PATCH_BUNDLE
            val exploratoryExpected = listOf(
                "novel", "creative", "brainstorm", "strategy", "design", "ideate", "proposal"
            ).any { it in objective }

            return when {
                implementationExpected -> implementation(evidenceExpected)
                evidenceExpected -> evidence()
                exploratoryExpected -> strategy()
                else -> generic()
            }
        }

        private fun implementation(evidenceExpected: Boolean): ExpectedDeliverableProfile =
            ExpectedDeliverableProfile(
                kind = ExpectedDeliverableKind.IMPLEMENTATION,
                enabledDimensionIds = buildSet {
                    addBaseline()
                    add(ScoringDimensionCatalog.ROBUSTNESS)
                    add(ScoringDimensionCatalog.IMPLEMENTATION_FEASIBILITY)
                    if (evidenceExpected) add(ScoringDimensionCatalog.EVIDENCE_QUALITY)
                },
                weights = mapOf(
                    ScoringDimensionCatalog.CORRECTNESS to 1.35,
                    ScoringDimensionCatalog.ROBUSTNESS to 1.20,
                    ScoringDimensionCatalog.IMPLEMENTATION_FEASIBILITY to 1.15,
                    ScoringDimensionCatalog.SECURITY_RISK to 1.10,
                    ScoringDimensionCatalog.USER_RELEVANCE to 1.00,
                    ScoringDimensionCatalog.COST_EFFICIENCY to 0.85,
                    ScoringDimensionCatalog.TIME_TO_VALUE to 0.80,
                    ScoringDimensionCatalog.EVIDENCE_QUALITY to 0.80
                )
            )

        private fun evidence(): ExpectedDeliverableProfile =
            ExpectedDeliverableProfile(
                kind = ExpectedDeliverableKind.EVIDENCE_SYNTHESIS,
                enabledDimensionIds = buildSet {
                    addBaseline()
                    add(ScoringDimensionCatalog.EVIDENCE_QUALITY)
                    add(ScoringDimensionCatalog.NOVELTY)
                },
                weights = mapOf(
                    ScoringDimensionCatalog.EVIDENCE_QUALITY to 1.40,
                    ScoringDimensionCatalog.CORRECTNESS to 1.25,
                    ScoringDimensionCatalog.USER_RELEVANCE to 1.10,
                    ScoringDimensionCatalog.NOVELTY to 0.85,
                    ScoringDimensionCatalog.TIME_TO_VALUE to 0.75,
                    ScoringDimensionCatalog.COST_EFFICIENCY to 0.70,
                    ScoringDimensionCatalog.SECURITY_RISK to 0.60
                )
            )

        private fun strategy(): ExpectedDeliverableProfile =
            ExpectedDeliverableProfile(
                kind = ExpectedDeliverableKind.STRATEGY,
                enabledDimensionIds = buildSet {
                    addBaseline()
                    add(ScoringDimensionCatalog.NOVELTY)
                    add(ScoringDimensionCatalog.IMPLEMENTATION_FEASIBILITY)
                },
                weights = mapOf(
                    ScoringDimensionCatalog.USER_RELEVANCE to 1.25,
                    ScoringDimensionCatalog.NOVELTY to 1.20,
                    ScoringDimensionCatalog.IMPLEMENTATION_FEASIBILITY to 1.05,
                    ScoringDimensionCatalog.CORRECTNESS to 1.00,
                    ScoringDimensionCatalog.TIME_TO_VALUE to 0.95,
                    ScoringDimensionCatalog.COST_EFFICIENCY to 0.80,
                    ScoringDimensionCatalog.SECURITY_RISK to 0.70
                )
            )

        private fun generic(): ExpectedDeliverableProfile =
            ExpectedDeliverableProfile(
                kind = ExpectedDeliverableKind.GENERIC,
                enabledDimensionIds = buildSet {
                    addBaseline()
                    add(ScoringDimensionCatalog.NOVELTY)
                },
                weights = mapOf(
                    ScoringDimensionCatalog.CORRECTNESS to 1.20,
                    ScoringDimensionCatalog.USER_RELEVANCE to 1.20,
                    ScoringDimensionCatalog.TIME_TO_VALUE to 0.90,
                    ScoringDimensionCatalog.NOVELTY to 0.85,
                    ScoringDimensionCatalog.COST_EFFICIENCY to 0.80,
                    ScoringDimensionCatalog.SECURITY_RISK to 0.80
                )
            )

        private fun MutableSet<String>.addBaseline() {
            add(ScoringDimensionCatalog.CORRECTNESS)
            add(ScoringDimensionCatalog.COST_EFFICIENCY)
            add(ScoringDimensionCatalog.SECURITY_RISK)
            add(ScoringDimensionCatalog.TIME_TO_VALUE)
            add(ScoringDimensionCatalog.USER_RELEVANCE)
        }
    }
}

private enum class ExpectedDeliverableKind {
    IMPLEMENTATION,
    EVIDENCE_SYNTHESIS,
    STRATEGY,
    GENERIC
}
