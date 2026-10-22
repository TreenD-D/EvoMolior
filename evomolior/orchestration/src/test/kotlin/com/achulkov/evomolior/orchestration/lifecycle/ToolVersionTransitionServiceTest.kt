@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

class ToolVersionTransitionServiceTest {
    private val runId = "run-tool-transition"
    private val groupId = "group-tool-transition"
    private val nodeId = NodeId("node-origin")

    @Test
    fun `failed maintenance preserves parent and later maintain fork retire preserve lineage`() =
        runTest {
            val root = createTempDirectory("tool-version-transition-").toFile()
            val database = SubstrateDatabaseFactory(root).get(runId, groupId)
            try {
                val commitment = commitment().withComputedHash()
                val promotionService = ToolPromotionService(
                    database = database,
                    runId = runId,
                    groupId = groupId,
                    toolsMountHostPath = File(root, "tools").absolutePath,
                    opportunityResolver = { commitment },
                    sealedPromotionTestResolver = {
                        listOf(
                            SealedPromotionTestFixture(
                                id = "identity-test",
                                input = Json.parseToJsonElement("""{"value":1.0}"""),
                                expectedOutput = Json.parseToJsonElement(
                                    """{"normalized":1.0}"""
                                )
                            )
                        )
                    }
                )
                assertEquals(
                    ToolPromotionResult.Promoted("normalize-v1"),
                    promotionService.promote(submission("initial", "normalize"), "critic")
                )
                val transitions = ToolVersionTransitionService(
                    database = database,
                    promotionService = promotionService
                )

                val failed = transitions.maintain(
                    parentVersionId = "normalize-v1",
                    submission = submission(
                        id = "failed-maintenance",
                        name = "normalize",
                        code = "cat >/dev/null\nsleep 1\necho '{\"normalized\":1}'"
                    ),
                    criticAgentId = "critic",
                    maintenanceCost = 1.0,
                    reason = "Failed bounded repair."
                )

                assertIs<ToolVersionTransitionResult.Rejected>(failed)
                assertEquals("active", database.toolRegistryDao().getById("normalize-v1")?.status)
                assertEquals(
                    ToolLifecycleState.PROMOTED,
                    ToolInfrastructureRepository(database)
                        .lifecycle(ToolVersionId("normalize-v1")).last().state
                )

                assertEquals(
                    ToolVersionTransitionResult.Committed(
                        ToolLifecycleState.MAINTAINED,
                        "normalize-v3"
                    ),
                    transitions.maintain(
                        parentVersionId = "normalize-v1",
                        submission = submission("maintained", "normalize"),
                        criticAgentId = "critic",
                        maintenanceCost = 2.0,
                        reason = "Validated bounded repair."
                    )
                )
                val maintained = database.toolRegistryDao().getById("normalize-v3")
                assertEquals("normalize-v1", maintained?.previousVersionId)
                assertEquals("archived", database.toolRegistryDao().getById("normalize-v1")?.status)
                assertEquals(
                    listOf("normalize-v3"),
                    database.toolRegistryDao().getActive(runId, groupId, null).map { it.id }
                )

                assertEquals(
                    ToolVersionTransitionResult.Committed(
                        ToolLifecycleState.FORKED,
                        "normalize-fork-v1"
                    ),
                    transitions.fork(
                        parentVersionId = "normalize-v3",
                        submission = submission("forked", "normalize-fork"),
                        criticAgentId = "critic",
                        maintenanceCost = 3.0,
                        reason = "Specialized immutable branch."
                    )
                )
                val fork = database.toolRegistryDao().getById("normalize-fork-v1")
                assertEquals("normalize-v3", fork?.previousVersionId)
                assertEquals("archived", database.toolRegistryDao().getById("normalize-v3")?.status)
                assertEquals(
                    listOf("normalize-fork-v1"),
                    database.toolRegistryDao().getActive(runId, groupId, null).map { it.id }
                )

                assertEquals(
                    ToolVersionTransitionResult.Committed(ToolLifecycleState.RETIRED, null),
                    transitions.retire(
                        parentVersionId = "normalize-fork-v1",
                        maintenanceCost = 0.5,
                        reason = "Superseded after downstream validation."
                    )
                )
                assertEquals(
                    "archived",
                    database.toolRegistryDao().getById("normalize-fork-v1")?.status
                )
                assertEquals(
                    emptyList(),
                    database.toolRegistryDao().getActive(runId, groupId, null)
                )
                val repository = ToolInfrastructureRepository(database)
                assertEquals(
                    ToolLifecycleState.MAINTAINED,
                    repository.lifecycle(ToolVersionId("normalize-v1")).last().state
                )
                assertEquals(
                    ToolLifecycleState.FORKED,
                    repository.lifecycle(ToolVersionId("normalize-v3")).last().state
                )
                assertEquals(
                    ToolLifecycleState.RETIRED,
                    repository.lifecycle(ToolVersionId("normalize-fork-v1")).last().state
                )
            } finally {
                database.close()
            }
        }

    private fun commitment() = ToolOpportunityCommitment(
        runId = runId,
        atlasHash = "atlas-hash",
        nodeId = nodeId,
        opportunity = ToolOpportunity(
            opportunityId = "opportunity-normalize",
            opportunityType = ToolOpportunityType.TRANSFORMATION,
            repeatedQuestionPattern = "Normalize each bounded value.",
            requiredInputs = listOf(
                TypedField("value", TypedFieldType.NUMBER, "Input value.")
            ),
            requiredOutputs = listOf(
                TypedField("normalized", TypedFieldType.NUMBER, "Normalized value.")
            ),
            validationStrategy = "Run the committed identity case.",
            expectedReuseNodeIds = setOf(NodeId("node-later")),
            preferredImplementationMode = ToolImplementationMode.SHELL,
            behavior = ToolBehavior.DETERMINISTIC,
            requiredCapabilities = setOf("shell"),
            promotionTestIds = setOf("identity-test"),
            maximumRuntimeMillis = 500,
            maximumOutputBytes = 4_096,
            assumptions = listOf("Input is finite."),
            knownInvalidDomains = listOf("Non-numeric input.")
        )
    )

    private fun submission(
        id: String,
        name: String,
        code: String = "cat >/dev/null\necho '{\"normalized\":1.0}'"
    ): StagingSubmissionEntity {
        val opportunity = commitment().opportunity
        val inputSchema = TypedToolSchemaCompiler.compile(opportunity.requiredInputs)
        val outputSchema = TypedToolSchemaCompiler.compile(opportunity.requiredOutputs)
        return StagingSubmissionEntity(
            id = id,
            runId = runId,
            groupId = groupId,
            agentId = "builder",
            role = "Builder",
            type = "TOOL",
            content = """
                {
                  "opportunityId": "${opportunity.opportunityId}",
                  "name": "$name",
                  "domain": "calculation",
                  "language": "shell",
                  "inputSchema": $inputSchema,
                  "outputSchema": $outputSchema,
                  "code": ${JsonPrimitive(code)},
                  "tests": [
                    {
                      "testId": "identity-test",
                      "input": {"value":1.0},
                      "expectedOutput": {"normalized":1.0}
                    }
                  ]
                }
            """.trimIndent(),
            wipTaskRef = nodeId.value,
            budgetSignature = "signature",
            generation = 1,
            submittedAtMs = 1L
        )
    }
}
