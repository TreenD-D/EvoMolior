package com.achulkov.evokammer.core.contract

import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
import kotlinx.serialization.Serializable

/**
 * The complete configuration produced by the Genesis Council.
 *
 * This is the single source of truth for a run. It is produced once, before any competing
 * group is spawned, and is immutable for the duration of the run.
 *
 * @param runId The run this StartingField initialises.
 * @param objective The overarching problem to solve (human-supplied).
 * @param globalBudgetCeiling Total budget available across all groups for the entire run.
 * @param generationCeiling Maximum number of agent generations to run.
 * @param groupCount Number of competing groups.
 * @param groupProfiles Profile assignment per group (groupId → profile type).
 * @param ringTopology Ring topology of group IDs (ordered list defining the ring).
 * @param checkinMilestones Generation numbers at which check-in gates open.
 * @param leakIntervalGenerations How many generations between automatic leak deliveries.
 * @param dispatchLagGenerations How many generations a dispatch sits in the relay before delivery.
 * @param graderGroupCount Number of Grader Group civilisations.
 * @param rolePrompts Role-specific system prompt templates keyed by [Role].
 * @param modelAssignment LLM model assignment keyed by [Role] (OpenRouter model strings).
 * @param kgSchema JSON-serialised typed KG schema (entities, relations, constraints).
 * @param actionCosts Cost overrides per ActionType (for custom council-designed economics).
 * @param nodeGraph JSON-serialised knowledge node graph (ResourceMapEngine seed).
 * @param primordialKb Seed KB documents to pre-populate each group's KB at genesis.
 * @param primordialKgFacts Seed KG facts to pre-populate each group's KG at genesis.
 * @param primordialTools Seed tools to pre-populate each group's Tool Registry at genesis.
 * @param deathNoteSchema JSON schema for the expected Death Note structure.
 * @param legacyProtocolTemplate JSON template for Legacy Protocol extraction.
 * @param sealedTestSuiteHash SHA-256 hash of the sealed test suite (for integrity verification).
 * @param norms Founding norms and coordination conventions injected into all role prompts.
 * @param stageContents Constraint-framed stage disclosure content per stage index.
 */
@Serializable
data class StartingField(
    val runId: String,
    val objective: String,
    val globalBudgetCeiling: Double,
    val generationCeiling: Int,
    val groupCount: Int,
    val groupProfiles: Map<String, GroupProfileType>,
    val ringTopology: List<String>,
    val checkinMilestones: List<Int>,
    val leakIntervalGenerations: Int,
    val dispatchLagGenerations: Int,
    val graderGroupCount: Int,
    val rolePrompts: Map<String, String>,
    val modelAssignment: Map<String, String>,
    val kgSchema: String,
    val actionCosts: Map<String, Double>,
    val nodeGraph: String,
    val primordialKb: List<SeedKBDocument>,
    val primordialKgFacts: List<SeedKGFact>,
    val primordialTools: List<SeedTool>,
    val deathNoteSchema: String,
    val legacyProtocolTemplate: String,
    val sealedTestSuiteHash: String,
    val norms: String,
    val stageContents: List<String>
) {
    companion object {
        /**
         * Returns a minimal [StartingField] with sensible defaults.
         * Used as a fallback when the Genesis Council output cannot be parsed.
         *
         * @param objective The run objective.
         * @param runId The run ID (default: random UUID placeholder).
         */
        fun default(
            objective: String,
            runId: String = "default-run"
        ): StartingField = StartingField(
            runId = runId,
            objective = objective,
            globalBudgetCeiling = 10_000.0,
            generationCeiling = 100,
            groupCount = 2,
            groupProfiles = mapOf("group-0" to GroupProfileType.Library, "group-1" to GroupProfileType.Factory),
            ringTopology = listOf("group-0", "group-1"),
            checkinMilestones = listOf(10, 25, 50, 75, 100),
            leakIntervalGenerations = 20,
            dispatchLagGenerations = 5,
            graderGroupCount = 1,
            rolePrompts = emptyMap(),
            modelAssignment = emptyMap(),
            kgSchema = "{}",
            actionCosts = emptyMap(),
            nodeGraph = "{}",
            primordialKb = emptyList(),
            primordialKgFacts = emptyList(),
            primordialTools = emptyList(),
            deathNoteSchema = "{}",
            legacyProtocolTemplate = "{}",
            sealedTestSuiteHash = "",
            norms = "",
            stageContents = emptyList()
        )
    }
}

/** A seed KB document for primordial substrate population. */
@Serializable
data class SeedKBDocument(
    val id: String,
    val content: String,
    val domain: String
)

/** A seed KG fact for primordial substrate population. */
@Serializable
data class SeedKGFact(
    val id: String,
    val subject: String,
    val relation: String,
    val `object`: String,
    val confidence: Double,
    val domain: String
)

/** A seed tool for primordial substrate population. */
@Serializable
data class SeedTool(
    val id: String,
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val outputSchemaJson: String,
    val code: String,
    val domain: String
)
