package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.atlas.ExactNodeEvaluationContext
import com.achulkov.evomolior.core.atlas.NoveltyType
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolImplementationMode

import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.contract.GeneratedImageAsset
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.contract.WorkerView
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Assembles the system prompt for an agent based on its role, boot context, and Legacy Protocol.
 *
 * The prompt structure:
 * 1. Role system prompt (from StartingField rolePrompts)
 * 2. Stage disclosure (constraint-framed task description)
 * 3. WiP task assignment (if present)
 * 4. Legacy Protocol (if present — injected as privileged context block)
 * 5. General EvoMolior instructions (norms, tool usage, death note reminder)
 */
object RolePromptBuilder {
    /** Header of the grader-authored automated test results appended to judge criteria. */
    const val JUDGE_AUTOMATED_TEST_RESULTS_HEADER = "=== GRADER-AUTHORED AUTOMATED TEST RESULTS ==="

    /** Criteria characters a RoundJudge prompt carries (previously a flat 2,500). */
    const val ROUND_JUDGE_CRITERIA_MAX_CHARS = 8_000

    /**
     * Bounds RoundJudge criteria while always keeping the authored automated test results.
     *
     * The results are appended after the criteria digest, so a flat prefix cut dropped them first
     * even though they are the most mechanical evidence the judge receives.
     *
     * @param criteriaContext Criteria digest, optionally followed by the test results section.
     * @param maxChars Upper bound on the returned text.
     */
    fun boundedJudgeCriteria(criteriaContext: String, maxChars: Int = ROUND_JUDGE_CRITERIA_MAX_CHARS): String {
        if (criteriaContext.length <= maxChars) return criteriaContext
        val marker = "...[criteria truncated]\n"
        val resultsStart = criteriaContext.indexOf(JUDGE_AUTOMATED_TEST_RESULTS_HEADER)
        if (resultsStart < 0) return criteriaContext.take(maxChars - marker.length) + marker
        val results = criteriaContext.substring(resultsStart).take(maxChars / 2)
        val head = criteriaContext.substring(0, resultsStart)
            .take((maxChars - results.length - marker.length).coerceAtLeast(0))
        return head + marker + results
    }

    /** Characters of the previous round's accepted criteria baseline shown to criteria agents. */
    const val GRADER_BASELINE_PROMPT_MAX_CHARS = 9_000

    /** Total canon-snapshot characters a RoundJudge prompt may carry across all target groups. */
    const val ROUND_JUDGE_SNAPSHOT_BUDGET_CHARS = 40_000

    /** Floor per snapshot so many-group prompts still show each group's headline evidence. */
    const val ROUND_JUDGE_MIN_SNAPSHOT_CHARS = 4_000

    /**
     * Characters retained per snapshot when [snapshotCount] groups share one RoundJudge prompt.
     *
     * @param snapshotCount Number of snapshots in the prompt.
     */
    fun roundJudgeSnapshotChars(snapshotCount: Int): Int =
        maxOf(ROUND_JUDGE_MIN_SNAPSHOT_CHARS, ROUND_JUDGE_SNAPSHOT_BUDGET_CHARS / snapshotCount.coerceAtLeast(1))

    private val json = Json { ignoreUnknownKeys = true }
    private val authorityJson = Json {
        encodeDefaults = true
        explicitNulls = true
    }
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

        // 3. Authority-scoped run context
        bootContext.workerView?.let { workerView ->
            appendLine(renderWorkerViewContext(workerView))
            appendLine()
        } ?: bootContext.startingField?.let { startingField ->
            appendLine(renderStartingFieldContext(startingField, bootContext.role))
            appendLine()
        }

        // 4. Routed context from prior runs (attention-selected top-k artifacts)
        bootContext.startingField?.routedContextSummaries
            ?.takeIf { it.isNotEmpty() }
            ?.let { routedContextSummaries ->
                appendLine("=== ROUTED CONTEXT ===")
                routedContextSummaries.forEachIndexed { index, summary ->
                    appendLine("[${index + 1}] ${summary}")
                }
                appendLine()
            }

        // 5. Run graph ancestry (graph/GNN-style run-node relationships)
        bootContext.startingField?.runGraphEdges
            ?.takeIf { it.isNotEmpty() }
            ?.let { runGraphEdges ->
                appendLine("=== RUN GRAPH ===")
                runGraphEdges.takeLast(12).forEach { edge ->
                    val formattedWeight = String.format(java.util.Locale.ROOT, "%.2f", edge.weight)
                    appendLine("${edge.sourceRunId} -[${edge.edgeType}, w=$formattedWeight]-> ${edge.targetRunId}")
                }
                if (runGraphEdges.size > 12) {
                    appendLine("...[truncated]")
                }
                appendLine()
            }

        // 6. WiP task assignment
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
        } else if (bootContext.role == Role.PerspectiveTaker) {
            appendLine("=== YOUR ROLE: PERSPECTIVE TAKER ===")
            appendLine("You are a Perspective Taker. You have no assigned task.")
            appendLine("Read other agents' Death Notes, recent staging output, and coordination signals.")
            appendLine("Surface missing viewpoints, stakeholder blind spots, and perspective mismatches.")
            appendLine("Summarise what the group is not noticing, not just what it is doing.")
            appendLine()
        }

        // 7. Legacy Protocol (privileged context from previous generation's elite agents)
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

        // 8. Operating norms — prefer dynamically-evolved norms from KB, fall back to universal defaults
        appendLine("=== OPERATING NORMS ===")
        val norms = bootContext.dynamicNorms?.takeIf { it.isNotBlank() } ?: UNIVERSAL_NORMS
        appendLine(norms)
        appendLine()
        appendLine("=== SUBSTRATE CONTRIBUTION CONTRACT ===")
        appendLine(buildSubstrateContributionContract(bootContext))
        appendLine()
        appendLine("=== TOOLS REMINDER ===")
        if (bootContext.workerView == null) {
            appendLine("- claim_task(nodeId?): Claim a WiP task to work on - FREE")
        } else {
            appendLine(
                "- Your boot-time Atlas assignment or no-task mandate is immutable for this " +
                    "lifecycle. No task-claim capability is available."
            )
        }
        appendLine(TOOLS_REMINDER)
    }

    private fun buildSubstrateContributionContract(bootContext: AgentBootContext): String = buildString {
        appendLine("- Before stopping, make one durable contribution or a structured abstention.")
        appendLine("- Read-only calls such as list_kb, read_kb, search_kb, read_kg, read_run_artifact, list_tools, read_messages, or request_source_access are context gathering only; they do not satisfy this contract.")
        appendLine("- KB context must be index-first: call list_kb or search_kb to inspect titles/headers and IDs, then call read_kb(entryIds=[...]) only for the entries required by your assigned task.")
        appendLine("- Do not load broad KB bodies into context. If too many KB entries look relevant, write a PLAN_UPDATE with the narrowing criteria before reading more.")
        appendLine("- Never stop with intention text such as \"let me read\", \"I will screen\", or \"now I will search\" after a read-only call; perform the next tool call immediately or write a blocker PLAN_UPDATE.")
        appendLine("- If you make any read-only call and learn nothing useful, write a PLAN_UPDATE explaining the blocker and next query/source to try.")
        appendLine("- If the run has one uploaded artifact and no source-acquisition contract, read the full artifact once, then write candidate-level decisions, extraction notes, or a table-completeness checklist from that artifact instead of repeating artifact discovery.")
        appendLine("- Durable contributions are: write_staging for a DOC/TOOL/PLAN_UPDATE, emit_fact for a KG claim, emit_constraint for schema learning, or execute_tool when a reusable tool already fits.")
        appendLine("- If you infer a novel but unsupported idea, preserve it as structured HYPOTHESIS JSON with write_staging(type=\"HYPOTHESIS\", speculative=true). Include inferredFrom, assumptions, uncertainty, expectedImpact, a falsifiablePrediction, a minimalTestOrArtifact, confidence, sorted provenanceRefs, and a computed hypothesisHash.")
        appendLine("- HYPOTHESIS entries propagate provisionally to successors for testing. They are not accepted evidence, cannot complete an assigned task, and cannot be used as a final receiver recommendation without a separate evidence-bound artifact.")
        appendLine("- If you add a claim, also emit at least one KG fact in the form subject -> relation -> object with domain and provenance.")
        appendLine("- If a repeated parsing, validation, extraction, or transformation step appears, either write_staging(type=\"TOOL\", ...) with a runnable tool proposal or write a PLAN_UPDATE headed TOOL ABSTENTION RATIONALE explaining why no tool is useful.")
        appendLine("- If no trustworthy KG fact can be emitted, write a PLAN_UPDATE headed KG ABSTENTION RATIONALE with the missing evidence or schema blocker.")
        if (bootContext.inputArtifacts.any { it.relativePath.equals("source_acquisition_contract.md", ignoreCase = true) }) {
            appendLine("- This run includes a source acquisition contract. Before proposing product claims, call request_source_access for each new source URL, then use fetch_source only after approval. Persist the URL, extracted signal, and implication with write_staging or emit_fact. If source access or fetches are blocked/fail, persist a PLAN_UPDATE headed SOURCE ACQUISITION BLOCKER with attempted URLs and next source candidates.")
        }
        when (bootContext.role) {
            Role.Researcher -> {
                appendLine("- Researcher-specific: prefer emitting evidence and implication facts, not only prose.")
                appendLine("- Recommended relations: SUPPORTS, CONTRADICTS, IMPLEMENTS, EVIDENCES, RISKS, DEPENDS_ON.")
            }
            Role.Builder -> {
                appendLine("- Builder-specific: prefer reusable tools for validation or artifact inspection; otherwise preserve a no-tool rationale.")
                appendLine("- TOOL submissions must be executable JSON with name, domain, language, inputSchema, outputSchema, non-empty code, and non-empty tests.")
            }
            Role.Critic -> {
                appendLine("- Critic-specific: emit facts for accepted/rejected claims and constraints for repeated failure patterns.")
            }
            Role.Archivist -> {
                appendLine("- Archivist-specific: preserve history while keeping the active KB working set useful.")
                appendLine("- Use list_kb/search_kb to find duplicated, superseded, stale, or uncited old-generation entries; read full bodies only for the entries you will consolidate.")
                appendLine("- Write a DOC or PLAN_UPDATE headed KB ORGANISATION with entries to consolidate, active entries to keep because they are novel or reused, and any stale entries that should remain only as audit history.")
                appendLine("- Do not delete or hide Dead Ends; they document failed approaches and must stay discoverable.")
            }
            Role.Outlier -> {
                appendLine("- Outlier-specific: use propose_novelty_vector for the typed falsifiable proposal before committing NOVELTY_PROPOSAL.")
                appendLine("- Outlier-specific: noveltyType must be exactly one of ${NoveltyType.entries.joinToString(", ") { it.name }}.")
                appendLine("- Outlier-specific: write a PLAN_UPDATE headed NOVEL VECTOR or ASSUMPTION BREAK with one non-obvious screening/checking vector, why ordinary agents may miss it, and concrete candidate/table implications.")
                appendLine("- Outlier-specific: in uploaded-file-only benchmark stages, use adversarial artifact checks such as comparator mismatch, subgroup/outcome ambiguity, hard-negative leakage, duplicate/omitted candidate IDs, and required-table completeness. Do not fetch external sources unless a source acquisition contract is present.")
                appendLine("- Outlier-specific: emit a KG fact linking the novel vector to the assumption it challenges when the evidence is explicit; otherwise write KG ABSTENTION RATIONALE with the missing evidence.")
            }
            else -> Unit
        }
        appendLine("- Death Notes are required but do not replace the contribution/abstention contract.")
    }

    private fun renderWorkerViewContext(view: WorkerView): String = buildString {
        appendLine("=== REDACTED WORKER VIEW ===")
        appendLine("Run: ${view.runId}")
        appendLine("Group: ${view.groupId.value}")
        appendLine("Round: ${view.round}")
        appendLine("Disclosure stage: ${view.stageId}")
        appendLine("Atlas: ${view.visibleAtlasSkeleton.atlasId} v${view.visibleAtlasSkeleton.atlasVersion}")
        appendLine("Worker view commitment: ${view.viewHash}")
        appendLine(
            "Visible graph: ${view.visibleAtlasSkeleton.nodes.size} committed nodes; " +
                "${view.revealedNodes.size} payloads revealed"
        )
        if (view.outlierMandate != null) {
            appendLine()
            appendLine("=== VISIBLE NOVELTY CONNECTION IDS ===")
            appendLine(
                "propose_novelty_vector.affectedVisibleNodeIds must use one or more exact IDs below:"
            )
            view.revealedNodes.sortedBy { it.nodeId.value }.forEach { node ->
                appendLine("- ${node.nodeId.value}: ${node.limitationStatement}")
            }
            appendLine(
                "propose_novelty_vector.noveltyType must be exactly one of: " +
                    NoveltyType.entries.joinToString(", ") { it.name }
            )
            appendLine(
                "Call propose_novelty_vector before write_staging or the death note. If the call " +
                    "returns an error, correct the typed field named by that error and retry."
            )
        }
        view.assignedNode?.let { node ->
            appendLine()
            appendLine("=== ASSIGNED ATLAS NODE ===")
            appendLine("Node ID: ${node.nodeId.value}")
            appendLine("Limitation: ${node.limitationStatement}")
            appendLine("Capability target: ${node.capabilityTarget}")
            appendLine("Atomic question: ${node.atomicQuestion}")
            appendLine("Proof method: ${node.proofContract.method.name}")
            appendLine(
                "Acceptance criteria: ${node.proofContract.acceptanceCriteria.sorted().joinToString("; ")}"
            )
            appendLine(
                "Accepted artifacts: " +
                    node.deliverableContract.acceptedArtifactTypes
                        .sortedBy { it.name }
                        .joinToString(", ") { it.name }
            )
            appendLine("Payload commitment: ${node.payloadHash}")
            node.toolOpportunity?.let { opportunity ->
                val skeleton = view.assignedToolSkeleton
                appendLine()
                appendLine("=== SEALED TOOL/SIMULATION OPPORTUNITY ===")
                appendLine("Opportunity handle: T1")
                appendLine(
                    "T1 is a lifecycle-local capability. The runtime resolves it one-way to the " +
                        "exact assigned node, historical opportunity commitment, schemas, and " +
                        "public tests. Unknown, stale, or foreign handles fail closed."
                )
                appendLine(
                    "Type/mode/behavior: ${opportunity.opportunityType.name} / " +
                        "${opportunity.preferredImplementationMode.name} / ${opportunity.behavior.name}"
                )
                appendLine("Repeated question pattern: ${opportunity.repeatedQuestionPattern}")
                appendLine("Promotion validation strategy: ${opportunity.validationStrategy}")
                appendLine(
                    "Typed inputs: " +
                        opportunity.requiredInputs.joinToString(", ") {
                            "${it.name}:${it.type.name}${if (it.required) "" else "?"}"
                        }
                )
                appendLine(
                    "Typed outputs: " +
                        opportunity.requiredOutputs.joinToString(", ") {
                            "${it.name}:${it.type.name}${if (it.required) "" else "?"}"
                        }
                )
                if (opportunity.preferredImplementationMode ==
                    ToolImplementationMode.FORMULA_DSL
                ) {
                    appendLine("Exact FORMULA_DSL physical dimensions:")
                    (opportunity.requiredInputs + opportunity.requiredOutputs).forEach { field ->
                        val dimension = field.physicalDimension
                        appendLine(
                            "- ${field.name}: " + when {
                                dimension == null -> "UNCOMMITTED"
                                dimension.exponents.isEmpty() -> "dimensionless"
                                else -> dimension.exponents.entries
                                    .sortedBy { it.key }
                                    .joinToString(",") { (name, exponent) ->
                                        "$name=$exponent"
                                    }
                            }
                        )
                    }
                    appendLine(
                        "Reference semantics: " +
                            (opportunity.formulaDslReferenceSemantics?.name ?: "UNCOMMITTED")
                    )
                    appendLine(
                        "The generated read-only contract and editable src/formula.json carry the " +
                            "Council executable authority. Do not transcribe the program into a " +
                            "payload; submit its generated workspace path."
                    )
                }
                appendLine(
                    "Runtime-owned public contract examples, when configured, are loaded from " +
                        "contract/public-tests.json. Private promotion IDs and fixtures are never " +
                        "materialized there. Supplemental cases receive independent runtime-owned " +
                        "public IDs; their count is unrelated to private promotion authority."
                )
                appendLine(
                    "Execution bounds: maximumRuntimeMillis=${opportunity.maximumRuntimeMillis}; " +
                        "maximumOutputBytes=${opportunity.maximumOutputBytes}."
                )
                appendLine(
                    "Every input accepted as valid, including the maximum variable-length horizon, " +
                        "must complete within both execution bounds. Constrain the accepted domain " +
                        "when the worst-case complete output would exceed either bound. Parameters " +
                        "required by sealed validation rules must be required by the implementation; " +
                        "do not silently default them."
                )
                appendLine(
                    "Every accepted decision-relevant input field, including nested values that " +
                        "the implementation parses, must materially affect at least one declared " +
                        "output, or be removed from the accepted input shape. Include sensitivity " +
                        "tests that vary each such input and prove the expected output change; " +
                        "parsing-only and fixed nominal tests are insufficient."
                )
                appendLine(
                    "Expected later-node reuse: " +
                        opportunity.expectedReuseNodeIds.sortedBy { it.value }
                            .joinToString(", ") { it.value }
                )
                opportunity.simulationRecordBindings?.let { bindings ->
                    appendLine(
                        "Simulation evidence fields: summary=${bindings.resultSummaryOutputField}; " +
                            "uncertainty=${bindings.uncertaintyOutputField}; " +
                            "seed=${bindings.randomSeedInputField ?: "none (deterministic)"}"
                    )
                }
                appendLine(
                    "Required runtime language: ${opportunity.preferredImplementationMode.runtimeLanguage}."
                )
                appendLine(
                    "The runtime owns canonical typed-value bytes. It rejects non-finite values, " +
                        "preserves INTEGER versus NUMBER from the sealed schemas, and rewrites " +
                        "public and supplemental test values through the promotion-compatible " +
                        "canonical encoder."
                )
                if (view.assignedToolRepairDiagnostics.isNotEmpty()) {
                    appendLine()
                    appendLine("=== PRIOR TYPED TOOL REPAIR DIAGNOSTICS ===")
                    appendLine(
                        "These delayed, bounded codes belong only to this assigned node and sealed " +
                            "opportunity. Treat them as hypotheses to test, not as hidden fixture values."
                    )
                    view.assignedToolRepairDiagnostics.forEach { diagnostic ->
                        appendLine(
                            "- diagnosticId=${diagnostic.diagnosticId}; " +
                                "createdRound=${diagnostic.createdRound}; " +
                                "source=${diagnostic.source.name}; code=${diagnostic.code.name}; " +
                                "remediationKey=${diagnostic.remediationKey}; " +
                                "affectedPublicField=${diagnostic.affectedPublicField ?: "none"}"
                        )
                    }
                    appendLine(
                        "Your public tests prove local consistency only. Private promotion authority " +
                            "is separate. Private fixtures and exact private outputs are never " +
                            "disclosed. You may receive exact public contract/schema diagnostics, " +
                            "public canonical-number diagnostics, public reference semantics and " +
                            "quantity metadata, and bounded safe semantic repair codes from a " +
                            "previous generation."
                    )
                    val approach = requireNotNull(view.assignedToolRepairApproach)
                    appendLine()
                    appendLine("=== SEALED TOOL REPAIR APPROACH ===")
                    appendLine("Strategy: ${approach.strategy.name}")
                    appendLine("Lineage attempt: ${approach.lineageAttempt}")
                    appendLine("Approach commitment: ${approach.vectorHash}")
                    appendLine(
                        "Use this strategy before producing the candidate; the runtime binds this " +
                            "sealed commitment into candidate and repair-attempt lineage."
                    )
                }
                skeleton?.let { project ->
                    appendLine()
                    appendLine("=== GENERATED IMMUTABLE TOOL WORKSPACE ===")
                    appendLine("Skeleton hash: ${project.skeletonHash}")
                    appendLine(
                        "Opportunity commitment hash: ${project.opportunityCommitmentHash}"
                    )
                    appendLine("Implementation mode: ${project.implementationMode.name}")
                    appendLine("Read-only runtime authority:")
                    appendLine("- contract/tool-contract.json")
                    appendLine("- contract/input-schema.json")
                    appendLine("- contract/output-schema.json")
                    appendLine("- contract/public-tests.json")
                    appendLine("- contract/README.md")
                    appendLine("Editable author files:")
                    appendLine("- ${project.implementationMode.workspaceSourcePath}")
                    appendLine("- tests/author-tests.json (optional supplemental cases)")
                    appendLine("Runtime output:")
                    appendLine("- artifacts/local-results.json")
                    appendLine(
                        "Use tool_workspace(operation=\"LIST\") to inspect declared paths, " +
                            "tool_workspace(operation=\"READ\", relativePath=...) to read the " +
                            "generated contract/source, and tool_workspace(operation=\"WRITE\", " +
                            "relativePath=..., content=...) to replace only AUTHOR_EDITABLE files."
                    )
                    appendLine(
                        "Edit only the generated source and optional author tests. Any edit under " +
                            "contract/ fails exact hash verification before model review."
                    )
                }
                appendLine(
                    "Submit with write_staging(type=\"TOOL\", content=\"\", toolCandidate={...}). " +
                        "toolCandidate must contain opportunityHandle=\"T1\", " +
                        "sourceArtifactPath=\"${opportunity.preferredImplementationMode.workspaceSourcePath}\", " +
                        "entryPoint=\"${opportunity.preferredImplementationMode.workspaceEntryPoint}\", " +
                        "supplementalTestsPath=\"tests/author-tests.json\" when you authored one " +
                        "or more additional public semantic cases, " +
                        "declaredAssumptions, and consumedRepairDiagnosticIds."
                )
                appendLine(
                    "Do not place opportunityId, inputSchema, outputSchema, committed public test " +
                        "IDs, or a hand-built TOOL payload in model output. The runtime reads and " +
                        "content-hashes generated files, resolves T1, and compiles the exact legacy " +
                        "TOOL object consumed by deterministic preflight and private promotion."
                )
                appendLine(
                    "write_staging runs deterministic TOOL wire compilation and preflight. If it " +
                        "returns one wire error, repair that exact workspace/path/value defect and " +
                        "submit a new candidate before ending."
                )
                appendLine(
                    "For constrained DSL implementations, static types are exact: INTEGER and " +
                        "NUMBER are distinct for equality operands and conditional branches. Use " +
                        "decimal literals such as 0.0 for NUMBER branches, and keep every expression " +
                        "within the sealed DSL depth and node-count bounds."
                )
                appendLine(
                    "Literal nodes use the tagged value shape in the generated skeleton: " +
                        "{\"type\":\"literal\",\"value\":{\"type\":\"integer\",\"value\":1}}. " +
                        "Arrays use a nested values list; objects use a nested entries list of " +
                        "{\"key\":\"...\",\"value\":{...}} records. Do not emit an untagged " +
                        "literal value."
                )
                appendLine(
                    "The generated source must contain runnable implementation, not a " +
                        "specification. The executable reads one JSON object from stdin and " +
                        "writes one JSON object matching outputSchema to stdout. The Critic will " +
                        "execute every test before promotion."
                )
                appendLine(
                    "The emitted JSON object must contain exactly outputSchema.properties at the " +
                        "top level because additionalProperties=false. Do not add assumptions, " +
                        "exclusions, diagnostics, units, service limits, or other metadata unless " +
                        "they are explicit requiredOutputs; preserve such explanation in a " +
                        "separate staged document or claim instead."
                )
                appendLine(
                    "Successful execute_tool execution IDs are boundary-generated and automatically " +
                        "attached by commit_contribution. Never invent or manually claim run IDs."
                )
            }
            appendLine()
            appendLine("=== MANDATORY CONTRIBUTION COMMIT ===")
            appendLine(
                "After creating your durable staging/KG/tool evidence, call commit_contribution " +
                    "with nodeId=${node.nodeId.value} and the exact returned submission/claim IDs."
            )
            appendLine(
                "This typed commit is required before your final Death Note. Staging alone does not " +
                    "establish contribution, lease, node-evidence, grading, or capture authority."
            )
            appendLine(
                "Use NO_CONTRIBUTION only when no useful durable evidence was produced; otherwise " +
                    "select the exact positive, validation, integration, reusable-tool, or negative-result type."
            )
        }
        view.outlierMandate?.let { mandate ->
            appendLine()
            appendLine("=== NOVELTY MANDATE ===")
            appendLine(mandate)
        }
        if (view.visibleShadowNodes.isNotEmpty()) {
            appendLine()
            appendLine("=== ACCEPTED SHADOW DISCOVERIES ===")
            appendLine(
                "These remain provisional. Test or adopt them only with independent evidence, " +
                    "then include proposalId in commit_contribution.referencedNoveltyProposalIds."
            )
            view.visibleShadowNodes.forEach { shadow ->
                appendLine("- proposalId=${shadow.proposalId}; type=${shadow.noveltyType.name}")
                appendLine("  vector=${shadow.proposedVector}")
                appendLine("  prediction=${shadow.falsifiablePrediction}")
                appendLine("  minimalTest=${shadow.minimalTestOrArtifact}")
                appendLine("  proposalHash=${shadow.proposalHash}")
            }
        }
        if (view.provisionalHypotheses.isNotEmpty()) {
            appendLine()
            appendLine("=== PROVISIONAL NOVEL HYPOTHESES ===")
            appendLine(
                "These group-local ideas are preserved for testing. They are not accepted " +
                    "evidence or final recommendations. Test them independently before citing " +
                    "any consequence, and preserve falsification or evidence-bound results separately."
            )
            view.provisionalHypotheses.forEach { hypothesis ->
                appendLine(
                    "- hypothesisId=${hypothesis.hypothesisId}; round=${hypothesis.round}; " +
                        "confidence=${hypothesis.confidence}"
                )
                appendLine("  title=${hypothesis.title}")
                appendLine("  idea=${hypothesis.idea}")
                appendLine("  inferredFrom=${hypothesis.inferredFrom}")
                appendLine("  assumptions=${hypothesis.assumptions.joinToString("; ")}")
                appendLine("  uncertainty=${hypothesis.uncertainty}")
                appendLine("  expectedImpact=${hypothesis.expectedImpact}")
                appendLine("  falsifiablePrediction=${hypothesis.falsifiablePrediction}")
                appendLine("  minimalTestOrArtifact=${hypothesis.minimalTestOrArtifact}")
                appendLine("  provenanceRefs=${hypothesis.provenanceRefs.joinToString(",")}")
                appendLine("  hypothesisHash=${hypothesis.hypothesisHash}")
            }
        }
        view.groupFeedback?.let { feedback ->
            appendLine()
            appendLine("=== REDACTED GROUP FEEDBACK ===")
            feedback.evidenceGaps.forEach { appendLine("- Evidence gap: $it") }
            feedback.actionableNextSteps.forEach { appendLine("- Next step: $it") }
        }
        if (view.allowedKnowledgeRefs.isNotEmpty()) {
            appendLine()
            appendLine("=== ALLOWED KNOWLEDGE REFERENCES ===")
            view.allowedKnowledgeRefs.forEach { ref ->
                appendLine("- ${ref.artifactId}: ${ref.label} [${ref.artifactType.name}] ${ref.contentHash}")
            }
        }
        if (view.availableToolCards.isNotEmpty()) {
            view.availableToolCards
                .groupBy { it.executionPurpose }
                .toSortedMap(compareBy(ToolExecutionPurpose::name))
                .forEach { (purpose, cards) ->
                    appendLine()
                    when (purpose) {
                        ToolExecutionPurpose.VALIDATION_TEST -> {
                            appendLine("=== REQUIRED PROMOTED TOOL VALIDATION ===")
                            appendLine(
                                "Every card below is mandatory validation authority for this " +
                                    "assigned node. Call list_tools with its exact toolName and " +
                                    "toolVersion first, then execute_tool with that same exact " +
                                    "name and version on the declared validation cases."
                            )
                            appendLine(
                                "Independent arithmetic, a registry citation, or promotion-test " +
                                    "prose does not validate the active implementation. Only a " +
                                    "successful validation-scoped execute_tool result does."
                            )
                        }
                        ToolExecutionPurpose.AGENT_USE -> {
                            appendLine("=== REQUIRED PROMOTED TOOL REUSE ===")
                            appendLine(
                                "Every card below is mandatory for this assigned node. For each " +
                                    "card, call list_tools with its exact toolName and toolVersion " +
                                    "first, then execute_tool with that same exact name and version."
                            )
                            appendLine(
                                "A prose citation, registry reference, promotion test, or " +
                                    "final-deliverable mention does not satisfy reuse. Only a " +
                                    "successful execute_tool result does."
                            )
                        }
                        ToolExecutionPurpose.PROMOTION_TEST ->
                            error("Worker views cannot expose promotion-test cards")
                    }
                    appendLine(
                        "Include every boundary-generated Execution ID in the final " +
                            "commit_contribution toolRunIds. Missing execution authority makes " +
                            "this node ineligible for an evidence packet or passing grade."
                    )
                    cards.forEach { tool ->
                        appendLine(
                            "- toolId=${tool.toolId}; name=${tool.name}; version=${tool.version}; " +
                                "opportunityId=${tool.opportunityId}; requiredForNodeId=" +
                                "${tool.requiredForNodeId.value}; executionPurpose=" +
                                tool.executionPurpose.name
                        )
                        appendLine("  purpose=${tool.description}")
                        appendLine(
                            "  opportunityCommitmentHash=${tool.opportunityCommitmentHash}; " +
                                "inputSchemaHash=${tool.inputSchemaHash}; " +
                                "outputSchemaHash=${tool.outputSchemaHash}"
                        )
                    }
                }
        }
        if (view.predecessorNotes.isNotEmpty()) {
            appendLine()
            appendLine("=== PREDECESSOR NOTE REFERENCES ===")
            view.predecessorNotes.forEach { note ->
                appendLine("- ${note.noteId}: ${note.summary} [${note.contentHash}]")
            }
        }
        appendLine()
        appendLine(
            "Forbidden visibility classes: " +
                view.forbiddenKnowledgeClasses.sortedBy { it.name }.joinToString(", ") { it.name }
        )
        appendLine("Do not infer or request hidden payloads from their commitments.")
    }

    private val ToolImplementationMode.runtimeLanguage: String
        get() = when (this) {
            ToolImplementationMode.SHELL -> "shell"
            ToolImplementationMode.PYTHON -> "python"
            ToolImplementationMode.FORMULA_DSL,
            ToolImplementationMode.RULE_DSL,
            ToolImplementationMode.SIMULATION_DSL -> "dsl"
        }

    private val ToolImplementationMode.workspaceSourcePath: String
        get() = when (this) {
            ToolImplementationMode.SHELL -> "src/main.sh"
            ToolImplementationMode.PYTHON -> "src/main.py"
            ToolImplementationMode.FORMULA_DSL -> "src/formula.json"
            ToolImplementationMode.RULE_DSL -> "src/rules.json"
            ToolImplementationMode.SIMULATION_DSL -> "src/simulation.json"
        }

    private val ToolImplementationMode.workspaceEntryPoint: String
        get() = when (this) {
            ToolImplementationMode.SHELL -> "main.sh"
            ToolImplementationMode.PYTHON -> "run_tool"
            ToolImplementationMode.FORMULA_DSL -> "formula"
            ToolImplementationMode.RULE_DSL -> "rules"
            ToolImplementationMode.SIMULATION_DSL -> "simulation"
        }

    private fun renderStartingFieldContext(startingField: StartingField, role: Role): String = buildString {
        appendLine("=== ORIGINAL RUN SEED ===")
        appendLine("Run ID: ${startingField.runId}")
        if (startingField.stageContents.isEmpty() || role == Role.MetaPlanner || role == Role.Auditor) {
            appendLine("Objective: ${startingField.objective}")
        } else {
            appendLine("Objective: [hidden by progressive disclosure; follow CURRENT TASK CONTEXT only]")
        }
        appendLine("Group count: ${startingField.groupCount}")
        appendLine("Topology: ${startingField.topologyKind.name} (${startingField.ringTopology.joinToString(" -> ")})")
        appendLine("Generation ceiling: ${startingField.generationCeiling}")
        appendLine("Budget ceiling: ${startingField.globalBudgetCeiling}")
        appendLine("Check-in milestones: ${startingField.checkinMilestones.joinToString(", ")}")
        appendLine("Leak interval: every ${startingField.leakIntervalGenerations} generations")
        appendLine("Dispatch lag: ${startingField.dispatchLagGenerations} generations")
        appendLine("Grader groups: ${startingField.graderGroupCount}")
        appendLine(
            "Seed artefacts: ${startingField.primordialKb.size} KB docs, " +
                "${startingField.primordialKgFacts.size} KG facts, " +
                "${startingField.primordialTools.size} tools, " +
                "${startingField.inputArtifacts.size} input artifacts"
        )
        appendLine(
            "Council outputs: ${startingField.rolePrompts.size} role prompts, " +
                "${startingField.modelAssignment.size} model assignments, " +
                "${startingField.modelPools.size} model pools"
        )
        if (startingField.continuedFromRunId != null) {
            appendLine("Continues from run: ${startingField.continuedFromRunId}")
        }
        if (startingField.stageContents.isNotEmpty()) {
            appendLine("Seeded stage disclosures: ${startingField.stageContents.size}")
        }
        if (startingField.consistencyReport.isNotBlank()) {
            appendLine("Consistency report:")
            appendLine(startingField.consistencyReport.take(1_000))
            if (startingField.consistencyReport.length > 1_000) {
                appendLine("...[truncated]")
            }
        }
        renderTaskOwnershipMap(startingField)?.let { ownershipBlock ->
            appendLine(ownershipBlock)
            appendLine()
        }
        startingField.feedforwardLayer?.let { feedforwardLayer ->
            appendLine("Feedforward layer: ${feedforwardLayer.layerLabel} (index=${feedforwardLayer.layerIndex})")
            if (feedforwardLayer.upstreamRunIds.isNotEmpty()) {
                appendLine("Layer upstream runs: ${feedforwardLayer.upstreamRunIds.joinToString(" -> ")}")
            }
            if (feedforwardLayer.layerObjective.isNotBlank()) {
                appendLine("Layer objective: ${feedforwardLayer.layerObjective}")
            }
        }
    }

    private fun renderTaskOwnershipMap(startingField: StartingField): String? {
        val raw = startingField.deliverableSkeletonTreeJson.trim()
        if (raw.isBlank() || raw == "{}") return null

        val tree = runCatching { json.decodeFromString(DeliverableSkeletonTree.serializer(), raw) }.getOrNull()
            ?: return null
        val summary = tree.summarize()
        return buildString {
            appendLine("=== TASK OWNERSHIP MAP ===")
            appendLine(
                "Ownership clarity: ${"%.2f".format(java.util.Locale.ROOT, summary.ownershipClarityScore)} " +
                    "(unowned=${summary.unownedNodeCount}/${summary.totalNodes})"
            )
            if (summary.owningRoleCounts.isNotEmpty()) {
                appendLine(
                    "Role ownership counts: " +
                        summary.owningRoleCounts.entries.joinToString(", ") { (role, count) -> "$role=$count" }
                )
            }
            tree.nodes.take(8).forEach { node ->
                val owner = node.owningRole ?: DeliverableSkeletonTree.defaultOwningRoleForNodeType(node.nodeType)
                appendLine("- ${node.nodeId}: ${node.title} -> ${owner?.name ?: "UNOWNED"}")
            }
            if (tree.nodes.size > 8) {
                appendLine("...[truncated]")
            }
        }
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
        previousGraderFindings: String? = null,
        foundingBrief: String? = null
    ): String = buildString {
        appendLine(GRADER_ROLE_PROMPTS[graderRole] ?: GRADER_RESEARCHER_PROMPT)
        appendLine()
        appendLine("=== RUN OBJECTIVE ===")
        appendLine(runObjective)
        appendLine()
        foundingBrief?.takeIf { it.isNotBlank() }?.let {
            appendLine("=== GRADER FOUNDING BRIEF ===")
            appendLine(it.take(2_000))
            appendLine()
        }
        appendLine("=== SCORING DIMENSIONS ===")
        appendLine("You must evaluate group '$targetGroupId' on all of these dimensions:")
        appendLine(dimensionDescriptions)
        appendLine()
        appendLine("=== PERSPECTIVE COVERAGE RUBRIC ===")
        appendLine("""
            Score how well the group's work models the relevant stakeholder perspectives.
            Look for explicit acknowledgement of users, operators, maintainers, reviewers,
            and any other objective-relevant parties. Penalise deliverables that overfit a
            single viewpoint or omit obvious blind spots.

            Perspective coverage signals:
            - Does the work distinguish at least 2-3 materially different stakeholder views?
            - Does it explain trade-offs from each important perspective?
            - Does it call out missing viewpoints, constraints, or likely objections?
            - Does it reconcile conflicts instead of flattening them?
        """.trimIndent())
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
              3. how the work advanced or contradicted this group's `GROUP STRATEGY HYPOTHESIS`,
              4. blockers or unresolved items,
              5. what the grader should inspect first.
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
            - list_kb(query?, domain?, maxResults?): inspect your own group's KB titles/headers first
            - read_kb(entryIds?, query?, domain?, maxResults?, includeContent?): load selected KB entries by ID only when needed
            - read_kg(subject?, relation?, obj?, maxResults?): read your own group's KG
            - write_staging(type, content, wipTaskRef?, speculative?): write the round summary
            - write_death_note(summary, whatWorked, whatFailed, recommendations): mandatory final action
        """.trimIndent())
    }

    /**
     * Builds the system prompt for the final standalone deliverable writer.
     *
     * The agent must synthesise a polished HTML5 document from the winning group's shared work product,
     * not from a single agent's freehand narrative, and persist it directly as the run's final downloadable article artifact.
     *
     * @param runObjective The run objective that defines the deliverable.
     * @param roundCount Number of completed rounds in the run.
     * @param groupId Winning group whose substrate should be synthesised.
     * @param validationRequirements Deterministic publication checks the generated file must pass.
     * @return The complete system prompt.
     */
    fun buildFinalDeliverableFile(
        runObjective: String,
        roundCount: Int,
        groupId: String,
        relativePath: String,
        mediaType: String,
        description: String,
        attempt: Int = 1,
        retryReason: String? = null,
        generatedImageAssets: List<GeneratedImageAsset> = emptyList(),
        finalRoundSummary: String? = null,
        validationRequirements: String? = null
    ): String = buildString {
        val normalizedMediaType = mediaType.lowercase()
        val isSystematicReviewPackTarget = relativePath.equals("systematic_review_pack.md", ignoreCase = true) ||
            runObjective.contains("systematic-review reconstruction", ignoreCase = true) ||
            runObjective.contains("included_studies.csv", ignoreCase = true)
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
                - First, optionally inspect the run via at most two quick read tool calls if needed:
                  one read_kb/read_kg call for the strongest group evidence and one read_run_artifact
                  call for source-pack or uploaded context verification.
                - Then write a staging entry with type="DOC".
                - The content must begin with the exact text: <!doctype html>
                - Tool arguments must remain valid JSON: avoid unnecessary backslash escape
                  sequences in CSS/HTML, escape literal backslashes and quotes correctly, and
                  retry with simpler CSS if the tool reports malformed JSON.
                - Keep the single write_staging DOC content below 256KB. If needed, simplify CSS,
                  metadata, and decorative copy before reducing the article's substantive body.
                - Produce a complete HTML document with:
                  1. a strong title,
                  2. a meta description,
                  3. a viewport meta tag,
                  4. embedded <style> rules for polished visual presentation,
                  5. semantic, accessible article markup including a real <footer>,
                  6. a navigable structure with a clear header/hero plus at least 6 substantive sections,
                  7. polished body copy that fulfils the run objective,
                  8. no markdown fences, no commentary outside the HTML.
                - If generated image assets are listed below, integrate the most relevant ones
                  using their exact relative paths in <img src="..."> or <figure> markup.
                  Always include meaningful alt text and captions; do not invent external image URLs.
                - This is the FINAL publication artifact, not a terse summary.
                - Synthesize the strongest ideas that survived across the completed run. Reconcile
                  contradictions, remove filler, and publish the most compelling final version.
                - Make the article feel substantial and publication-ready:
                  - preserve and obey any explicit length, word-count, section-count, or completeness
                    requirement from the run objective; those requirements override this prompt,
                  - for long-form article objectives with no explicit word-count target, aim for
                    roughly 2,500-4,000 visible body words,
                  - include a clear narrative arc, scannable subsections, and at least one callout,
                  - include a concrete CTA or next-step section when appropriate for the objective.
                - Ground the deliverable in the winning group's KB/KG/staging artefacts, the judge outputs, and the run objective.
                - For research, benchmark, strategy, policy, medical-adjacent, legal-adjacent, or
                  evidence-synthesis objectives, enforce source fidelity:
                  1. include a visible "Evidence traceability" or "Source-grounded evidence notes" section,
                  2. tie important claims to source-pack notes, uploaded artifacts, KB facts, or clearly label them as inference,
                  3. do not invent citations, source names, URLs, statistics, or "proven" claims,
                  4. downgrade unsupported implementation ideas to hypotheses or experiment candidates,
                  5. preserve uncertainty and caveats from the source material.
                - Machine-readable evidence contract:
                  1. the run archive will emit final_answer.md, claim_ledger.jsonl,
                     evidence_table.csv, citation_map.json, unknowns_and_contradictions.md,
                     search_trace.jsonl, and cost_report.json,
                  2. make major claims explicit, source-linked, and easy to extract into that ledger,
                  3. include unknowns and contradictions as first-class content, not as hidden caveats,
                  4. optimize for validated claim accumulation rather than polished unsupported prose.
                - After write_staging succeeds, immediately call write_death_note.
                - Do not end the run before write_staging succeeds.
                - Then finish with write_death_note as your final action.
            """.trimIndent())
        } else if (isTier0BenchmarkPredictionTarget(relativePath, mediaType, description, runObjective)) {
            appendLine("""
                You are the Final Deliverable Writer for group '$groupId'.

                Your only job is to produce the benchmark prediction JSON file.

                Output target:
                - Relative path: $relativePath
                - Media type: $mediaType
                - Description: ${description.ifBlank { "Tier-0 benchmark prediction JSON" }}

                Required file body:
                - Output exactly one valid JSON object.
                - The JSON object must contain exactly these fields:
                  1. `final_answer`: concise exact answer string; no explanation.
                  2. `evidence_path`: ordered array of objects with `source` and `claim`.
                  3. `confidence`: number from 0.0 to 1.0.
                - Do not wrap the JSON in markdown fences.
                - Do not add commentary outside the JSON object.
                - Use the generated benchmark source-summary excerpt in the run objective when present.
                - Cite both the source-summary artifact and the underlying source artifact rows in evidence_path.
                - If tools are available, persist this JSON body with write_staging(type="DOC", content=<json>), then write_death_note.
                - If the runtime tells you it is text-only compatibility mode, return the JSON body directly.
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
                - First, optionally inspect the run via at most two quick read tool calls if needed:
                  one read_kb/read_kg call for the strongest group evidence and one read_run_artifact
                  call for source-pack or uploaded context verification.
                  If the strongest KB document is returned in pages, continue it with
                  read_kb(entryIds=[...], offset=...) until you have read it completely; those
                  continuation pages do not count against the two-call limit.
                - When the group already holds a substantial report for this objective, treat it as
                  your baseline: correct, complete, and restructure it to meet the contract, but do
                  not condense away supported content unless an explicit word limit requires it.
                - Then write a staging entry with type="DOC".
                - The DOC content must be the FULL file body only.
                - Do not prepend wrappers, labels, or metadata headers.
                - The runtime already knows the target path and media type; do not restate them in the body.
                - Do not wrap the file body in markdown fences.
                - If the media type is JSON, the body must be valid JSON and should begin with `{` or `[`.
                - If the media type is Markdown or plain text, write production-quality body copy.
                - This is the FINAL publication artifact, not a terse summary.
                - Ground the file in the winning group's KB/KG/staging artefacts, the judge outputs, and the run objective.
                ${if (isSystematicReviewPackTarget) """
                - SYSTEMATIC REVIEW PACK OVERRIDE:
                  1. You must call read_run_artifact(relativePath="metasyn_task_24_candidates.md", listOnly=false)
                     before writing the DOC, even on retries.
                  2. Screen every candidate in that uploaded artifact against the review question,
                     PICO fields, inclusion criteria, and exclusion criteria in the artifact.
                  3. `included_studies.csv`, `excluded_hard_negatives.csv`, and
                     `evidence_table.csv` must contain candidate rows, not header-only tables.
                  4. If group KB/KG/staging conflicts with the uploaded artifact, use the uploaded
                     artifact as source of truth and label conflicting group notes as lower-trust.
                  5. Do not publish "screening incomplete" or empty CSV sections when the uploaded
                     candidate artifact is available; read it and produce candidate-level decisions.
                """.trimIndent() else ""}
                - For research, benchmark, strategy, policy, medical-adjacent, legal-adjacent, or
                  evidence-synthesis objectives, enforce source fidelity:
                  1. include an "Evidence traceability" or "Source-grounded evidence notes" section when the format allows,
                  2. tie important claims to source-pack notes, uploaded artifacts, KB facts, or clearly label them as inference,
                  3. do not invent citations, source names, URLs, statistics, or "proven" claims,
                  4. downgrade unsupported implementation ideas to hypotheses or experiment candidates,
                  5. preserve uncertainty and caveats from the source material.
                - Machine-readable evidence contract:
                  1. the run archive will emit final_answer.md, claim_ledger.jsonl,
                     evidence_table.csv, citation_map.json, unknowns_and_contradictions.md,
                     search_trace.jsonl, and cost_report.json,
                  2. make major claims explicit, source-linked, and easy to extract into that ledger,
                  3. include unknowns and contradictions as first-class content, not as hidden caveats,
                  4. optimize for validated claim accumulation rather than polished unsupported prose.
                - After write_staging succeeds, immediately call write_death_note.
                - Do not end the run before write_staging succeeds.
                - Then finish with write_death_note as your final action.
            """.trimIndent())
        }
        appendLine()
        validationRequirements
            ?.takeIf { it.isNotBlank() }
            ?.let { requirements ->
                appendLine("=== DETERMINISTIC PUBLICATION CONTRACT ===")
                appendLine(requirements.trim())
                appendLine(
                    "The runtime validates these requirements before publication. A violation rejects " +
                        "the file and triggers another writer attempt."
                )
                appendLine()
            }
        if (attempt > 1) {
            appendLine("=== RETRY CONTEXT ===")
            appendLine(
                "This is retry attempt $attempt because a previous final-deliverable writer finished " +
                    "without persisting the required final output file."
            )
            retryReason?.takeIf { it.isNotBlank() }?.let { reason ->
                appendLine("Previous attempt rejection reason: $reason")
            }
            appendLine(
                if (isSystematicReviewPackTarget) {
                    "On this retry, first re-read the uploaded candidate artifact with " +
                        "read_run_artifact(relativePath=\"metasyn_task_24_candidates.md\", listOnly=false), " +
                        "then immediately call write_staging with a complete candidate-row systematic-review pack. " +
                        "Do not end with analysis, intent text, or a plain-text answer."
                } else {
                    "On this retry, do not perform more exploratory reads. Immediately call write_staging " +
                        "with the full final file content using your current context. Do not end with " +
                        "analysis, intent text, or a plain-text answer."
                }
            )
            appendLine()
        }
        appendLine("=== RUN OBJECTIVE ===")
        appendLine(runObjective)
        appendLine()
        appendLine("=== COMPLETED ROUNDS ===")
        appendLine(roundCount.toString())
        appendLine()
        finalRoundSummary
            ?.takeIf { it.isNotBlank() }
            ?.let { summary ->
                appendLine("=== WINNING GROUP FINAL ROUND SUMMARY ===")
                appendLine(summary.trim())
                appendLine()
                appendLine(
                    "Use this final-round summary to reconcile late accepted evidence with the KB; " +
                        "do not silently discard concrete decisions recorded in it."
                )
                appendLine()
            }
        if (generatedImageAssets.isNotEmpty()) {
            appendLine("=== VERIFIED GENERATED IMAGE ASSETS ===")
            generatedImageAssets.forEach { asset ->
                appendLine("- Path: ${asset.relativePath}")
                appendLine("  Media type: ${asset.mediaType}")
                appendLine("  Dimensions: ${asset.width}x${asset.height}")
                appendLine("  Review: ${asset.reviewStatus}")
                appendLine("  Generation prompt: ${asset.prompt}")
            }
            appendLine()
            appendLine(
                "Use these paths exactly as relative browser paths from the final HTML document; " +
                    "for example: <img src=\"${generatedImageAssets.first().relativePath}\" alt=\"...\">."
            )
            appendLine()
        }
        appendLine("=== AVAILABLE TOOLS ===")
        appendLine("""
            - list_kb(query?, domain?, maxResults?): inspect your group's KB titles/headers first
            - read_kb(entryIds?, query?, domain?, maxResults?, includeContent?): load selected KB entries by ID only when needed
            - read_kg(subject?, relation?, obj?, maxResults?): inspect your group's knowledge graph
            - read_run_artifact(relativePath?, listOnly?): inspect uploaded run-start context artifacts and previous-stage pipeline artifacts
            - write_staging(type, content, wipTaskRef?, speculative?): persist the final generated file
            - write_death_note(summary, whatWorked, whatFailed, recommendations): mandatory final action
        """.trimIndent())
    }

    private fun isTier0BenchmarkPredictionTarget(
        relativePath: String,
        mediaType: String,
        description: String,
        runObjective: String
    ): Boolean =
        relativePath.equals("tier0-prediction.json", ignoreCase = true) &&
            mediaType.equals("application/json", ignoreCase = true) &&
            (
                description.contains("Tier-0 benchmark prediction", ignoreCase = true) ||
                    runObjective.contains("Return a single JSON object with exactly these fields", ignoreCase = true)
            )

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
        - list_kb(query?, domain?, maxResults?): Search YOUR OWN grader KB titles/headers for evaluation context
        - read_kb(entryIds?, query?, domain?, maxResults?, includeContent?): Load selected grader KB entries by ID only when needed
        - read_kg(subject?, relation?, obj?, maxResults?): Query YOUR OWN grader KG
        - read_messages(includeBroadcast?, maxResults?, sinceGeneration?): Read intra-grader-group messages
        
        EVALUATION TOOL (free — use for EVERY dimension for EVERY group):
        - submit_grading_score(targetGroupId, dimensionId, rawScore, rationale): Submit a score
          Example: submit_grading_score(targetGroupId="group-1", dimensionId="kb_density", rawScore=65.0, rationale="...")
        
        WRITE TOOLS (cost budget — optional, for methodology documentation):
        - write_staging(type, content, wipTaskRef?, speculative?): Document evaluation methodology
        - emit_fact(subject, relation, obj, confidence, domain, provenance?): Record grading insight to KG
          If you are working on an assigned task, include the task ID as wipTaskRef.
        - send_message(recipientAgentId, message, subject?): Send an intra-grader-group message to an agent ID or "broadcast"
        
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
        previousCriteria: String? = null,
        foundingBrief: String? = null,
        wipTaskDescription: String? = null,
        legacyContext: String? = null,
        graderAtlasContext: String? = null,
        baselineCriteria: String? = null
    ): String = buildString {
        val revisingBaseline = !baselineCriteria.isNullOrBlank()
        when (graderRole) {
            "GraderResearcher" -> appendLine(CRITERIA_RESEARCHER_PROMPT)
            "GraderBuilder" -> appendLine(CRITERIA_BUILDER_PROMPT)
            else -> appendLine(CRITERIA_RESEARCHER_PROMPT)
        }
        appendLine()
        appendLine("=== RUN OBJECTIVE ===")
        appendLine(runObjective)
        appendLine()
        foundingBrief?.takeIf { it.isNotBlank() }?.let {
            appendLine("=== GRADER FOUNDING BRIEF ===")
            appendLine(it.take(2_000))
            appendLine()
        }
        legacyContext?.takeIf { it.isNotBlank() }?.let {
            appendLine("=== ACTIVE GRADER LEGACY PROTOCOL ===")
            appendLine(it.take(2_000))
            appendLine()
        }
        wipTaskDescription?.takeIf { it.isNotBlank() }?.let {
            appendLine("=== CLAIMED GRADER WIP TASK ===")
            appendLine(it)
            appendLine("Your staged criteria must complete this task.")
            appendLine()
        }
        appendLine("=== SCORING DIMENSIONS (develop criteria for ALL of these) ===")
        appendLine(dimensionDescriptions)
        appendLine()
        graderAtlasContext?.takeIf { it.isNotBlank() }?.let {
            appendLine("=== SEALED GRADER METHODOLOGY HANDLE VIEW ===")
            appendLine(it)
            appendLine(
                "Every typed requirement must reference one listed graderNodeHandle, at least " +
                    "one workerNodeHandle allowed by that row, and at least one listed receiver " +
                    "obligation."
            )
            appendLine(
                "The handles are the only model-facing binding authority. Do not invent or emit " +
                    "raw Atlas node IDs, lease IDs, group IDs, labels, or titles as bindings."
            )
            appendLine()
        }
        appendLine("=== PERSPECTIVE COVERAGE RUBRIC ===")
        appendLine("""
            Score how well the eventual judge will be able to model stakeholder perspectives.
            The criteria you write should help the judge distinguish whether a group has
            actually reasoned about users, operators, maintainers, reviewers, and other
            objective-relevant parties or merely mentioned them superficially.

            Perspective coverage signals:
            - Does the rubric require at least 2-3 materially different stakeholder views?
            - Does it reward explicit trade-offs from each important perspective and force
              trade-off reconciliation where perspectives conflict?
            - Does it penalise missing blind spots or one-sided narratives?
            - Does it distinguish concrete stakeholder modeling from generic "consider users" prose?
        """.trimIndent())
        appendLine()
        if (previousCriteria != null) {
            appendLine("=== PRIOR CRITERIA AGENT OUTPUT (refine and extend, do not repeat) ===")
            appendLine(previousCriteria.take(1_500))
            appendLine()
        }
        if (revisingBaseline) {
            appendLine("=== ACCEPTED CRITERIA BASELINE FROM THE PREVIOUS ROUND (start here) ===")
            appendLine(baselineCriteria.orEmpty().take(GRADER_BASELINE_PROMPT_MAX_CHARS))
            appendLine()
        }
        appendLine("=== YOUR TASK ===")
        if (graderRole == "GraderBuilder" && graderAtlasContext != null) {
            if (revisingBaseline) {
                appendLine(
                    "0. FIRST ACTION: call publish_grading_sheet with an improved revision of the " +
                        "baseline typed methodology: keep requirements and tests that still hold, " +
                        "sharpen weak or generic ones, add coverage that failure telemetry or new " +
                        "handles require, and bind only handles from the current matrix. Do this " +
                        "before write_staging, prose analysis, or the death note. If the tool " +
                        "returns an error, correct the exact rejected fields and call it again."
                )
            } else {
                appendLine(
                    "0. FIRST ACTION: call publish_grading_sheet with a valid, objective-specific " +
                        "typed sheet. " +
                        "Do this before write_staging, prose analysis, or the death note. If the tool " +
                        "returns an error, correct the exact rejected fields and call it again."
                )
            }
            appendLine(
                "   Use one concise requirement per sealed workerNodeHandle. Each requirement " +
                    "must map exactly one worker handle and be independently satisfiable from " +
                    "that descriptor's atomic question, proof contract, and deliverable contract. " +
                    "Each requirement and automated test must check a concrete receiver " +
                    "obligation, worker artifact, calculation, safety constraint, evidence trace, " +
                    "or reproducibility property from the objective; a generic keyword-presence " +
                    "test is insufficient."
            )
            appendLine(
                "   Copy graderNodeHandle, mappedWorkerNodeHandles, and " +
                    "mappedReceiverObligationRefs from one sealed handle row. Worker-handle " +
                    "mappings must contain exactly one handle and may not use raw IDs, group IDs, " +
                    "labels, or titles. Cover every listed worker handle across separate " +
                    "requirements."
            )
        }
        if (revisingBaseline) {
            appendLine("1. Treat the accepted baseline as the current rubric: it passed Critic review and graded the previous round.")
            appendLine("   Do not re-derive it from scratch; spend your effort on what should change.")
            appendLine("2. Improve it: keep criteria that still discriminate well; sharpen vague thresholds, weak or")
            appendLine("   keyword-only tests, and deceptive-signal checks; add criteria for gaps exposed by failure")
            appendLine("   telemetry or your claimed task; retire criteria that no longer apply.")
            appendLine("3. Stage exactly ONE consolidated write_staging(type=\"PLAN_UPDATE\", content=...) titled")
            appendLine("   \"CRITERIA REVISION — generation ${roundIndex + 1}\" holding the complete current criteria for")
            appendLine("   all dimensions, marking each item [KEPT], [CHANGED], or [NEW], and listing [RETIRED] items")
            appendLine("   with reasons. Keep LOW/MEDIUM/HIGH/EXCELLENT thresholds, anti-patterns, tie-breakers, and")
            appendLine("   hard-to-fake signals for every dimension. Do not restage unchanged per-dimension documents.")
        } else {
            appendLine("1. Study the run objective and each scoring dimension carefully.")
            appendLine("2. For each dimension, develop deep, adversarial, measurable scoring criteria.")
            appendLine("   Your criteria MUST include all of the following sections:")
            appendLine("   - LOW / MEDIUM / HIGH evidence thresholds")
            appendLine("   - EXCELLENT / EXCEEDS EXPECTATIONS threshold describing what a near-perfect deliverable adds beyond the minimum")
            appendLine("   - concrete observable indicators in KB/KG/staging outputs")
            appendLine("   - anti-patterns that should trigger score penalties")
            appendLine("   - failure modes and deceptive signals that can look good but are weak")
            appendLine("   - tie-breakers for comparing two close groups")
            appendLine("   - at least one hard-to-fake signal of genuine quality")
            appendLine("3. Write your criteria to staging: write_staging(type=\"PLAN_UPDATE\", content=...)")
            appendLine("   Prefer one substantial criteria document per dimension.")
        }
        appendLine("   Do not stop after drafting criteria in plain text. The criteria are invisible to judges unless you write_staging.")
        if (graderRole == "GraderBuilder") {
            appendLine("   Then publish the generation's typed methodology with publish_grading_sheet.")
            appendLine(
                "   It must cover every scoring dimension exactly once and include at least one " +
                    "valid linear-time RE2/J regex test. RE2/J does not support lookaround, " +
                    "backreferences, possessive quantifiers, or atomic groups."
            )
            if (graderAtlasContext != null) {
                appendLine(
                    "   It must also include typed requirements with failure levels, adversarial " +
                        "cases, worker-node/receiver mappings, bounded cost, novelty fingerprints, " +
                        "and hidden-test mappings."
                )
                appendLine(
                    "   Each failure level needs a unique ascending integer level, a non-blank " +
                        "observable description, scoreCeiling in the normalized 0.0..1.0 range, " +
                        "and an explicit critical Boolean."
                )
            }
            appendLine("   The typed sheet is the authoritative rubric/test artifact; prose PLAN_UPDATE documents are supporting analysis.")
        }
        appendLine("4. Your rubric must help distinguish shallow output from robust output.")
        appendLine("   Reward evidence, reproducibility, specificity, and resilience to failure cases.")
        appendLine("5. Round-to-round compounding requirement:")
        appendLine("   - explicitly state what changed from prior criteria when prior criteria are present;")
        appendLine("   - add at least one adversarial, objective-specific check that would catch shallow or incomplete deliverables;")
        appendLine("   - broaden coverage each round across correctness, completeness, evidence fidelity, robustness, edge cases, usability, reproducibility, uncertainty handling, and graceful failure behavior;")
        appendLine("   - define what a perfect or exceeding-expectations deliverable would contain, even if current groups are far below that bar;")
        appendLine("   - for systematic-review/candidate-table tasks, include candidate coverage, table completeness, hard-negative rejection, and evidence-traceability checks.")
        appendLine("6. IMPORTANT: Do NOT score any specific group — you have no group data.")
        appendLine("   Your job is ONLY to develop the rubric that the Round Judge will use.")
        appendLine("7. MANDATORY FINAL ACTION: write_death_note summarising your criteria.")
        appendLine()
        appendLine("=== CRITERIA TOOLS ===")
        appendLine("- list_kb(query?, domain?, maxResults?): Check your own grader KB titles/headers for prior criteria")
        appendLine("- read_kb(entryIds?, query?, domain?, maxResults?, includeContent?): Load selected grader KB entries by ID only when needed")
        appendLine("- write_staging(type, content, wipTaskRef?, speculative?): Publish your criteria")
        appendLine("  Use type=\"PLAN_UPDATE\" for criteria documents.")
        appendLine("- write_death_note(summary, whatWorked, whatFailed, recommendations): MANDATORY last action")
        if (graderRole == "GraderBuilder") {
            appendLine("- publish_grading_sheet(rubricDimensions, automatedTests, failureModes, requirements): publish the typed grader-authored sheet")
        }
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
        nodeEvaluationContexts: List<ExactNodeEvaluationContext> = emptyList(),
        roundIndex: Int,
        foundingBrief: String? = null
    ): String = buildString {
        require(
            nodeEvaluationContexts.all(ExactNodeEvaluationContext::hasValidHash) &&
                nodeEvaluationContexts.map {
                    it.assignment.assignmentId
                }.toSet().size == nodeEvaluationContexts.size
        ) {
            "RoundJudge exact node contexts must be valid and assignment-unique"
        }
        appendLine(ROUND_JUDGE_PROMPT)
        appendLine()
        appendLine("=== RUN OBJECTIVE ===")
        appendLine(runObjective)
        appendLine()
        foundingBrief?.takeIf { it.isNotBlank() }?.let {
            appendLine("=== GRADER FOUNDING BRIEF ===")
            appendLine(it.take(2_000))
            appendLine()
        }
        appendLine("=== SCORING DIMENSIONS ===")
        appendLine(dimensionDescriptions)
        appendLine()
        appendLine("=== GRADING CRITERIA (developed by criteria agents this round) ===")
        appendLine(boundedJudgeCriteria(criteriaContext))
        appendLine()
        appendLine("=== COMPETING GROUP CANON SNAPSHOTS (Round $roundIndex) ===")
        val perSnapshotChars = roundJudgeSnapshotChars(allGroupSnapshots.size)
        for ((groupId, snapshot) in allGroupSnapshots) {
            appendLine("--- GROUP: $groupId ---")
            if (snapshot.length > perSnapshotChars) {
                appendLine(snapshot.take(perSnapshotChars))
                appendLine(
                    "...[snapshot truncated: ${snapshot.length - perSnapshotChars} of " +
                        "${snapshot.length} chars omitted]"
                )
            } else {
                appendLine(snapshot)
            }
            appendLine()
        }
        if (nodeEvaluationContexts.isNotEmpty()) {
            appendLine("=== EXACT NODE EVALUATION CONTEXTS ===")
            nodeEvaluationContexts
                .sortedBy { it.assignment.assignmentId }
                .forEach { context ->
                    appendLine(authorityJson.encodeToString(context))
                    appendLine()
            }
            appendLine(
                "Call submit_node_grade exactly once for every context assignment, passing only " +
                    "assignmentId, verdict, scoreVector, and rationale. The runtime resolves the " +
                    "immutable assignment and binds all packet, node, criteria, capture-policy, and " +
                    "hash commitments server-side. Grade only its " +
                    "complete packet-bound evidence against its sealed node contract and mapped " +
                    "frozen requirements. Generic snapshots, broad criteria, dimensions, and " +
                    "weighted totals are not node-grade evidence."
            )
            appendLine(
                "Do not copy or submit assignmentHash, packetId, packetHash, nodeId, atlasHash, " +
                    "nodePayloadHash, criteriaHash, or capturePolicyHash. " +
                    "The runtime has already validated each immutable assignment and its commitments. " +
                    "Use VALID with a low score and hardFailure=true for weak, incomplete, or failing " +
                    "evidence that remains evaluable. Use ABSTAINED only when the exact frozen authority " +
                    "cannot be evaluated. INVALID is runtime-reserved and cannot be submitted by a judge."
            )
            appendLine(
                "An artifact that says a desired report, validation result, source, or downstream " +
                    "output was unavailable is still evaluable evidence of that limitation. Grade what " +
                    "the submitted artifact actually proves against the frozen requirement; do not copy " +
                    "the artifact's own abstention or unavailability statement into an ABSTAINED verdict."
            )
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
        appendLine("- list_kb(query?, domain?, maxResults?): Query your own grader KB titles/headers first (FREE)")
        appendLine("- read_kb(entryIds?, query?, domain?, maxResults?, includeContent?): Load selected grader KB entries by ID only when needed (FREE)")
        appendLine("- write_death_note(summary, whatWorked, whatFailed, recommendations): MANDATORY last action")
        if (nodeEvaluationContexts.isNotEmpty()) {
            appendLine(
                "- submit_node_grade(assignmentId, verdict, scoreVector, rationale): Submit one " +
                    "runtime-bound packet-grade vector (FREE)"
            )
        }
        appendLine()
        appendLine("IMPORTANT: You MUST call submit_grading_score for every group × dimension combination.")
        appendLine("Do not skip any. If evidence is insufficient, score 50.0 with rationale 'insufficient evidence'.")
        appendLine()
        appendLine("TEXT-ONLY FALLBACK CONTRACT:")
        appendLine("If your backend cannot call tools, return ONLY this strict JSON object:")
        appendLine("""{"scores":[{"targetGroupId":"<group-id>","dimensionId":"<dimension-id>","rawScore":<0-100>,"rationale":"<brief evidence-grounded rationale>"}]}""")
        appendLine("Include exactly one object for every group × dimension combination.")
        appendLine("Do not add prose, markdown, code fences, comments, or additional fields.")
    }

    /**
     * Builds the identity-redacted prompt for one isolated novelty assessment.
     *
     * @param runObjective Receiver objective used for relevance scoring.
     * @param proposal Exact proposal; author and worker-group identity are intentionally omitted.
     * @param roundIndex Source round of the proposal.
     * @return Prompt exposing only the proposal content required for independent assessment.
     */
    fun buildNoveltyAssessor(
        runObjective: String,
        proposal: com.achulkov.evomolior.core.atlas.NoveltyVectorProposal,
        roundIndex: Int
    ): String = buildString {
        appendLine("You are an independent novelty assessor in a competitive Grader society.")
        appendLine("Evaluate one proposal without inferring or requesting its author or worker group.")
        appendLine("You do not receive hidden Atlas content or hidden-map match details.")
        appendLine()
        appendLine("=== RECEIVER OBJECTIVE ===")
        appendLine(runObjective)
        appendLine()
        appendLine("=== REDACTED NOVELTY PROPOSAL (ROUND $roundIndex) ===")
        appendLine("proposalHash=${proposal.proposalHash}")
        appendLine("type=${proposal.noveltyType}")
        appendLine("visibleConnections=${proposal.affectedVisibleNodeIds.map { it.value }.sorted()}")
        appendLine("missingLayerOrAssumption=${proposal.missingLayerOrAssumption}")
        appendLine("proposedVector=${proposal.proposedVector}")
        appendLine("whyCurrentMapMayMissIt=${proposal.whyCurrentMapMayMissIt}")
        appendLine("falsifiablePrediction=${proposal.falsifiablePrediction}")
        appendLine("minimalTestOrArtifact=${proposal.minimalTestOrArtifact}")
        appendLine("expectedReceiverImpact=${proposal.expectedReceiverImpact.orEmpty()}")
        appendLine("expectedCost=${proposal.expectedCost}")
        appendLine("conflicts=${proposal.conflictWithExistingAssumptions}")
        appendLine()
        appendLine("Score novelty, receiver relevance, testability, plausibility, expected impact,")
        appendLine("and cost discipline independently on [0.0, 1.0].")
        appendLine("Do not score downstream adoption before later evidence exists.")
        appendLine("Call submit_novelty_assessment exactly once. Use ABSTAINED when evidence is")
        appendLine("insufficient and INVALID only when the proposal authority itself is malformed.")
        appendLine()
        appendLine("TEXT-ONLY FALLBACK CONTRACT:")
        appendLine("Return only one strict JSON object with these fields:")
        appendLine(
            """{"status":"VALID|ABSTAINED|INVALID","novelty":0.0,"receiverRelevance":0.0,"testability":0.0,"plausibility":0.0,"expectedImpact":0.0,"costDiscipline":0.0,"rationale":"brief rationale"}"""
        )
        appendLine("For ABSTAINED or INVALID, omit every numeric score. Add no other fields.")
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
        - Reads (list_kb, read_kb, search_kb, read_kg) are free. Writes and executions cost budget.
        - Prefer building on existing KB knowledge rather than rediscovering from scratch.
        - Use KB index-first: inspect titles/headers before loading selected full KB entries.
    """.trimIndent()

    private val TOOLS_REMINDER = """
        Available tools — EXACT argument names are shown (must match exactly or calls will fail):

        READ TOOLS (free):
        - list_kb(query?, domain?, maxResults?): List compact KB titles/headers and IDs without full bodies — FREE
          Use this before loading KB bodies.
        - read_kb(entryIds?, query?, domain?, maxResults?, includeContent?): Read selected KB entries by ID; without entryIds returns headers only unless includeContent=true — FREE
          Prefer read_kb(entryIds=[...]) after list_kb/search_kb.
        - search_kb(query?, domain?, maxResults?, includeContent?): Semantic/vector KB search returning headers by default; use read_kb(entryIds=[...]) for selected full entries — costs 0.1
        - read_run_artifact(relativePath?, listOnly?): Read uploaded run-start context artifacts and compact summaries — FREE
        - request_source_access(url, purpose): Request allow/block decision for an external source URL — FREE
          This does not fetch content. Approved hosts are audited and become fetchable in this and later agent spawns.
        - fetch_source(url, purpose): Fetch an already-approved public source URL and return sanitised page text — FREE
          If a link is outside the current allowlist, call request_source_access first. fetch_source will block unapproved hosts.
        - read_kg(subject?, relation?, obj?, maxResults?, minTrustScore?): Query KG typed triples — FREE
          NOTE: use "obj" not "object". Use "*" or "" as wildcard. Returns facts with trust scores.
        - query_pattern(subject?, relation?, obj?, maxResults?, minTrustScore?): Pattern traversal over KG — costs 0.5
        - check_consistency(facts): Preflight-check a batch of fact candidates — FREE
          Call BEFORE emit_fact to catch schema violations early. Input: list of {subject, relation, obj, confidence?}
        - list_tools(domain?, maxResults?): List active registered tools and schemas before execute_tool — FREE
        - read_messages(includeBroadcast?, maxResults?, sinceGeneration?): Read intra-group messages to you or broadcast — FREE
        WRITE TOOLS (cost budget):
        - emit_fact(subject, relation, obj, confidence?, domain?, provenance?): Submit a KG fact — costs 1
          NOTE: use "obj" not "object". Domain is required. Example: domain="science".
        - write_staging(type, content, wipTaskRef?, speculative?): Submit work for Critic review — costs 1
          Submission types: DOC, TOOL, FACT, PLAN_UPDATE, HYPOTHESIS, DISPATCH
          DOC payloads must be valid JSON tool arguments. Escape literal backslashes and quotes;
          avoid CSS/HTML escape tricks that create invalid JSON strings; keep content below 256KB.
          HYPOTHESIS payloads must be strict JSON with status="PROPOSED", speculative=true, exact
          current run/group/agent/round identity, and a valid hypothesisHash. They propagate
          provisionally for later testing but are never accepted receiver evidence.
        - emit_constraint(constraintId, description, appliesToType, condition, violationPenalty?, rationale?): Propose KG constraint — costs 2
          If you are working on an assigned task, include the task ID as wipTaskRef via the staging write path.
        - execute_tool(toolName, argsJson, toolVersion?): Execute a registered tool from list_tools — costs 2-8
          NOTE: argsJson must be a JSON string (e.g. '{"key":"value"}')
        - send_message(recipientAgentId, message, subject?): Send an intra-group message to an agent ID or "broadcast" — costs 0.5
        - commit_contribution(commitType, nodeId?, artifactIds?, claimIds?, citedArtifactIds?, toolRunIds?,
          testsRun?, referencedNoveltyProposalIds?, changedStateSummary, evidenceSummary?, confidence,
          unresolvedRisks?, successorRecommendations?): Commit the typed durable outcome for your
          assigned Atlas node. Use exact IDs returned by write tools. This is required before exit
          for assigned workers and does not itself grade, capture, or close the node.

        DEATH NOTE (free — MANDATORY last action):
        - write_death_note(summary, whatWorked, whatFailed, recommendations, kgHabits?, toolObservations?): Write your Death Note

        IMPORTANT RULES:
        - Read-only tool calls do not count as output. After reading, persist a contribution,
          source-access blocker, or abstention rationale with write_staging/emit_fact/emit_constraint.
        - Use list_kb/search_kb for KB titles/headers first; use read_kb(entryIds=[...]) only for selected entries.
        - For web links outside the current allowlist, call request_source_access before fetch_source.
        - ALWAYS write your Death Note as your final action before stopping.
        - If you have an assigned Atlas node, commit_contribution MUST be the last authority action
          before write_death_note. A write_staging/emit_fact success is not a contribution commit.
        - Dead Ends in KB document failed approaches — read them first.
        - To propose norms: write_staging(type="DOC", content=..., wipTaskRef=null) with domain="system:norms".
        - To build a derivative copy or edited version of an uploaded artifact, use read_run_artifact plus write_staging or execute_tool, then let the normal Critic review path approve it.
        - Budget is finite — never retry the same tool call with identical arguments.
    """.trimIndent()
}
