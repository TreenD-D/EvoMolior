@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.deliverable

import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
import com.achulkov.evomolior.core.deliverable.DeliverableComponentKind
import com.achulkov.evomolior.core.deliverable.DeliverableComponentSpec
import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import com.achulkov.evomolior.core.deliverable.InterimDeliverableSnapshot
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProvisionalDecisionPackageCompilerTest {
    @Test
    fun `multi-source no-candidate compiles a valid provisional package`() {
        val result = compiler(
            snapshots = listOf(snapshot("group-b", "b"), snapshot("group-a", "a")),
            groups = setOf("group-a", "group-b"),
        )

        assertEquals(RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE, result.decisionPackage.outcomeClass)
        assertTrue(result.decisionPackage.hasValidHashes())
        assertTrue(result.recoverySourceSet.hasValidHash())
        assertEquals(listOf("group-a", "group-b"), result.recoverySourceSet.sourceGroupIds)
        assertTrue(
            result.decisionPackage.knownFailures.any {
                it.gateId == "accepted-winner-authority-unresolved"
            }
        )
        assertTrue(
            result.decisionPackage.knownFailures.any {
                it.gateId == "accepted-writer-authority-unresolved"
            }
        )
        assertTrue(result.decisionPackage.knownFailures.any { it.gateId == "gate-a" })
        assertTrue(result.decisionPackage.knownFailures.any { it.gateId == "gate-b" })
    }

    @Test
    fun `source comparison uses neutral labels and contains no rank`() {
        val result = compiler(
            snapshots = listOf(snapshot("group-b", "b"), snapshot("group-a", "a")),
            groups = setOf("group-a", "group-b"),
        )
        val matrix = result.decisionPackage.components
            .single { it.kind == DeliverableComponentKind.DECISION_MATRIX }
            .blocks
            .single() as com.achulkov.evomolior.core.deliverable.DeliverableSemanticBlock.Table

        assertEquals(
            mapOf("group-a" to "NON_ACCEPTED_SOURCE_A", "group-b" to "NON_ACCEPTED_SOURCE_B"),
            result.recoverySourceSet.sourceLabels,
        )
        assertEquals(listOf("NON_ACCEPTED_SOURCE_A", "NON_ACCEPTED_SOURCE_B"), matrix.rows.map { it[0] })
        assertFalse(matrix.columns.any { it.contains("rank", ignoreCase = true) })
        assertFalse(matrix.rows.flatten().any { it.contains("winner", ignoreCase = true) })
    }

    @Test
    fun `missing duplicate and foreign sources are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            compiler(
                snapshots = listOf(snapshot("group-a", "a")),
                groups = setOf("group-a", "group-b"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            compiler(
                snapshots = listOf(snapshot("group-a", "a"), snapshot("group-a", "b")),
                groups = setOf("group-a"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            compiler(
                snapshots = listOf(snapshot("group-a", "a", runId = "other-run")),
                groups = setOf("group-a"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProvisionalDecisionPackageCompiler.compile(
                ProvisionalDecisionPackageRequest(
                    blueprint = blueprint(),
                    runId = RUN_ID,
                    eligibleGroupIds = setOf("group-a"),
                    latestSnapshots = listOf(
                        snapshot("group-a", "a").copy(blueprintHash = hash("other-blueprint"))
                            .withComputedHash()
                    ),
                    generatedAt = Instant.parse("2026-07-24T00:00:00Z"),
                )
            )
        }
    }

    @Test
    fun `package has no accepted authority or private markers`() {
        val result = compiler(
            snapshots = listOf(snapshot("group-a", "a")),
            groups = setOf("group-a"),
        )

        assertNull(result.decisionPackage.acceptedAuthority)
        assertTrue(result.decisionPackage.components.none {
            it.authorityClass == ComponentAuthorityClass.ACCEPTED
        })
        val recommendation = result.decisionPackage.components.single {
            it.kind == DeliverableComponentKind.RECOMMENDATION
        }
        assertEquals(ComponentAuthorityClass.PROVISIONAL, recommendation.authorityClass)
        assertTrue(recommendation.blocks.isNotEmpty())
        assertTrue(result.decisionPackage.decisionOrUse.startsWith(
            com.achulkov.evomolior.core.deliverable.PROVISIONAL_DECISION_OR_USE_PREFIX
        ))
        assertTrue(result.decisionPackage.components.none {
            it.kind == DeliverableComponentKind.RECOMMENDATION &&
                it.authorityClass == ComponentAuthorityClass.WITHHELD
        })
        assertTrue(
            result.decisionPackage.missingAuthority.single {
                it.componentId == "recommendation"
            }.publicReason.contains("winner") &&
                result.decisionPackage.missingAuthority.single {
                    it.componentId == "recommendation"
                }.publicReason.contains("writer")
        )
        assertEquals(
            ComponentAuthorityClass.ACCEPTED,
            result.decisionPackage.missingAuthority.single {
                it.componentId == "recommendation"
            }.requiredAuthority,
        )
        val rendered = DeterministicDeliverableRenderers.renderAll(
            result.decisionPackage,
            blueprint().companionFormats + blueprint().primaryFormat,
        )
        assertTrue(rendered.all { artifact ->
            artifact.artifacts.none { it.content.contains("submission.ipynb") }
        })
        assertFalse(result.decisionPackage.toString().contains("private", ignoreCase = true))
        assertFalse(result.recoverySourceSet.toString().contains("private", ignoreCase = true))
        assertFailsWith<IllegalArgumentException> {
            compiler(
                snapshots = listOf(snapshot("private-group", "a")),
                groups = setOf("private-group"),
            )
        }
    }

    @Test
    fun `opaque operational evidence identities are counted but never republished`() {
        val operationalRefs = listOf(
            "/private/workspace/tests/author-tests.json",
            "agent-critic-private-rationale",
        )
        val result = compiler(
            snapshots = listOf(
                snapshot("group-a", "a").copy(evidenceRefs = operationalRefs.sorted())
                    .withComputedHash()
            ),
            groups = setOf("group-a"),
        )

        assertTrue(result.decisionPackage.hasValidHashes())
        assertTrue(result.decisionPackage.toString().contains("Evidence reference count"))
        operationalRefs.forEach { reference ->
            assertFalse(result.decisionPackage.toString().contains(reference))
            assertFalse(result.recoverySourceSet.toString().contains(reference))
        }
    }

    private fun compiler(
        snapshots: List<InterimDeliverableSnapshot>,
        groups: Set<String>,
    ) = ProvisionalDecisionPackageCompiler.compile(
        ProvisionalDecisionPackageRequest(
            blueprint = blueprint(),
            runId = RUN_ID,
            eligibleGroupIds = groups,
            latestSnapshots = snapshots,
            generatedAt = Instant.parse("2026-07-24T00:00:00Z"),
        )
    )

    private fun blueprint() = DeliverableBlueprint(
        blueprintId = "blueprint-structural",
        receiverContractHash = hash("receiver"),
        audience = "Public operator",
        decisionOrUse = "Review unresolved authority",
        primaryFormat = DeliverableFormat.MARKDOWN,
        companionFormats = setOf(DeliverableFormat.JSON),
        componentSpecs = listOf(
            spec("summary", DeliverableComponentKind.EXECUTIVE_SUMMARY, ComponentAuthorityClass.PROVISIONAL),
            spec("matrix", DeliverableComponentKind.DECISION_MATRIX, ComponentAuthorityClass.PROVISIONAL),
            spec("recommendation", DeliverableComponentKind.RECOMMENDATION, ComponentAuthorityClass.PROVISIONAL),
            spec("ledger", DeliverableComponentKind.EVIDENCE_LEDGER, ComponentAuthorityClass.PROVISIONAL),
            spec("assumptions", DeliverableComponentKind.ASSUMPTIONS, ComponentAuthorityClass.PROVISIONAL),
            spec("uncertainties", DeliverableComponentKind.UNCERTAINTIES, ComponentAuthorityClass.PROVISIONAL),
            spec("risks", DeliverableComponentKind.RISK_REGISTER, ComponentAuthorityClass.PROVISIONAL),
            spec("limitations", DeliverableComponentKind.LIMITATIONS_AND_NEXT_EVIDENCE, ComponentAuthorityClass.PROVISIONAL),
        ),
    ).withComputedHash()

    private fun spec(
        id: String,
        kind: DeliverableComponentKind,
        authority: ComponentAuthorityClass,
    ) = DeliverableComponentSpec(
        componentId = id,
        kind = kind,
        required = true,
        minimumAuthority = authority,
    )

    private fun snapshot(
        groupId: String,
        marker: String,
        runId: String = RUN_ID,
    ) = InterimDeliverableSnapshot(
        runId = runId,
        round = 2,
        groupId = groupId,
        blueprintHash = blueprint().blueprintHash,
        acceptedComponentIds = emptyList(),
        provisionalComponentIds = listOf("summary"),
        withheldComponentIds = listOf("recommendation"),
        failedGateIds = listOf("gate-$marker"),
        evidenceRefs = listOf("evidence-$marker"),
        missingAuthorityComponentIds = listOf("recommendation"),
    ).withComputedHash()

    private fun hash(value: String): String =
        com.achulkov.evomolior.core.deliverable.DeliverableHashing.hashText(value)

    private companion object {
        const val RUN_ID = "run-provisional"
    }
}
