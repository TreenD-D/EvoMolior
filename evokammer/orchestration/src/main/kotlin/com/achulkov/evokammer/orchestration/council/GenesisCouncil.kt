package com.achulkov.evokammer.orchestration.council

import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.Role
import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
 * This class is a coordination shell — the actual agents are created by `EvoKammerAgentFactory`.
 *
 * @param objective The natural-language run objective.
 * @param openRouterApiKey API key for the LLM executor.
 */
class GenesisCouncil(
    private val objective: String,
    @Suppress("unused") private val openRouterApiKey: String
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
     * Runs the Genesis Council with roles 1–7 in parallel, then [Role.CouncilSynthesis] sequentially.
     *
     * **Parallel phase** (roles 1–7): all non-synthesis council agents run concurrently
     * using `coroutineScope { async { ... } }`. Each agent receives only the base objective
     * as context (they don't depend on each other's outputs).
     *
     * **Synthesis phase**: [Role.CouncilSynthesis] runs after all parallel agents complete,
     * with the merged output of all 7 roles as context.
     *
     * **Consistency re-run**: if the synthesis output contains a `"consistencyError": true`
     * field (written by the CouncilSynthesis prompt when conflicts are detected), the
     * conflicting role is re-run with the error as additional context, up to [maxRetries] times.
     *
     * @param agentRunner Suspend function that takes (role, contextJson) and returns the agent's output.
     * @param onProgress Optional callback invoked after each agent completes.
     * @param maxRetries Maximum number of consistency re-runs (default: 3).
     * @return The assembled [StartingField].
     */
    suspend fun run(
        agentRunner: suspend (role: Role, contextJson: String) -> String,
        onProgress: ((String) -> Unit)? = null,
        maxRetries: Int = 3
    ): StartingField {
        Napier.i("GenesisCouncil: starting (parallel phase) for objective: ${objective.take(80)}…")
        val totalAgents = councilRoles.size
        val baseContext = """{"objective": "${objective.replace("\"", "\\\"")}"}"""

        // ── Parallel phase: roles 1–7 ────────────────────────────────────────
        val parallelRoles = councilRoles.dropLast(1)   // everything except CouncilSynthesis
        val synthesisRole = councilRoles.last()          // CouncilSynthesis

        onProgress?.invoke("[parallel] Running ${parallelRoles.size} council agents concurrently…")

        val parallelOutputs: List<Pair<Role, String>> = coroutineScope {
            parallelRoles.mapIndexed { index, role ->
                async {
                    Napier.i("GenesisCouncil: [parallel] ${role.name} starting (${index + 1}/${parallelRoles.size})")
                    val output = agentRunner(role, baseContext)
                    val preview = output.take(300).replace("\n", " ")
                    Napier.i("GenesisCouncil: [parallel] ${role.name} done (len=${output.length})")
                    Napier.d("GenesisCouncil: ${role.name} preview: $preview")
                    onProgress?.invoke("[${index + 1}/$totalAgents] ${role.name} complete (${output.length} chars): $preview")
                    role to output
                }
            }.awaitAll()
        }

        // ── Synthesis phase + consistency re-run loop ────────────────────────
        val allOutputs = parallelOutputs.toMutableList()
        var synthesisOutput: String

        var attempt = 0
        while (true) {
            val synthContext = buildContext(allOutputs)
            onProgress?.invoke("[$totalAgents/$totalAgents] ${synthesisRole.name} — synthesising${if (attempt > 0) " (retry $attempt)" else ""}…")
            Napier.i("GenesisCouncil: ${synthesisRole.name} starting (attempt=${attempt + 1})")

            synthesisOutput = agentRunner(synthesisRole, synthContext)
            val preview = synthesisOutput.take(300).replace("\n", " ")
            Napier.i("GenesisCouncil: ${synthesisRole.name} done (len=${synthesisOutput.length})")
            onProgress?.invoke("[$totalAgents/$totalAgents] ${synthesisRole.name} complete (${synthesisOutput.length} chars): $preview")

            // Check for consistency errors emitted by the synthesis agent
            val consistencyError = extractConsistencyError(synthesisOutput)
            if (consistencyError == null || attempt >= maxRetries) {
                if (consistencyError != null) {
                    Napier.w("GenesisCouncil: consistency error persists after $maxRetries retries — proceeding with best-effort output")
                }
                break
            }

            // Re-run the conflicting role with the error as additional context
            Napier.w("GenesisCouncil: consistency error detected — re-running conflicting role (attempt ${attempt + 1}/$maxRetries): $consistencyError")
            onProgress?.invoke("[retry $attempt] Consistency conflict: $consistencyError — re-running…")
            val conflictingRole = resolveConflictingRole(consistencyError) ?: parallelRoles.first()
            val retryContext = buildContext(allOutputs) +
                """, "consistencyError": "${consistencyError.replace("\"", "\\\"")}""""
            val retryOutput = agentRunner(conflictingRole, retryContext)
            // Replace the old output for that role
            allOutputs.removeAll { it.first == conflictingRole }
            allOutputs.add(conflictingRole to retryOutput)
            attempt++
        }

        return parseStartingField(synthesisOutput, objective)
    }

    /**
     * Extracts the `consistencyError` string from the synthesis output JSON if present.
     * Returns null if no consistency error is reported.
     *
     * Strips markdown code fences before parsing so the function works whether or not
     * the LLM wrapped the JSON in ` ```json … ``` `.
     */
    private fun extractConsistencyError(synthesisOutput: String): String? {
        return try {
            val cleaned = stripCodeFences(synthesisOutput)
            val jsonStr = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(cleaned)?.value ?: return null
            val obj = lenientJson.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonObject ?: return null
            if (obj["consistencyError"]?.toString()?.contains("true") == true) {
                obj["consistencyDetails"]?.toString()?.trim('"') ?: "consistency_conflict"
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * Tries to resolve which council role is responsible for the consistency conflict.
     * Falls back to null if the role name can't be extracted from the error string.
     */
    private fun resolveConflictingRole(error: String): Role? {
        return councilRoles.dropLast(1).firstOrNull { role ->
            error.contains(role.name, ignoreCase = true)
        }
    }

    private fun buildContext(outputs: List<Pair<Role, String>>): String {
        // Strip code fences from each parallel agent's output so the synthesis context is
        // valid JSON rather than a mix of JSON and markdown code fence markers.
        val parts = outputs.joinToString(",\n") { (role, output) ->
            val cleaned = stripCodeFences(output)
            """"${role.name}": ${cleaned.ifBlank { "\"\"" }}"""
        }
        return "{\n$parts\n}"
    }

    /**
     * Parses the CouncilSynthesis output JSON into a [StartingField].
     *
     * LLMs reliably wrap JSON responses in markdown code fences (` ```json … ``` `).
     * [stripCodeFences] is applied first so the JSON parser never sees backticks.
     *
     * Full deserialization attempts to decode the LLM's JSON output directly into
     * [StartingField]. On failure, falls back to [StartingField.default] with only the
     * `consistencyReport` field rescued from the raw output if present (B-3).
     *
     * @param synthesisOutput Raw LLM output from [Role.CouncilSynthesis].
     * @param objective The run objective (used for the default fallback).
     */
    private fun parseStartingField(synthesisOutput: String, objective: String): StartingField {
        // Strip markdown code fences — LLMs commonly wrap JSON in ```json … ```.
        val cleaned = stripCodeFences(synthesisOutput)
        return try {
            lenientJson.decodeFromString<StartingField>(cleaned)
        } catch (e: Exception) {
            Napier.w("GenesisCouncil: failed to parse StartingField JSON, using defaults: ${e.message}")
            // B-3: Rescue consistencyReport even if the full StartingField parse fails.
            val rescuedReport = extractConsistencyReport(cleaned)
            val rescuedSuiteJson = extractJsonStringField(cleaned, "sealedTestSuiteJson")
            val rescuedSuiteHash = extractJsonStringField(cleaned, "sealedTestSuiteHash")
            val rescuedStageContents = extractJsonStringArrayField(cleaned, "stageContents")
            StartingField.default(objective).copy(
                sealedTestSuiteJson = rescuedSuiteJson,
                sealedTestSuiteHash = rescuedSuiteHash,
                stageContents = rescuedStageContents,
                consistencyReport = rescuedReport
            )
        }
    }

    /**
     * Extracts the `consistencyReport` field from the synthesis JSON if present.
     * Returns an empty string on failure.
     *
     * Accepts either a raw JSON string or one already stripped of code fences.
     *
     * @param synthesisOutput Raw or stripped LLM output string.
     */
    private fun extractConsistencyReport(synthesisOutput: String): String {
        return try {
            val jsonStr = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(synthesisOutput)?.value
                ?: return ""
            val obj = lenientJson.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonObject
                ?: return ""
            obj["consistencyReport"]?.toString()?.trim('"') ?: ""
        } catch (_: Exception) { "" }
    }

    /**
     * Extracts a top-level JSON string field from synthesis output if present.
     *
     * Used as a fallback when the full StartingField decode fails, so the sealed suite
     * can still survive partial JSON corruption.
     *
     * @param synthesisOutput Raw or stripped LLM output string.
     * @param fieldName The JSON string field to rescue.
     */
    private fun extractJsonStringField(synthesisOutput: String, fieldName: String): String {
        return try {
            val jsonStr = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(synthesisOutput)?.value
                ?: return ""
            val obj = lenientJson.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonObject
                ?: return ""
            obj[fieldName]?.toString()?.trim('"') ?: ""
        } catch (_: Exception) { "" }
    }

    /**
     * Extracts a top-level JSON string array field from synthesis output if present.
     *
     * Used as a fallback when the full StartingField decode fails so disclosure stages
     * are not lost during JSON corruption.
     *
     * @param synthesisOutput Raw or stripped LLM output string.
     * @param fieldName The JSON string array field to rescue.
     */
    private fun extractJsonStringArrayField(synthesisOutput: String, fieldName: String): List<String> {
        return try {
            val jsonStr = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(synthesisOutput)?.value
                ?: return emptyList()
            val obj = lenientJson.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonObject
                ?: return emptyList()
            val array = obj[fieldName] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                element.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) { emptyList() }
    }

    companion object {
        /** Shared lenient Json instance to avoid repeated allocations. */
        private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Strips markdown code fences from an LLM response.
         *
         * LLMs routinely return JSON wrapped in ` ```json … ``` ` or ` ``` … ``` `.
         * This function removes those markers so the content can be parsed as plain JSON.
         *
         * Handles both ` ```json ` and plain ` ``` ` opening fences, and is safe to call
         * on strings that don't have code fences (returns the trimmed input unchanged).
         *
         * @param text Raw LLM response text, possibly containing markdown code fences.
         * @return The content with the outermost code fence removed, trimmed.
         */
        fun stripCodeFences(text: String): String {
            val trimmed = text.trim()
            if (!trimmed.startsWith("```")) return trimmed
            // Find the end of the opening fence line (e.g. "```json\n" or "```\n")
            val firstNewline = trimmed.indexOf('\n')
            // Find the last occurrence of ``` that closes the block
            val closingFence = trimmed.lastIndexOf("```")
            return if (firstNewline != -1 && closingFence > firstNewline) {
                trimmed.substring(firstNewline + 1, closingFence).trim()
            } else {
                // Malformed fence (no newline or no closing) — strip the opening marker only
                trimmed.removePrefix("```json").removePrefix("```").trim()
            }
        }
    }
}
