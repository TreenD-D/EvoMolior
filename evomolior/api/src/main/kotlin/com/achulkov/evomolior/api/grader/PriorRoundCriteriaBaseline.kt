package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.core.contract.GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyWireDraft
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.serialization.json.Json

/**
 * A grader civilisation's accepted output from earlier rounds, used as this round's baseline.
 *
 * Criteria agents previously received only the founding brief, lifecycle notes, and telemetry, so
 * every round re-researched every dimension from scratch. The baseline carries the newest
 * Critic-promoted typed methodology and criteria documents forward so agents revise them.
 *
 * @param methodologyGeneration Generation of [methodology], or null when none was promoted.
 * @param methodology Newest promoted typed methodology with content.
 * @param documentsGeneration Generation of [criteriaDocuments], or null when none were promoted.
 * @param criteriaDocuments Promoted prose criteria from that generation, oldest first.
 */
internal data class PriorRoundCriteriaBaseline(
    val methodologyGeneration: Int?,
    val methodology: GraderMethodologyDraft?,
    val documentsGeneration: Int?,
    val criteriaDocuments: List<StagingSubmissionEntity>,
) {
    /**
     * Renders the baseline for criteria-agent prompts.
     *
     * @param boundMethodology [methodology] projected onto this round's handle matrix, when the
     *   leased frontier still covers it; it is shown verbatim so builders can edit it in place.
     * @param redact Removes exact Atlas/lease authority from model-facing prose.
     * @param maxChars Upper bound on the rendered text.
     */
    fun render(
        boundMethodology: GraderMethodologyWireDraft?,
        redact: (String) -> String = { it },
        maxChars: Int = BASELINE_RENDER_MAX_CHARS,
    ): String {
        val methodologyText = when {
            boundMethodology != null -> buildString {
                appendLine(
                    "Typed methodology from generation $methodologyGeneration, bound to this " +
                        "round's handle matrix (revise it in place):"
                )
                append(json.encodeToString(GraderMethodologyWireDraft.serializer(), boundMethodology))
            }
            methodology != null -> buildString {
                appendLine(
                    "Typed methodology from generation $methodologyGeneration (this round's handle " +
                        "matrix changed; keep the semantics and bind only current handles):"
                )
                methodology.rubricDimensions.forEach {
                    appendLine("- rubric [${it.dimensionId}]: ${redact(it.scoringGuidance).compact(320)}")
                }
                methodology.requirements.forEach {
                    appendLine(
                        "- requirement ${it.requirementId}: ${redact(it.criterion).compact(260)} " +
                            "| pass: ${redact(it.passThreshold).compact(160)}"
                    )
                }
                methodology.automatedTests.forEach {
                    appendLine(
                        "- test ${it.testId}${if (it.isMandatory) " (mandatory)" else ""}: " +
                            "${redact(it.description).compact(200)} /${it.expectedOutputPattern.compact(160)}/"
                    )
                }
                methodology.failureModes.forEach {
                    appendLine("- failure mode ${it.modeId}: ${redact(it.description).compact(200)}")
                }
            }.trimEnd()
            else -> null
        }
        val header = buildString {
            appendLine(
                "These criteria passed Critic review and graded earlier rounds. Improve them; do not " +
                    "start over."
            )
            methodologyText?.let { appendLine(it.take(maxChars * 2 / 3)) }
        }
        val documentBudget = (maxChars - header.length).coerceAtLeast(0)
        val documents = buildString {
            if (criteriaDocuments.isEmpty() || documentBudget < 200) return@buildString
            appendLine("Accepted criteria documents from generation $documentsGeneration:")
            val perDocument = (documentBudget / criteriaDocuments.size).coerceAtLeast(200)
            criteriaDocuments.forEach { entry ->
                appendLine("- [${entry.role}] ${redact(entry.content).compact(perDocument)}")
            }
        }
        return (header + documents).take(maxChars).trimEnd()
    }

    companion object {
        /** Bound on the rendered baseline; the prompt applies its own cap as well. */
        const val BASELINE_RENDER_MAX_CHARS = 9_000

        private val json = Json { ignoreUnknownKeys = true }
        private val criteriaRoles = setOf(Role.GraderResearcher.name, Role.GraderBuilder.name)

        /**
         * Loads the newest accepted criteria promoted before [roundIndex], or null in round 0 or
         * when no earlier round promoted any criteria.
         *
         * @param db Isolated grader substrate.
         * @param runId Run identifier.
         * @param graderGroupId Grader civilisation.
         * @param roundIndex Round whose criteria are being developed (generation `roundIndex + 1`).
         */
        suspend fun load(
            db: EvoMoliorDatabase,
            runId: String,
            graderGroupId: String,
            roundIndex: Int,
        ): PriorRoundCriteriaBaseline? {
            if (roundIndex <= 0) return null
            val promoted = db.stagingDao().getAll(runId, graderGroupId).filter {
                it.type == "PLAN_UPDATE" && it.status == "promoted" && it.generation in 1..roundIndex
            }
            val methodologyEntry = promoted.asSequence()
                .filter { it.budgetSignature.startsWith(GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX) }
                .sortedWith(compareByDescending<StagingSubmissionEntity> { it.generation }
                    .thenByDescending { it.submittedAtMs })
                .mapNotNull { entry ->
                    runCatching { json.decodeFromString(GraderMethodologyDraft.serializer(), entry.content) }
                        .getOrNull()
                        ?.takeIf { it.requirements.isNotEmpty() || it.rubricDimensions.isNotEmpty() }
                        ?.let { entry.generation to it }
                }
                .firstOrNull()
            val documents = promoted.filter {
                it.role in criteriaRoles && !it.budgetSignature.startsWith(GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX)
            }
            val documentsGeneration = documents.maxOfOrNull { it.generation }
            val latestDocuments = documents
                .filter { it.generation == documentsGeneration }
                .sortedBy { it.submittedAtMs }
            if (methodologyEntry == null && latestDocuments.isEmpty()) return null
            return PriorRoundCriteriaBaseline(
                methodologyGeneration = methodologyEntry?.first,
                methodology = methodologyEntry?.second,
                documentsGeneration = documentsGeneration,
                criteriaDocuments = latestDocuments,
            )
        }

        private fun String.compact(maxChars: Int): String {
            val singleLine = replace(Regex("\\s+"), " ").trim()
            return if (singleLine.length <= maxChars) singleLine else singleLine.take(maxChars - 1) + "…"
        }
    }
}
