package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.CaptureMode
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationMode
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.DisclosureStageDraft
import com.achulkov.evomolior.core.council.EcologistOutput
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.GroupFieldDesign
import com.achulkov.evomolior.core.council.GeneralAssertionV1
import com.achulkov.evomolior.core.council.GeneralTestDefinitionV1
import com.achulkov.evomolior.core.council.GeneralTestDefinitionV1Codec
import com.achulkov.evomolior.core.council.GeneralTestTargetV1
import com.achulkov.evomolior.core.council.IncentiveDesignerOutput
import com.achulkov.evomolior.core.council.KgArchitectOutput
import com.achulkov.evomolior.core.council.KgEntityTypeSpec
import com.achulkov.evomolior.core.council.KgRelationTypeSpec
import com.achulkov.evomolior.core.council.LocalLimitationNode
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.ModelEcologistOutput
import com.achulkov.evomolior.core.council.NodeAtomicityAssessment
import com.achulkov.evomolior.core.council.NodeIncentivePolicy
import com.achulkov.evomolior.core.council.PromptArchitectOutput
import com.achulkov.evomolior.core.council.ReceiverContractDraft
import com.achulkov.evomolior.core.council.RoleInformationBoundary
import com.achulkov.evomolior.core.council.RoleModelAssignment
import com.achulkov.evomolior.core.council.SealedTestDraft
import com.achulkov.evomolior.core.council.SeedCuratorOutput
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.council.SynthesisOutput
import com.achulkov.evomolior.core.council.TaskAnatomistOutput
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.council.StartingFieldCompiler
import kotlin.test.assertIs

internal data class CompiledCouncilGeneralTestFixture(
    val testId: String,
    val nodeKey: LocalNodeKey,
    val nodeKind: NodeKind,
    val acceptedArtifactTypes: Set<ArtifactType>,
    val expectedText: String = "",
    val definitionJson: String? = null
)

internal fun compiledCouncilTestFixture(
    runId: String,
    objective: String,
    envelope: ExecutionEnvelope,
    inputArtifacts: List<RunInputArtifact> = emptyList(),
    workerGroupKeys: List<String> = (0 until envelope.requiredWorkerGroupCount.minimum)
        .map { "worker-$it" },
    workerProfiles: List<GroupProfileType> = workerGroupKeys.mapIndexed { index, _ ->
        envelope.permittedProfiles.sortedBy { it.name }[
            index % envelope.permittedProfiles.size
        ]
    },
    topologyOrder: List<String> = workerGroupKeys,
    topologyKind: GroupTopologyKind = GroupTopologyKind.RING,
    generalTests: List<CompiledCouncilGeneralTestFixture> = listOf(
        CompiledCouncilGeneralTestFixture(
            testId = "delivery-test",
            nodeKey = LocalNodeKey("receiver-delivery"),
            nodeKind = NodeKind.DELIVERY,
            acceptedArtifactTypes = setOf(ArtifactType.FINAL_DELIVERABLE),
            expectedText = "receiver-delivery-accepted"
        )
    )
): StartingFieldCompileResult.Valid = assertIs(
    compiledCouncilFixtureResult(
        runId = runId,
        objective = objective,
        envelope = envelope,
        inputArtifacts = inputArtifacts,
        workerGroupKeys = workerGroupKeys,
        workerProfiles = workerProfiles,
        topologyOrder = topologyOrder,
        topologyKind = topologyKind,
        generalTests = generalTests
    )
)

internal fun compiledCouncilFixtureResult(
    runId: String,
    objective: String,
    envelope: ExecutionEnvelope,
    inputArtifacts: List<RunInputArtifact> = emptyList(),
    workerGroupKeys: List<String> = (0 until envelope.requiredWorkerGroupCount.minimum)
        .map { "worker-$it" },
    workerProfiles: List<GroupProfileType> = workerGroupKeys.mapIndexed { index, _ ->
        envelope.permittedProfiles.sortedBy { it.name }[
            index % envelope.permittedProfiles.size
        ]
    },
    topologyOrder: List<String> = workerGroupKeys,
    topologyKind: GroupTopologyKind = GroupTopologyKind.RING,
    generalTests: List<CompiledCouncilGeneralTestFixture>
): StartingFieldCompileResult {
    require(generalTests.isNotEmpty())
    require(generalTests.map(CompiledCouncilGeneralTestFixture::testId).distinct().size == generalTests.size)
    val testsByNode = generalTests.groupBy(CompiledCouncilGeneralTestFixture::nodeKey)
    val nodes = testsByNode.map { (nodeKey, tests) ->
        require(tests.map(CompiledCouncilGeneralTestFixture::nodeKind).distinct().size == 1)
        LocalLimitationNode(
            key = nodeKey,
            kind = tests.first().nodeKind,
            layer = 0,
            receiverObligationKey = "delivery",
            limitationStatement = "No accepted evidence exists for ${nodeKey.value}.",
            capabilityTarget = "Accepted evidence exists for ${nodeKey.value}.",
            atomicQuestion = "Does the final artifact satisfy ${nodeKey.value}?",
            estimatedEffort = 1,
            proofContract = ProofContract(
                method = ProofMethod.EXECUTABLE_TEST,
                acceptanceCriteria = setOf("Every sealed test for this node passes."),
                executableTestIds = tests.mapTo(linkedSetOf()) { it.testId }
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = tests
                    .flatMapTo(linkedSetOf()) { it.acceptedArtifactTypes },
                finalDeliverableRelationship = if (
                    tests.any { ArtifactType.FINAL_DELIVERABLE in it.acceptedArtifactTypes }
                ) {
                    "This node contributes the final deliverable."
                } else {
                    ""
                }
            ),
            stageKey = "stage-zero",
            revealMode = RevealMode.INITIAL,
            replicationPolicy = ReplicationPolicy(
                mode = ReplicationMode.NONE,
                requiredCount = 1
            ),
            capturePolicy = CapturePolicy(CaptureMode.NONE, 0.0, 0.0),
            rewardSpec = RewardSpec(RewardClass.NONE, 0.0)
        )
    }
    val sealedTests = generalTests.map { test ->
        val definition = test.definitionJson ?: GeneralTestDefinitionV1Codec.encode(
            GeneralTestDefinitionV1(
                target = GeneralTestTargetV1.FinalOutputText,
                assertion = GeneralAssertionV1.TextContains(
                    expected = test.expectedText,
                    caseSensitive = false
                )
            )
        )
        SealedTestDraft(
            testKey = test.testId,
            nodeKey = test.nodeKey,
            acceptedArtifactTypes = test.acceptedArtifactTypes,
            testDefinitionJson = definition,
            commitmentHash = CouncilContractHashing.sha256(definition)
        )
    }
    val capturePolicy = CapturePolicy(
        mode = CaptureMode.GRADE_AUTHORITY,
        minimumGrade = 0.7,
        recaptureMargin = 0.05
    )
    val rewardSpec = RewardSpec(
        rewardClass = RewardClass.NONE,
        credits = 0.0
    )
    val modelId = envelope.operatorOverrides["model.${Role.Researcher.name}"]
        ?: envelope.availableModels.first().modelId
    val graderModelId = envelope.operatorOverrides["model.${Role.GraderResearcher.name}"]
        ?: envelope.availableModels.first().modelId
    val output = CouncilRoleOutputBundle(
        taskAnatomist = TaskAnatomistOutput(
            receiverContract = ReceiverContractDraft(
                receiverKey = "operator",
                receiverDescription = "Operator",
                situation = "A receiver needs an evidence-backed deliverable.",
                decisionOrUse = "Use the deliverable to satisfy the objective.",
                whyNeeded = "The result must be receiver-usable.",
                successOutcomes = listOf("The output contract is satisfied."),
                unacceptableOutcomes = listOf("The objective is changed."),
                constraints = listOf("Preserve exact objective bytes."),
                evidenceProfile = EvidenceProfile(
                    minimumIndependentSources = 1,
                    acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE),
                    requirePrimarySources = false
                )
            ),
            receiverObligations = mapOf("delivery" to "Satisfy the final output contract."),
            nodes = nodes,
            rootNodeKeys = nodes.mapTo(linkedSetOf()) { it.key },
            atomicityReport = nodes.map {
                NodeAtomicityAssessment(it.key, true, 1, "One bounded output check.")
            }
        ),
        incentiveDesigner = IncentiveDesignerOutput(
            nodePolicies = nodes.map {
                NodeIncentivePolicy(it.key, capturePolicy, rewardSpec)
            },
            actionCosts = mapOf("WRITE_STAGING" to 1.0),
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "quality",
                    name = "Quality",
                    description = "Receiver-facing quality",
                    weight = 1.0
                )
            ),
            maximumRewardCreditsPerRound = 0.0,
            maximumSharePerGroup = 0.6,
            repeatOwnershipDiminishingFactor = 0.8,
            mandatoryFrontierBonus = 0.15,
            independentValidationCredit = 0.5,
            graderRewardPolicy = "Reward calibrated, evidence-backed judgments."
        ),
        ecologist = EcologistOutput(
            workerGroups = workerGroupKeys.zip(workerProfiles).map { (key, profile) ->
                GroupFieldDesign(key, profile)
            },
            graderGroups = (0 until envelope.requiredGraderGroupCount.minimum).map { index ->
                GroupFieldDesign(
                    groupKey = "grader-$index",
                    profile = workerProfiles[index % workerProfiles.size]
                )
            },
            topologyKind = topologyKind,
            topologyOrder = topologyOrder,
            checkinRounds = listOf(1),
            leakIntervalRounds = 1,
            dispatchLagRounds = 0
        ),
        promptArchitect = PromptArchitectOutput(
            rolePrompts = mapOf(
                Role.Researcher to "Produce bounded evidence for the assigned node.",
                Role.GraderResearcher to "Evaluate evidence independently."
            ),
            informationBoundaries = listOf(
                RoleInformationBoundary(
                    role = Role.Researcher,
                    visibleFields = setOf("currentStage", "assignedNode"),
                    forbiddenFields = setOf("hiddenTests", "futureNodePayloads")
                )
            ),
            outlierNoveltyMandate = "Propose only novel shadow-node opportunities.",
            deathCommitSchemaJson = "{}",
            legacyProtocolTemplateJson = "{}"
        ),
        kgArchitect = KgArchitectOutput(
            schemaVersion = "node-atlas-v1",
            entityTypes = listOf(KgEntityTypeSpec("Claim")),
            relationTypes = listOf(
                KgRelationTypeSpec("SUPPORTS", setOf("Claim"), setOf("Claim"))
            ),
            aliases = mapOf("backs" to "SUPPORTS"),
            claimLedgerSchemaJson = "{}",
            nodeMappings = nodes.associate { it.key to setOf("Claim", "SUPPORTS") }
        ),
        modelEcologist = ModelEcologistOutput(
            assignments = listOf(
                RoleModelAssignment(Role.Researcher, modelId),
                RoleModelAssignment(Role.GraderResearcher, graderModelId)
            ),
            diversityRationale = "Worker and grader execution remain independently grouped.",
            degradedModePolicy = "Fail closed if the assigned model is unavailable."
        ),
        seedCurator = SeedCuratorOutput(
            primordialKb = emptyList(),
            primordialKgFacts = emptyList(),
            primordialTools = emptyList(),
            calibrationArtifacts = emptyList(),
            knownDeadEnds = emptyList(),
            stageZeroContext = listOf("Satisfy the exact receiver contract."),
            foundingNorms = "Prefer auditable evidence and bounded contributions."
        ),
        synthesis = SynthesisOutput(
            stages = listOf(
                DisclosureStageDraft(
                    stageKey = "stage-zero",
                    title = "Initial",
                    nodeKeys = nodes.mapTo(linkedSetOf()) { it.key },
                    mandatoryNodeKeys = nodes.mapTo(linkedSetOf()) { it.key },
                    evidenceCoverageThreshold = 0.0,
                    integrationReadinessThreshold = 0.0
                )
            ),
            sealedTests = sealedTests,
            rubricSchemaVersion = "node-atlas-v1"
        )
    )
    return StartingFieldCompiler().compile(
        runId = runId,
        rawObjective = objective,
        envelope = envelope,
        outputs = output,
        inputArtifacts = inputArtifacts
    )
}
