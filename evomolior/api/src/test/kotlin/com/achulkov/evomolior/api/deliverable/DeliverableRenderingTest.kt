@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.deliverable

import com.achulkov.evomolior.core.deliverable.AcceptedDeliverableAuthority
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableAssemblyRequest
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
import com.achulkov.evomolior.core.deliverable.DeliverableComponentKind
import com.achulkov.evomolior.core.deliverable.DeliverableComponentProvenance
import com.achulkov.evomolior.core.deliverable.DeliverableComponentSpec
import com.achulkov.evomolior.core.deliverable.DeliverableContentOrigin
import com.achulkov.evomolior.core.deliverable.DeliverableEvidenceDisclosure
import com.achulkov.evomolior.core.deliverable.DeliverableEvidenceLink
import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.DeliverableSemanticBlock
import com.achulkov.evomolior.core.deliverable.DeterministicDeliverableAssembler
import com.achulkov.evomolior.core.deliverable.FailedAcceptanceGate
import com.achulkov.evomolior.core.deliverable.InterimDeliverableSnapshotAssembler
import com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.core.deliverable.SuppliedDeliverableComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class DeliverableRenderingTest {
    @Test
    fun `all renderer formats are deterministic and semantically equal`() {
        val fixture = provisionalFixture()
        val formats = fixture.blueprint.companionFormats + fixture.blueprint.primaryFormat

        val first = DeterministicDeliverableRenderers.renderAll(fixture.packageValue, formats)
        val second = DeterministicDeliverableRenderers.renderAll(fixture.packageValue, formats)

        assertEquals(first, second)
        SemanticRendererEqualityValidator.requireCompleteEquivalentSet(
            fixture.packageValue,
            formats,
            first,
        )
        assertTrue(first.single { it.format == DeliverableFormat.MARKDOWN }
            .artifacts.single().content.contains("PROVISIONAL NON-ACCEPTED"))
        assertTrue(first.single { it.format == DeliverableFormat.HTML5 }
            .artifacts.single().content.contains("&lt;unsafe&gt;"))
        assertTrue(first.single { it.format == DeliverableFormat.CSV_BUNDLE }
            .artifacts.single { it.path.startsWith("tables/") }
            .content.contains("\"A, B\""))
    }

    @Test
    fun `semantic equality rejects a renderer with tampered embedded package`() {
        val fixture = provisionalFixture()
        val rendered = MarkdownDeliverableRenderer.render(fixture.packageValue)
        val artifact = rendered.artifacts.single()
        val tampered = rendered.copy(
            artifacts = listOf(
                RenderedDeliverableArtifact(
                    path = artifact.path,
                    mediaType = artifact.mediaType,
                    content = artifact.content.replace(
                        "EVOMOLIOR_SEMANTIC_PACKAGE_V1:",
                        "EVOMOLIOR_SEMANTIC_PACKAGE_V0:",
                    ),
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            SemanticRendererEqualityValidator.requireEquivalent(
                fixture.packageValue,
                listOf(tampered),
            )
        }
    }

    @Test
    fun `semantic equality rejects changed visible markdown with intact embedded package`() {
        val fixture = provisionalFixture()
        val rendered = MarkdownDeliverableRenderer.render(fixture.packageValue)
        val artifact = rendered.artifacts.single()
        val tampered = rendered.copy(
            artifacts = listOf(
                RenderedDeliverableArtifact(
                    path = artifact.path,
                    mediaType = artifact.mediaType,
                    content = artifact.content.replace(
                        "Safe <unsafe> summary.",
                        "Changed receiver-visible summary.",
                    ),
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            SemanticRendererEqualityValidator.requireEquivalent(
                fixture.packageValue,
                listOf(tampered),
            )
        }
    }

    @Test
    fun `semantic equality rejects changed visible csv with intact semantic json`() {
        val fixture = provisionalFixture()
        val rendered = CsvBundleDeliverableRenderer.render(fixture.packageValue)
        val tampered = rendered.copy(
            artifacts = rendered.artifacts.map { artifact ->
                if (!artifact.path.startsWith("tables/")) {
                    artifact
                } else {
                    RenderedDeliverableArtifact(
                        path = artifact.path,
                        mediaType = artifact.mediaType,
                        content = artifact.content.replace("Provisional", "Accepted"),
                    )
                }
            }
        )

        assertFailsWith<IllegalArgumentException> {
            SemanticRendererEqualityValidator.requireEquivalent(
                fixture.packageValue,
                listOf(tampered),
            )
        }
    }

    @Test
    fun `provisional standard layout is explicit and manifest bound`() {
        val fixture = provisionalFixture()
        val snapshot = InterimDeliverableSnapshotAssembler.assemble(
            fixture.packageValue,
            round = 2,
            groupId = "group/a",
        )

        val compiled = BlueprintStandardPackageCompiler.compile(
            fixture.blueprint,
            fixture.packageValue,
            listOf(snapshot),
        )
        val paths = compiled.files.map { it.path }.toSet()

        assertEquals("recovery-results.zip", compiled.rootName)
        assertTrue("README-NON-ACCEPTED.md" in paths)
        assertTrue("provisional-decision-package.md" in paths)
        assertTrue("provisional-decision-package.html" in paths)
        assertTrue("source-comparison.csv" in paths)
        assertTrue("evidence-ledger.csv" in paths)
        assertTrue("failed-mandatory-tests.md" in paths)
        assertTrue("missing-authority.md" in paths)
        assertTrue("recommended-next-evidence.md" in paths)
        assertTrue("round-summaries/round-2-group_a.json" in paths)
        assertTrue("recovery-manifest.json" in paths)
        assertEquals(listOf("run-metrics-v1.json"), compiled.manifest.unavailableStandardPaths)
        assertTrue(compiled.manifest.hasValidHash())
        assertEquals(
            paths - "recovery-manifest.json",
            compiled.manifest.entries.map { it.path }.toSet(),
        )
    }

    @Test
    fun `accepted standard layout carries final reference but no recovery authority`() {
        val fixture = acceptedFixture()

        val compiled = BlueprintStandardPackageCompiler.compile(
            fixture.blueprint,
            fixture.packageValue,
        )
        val paths = compiled.files.map { it.path }.toSet()

        assertEquals("results.zip", compiled.rootName)
        assertTrue("README.md" in paths)
        assertTrue("decision-package.md" in paths)
        assertTrue("decision-package.html" in paths)
        assertTrue("option-comparison.csv" in paths)
        assertTrue("authority/final-reference.json" in paths)
        assertTrue("manifest.json" in paths)
        assertTrue("missing-authority.md" !in paths)
        assertTrue(compiled.manifest.hasValidHash())
        assertEquals(
            listOf("authority/publication-history.json", "run-metrics-v1.json"),
            compiled.manifest.unavailableStandardPaths,
        )
    }

    private fun provisionalFixture(): Fixture {
        val blueprint = blueprint()
        val acceptedEvidence = evidence("summary-evidence", ComponentAuthorityClass.ACCEPTED)
        val provisionalEvidence = evidence("options-evidence", ComponentAuthorityClass.PROVISIONAL)
        val packageValue = DeterministicDeliverableAssembler.assemble(
            DeliverableAssemblyRequest(
                blueprint = blueprint,
                packageId = "package-provisional",
                runId = "run-1",
                outcomeClass = RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
                suppliedComponents = listOf(
                    component(
                        "summary",
                        DeliverableComponentKind.EXECUTIVE_SUMMARY,
                        ComponentAuthorityClass.ACCEPTED,
                        acceptedEvidence.evidenceId,
                        DeliverableSemanticBlock.Narrative(
                            "Safe <unsafe> summary.",
                            DeliverableContentOrigin.MODEL_AUTHORED_SUPPLIED,
                        ),
                    ),
                    component(
                        "options",
                        DeliverableComponentKind.DECISION_MATRIX,
                        ComponentAuthorityClass.PROVISIONAL,
                        provisionalEvidence.evidenceId,
                        DeliverableSemanticBlock.Table(
                            columns = listOf("Option", "Note"),
                            rows = listOf(listOf("A, B", "Provisional")),
                        ),
                    ),
                    component(
                        "next-evidence",
                        DeliverableComponentKind.LIMITATIONS_AND_NEXT_EVIDENCE,
                        ComponentAuthorityClass.PROVISIONAL,
                        provisionalEvidence.evidenceId,
                        DeliverableSemanticBlock.Narrative(
                            "Run the supplied promotion test.",
                            DeliverableContentOrigin.DETERMINISTIC_SUPPLIED,
                        ),
                    ),
                ),
                evidenceLedger = listOf(provisionalEvidence, acceptedEvidence),
                knownFailures = listOf(
                    FailedAcceptanceGate(
                        gateId = "simulator",
                        code = "PRIVATE_TEST_FAILED",
                        publicMessage = "Simulator promotion did not pass.",
                        authorityHash = hash('f'),
                    )
                ),
                missingEvidenceByComponent = mapOf("options" to setOf("promoted-simulator")),
                missingAuthorityReasons = mapOf(
                    "options" to "Accepted simulator authority is missing."
                ),
                generatedAt = Instant.parse("2026-07-24T12:00:00Z"),
            )
        )
        return Fixture(blueprint, packageValue)
    }

    private fun acceptedFixture(): Fixture {
        val blueprint = blueprint()
        val summaryEvidence = evidence("summary-evidence", ComponentAuthorityClass.ACCEPTED)
        val optionsEvidence = evidence("options-evidence", ComponentAuthorityClass.ACCEPTED)
        val packageValue = DeterministicDeliverableAssembler.assemble(
            DeliverableAssemblyRequest(
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
                acceptedAuthority = AcceptedDeliverableAuthority(
                    winnerAuthorityId = "winner-1",
                    winnerAuthorityHash = hash('w'),
                    sourceAuthorityIds = setOf("source-1"),
                    writerAuthorityId = "writer-1",
                    writerOutputHash = hash('o'),
                ),
                generatedAt = Instant.parse("2026-07-24T12:00:00Z"),
            )
        )
        return Fixture(blueprint, packageValue)
    }

    private fun blueprint(): DeliverableBlueprint = DeliverableBlueprint(
        blueprintId = "blueprint-1",
        receiverContractHash = hash('r'),
        audience = "Emergency manager",
        decisionOrUse = "Choose an intervention",
        primaryFormat = DeliverableFormat.MARKDOWN,
        companionFormats = setOf(
            DeliverableFormat.HTML5,
            DeliverableFormat.JSON,
            DeliverableFormat.CSV_BUNDLE,
        ),
        componentSpecs = listOf(
            DeliverableComponentSpec(
                "summary",
                DeliverableComponentKind.EXECUTIVE_SUMMARY,
                required = true,
                sourceNodeIds = setOf("node-1"),
                minimumAuthority = ComponentAuthorityClass.ACCEPTED,
            ),
            DeliverableComponentSpec(
                "options",
                DeliverableComponentKind.DECISION_MATRIX,
                required = true,
                sourceNodeIds = setOf("node-1"),
                minimumAuthority = ComponentAuthorityClass.ACCEPTED,
            ),
            DeliverableComponentSpec(
                "next-evidence",
                DeliverableComponentKind.LIMITATIONS_AND_NEXT_EVIDENCE,
                required = false,
                sourceNodeIds = setOf("node-1"),
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
        title = id,
        authorityClass = authority,
        blocks = listOf(block),
        provenance = DeliverableComponentProvenance(
            evidenceIds = setOf(evidenceId),
            sourceNodeIds = setOf("node-1"),
            evidencePacketIds = setOf("packet-1"),
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
        publicSummary = "Supplied public evidence.",
    )

    private fun hash(character: Char): String =
        DeliverableHashing.hashText(character.toString())

    private data class Fixture(
        val blueprint: DeliverableBlueprint,
        val packageValue: ReceiverDecisionPackage,
    )
}
