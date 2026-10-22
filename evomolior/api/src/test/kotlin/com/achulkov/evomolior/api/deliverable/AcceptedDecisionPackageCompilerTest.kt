@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.deliverable

import com.achulkov.evomolior.api.service.GeneratedOutputFile
import com.achulkov.evomolior.core.deliverable.AcceptedDeliverableAuthority
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
import com.achulkov.evomolior.core.deliverable.DeliverableComponentKind
import com.achulkov.evomolior.core.deliverable.DeliverableComponentSpec
import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.DeliverableSemanticBlock
import com.achulkov.evomolior.core.deliverable.InterimDeliverableSnapshot
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class AcceptedDecisionPackageCompilerTest {
    @Test
    fun `exact accepted authorities compile deterministically with complete provenance`() {
        val first = compile()
        val second = compile()

        assertEquals(first, second)
        assertEquals(RunOutcomeClass.ACCEPTED_DECISION_PACKAGE, first.outcomeClass)
        assertTrue(first.hasValidHashes())
        assertTrue(first.knownFailures.isEmpty())
        assertTrue(first.missingAuthority.isEmpty())
        assertEquals(authority(), first.acceptedAuthority)
        assertTrue(first.components.all {
            it.authorityClass == ComponentAuthorityClass.ACCEPTED &&
                it.provenance.evidenceIds == setOf(
                    "accepted-final-winner-snapshot",
                    "accepted-winner-authority",
                    "accepted-final-writer-output",
                ) &&
                it.blocks.filterIsInstance<DeliverableSemanticBlock.Narrative>()
                    .single().text == OUTPUT_CONTENT
        })
        assertEquals(
            listOf(hash(OUTPUT_CONTENT), hash("winner"), snapshot().snapshotHash).sorted(),
            first.evidenceLedger.map { it.evidenceHash }.sorted(),
        )
    }

    @Test
    fun `foreign run blueprint and winner group fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            compile(snapshot = snapshot().copy(runId = "other-run").withComputedHash())
        }
        assertFailsWith<IllegalArgumentException> {
            compile(blueprint = blueprint("other-blueprint"))
        }
        assertFailsWith<IllegalArgumentException> {
            compile(output = output().copy(sourceGroupId = "other-group"))
        }
    }

    @Test
    fun `invalid hashes and incomplete component acceptance fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            compile(snapshot = snapshot().copy(snapshotHash = hash("forged")))
        }
        assertFailsWith<IllegalArgumentException> {
            compile(output = output().copy(contentHash = hash("forged")))
        }
        assertFailsWith<IllegalArgumentException> {
            compile(authority = authority().copy(writerOutputHash = hash("forged")))
        }
        assertFailsWith<IllegalArgumentException> {
            compile(
                snapshot = snapshot().copy(
                    acceptedComponentIds = listOf("summary"),
                    provisionalComponentIds = listOf("recommendation"),
                ).withComputedHash(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            compile(
                snapshot = snapshot().copy(failedGateIds = listOf("gate-open"))
                    .withComputedHash(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            compile(
                snapshot = snapshot().copy(
                    missingAuthorityComponentIds = listOf("recommendation"),
                ).withComputedHash(),
            )
        }
    }

    @Test
    fun `unsafe writer and private structural markers fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            compile(output = output(content = "Include private evaluator reasoning."))
        }
        assertFailsWith<IllegalArgumentException> {
            compile(output = output().copy(relativePath = "../accepted.md"))
        }
        assertFailsWith<IllegalArgumentException> {
            compile(output = output().copy(sourceSubmissionId = "private-submission"))
        }
        assertFailsWith<IllegalArgumentException> {
            compile(output = output().copy(sourceRole = Role.Critic))
        }
    }

    private fun compile(
        blueprint: DeliverableBlueprint = blueprint(),
        snapshot: InterimDeliverableSnapshot = snapshot(),
        authority: AcceptedDeliverableAuthority = authority(),
        output: GeneratedOutputFile = output(),
    ) = AcceptedDecisionPackageCompiler.compile(
        AcceptedDecisionPackageRequest(
            blueprint = blueprint,
            runId = RUN_ID,
            winnerGroupId = GROUP_ID,
            finalWinnerSnapshot = snapshot,
            acceptedAuthority = authority,
            generatedOutput = output,
            generatedAt = GENERATED_AT,
        )
    )

    private fun blueprint(id: String = "accepted-blueprint") = DeliverableBlueprint(
        blueprintId = id,
        receiverContractHash = hash("receiver"),
        audience = "Public operator",
        decisionOrUse = "Use the accepted result",
        primaryFormat = DeliverableFormat.MARKDOWN,
        companionFormats = setOf(DeliverableFormat.JSON),
        componentSpecs = listOf(
            DeliverableComponentSpec(
                componentId = "summary",
                kind = DeliverableComponentKind.EXECUTIVE_SUMMARY,
                required = true,
                sourceNodeIds = setOf("node-a"),
                minimumAuthority = ComponentAuthorityClass.ACCEPTED,
            ),
            DeliverableComponentSpec(
                componentId = "recommendation",
                kind = DeliverableComponentKind.RECOMMENDATION,
                required = true,
                sourceNodeIds = setOf("node-b"),
                minimumAuthority = ComponentAuthorityClass.ACCEPTED,
            ),
        ),
    ).withComputedHash()

    private fun snapshot() = InterimDeliverableSnapshot(
        runId = RUN_ID,
        round = 4,
        groupId = GROUP_ID,
        blueprintHash = blueprint().blueprintHash,
        acceptedComponentIds = listOf("recommendation", "summary"),
        provisionalComponentIds = emptyList(),
        withheldComponentIds = emptyList(),
        failedGateIds = emptyList(),
        evidenceRefs = listOf("public-evidence-a", "public-evidence-b"),
        missingAuthorityComponentIds = emptyList(),
    ).withComputedHash()

    private fun authority() = AcceptedDeliverableAuthority(
        winnerAuthorityId = "winner-decision-1",
        winnerAuthorityHash = hash("winner"),
        sourceAuthorityIds = setOf("node-a", "node-b"),
        writerAuthorityId = "writer-1",
        writerOutputHash = hash(OUTPUT_CONTENT),
    )

    private fun output(content: String = OUTPUT_CONTENT) = GeneratedOutputFile(
        relativePath = "accepted/result.md",
        mediaType = "text/markdown",
        content = content,
        sourceGroupId = GROUP_ID,
        sourceAgentId = "writer-1",
        sourceSubmissionId = "writer-doc-1",
        sourceRole = Role.Builder,
        componentNodeIds = mapOf(
            "summary" to setOf("node-a"),
            "recommendation" to setOf("node-b"),
        ),
        componentLineageSubmissionId = "writer-lineage-1",
        componentLineageSubmittedAtMs = 1_721_776_400_000L,
    )

    private fun hash(value: String): String = DeliverableHashing.hashText(value)

    private companion object {
        const val RUN_ID = "run-accepted"
        const val GROUP_ID = "group-winner"
        const val OUTPUT_CONTENT = "Use option A based on the published evidence."
        val GENERATED_AT: Instant = Instant.parse("2026-07-24T12:00:00Z")
    }
}
