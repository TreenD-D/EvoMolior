package com.achulkov.evokammer.agents.factory

import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.contract.DeliverableArtifactType
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNode
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evokammer.core.contract.DeliverableSkeletonTree
import com.achulkov.evokammer.core.contract.FeedforwardLayerSpec
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.Role
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that ordinary agent prompts only include the constraint-framed task context.
 */
class RolePromptBuilderTest {

    @Test
    fun `final html deliverable prompt requires json safe bounded semantic output`() {
        val prompt = RolePromptBuilder.buildFinalDeliverableFile(
            runObjective = "Publish a long-form browser article",
            roundCount = 1,
            groupId = "group-0",
            relativePath = "final-article.html",
            mediaType = "text/html",
            description = "Publication article"
        )

        assertContains(prompt, "valid JSON")
        assertContains(prompt, "below 256KB")
        assertContains(prompt, "real <footer>")
        assertContains(prompt, "explicit length")
        assertContains(prompt, "2,500-4,000 visible body words")
        assertContains(prompt, "at most two quick read tool calls")
        assertContains(prompt, "Evidence traceability")
        assertContains(prompt, "do not invent citations")
        assertContains(prompt, "label them as inference")
        assertContains(prompt, "write_staging(type=\"DOC\"")
    }

    @Test
    fun `ordinary agent prompt includes task context but no goal-framed section`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val tree = DeliverableSkeletonTree(
            runId = "run-1",
            groupId = "group-0",
            nodes = listOf(
                DeliverableSkeletonNode(
                    nodeId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                    title = "Root",
                    nodeType = DeliverableSkeletonNodeType.ROOT,
                    children = listOf("dst:research", "dst:build"),
                    owningRole = Role.Planner
                ),
                DeliverableSkeletonNode(
                    nodeId = "dst:research",
                    parentId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                    title = "Research",
                    nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION,
                    status = DeliverableSkeletonNodeStatus.IN_PROGRESS,
                    artifactType = DeliverableArtifactType.KB_DOC,
                    owningRole = Role.Researcher
                ),
                DeliverableSkeletonNode(
                    nodeId = "dst:build",
                    parentId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                    title = "Build",
                    nodeType = DeliverableSkeletonNodeType.SOURCE_FILE,
                    status = DeliverableSkeletonNodeStatus.STUB,
                    owningRole = Role.Builder
                )
            )
        )
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-1",
                runId = "run-1",
                groupId = "group-0",
                role = Role.Researcher,
                rolePrompt = "You are a Researcher.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Constraint: do not reveal the target objective.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1,
                startingField = StartingField.default(
                    objective = "Build a shared research brief",
                    runId = "run-1",
                    groupCount = 2
                ).copy(
                    deliverableSkeletonTreeJson = json.encodeToString(
                        DeliverableSkeletonTree.serializer(),
                        tree
                    )
                )
            )
        )

        assertContains(prompt, "=== CURRENT TASK CONTEXT ===")
        assertContains(prompt, "Constraint: do not reveal the target objective.")
        assertContains(prompt, "=== ORIGINAL RUN SEED ===")
        assertContains(prompt, "Objective: Build a shared research brief")
        assertContains(prompt, "=== TASK OWNERSHIP MAP ===")
        assertContains(prompt, "Ownership clarity:")
        assertContains(prompt, "dst:research: Research -> Researcher")
        assertFalse(prompt.contains("goalFramedContent", ignoreCase = true))
        assertFalse(prompt.contains("=== GOAL", ignoreCase = true))
    }


    @Test
    fun `agent prompt includes routed context from a prior run when available`() {
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-route-1",
                runId = "run-route-1",
                groupId = "group-0",
                role = Role.Researcher,
                rolePrompt = "You are a Researcher.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Constraint: route from prior run.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1,
                startingField = StartingField.default(
                    objective = "Continue from a prior run",
                    runId = "run-route-1",
                    groupCount = 2
                ).copy(
                    routedContextSummaries = listOf(
                        "high-level-run-log: winner=group-1",
                        "final-answer-summary: improved conclusion"
                    )
                )
            )
        )

        assertContains(prompt, "=== ROUTED CONTEXT ===")
        assertContains(prompt, "high-level-run-log: winner=group-1")
        assertContains(prompt, "final-answer-summary: improved conclusion")
    }

    @Test
    fun `agent prompt includes run graph ancestry when available`() {
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-graph-1",
                runId = "run-graph-1",
                groupId = "group-0",
                role = Role.Researcher,
                rolePrompt = "You are a Researcher.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Constraint: graph ancestry.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1,
                startingField = StartingField.default(
                    objective = "Trace the ancestry graph",
                    runId = "run-graph-1",
                    groupCount = 2
                ).copy(
                    runGraphEdges = listOf(
                        com.achulkov.evokammer.core.contract.RunGraphEdge(
                            sourceRunId = "run-ancestor",
                            targetRunId = "run-graph-1",
                            edgeType = "continuation",
                            weight = 0.75
                        )
                    )
                )
            )
        )

        assertContains(prompt, "=== RUN GRAPH ===")
        assertContains(prompt, "run-ancestor -[continuation, w=0.75]-> run-graph-1")
    }

    @Test
    fun `agent prompt includes feedforward layer metadata when available`() {
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-layer-1",
                runId = "run-layer-1",
                groupId = "group-0",
                role = Role.Researcher,
                rolePrompt = "You are a Researcher.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Constraint: feedforward layer.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1,
                startingField = StartingField.default(
                    objective = "Layered synthesis",
                    runId = "run-layer-1",
                    groupCount = 2
                ).copy(
                    feedforwardLayer = FeedforwardLayerSpec(
                        layerIndex = 2,
                        layerLabel = "L3",
                        upstreamRunIds = listOf("run-l1", "run-l2"),
                        layerObjective = "Final synthesis"
                    )
                )
            )
        )

        assertContains(prompt, "Feedforward layer: L3 (index=2)")
        assertContains(prompt, "Layer upstream runs: run-l1 -> run-l2")
        assertContains(prompt, "Layer objective: Final synthesis")
    }

    @Test
    fun `ordinary agent prompt never includes hidden goal wording`() {
        val hiddenGoal = "FIND THE TARGET OBJECT IN PHASE 2"
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-2",
                runId = "run-1",
                groupId = "group-0",
                role = Role.Builder,
                rolePrompt = "You are a Builder.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Constraint: operate only on the disclosed stage constraints.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1
            )
        )

        assertContains(prompt, "Constraint: operate only on the disclosed stage constraints.")
        assertFalse(prompt.contains(hiddenGoal, ignoreCase = true))
    }

    @Test
    fun `ordinary agent prompt does not leak goal-framed disclosure content`() {
        val hiddenGoal = "GOAL: identify the hidden artifact location"
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-3",
                runId = "run-1",
                groupId = "group-0",
                role = Role.Researcher,
                rolePrompt = "You are a Researcher.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Constraint: only use disclosed stage constraints.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1
            )
        )

        assertFalse(prompt.contains(hiddenGoal, ignoreCase = true))
        assertFalse(prompt.contains("GOAL:", ignoreCase = true))
        assertContains(prompt, "Constraint: only use disclosed stage constraints.")
    }

    @Test
    fun `round submission summary prompt requires fallback write even when substrate is sparse`() {
        val prompt = RolePromptBuilder.buildRoundSubmissionSummary(
            runObjective = "Create a standalone HTML article for mindvortex.app",
            roundIndex = 0,
            groupId = "group-0",
            attempt = 2
        )

        assertContains(prompt, "If your read tools return little or no evidence, you MUST still write the summary.")
        assertContains(prompt, "Do not stop after read-only tool calls.")
        assertContains(prompt, "If no prior HTML draft exists")
        assertContains(prompt, "=== RETRY CONTEXT ===")
        assertTrue(prompt.contains("attempt 2", ignoreCase = true))
    }

    @Test
    fun `final deliverable prompt requires publication grade html article`() {
        val prompt = RolePromptBuilder.buildFinalDeliverableFile(
            runObjective = "Create a standalone HTML article for mindvortex.app",
            roundCount = 10,
            groupId = "group-0",
            relativePath = "final-article.html",
            mediaType = "text/html",
            description = "Publication article"
        )

        assertContains(prompt, "embedded <style> rules")
        assertContains(prompt, "at least 6 substantive sections")
        assertContains(prompt, "FINAL publication artifact")
        assertContains(prompt, "2,500-4,000 visible body words")
        assertContains(prompt, "CTA or next-step section")
    }

    @Test
    fun `final deliverable prompt supports generic non-html file outputs`() {
        val prompt = RolePromptBuilder.buildFinalDeliverableFile(
            runObjective = "Produce a machine-readable JSON content brief",
            roundCount = 1,
            groupId = "group-0",
            relativePath = "content-brief.json",
            mediaType = "application/json",
            description = "Structured content brief",
            attempt = 2
        )

        assertContains(prompt, "Relative path: content-brief.json")
        assertContains(prompt, "Media type: application/json")
        assertContains(prompt, "FULL file body only")
        assertContains(prompt, "do not restate them in the body")
        assertContains(prompt, "valid JSON")
        assertContains(prompt, "source fidelity")
        assertContains(prompt, "do not invent citations")
        assertContains(prompt, "=== RETRY CONTEXT ===")
    }

    @Test
    fun `patch bundle prompt requires edited files and documentation`() {
        val prompt = RolePromptBuilder.buildPatchBundleFile(
            runObjective = "Produce a machine-readable patch bundle for the code changes",
            roundCount = 2,
            groupId = "group-0",
            relativePath = "self-improvement-patch-bundle.json",
            mediaType = "application/json",
            description = "Machine-readable patch bundle with edited files, diffs, and documentation.",
            attempt = 2
        )

        assertContains(prompt, "Patch Bundle Writer")
        assertContains(prompt, "`changedFiles`")
        assertContains(prompt, "`documentation`")
        assertContains(prompt, "unified diffs")
        assertContains(prompt, "=== RETRY CONTEXT ===")
        assertContains(prompt, "PATCH BUNDLE HINTS")
    }

    @Test
    fun `grader prompt includes a perspective coverage rubric`() {
        val prompt = RolePromptBuilder.buildGrader(
            graderRole = Role.GraderCritic.name,
            runObjective = "Evaluate a multi-stakeholder planning run",
            dimensionDescriptions = "  • [accuracy] Accuracy (weight=1.00): reward grounded evaluation.",
            canonSnapshot = "Canon snapshot body",
            targetGroupId = "group-0",
            graderGroupId = "grader-0",
            roundIndex = 0,
            previousGraderFindings = "Earlier grader findings",
            foundingBrief = "Founding brief for the grader civilisation"
        )

        assertContains(prompt, "=== PERSPECTIVE COVERAGE RUBRIC ===")
        assertContains(prompt, "stakeholder perspectives")
        assertContains(prompt, "blind spots")
        assertContains(prompt, "=== GRADER FOUNDING BRIEF ===")
    }
}
