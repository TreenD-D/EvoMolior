package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.*
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.council.*
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*
class StartingFieldCompilerTest {
    private val compiler = StartingFieldCompiler()
    private val json = Json { ignoreUnknownKeys = false }
    private val coolingSimulatorBoundaryTestId = "cooling-simulator-boundary-test"
    private val coolingSimulatorRepeatabilityTestId = "cooling-simulator-repeatability-test"
    private val coolingSimulatorPromotionTestIds = linkedSetOf(
        coolingSimulatorBoundaryTestId,
        coolingSimulatorRepeatabilityTestId
    )

    @Test
    fun `three distinct objectives compile into deterministic valid sealed fields`() {
        val objectives = listOf(
            "Establish a primary-source market brief.",
            "Design and verify a reproducible parser.",
            "Compare two policy options for a municipal receiver."
        )
        objectives.forEachIndexed { index, objective ->
            val runId = "fixture-$index"
            val first = assertIs<StartingFieldCompileResult.Valid>(
                compiler.compile(runId, objective, envelope(), outputs())
            )
            val second = assertIs<StartingFieldCompileResult.Valid>(
                compiler.compile(runId, objective, envelope(), outputs())
            )
            assertEquals(first, second)
            assertTrue(first.atlas.validate().isValid)
            assertEquals(objective, first.workerField.objective)
            assertEquals(objective, first.graderField.objective)
            assertEquals(first.workerField.seal, first.graderField.seal)
            assertTrue(
                CouncilFieldSealing.verifySeal(
                    first.workerField.seal!!,
                    first.workerField,
                    first.graderField,
                    first.atlas,
                    first.graderAtlas,
                    envelope(),
                    first.report
                )
            )
            assertTrue(first.workerField.resourceNodes.all { it.qualityThreshold in 0.0..1.0 })
            val yieldPolicy = assertNotNull(first.workerField.yieldConstraintPolicy)
            assertTrue(yieldPolicy.hasValidHash())
            assertEquals(10.0, yieldPolicy.totalRoundCap)
            assertEquals(6.0, yieldPolicy.perGroupRoundCap)
            assertEquals(yieldPolicy, first.graderField.yieldConstraintPolicy)
            assertTrue(
                first.workerField.deliverableSkeletonTreeJson.contains(
                    first.atlas.rootNodeIds.single().value
                )
            )
        }
    }

    @Test
    fun `runtime usability smoke compiles one stage with general tests and no tools`() {
        val result = assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile(
                runId = "acceptance-smoke-valid",
                rawObjective = "Objective",
                envelope = acceptanceEnvelope(
                    AcceptanceProfile.RUNTIME_USABILITY_SMOKE
                ),
                outputs = smokeAcceptanceOutputs()
            )
        )

        assertEquals(1, result.atlas.stageDefinitions.size)
        assertTrue(result.atlas.nodes.values.none { it.toolOpportunity != null })
        assertTrue(result.sealedTests.privateSuite.tests.all { it is SealedTestV3.General })
        assertTrue(result.sealedTests.privateSuite.tests.isNotEmpty())
    }

    @Test
    fun `mixed sealed tests retain exact rejected compilation evidence`() {
        val source = outputs()
        val valid = source.synthesis.sealedTests.first().let { test ->
            test.copy(
                testDefinitionJson = "\n  ${test.testDefinitionJson}  \n",
                commitmentHash = CouncilContractHashing.sha256("\n  ${test.testDefinitionJson}  \n")
            )
        }
        val invalid = source.synthesis.sealedTests.last().copy(
            testDefinitionJson = "null",
            commitmentHash = CouncilContractHashing.sha256("null")
        )
        val candidate = source.copy(
            synthesis = source.synthesis.copy(sealedTests = listOf(valid, invalid))
        )

        val result = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(
                runId = "mixed-rejection-evidence",
                rawObjective = "Objective",
                envelope = envelope(),
                outputs = candidate
            )
        )

        assertEquals(2, result.declaredTestCount)
        assertEquals(1, result.compiledTestCount)
        assertEquals(
            mapOf(
                valid.testKey to CouncilContractHashing.sha256(
                    CouncilContractHashing.canonicalJson(
                        json.parseToJsonElement(source.synthesis.sealedTests.first().testDefinitionJson)
                    )
                ),
                invalid.testKey to CouncilContractHashing.sha256(
                    CouncilContractHashing.canonicalJson(json.parseToJsonElement("null"))
                )
            ),
            result.definitionHashesByTestId
        )
        assertTrue(
            result.errors.any {
                it.path.toString() == "synthesis.sealedTests.${invalid.testKey}.definition"
            }
        )
    }

    @Test
    fun `runtime usability smoke rejects tools multiple stages and missing general tests`() {
        val profileEnvelope = acceptanceEnvelope(
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE
        )
        val smoke = smokeAcceptanceOutputs()
        val origin = smoke.taskAnatomist.nodes.first()
        val target = smoke.taskAnatomist.nodes.last()
        val withTool = smoke.copy(
            taskAnatomist = smoke.taskAnatomist.copy(
                nodes = listOf(
                    origin.copy(
                        toolOpportunity = scheduleToolOpportunity(
                            "acceptance-smoke-tool",
                            origin,
                            target
                        )
                    ),
                    target
                )
            )
        )
        val toolFailure = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(
                "acceptance-smoke-tool",
                "Objective",
                profileEnvelope,
                withTool
            )
        )
        assertTrue(
            toolFailure.errors.any { it.message.contains("smoke forbids tool opportunities") },
            toolFailure.errors.joinToString()
        )

        val stagedFailure = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(
                "acceptance-smoke-stages",
                "Objective",
                profileEnvelope,
                withAcceptanceGraderGroups(twoRoundStagedOutputs())
            )
        )
        assertTrue(
            stagedFailure.errors.any { it.message.contains("exactly one disclosure stage") },
            stagedFailure.errors.joinToString()
        )

        val noGeneral = smoke.copy(
            taskAnatomist = smoke.taskAnatomist.copy(
                nodes = smoke.taskAnatomist.nodes.map { node ->
                    node.copy(
                        proofContract = node.proofContract.copy(
                            executableTestIds = emptySet()
                        )
                    )
                }
            ),
            synthesis = smoke.synthesis.copy(sealedTests = emptyList())
        )
        val testFailure = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(
                "acceptance-smoke-tests",
                "Objective",
                profileEnvelope,
                noGeneral
            )
        )
        assertTrue(
            testFailure.errors.any { it.message.contains("GENERAL test declaration") },
            testFailure.errors.joinToString()
        )
    }

    @Test
    fun `acceptance profiles reject every declared model fallback route`() {
        val outputs = smokeAcceptanceOutputs()
        val assignment = outputs.modelEcologist.assignments.first()
        val withFallback = outputs.copy(
            modelEcologist = outputs.modelEcologist.copy(
                assignments = listOf(
                    assignment.copy(fallbackModelIds = listOf("provider/grader"))
                ) + outputs.modelEcologist.assignments.drop(1)
            )
        )

        val invalid = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(
                runId = "acceptance-fallback-route",
                rawObjective = "Objective",
                envelope = acceptanceEnvelope(AcceptanceProfile.RUNTIME_USABILITY_SMOKE),
                outputs = withFallback
            )
        )

        assertTrue(
            invalid.errors.any {
                it.path.toString().endsWith("fallbackModelIds") &&
                    it.message.contains("empty fallback route set")
            },
            invalid.errors.joinToString()
        )
    }

    @Test
    fun `full Appendix B compiles exact six round tool validation reuse and influence topology`() {
        val runId = "appendix-b-full-valid"
        val result = assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile(
                runId = runId,
                rawObjective = "Objective",
                envelope = acceptanceEnvelope(AcceptanceProfile.APPENDIX_B_FULL),
                outputs = fullAcceptanceOutputs(runId)
            )
        )

        assertTrue(result.atlas.stageDefinitions.size >= 2)
        assertTrue(result.atlas.nodes.values.any { it.toolOpportunity != null })
        assertTrue(result.sealedTests.privateSuite.tests.any { it is SealedTestV3.Promotion })
        assertTrue(result.sealedTests.privateSuite.tests.any { it is SealedTestV3.General })
    }

    @Test
    fun `full Appendix B rejects missing opportunity validation and influence authority`() {
        val profileEnvelope = acceptanceEnvelope(AcceptanceProfile.APPENDIX_B_FULL)
        val noOpportunity = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(
                "appendix-b-full-no-tool",
                "Objective",
                profileEnvelope,
                withAcceptanceGraderGroups(twoRoundStagedOutputs())
            )
        )
        assertTrue(
            noOpportunity.errors.any { it.message.contains("requires at least one tool opportunity") },
            noOpportunity.errors.joinToString()
        )

        val runId = "appendix-b-full-no-validation"
        val staged = withAcceptanceGraderGroups(twoRoundStagedOutputs())
        val origin = staged.taskAnatomist.nodes.first()
        val reuse = staged.taskAnatomist.nodes.last()
        val opportunity = scheduleToolOpportunity(runId, origin, reuse)
        val noValidation = withPromotionTestDefinition(
            staged.copy(
                taskAnatomist = staged.taskAnatomist.copy(
                    nodes = listOf(origin.copy(toolOpportunity = opportunity), reuse)
                )
            ),
            origin,
            opportunity,
            evidenceRulePromotionDefinition()
        )
        val validationFailure = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(runId, "Objective", profileEnvelope, noValidation)
        )
        assertTrue(
            validationFailure.errors.any {
                it.message.contains("kind=VALIDATION") &&
                    it.message.contains("both parentKeys and prerequisiteKeys") &&
                    it.message.contains("proofContract.method=REPRODUCTION") &&
                    it.message.contains("toolOpportunity=null")
            },
            validationFailure.errors.joinToString()
        )

        val valid = fullAcceptanceOutputs("appendix-b-full-no-influence")
        val authoringNode = valid.taskAnatomist.nodes.first {
            it.toolOpportunity != null
        }
        val validationNode = valid.taskAnatomist.nodes.first {
            it.kind == NodeKind.VALIDATION
        }
        val validationAuthoringFailure = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(
                "appendix-b-full-validation-authors-tool",
                "Objective",
                profileEnvelope,
                valid.copy(
                    taskAnatomist = valid.taskAnatomist.copy(
                        nodes = valid.taskAnatomist.nodes.map { node ->
                            if (node.key == validationNode.key) {
                                node.copy(
                                    toolOpportunity = requireNotNull(
                                        authoringNode.toolOpportunity
                                    ).copy(
                                        opportunityId = "validation-node-must-not-author"
                                    )
                                )
                            } else {
                                node
                            }
                        }
                    )
                )
            )
        )
        val exactValidationErrors = validationAuthoringFailure.errors.filter {
            it.path.segments == listOf(
                "taskAnatomist",
                "nodes",
                authoringNode.key.value,
                "toolOpportunity",
                "validation"
            ) &&
                it.relatedLocalNodeKeys == setOf(authoringNode.key) &&
                it.message.contains("toolOpportunity=null")
        }
        assertEquals(
            1,
            exactValidationErrors.size,
            validationAuthoringFailure.errors.joinToString()
        )

        val noInfluenceReuse = valid.taskAnatomist.nodes.last().copy(
            kind = NodeKind.KNOWLEDGE,
            deliverableContract = valid.taskAnatomist.nodes.last().deliverableContract.copy(
                acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET),
                finalDeliverableRelationship = ""
            )
        )
        val noInfluence = valid.copy(
            taskAnatomist = valid.taskAnatomist.copy(
                nodes = valid.taskAnatomist.nodes.dropLast(1) + noInfluenceReuse
            )
        )
        val influenceFailure = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(
                "appendix-b-full-no-influence",
                "Objective",
                profileEnvelope,
                noInfluence
            )
        )
        assertTrue(
            influenceFailure.errors.any {
                it.message.contains("must reach later evidence, integration, or final-output authority")
            },
            influenceFailure.errors.joinToString()
        )
    }

    @Test
    fun `receiver contract preserves exact objective whitespace and bytes`() {
        val objective = "  Preserve\tthis objective.\r\nSecond line.  \n"
        val result = assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile("whitespace-run", objective, envelope(), outputs())
        )
        val draft = outputs().taskAnatomist.receiverContract
        val expected = ReceiverContract(
            rawObjective = objective,
            receiverId = CouncilDeterministicIds.create("receiver", draft.receiverKey),
            receiverDescription = draft.receiverDescription,
            situation = draft.situation,
            decisionOrUse = draft.decisionOrUse,
            whyNeeded = draft.whyNeeded,
            successOutcomes = draft.successOutcomes,
            unacceptableOutcomes = draft.unacceptableOutcomes,
            constraints = draft.constraints,
            finalDeliverableContract = envelope().outputSpec,
            evidenceProfile = draft.evidenceProfile
        )
        assertEquals(objective, result.workerField.objective)
        assertTrue(objective.encodeToByteArray().contentEquals(result.workerField.objective.encodeToByteArray()))
        assertEquals(AtlasHashing.hashReceiverContract(expected), result.atlas.receiverContractHash)
    }

    @Test
    fun `incomplete and over-cap rewards fail without repair while incentive policy is authoritative`() {
        val base = outputs()
        val missing = base.copy(
            incentiveDesigner = base.incentiveDesigner.copy(
                nodePolicies = base.incentiveDesigner.nodePolicies.dropLast(1)
            )
        )
        val authoritative = base.copy(
            incentiveDesigner = base.incentiveDesigner.copy(
                nodePolicies = base.incentiveDesigner.nodePolicies.mapIndexed { index, policy ->
                    if (index == 0) policy.copy(
                        capturePolicy = policy.capturePolicy.copy(minimumGrade = 0.8),
                        rewardSpec = policy.rewardSpec.copy(credits = 3.0)
                    )
                    else policy
                }
            )
        )
        val overCap = base.copy(
            incentiveDesigner = base.incentiveDesigner.copy(maximumRewardCreditsPerRound = 4.0)
        )
        assertHasError(missing, FieldCompileErrorCode.INCOMPLETE_POLICY)
        assertHasError(overCap, FieldCompileErrorCode.REWARD_CAP_EXCEEDED)
        val valid = assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile("authoritative-policy", "Objective", envelope(), authoritative)
        )
        val firstNodeId = CouncilDeterministicIds.nodeId(
            "authoritative-policy",
            base.taskAnatomist.nodes.first().key
        )
        assertEquals(0.8, valid.atlas.nodes.getValue(NodeId(firstNodeId)).capturePolicy.minimumGrade)
        assertEquals(3.0, valid.atlas.nodes.getValue(NodeId(firstNodeId)).rewardSpec.credits)
    }

    @Test
    fun `near tie capture margin must remain within normalized score bounds`() {
        val base = outputs()
        val invalid = base.copy(
            incentiveDesigner = base.incentiveDesigner.copy(
                nodePolicies = base.incentiveDesigner.nodePolicies.mapIndexed { index, policy ->
                    if (index == 0) {
                        policy.copy(
                            capturePolicy = policy.capturePolicy.copy(nearTieMargin = 1.01)
                        )
                    } else {
                        policy
                    }
                }
            )
        )

        assertHasError(invalid, FieldCompileErrorCode.INCOMPLETE_POLICY)
    }

    @Test
    fun `non-atomic node and mismatched assessment fail`() {
        val base = outputs()
        val oversizedNode = base.taskAnatomist.nodes.first().copy(estimatedEffort = 4)
        val invalid = base.copy(
            taskAnatomist = base.taskAnatomist.copy(
                nodes = listOf(oversizedNode) + base.taskAnatomist.nodes.drop(1),
                atomicityReport = base.taskAnatomist.atomicityReport.map {
                    if (it.nodeKey == oversizedNode.key) it.copy(estimatedEffort = 4) else it
                }
            )
        )

        assertHasError(invalid, FieldCompileErrorCode.NON_ATOMIC_NODE)
    }

    @Test
    fun `cross-node test identities remain a Task-owned compiler rejection`() {
        val base = outputs()
        val duplicateTestId = "cross-node-duplicate-test"
        val invalid = base.copy(
            taskAnatomist = base.taskAnatomist.copy(
                nodes = base.taskAnatomist.nodes.map { node ->
                    node.copy(
                        proofContract = node.proofContract.copy(
                            executableTestIds = setOf(duplicateTestId)
                        )
                    )
                }
            )
        )

        val result = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile("cross-node-test-id", "Objective", envelope(), invalid)
        )
        val error = result.errors.single {
            it.path.toString() ==
                "taskAnatomist.nodes.executableTestIds.$duplicateTestId"
        }

        assertEquals(FieldCompileErrorCode.MISSING_TEST_COMMITMENT, error.code)
        assertEquals(setOf(Role.CouncilTaskAnatomist), error.responsibleCouncilRoles)
        assertTrue(error.message.contains("declared by multiple nodes"))
        assertTrue(error.message.contains("delivery"))
        assertTrue(error.message.contains("evidence"))
    }

    @Test
    fun `future-stage reference fails with responsible role metadata`() {
        val revised = splitStages(outputs())
        val first = revised.taskAnatomist.nodes.first()
        val futureKey = revised.taskAnatomist.nodes.last().key
        val leaking = revised.copy(
            taskAnatomist = revised.taskAnatomist.copy(
                nodes = listOf(first.copy(alternativeToKeys = setOf(futureKey))) +
                    revised.taskAnatomist.nodes.drop(1)
            )
        )
        val invalid = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile("future-stage", "Objective", envelope(), leaking)
        )
        val error = invalid.errors.first { it.code == FieldCompileErrorCode.FUTURE_STAGE_DISCLOSURE }
        assertTrue(Role.CouncilTaskAnatomist in error.responsibleCouncilRoles)
        assertTrue(Role.CouncilSynthesis in error.responsibleCouncilRoles)
        assertTrue(futureKey in error.relatedLocalNodeKeys)
    }
    @Test
    fun `stage zero worker field contains commitments but no future payload text`() {
        val staged = splitStages(outputs())
        val result = assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile("redacted-future", "Objective", envelope(), staged)
        )
        val futureNode = staged.taskAnatomist.nodes.last()
        val workerJson = json.encodeToString(StartingField.serializer(), result.workerField)
        val graderJson = json.encodeToString(StartingField.serializer(), result.graderField)

        assertFalse(workerJson.contains(futureNode.limitationStatement))
        assertFalse(workerJson.contains(futureNode.capabilityTarget))
        assertFalse(workerJson.contains(futureNode.atomicQuestion))
        assertFalse(workerJson.contains("delivery-accepted"))
        assertTrue(result.workerField.stageContents[1].contains("nodePayloadHashes"))
        assertTrue(graderJson.contains(futureNode.limitationStatement))
        assertTrue(graderJson.contains(futureNode.capabilityTarget))
        assertTrue(graderJson.contains(futureNode.atomicQuestion))
        assertTrue(graderJson.contains("delivery-accepted"))
    }

    @Test
    fun `typed tool opportunity compiles with exact downstream and promotion commitments`() {
        val runId = "tool-opportunity"
        val staged = twoRoundStagedOutputs()
        val origin = staged.taskAnatomist.nodes.first()
        val downstream = staged.taskAnatomist.nodes.last()
        val opportunity = scheduleToolOpportunity(runId, origin, downstream)
        val withOpportunity = withPromotionTestDefinition(
            outputs = staged.copy(
                taskAnatomist = staged.taskAnatomist.copy(
                    nodes = listOf(origin.copy(toolOpportunity = opportunity), downstream)
                )
            ),
            origin = origin,
            opportunity = opportunity,
            definition = evidenceRulePromotionDefinition()
        )

        val result = assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile(runId, "Objective", envelope().copy(maxRounds = 2), withOpportunity)
        )

        val originId = NodeId(CouncilDeterministicIds.nodeId(runId, origin.key))
        assertEquals(opportunity, result.atlas.nodes.getValue(originId).toolOpportunity)
        assertTrue(result.atlas.nodes.getValue(originId).hasValidPayloadHash())
        assertEquals(CompiledFieldState.ACTIVATABLE, result.state)
        assertEquals(result.atlas.atlasHash, result.sealedTests.validationContext.atlasHash)
        assertEquals(
            envelope().copy(maxRounds = 2).outputSpec,
            result.sealedTests.validationContext.finalOutputSpec
        )

        val privateSuite = SealedTestSuiteV3Codec.decodeVerified(
            result.graderField.sealedTestSuiteJson,
            result.sealedTests.validationContext
        )
        val workerView = SealedTestSuiteV3CommitmentViewCodec.decodeVerified(
            result.workerField.sealedTestSuiteJson
        )
        assertEquals(result.sealedTests.privateSuite, privateSuite)
        assertEquals(result.sealedTests.workerCommitmentView, workerView)
        assertTrue(
            SealedTestSuiteV3Authority.verifyCommitmentView(
                workerView,
                privateSuite,
                result.sealedTests.validationContext
            )
        )
        assertEquals(privateSuite.suiteHash, workerView.sourceSuiteHash)
        assertTrue(privateSuite.tests.any { it is SealedTestV3.Promotion })
        assertTrue(privateSuite.tests.any { it is SealedTestV3.General })

        val workerSuiteJson = result.workerField.sealedTestSuiteJson
        assertFalse(workerSuiteJson.contains("\"definition\""))
        assertFalse(workerSuiteJson.contains("\"compiledPlan\""))
        assertFalse(workerSuiteJson.contains("\"input\""))
        assertFalse(workerSuiteJson.contains("\"expectedOutput\""))
        assertFalse(workerSuiteJson.contains("official-source"))
        assertFalse(workerSuiteJson.contains("\"violations\""))
    }

    @Test
    fun `coherent Council formula reference compiles with sealed dimensional semantics`() {
        val runId = "formula-reference"
        val staged = splitStages(outputs())
        val origin = staged.taskAnatomist.nodes.first()
        val downstream = staged.taskAnatomist.nodes.last()
        val opportunity = heatTransferFormulaOpportunity(runId, origin, downstream)
        val withOpportunity = withFormulaReferenceTests(
            staged.copy(
                taskAnatomist = staged.taskAnatomist.copy(
                    nodes = listOf(origin.copy(toolOpportunity = opportunity), downstream)
                )
            ),
            origin = origin,
            opportunity = opportunity,
            expectedValue = "4.0"
        )

        val result = assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile(runId, "Objective", envelope(), withOpportunity)
        )

        val originId = NodeId(CouncilDeterministicIds.nodeId(runId, origin.key))
        assertEquals(opportunity, result.atlas.nodes.getValue(originId).toolOpportunity)
    }

    @Test
    fun `formula sealing rejects integer expected JSON when production runtime emits decimal`() {
        val runId = "formula-reference-exact-json"
        val staged = splitStages(outputs())
        val origin = staged.taskAnatomist.nodes.first()
        val downstream = staged.taskAnatomist.nodes.last()
        val opportunity = heatTransferFormulaOpportunity(runId, origin, downstream)
        val withOpportunity = withFormulaReferenceTests(
            staged.copy(
                taskAnatomist = staged.taskAnatomist.copy(
                    nodes = listOf(origin.copy(toolOpportunity = opportunity), downstream)
                )
            ),
            origin = origin,
            opportunity = opportunity,
            expectedValue = "4"
        )

        val result = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(runId, "Objective", envelope(), withOpportunity)
        )

        assertTrue(
            result.errors.any {
                it.code == FieldCompileErrorCode.INVALID_TOOL_SEMANTICS &&
                    it.path.toString().endsWith(
                        "sealedTests.${opportunity.promotionTestIds.single()}." +
                            "testDefinitionJson"
                    ) &&
                    it.message.contains("expectedOutput={\"heat_loss_coefficient\":4}") &&
                    it.message.contains(
                        "production reference output={\"heat_loss_coefficient\":4.0}"
                    )
            },
            result.errors.joinToString()
        )
    }

    @Test
    fun `formula sealing rejects narrative promotion tests without executable cases`() {
        val runId = "formula-reference-narrative-test"
        val staged = splitStages(outputs())
        val origin = staged.taskAnatomist.nodes.first()
        val downstream = staged.taskAnatomist.nodes.last()
        val opportunity = heatTransferFormulaOpportunity(runId, origin, downstream)
        val withOpportunity = withPromotionTestDefinition(
            outputs = staged.copy(
                taskAnatomist = staged.taskAnatomist.copy(
                    nodes = listOf(origin.copy(toolOpportunity = opportunity), downstream)
                )
            ),
            origin = origin,
            opportunity = opportunity,
            definition = """{"case":"narrative-only"}"""
        )

        val result = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(runId, "Objective", envelope(), withOpportunity)
        )

        assertTrue(
            result.errors.any {
                it.code == FieldCompileErrorCode.INVALID_TOOL_SEMANTICS &&
                    it.message.contains("unsupported fields")
            },
            result.errors.joinToString()
        )
    }

    @Test
    fun `aggregate coefficient multiplied by area fails exact dimensional sealing`() {
        val runId = "aggregate-times-area"
        val staged = splitStages(outputs())
        val origin = staged.taskAnatomist.nodes.first()
        val downstream = staged.taskAnatomist.nodes.last()
        val aggregatePowerPerTemperature = PhysicalDimension(
            mapOf("power" to 1, "temperature" to -1)
        )
        val invalidOpportunity = heatTransferFormulaOpportunity(
            runId = runId,
            source = origin,
            target = downstream,
            coefficientDimension = aggregatePowerPerTemperature
        )
        val invalid = withFormulaReferenceTests(
            outputs = staged.copy(
                taskAnatomist = staged.taskAnatomist.copy(
                    nodes = listOf(
                        origin.copy(toolOpportunity = invalidOpportunity),
                        downstream
                    )
                )
            ),
            origin = origin,
            opportunity = invalidOpportunity,
            expectedValue = "4.0"
        )

        val result = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(runId, "Objective", envelope(), invalid)
        )

        assertEquals(
            listOf("taskAnatomist", "nodes", origin.key.value, "toolOpportunity",
                "formulaDslReferenceProgram"),
            result.errors.single { it.code == FieldCompileErrorCode.INVALID_TOOL_SEMANTICS }
                .path.segments
        )
        assertTrue(
            result.errors.any {
                it.message.endsWith("wrong_output_dimension_heat_loss_coefficient")
            },
            result.errors.joinToString()
        )
    }

    @Test
    fun `formula sealing rejects missing numeric dimensions semantics and reference program`() {
        val runId = "missing-formula-semantics"
        val staged = splitStages(outputs())
        val origin = staged.taskAnatomist.nodes.first()
        val downstream = staged.taskAnatomist.nodes.last()
        val validOpportunity = heatTransferFormulaOpportunity(runId, origin, downstream)
        val missingDimension = validOpportunity.copy(
            requiredInputs = validOpportunity.requiredInputs.map { field ->
                if (field.name == "coefficients") field.copy(physicalDimension = null) else field
            }
        )
        val missingSemanticsAndProgram = validOpportunity.copy(
            formulaDslReferenceSemantics = null,
            formulaDslReferenceProgram = null
        )
        val missingProgram = validOpportunity.copy(
            formulaDslReferenceProgram = null
        )

        val dimensionResult = compileWithToolOpportunity(
            runId,
            staged,
            origin,
            downstream,
            missingDimension
        )
        val semanticsResult = compileWithToolOpportunity(
            "$runId-program",
            staged,
            origin,
            downstream,
            missingSemanticsAndProgram.copy(
                expectedReuseNodeIds = setOf(
                    NodeId(
                        CouncilDeterministicIds.nodeId(
                            "$runId-program",
                            downstream.key
                        )
                    )
                )
            )
        )
        val missingProgramRunId = "$runId-reference"
        val programResult = compileWithToolOpportunity(
            missingProgramRunId,
            staged,
            origin,
            downstream,
            missingProgram.copy(
                expectedReuseNodeIds = setOf(
                    NodeId(
                        CouncilDeterministicIds.nodeId(
                            missingProgramRunId,
                            downstream.key
                        )
                    )
                )
            )
        )

        assertEquals(
            listOf("taskAnatomist", "nodes", origin.key.value, "toolOpportunity",
                "requiredInputs", "coefficients", "physicalDimension"),
            dimensionResult.errors.single {
                it.path.toString().endsWith(
                    "toolOpportunity.requiredInputs.coefficients.physicalDimension"
                )
            }.path.segments
        )
        assertEquals(
            setOf(
                "taskAnatomist.nodes.${origin.key.value}.toolOpportunity." +
                    "formulaDslReferenceSemantics",
                "taskAnatomist.nodes.${origin.key.value}.toolOpportunity." +
                    "formulaDslReferenceProgram"
            ),
            semanticsResult.errors
                .filter { it.code == FieldCompileErrorCode.INVALID_TOOL_SEMANTICS }
                .mapTo(linkedSetOf()) { it.path.toString() }
        )
        assertEquals(
            "taskAnatomist.nodes.${origin.key.value}.toolOpportunity." +
                "formulaDslReferenceProgram",
            programResult.errors.single {
                it.code == FieldCompileErrorCode.INVALID_TOOL_SEMANTICS
            }.path.toString()
        )
    }

    @Test
    fun `formula sealing rejects structured object fields outside the flat DSL`() {
        val runId = "structured-formula-field"
        val staged = splitStages(outputs())
        val origin = staged.taskAnatomist.nodes.first()
        val downstream = staged.taskAnatomist.nodes.last()
        val validOpportunity = heatTransferFormulaOpportunity(runId, origin, downstream)
        val structuredOpportunity = validOpportunity.copy(
            requiredInputs = validOpportunity.requiredInputs + listOf(
                TypedField(
                    name = "scenario",
                    type = TypedFieldType.OBJECT,
                    description = "Structured scenario.",
                    required = false,
                    objectFields = setOf(
                        TypedField(
                            name = "area",
                            type = TypedFieldType.NUMBER,
                            description = "Scenario area.",
                            physicalDimension = PhysicalDimension(mapOf("length" to 2))
                        )
                    )
                ),
                TypedField(
                    name = "zones",
                    type = TypedFieldType.ARRAY,
                    description = "Structured zones.",
                    required = false,
                    itemType = TypedFieldType.OBJECT,
                    minimumItems = 1,
                    maximumItems = 4,
                    objectFields = setOf(
                        TypedField(
                            name = "area",
                            type = TypedFieldType.NUMBER,
                            description = "Zone area.",
                            physicalDimension = PhysicalDimension(mapOf("length" to 2))
                        )
                    )
                )
            )
        )

        val result = compileWithToolOpportunity(
            runId,
            staged,
            origin,
            downstream,
            structuredOpportunity
        )

        assertEquals(
            setOf(
                "taskAnatomist.nodes.${origin.key.value}.toolOpportunity." +
                    "requiredInputs.scenario.type",
                "taskAnatomist.nodes.${origin.key.value}.toolOpportunity." +
                    "requiredInputs.zones.type"
            ),
            result.errors
                .filter {
                    it.code == FieldCompileErrorCode.INVALID_TOOL_SEMANTICS &&
                        it.message.contains("flat primitive")
                }
                .mapTo(linkedSetOf()) { it.path.toString() }
        )
    }

    @Test
    fun `two-round tool schedule rejects grade-gated authoring before later reuse`() {
        val runId = "unschedulable-tool-opportunity"
        val staged = twoRoundStagedOutputs()
        val source = staged.taskAnatomist.nodes.first()
        val target = staged.taskAnatomist.nodes.last()
        val prerequisite = source.copy(
            key = LocalNodeKey("tool-prerequisite"),
            limitationStatement = "Tool inputs are not yet validated.",
            capabilityTarget = "Validated tool inputs.",
            atomicQuestion = "Which inputs are valid for the tool?",
            proofContract = source.proofContract.copy(
                executableTestIds = setOf("tool-prerequisite-test")
            ),
            toolOpportunity = null
        )
        val gradeGatedSource = source.copy(
            layer = 1,
            parentKeys = setOf(prerequisite.key),
            prerequisiteKeys = setOf(prerequisite.key),
            toolOpportunity = scheduleToolOpportunity(runId, source, target)
        )
        val downstream = target.copy(
            layer = 2,
            parentKeys = setOf(source.key),
            prerequisiteKeys = setOf(source.key)
        )
        val prerequisiteDefinition =
            """{"assertion":"tool-prerequisite-accepted","version":"1"}"""
        val invalid = staged.copy(
            taskAnatomist = staged.taskAnatomist.copy(
                nodes = listOf(prerequisite, gradeGatedSource, downstream),
                rootNodeKeys = setOf(prerequisite.key),
                atomicityReport = listOf(prerequisite, gradeGatedSource, downstream).map {
                    NodeAtomicityAssessment(
                        it.key,
                        true,
                        it.estimatedEffort,
                        "One bounded assignment"
                    )
                }
            ),
            incentiveDesigner = staged.incentiveDesigner.copy(
                nodePolicies = listOf(prerequisite, gradeGatedSource, downstream).map {
                    NodeIncentivePolicy(it.key, it.capturePolicy, it.rewardSpec)
                }
            ),
            kgArchitect = staged.kgArchitect.copy(
                nodeMappings = staged.kgArchitect.nodeMappings +
                    (prerequisite.key to setOf("evidence", "supports"))
            ),
            synthesis = staged.synthesis.copy(
                stages = listOf(
                    staged.synthesis.stages.first().copy(
                        nodeKeys = setOf(prerequisite.key, source.key),
                        mandatoryNodeKeys = setOf(prerequisite.key, source.key)
                    ),
                    staged.synthesis.stages.last().copy(
                        nodeKeys = setOf(target.key),
                        mandatoryNodeKeys = setOf(target.key)
                    )
                ),
                sealedTests = staged.synthesis.sealedTests + SealedTestDraft(
                    testKey = "tool-prerequisite-test",
                    nodeKey = prerequisite.key,
                    acceptedArtifactTypes = prerequisite.deliverableContract.acceptedArtifactTypes,
                    testDefinitionJson = prerequisiteDefinition,
                    commitmentHash = CouncilContractHashing.sha256(prerequisiteDefinition)
                )
            )
        )

        val result = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(
                runId,
                "Objective",
                envelope().copy(maxRounds = 2),
                invalid
            )
        )

        assertTrue(
            result.errors.any {
                it.code == FieldCompileErrorCode.ENVELOPE_VIOLATION &&
                    it.path.segments.lastOrNull() == "expectedReuseNodeIds" &&
                    it.message.contains("round 2")
            },
            result.errors.joinToString()
        )
    }

    @Test
    fun `complex simulation accepts bounded Python and rejects unsupported implementation mode`() {
        val runId = "python-simulation-opportunity"
        val staged = splitStages(outputs())
        val origin = staged.taskAnatomist.nodes.first()
        val downstream = staged.taskAnatomist.nodes.last()
        val simulation = ToolOpportunity(
            opportunityId = CouncilDeterministicIds.create(
                "tool-opportunity",
                runId,
                origin.key.value
            ),
            opportunityType = ToolOpportunityType.SIMULATION,
            repeatedQuestionPattern =
                "Simulate hourly shelter temperatures across nested zone profiles.",
            requiredInputs = listOf(
                TypedField(
                    name = "zone_profiles",
                    type = TypedFieldType.ARRAY,
                    description = "Nested hourly zone profiles.",
                    itemType = TypedFieldType.OBJECT,
                    minimumItems = 1,
                    maximumItems = 64,
                    objectFields = setOf(
                        TypedField("zone_id", TypedFieldType.STRING, "Stable zone identifier."),
                        TypedField(
                            name = "hourly_temperatures",
                            type = TypedFieldType.ARRAY,
                            description = "Exactly 72 hourly temperatures.",
                            itemType = TypedFieldType.NUMBER,
                            minimumItems = 72,
                            maximumItems = 72
                        )
                    )
                )
            ),
            requiredOutputs = listOf(
                TypedField(
                    name = "hourly_results",
                    type = TypedFieldType.ARRAY,
                    description = "Computed hourly results.",
                    itemType = TypedFieldType.OBJECT,
                    minimumItems = 1,
                    maximumItems = 64,
                    objectFields = setOf(
                        TypedField("zone_id", TypedFieldType.STRING, "Stable zone identifier."),
                        TypedField(
                            name = "hourly_temperatures",
                            type = TypedFieldType.ARRAY,
                            description = "Exactly 72 simulated hourly temperatures.",
                            itemType = TypedFieldType.NUMBER,
                            minimumItems = 72,
                            maximumItems = 72
                        )
                    )
                ),
                TypedField("summary", TypedFieldType.STRING, "Bounded result summary."),
                TypedField("uncertainty", TypedFieldType.STRING, "Explicit uncertainty.")
            ),
            validationStrategy = "Execute the committed nested hourly boundary case.",
            expectedReuseNodeIds = setOf(
                NodeId(CouncilDeterministicIds.nodeId(runId, downstream.key))
            ),
            preferredImplementationMode = ToolImplementationMode.PYTHON,
            behavior = ToolBehavior.DETERMINISTIC,
            requiredCapabilities = setOf("shell"),
            promotionTestIds = origin.proofContract.executableTestIds,
            maximumRuntimeMillis = 5_000,
            maximumOutputBytes = 32_768,
            assumptions = listOf("Hourly profiles are finite and ordered."),
            knownInvalidDomains = listOf("Missing zone profiles."),
            simulationRecordBindings = SimulationRecordBindings(
                resultSummaryOutputField = "summary",
                uncertaintyOutputField = "uncertainty"
            )
        )
        val temperatures = List(72) { "20.0" }.joinToString(",")
        val testDefinition = """
            {
              "input": {
                "zone_profiles": [{
                  "zone_id": "zone-a",
                  "hourly_temperatures": [$temperatures]
                }]
              },
              "expectedOutput": {
                "hourly_results": [{
                  "zone_id": "zone-a",
                  "hourly_temperatures": [$temperatures]
                }],
                "summary": "Stable boundary case.",
                "uncertainty": "Deterministic fixture."
              }
            }
        """.trimIndent()
        val pythonOutputs = withPromotionTestDefinition(
            outputs = staged.copy(
                taskAnatomist = staged.taskAnatomist.copy(
                    nodes = listOf(origin.copy(toolOpportunity = simulation), downstream)
                )
            ),
            origin = origin,
            opportunity = simulation,
            definition = testDefinition
        )

        val valid = assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile(runId, "Objective", envelope(), pythonOutputs)
        )
        val originId = NodeId(CouncilDeterministicIds.nodeId(runId, origin.key))
        assertEquals(
            ToolImplementationMode.PYTHON,
            valid.atlas.nodes.getValue(originId).toolOpportunity?.preferredImplementationMode
        )

        val unsupported = pythonOutputs.copy(
            taskAnatomist = pythonOutputs.taskAnatomist.copy(
                nodes = listOf(
                    origin.copy(
                        toolOpportunity = simulation.copy(
                            preferredImplementationMode = ToolImplementationMode.SHELL
                        )
                    ),
                    downstream
                )
            )
        )
        val invalid = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(runId, "Objective", envelope(), unsupported)
        )
        assertTrue(
            invalid.errors.any {
                it.code == FieldCompileErrorCode.ENVELOPE_VIOLATION &&
                    it.path.segments.lastOrNull() == "preferredImplementationMode"
            }
        )
    }

    @Test
    fun `fixed array promotion fixtures must match before StartingField sealing`() {
        val runId = "fixed-array-promotion-fixture"
        val staged = twoRoundStagedOutputs()
        val origin = staged.taskAnatomist.nodes.first()
        val downstream = staged.taskAnatomist.nodes.last()
        val opportunity = scheduleToolOpportunity(runId, origin, downstream).copy(
            opportunityType = ToolOpportunityType.CALCULATION,
            repeatedQuestionPattern = "Calculate exactly 72 hourly shelter loads.",
            requiredInputs = listOf(
                TypedField("hours", TypedFieldType.INTEGER, "Forecast horizon.")
            ),
            requiredOutputs = listOf(
                TypedField(
                    name = "hourly_load",
                    type = TypedFieldType.ARRAY,
                    description = "Exactly 72 hourly loads.",
                    itemType = TypedFieldType.NUMBER,
                    minimumItems = 72,
                    maximumItems = 72
                )
            ),
            preferredImplementationMode = ToolImplementationMode.PYTHON
        )
        val withOpportunity = staged.copy(
            taskAnatomist = staged.taskAnatomist.copy(
                nodes = listOf(origin.copy(toolOpportunity = opportunity), downstream)
            )
        )
        val invalidDefinition =
            """{"input":{"hours":72},"expectedOutput":{"hourly_load":1.0}}"""
        val invalid = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(
                runId,
                "Objective",
                envelope().copy(maxRounds = 2),
                withPromotionTestDefinition(
                    outputs = withOpportunity,
                    origin = origin,
                    opportunity = opportunity,
                    definition = invalidDefinition
                )
            )
        )
        assertTrue(
            invalid.errors.any {
                it.path.toString() ==
                    "synthesis.sealedTests.evidence-test.testDefinitionJson" &&
                    it.message.contains("expectedOutput does not match the sealed schema")
            },
            invalid.errors.joinToString()
        )

        val values = List(72) { "1.0" }.joinToString(",")
        val validDefinition =
            """{"input":{"hours":72},"expectedOutput":{"hourly_load":[$values]}}"""
        assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile(
                runId,
                "Objective",
                envelope().copy(maxRounds = 2),
                withPromotionTestDefinition(
                    outputs = withOpportunity,
                    origin = origin,
                    opportunity = opportunity,
                    definition = validDefinition
                )
            )
        )
    }

    @Test
    fun `v68 rejects cooling simulator non-object definitions at literal test paths`() {
        val runId = "cooling-simulator-non-object-definitions"
        val staged = twoRoundStagedOutputs()
        val origin = staged.taskAnatomist.nodes.first()
        val downstream = staged.taskAnatomist.nodes.last()
        val opportunity = scheduleToolOpportunity(runId, origin, downstream).copy(
            promotionTestIds = coolingSimulatorPromotionTestIds
        )
        val withOpportunity = staged.copy(
            taskAnatomist = staged.taskAnatomist.copy(
                nodes = listOf(origin.copy(toolOpportunity = opportunity), downstream)
            )
        )
        val invalid = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(
                runId,
                "Objective",
                envelope().copy(maxRounds = 2),
                withPromotionTestDefinitions(
                    outputs = withOpportunity,
                    origin = origin,
                    definitions = linkedMapOf(
                        coolingSimulatorBoundaryTestId to "[]",
                        coolingSimulatorRepeatabilityTestId to "\"repeatable\""
                    )
                )
            )
        )

        val expectedPaths = coolingSimulatorPromotionTestIds.map {
            "synthesis.sealedTests.$it.testDefinitionJson"
        }
        val errors = invalid.errors.filter { it.path.toString() in expectedPaths }
        assertEquals(expectedPaths, errors.map { it.path.toString() })
        assertTrue(
            errors.all {
                it.code == FieldCompileErrorCode.INVALID_TOOL_SEMANTICS &&
                    it.message ==
                    "Tool promotion test does not satisfy its exact sealed contract: " +
                    "test definition must be one JSON object"
            },
            errors.joinToString()
        )
    }

    @Test
    fun `v68 accepts cooling simulator object fixtures when schemas match`() {
        val runId = "cooling-simulator-object-definitions"
        val staged = twoRoundStagedOutputs()
        val origin = staged.taskAnatomist.nodes.first()
        val downstream = staged.taskAnatomist.nodes.last()
        val opportunity = scheduleToolOpportunity(runId, origin, downstream).copy(
            promotionTestIds = coolingSimulatorPromotionTestIds
        )
        val withOpportunity = staged.copy(
            taskAnatomist = staged.taskAnatomist.copy(
                nodes = listOf(origin.copy(toolOpportunity = opportunity), downstream)
            )
        )

        assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile(
                runId,
                "Objective",
                envelope().copy(maxRounds = 2),
                withPromotionTestDefinitions(
                    outputs = withOpportunity,
                    origin = origin,
                    definitions = linkedMapOf(
                        coolingSimulatorBoundaryTestId to
                            """{"input":{"evidence_rows":[{"source":"sensor-a"}]},"expectedOutput":{"violations":[]}}""",
                        coolingSimulatorRepeatabilityTestId to
                            """{"input":{"evidence_rows":[{"source":"sensor-a"},{"source":"sensor-b"}]},"expectedOutput":{"violations":[{"code":"stable"}]}}"""
                    )
                )
            )
        )
    }

    @Test
    fun `opaque JSON tool fields fail before StartingField sealing`() {
        val runId = "opaque-json-tool-field"
        val staged = twoRoundStagedOutputs()
        val origin = staged.taskAnatomist.nodes.first()
        val downstream = staged.taskAnatomist.nodes.last()
        val opportunity = scheduleToolOpportunity(runId, origin, downstream).copy(
            requiredInputs = listOf(
                TypedField("payload", TypedFieldType.JSON, "Opaque input payload.")
            ),
            requiredOutputs = listOf(
                TypedField("valid", TypedFieldType.BOOLEAN, "Validation result.")
            ),
            preferredImplementationMode = ToolImplementationMode.PYTHON
        )
        val withOpportunity = staged.copy(
            taskAnatomist = staged.taskAnatomist.copy(
                nodes = listOf(origin.copy(toolOpportunity = opportunity), downstream)
            )
        )
        val definition =
            """{"input":{"payload":{}},"expectedOutput":{"valid":true}}"""

        val invalid = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(
                runId,
                "Objective",
                envelope().copy(maxRounds = 2),
                withPromotionTestDefinition(
                    outputs = withOpportunity,
                    origin = origin,
                    opportunity = opportunity,
                    definition = definition
                )
            )
        )

        assertTrue(
            invalid.errors.any {
                it.path.toString().endsWith("toolOpportunity.requiredInputs.payload") &&
                    it.message.contains("opaque JSON")
            },
            invalid.errors.joinToString()
        )
    }

    @Test
    fun `compiler rejects a node serving as both tool validation and expected reuse`() {
        val runId = "tool-purpose-overlap"
        val staged = splitStages(outputs())
        val origin = staged.taskAnatomist.nodes.first()
        val validation = staged.taskAnatomist.nodes.last().copy(
            kind = NodeKind.VALIDATION,
            parentKeys = setOf(origin.key),
            prerequisiteKeys = setOf(origin.key),
            proofContract = staged.taskAnatomist.nodes.last().proofContract.copy(
                method = ProofMethod.REPRODUCTION
            )
        )
        val opportunity = scheduleToolOpportunity(
            runId,
            origin,
            validation
        )
        val invalid = withPromotionTestDefinition(
            outputs = staged.copy(
                taskAnatomist = staged.taskAnatomist.copy(
                    nodes = listOf(
                        origin.copy(toolOpportunity = opportunity),
                        validation
                    )
                )
            ),
            origin = origin,
            opportunity = opportunity,
            definition = evidenceRulePromotionDefinition()
        )

        val result = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(runId, "Objective", envelope(), invalid)
        )

        assertTrue(
            result.errors.any {
                it.code == FieldCompileErrorCode.INVALID_NODE_REFERENCE &&
                    it.message.contains("disjoint nodes")
            },
            result.errors.joinToString()
        )
    }

    @Test
    fun `tool opportunity rejects unavailable capability and non-later reuse`() {
        val runId = "invalid-tool-opportunity"
        val staged = splitStages(outputs())
        val origin = staged.taskAnatomist.nodes.first()
        val invalidOpportunity = ToolOpportunity(
            opportunityId = "opportunity-invalid",
            opportunityType = ToolOpportunityType.CALCULATION,
            repeatedQuestionPattern = "Calculate the repeated value.",
            requiredInputs = listOf(
                TypedField(
                    "input",
                    TypedFieldType.NUMBER,
                    "Input value.",
                    physicalDimension = PhysicalDimension()
                )
            ),
            requiredOutputs = listOf(
                TypedField(
                    "output",
                    TypedFieldType.NUMBER,
                    "Output value.",
                    physicalDimension = PhysicalDimension()
                )
            ),
            validationStrategy = "Run committed exact examples.",
            expectedReuseNodeIds = setOf(
                NodeId(CouncilDeterministicIds.nodeId(runId, origin.key))
            ),
            preferredImplementationMode = ToolImplementationMode.FORMULA_DSL,
            behavior = ToolBehavior.DETERMINISTIC,
            requiredCapabilities = setOf("unavailable-capability"),
            promotionTestIds = setOf("evidence-test"),
            maximumRuntimeMillis = 1_000,
            maximumOutputBytes = 4_096,
            formulaDslReferenceSemantics =
                FormulaDslReferenceSemantics.DIMENSIONAL_ANALYSIS_V1,
            formulaDslReferenceProgram = ToolDslProgram(
                kind = ToolDslKind.FORMULA,
                outputs = listOf(
                    ToolDslOutput("output", ToolDslExpression.Input("input"))
                )
            )
        )
        val invalid = staged.copy(
            taskAnatomist = staged.taskAnatomist.copy(
                nodes = listOf(origin.copy(toolOpportunity = invalidOpportunity)) +
                    staged.taskAnatomist.nodes.drop(1)
            )
        )

        val result = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile(runId, "Objective", envelope(), invalid)
        )

        assertTrue(result.errors.any { it.code == FieldCompileErrorCode.ENVELOPE_VIOLATION })
        assertTrue(result.errors.any { it.code == FieldCompileErrorCode.INVALID_NODE_REFERENCE })
    }

    @Test
    fun `unavailable model fails rather than substituting a fallback`() {
        val base = outputs()
        val unavailable = base.copy(
            modelEcologist = base.modelEcologist.copy(
                assignments = base.modelEcologist.assignments.mapIndexed { index, assignment ->
                    if (index == 0) assignment.copy(primaryModelId = "provider/unavailable")
                    else assignment
                }
            )
        )
        val invalid = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile("unknown-model", "Objective", envelope(), unavailable)
        )
        assertTrue(invalid.errors.any { it.code == FieldCompileErrorCode.UNKNOWN_MODEL })
        assertTrue(Role.CouncilModelEcologist in invalid.responsibleCouncilRoles)
    }
    @Test
    fun `missing stage and mismatched schema versions fail as typed errors`() {
        val base = outputs()
        val missingStage = base.copy(taskAnatomist = base.taskAnatomist.copy(
            nodes = listOf(base.taskAnatomist.nodes.first().copy(stageKey = "missing")) +
                base.taskAnatomist.nodes.drop(1)
        )
        )
        assertHasError(missingStage, FieldCompileErrorCode.INVALID_STAGE)
        assertHasError(
            base.copy(synthesis = base.synthesis.copy(rubricSchemaVersion = "2")),
            FieldCompileErrorCode.SCHEMA_VERSION_MISMATCH
        )
    }

    @Test
    fun `material revision requires declaration and declared revision records degradation`() {
        val initial = assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile("revision-run", "Objective", envelope(), outputs())
        )
        val staged = splitStages(outputs())
        val undeclared = staged.copy(
            synthesis = staged.synthesis.copy(
                atlasVersion = 2,
                parentAtlasHash = initial.atlas.atlasHash
            )
        )
        val invalid = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile("revision-run", "Objective", envelope(), undeclared, initial)
        )
        assertTrue(invalid.errors.any { it.code == FieldCompileErrorCode.UNDECLARED_MATERIAL_CHANGE })

        val rationale = "Expose delivery only after evidence completion."
        val declaration = FieldChangeDeclaration(
            declarationId = CouncilDeterministicIds.changeDeclarationId(initial.atlas.atlasHash, rationale),
            parentAtlasHash = initial.atlas.atlasHash,
            changeKinds = setOf(MaterialFieldChangeKind.STAGE_COUNT),
            rationale = rationale,
            requestedBy = "operator"
        )
        val declared = undeclared.copy(
            synthesis = undeclared.synthesis.copy(declaredChange = declaration)
        )
        val valid = assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile("revision-run", "Objective", envelope(), declared, initial)
        )

        assertEquals(2, valid.atlas.version)
        assertEquals(initial.atlas.atlasHash, valid.atlas.parentAtlasHash)
        assertEquals(listOf(declaration.declarationId), valid.atlas.amendmentIds)
        assertEquals(1, valid.degradationRecords.size)
        assertEquals(declaration.declarationId, valid.degradationRecords.single().declarationId)
        assertNotEquals(initial.atlas.atlasHash, valid.atlas.atlasHash)

        val original = outputs()
        val objectiveRevision = original.copy(synthesis = original.synthesis.copy(
            atlasVersion = 2, parentAtlasHash = initial.atlas.atlasHash
        ))
        val changedObjective = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile("revision-run", "Changed objective", envelope(), objectiveRevision, initial)
        )
        assertTrue(changedObjective.errors.any { it.code == FieldCompileErrorCode.OBJECTIVE_CHANGED })
    }

    @Test
    fun `policy-only revision is a material change requiring declaration`() {
        val base = outputs()
        val initial = assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile("policy-revision", "Objective", envelope(), base))
        val changedNode = base.taskAnatomist.nodes.first().copy(capturePolicy =
            base.taskAnatomist.nodes.first().capturePolicy.copy(minimumGrade = 0.8))
        val revised = base.copy(
            taskAnatomist = base.taskAnatomist.copy(nodes = listOf(changedNode) + base.taskAnatomist.nodes.drop(1)),
            incentiveDesigner = base.incentiveDesigner.copy(nodePolicies =
                base.incentiveDesigner.nodePolicies.map {
                    if (it.nodeKey == changedNode.key)
                        it.copy(capturePolicy = changedNode.capturePolicy) else it
                }),
            synthesis = base.synthesis.copy(atlasVersion = 2, parentAtlasHash = initial.atlas.atlasHash)
        )
        assertHasRevisionError(revised, initial, FieldCompileErrorCode.UNDECLARED_MATERIAL_CHANGE)
    }

    @Test
    fun `generated JSON is typed parseable and sealed test hash commits exact JSON`() {
        val result = assertIs<StartingFieldCompileResult.Valid>(
            compiler.compile("json-run", "Objective", envelope(), outputs())
        )

        assertEquals("node-graph-v1", json.parseToJsonElement(result.workerField.nodeGraph).jsonObject["schemaVersion"]?.toString()?.trim('"'))
        assertEquals("1", json.parseToJsonElement(result.workerField.kgSchema).jsonObject["schemaVersion"]?.toString()?.trim('"'))
        result.workerField.stageContents.forEach { json.parseToJsonElement(it) }
        assertEquals(
            CouncilContractHashing.sha256(result.workerField.sealedTestSuiteJson),
            result.workerField.sealedTestSuiteHash
        )
        assertEquals(result.workerField.nodeGraph, result.graderField.nodeGraph)
        assertEquals(result.workerField.kgSchema, result.graderField.kgSchema)
        assertEquals(setOf(Role.Researcher.name), result.workerField.modelAssignment.keys)
        assertEquals(setOf(Role.GraderResearcher.name), result.graderField.modelAssignment.keys)
        assertTrue(Role.GraderResearcher.name !in result.workerField.rolePrompts)
        assertTrue(Role.Researcher.name !in result.graderField.rolePrompts)
    }

    private fun assertHasError(
        outputs: CouncilRoleOutputBundle,
        code: FieldCompileErrorCode
    ) {
        val invalid = assertIs<StartingFieldCompileResult.Invalid>(
            compiler.compile("invalid-run", "Objective", envelope(), outputs)
        )
        assertTrue(invalid.errors.any { it.code == code }, invalid.errors.joinToString())
    }

    private fun assertHasRevisionError(
        outputs: CouncilRoleOutputBundle,
        previous: StartingFieldCompileResult.Valid,
        code: FieldCompileErrorCode
    ) {
        val invalid = assertIs<StartingFieldCompileResult.Invalid>(compiler.compile(
            previous.atlas.runId, previous.workerField.objective, envelope(), outputs, previous))
        assertTrue(invalid.errors.any { it.code == code }, invalid.errors.joinToString())
    }

    private fun splitStages(base: CouncilRoleOutputBundle): CouncilRoleOutputBundle {
        val root = base.taskAnatomist.nodes.first()
        val child = base.taskAnatomist.nodes.last().copy(
            stageKey = "delivery",
            revealMode = RevealMode.STAGE_PASS
        )
        return base.copy(
            taskAnatomist = base.taskAnatomist.copy(nodes = listOf(root, child)),
            synthesis = base.synthesis.copy(
                stages = listOf(
                    base.synthesis.stages.single().copy(
                        nodeKeys = setOf(root.key),
                        mandatoryNodeKeys = setOf(root.key)
                    ),
                    DisclosureStageDraft(
                        stageKey = "delivery",
                        title = "Delivery",
                        nodeKeys = setOf(child.key),
                        mandatoryNodeKeys = setOf(child.key),
                        evidenceCoverageThreshold = 1.0,
                        integrationReadinessThreshold = 1.0
                    )
                )
            )
        )
    }

    private fun twoRoundStagedOutputs(): CouncilRoleOutputBundle {
        val staged = splitStages(outputs())
        return staged.copy(
            ecologist = staged.ecologist.copy(
                checkinRounds = listOf(1, 2),
                leakIntervalRounds = 2,
                dispatchLagRounds = 1
            )
        )
    }

    private fun acceptanceEnvelope(profile: AcceptanceProfile): ExecutionEnvelope =
        envelope().copy(
            requiredGraderGroupCount = InclusiveIntRange(2, 2),
            maxRounds = when (profile) {
                AcceptanceProfile.NORMAL -> envelope().maxRounds
                AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> 1
                AcceptanceProfile.APPENDIX_B_FULL -> 6
            },
            acceptanceProfile = profile
        )

    private fun withAcceptanceGraderGroups(
        base: CouncilRoleOutputBundle
    ): CouncilRoleOutputBundle {
        val first = base.ecologist.graderGroups.first()
        return base.copy(
            ecologist = base.ecologist.copy(
                graderGroups = listOf(
                    first,
                    first.copy(groupKey = "${first.groupKey}-peer")
                )
            )
        )
    }

    private fun smokeAcceptanceOutputs(): CouncilRoleOutputBundle {
        val base = withAcceptanceGraderGroups(outputs())
        return base.copy(
            ecologist = base.ecologist.copy(
                checkinRounds = listOf(1),
                leakIntervalRounds = 1,
                dispatchLagRounds = 0
            )
        )
    }

    private fun fullAcceptanceOutputs(runId: String): CouncilRoleOutputBundle {
        val staged = withAcceptanceGraderGroups(twoRoundStagedOutputs())
        val origin = staged.taskAnatomist.nodes.first()
        val reuse = staged.taskAnatomist.nodes.last()
        val validation = reuse.copy(
            key = LocalNodeKey("tool-validation"),
            kind = NodeKind.VALIDATION,
            parentKeys = setOf(origin.key),
            prerequisiteKeys = setOf(origin.key),
            alternativeToKeys = emptySet(),
            limitationStatement = "The promoted tool has not been independently reproduced.",
            capabilityTarget = "The promoted tool is independently reproduced.",
            atomicQuestion = "Does the promoted tool pass independent reproduction?",
            proofContract = reuse.proofContract.copy(
                method = ProofMethod.REPRODUCTION,
                executableTestIds = setOf("tool-validation-general-test")
            ),
            deliverableContract = reuse.deliverableContract.copy(
                acceptedArtifactTypes = setOf(ArtifactType.VALIDATION_REPORT),
                finalDeliverableRelationship =
                    "Validation evidence can support the final deliverable."
            )
        )
        val opportunity = scheduleToolOpportunity(runId, origin, reuse)
        val validationDefinition = GeneralTestDefinitionV1Codec.encode(
            GeneralTestDefinitionV1(
                target = GeneralTestTargetV1.FinalOutputText,
                assertion = GeneralAssertionV1.TextContains(
                    expected = "tool-validation-accepted",
                    caseSensitive = false
                )
            )
        )
        val validationTest = SealedTestDraft(
            testKey = validation.proofContract.executableTestIds.single(),
            nodeKey = validation.key,
            acceptedArtifactTypes = validation.deliverableContract.acceptedArtifactTypes,
            testDefinitionJson = validationDefinition,
            commitmentHash = CouncilContractHashing.sha256(validationDefinition)
        )
        val expanded = staged.copy(
            taskAnatomist = staged.taskAnatomist.copy(
                nodes = listOf(
                    origin.copy(toolOpportunity = opportunity),
                    validation,
                    reuse
                ),
                atomicityReport = staged.taskAnatomist.atomicityReport +
                    NodeAtomicityAssessment(
                        validation.key,
                        true,
                        validation.estimatedEffort,
                        "One bounded reproduction."
                    )
            ),
            incentiveDesigner = staged.incentiveDesigner.copy(
                nodePolicies = staged.incentiveDesigner.nodePolicies +
                    NodeIncentivePolicy(
                        validation.key,
                        validation.capturePolicy,
                        validation.rewardSpec
                    )
            ),
            kgArchitect = staged.kgArchitect.copy(
                nodeMappings = staged.kgArchitect.nodeMappings +
                    (validation.key to setOf("evidence", "supports"))
            ),
            synthesis = staged.synthesis.copy(
                stages = staged.synthesis.stages.map { stage ->
                    if (stage.stageKey == reuse.stageKey) {
                        stage.copy(
                            nodeKeys = stage.nodeKeys + validation.key,
                            mandatoryNodeKeys = stage.mandatoryNodeKeys + validation.key
                        )
                    } else {
                        stage
                    }
                },
                sealedTests = staged.synthesis.sealedTests + validationTest
            )
        )
        return withPromotionTestDefinition(
            expanded,
            origin,
            opportunity,
            evidenceRulePromotionDefinition()
        )
    }

    private fun scheduleToolOpportunity(
        runId: String,
        source: LocalLimitationNode,
        target: LocalLimitationNode
    ): ToolOpportunity = ToolOpportunity(
        opportunityId = CouncilDeterministicIds.create(
            "tool-opportunity",
            runId,
            source.key.value
        ),
        opportunityType = ToolOpportunityType.CONSISTENCY_CHECK,
        repeatedQuestionPattern = "Check each evidence row against the same source rules.",
        requiredInputs = listOf(
            TypedField(
                name = "evidence_rows",
                type = TypedFieldType.ARRAY,
                description = "Evidence rows to validate.",
                itemType = TypedFieldType.OBJECT,
                minimumItems = 1,
                maximumItems = 64,
                objectFields = setOf(
                    TypedField("source", TypedFieldType.STRING, "Evidence source identifier.")
                )
            )
        ),
        requiredOutputs = listOf(
            TypedField(
                name = "violations",
                type = TypedFieldType.ARRAY,
                description = "Detected rule violations.",
                itemType = TypedFieldType.OBJECT,
                minimumItems = 0,
                maximumItems = 64,
                objectFields = setOf(
                    TypedField("code", TypedFieldType.STRING, "Violation code.")
                )
            )
        ),
        validationStrategy = "Run the committed evidence boundary cases.",
        expectedReuseNodeIds = setOf(
            NodeId(CouncilDeterministicIds.nodeId(runId, target.key))
        ),
        preferredImplementationMode = ToolImplementationMode.RULE_DSL,
        behavior = ToolBehavior.DETERMINISTIC,
        requiredCapabilities = setOf("shell"),
        promotionTestIds = source.proofContract.executableTestIds,
        maximumRuntimeMillis = 2_000,
        maximumOutputBytes = 32_768,
        assumptions = listOf("Evidence rows conform to the typed input schema."),
        knownInvalidDomains = listOf("Binary evidence payloads.")
    )

    private fun evidenceRulePromotionDefinition(): String =
        """
        {
          "input": {
            "evidence_rows": [
              {
                "source": "official-source"
              }
            ]
          },
          "expectedOutput": {
            "violations": []
          }
        }
        """.trimIndent()

    private fun heatTransferFormulaOpportunity(
        runId: String,
        source: LocalLimitationNode,
        target: LocalLimitationNode,
        coefficientDimension: PhysicalDimension = PhysicalDimension(
            mapOf("power" to 1, "temperature" to -1, "length" to -2)
        )
    ): ToolOpportunity {
        val aggregatePowerPerTemperature = PhysicalDimension(
            mapOf("power" to 1, "temperature" to -1)
        )
        return ToolOpportunity(
            opportunityId = CouncilDeterministicIds.create(
                "tool-opportunity",
                runId,
                source.key.value
            ),
            opportunityType = ToolOpportunityType.FORMULA,
            repeatedQuestionPattern =
                "Convert measured per-area coefficients to an aggregate heat-loss coefficient.",
            requiredInputs = listOf(
                TypedField(
                    name = "coefficients",
                    type = TypedFieldType.ARRAY,
                    description = "Power-per-area-per-temperature coefficients.",
                    itemType = TypedFieldType.NUMBER,
                    minimumItems = 1,
                    maximumItems = 64,
                    physicalDimension = coefficientDimension
                ),
                TypedField(
                    name = "area",
                    type = TypedFieldType.NUMBER,
                    description = "Measured transfer area.",
                    physicalDimension = PhysicalDimension(mapOf("length" to 2))
                )
            ),
            requiredOutputs = listOf(
                TypedField(
                    name = "heat_loss_coefficient",
                    type = TypedFieldType.NUMBER,
                    description = "Aggregate power-per-temperature coefficient.",
                    physicalDimension = aggregatePowerPerTemperature
                )
            ),
            validationStrategy =
                "Promotion verifies the committed per-area-times-area boundary examples.",
            expectedReuseNodeIds = setOf(
                NodeId(CouncilDeterministicIds.nodeId(runId, target.key))
            ),
            preferredImplementationMode = ToolImplementationMode.FORMULA_DSL,
            behavior = ToolBehavior.DETERMINISTIC,
            promotionTestIds = source.proofContract.executableTestIds,
            maximumRuntimeMillis = 1_000,
            maximumOutputBytes = 4_096,
            assumptions = listOf(
                "Coefficient items are per-area values and area is measured in square length."
            ),
            formulaDslReferenceSemantics =
                FormulaDslReferenceSemantics.DIMENSIONAL_ANALYSIS_V1,
            formulaDslReferenceProgram = ToolDslProgram(
                kind = ToolDslKind.FORMULA,
                outputs = listOf(
                    ToolDslOutput(
                        field = "heat_loss_coefficient",
                        expression = ToolDslExpression.Binary(
                            operation = ToolDslBinaryOperation.MULTIPLY,
                            left = ToolDslExpression.Aggregate(
                                operation = ToolDslAggregateOperation.MEAN,
                                values = ToolDslExpression.Input("coefficients")
                            ),
                            right = ToolDslExpression.Input("area")
                        )
                    )
                )
            )
        )
    }

    private fun withFormulaReferenceTests(
        outputs: CouncilRoleOutputBundle,
        origin: LocalLimitationNode,
        opportunity: ToolOpportunity,
        expectedValue: String
    ): CouncilRoleOutputBundle {
        val definition = """
            {
              "input": {
                "coefficients": [1.0, 3.0],
                "area": 2.0
              },
              "expectedOutput": {
                "heat_loss_coefficient": $expectedValue
              }
            }
        """.trimIndent()
        return withPromotionTestDefinition(
            outputs = outputs,
            origin = origin,
            opportunity = opportunity,
            definition = definition
        )
    }

    private fun withPromotionTestDefinition(
        outputs: CouncilRoleOutputBundle,
        origin: LocalLimitationNode,
        opportunity: ToolOpportunity,
        definition: String
    ): CouncilRoleOutputBundle {
        val promotionTestIds = opportunity.promotionTestIds
        return outputs.copy(
            synthesis = outputs.synthesis.copy(
                sealedTests = outputs.synthesis.sealedTests.map { test ->
                    if (
                        test.nodeKey == origin.key &&
                        test.testKey in promotionTestIds
                    ) {
                        test.copy(
                            testDefinitionJson = definition,
                            commitmentHash = CouncilContractHashing.sha256(definition)
                        )
                    } else {
                        test
                    }
                }
            )
        )
    }

    private fun withPromotionTestDefinitions(
        outputs: CouncilRoleOutputBundle,
        origin: LocalLimitationNode,
        definitions: Map<String, String>
    ): CouncilRoleOutputBundle = outputs.copy(
        synthesis = outputs.synthesis.copy(
            sealedTests = outputs.synthesis.sealedTests + definitions.map { (testId, definition) ->
                SealedTestDraft(
                    testKey = testId,
                    nodeKey = origin.key,
                    acceptedArtifactTypes = origin.deliverableContract.acceptedArtifactTypes,
                    testDefinitionJson = definition,
                    commitmentHash = CouncilContractHashing.sha256(definition)
                )
            }
        )
    )

    private fun compileWithToolOpportunity(
        runId: String,
        staged: CouncilRoleOutputBundle,
        origin: LocalLimitationNode,
        downstream: LocalLimitationNode,
        opportunity: ToolOpportunity
    ): StartingFieldCompileResult.Invalid {
        val outputs = staged.copy(
            taskAnatomist = staged.taskAnatomist.copy(
                nodes = listOf(origin.copy(toolOpportunity = opportunity), downstream)
            )
        )
        val fixtureOutputs = if (
            opportunity.preferredImplementationMode == ToolImplementationMode.FORMULA_DSL
        ) {
            withFormulaReferenceTests(
                outputs = outputs,
                origin = origin,
                opportunity = opportunity,
                expectedValue = "4.0"
            )
        } else {
            outputs
        }
        return assertIs(compiler.compile(runId, "Objective", envelope(), fixtureOutputs))
    }

    internal fun envelope() = ExecutionEnvelope(
        requiredWorkerGroupCount = InclusiveIntRange(2, 2),
        requiredGraderGroupCount = InclusiveIntRange(1, 1),
        permittedProfiles = setOf(GroupProfileType.Library, GroupProfileType.Factory, GroupProfileType.Monastery),
        availableModels = listOf(
            ModelCapabilityCard(
                modelId = "provider/worker",
                familyId = "worker-family",
                capabilities = setOf("structured-output", "research"),
                structuredOutputReliability = 0.9,
                toolCallReliability = 0.8,
                codeBuildReliability = 0.7,
                longContextSynthesisReliability = 0.9
            ),
            ModelCapabilityCard(
                modelId = "provider/grader",
                familyId = "grader-family",
                capabilities = setOf("structured-output", "judge"),
                structuredOutputReliability = 0.95,
                toolCallReliability = 0.7,
                codeBuildReliability = 0.7,
                longContextSynthesisReliability = 0.9,
                judgeCalibrationScore = 0.9
            )
        ),
        maxAgentsPerGroupPerRound = 4,
        maxRounds = 4,
        perGenerationCreditPolicy = GenerationCreditPolicy(20.0, 50.0, 0.1),
        cumulativeCostCap = 400.0,
        allowedTopologyKinds = setOf(GroupTopologyKind.RING),
        sourceAccessPolicy = SourceAccessPolicySummary(
            networkAccessEnabled = true,
            allowedDomains = setOf("official.example")
        ),
        sandboxCapabilities = SandboxCapabilitySummary(
            capabilityIds = setOf("browser", "shell"),
            outboundNetworkEnabled = true,
            maximumToolRuntimeSeconds = 60,
            maximumWorkspaceBytes = 1_000_000
        ),
        outputSpec = FinalOutputSpec(
            relativePath = "result.md",
            mediaType = "text/markdown",
            description = "Receiver-ready report",
            requiredSections = setOf("answer", "evidence"),
            minimumEvidenceItems = 2
        ),
        atomicNodeEffortBound = 3
    )

    internal fun outputs(): CouncilRoleOutputBundle {
        val evidence = LocalNodeKey("evidence")
        val delivery = LocalNodeKey("delivery")
        val evidenceNode = node(
            key = evidence,
            kind = NodeKind.EVIDENCE,
            capability = "Primary evidence established",
            question = "Which primary sources establish the material claims?",
            artifact = ArtifactType.EVIDENCE_TABLE,
            testId = "evidence-test"
        )
        val deliveryNode = node(
            key = delivery,
            kind = NodeKind.DELIVERY,
            capability = "Receiver-ready report delivered",
            question = "Does the report satisfy every final-output obligation?",
            artifact = ArtifactType.FINAL_DELIVERABLE,
            parentKeys = setOf(evidence),
            testId = "delivery-test"
        )
        val nodes = listOf(evidenceNode, deliveryNode)
        val tests = nodes.map { node ->
            val definition = GeneralTestDefinitionV1Codec.encode(
                GeneralTestDefinitionV1(
                    target = GeneralTestTargetV1.FinalOutputText,
                    assertion = GeneralAssertionV1.TextContains(
                        expected = "${node.key.value}-accepted",
                        caseSensitive = false
                    )
                )
            )
            SealedTestDraft(
                testKey = node.proofContract.executableTestIds.single(),
                nodeKey = node.key,
                acceptedArtifactTypes = node.deliverableContract.acceptedArtifactTypes,
                testDefinitionJson = definition,
                commitmentHash = CouncilContractHashing.sha256(definition)
            )
        }
        return CouncilRoleOutputBundle(
            taskAnatomist = TaskAnatomistOutput(
                receiverContract = ReceiverContractDraft(
                    receiverKey = "operator",
                    receiverDescription = "Decision-making operator",
                    situation = "A consequential decision requires evidence.",
                    decisionOrUse = "Select and justify an action.",
                    whyNeeded = "Reduce uncertainty without inventing support.",
                    successOutcomes = listOf("Claims are supported", "Output is actionable"),
                    unacceptableOutcomes = listOf("Fabricated evidence"),
                    constraints = listOf("Auditable"),
                    evidenceProfile = EvidenceProfile(
                        minimumIndependentSources = 2,
                        acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE),
                        requiredSourceDomains = setOf("official.example")
                    )
                ),
                receiverObligations = mapOf("decision-support" to "Support the receiver's decision"),
                nodes = nodes,
                rootNodeKeys = setOf(evidence),
                atomicityReport = nodes.map {
                    NodeAtomicityAssessment(it.key, true, it.estimatedEffort, "One bounded assignment")
                }
            ),
            incentiveDesigner = IncentiveDesignerOutput(
                nodePolicies = nodes.map { NodeIncentivePolicy(it.key, it.capturePolicy, it.rewardSpec) },
                actionCosts = mapOf("READ_KB" to 0.2, "WRITE_STAGING" to 1.0),
                scoringDimensions = listOf(
                    ScoringDimension("quality", "Quality", "Contract satisfaction", 1.0)
                ),
                maximumRewardCreditsPerRound = 10.0,
                maximumSharePerGroup = 0.6,
                repeatOwnershipDiminishingFactor = 0.8,
                mandatoryFrontierBonus = 0.15,
                independentValidationCredit = 0.5,
                graderRewardPolicy = "Graders receive no outcome-contingent reward."
            ),
            ecologist = EcologistOutput(
                workerGroups = listOf(
                    GroupFieldDesign("worker-library", GroupProfileType.Library, "worker-family"),
                    GroupFieldDesign("worker-factory", GroupProfileType.Factory, "worker-family")
                ),
                graderGroups = listOf(
                    GroupFieldDesign("grader-monastery", GroupProfileType.Monastery, "grader-family")
                ),
                topologyKind = GroupTopologyKind.RING,
                topologyOrder = listOf("worker-library", "worker-factory"),
                checkinRounds = listOf(1, 2, 4),
                leakIntervalRounds = 2,
                dispatchLagRounds = 1
            ),
            promptArchitect = PromptArchitectOutput(
                rolePrompts = mapOf(
                    Role.Researcher to "Establish evidence within the current disclosure stage.",
                    Role.GraderResearcher to "Evaluate only committed criteria."
                ),
                informationBoundaries = listOf(
                    RoleInformationBoundary(
                        Role.Researcher,
                        visibleFields = setOf("currentStage", "revealedNodes"),
                        forbiddenFields = setOf("futureStages", "sealedTests")
                    )
                ),
                outlierNoveltyMandate = "Explore one independent alternative.",
                deathCommitSchemaJson = """{"type":"object","version":"1"}""",
                legacyProtocolTemplateJson = """{"type":"object","version":"1"}"""
            ),
            kgArchitect = KgArchitectOutput(
                schemaVersion = "1",
                entityTypes = listOf(
                    KgEntityTypeSpec("claim"),
                    KgEntityTypeSpec("evidence")
                ),
                relationTypes = listOf(
                    KgRelationTypeSpec("supports", setOf("evidence"), setOf("claim"))
                ),
                aliases = mapOf("backs" to "supports"),
                claimLedgerSchemaJson = """{"type":"object","version":"1"}""",
                nodeMappings = mapOf(
                    evidence to setOf("evidence", "supports"),
                    delivery to setOf("claim")
                )
            ),
            modelEcologist = ModelEcologistOutput(
                assignments = listOf(
                    RoleModelAssignment(
                        Role.Researcher,
                        "provider/worker",
                        requiredCapabilities = setOf("research")
                    ),
                    RoleModelAssignment(
                        Role.GraderResearcher,
                        "provider/grader",
                        requiredCapabilities = setOf("judge")
                    )
                ),
                diversityRationale = "Separate worker and calibrated judge families.",
                degradedModePolicy = "Abort when a declared model is unavailable."
            ),
            seedCurator = SeedCuratorOutput(
                primordialKb = emptyList(),
                primordialKgFacts = emptyList(),
                primordialTools = emptyList(),
                calibrationArtifacts = emptyList(),
                knownDeadEnds = listOf("Unsupported synthesis"),
                stageZeroContext = listOf("Use only disclosed node obligations."),
                foundingNorms = "Cite exact evidence and expose uncertainty."
            ),
            synthesis = SynthesisOutput(
                stages = listOf(
                    DisclosureStageDraft(
                        stageKey = "foundation",
                        title = "Foundation",
                        nodeKeys = setOf(evidence, delivery),
                        mandatoryNodeKeys = setOf(evidence, delivery),
                        evidenceCoverageThreshold = 0.8,
                        integrationReadinessThreshold = 0.7
                    )
                ),
                sealedTests = tests,
                workerFieldNotes = listOf("Compete on verified quality."),
                graderFieldNotes = listOf("Remain isolated from worker substrate."),
                rubricSchemaVersion = "1"
            )
        )
    }

    private fun node(
        key: LocalNodeKey,
        kind: NodeKind,
        capability: String,
        question: String,
        artifact: ArtifactType,
        parentKeys: Set<LocalNodeKey> = emptySet(),
        testId: String
    ) = LocalLimitationNode(
        key = key,
        kind = kind,
        layer = if (parentKeys.isEmpty()) 0 else 1,
        parentKeys = parentKeys,
        receiverObligationKey = "decision-support",
        limitationStatement = "${key.value} capability is not yet established.",
        capabilityTarget = capability,
        atomicQuestion = question,
        estimatedEffort = 2,
        proofContract = ProofContract(
            method = if (kind == NodeKind.DELIVERY) ProofMethod.RECEIVER_VALIDATION else ProofMethod.SOURCE_CORROBORATION,
            acceptanceCriteria = setOf("Meets the typed contract"),
            requiredEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE),
            executableTestIds = setOf(testId)
        ),
        deliverableContract = NodeDeliverableContract(
            acceptedArtifactTypes = setOf(artifact),
            requiredFields = setOf("result"),
            mediaTypes = setOf(if (artifact == ArtifactType.FINAL_DELIVERABLE) "text/markdown" else "application/json"),
            finalDeliverableRelationship = key.value
        ),
        stageKey = "foundation",
        revealMode = RevealMode.INITIAL,
        replicationPolicy = ReplicationPolicy(),
        capturePolicy = CapturePolicy(minimumGrade = 0.7, recaptureMargin = 0.05),
        rewardSpec = RewardSpec(RewardClass.SMALL, credits = 2.0, firstCompletionBonus = 0.5)
    )
}
