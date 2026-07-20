package com.achulkov.evomolior.core.benchmark

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.contract.StartingField
import kotlinx.serialization.Serializable

/**
 * Frozen runtime payload shared by every arm in a comparative experiment.
 *
 * [taskToolIds] contains only task-facing capabilities. Society protocol tools used for
 * contribution, coordination, and publication are not part of the matched task-tool bundle.
 *
 * @param receiverContract Exact receiver contract used by the Atlas.
 * @param atlas Exact sealed Node Atlas initialized before worker activation.
 * @param startingFieldTemplate Frozen Council field whose node projections correspond to [atlas].
 * @param taskToolIds Exact task-facing tool capability identifiers exposed to every arm.
 */
@Serializable
data class ComparativeRuntimeInputs(
    val receiverContract: ReceiverContract,
    val atlas: NodeAtlas,
    val startingFieldTemplate: StartingField,
    val taskToolIds: Set<String>
) {
    init {
        require(taskToolIds.isNotEmpty() && taskToolIds.all(String::isNotBlank)) {
            "Comparative task tool identifiers must not be empty or blank"
        }
        require(atlas.validate().isValid) {
            "Comparative runtime Atlas must be sealed and structurally valid"
        }
        require(atlas.receiverContractHash == AtlasHashing.hashReceiverContract(receiverContract)) {
            "Comparative runtime Atlas does not bind the supplied receiver contract"
        }
        require(
            startingFieldTemplate.runId == atlas.runId &&
                startingFieldTemplate.objective == receiverContract.rawObjective
        ) {
            "Comparative StartingField template does not bind the frozen run and objective"
        }
    }

    /** Deterministic hash of the exact task-facing tool allowlist. */
    fun taskToolBundleHash(): String =
        AtlasHashing.hashText(taskToolIds.toSortedSet().joinToString(separator = "\n"))

    /** Verifies this payload against the experiment's immutable commitments. */
    fun requireMatches(commitments: ComparativeInputCommitments) {
        require(atlas.atlasHash == commitments.atlasHash) {
            "Frozen comparative Atlas does not match the committed Atlas hash"
        }
        require(taskToolBundleHash() == commitments.toolBundleHash) {
            "Frozen comparative task tools do not match the committed tool bundle hash"
        }
    }
}
