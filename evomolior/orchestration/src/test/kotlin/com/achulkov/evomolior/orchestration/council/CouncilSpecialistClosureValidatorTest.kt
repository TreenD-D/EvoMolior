package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.contract.SeedKGFact
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.council.LocalLimitationNode
import com.achulkov.evomolior.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CouncilSpecialistClosureValidatorTest {
    private val fixtures = StartingFieldCompilerTest()

    @Test
    fun `valid specialist bundle closes and freezes every canonical role hash`() {
        val bundle = CouncilSpecialistBundle.from(fixtures.outputs())
        val report = CouncilSpecialistClosureValidator.validate(
            runId = "valid-specialist-closure",
            envelope = fixtures.envelope(),
            bundle = bundle
        )

        assertTrue(report.isClosed)
        assertEquals(specialistRoles.toSet(), report.frozenValidRoleHashes.keys)
        assertTrue(
            CouncilSpecialistClosureValidator.verifyFrozenRoleHashes(
                bundle,
                report.frozenValidRoleHashes
            ).isEmpty()
        )

        val repairedModel = bundle.copy(
            modelEcologist = bundle.modelEcologist.copy(
                diversityRationale = "Repaired without rewriting another specialist."
            )
        )
        val unaffectedHashes = report.frozenValidRoleHashes
            .filterKeys { it != Role.CouncilModelEcologist }
        assertTrue(
            CouncilSpecialistClosureValidator.verifyFrozenRoleHashes(
                repairedModel,
                unaffectedHashes
            ).isEmpty()
        )
        val mutatedUnrepairedPrompt = repairedModel.copy(
            promptArchitect = repairedModel.promptArchitect.copy(
                outlierNoveltyMandate = "Unexpected mutation"
            )
        )
        val frozenErrors = CouncilSpecialistClosureValidator.verifyFrozenRoleHashes(
            mutatedUnrepairedPrompt,
            unaffectedHashes
        )
        assertEquals(1, frozenErrors.size)
        assertEquals(FieldCompileErrorCode.HASH_MISMATCH, frozenErrors.single().code)
        assertEquals(
            setOf(Role.CouncilPromptArchitect),
            frozenErrors.single().responsibleCouncilRoles
        )
    }

    @Test
    fun `prompt and model mismatch is jointly owned before synthesis`() {
        val base = CouncilSpecialistBundle.from(fixtures.outputs())
        val researcherBoundary = base.promptArchitect.informationBoundaries.single()
        val bundle = base.copy(
            promptArchitect = base.promptArchitect.copy(
                rolePrompts = base.promptArchitect.rolePrompts +
                    (Role.Builder to "Build only within the disclosed node contract."),
                informationBoundaries = base.promptArchitect.informationBoundaries +
                    researcherBoundary.copy(role = Role.Builder)
            )
        )

        val report = CouncilSpecialistClosureValidator.validate(
            runId = "prompt-model-mismatch",
            envelope = fixtures.envelope(),
            bundle = bundle
        )

        assertEquals(
            setOf(Role.CouncilPromptArchitect, Role.CouncilModelEcologist),
            report.errorsByResponsibleRole.keys
        )
        assertTrue(
            report.errorsFor(Role.CouncilModelEcologist).any {
                it.code == FieldCompileErrorCode.UNKNOWN_MODEL &&
                    it.path.segments == listOf("modelEcologist", "assignments")
            }
        )
        assertTrue(
            Role.CouncilPromptArchitect !in report.frozenValidRoleHashes &&
                Role.CouncilModelEcologist !in report.frozenValidRoleHashes
        )
    }

    @Test
    fun `KG mapping and seed relation failures retain exact cross-role ownership`() {
        val base = CouncilSpecialistBundle.from(fixtures.outputs())
        val unmappedNode = base.taskAnatomist.nodes.last().key
        val bundle = base.copy(
            kgArchitect = base.kgArchitect.copy(
                nodeMappings = base.kgArchitect.nodeMappings - unmappedNode,
                aliases = base.kgArchitect.aliases + ("supports" to "supports")
            ),
            seedCurator = base.seedCurator.copy(
                primordialKgFacts = listOf(
                    SeedKGFact(
                        id = "unknown-relation-fact",
                        subject = "evidence",
                        relation = "not-in-schema",
                        `object` = "claim",
                        confidence = 0.9
                    )
                )
            )
        )

        val report = CouncilSpecialistClosureValidator.validate(
            runId = "kg-seed-ownership",
            envelope = fixtures.envelope(),
            bundle = bundle
        )

        assertTrue(
            report.errorsFor(Role.CouncilTaskAnatomist).any {
                it.path.segments == listOf("kgArchitect", "nodeMappings")
            }
        )
        assertTrue(
            report.errorsFor(Role.CouncilKGArchitect).any {
                it.path.segments == listOf("kgArchitect", "nodeMappings")
            }
        )
        assertTrue(
            report.errorsFor(Role.CouncilKGArchitect).any {
                it.path.segments == listOf("kgArchitect", "aliases", "supports")
            }
        )
        assertTrue(
            report.errorsFor(Role.CouncilKGArchitect).any {
                it.path.segments.take(2) == listOf("seedCurator", "primordialKgFacts")
            }
        )
        assertTrue(
            report.errorsFor(Role.CouncilSeedCurator).any {
                it.path.segments.take(2) == listOf("seedCurator", "primordialKgFacts")
            }
        )
        assertTrue(
            setOf(
                Role.CouncilTaskAnatomist,
                Role.CouncilKGArchitect,
                Role.CouncilSeedCurator
            ).none(report.frozenValidRoleHashes::containsKey)
        )
    }

    @Test
    fun `distinct stage-key count closes before ordered schedule authority exists`() {
        val base = CouncilSpecialistBundle.from(fixtures.outputs())
        val bundle = base.copy(
            taskAnatomist = base.taskAnatomist.copy(
                nodes = base.taskAnatomist.nodes.mapIndexed { index, node ->
                    if (index == 0) node else node.copy(stageKey = "later")
                }
            ),
            ecologist = base.ecologist.copy(
                checkinRounds = listOf(1),
                leakIntervalRounds = 1,
                dispatchLagRounds = 0
            )
        )

        val report = CouncilSpecialistClosureValidator.validate(
            runId = "distinct-stage-count",
            envelope = fixtures.envelope().copy(maxRounds = 1),
            bundle = bundle
        )

        assertTrue(
            report.errorsFor(Role.CouncilTaskAnatomist).any {
                it.code == FieldCompileErrorCode.ENVELOPE_VIOLATION &&
                    it.path.segments == listOf("taskAnatomist", "nodes", "stageKey")
            }
        )
    }

    @Test
    fun `normal field without Task-owned test authority routes repair before synthesis`() {
        val base = CouncilSpecialistBundle.from(fixtures.outputs())
        val testless = base.copy(
            taskAnatomist = base.taskAnatomist.copy(
                nodes = base.taskAnatomist.nodes.map { node ->
                    node.copy(
                        proofContract = node.proofContract.copy(
                            executableTestIds = emptySet()
                        ),
                        toolOpportunity = node.toolOpportunity?.copy(
                            promotionTestIds = emptySet()
                        )
                    )
                }
            )
        )

        val report = CouncilSpecialistClosureValidator.validate(
            runId = "normal-testless-specialist-closure",
            envelope = fixtures.envelope(),
            bundle = testless
        )
        val error = report.errors.single {
            it.path.segments ==
                listOf(
                    "taskAnatomist",
                    "nodes",
                    "proofContract",
                    "executableTestIds"
                )
        }

        assertEquals(FieldCompileErrorCode.ENVELOPE_VIOLATION, error.code)
        assertEquals(setOf(Role.CouncilTaskAnatomist), error.responsibleCouncilRoles)
        assertTrue(error.message.contains("at least one executable or promotion test"))
        assertTrue(Role.CouncilTaskAnatomist !in report.frozenValidRoleHashes)
    }

    @Test
    fun `tool reuse resolves semantic and deterministic references but rejects self use`() {
        val runId = "specialist-tool-reuse"
        val base = CouncilSpecialistBundle.from(fixtures.outputs())
        val origin = base.taskAnatomist.nodes.first()
        val downstream = base.taskAnatomist.nodes.last()

        fun reportFor(reference: NodeId): CouncilClosureReport {
            val task = base.taskAnatomist.copy(
                nodes = base.taskAnatomist.nodes.map { node ->
                    if (node.key == origin.key) {
                        node.copy(toolOpportunity = opportunity(origin, reference))
                    } else {
                        node
                    }
                }
            )
            return CouncilSpecialistClosureValidator.validate(
                runId = runId,
                envelope = fixtures.envelope(),
                bundle = base.copy(taskAnatomist = task)
            )
        }

        assertTrue(reportFor(NodeId(downstream.key.value)).isClosed)
        assertTrue(
            reportFor(
                NodeId(CouncilDeterministicIds.nodeId(runId, downstream.key))
            ).isClosed
        )
        val selfUse = reportFor(NodeId(origin.key.value))
        assertTrue(
            selfUse.errorsFor(Role.CouncilTaskAnatomist).any {
                it.code == FieldCompileErrorCode.INVALID_NODE_REFERENCE &&
                    it.path.segments.last() == "expectedReuseNodeIds"
            }
        )
    }

    private fun opportunity(
        origin: LocalLimitationNode,
        expectedReuseNodeId: NodeId
    ): ToolOpportunity = ToolOpportunity(
        opportunityId = "specialist-reuse-opportunity",
        opportunityType = ToolOpportunityType.CALCULATION,
        repeatedQuestionPattern = "Evaluate one deterministic typed input.",
        requiredInputs = listOf(
            TypedField("input", TypedFieldType.NUMBER, "Bounded numeric input.")
        ),
        requiredOutputs = listOf(
            TypedField("result", TypedFieldType.NUMBER, "Deterministic numeric result.")
        ),
        validationStrategy = "Execute the exact committed fixture.",
        expectedReuseNodeIds = setOf(expectedReuseNodeId),
        preferredImplementationMode = ToolImplementationMode.PYTHON,
        behavior = ToolBehavior.DETERMINISTIC,
        promotionTestIds = origin.proofContract.executableTestIds,
        maximumRuntimeMillis = 1_000,
        maximumOutputBytes = 4_096
    )
}
