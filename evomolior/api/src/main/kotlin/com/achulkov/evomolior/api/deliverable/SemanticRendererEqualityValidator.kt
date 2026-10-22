package com.achulkov.evomolior.api.deliverable

import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage

/** Verifies that every deterministic renderer carries the exact same semantic package. */
object SemanticRendererEqualityValidator {
    /**
     * Fails when artifact integrity, package commitments, or embedded semantics disagree.
     *
     * @param expected Exact source semantic package.
     * @param rendered Outputs to compare.
     */
    fun requireEquivalent(
        expected: ReceiverDecisionPackage,
        rendered: List<RenderedDeliverable>,
    ) {
        require(expected.hasValidHashes()) { "Expected semantic package hash is invalid" }
        require(rendered.isNotEmpty()) { "At least one rendered output is required" }
        require(rendered.map { it.format }.distinct().size == rendered.size) {
            "Only one output per renderer format may be compared"
        }
        val canonicalExpected = DeliverableHashing.encodePackage(expected)
        rendered.forEach { output ->
            require(output.semanticPackageHash == expected.packageHash) {
                "${output.format} advertises a different semantic package hash"
            }
            output.artifacts.forEach { artifact ->
                require(
                    DeliverableHashing.constantTimeEquals(
                        artifact.contentHash,
                        DeliverableHashing.hashText(artifact.content)
                    )
                ) { "${output.format} artifact ${artifact.path} failed content hashing" }
            }
            val decoded = extractEmbeddedPackage(output)
            require(decoded.hasValidHashes()) {
                "${output.format} embeds an invalid semantic package"
            }
            require(
                DeliverableHashing.encodePackage(decoded) == canonicalExpected
            ) { "${output.format} changes semantic values, authority, or provenance" }

            /*
             * Embedded canonical JSON proves carriage, but not that the receiver-visible
             * projection is honest. Exact comparison with the deterministic renderer catches
             * omitted components and changed values, authority labels, tables, or provenance in
             * the Markdown, HTML, and CSV artifacts themselves.
             */
            val canonicalProjection = DeterministicDeliverableRenderers
                .renderer(output.format)
                .render(expected)
            require(output.artifacts == canonicalProjection.artifacts) {
                "${output.format} receiver-visible projection differs from the semantic package"
            }
        }
    }

    /**
     * Verifies that [rendered] covers [requiredFormats] exactly once and is semantically equal.
     *
     * @param expected Exact source semantic package.
     * @param requiredFormats Formats required by a blueprint.
     * @param rendered Outputs to validate.
     */
    fun requireCompleteEquivalentSet(
        expected: ReceiverDecisionPackage,
        requiredFormats: Set<DeliverableFormat>,
        rendered: List<RenderedDeliverable>,
    ) {
        require(rendered.map { it.format }.toSet() == requiredFormats) {
            "Rendered formats do not exactly match the blueprint"
        }
        requireEquivalent(expected, rendered)
    }
}
