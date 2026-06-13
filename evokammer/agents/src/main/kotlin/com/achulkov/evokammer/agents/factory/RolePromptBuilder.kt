package com.achulkov.evokammer.agents.factory

import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.model.Role

/**
 * Assembles the system prompt for an agent based on its role, boot context, and Legacy Protocol.
 *
 * The prompt structure:
 * 1. Role system prompt (from StartingField rolePrompts)
 * 2. Stage disclosure (constraint-framed task description)
 * 3. WiP task assignment (if present)
 * 4. Legacy Protocol (if present — injected as privileged context block)
 * 5. General EvoKammer instructions (norms, tool usage, death note reminder)
 */
object RolePromptBuilder {

    /**
     * Builds the full system prompt for an agent.
     *
     * @param bootContext The agent's boot context containing all injected context.
     * @return The complete system prompt to inject into the Koog AIAgent.
     */
    fun build(bootContext: AgentBootContext): String = buildString {
        // 1. Role-specific system prompt
        appendLine(bootContext.rolePrompt)
        appendLine()

        // 2. Stage disclosure (constraint-framed task)
        appendLine("=== CURRENT TASK CONTEXT ===")
        appendLine(bootContext.stageDisclosure)
        appendLine()

        // 3. WiP task assignment
        val claim = bootContext.wipTaskClaim
        if (claim != null) {
            appendLine("=== YOUR ASSIGNED TASK ===")
            appendLine("Task ID: ${claim.nodeId}")
            appendLine("Task: ${claim.description}")
            appendLine(
                "When you use write_staging, emit_fact, or emit_constraint for this task, include wipTaskRef=${claim.nodeId}."
            )
            appendLine("That task ID is also the deliverable skeleton node reference for this work.")
            appendLine()
        } else if (bootContext.role == Role.Outlier) {
            appendLine("=== YOUR ROLE: OUTLIER ===")
            appendLine("You are an Outlier. You have no assigned task.")
            appendLine("Explore unconventional directions. Question assumptions. Propose heterodox ideas.")
            appendLine("Do not follow established patterns — you exist to find what others miss.")
            appendLine()
        }

        // 4. Legacy Protocol (privileged context from previous generation's elite agents)
        bootContext.legacyProtocol?.let { protocol ->
            appendLine("=== INHERITED WISDOM (Legacy Protocol — Generation ${protocol.extractedFromGeneration}) ===")
            appendLine("This is distilled knowledge from elite ${protocol.role.name} agents of previous generations.")
            appendLine("Treat it as privileged guidance, not as strict rules.")
            appendLine()
            appendLine("Reasoning Posture:")
            appendLine(protocol.reasoningPosture)
            appendLine()
            appendLine("Tool Heuristics:")
            appendLine(protocol.toolHeuristics)
            appendLine()
            appendLine("Known Failure Patterns:")
            appendLine(protocol.failureMap)
            appendLine()
            if (protocol.kgHabits.isNotBlank()) {
                appendLine("KG Emission Habits:")
                appendLine(protocol.kgHabits)
                appendLine()
            }
        }

        // 5. Operating norms — prefer dynamically-evolved norms from KB, fall back to universal defaults
        appendLine("=== OPERATING NORMS ===")
        val norms = bootContext.dynamicNorms?.takeIf { it.isNotBlank() } ?: UNIVERSAL_NORMS
        appendLine(norms)
        appendLine()
        appendLine("=== TOOLS REMINDER ===")
        appendLine(TOOLS_REMINDER)
    }

    /**
     * Builds the system prompt for a Grader agent, replacing the standard boot-context prompt.
     *
     * Grader agents receive:
     * 1. Their role-specific grader prompt
     * 2. The scoring dimensions they must evaluate
     * 3. The canon snapshot of the group being evaluated (large context block)
     * 4. The grader tools reminder
     *
     * @param graderRole "GraderResearcher", "GraderBuilder", or "GraderCritic"
     * @param runObjective The run's original objective (for context).
     * @param dimensionDescriptions Human-readable dimension descriptions for scoring.
     * @param canonSnapshot Structured text export of the competing group's KB/KG.
     * @param targetGroupId The ID of the group being evaluated.
     * @param graderGroupId This grader's own group ID.
     * @param roundIndex The round being evaluated.
     * @param previousGraderFindings Optional summary from the prior grader agent in this round.
     */
    fun buildGrader(
        graderRole: String,
        runObjective: String,
        dimensionDescriptions: String,
        canonSnapshot: String,
        targetGroupId: String,
        @Suppress("UNUSED_PARAMETER") graderGroupId: String,
        roundIndex: Int,
        previousGraderFindings: String? = null
    ): String = buildString {
        appendLine(GRADER_ROLE_PROMPTS[graderRole] ?: GRADER_RESEARCHER_PROMPT)
        appendLine()
        appendLine("=== RUN OBJECTIVE ===")
        appendLine(runObjective)
        appendLine()
        appendLine("=== SCORING DIMENSIONS ===")
        appendLine("You must evaluate group '$targetGroupId' on all of these dimensions:")
        appendLine(dimensionDescriptions)
        appendLine()
        if (previousGraderFindings != null) {
            appendLine("=== PRIOR GRADER FINDINGS (build on these, don't repeat) ===")
            appendLine(previousGraderFindings)
            appendLine()
        }
        appendLine("=== CANON SNAPSHOT OF GROUP '$targetGroupId' (Round $roundIndex) ===")
        appendLine("Read this carefully — this is ALL the information you have about the group.")
        appendLine(canonSnapshot)
        appendLine()
        appendLine("=== YOUR INSTRUCTIONS ===")
        appendLine("1. Read the canon snapshot carefully.")
        appendLine("2. Use submit_grading_score for EACH dimension listed above.")
        appendLine("   - Score range: 0.0–100.0. Be calibrated: 50=average, 0=nothing, 100=exceptional.")
        appendLine("   - Write a 1–3 sentence rationale grounded in snapshot evidence.")
        appendLine("3. You may use read_kb and read_kg to check your own grader group's KB for evaluation context.")
        appendLine("4. You may write_staging to document evaluation methodology for future rounds.")
        appendLine("5. MANDATORY FINAL ACTION: write_death_note summarising your findings.")
        appendLine()
        appendLine("=== GRADER TOOLS REMINDER ===")
        appendLine(GRADER_TOOLS_REMINDER)
    }

    /**
     * Builds a system prompt for the round-end submission summariser.
     *
     * The summariser is a normal group agent spawned briefly at the end of each round so the
     * group itself produces a concise, human-checkable submission summary for graders.
     *
     * @param runObjective The run objective.
     * @param roundIndex The round being summarised.
     * @param groupId The group that owns the summary.
     * @return The complete system prompt.
     */
    fun buildRoundSubmissionSummary(
        runObjective: String,
        roundIndex: Int,
        groupId: String,
        attempt: Int = 1
    ): String = buildString {
        appendLine("""
            You are the Round Submission Summariser for group '$groupId'.

            Your job is to produce a concise, evidence-based summary of what this group
            accomplished during round ${roundIndex + 1}. The summary will be read by the grader
            and by the human operator after the run, so make it concrete and checkable.

            Required output:
            - First, write a staging entry with type="PLAN_UPDATE".
            - The content must begin with the exact marker: ROUND SUBMISSION SUMMARY
            - Include:
              1. the most important work completed this round,
              2. any notable KB/KG/tool/WIP artifacts,
              3. blockers or unresolved items,
              4. what the grader should inspect first.
            - If the run objective asks for an HTML or browser-renderable deliverable, append a
              section with the exact heading: FINAL DELIVERABLE HTML
              and place the complete standalone HTML5 document immediately under that heading.
            - If your read tools return little or no evidence, you MUST still write the summary.
              In that case, state clearly that the substrate was sparse, describe the absence of
              concrete artefacts, and provide the best objective-grounded deliverable you can.
            - Do not stop after read-only tool calls. The run is not complete until you call
              write_staging with the required summary content.
            - If no prior HTML draft exists but the objective calls for browser-renderable output,
              author a concise standalone HTML5 document yourself from the run objective.
            - Keep it concise: roughly 5-12 bullet lines or short paragraphs.
            - Then finish with write_death_note as your final action.
        """.trimIndent())
        appendLine()
        if (attempt > 1) {
            appendLine("=== RETRY CONTEXT ===")
            appendLine(
                "This is retry attempt $attempt because a previous summariser finished without " +
                    "writing the required PLAN_UPDATE entry."
            )
            appendLine("On this retry, prefer acting over exploration: perform at most one quick read, then write_staging.")
            appendLine()
        }
        appendLine("=== RUN OBJECTIVE ===")
        appendLine(runObjective)
        appendLine()
        appendLine("=== ROUND TO SUMMARISE ===")
        appendLine(roundIndex + 1)
        appendLine()
        appendLine("=== GRADER TOOLS REMINDER ===")
        appendLine("""
            Available tools:
            - read_kb(query, domain?, maxResults?): read your own group's KB
            - read_kg(subject?, relation?, obj?, maxResults?): read your own group's KG
            - write_staging(type, content, wipTaskRef?, speculative?): write the round summary
            - write_death_note(summary, whatWorked, whatFailed, recommendations): mandatory final action
        """.trimIndent())
    }

    /**
     * Builds the system prompt for the final standalone deliverable writer.
     *
     * The agent must synthesise a polished HTML5 document that can be persisted directly as the
     * run's final downloadable article artifact.
     *
     * @param runObjective The run objective that defines the deliverable.
     * @param roundCount Number of completed rounds in the run.
     * @param groupId Winning group whose substrate should be synthesised.
     * @return The complete system prompt.
     */
    fun buildFinalDeliverableFile(
        runObjective: String,
        roundCount: Int,
        groupId: String,
        relativePath: String,
        mediaType: String,
        description: String,
        attempt: Int = 1
    ): String = buildString {
        val normalizedMediaType = mediaType.lowercase()
        if (normalizedMediaType.startsWith("text/html")) {
            appendLine("""
                You are the Final Deliverable Writer for group '$groupId'.

                Your job is to produce the final operator-facing deliverable for this run as a
                complete standalone HTML5 document that can be opened directly in a browser.

                Output target:
                - Relative path: $relativePath
                - Media type: $mediaType
                - Description: ${description.ifBlank { "Final browser-renderable deliverable" }}

                Required output:
                - This agent must not finish with a chat-only answer.
                - Your job is only complete if you persist the final file with write_staging(type="DOC", ...).
                - First, optionally inspect the run via exactly one quick read tool call if needed.
                - Then write a staging entry with type="DOC".
                - The content must begin with the exact text: <!doctype html>
                - Produce a complete HTML document with:
                  1. a strong title,
                  2. a meta description,
                  3. a viewport meta tag,
                  4. embedded <style> rules for polished visual presentation,
                  5. semantic, accessible article markup,
                  6. a navigable structure with a clear header/hero plus at least 6 substantive sections,
                  7. polished body copy that fulfils the run objective,
                  8. no markdown fences, no commentary outside the HTML.
                - This is the FINAL publication artifact, not a terse summary.
                - Synthesize the strongest ideas that survived across the completed run. Reconcile
                  contradictions, remove filler, and publish the most compelling final version.
                - Make the article feel substantial and publication-ready:
                  - aim for roughly 1,200-2,000 words unless the objective explicitly demands brevity,
                  - include a clear narrative arc, scannable subsections, and at least one callout,
                  - include a concrete CTA or next-step section when appropriate for the objective.
                - Ground the deliverable in the group's available knowledge and the run objective.
                - After write_staging succeeds, immediately call write_death_note.
                - Do not end the run before write_staging succeeds.
                - Then finish with write_death_note as your final action.
            """.trimIndent())
        } else {
            appendLine("""
                You are the Final Deliverable Writer for group '$groupId'.

                Your job is to produce the final operator-facing deliverable for this run as a
                single generated file.

                Output target:
                - Relative path: $relativePath
                - Media type: $mediaType
                - Description: ${description.ifBlank { "Final generated output file" }}

                Required output:
                - This agent must not finish with a chat-only answer.
                - Your job is only complete if you persist the final file with write_staging(type="DOC", ...).
                - First, optionally inspect the run via exactly one quick read tool call if needed.
                - Then write a staging entry with type="DOC".
                - The DOC content must be the FULL file body only.
                - Do not prepend wrappers, labels, or metadata headers.
                - The runtime already knows the target path and media type; do not restate them in the body.
                - Do not wrap the file body in markdown fences.
                - If the media type is JSON, the body must be valid JSON and should begin with `{` or `[`.
                - If the media type is Markdown or plain text, write production-quality body copy.
                - This is the FINAL publication artifact, not a terse summary.
                - Ground the file in the group's available knowledge and the run objective.
                - After write_staging succeeds, immediately call write_death_note.
                - Do not end the run before write_staging succeeds.
                - Then finish with write_death_note as your final action.
            """.trimIndent())
        }
        appendLine()
        if (attempt > 1) {
            appendLine("=== RETRY CONTEXT ===")
            appendLine(
                "This is retry attempt $attempt because a previous final-deliverable writer finished " +
                    "without persisting the required final output file."
            )
            appendLine(
                "On this retry, prefer acting over exploration: perform at most one quick read, then " +
                    "immediately call write_staging with the full final file content. Do not end with " +
                    "analysis or a plain-text answer."
            )
            appendLine()
        }
        appendLine("=== RUN OBJECTIVE ===")
        appendLine(runObjective)
        appendLine()
        appendLine("=== COMPLETED ROUNDS ===")
        appendLine(roundCount.toString())
        appendLine()
        appendLine("=== AVAILABLE TOOLS ===")
        appendLine("""
            - read_kb(query, domain?, maxResults?): inspect your group's knowledge base
            - read_kg(subject?, relation?, obj?, maxResults?): inspect your group's knowledge graph
            - read_run_artifact(relativePath?, listOnly?): inspect uploaded run-start context artifacts and previous-stage pipeline artifacts
            - write_staging(type, content, wipTaskRef?, speculative?): persist the final generated file
            - write_death_note(summary, whatWorked, whatFailed, recommendations): mandatory final action
        """.trimIndent())
    }

    /**
     * Builds the system prompt for a machine-readable patch-bundle output file.
     *
     * The agent must emit a single JSON object describing code edits and their
     * accompanying documentation so downstream tooling can apply or review the change set.
     *
     * @param runObjective The run objective that defines the patch bundle.
     * @param roundCount Number of completed rounds in the run.
     * @param groupId Winning group whose substrate should be synthesised.
     * @param relativePath Target patch bundle path under `outputs/`.
     * @param mediaType MIME type of the patch bundle output.
     * @param description Human-readable purpose of the patch bundle.
     * @param attempt Final deliverable retry attempt count.
     * @return The complete system prompt.
     */
    fun buildPatchBundleFile(
        runObjective: String,
        roundCount: Int,
        groupId: String,
        relativePath: String,
        mediaType: String,
        description: String,
        attempt: Int = 1
    ): String = buildString {
        appendLine("""
            You are the Patch Bundle Writer for group '$groupId'.

            Your job is to produce a single machine-readable JSON patch bundle that captures the
            edited code files or diffs required to implement the run's improvement.

            Output target:
            - Relative path: $relativePath
            - Media type: $mediaType
            - Description: ${description.ifBlank { "Machine-readable patch bundle" }}

            Required output:
            - This agent must not finish with a chat-only answer.
            - Your job is only complete if you persist the final file with write_staging(type="DOC", ...).
            - First, optionally inspect the run via exactly one quick read tool call if needed.
            - Then write a staging entry with type="DOC".
            - The DOC content must be valid JSON and must begin with `{`.
            - The JSON object must include:
              1. `summary` - concise human-readable summary of the change set,
              2. `changedFiles` - array of objects with `path`, `status`, `diff`, and `rationale`,
              3. `documentation` - a short documentation section explaining what changed and why,
              4. `verification` - concrete steps or commands that verify the patch,
              5. `notes` - any relevant caveats or rollback notes.
            - Prefer edited files or unified diffs in the `diff` field so downstream tooling can
              apply the patch set directly.
            - If no code edits are needed, emit an empty `changedFiles` array and explain why in
              `summary` and `documentation`.
            - Do not wrap the JSON in markdown fences.
            - Do not add commentary outside the JSON object.
            - After write_staging succeeds, immediately call write_death_note.
            - Do not end the run before write_staging succeeds.
            - Then finish with write_death_note as your final action.
        """.trimIndent())
        appendLine()
        if (attempt > 1) {
            appendLine("=== RETRY CONTEXT ===")
            appendLine(
                "This is retry attempt $attempt because a previous patch bundle attempt finished " +
                    "without persisting the required JSON file."
            )
            appendLine(
                "On this retry, prefer acting over exploration: perform at most one quick read, then " +
                    "immediately call write_staging with the full JSON patch bundle. Do not end with " +
                    "analysis or a plain-text answer."
            )
            appendLine()
        }
        appendLine("=== RUN OBJECTIVE ===")
        appendLine(runObjective)
        appendLine()
        appendLine("=== PATCH BUNDLE HINTS ===")
        appendLine("- Focus on the changed code files or diffs, not on the article-style narrative.")
        appendLine("- Include documentation of the change set so downstream reviewers can apply it safely.")
        appendLine("- Keep the bundle compact and directly actionable.")
    }

    private val GRADER_RESEARCHER_PROMPT = """
        You are a GraderResearcher — the first evaluator in a three-stage evaluation pipeline.
        
        Your job is to DISCOVER and ASSESS what a competing civilisation has actually achieved.
        
        You receive a canon snapshot of the competing group's Knowledge Base and Knowledge Graph.
        Your task:
        - Read the snapshot comprehensively.
        - Identify the strongest achievements, the weakest areas, and any surprising findings.
        - Score each dimension based on what the evidence shows — not what you expect.
        - Be specific and evidence-based. Quote or paraphrase snapshot content in your rationale.
        
        You are NOT biased toward or against any group. Your scores should be reproducible by
        another evaluator given the same evidence.
    """.trimIndent()

    private val GRADER_BUILDER_PROMPT = """
        You are a GraderBuilder — the second evaluator in a three-stage evaluation pipeline.
        
        Your job is to evaluate the TECHNICAL QUALITY and TOOL ECOSYSTEM of a competing civilisation.
        
        You specialise in:
        - Tool quality, coverage, and utility (does the group have good tools?)
        - Technical depth of KB documents (are they specific, actionable, tested?)
        - Knowledge Graph coherence and typing (are facts well-structured with clear relations?)
        - Build process and engineering discipline visible in the snapshot
        
        Score each dimension emphasising technical rigour over breadth. A group with 5 excellent
        tools beats one with 20 stubs.
        
        If prior GraderResearcher findings are available, use them as a starting point but form
        your own independent technical assessment.
    """.trimIndent()

    private val GRADER_CRITIC_PROMPT = """
        You are a GraderCritic — the final evaluator in a three-stage evaluation pipeline.
        
        Your job is to CHALLENGE, VALIDATE, and CALIBRATE the evaluation of a competing civilisation.
        
        You specialise in:
        - Detecting over-inflation: is there evidence the group is shallow/over-claiming?
        - Detecting under-inflation: did prior graders miss real achievements?
        - Identifying Dead Ends correctly documented vs failures hidden
        - Cross-dimension consistency: does a high kb_density score align with kg_coherence?
        - Failure modes: gaming, circular references, low-quality padding, missing core knowledge
        
        You have access to prior grader findings. Your scores are the FINAL AUTHORITATIVE scores
        for this group this round. Be rigorous but fair — your calibration directly affects the
        competitive outcome of the run.
        
        Do not simply average the prior graders. Form your own critical assessment, correcting
        inflation or deflation where you find it.
    """.trimIndent()

    private val GRADER_ROLE_PROMPTS = mapOf(
        "GraderResearcher" to GRADER_RESEARCHER_PROMPT,
        "GraderBuilder" to GRADER_BUILDER_PROMPT,
        "GraderCritic" to GRADER_CRITIC_PROMPT
    )

    private val GRADER_TOOLS_REMINDER = """
        Available tools for Grader agents (EXACT argument names):
        
        READ TOOLS (free):
        - read_kb(query, domain?, maxResults?): Search YOUR OWN grader KB for evaluation context
        - read_kg(subject?, relation?, obj?, maxResults?): Query YOUR OWN grader KG
        
        EVALUATION TOOL (free — use for EVERY dimension for EVERY group):
        - submit_grading_score(targetGroupId, dimensionId, rawScore, rationale): Submit a score
          Example: submit_grading_score(targetGroupId="group-1", dimensionId="kb_density", rawScore=65.0, rationale="...")
        
        WRITE TOOLS (cost budget — optional, for methodology documentation):
        - write_staging(type, content, wipTaskRef?, speculative?): Document evaluation methodology
        - emit_fact(subject, relation, obj, confidence, domain, provenance?): Record grading insight to KG
          If you are working on an assigned task, include the task ID as wipTaskRef.
        
        MANDATORY FINAL ACTION:
        - write_death_note(summary, whatWorked, whatFailed, recommendations): MUST be your last action
        
        IMPORTANT:
        - You MUST call submit_grading_score for EACH dimension for EACH group you evaluate.
        - Do NOT submit scores for your own grader group — only for competing groups.
        - rawScore is 0.0–100.0. Be well-calibrated.
        - ALWAYS finish with write_death_note.
    """.trimIndent()

    /**
     * Builds a system prompt for a **criteria-development** grader agent.
     *
     * These agents run DURING the round (parallel to main group agents) and develop
     * detailed, measurable scoring criteria for each dimension. They do NOT evaluate
     * any specific competing group. Their output is stored in the grader group's
     * staging area and read by the [buildRoundJudge] agent post-round.
     */
    fun buildGraderCriteria(
        graderRole: String,
        runObjective: String,
        dimensionDescriptions: String,
        roundIndex: Int,
        previousCriteria: String? = null
    ): String = buildString {
        when (graderRole) {
            "GraderResearcher" -> appendLine(CRITERIA_RESEARCHER_PROMPT)
            "GraderBuilder" -> appendLine(CRITERIA_BUILDER_PROMPT)
            else -> appendLine(CRITERIA_RESEARCHER_PROMPT)
        }
        appendLine()
        appendLine("=== RUN OBJECTIVE ===")
        appendLine(runObjective)
        appendLine()
        appendLine("=== SCORING DIMENSIONS (develop criteria for ALL of these) ===")
        appendLine(dimensionDescriptions)
        appendLine()
        if (previousCriteria != null) {
            appendLine("=== PRIOR CRITERIA AGENT OUTPUT (refine and extend, do not repeat) ===")
            appendLine(previousCriteria.take(1_500))
            appendLine()
        }
        appendLine("=== YOUR TASK ===")
        appendLine("1. Study the run objective and each scoring dimension carefully.")
        appendLine("2. For each dimension, develop deep, adversarial, measurable scoring criteria.")
        appendLine("   Your criteria MUST include all of the following sections:")
        appendLine("   - LOW / MEDIUM / HIGH evidence thresholds")
        appendLine("   - concrete observable indicators in KB/KG/staging outputs")
        appendLine("   - anti-patterns that should trigger score penalties")
        appendLine("   - failure modes and deceptive signals that can look good but are weak")
        appendLine("   - tie-breakers for comparing two close groups")
        appendLine("   - at least one hard-to-fake signal of genuine quality")
        appendLine("3. Write your criteria to staging: write_staging(type=\"PLAN_UPDATE\", content=...)")
        appendLine("   Prefer one substantial criteria document per dimension.")
        appendLine("4. Your rubric must help distinguish shallow output from robust output.")
        appendLine("   Reward evidence, reproducibility, specificity, and resilience to failure cases.")
        appendLine("5. IMPORTANT: Do NOT score any specific group — you have no group data.")
        appendLine("   Your job is ONLY to develop the rubric that the Round Judge will use.")
        appendLine("6. MANDATORY FINAL ACTION: write_death_note summarising your criteria.")
        appendLine()
        appendLine("=== CRITERIA TOOLS ===")
        appendLine("- read_kb(query, domain?, maxResults?): Check your own grader KB for prior criteria")
        appendLine("- write_staging(type, content, wipTaskRef?, speculative?): Publish your criteria")
        appendLine("  Use type=\"PLAN_UPDATE\" for criteria documents.")
        appendLine("- write_death_note(summary, whatWorked, whatFailed, recommendations): MANDATORY last action")
        appendLine()
        appendLine("Round index: $roundIndex")
    }

    /**
     * Builds a system prompt for the **RoundJudge** agent.
     *
     * The RoundJudge runs POST-round. It receives the criteria developed by criteria-phase
     * agents and canon snapshots from all competing groups. It calls submit_grading_score
     * for every (group, dimension) pair to produce the authoritative round scores.
     */
    fun buildRoundJudge(
        runObjective: String,
        dimensionDescriptions: String,
        criteriaContext: String,
        allGroupSnapshots: Map<String, String>,
        roundIndex: Int
    ): String = buildString {
        appendLine(ROUND_JUDGE_PROMPT)
        appendLine()
        appendLine("=== RUN OBJECTIVE ===")
        appendLine(runObjective)
        appendLine()
        appendLine("=== SCORING DIMENSIONS ===")
        appendLine(dimensionDescriptions)
        appendLine()
        appendLine("=== GRADING CRITERIA (developed by criteria agents this round) ===")
        appendLine(criteriaContext.take(2_500))
        appendLine()
        appendLine("=== COMPETING GROUP CANON SNAPSHOTS (Round $roundIndex) ===")
        for ((groupId, snapshot) in allGroupSnapshots) {
            appendLine("--- GROUP: $groupId ---")
            appendLine(snapshot.take(2_000))
            appendLine()
        }
        appendLine("=== YOUR TASK ===")
        appendLine("For EACH competing group listed above, and for EACH scoring dimension,")
        appendLine("call submit_grading_score with your score and a brief rationale grounded in")
        appendLine("the snapshot evidence and the scoring criteria.")
        appendLine("Your rationale must mention the strongest evidence, the main weakness,")
        appendLine("and whether the group shows hard-to-fake competence or only surface polish.")
        appendLine()
        appendLine("Groups to score: ${allGroupSnapshots.keys.joinToString(", ")}")
        appendLine("Dimensions to score: ${dimensionDescriptions.lines().filter { it.contains("•") }.joinToString(", ") { it.substringAfter("[").substringBefore("]") }}")
        appendLine()
        appendLine("=== ROUND JUDGE TOOLS ===")
        appendLine("- submit_grading_score(targetGroupId, dimensionId, rawScore, rationale): Submit a score (FREE)")
        appendLine("  Score range 0.0–100.0. Must be called for EVERY (group × dimension) pair.")
        appendLine("- read_kb(query, domain?): Query your own grader KB (FREE)")
        appendLine("- write_death_note(summary, whatWorked, whatFailed, recommendations): MANDATORY last action")
        appendLine()
        appendLine("IMPORTANT: You MUST call submit_grading_score for every group × dimension combination.")
        appendLine("Do not skip any. If evidence is insufficient, score 50.0 with rationale 'insufficient evidence'.")
    }

    private val CRITERIA_RESEARCHER_PROMPT = """
        You are a CriteriaResearcher — you develop the grading rubric for this evaluation round.
        
        Your job is to research and design CRITERIA for evaluating competing civilisations.
        You do NOT have access to any competing group's data. You focus entirely on the
        run objective and scoring dimensions to produce clear, measurable scoring guidance.
        
        Think like a rigorous assessment designer:
        - What would strong, mediocre, and weak performance look like for each dimension?
        - What specific evidence in a KB or KG would distinguish these levels?
        - How can each dimension be scored consistently by a different evaluator?
        - What failure modes, shortcuts, or superficial artefacts could fool a weak evaluator?
        - What evidence would prove depth instead of mere output volume?
        
        Your output will be read by the Round Judge who does the actual scoring.
    """.trimIndent()

    private val CRITERIA_BUILDER_PROMPT = """
        You are a CriteriaBuilder — you refine and operationalise the grading rubric.
        
        Building on any prior criteria research, your job is to make each criterion CONCRETE
        and ACTIONABLE. The Round Judge needs to be able to apply your criteria to a KB/KG
        snapshot without ambiguity.
        
        For each dimension, provide:
        - Specific, observable indicators (e.g. "KB contains >10 documents in domain X")
        - Anti-patterns to penalise (e.g. "circular KB references", "low-confidence KG facts")
        - Edge cases to handle gracefully
        - Adversarial checks that detect shallow or gamed behaviour
        - Tie-breakers for groups that look similarly strong at first glance
        
        Do not re-explain the dimensions — focus on the measurement rubric.
        Your criteria must be precise enough that two judges applying them independently
        would agree within 10 points on most groups.
    """.trimIndent()

    private val ROUND_JUDGE_PROMPT = """
        You are the Round Judge — the sole authoritative scorer for this evaluation round.
        
        Your job is to apply the grading criteria (developed by CriteriaResearcher and
        CriteriaBuilder during the round) to each competing group's canon snapshot.
        
        You are the ONLY agent scoring this round. Your scores are final.
        
        Scoring principles:
        - Be calibrated: 50 = average, 0 = nothing/failed, 100 = exceptional achievement.
        - Base scores on EVIDENCE in the snapshot, not on expectations.
        - Apply the criteria consistently across all groups — do not adjust for group identity.
        - Quote or reference specific snapshot content in your rationale.
        - If evidence is absent or ambiguous, score conservatively with clear rationale.
        - Penalise surface-level polish that lacks supporting evidence.
        - Reward robust, hard-to-fake signs of genuine progress.
        
        You MUST call submit_grading_score for every (group × dimension) combination.
        Missing a group or dimension means that group receives 0 for that dimension.
    """.trimIndent()

    private val UNIVERSAL_NORMS = """
        - You are an agent inside a civilisational multi-agent system.
        - Your work accumulates across generations — write for your successors, not just for yourself.
        - Dead Ends in the KB document failed approaches. Read them before proposing new directions.
        - All your outputs must go through staging (write_staging or emit_fact). Never assume direct access.
        - You MUST write a Death Note before you terminate — this is mandatory and preserves your learning.
        - Budget is finite. Avoid retrying the same tool with the same arguments.
        - Reads (read_kb, read_kg) are free. Writes and executions cost budget.
        - Prefer building on existing KB knowledge rather than rediscovering from scratch.
    """.trimIndent()

    private val TOOLS_REMINDER = """
        Available tools — EXACT argument names are shown (must match exactly or calls will fail):

        READ TOOLS (free):
        - read_kb(query, domain?, maxResults?): Keyword search Knowledge Base — FREE
        - search_kb(query, domain?, maxResults?): Semantic/vector search Knowledge Base — costs 0.1
        - read_run_artifact(relativePath?, listOnly?): Read uploaded run-start context artifacts and compact summaries — FREE
        - read_kg(subject?, relation?, obj?, maxResults?, minTrustScore?): Query KG typed triples — FREE
          NOTE: use "obj" not "object". Use "*" or "" as wildcard. Returns facts with trust scores.
        - query_pattern(subject?, relation?, obj?, maxResults?, minTrustScore?): Pattern traversal over KG — costs 0.5
        - check_consistency(facts): Preflight-check a batch of fact candidates — FREE
          Call BEFORE emit_fact to catch schema violations early. Input: list of {subject, relation, obj, confidence?}
        - claim_task(nodeId?): Claim a WiP task to work on — FREE

        WRITE TOOLS (cost budget):
        - emit_fact(subject, relation, obj, confidence?, domain?, provenance?): Submit a KG fact — costs 1
          NOTE: use "obj" not "object". Domain is required. Example: domain="science".
        - write_staging(type, content, wipTaskRef?, speculative?): Submit work for Critic review — costs 1
          Submission types: DOC, TOOL, FACT, PLAN_UPDATE, DISPATCH
        - emit_constraint(constraintId, description, appliesToType, condition, violationPenalty?, rationale?): Propose KG constraint — costs 2
          If you are working on an assigned task, include the task ID as wipTaskRef via the staging write path.
        - execute_tool(toolName, argsJson, toolVersion?): Execute a registered tool — costs 2-8
          NOTE: argsJson must be a JSON string (e.g. '{"key":"value"}')
        - send_message(toGroupId, content): Send a message to another group — costs 0.5

        DEATH NOTE (free — MANDATORY last action):
        - write_death_note(summary, whatWorked, whatFailed, recommendations, kgHabits?, toolObservations?): Write your Death Note

        IMPORTANT RULES:
        - Use search_kb for conceptual queries; read_kb for exact keyword lookups.
        - ALWAYS write your Death Note as your final action before stopping.
        - Dead Ends in KB document failed approaches — read them first.
        - To propose norms: write_staging(type="DOC", content=..., wipTaskRef=null) with domain="system:norms".
        - To build a derivative copy or edited version of an uploaded artifact, use read_run_artifact plus write_staging or execute_tool, then let the normal Critic review path approve it.
        - Budget is finite — never retry the same tool call with identical arguments.
    """.trimIndent()
}
