package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.DeliverableArtifactType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.contract.FeedforwardLayerSpec
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.contract.RunInputArtifactKind
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.contract.WorkerView
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.contract.AtlasSkeletonView
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.FormulaDslReferenceSemantics
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolDslBinaryOperation
import com.achulkov.evomolior.core.atlas.ToolDslExpression
import com.achulkov.evomolior.core.atlas.ToolDslKind
import com.achulkov.evomolior.core.atlas.ToolDslOutput
import com.achulkov.evomolior.core.atlas.ToolDslProgram
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.ToolProjectSkeletonCompiler
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.contract.RevealedNodeCard
import com.achulkov.evomolior.core.contract.WorkerToolCard
import com.achulkov.evomolior.core.contract.WorkerToolRepairDiagnostic
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.repair.ApproachVector
import com.achulkov.evomolior.core.repair.RepairStrategy
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolRepairSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that ordinary agent prompts only include the constraint-framed task context.
 */
class RolePromptBuilderTest {

    @Test
    fun `assigned worker prompt requires typed contribution commit before death note`() {
        val nodeId = NodeId("node-assigned")
        val node = RevealedNodeCard(
            nodeId = nodeId,
            kind = NodeKind.EVIDENCE,
            receiverWhyRef = "receiver evidence need",
            limitationStatement = "Evidence is incomplete.",
            capabilityTarget = "A traceable evidence packet.",
            atomicQuestion = "What evidence resolves the limitation?",
            proofContract = ProofContract(
                method = ProofMethod.RECEIVER_VALIDATION,
                acceptanceCriteria = setOf("Evidence is traceable")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.DELIVERABLE_FRAGMENT)
            ),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.MEDIUM, 2.0),
            toolOpportunity = ToolOpportunity(
                opportunityId = "evidence-calculator",
                opportunityType = ToolOpportunityType.CALCULATION,
                repeatedQuestionPattern = "Calculate an evidence ratio.",
                requiredInputs = listOf(
                    TypedField("count", TypedFieldType.INTEGER, "Evidence count.")
                ),
                requiredOutputs = listOf(
                    TypedField("ratio", TypedFieldType.NUMBER, "Normalized evidence ratio.")
                ),
                validationStrategy = "Run the committed boundary case.",
                expectedReuseNodeIds = setOf(NodeId("node-later")),
                preferredImplementationMode = ToolImplementationMode.PYTHON,
                behavior = ToolBehavior.DETERMINISTIC,
                promotionTestIds = setOf("ratio-boundary"),
                maximumRuntimeMillis = 5_000,
                maximumOutputBytes = 4_096
            ),
            payloadHash = "node-payload-hash"
        )
        val opportunityCommitment = ToolOpportunityCommitment(
            runId = "run-worker",
            atlasHash = "atlas-hash",
            nodeId = nodeId,
            opportunity = requireNotNull(node.toolOpportunity)
        ).withComputedHash()
        val workerView = WorkerView(
            runId = "run-worker",
            groupId = AtlasGroupId("group-worker"),
            round = 1,
            stageId = "stage-0",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = "atlas-worker",
                atlasVersion = 1,
                atlasHash = "atlas-hash",
                rootNodeIds = setOf(node.nodeId),
                nodes = emptyMap()
            ),
            revealedNodes = listOf(node),
            assignedNode = node,
            groupFeedback = null,
            availableToolCards = listOf(
                WorkerToolCard(
                    toolId = "prior-calculator-v2",
                    name = "prior-calculator",
                    version = 2,
                    opportunityId = "prior-calculation",
                    opportunityCommitmentHash = "prior-opportunity-commitment",
                    requiredForNodeId = nodeId,
                    description = "Calculate the exact evidence denominator.",
                    inputSchemaHash = "input-schema-hash",
                    outputSchemaHash = "output-schema-hash"
                )
            ),
            assignedToolSkeleton = ToolProjectSkeletonCompiler.compile(
                opportunityCommitment
            ),
            assignedToolOpportunityCommitmentHash = opportunityCommitment.commitmentHash,
            assignedToolRepairDiagnostics = listOf(
                WorkerToolRepairDiagnostic(
                    diagnosticId = "repair-ratio-tool",
                    runId = "run-worker",
                    groupId = AtlasGroupId("group-worker"),
                    opportunityId = "evidence-calculator",
                    opportunityCommitmentHash = opportunityCommitment.commitmentHash,
                    authoringNodeId = nodeId,
                    source = ToolRepairSource.PRIVATE_PROMOTION_AUTHORITY,
                    code = ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION,
                    createdRound = 0,
                    visibleFromRound = 1,
                    affectedPublicField = "ratio",
                    diagnosticHash = "d".repeat(64)
                )
            ),
            assignedToolRepairApproach = ApproachVector(
                vectorId = "approach-run-worker-group-worker-1",
                runId = "run-worker",
                groupId = "group-worker",
                opportunityId = "evidence-calculator",
                round = 1,
                lineageAttempt = 1,
                strategy = RepairStrategy.DIMENSIONAL_ANALYSIS_FIRST
            ).withComputedHash()
        ).withComputedHash()
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-worker",
                runId = "run-worker",
                groupId = "group-worker",
                role = Role.Researcher,
                rolePrompt = "Research the assigned limitation.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = com.achulkov.evomolior.core.contract.WipTaskClaim(
                    nodeId.value,
                    node.atomicQuestion
                ),
                legacyProtocol = null,
                stageDisclosure = "Use the redacted worker view.",
                modelAssignment = "gpt-test",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1,
                workerView = workerView
            )
        )

        assertContains(prompt, "=== MANDATORY CONTRIBUTION COMMIT ===")
        assertContains(prompt, "call commit_contribution")
        assertContains(prompt, "nodeId=${nodeId.value}")
        assertContains(prompt, "Staging alone does not establish contribution")
        assertContains(prompt, "commit_contribution MUST be the last authority action")
        assertContains(prompt, "Required runtime language: python")
        assertContains(prompt, "Repeated question pattern: Calculate an evidence ratio.")
        assertContains(prompt, "Promotion validation strategy: Run the committed boundary case.")
        assertContains(
            prompt,
            "runtime owns canonical typed-value bytes"
        )
        assertContains(prompt, "Opportunity handle: T1")
        assertContains(prompt, "Unknown, stale, or foreign handles fail closed")
        assertContains(prompt, "contract/public-tests.json")
        assertContains(prompt, "=== GENERATED IMMUTABLE TOOL WORKSPACE ===")
        assertContains(prompt, "Skeleton hash: ${workerView.assignedToolSkeleton?.skeletonHash}")
        assertContains(prompt, "contract/input-schema.json")
        assertContains(prompt, "contract/output-schema.json")
        assertContains(prompt, "src/main.py")
        assertContains(prompt, "tests/author-tests.json")
        assertContains(prompt, "toolCandidate must contain opportunityHandle=\"T1\"")
        assertContains(prompt, "entryPoint=\"run_tool\"")
        assertContains(prompt, "Do not place opportunityId, inputSchema, outputSchema")
        assertContains(prompt, "content-hashes generated files")
        assertContains(prompt, "=== PRIOR TYPED TOOL REPAIR DIAGNOSTICS ===")
        assertContains(prompt, "diagnosticId=repair-ratio-tool")
        assertContains(prompt, "code=POWER_TO_ENERGY_UNIT_CONVERSION")
        assertContains(prompt, "remediationKey=tool_repair.power_to_energy_unit_conversion")
        assertFalse(prompt.contains("ratio-boundary"))
        assertContains(prompt, "Private fixtures and exact private outputs are never disclosed")
        assertContains(prompt, "Treat them as hypotheses")
        assertContains(prompt, "=== SEALED TOOL REPAIR APPROACH ===")
        assertContains(prompt, "Strategy: DIMENSIONAL_ANALYSIS_FIRST")
        assertFalse(prompt.contains("Opportunity ID: evidence-calculator"))
        assertFalse(prompt.contains("Exact generated inputSchema JSON"))
        assertFalse(prompt.contains(workerView.assignedToolSkeleton!!.inputSchemaJson))
        assertFalse(prompt.contains(workerView.assignedToolSkeleton!!.outputSchemaJson))
        assertFalse(prompt.contains("private-fixture-input"))
        assertFalse(prompt.contains("private-expected-output"))
        assertContains(prompt, "exactly outputSchema.properties")
        assertContains(prompt, "additionalProperties=false")
        assertContains(prompt, "Do not add assumptions, exclusions, diagnostics")
        assertContains(prompt, "Every accepted decision-relevant input field, including nested values")
        assertContains(prompt, "sensitivity tests")
        assertContains(prompt, "maximumOutputBytes=4096")
        assertContains(prompt, "maximum variable-length horizon")
        assertContains(prompt, "do not silently default them")
        assertContains(prompt, "one wire error")
        assertContains(prompt, "INTEGER and NUMBER are distinct")
        assertContains(prompt, "decimal literals such as 0.0")
        assertContains(prompt, "Literal nodes use the tagged value shape")
        assertContains(
            prompt,
            "{\"type\":\"literal\",\"value\":{\"type\":\"integer\",\"value\":1}}"
        )
        assertContains(prompt, "boot-time Atlas assignment or no-task mandate is immutable")
        assertContains(prompt, "=== REQUIRED PROMOTED TOOL REUSE ===")
        assertContains(prompt, "list_tools with its exact toolName and toolVersion first")
        assertContains(prompt, "name=prior-calculator; version=2")
        assertContains(prompt, "Only a successful execute_tool result")
        assertContains(prompt, "commit_contribution toolRunIds")
        assertContains(prompt, "inputSchemaHash=input-schema-hash")
        assertFalse(prompt.contains("claim_task"))
    }

    @Test
    fun `validation worker prompt requires exact promoted tool validation authority`() {
        val nodeId = NodeId("node-validation")
        val node = RevealedNodeCard(
            nodeId = nodeId,
            kind = NodeKind.VALIDATION,
            receiverWhyRef = "receiver validation need",
            limitationStatement = "The promoted implementation has not been independently reproduced.",
            capabilityTarget = "An exact independent execution of the promoted implementation.",
            atomicQuestion = "Does the promoted implementation pass the declared validation cases?",
            proofContract = ProofContract(
                method = ProofMethod.REPRODUCTION,
                acceptanceCriteria = setOf("Declared validation cases pass"),
                executableTestIds = setOf("validation-case")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.VALIDATION_REPORT)
            ),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.MEDIUM, 2.0),
            toolOpportunity = null,
            payloadHash = "validation-payload-hash"
        )
        val workerView = WorkerView(
            runId = "run-validation",
            groupId = AtlasGroupId("group-validation"),
            round = 2,
            stageId = "stage-validation",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = "atlas-validation",
                atlasVersion = 1,
                atlasHash = "atlas-validation-hash",
                rootNodeIds = setOf(nodeId),
                nodes = emptyMap()
            ),
            revealedNodes = listOf(node),
            assignedNode = node,
            groupFeedback = null,
            availableToolCards = listOf(
                WorkerToolCard(
                    toolId = "cooling-simulator-v1",
                    name = "cooling-simulator",
                    version = 1,
                    opportunityId = "cooling-simulation",
                    opportunityCommitmentHash = "cooling-opportunity-commitment",
                    requiredForNodeId = nodeId,
                    executionPurpose = ToolExecutionPurpose.VALIDATION_TEST,
                    description = "Reproduce the declared shelter-cooling validation cases.",
                    inputSchemaHash = "cooling-input-schema",
                    outputSchemaHash = "cooling-output-schema"
                )
            )
        ).withComputedHash()

        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-validator",
                runId = "run-validation",
                groupId = "group-validation",
                role = Role.Researcher,
                rolePrompt = "Validate the assigned implementation.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = com.achulkov.evomolior.core.contract.WipTaskClaim(
                    nodeId.value,
                    node.atomicQuestion
                ),
                legacyProtocol = null,
                stageDisclosure = "Use the redacted validation view.",
                modelAssignment = "gpt-test",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 2,
                workerView = workerView
            )
        )

        assertContains(prompt, "=== REQUIRED PROMOTED TOOL VALIDATION ===")
        assertContains(prompt, "name=cooling-simulator; version=1")
        assertContains(prompt, "declared validation cases")
        assertContains(prompt, "Independent arithmetic")
        assertContains(prompt, "promotion-test prose does not validate")
        assertContains(prompt, "commit_contribution toolRunIds")
        assertContains(prompt, "executionPurpose=VALIDATION_TEST")
        assertFalse(prompt.contains("=== REQUIRED PROMOTED TOOL REUSE ==="))
    }

    @Test
    fun `formula worker prompt exposes sealed dimensions and Council reference semantics`() {
        val nodeId = NodeId("node-formula")
        val referenceProgram = ToolDslProgram(
            kind = ToolDslKind.FORMULA,
            outputs = listOf(
                ToolDslOutput(
                    "required_power",
                    ToolDslExpression.Binary(
                        operation = ToolDslBinaryOperation.MULTIPLY,
                        left = ToolDslExpression.Binary(
                            operation = ToolDslBinaryOperation.MULTIPLY,
                            left = ToolDslExpression.Input("per_area_coefficient"),
                            right = ToolDslExpression.Input("temperature_delta")
                        ),
                        right = ToolDslExpression.Input("area")
                    )
                )
            )
        )
        val opportunity = ToolOpportunity(
            opportunityId = "cooling-formula",
            opportunityType = ToolOpportunityType.CALCULATION,
            repeatedQuestionPattern = "Calculate required cooling power.",
            requiredInputs = listOf(
                TypedField(
                    "per_area_coefficient",
                    TypedFieldType.NUMBER,
                    "Power per area per temperature.",
                    physicalDimension = PhysicalDimension(
                        mapOf("power" to 1, "length" to -2, "temperature" to -1)
                    )
                ),
                TypedField(
                    "temperature_delta",
                    TypedFieldType.NUMBER,
                    "Temperature difference.",
                    physicalDimension = PhysicalDimension(mapOf("temperature" to 1))
                ),
                TypedField(
                    "area",
                    TypedFieldType.NUMBER,
                    "Shelter floor area.",
                    physicalDimension = PhysicalDimension(mapOf("length" to 2))
                )
            ),
            requiredOutputs = listOf(
                TypedField(
                    "required_power",
                    TypedFieldType.NUMBER,
                    "Required aggregate cooling power.",
                    physicalDimension = PhysicalDimension(mapOf("power" to 1))
                )
            ),
            validationStrategy = "Run the committed exact cooling case.",
            expectedReuseNodeIds = setOf(NodeId("node-later")),
            preferredImplementationMode = ToolImplementationMode.FORMULA_DSL,
            behavior = ToolBehavior.DETERMINISTIC,
            promotionTestIds = setOf("cooling-case"),
            maximumRuntimeMillis = 1_000,
            maximumOutputBytes = 4_096,
            formulaDslReferenceSemantics =
                FormulaDslReferenceSemantics.DIMENSIONAL_ANALYSIS_V1,
            formulaDslReferenceProgram = referenceProgram
        )
        val node = RevealedNodeCard(
            nodeId = nodeId,
            kind = NodeKind.TOOL,
            receiverWhyRef = "receiver cooling decision",
            limitationStatement = "Cooling power is not reproducibly calculated.",
            capabilityTarget = "A dimensionally valid cooling calculator.",
            atomicQuestion = "What formula satisfies the sealed cooling contract?",
            proofContract = ProofContract(
                method = ProofMethod.EXECUTABLE_TEST,
                acceptanceCriteria = setOf("Committed cooling case passes")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.TOOL)
            ),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.MEDIUM, 2.0),
            toolOpportunity = opportunity,
            payloadHash = "formula-payload"
        )
        val opportunityCommitment = ToolOpportunityCommitment(
            runId = "run-formula",
            atlasHash = "atlas-hash",
            nodeId = nodeId,
            opportunity = opportunity
        ).withComputedHash()
        val workerView = WorkerView(
            runId = "run-formula",
            groupId = AtlasGroupId("group-formula"),
            round = 0,
            stageId = "stage-0",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = "atlas-formula",
                atlasVersion = 1,
                atlasHash = "atlas-hash",
                rootNodeIds = setOf(nodeId),
                nodes = emptyMap()
            ),
            revealedNodes = listOf(node),
            assignedNode = node,
            groupFeedback = null,
            assignedToolSkeleton = ToolProjectSkeletonCompiler.compile(
                opportunityCommitment
            ),
            assignedToolOpportunityCommitmentHash = opportunityCommitment.commitmentHash
        ).withComputedHash()

        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-formula",
                runId = "run-formula",
                groupId = "group-formula",
                role = Role.Builder,
                rolePrompt = "Build the assigned formula.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = com.achulkov.evomolior.core.contract.WipTaskClaim(
                    nodeId.value,
                    node.atomicQuestion
                ),
                legacyProtocol = null,
                stageDisclosure = "Use the redacted worker view.",
                modelAssignment = "gpt-test",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 0,
                workerView = workerView
            )
        )

        assertContains(prompt, "Exact FORMULA_DSL physical dimensions")
        assertContains(prompt, "Repeated question pattern: Calculate required cooling power.")
        assertContains(
            prompt,
            "Promotion validation strategy: Run the committed exact cooling case."
        )
        assertContains(
            prompt,
            "per_area_coefficient: length=-2,power=1,temperature=-1"
        )
        assertContains(prompt, "Reference semantics: DIMENSIONAL_ANALYSIS_V1")
        assertContains(prompt, "editable src/formula.json carry the Council executable authority")
        assertContains(prompt, "sourceArtifactPath=\"src/formula.json\"")
        assertContains(prompt, "entryPoint=\"formula\"")
        assertFalse(prompt.contains("Council-authored reference FORMULA_DSL program"))
        assertFalse(prompt.contains("\"field\":\"per_area_coefficient\""))
    }

    @Test
    fun `Outlier prompt exposes typed novelty enum and revealed connection IDs`() {
        val nodeId = NodeId("visible-novelty-node")
        val node = RevealedNodeCard(
            nodeId = nodeId,
            kind = NodeKind.INTEGRATION,
            receiverWhyRef = "visible receiver relationship",
            limitationStatement = "Electrical startup risk is unresolved.",
            capabilityTarget = "A bounded electrical feasibility decision.",
            atomicQuestion = "What startup evidence changes feasibility?",
            proofContract = ProofContract(
                method = ProofMethod.RECEIVER_VALIDATION,
                acceptanceCriteria = setOf("Startup risk is tested")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.DELIVERABLE_FRAGMENT)
            ),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.MEDIUM, 2.0),
            toolOpportunity = null,
            payloadHash = "visible-payload"
        )
        val view = WorkerView(
            runId = "run-outlier",
            groupId = AtlasGroupId("group-outlier"),
            round = 0,
            stageId = "stage-0",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = "atlas-outlier",
                atlasVersion = 1,
                atlasHash = "atlas-hash",
                rootNodeIds = setOf(nodeId),
                nodes = emptyMap()
            ),
            revealedNodes = listOf(node),
            assignedNode = null,
            groupFeedback = null,
            outlierMandate = "Propose one connected falsifiable novelty."
        ).withComputedHash()

        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "outlier-1",
                runId = view.runId,
                groupId = view.groupId.value,
                role = Role.Outlier,
                rolePrompt = "Find a bounded novelty.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Use only the redacted worker view.",
                modelAssignment = "gpt-test",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 0,
                workerView = view
            )
        )

        assertContains(prompt, "=== VISIBLE NOVELTY CONNECTION IDS ===")
        assertContains(prompt, nodeId.value)
        assertContains(prompt, "HIDDEN_FAILURE_MODE")
        assertContains(prompt, "Call propose_novelty_vector before write_staging")
    }

    @Test
    fun `round judge text-only contract matches strict grader score parser schema`() {
        val prompt = RolePromptBuilder.buildRoundJudge(
            runObjective = "Rank two submissions.",
            dimensionDescriptions = "  • [accuracy] Accuracy (weight=1.00): Evidence-backed accuracy.",
            criteriaContext = "Use source fidelity and concrete deliverables.",
            allGroupSnapshots = mapOf("group-0" to "Promoted evidence document."),
            roundIndex = 0
        )

        assertContains(prompt, """{"scores":[{"targetGroupId":""")
        assertContains(prompt, "Include exactly one object for every group × dimension combination.")
        assertContains(prompt, "Do not add prose, markdown, code fences, comments, or additional fields.")
        assertFalse(prompt.contains("GRADING_SCORE targetGroupId="))
    }

    @Test
    fun `round judge prompt carries substantial snapshot evidence within a shared budget`() {
        val reportBody = "Regional terrain inventory row. ".repeat(900) // ~28.8k chars
        val snapshot = "=== CANON SNAPSHOT ===\n$reportBody\nFINAL-SECTION-MARKER\n=== END SNAPSHOT ==="
        val single = RolePromptBuilder.buildRoundJudge(
            runObjective = "Rank the submission.",
            dimensionDescriptions = "  • [evidence] Evidence (weight=1.00): Evidence quality.",
            criteriaContext = "Score the full report.",
            allGroupSnapshots = mapOf("group-0" to snapshot),
            roundIndex = 0
        )
        // The former flat 2,000-character cut hid everything after the first document lines.
        assertContains(single, "FINAL-SECTION-MARKER")
        assertFalse(single.contains("snapshot truncated"))

        val oversized = "x".repeat(RolePromptBuilder.ROUND_JUDGE_SNAPSHOT_BUDGET_CHARS + 500)
        val manyGroups = (0 until 12).associate { "group-$it" to oversized }
        val crowded = RolePromptBuilder.buildRoundJudge(
            runObjective = "Rank twelve submissions.",
            dimensionDescriptions = "  • [evidence] Evidence (weight=1.00): Evidence quality.",
            criteriaContext = "Score each group.",
            allGroupSnapshots = manyGroups,
            roundIndex = 0
        )
        val perSnapshot = RolePromptBuilder.roundJudgeSnapshotChars(12)
        assertEquals(RolePromptBuilder.ROUND_JUDGE_MIN_SNAPSHOT_CHARS, perSnapshot)
        assertContains(crowded, "snapshot truncated: ${oversized.length - perSnapshot} of ${oversized.length} chars omitted")
    }

    @Test
    fun `final html deliverable prompt requires json safe bounded semantic output`() {
        val prompt = RolePromptBuilder.buildFinalDeliverableFile(
            runObjective = "Publish a long-form browser article",
            roundCount = 1,
            groupId = "group-0",
            relativePath = "final-article.html",
            mediaType = "text/html",
            description = "Publication article",
            finalRoundSummary = "Accepted late evidence: include corpus_id 7016."
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
        assertContains(prompt, "=== WINNING GROUP FINAL ROUND SUMMARY ===")
        assertContains(prompt, "include corpus_id 7016")
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
        assertContains(prompt, "=== SUBSTRATE CONTRIBUTION CONTRACT ===")
        assertContains(prompt, "KG ABSTENTION RATIONALE")
        assertContains(prompt, "TOOL ABSTENTION RATIONALE")
        assertContains(prompt, "Never stop with intention text")
        assertContains(prompt, "candidate-level decisions")
        assertContains(prompt, "KB context must be index-first")
        assertContains(prompt, "list_kb")
        assertContains(prompt, "read_kb(entryIds=[...])")
        assertContains(prompt, "list_tools")
        assertContains(prompt, "search_kb(query?, domain?, maxResults?, includeContent?)")
        assertContains(prompt, "Researcher-specific: prefer emitting evidence and implication facts")
        assertFalse(prompt.contains("goalFramedContent", ignoreCase = true))
        assertFalse(prompt.contains("=== GOAL", ignoreCase = true))
    }

    @Test
    fun `source acquisition contract makes fetch source operational in agent prompt`() {
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-source-contract",
                runId = "run-source-contract",
                groupId = "group-0",
                role = Role.Researcher,
                rolePrompt = "You are a Researcher.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                inputArtifacts = listOf(
                    RunInputArtifact(
                        relativePath = "source_acquisition_contract.md",
                        sourceName = "source_acquisition_contract.md",
                        mimeType = "text/markdown",
                        sizeBytes = 42,
                        sha256 = "abc123",
                        kind = RunInputArtifactKind.TEXT,
                        textPreview = "Agents must acquire public source evidence during the run."
                    )
                ),
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Find product opportunities from public evidence.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1,
                startingField = StartingField.default(
                    objective = "Find micro-SaaS ideas from public evidence",
                    runId = "run-source-contract",
                    groupCount = 1
                )
            )
        )

        assertContains(prompt, "This run includes a source acquisition contract")
        assertContains(prompt, "request_source_access")
        assertContains(prompt, "use fetch_source only after approval")
        assertContains(prompt, "SOURCE ACQUISITION BLOCKER")
        assertContains(prompt, "fetch_source(url, purpose)")
    }

    @Test
    fun `archivist prompt requires KB working set organisation`() {
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-archivist",
                runId = "run-archivist",
                groupId = "group-0",
                role = Role.Archivist,
                rolePrompt = "You are an Archivist.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Round 3: organise the group substrate.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 3
            )
        )

        assertContains(prompt, "Archivist-specific")
        assertContains(prompt, "active KB working set")
        assertContains(prompt, "novel or reused")
        assertContains(prompt, "KB ORGANISATION")
        assertContains(prompt, "Do not delete or hide Dead Ends")
    }

    @Test
    fun `outlier prompt requires durable novel vectors and benchmark-safe adversarial checks`() {
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-outlier",
                runId = "run-outlier",
                groupId = "group-0",
                role = Role.Outlier,
                rolePrompt = "You are an Outlier.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Use only the uploaded candidate file.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1,
                startingField = StartingField.default(
                    objective = "Produce a systematic review pack from uploaded candidates",
                    runId = "run-outlier",
                    groupCount = 1
                )
            )
        )

        assertContains(prompt, "NOVEL VECTOR")
        assertContains(prompt, "ASSUMPTION BREAK")
        assertContains(prompt, "uploaded-file-only benchmark stages")
        assertContains(prompt, "required-table completeness")
        assertContains(prompt, "Do not fetch external sources unless a source acquisition contract is present")
    }

    @Test
    fun `ordinary progressive-disclosure prompt hides original objective from run seed`() {
        val hiddenObjective = "FIND THE HIDDEN TARGET OBJECT IN PHASE 2"
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-hidden-objective",
                runId = "run-hidden-objective",
                groupId = "group-0",
                role = Role.Researcher,
                rolePrompt = "You are a Researcher.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Constraint: inspect only the visible clue set.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1,
                startingField = StartingField.default(
                    objective = hiddenObjective,
                    runId = "run-hidden-objective",
                    groupCount = 2
                ).copy(stageContents = listOf("Stage 0 constraints", "Stage 1 constraints"))
            )
        )

        assertContains(prompt, "Constraint: inspect only the visible clue set.")
        assertContains(prompt, "Objective: [hidden by progressive disclosure")
        assertFalse(prompt.contains(hiddenObjective, ignoreCase = true))
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
                        com.achulkov.evomolior.core.contract.RunGraphEdge(
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
        assertContains(prompt, "claim_ledger.jsonl")
        assertContains(prompt, "unknowns_and_contradictions.md")
        assertContains(prompt, "validated claim accumulation")
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
        assertContains(prompt, "claim_ledger.jsonl")
        assertContains(prompt, "cost_report.json")
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

    @Test
    fun `grader criteria prompt requires broad expanding excellence criteria`() {
        val prompt = RolePromptBuilder.buildGraderCriteria(
            graderRole = Role.GraderResearcher.name,
            runObjective = "Produce a systematic review pack from candidate tables.",
            dimensionDescriptions = "  • [accuracy] Accuracy (weight=1.00): reward grounded evaluation.",
            roundIndex = 2,
            previousCriteria = "Prior criteria focused on basic correctness only.",
            foundingBrief = null
        )

        assertContains(prompt, "EXCELLENT / EXCEEDS EXPECTATIONS")
        assertContains(prompt, "broaden coverage each round")
        assertContains(prompt, "correctness, completeness, evidence fidelity, robustness, edge cases")
        assertContains(prompt, "perfect or exceeding-expectations deliverable")
        assertContains(prompt, "candidate coverage")
        assertContains(prompt, "table completeness")
    }

    @Test
    fun `compiled grader atlas builder prompt requires typed publication as first action`() {
        val prompt = RolePromptBuilder.buildGraderCriteria(
            graderRole = Role.GraderBuilder.name,
            runObjective = "Evaluate a municipal shelter cooling plan.",
            dimensionDescriptions = "  • [accuracy] Accuracy",
            roundIndex = 0,
            graderAtlasContext =
                """{"rows":[{"graderNodeHandle":"g_abcd_1234","workerNodeHandles":["w_abcd_5678"]}]}"""
        )

        assertContains(prompt, "0. FIRST ACTION: call publish_grading_sheet")
        assertContains(prompt, "before write_staging, prose analysis, or the death note")
        assertContains(prompt, "correct the exact rejected fields and call it again")
        assertContains(prompt, "a generic keyword-presence test is insufficient")
        assertContains(prompt, "graderNodeHandle")
        assertContains(prompt, "mappedWorkerNodeHandles")
        assertContains(prompt, "Do not invent or emit raw Atlas node IDs")
    }
}
