package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import kotlinx.serialization.Serializable

/**
 * Lifecycle state for an inferred idea that has not yet earned receiver-grade evidence.
 */
@Serializable
enum class HypothesisStatus {
    /** Newly proposed and eligible for provisional propagation. */
    PROPOSED,

    /** A shadow or minimal test has been run, but receiver-grade evidence is not established. */
    SHADOW_TESTED,

    /** A separate evidence-bound artifact now supports the idea. */
    EVIDENCE_BOUND,

    /** Accepted by an explicit downstream authority after evidence review. */
    ACCEPTED,

    /** Falsified or rejected by a downstream test or review. */
    REJECTED,

    /** Replaced by a more precise or better-supported hypothesis. */
    SUPERSEDED
}

/**
 * A structured, explicitly provisional inference that can be propagated to later agents.
 *
 * A hypothesis is not receiver evidence. Its [status] is intentionally required to remain
 * [HypothesisStatus.PROPOSED] at the staging boundary; a separate evidence-bound artifact and
 * authority transition are required before any later lifecycle can accept it.
 *
 * @param hypothesisId Stable hypothesis identity.
 * @param runId Run that produced the hypothesis.
 * @param groupId Group that produced the hypothesis.
 * @param round Generation/round in which the hypothesis was inferred.
 * @param agentId Agent that inferred the hypothesis.
 * @param title Short human-readable label.
 * @param idea The novel idea or proposed mechanism.
 * @param inferredFrom Observation or reasoning path that produced the inference.
 * @param assumptions Assumptions on which the inference depends.
 * @param uncertainty Known uncertainty and possible confounders.
 * @param expectedImpact Expected value if the idea is true.
 * @param falsifiablePrediction Observable prediction that could disconfirm the idea.
 * @param minimalTestOrArtifact Smallest useful test or artifact for checking the prediction.
 * @param confidence Subjective confidence in the inference, between 0 and 1.
 * @param provenanceRefs Stable references to source artifacts, reads, or observations.
 * @param status Current lifecycle status; staged payloads must remain PROPOSED.
 * @param hypothesisHash Self-hash over every field except this hash.
 */
@Serializable
data class ProvisionalNovelHypothesis(
    val hypothesisId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val agentId: AtlasAgentId,
    val title: String,
    val idea: String,
    val inferredFrom: String,
    val assumptions: List<String>,
    val uncertainty: String,
    val expectedImpact: String,
    val falsifiablePrediction: String,
    val minimalTestOrArtifact: String,
    val confidence: Double,
    val provenanceRefs: List<String>,
    val status: HypothesisStatus = HypothesisStatus.PROPOSED,
    val hypothesisHash: String = ""
) {
    init {
        require(hypothesisId.isNotBlank()) { "Hypothesis ID must not be blank" }
        require(runId.isNotBlank()) { "Hypothesis run ID must not be blank" }
        require(groupId.value.isNotBlank()) { "Hypothesis group ID must not be blank" }
        require(round >= 0) { "Hypothesis round must be non-negative" }
        require(agentId.value.isNotBlank()) { "Hypothesis agent ID must not be blank" }
        require(title.isNotBlank()) { "Hypothesis title must not be blank" }
        require(idea.isNotBlank()) { "Hypothesis idea must not be blank" }
        require(inferredFrom.isNotBlank()) { "Hypothesis provenance narrative must not be blank" }
        require(assumptions.isNotEmpty() && assumptions.all(String::isNotBlank)) {
            "Hypothesis must declare at least one non-blank assumption"
        }
        require(assumptions == assumptions.distinct()) {
            "Hypothesis assumptions must be distinct"
        }
        require(uncertainty.isNotBlank()) { "Hypothesis uncertainty must not be blank" }
        require(expectedImpact.isNotBlank()) { "Hypothesis expected impact must not be blank" }
        require(falsifiablePrediction.isNotBlank()) {
            "Hypothesis must declare a falsifiable prediction"
        }
        require(minimalTestOrArtifact.isNotBlank()) {
            "Hypothesis must declare a minimal test or artifact"
        }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Hypothesis confidence must be finite and between 0 and 1"
        }
        require(provenanceRefs.isNotEmpty() && provenanceRefs.all(String::isNotBlank)) {
            "Hypothesis must declare at least one provenance reference"
        }
        require(provenanceRefs == provenanceRefs.distinct().sorted()) {
            "Hypothesis provenance references must be distinct and sorted"
        }
        require(status == HypothesisStatus.PROPOSED) {
            "A provisional hypothesis payload cannot directly claim a downstream lifecycle status"
        }
    }

    /** Returns this hypothesis with its deterministic self-hash populated. */
    fun withComputedHash(): ProvisionalNovelHypothesis =
        copy(hypothesisHash = AtlasHashing.hashProvisionalNovelHypothesis(this))

    /** Returns whether the stored self-hash matches the immutable payload. */
    fun hasValidHash(): Boolean =
        hypothesisHash.isNotBlank() &&
            AtlasHashing.constantTimeEquals(
                hypothesisHash,
                AtlasHashing.hashProvisionalNovelHypothesis(this)
            )
}
