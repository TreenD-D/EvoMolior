package com.achulkov.evomolior.api.deliverable

import com.achulkov.evomolior.core.deliverable.AcceptedDeliverableAuthority
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
import com.achulkov.evomolior.core.deliverable.DeliverableComponent
import com.achulkov.evomolior.core.deliverable.DeliverableComponentKind
import com.achulkov.evomolior.core.deliverable.DeliverableContractValidator
import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.DeliverableSemanticBlock
import com.achulkov.evomolior.core.deliverable.InterimDeliverableSnapshot
import com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One manifest entry binding a package-relative artifact.
 *
 * @param path Artifact path.
 * @param mediaType Artifact media type.
 * @param contentHash Exact content commitment.
 */
@Serializable
data class DeliverableManifestEntry(
    val path: String,
    val mediaType: String,
    val contentHash: String,
)

/**
 * Deterministic manifest for a blueprint-standard virtual package.
 *
 * The manifest does not list its own file, avoiding a recursive self-hash.
 *
 * @param schemaVersion Manifest schema version.
 * @param packageId Semantic package ID.
 * @param outcomeClass Package authority class.
 * @param blueprintHash Exact blueprint commitment.
 * @param semanticPackageHash Exact semantic package commitment.
 * @param entries Sorted non-manifest file commitments.
 * @param unavailableStandardPaths Blueprint-standard paths not supplied by this isolated layer.
 * @param manifestHash Self-hash over all preceding fields.
 */
@Serializable
data class DeliverablePackageManifest(
    val schemaVersion: String = "deliverable-package-manifest-v1",
    val packageId: String,
    val outcomeClass: RunOutcomeClass,
    val blueprintHash: String,
    val semanticPackageHash: String,
    val entries: List<DeliverableManifestEntry>,
    val unavailableStandardPaths: List<String>,
    val manifestHash: String,
) {
    /** Returns whether [manifestHash] commits the complete non-manifest file inventory. */
    fun hasValidHash(): Boolean =
        DeliverableHashing.constantTimeEquals(
            manifestHash,
            computeManifestHash(
                schemaVersion,
                packageId,
                outcomeClass,
                blueprintHash,
                semanticPackageHash,
                entries,
                unavailableStandardPaths,
            )
        )
}

/**
 * In-memory blueprint-standard package file tree.
 *
 * @param rootName Suggested archive root name.
 * @param files Sorted files, including the manifest.
 * @param manifest Parsed package manifest.
 */
data class BlueprintStandardPackage(
    val rootName: String,
    val files: List<RenderedDeliverableArtifact>,
    val manifest: DeliverablePackageManifest,
)

/**
 * Compiles accepted or provisional semantic output into the completion-blueprint file layout.
 *
 * It emits only facts already carried by the semantic package and supplied interim snapshots.
 * Runtime metrics and publication history remain explicitly unavailable until their owning
 * services integrate this foundation.
 */
object BlueprintStandardPackageCompiler {
    /**
     * Compiles and verifies one virtual package.
     *
     * @param blueprint Exact source blueprint.
     * @param decisionPackage Exact semantic package.
     * @param interimSnapshots Optional already-hashed round snapshots.
     * @return Deterministic virtual file tree.
     */
    fun compile(
        blueprint: DeliverableBlueprint,
        decisionPackage: ReceiverDecisionPackage,
        interimSnapshots: List<InterimDeliverableSnapshot> = emptyList(),
    ): BlueprintStandardPackage {
        DeliverableContractValidator.requireValidPackage(blueprint, decisionPackage)
        require(interimSnapshots.all {
            it.hasValidHash() &&
                it.runId == decisionPackage.runId &&
                it.blueprintHash == blueprint.blueprintHash
        }) { "Interim snapshots must be valid and belong to the package run and blueprint" }

        val formats = blueprint.companionFormats + blueprint.primaryFormat
        val rendered = DeterministicDeliverableRenderers.renderAll(
            decisionPackage,
            formats,
        )
        SemanticRendererEqualityValidator.requireCompleteEquivalentSet(
            decisionPackage,
            formats,
            rendered,
        )

        val files = linkedMapOf<String, RenderedDeliverableArtifact>()
        rendered.flatMap { it.artifacts }.forEach { artifact ->
            addUnique(files, artifact)
        }
        val csvLayoutArtifacts = CsvBundleDeliverableRenderer
            .render(decisionPackage)
            .artifacts
            .associateBy { it.path }
        csvLayoutArtifacts["evidence-ledger.csv"]?.let { artifact ->
            files.putIfAbsent(artifact.path, artifact)
        }
        addSemanticSidecars(files, decisionPackage, csvLayoutArtifacts)
        interimSnapshots.sortedWith(compareBy({ it.round }, { it.groupId })).forEach {
            addUnique(
                files,
                RenderedDeliverableArtifact(
                    path = "round-summaries/round-${it.round}-${safeFileToken(it.groupId)}.json",
                    mediaType = "application/json",
                    content = snapshotJson.encodeToString(
                        InterimDeliverableSnapshot.serializer(),
                        it
                    ),
                ),
            )
        }

        val unavailable = when (decisionPackage.outcomeClass) {
            RunOutcomeClass.ACCEPTED_DECISION_PACKAGE -> listOf(
                "authority/publication-history.json",
                "run-metrics-v1.json",
            )
            RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE -> listOf(
                "run-metrics-v1.json",
            )
            RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE -> emptyList()
        }
        addUnique(
            files,
            readmeArtifact(decisionPackage, unavailable)
        )
        if (decisionPackage.outcomeClass == RunOutcomeClass.ACCEPTED_DECISION_PACKAGE) {
            addUnique(
                files,
                acceptedAuthorityArtifact(requireNotNull(decisionPackage.acceptedAuthority))
            )
        }

        val sortedEntries = files.values.sortedBy { it.path }.map {
            DeliverableManifestEntry(
                path = it.path,
                mediaType = it.mediaType,
                contentHash = it.contentHash,
            )
        }
        val computedManifestHash = computeManifestHash(
            "deliverable-package-manifest-v1",
            decisionPackage.packageId,
            decisionPackage.outcomeClass,
            blueprint.blueprintHash,
            decisionPackage.packageHash,
            sortedEntries,
            unavailable,
        )
        val manifest = DeliverablePackageManifest(
            packageId = decisionPackage.packageId,
            outcomeClass = decisionPackage.outcomeClass,
            blueprintHash = blueprint.blueprintHash,
            semanticPackageHash = decisionPackage.packageHash,
            entries = sortedEntries,
            unavailableStandardPaths = unavailable.sorted(),
            manifestHash = computedManifestHash,
        )
        val manifestPath = when (decisionPackage.outcomeClass) {
            RunOutcomeClass.ACCEPTED_DECISION_PACKAGE -> "manifest.json"
            RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE -> "recovery-manifest.json"
            RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE -> "diagnostic-manifest.json"
        }
        addUnique(
            files,
            RenderedDeliverableArtifact(
                path = manifestPath,
                mediaType = "application/json",
                content = manifestJson.encodeToString(
                    DeliverablePackageManifest.serializer(),
                    manifest
                ),
            ),
        )
        return BlueprintStandardPackage(
            rootName = when (decisionPackage.outcomeClass) {
                RunOutcomeClass.ACCEPTED_DECISION_PACKAGE -> "results.zip"
                RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE -> "recovery-results.zip"
                RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE -> "startup-diagnostic.zip"
            },
            files = files.values.sortedBy { it.path },
            manifest = manifest,
        )
    }

    private fun addSemanticSidecars(
        files: MutableMap<String, RenderedDeliverableArtifact>,
        decisionPackage: ReceiverDecisionPackage,
        csvLayoutArtifacts: Map<String, RenderedDeliverableArtifact>,
    ) {
        decisionPackage.components.forEachIndexed { componentIndex, component ->
            component.blocks.forEachIndexed { blockIndex, block ->
                if (block !is DeliverableSemanticBlock.Table) return@forEachIndexed
                val sourcePath = "tables/${componentIndex.toString().padStart(3, '0')}-" +
                    "${blockIndex.toString().padStart(3, '0')}.csv"
                val source = files[sourcePath]
                    ?: csvLayoutArtifacts[sourcePath]
                    ?: return@forEachIndexed
                if (component.kind == DeliverableComponentKind.DECISION_MATRIX && blockIndex == 0) {
                    addUnique(
                        files,
                        source.copy(
                            path = if (
                                decisionPackage.outcomeClass ==
                                RunOutcomeClass.ACCEPTED_DECISION_PACKAGE
                            ) {
                                "option-comparison.csv"
                            } else {
                                "source-comparison.csv"
                            }
                        ),
                    )
                }
                if (component.kind == DeliverableComponentKind.TOOL_AND_SIMULATION_RESULTS) {
                    addUnique(
                        files,
                        source.copy(
                            path = "tool-results/${componentIndex.toString().padStart(3, '0')}-" +
                                "${blockIndex.toString().padStart(3, '0')}.csv"
                        ),
                    )
                }
            }
        }
        val executive = decisionPackage.components.ofKinds(
            DeliverableComponentKind.EXECUTIVE_SUMMARY
        )
        if (executive.isNotEmpty()) {
            addMarkdownSidecar(
                files,
                decisionPackage,
                "executive-brief.md",
                "Executive brief",
                executive,
            )
        }
        val assumptions = decisionPackage.components.ofKinds(
            DeliverableComponentKind.ASSUMPTIONS,
            DeliverableComponentKind.UNCERTAINTIES,
        )
        if (assumptions.isNotEmpty()) {
            addMarkdownSidecar(
                files,
                decisionPackage,
                "assumptions-and-uncertainties.md",
                "Assumptions and uncertainties",
                assumptions,
            )
        }
        val risks = decisionPackage.components.ofKinds(DeliverableComponentKind.RISK_REGISTER)
        if (risks.isNotEmpty()) {
            addMarkdownSidecar(
                files,
                decisionPackage,
                "risk-register.md",
                "Risk register",
                risks,
            )
        }
        val implementation = decisionPackage.components.ofKinds(
            DeliverableComponentKind.IMPLEMENTATION_PLAN,
            DeliverableComponentKind.ACTION_CHECKLIST,
        )
        if (implementation.isNotEmpty()) {
            addMarkdownSidecar(
                files,
                decisionPackage,
                "implementation-checklist.md",
                "Implementation checklist",
                implementation,
            )
        }
        val nextEvidence = decisionPackage.components.ofKinds(
            DeliverableComponentKind.LIMITATIONS_AND_NEXT_EVIDENCE
        )
        if (nextEvidence.isNotEmpty() &&
            decisionPackage.outcomeClass == RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE) {
            addMarkdownSidecar(
                files,
                decisionPackage,
                "recommended-next-evidence.md",
                "Recommended next evidence",
                nextEvidence,
            )
        }
        if (decisionPackage.outcomeClass == RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE) {
            addUnique(
                files,
                RenderedDeliverableArtifact(
                    path = "failed-mandatory-tests.md",
                    mediaType = "text/markdown; charset=utf-8",
                    content = buildString {
                        appendLine("# Failed mandatory gates")
                        decisionPackage.knownFailures.forEach {
                            appendLine()
                            appendLine("## ${it.gateId}")
                            appendLine()
                            appendLine("- Code: `${it.code}`")
                            appendLine("- Authority hash: `${it.authorityHash}`")
                            appendLine()
                            appendLine(it.publicMessage)
                        }
                    }.trimEnd() + "\n",
                ),
            )
            addUnique(
                files,
                RenderedDeliverableArtifact(
                    path = "missing-authority.md",
                    mediaType = "text/markdown; charset=utf-8",
                    content = buildString {
                        appendLine("# Missing authority")
                        decisionPackage.missingAuthority.forEach {
                            appendLine()
                            appendLine("## ${it.componentId}")
                            appendLine()
                            appendLine("- Required: ${it.requiredAuthority.name}")
                            appendLine("- Observed: ${it.observedAuthority?.name ?: "NONE"}")
                            appendLine(
                                "- Missing evidence: " +
                                    it.missingEvidenceIds.sorted().ifEmpty { listOf("none") }
                                        .joinToString(", ")
                            )
                            appendLine()
                            appendLine(it.publicReason)
                        }
                    }.trimEnd() + "\n",
                ),
            )
        }
    }

    private fun addMarkdownSidecar(
        files: MutableMap<String, RenderedDeliverableArtifact>,
        decisionPackage: ReceiverDecisionPackage,
        path: String,
        heading: String,
        components: List<DeliverableComponent>,
    ) {
        addUnique(
            files,
            RenderedDeliverableArtifact(
                path = path,
                mediaType = "text/markdown; charset=utf-8",
                content = MarkdownSemanticWriter.render(
                    decisionPackage = decisionPackage,
                    components = components,
                    heading = heading,
                    includePackageHeader = false,
                ) + "\n",
            ),
        )
    }

    private fun readmeArtifact(
        decisionPackage: ReceiverDecisionPackage,
        unavailable: List<String>,
    ): RenderedDeliverableArtifact {
        val path = when (decisionPackage.outcomeClass) {
            RunOutcomeClass.ACCEPTED_DECISION_PACKAGE -> "README.md"
            RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE -> "README-NON-ACCEPTED.md"
            RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE -> "README-DIAGNOSTIC.md"
        }
        return RenderedDeliverableArtifact(
            path = path,
            mediaType = "text/markdown; charset=utf-8",
            content = buildString {
                appendLine("# ${decisionPackage.authorityBanner()}")
                appendLine()
                appendLine("- Audience: ${decisionPackage.audience}")
                appendLine("- Decision or use: ${decisionPackage.decisionOrUse}")
                appendLine("- Semantic package hash: `${decisionPackage.packageHash}`")
                if (unavailable.isNotEmpty()) {
                    appendLine()
                    appendLine("## Standard-path integration still required")
                    appendLine()
                    unavailable.forEach { appendLine("- `$it`") }
                }
            },
        )
    }

    private fun acceptedAuthorityArtifact(
        authority: AcceptedDeliverableAuthority,
    ): RenderedDeliverableArtifact =
        RenderedDeliverableArtifact(
            path = "authority/final-reference.json",
            mediaType = "application/json",
            content = authorityJson.encodeToString(
                AcceptedDeliverableAuthority.serializer(),
                authority,
            ),
        )

    private fun addUnique(
        files: MutableMap<String, RenderedDeliverableArtifact>,
        artifact: RenderedDeliverableArtifact,
    ) {
        require(files.putIfAbsent(artifact.path, artifact) == null) {
            "Package layout path collision: ${artifact.path}"
        }
    }

    private fun List<DeliverableComponent>.ofKinds(
        vararg kinds: DeliverableComponentKind,
    ): List<DeliverableComponent> {
        val selected = kinds.toSet()
        return filter { it.kind in selected }
    }

    private fun safeFileToken(value: String): String =
        value.map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_') {
                character
            } else {
                '_'
            }
        }.joinToString("").ifBlank { "group" }

    private val manifestJson = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }
    private val snapshotJson = Json {
        encodeDefaults = true
        explicitNulls = true
    }
    private val authorityJson = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }
}

private fun computeManifestHash(
    schemaVersion: String,
    packageId: String,
    outcomeClass: RunOutcomeClass,
    blueprintHash: String,
    semanticPackageHash: String,
    entries: List<DeliverableManifestEntry>,
    unavailableStandardPaths: List<String>,
): String = DeliverableHashing.hashText(
    buildString {
        append(schemaVersion).append('\n')
        append(packageId).append('\n')
        append(outcomeClass.name).append('\n')
        append(blueprintHash).append('\n')
        append(semanticPackageHash).append('\n')
        entries.sortedBy { it.path }.forEach {
            append(it.path).append('\u0000')
            append(it.mediaType).append('\u0000')
            append(it.contentHash).append('\n')
        }
        unavailableStandardPaths.sorted().forEach {
            append("unavailable:").append(it).append('\n')
        }
    }
)
