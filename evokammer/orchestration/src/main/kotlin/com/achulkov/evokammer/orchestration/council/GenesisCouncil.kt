package com.achulkov.evokammer.orchestration.council

import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.Role
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json

/**
 * Drives the Genesis Council — a one-time multi-agent session that designs the run.
 *
 * The Genesis Council consists of 8 specialised agents ([Role.isCouncilRole]):
 * - [Role.CouncilTaskAnatomist] — decomposes the objective into knowledge nodes + schedule
 * - [Role.CouncilIncentiveDesigner] — designs costs, scoring, and resource yields
 * - [Role.CouncilEcologist] — designs group count, profiles, ring topology
 * - [Role.CouncilPromptArchitect] — generates role system prompts and Death Note schema
 * - [Role.CouncilKGArchitect] — defines the KG schema (entity/relation types)
 * - [Role.CouncilModelEcologist] — assigns LLM models to roles
 * - [Role.CouncilSeedCurator] — assembles the primordial KB + KG seed
 * - [Role.CouncilSynthesis] — merges all outputs, consistency check, produces [StartingField]
 *
 * Each agent runs sequentially (output of one feeds into the next).
 * The final output is a [StartingField] that is persisted and used to bootstrap all groups.
 *
 * This class is a coordination shell — the actual agents are created by [EvoKammerAgentFactory].
 *
 * @param objective The natural-language run objective.
 * @param openRouterApiKey API key for the LLM executor.
 */
class GenesisCouncil(
    private val objective: String,
    private val openRouterApiKey: String
) {

    /** Returns the ordered list of council roles to run. */
    val councilRoles: List<Role> = listOf(
        Role.CouncilTaskAnatomist,
        Role.CouncilIncentiveDesigner,
        Role.CouncilEcologist,
        Role.CouncilPromptArchitect,
        Role.CouncilKGArchitect,
        Role.CouncilModelEcologist,
        Role.CouncilSeedCurator,
        Role.CouncilSynthesis
    )

    /**
     * Runs the Genesis Council sequentially.
     *
     * Each agent's output is serialised to JSON and passed as context to the next agent.
     * The [StartingField] is returned on successful completion.
     *
     * The actual Koog agent execution is delegated to the [agentRunner] callback, which
     * allows the caller (LifecycleManager) to inject the configured agent factory.
     *
     * @param agentRunner Suspend function that takes (role, contextJson) and returns the agent's output.
     * @return The assembled [StartingField].
     */
    suspend fun run(
        agentRunner: suspend (role: Role, contextJson: String) -> String
    ): StartingField {
        Napier.i("GenesisCouncil: starting for objective: ${objective.take(80)}...")

        var context = """{"objective": "${objective.replace("\"", "\\\"")}"}"""
        val outputs = mutableListOf<Pair<Role, String>>()

        for (role in councilRoles) {
            Napier.i("GenesisCouncil: running ${role.name}")
            val output = agentRunner(role, context)
            outputs.add(role to output)
            // Append this agent's output to context for the next agent
            context = buildContext(outputs)
            Napier.i("GenesisCouncil: ${role.name} completed (output length=${output.length})")
        }

        val synthesisOutput = outputs.last { it.first == Role.CouncilSynthesis }.second
        return parseStartingField(synthesisOutput, objective)
    }

    private fun buildContext(outputs: List<Pair<Role, String>>): String {
        val parts = outputs.joinToString(",\n") { (role, output) ->
            """"${role.name}": ${output.ifBlank { "\"\"" }}"""
        }
        return "{\n$parts\n}"
    }

    /**
     * Parses the CouncilSynthesis output JSON into a [StartingField].
     *
     * In production, this deserialises the LLM's JSON output. For now, provides a
     * sensible default if the LLM output doesn't parse cleanly.
     */
    private fun parseStartingField(synthesisOutput: String, objective: String): StartingField {
        // Best-effort parse; full implementation would use kotlinx.serialization
        return try {
            lenientJson.decodeFromString<StartingField>(synthesisOutput)
        } catch (e: Exception) {
            Napier.w("GenesisCouncil: failed to parse StartingField JSON, using defaults: ${e.message}")
            StartingField.default(objective)
        }
    }

    companion object {
        /** Shared lenient Json instance to avoid repeated allocations. */
        private val lenientJson = Json { ignoreUnknownKeys = true }
    }
}
