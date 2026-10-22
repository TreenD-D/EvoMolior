@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.deliverable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class DeliverableFoundationTest {
    @Test
    fun `blueprint requires human and machine formats and stable self hash`() {
        val blueprint = blueprint()

        DeliverableContractValidator.requireValidBlueprint(blueprint)
        assertTrue(blueprint.hasValidHash())
        assertEquals(
            blueprint.blueprintHash,
            blueprint.copy(
                companionFormats = linkedSetOf(
                    DeliverableFormat.CSV_BUNDLE,
                    DeliverableFormat.JSON,
                    DeliverableFormat.HTML5,
                )
            ).withComputedHash().blueprintHash,
        )

        val humanOnly = blueprint.copy(
            companionFormats = setOf(DeliverableFormat.HTML5),
            blueprintHash = "",
        ).withComputedHash()
        assertFailsWith<IllegalArgumentException> {
            DeliverableContractValidator.requireValidBlueprint(humanOnly)
        }
    }

    @Test
    fun `assembler preserves only supplied semantics and records missing authority`() {
        val blueprint = blueprint()
        val acceptedEvidence = evidence("evidence-summary", ComponentAuthorityClass.ACCEPTED)
        val provisionalEvidence = evidence("evidence-options", ComponentAuthorityClass.PROVISIONAL)

        val decisionPackage = DeterministicDeliverableAssembler.assemble(
            DeliverableAssemblyRequest(
                blueprint = blueprint,
                packageId = "package-1",
                runId = "run-1",
                outcomeClass = RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
                suppliedComponents = listOf(
                    component(
                        id = "summary",
                        kind = DeliverableComponentKind.EXECUTIVE_SUMMARY,
                        authority = ComponentAuthorityClass.ACCEPTED,
                        evidenceId = acceptedEvidence.evidenceId,
                        block = DeliverableSemanticBlock.Narrative(
                            text = "Supplied receiver narrative.",
                            origin = DeliverableContentOrigin.MODEL_AUTHORED_SUPPLIED,
                        ),
                    ),
                    component(
                        id = "options",
                        kind = DeliverableComponentKind.DECISION_MATRIX,
                        authority = ComponentAuthorityClass.PROVISIONAL,
                        evidenceId = provisionalEvidence.evidenceId,
                        block = DeliverableSemanticBlock.Table(
                            columns = listOf("Option", "Cost"),
                            rows = listOf(listOf("A", "10")),
                        ),
                    ),
                ),
                evidenceLedger = listOf(provisionalEvidence, acceptedEvidence),
                knownFailures = listOf(failedGate()),
                missingEvidenceByComponent = mapOf("options" to setOf("private-simulator-pass")),
                missingAuthorityReasons = mapOf(
                    "options" to "Promoted simulator authority is absent."
                ),
                generatedAt = Instant.parse("2026-07-24T12:00:00Z"),
            )
        )

        assertTrue(decisionPackage.hasValidHashes())
        assertEquals(listOf("summary", "options"), decisionPackage.components.map {
            it.componentId
        })
        assertEquals(
            "Supplied receiver narrative.",
            (decisionPackage.components.first().blocks.single() as
                DeliverableSemanticBlock.Narrative).text,
        )
        assertEquals(
            setOf("private-simulator-pass"),
            decisionPackage.missingAuthority.single().missingEvidenceIds,
        )
        assertEquals(null, decisionPackage.acceptedAuthority)
        DeliverableContractValidator.requireValidPackage(blueprint, decisionPackage)
    }

    @Test
    fun `missing required component becomes empty withheld structure rather than invented content`() {
        val blueprint = blueprint()
        val acceptedEvidence = evidence("evidence-summary", ComponentAuthorityClass.ACCEPTED)

        val decisionPackage = DeterministicDeliverableAssembler.assemble(
            DeliverableAssemblyRequest(
                blueprint = blueprint,
                packageId = "package-2",
                runId = "run-1",
                outcomeClass = RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
                suppliedComponents = listOf(
                    component(
                        id = "summary",
                        kind = DeliverableComponentKind.EXECUTIVE_SUMMARY,
                        authority = ComponentAuthorityClass.ACCEPTED,
                        evidenceId = acceptedEvidence.evidenceId,
                        block = DeliverableSemanticBlock.Narrative(
                            "Only supplied semantics.",
                            DeliverableContentOrigin.DETERMINISTIC_SUPPLIED,
                        ),
                    )
                ),
                evidenceLedger = listOf(acceptedEvidence),
                knownFailures = listOf(failedGate()),
                generatedAt = Instant.parse("2026-07-24T12:00:00Z"),
            )
        )

        val withheld = decisionPackage.components.single { it.componentId == "options" }
        assertEquals(ComponentAuthorityClass.WITHHELD, withheld.authorityClass)
        assertTrue(withheld.blocks.isEmpty())
        assertTrue(withheld.provenance.evidenceIds.isEmpty())
        assertEquals("options", decisionPackage.missingAuthority.single().componentId)
    }

    @Test
    fun `accepted package requires winner source writer chain and accepted evidence`() {
        val blueprint = blueprint()
        val summaryEvidence = evidence("evidence-summary", ComponentAuthorityClass.ACCEPTED)
        val optionsEvidence = evidence("evidence-options", ComponentAuthorityClass.ACCEPTED)

        assertFailsWith<IllegalArgumentException> {
            DeterministicDeliverableAssembler.assemble(
                acceptedRequest(
                    blueprint = blueprint,
                    summaryEvidence = summaryEvidence,
                    optionsEvidence = optionsEvidence,
                    acceptedAuthority = null,
                )
            )
        }

        val accepted = DeterministicDeliverableAssembler.assemble(
            acceptedRequest(
                blueprint = blueprint,
                summaryEvidence = summaryEvidence,
                optionsEvidence = optionsEvidence,
                acceptedAuthority = acceptedAuthority(),
            )
        )
        assertEquals(RunOutcomeClass.ACCEPTED_DECISION_PACKAGE, accepted.outcomeClass)
        assertTrue(accepted.missingAuthority.isEmpty())
        assertTrue(accepted.components.all {
            it.authorityClass == ComponentAuthorityClass.ACCEPTED
        })
    }

    @Test
    fun `provisional decision use can be explicitly caveated while accepted use stays exact`() {
        val blueprint = blueprint()
        val provisional = DeterministicDeliverableAssembler.assemble(
            acceptedRequest(
                blueprint = blueprint,
                summaryEvidence = evidence("evidence-summary", ComponentAuthorityClass.ACCEPTED),
                optionsEvidence = evidence("evidence-options", ComponentAuthorityClass.ACCEPTED),
                acceptedAuthority = null,
            ).copy(
                outcomeClass = RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
                knownFailures = listOf(failedGate()),
                acceptedAuthority = null,
                suppliedComponents = acceptedRequest(
                    blueprint = blueprint,
                    summaryEvidence = evidence(
                        "evidence-summary",
                        ComponentAuthorityClass.ACCEPTED,
                    ),
                    optionsEvidence = evidence(
                        "evidence-options",
                        ComponentAuthorityClass.ACCEPTED,
                    ),
                    acceptedAuthority = null,
                ).suppliedComponents.map { component ->
                    if (component.componentId == "options") {
                        component.copy(authorityClass = ComponentAuthorityClass.PROVISIONAL)
                    } else {
                        component
                    }
                },
                decisionOrUseOverride =
                    "Provisional output only: inspect preserved evidence and failed gates.",
            )
        )

        assertTrue(provisional.decisionOrUse.startsWith(PROVISIONAL_DECISION_OR_USE_PREFIX))
        DeliverableContractValidator.requireValidPackage(blueprint, provisional)
        assertFailsWith<IllegalArgumentException> {
            DeterministicDeliverableAssembler.assemble(
                acceptedRequest(
                    blueprint = blueprint,
                    summaryEvidence = evidence(
                        "evidence-summary",
                        ComponentAuthorityClass.ACCEPTED,
                    ),
                    optionsEvidence = evidence(
                        "evidence-options",
                        ComponentAuthorityClass.ACCEPTED,
                    ),
                    acceptedAuthority = acceptedAuthority(),
                ).copy(
                    decisionOrUseOverride =
                        "Provisional output only: this must not alter accepted semantics.",
                )
            )
        }
    }

    @Test
    fun `model authored origin is rejected for deterministic table semantics`() {
        assertFailsWith<IllegalArgumentException> {
            DeliverableSemanticBlock.Table(
                columns = listOf("Metric"),
                rows = listOf(listOf("42")),
                origin = DeliverableContentOrigin.MODEL_AUTHORED_SUPPLIED,
            )
        }
    }

    @Test
    fun `semantic hashes change with content authority and provenance`() {
        val base = component(
            id = "summary",
            kind = DeliverableComponentKind.EXECUTIVE_SUMMARY,
            authority = ComponentAuthorityClass.ACCEPTED,
            evidenceId = "evidence-summary",
            block = DeliverableSemanticBlock.Narrative(
                "Base",
                DeliverableContentOrigin.DETERMINISTIC_SUPPLIED,
            ),
        )
        val changedContent = base.copy(
            blocks = listOf(
                DeliverableSemanticBlock.Narrative(
                    "Changed",
                    DeliverableContentOrigin.DETERMINISTIC_SUPPLIED,
                )
            )
        )
        val changedAuthority = base.copy(authorityClass = ComponentAuthorityClass.PROVISIONAL)
        val changedProvenance = base.copy(
            provenance = base.provenance.copy(sourceNodeIds = setOf("node-2"))
        )

        assertNotEquals(
            DeliverableHashing.hashText(""),
            base.let {
                DeliverableComponent(
                    it.componentId,
                    it.kind,
                    it.title,
                    it.authorityClass,
                    it.blocks,
                    it.provenance,
                    it.uncertainty,
                ).withComputedHash().contentHash
            },
        )
        assertNotEquals(
            hashSupplied(base),
            hashSupplied(changedContent),
        )
        assertNotEquals(hashSupplied(base), hashSupplied(changedAuthority))
        assertNotEquals(hashSupplied(base), hashSupplied(changedProvenance))
    }

    @Test
    fun `interim snapshot is deterministic and authority classified`() {
        val blueprint = blueprint()
        val summaryEvidence = evidence("evidence-summary", ComponentAuthorityClass.ACCEPTED)
        val provisionalEvidence = evidence("evidence-options", ComponentAuthorityClass.PROVISIONAL)
        val decisionPackage = DeterministicDeliverableAssembler.assemble(
            DeliverableAssemblyRequest(
                blueprint = blueprint,
                packageId = "package-snapshot",
                runId = "run-1",
                outcomeClass = RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
                suppliedComponents = listOf(
                    component(
                        "summary",
                        DeliverableComponentKind.EXECUTIVE_SUMMARY,
                        ComponentAuthorityClass.ACCEPTED,
                        summaryEvidence.evidenceId,
                        DeliverableSemanticBlock.Narrative(
                            "Summary",
                            DeliverableContentOrigin.DETERMINISTIC_SUPPLIED,
                        ),
                    ),
                    component(
                        "options",
                        DeliverableComponentKind.DECISION_MATRIX,
                        ComponentAuthorityClass.PROVISIONAL,
                        provisionalEvidence.evidenceId,
                        DeliverableSemanticBlock.Table(
                            listOf("Option"),
                            listOf(listOf("A")),
                        ),
                    ),
                ),
                evidenceLedger = listOf(provisionalEvidence, summaryEvidence),
                knownFailures = listOf(failedGate()),
                generatedAt = Instant.parse("2026-07-24T12:00:00Z"),
            )
        )

        val first = InterimDeliverableSnapshotAssembler.assemble(
            decisionPackage,
            round = 3,
            groupId = "group-a",
        )
        val second = InterimDeliverableSnapshotAssembler.assemble(
            decisionPackage,
            round = 3,
            groupId = "group-a",
        )

        assertEquals(first, second)
        assertEquals(listOf("summary"), first.acceptedComponentIds)
        assertEquals(listOf("options"), first.provisionalComponentIds)
        assertEquals(listOf("options"), first.missingAuthorityComponentIds)
        assertTrue(first.hasValidHash())
    }

    private fun acceptedRequest(
        blueprint: DeliverableBlueprint,
        summaryEvidence: DeliverableEvidenceLink,
        optionsEvidence: DeliverableEvidenceLink,
        acceptedAuthority: AcceptedDeliverableAuthority?,
    ): DeliverableAssemblyRequest = DeliverableAssemblyRequest(
        blueprint = blueprint,
        packageId = "package-accepted",
        runId = "run-1",
        outcomeClass = RunOutcomeClass.ACCEPTED_DECISION_PACKAGE,
        suppliedComponents = listOf(
            component(
                "summary",
                DeliverableComponentKind.EXECUTIVE_SUMMARY,
                ComponentAuthorityClass.ACCEPTED,
                summaryEvidence.evidenceId,
                DeliverableSemanticBlock.Narrative(
                    "Accepted summary.",
                    DeliverableContentOrigin.MODEL_AUTHORED_SUPPLIED,
                ),
            ),
            component(
                "options",
                DeliverableComponentKind.DECISION_MATRIX,
                ComponentAuthorityClass.ACCEPTED,
                optionsEvidence.evidenceId,
                DeliverableSemanticBlock.Table(
                    listOf("Option", "Score"),
                    listOf(listOf("A", "1.0")),
                ),
            ),
        ),
        evidenceLedger = listOf(summaryEvidence, optionsEvidence),
        knownFailures = emptyList(),
        acceptedAuthority = acceptedAuthority,
        generatedAt = Instant.parse("2026-07-24T12:00:00Z"),
    )

    private fun hashSupplied(component: SuppliedDeliverableComponent): String =
        DeliverableComponent(
            component.componentId,
            component.kind,
            component.title,
            component.authorityClass,
            component.blocks,
            component.provenance,
            component.uncertainty,
        ).withComputedHash().contentHash

    private fun blueprint(): DeliverableBlueprint = DeliverableBlueprint(
        blueprintId = "blueprint-1",
        receiverContractHash = hash('r'),
        audience = "Municipal emergency manager",
        decisionOrUse = "Choose a heat-risk intervention",
        primaryFormat = DeliverableFormat.MARKDOWN,
        companionFormats = linkedSetOf(
            DeliverableFormat.HTML5,
            DeliverableFormat.JSON,
            DeliverableFormat.CSV_BUNDLE,
        ),
        componentSpecs = listOf(
            DeliverableComponentSpec(
                componentId = "summary",
                kind = DeliverableComponentKind.EXECUTIVE_SUMMARY,
                required = true,
                sourceNodeIds = setOf("node-1"),
                minimumAuthority = ComponentAuthorityClass.ACCEPTED,
            ),
            DeliverableComponentSpec(
                componentId = "options",
                kind = DeliverableComponentKind.DECISION_MATRIX,
                required = true,
                sourceNodeIds = setOf("node-1"),
                minimumAuthority = ComponentAuthorityClass.ACCEPTED,
            ),
            DeliverableComponentSpec(
                componentId = "risks",
                kind = DeliverableComponentKind.RISK_REGISTER,
                required = false,
                minimumAuthority = ComponentAuthorityClass.PROVISIONAL,
            ),
        ),
    ).withComputedHash()

    private fun component(
        id: String,
        kind: DeliverableComponentKind,
        authority: ComponentAuthorityClass,
        evidenceId: String,
        block: DeliverableSemanticBlock,
    ): SuppliedDeliverableComponent = SuppliedDeliverableComponent(
        componentId = id,
        kind = kind,
        title = id.replaceFirstChar(Char::uppercase),
        authorityClass = authority,
        blocks = listOf(block),
        provenance = DeliverableComponentProvenance(
            evidenceIds = setOf(evidenceId),
            sourceNodeIds = setOf("node-1"),
            evidencePacketIds = setOf("packet-1"),
            grades = listOf(
                DeliverableGradeReference(
                    gradeId = "grade-1",
                    gradeHash = hash('g'),
                    score = 0.8,
                )
            ),
            tests = listOf(
                DeliverableTestReference(
                    testId = "public-test-1",
                    resultHash = hash('t'),
                    status = DeliverableTestStatus.PASSED,
                )
            ),
            toolExecutionIds = setOf("tool-run-1"),
            simulationExecutionIds = setOf("simulation-run-1"),
        ),
        uncertainty = "Supplied uncertainty.",
    )

    private fun evidence(
        id: String,
        authority: ComponentAuthorityClass,
    ): DeliverableEvidenceLink = DeliverableEvidenceLink(
        evidenceId = id,
        evidenceHash = hash(id.first()),
        authorityClass = authority,
        disclosure = DeliverableEvidenceDisclosure.PUBLIC_COMMITTED,
        sourceNodeId = "node-1",
        evidencePacketId = "packet-1",
        publicSummary = "Public committed evidence.",
        provenanceHashes = setOf(hash('p')),
    )

    private fun failedGate(): FailedAcceptanceGate = FailedAcceptanceGate(
        gateId = "simulator-promotion",
        code = "PRIVATE_TEST_FAILED",
        publicMessage = "The promoted simulator authority is absent.",
        authorityHash = hash('f'),
    )

    private fun acceptedAuthority(): AcceptedDeliverableAuthority =
        AcceptedDeliverableAuthority(
            winnerAuthorityId = "winner-1",
            winnerAuthorityHash = hash('w'),
            sourceAuthorityIds = setOf("source-1"),
            writerAuthorityId = "writer-1",
            writerOutputHash = hash('o'),
        )

    private fun hash(character: Char): String =
        DeliverableHashing.hashText(character.toString())
}
