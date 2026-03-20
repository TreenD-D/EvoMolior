package com.achulkov.evokammer.competition.scoring

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

/**
 * Enforces the blind scoring protocol: scores are computed, sealed, and held until
 * all groups have submitted. The gate then opens and reveals all scores simultaneously.
 *
 * ### Protocol
 * 1. Watchdog calls [openGate] when a scoring round begins.
 * 2. Each group's results are submitted via [submitGroupResults].
 * 3. Once all groups submit (or the gate times out), [closeAndReveal] is called.
 * 4. All sealed scores are emitted on [revealedScores] at once.
 *
 * No partial scores are visible between [openGate] and [closeAndReveal].
 *
 * @param groupIds All competing group IDs that must submit before reveal.
 * @param gateTimeoutMs Maximum time (ms) to wait after [openGate] before force-revealing.
 */
class BlindSubmissionGate(
    private val groupIds: List<String>,
    private val gateTimeoutMs: Long = 300_000L // 5 minutes
) {

    private val _revealedScores = MutableSharedFlow<RevealedScores>(extraBufferCapacity = 32)

    /** Emitted when the gate closes and scores are revealed to all groups. */
    val revealedScores: SharedFlow<RevealedScores> = _revealedScores.asSharedFlow()

    // State per round
    private var currentRoundIndex: Int = -1
    private var gateOpenedAt: Long = 0L
    private val pendingScores = mutableMapOf<String, GroupRoundResult>()
    private var gateOpen: Boolean = false

    /**
     * Opens the gate for [roundIndex]. Resets all pending submission state.
     *
     * @throws IllegalStateException if a gate is already open.
     */
    fun openGate(roundIndex: Int) {
        check(!gateOpen) { "BlindSubmissionGate: gate for round $currentRoundIndex is already open" }
        currentRoundIndex = roundIndex
        gateOpenedAt = System.currentTimeMillis()
        pendingScores.clear()
        gateOpen = true
        Napier.i("BlindSubmissionGate: gate OPEN for round $roundIndex (waiting for ${groupIds.size} submissions)")
    }

    /**
     * Submits a group's results for the current round.
     *
     * Scores are held and NOT accessible until [closeAndReveal] is called.
     *
     * @throws IllegalStateException if the gate is not open.
     * @throws IllegalArgumentException if [groupId] is not in the known group list.
     */
    fun submitGroupResults(groupId: String, result: GroupRoundResult) {
        check(gateOpen) { "BlindSubmissionGate: gate is not open, cannot accept submission from $groupId" }
        require(groupId in groupIds) { "BlindSubmissionGate: unknown groupId=$groupId" }
        pendingScores[groupId] = result
        Napier.d("BlindSubmissionGate: received submission from $groupId (${pendingScores.size}/${groupIds.size})")
    }

    /**
     * Returns true if all groups have submitted their results for the current round.
     */
    fun allSubmitted(): Boolean = pendingScores.keys.containsAll(groupIds)

    /**
     * Returns true if the gate timeout has elapsed since [openGate] was called.
     */
    fun isTimedOut(): Boolean =
        gateOpen && (System.currentTimeMillis() - gateOpenedAt) > gateTimeoutMs

    /**
     * Closes the gate and emits all scores simultaneously on [revealedScores].
     *
     * Missing submissions get a default zero-score result.
     *
     * @param forceReveal If true, reveals even if not all groups have submitted (e.g. on timeout).
     */
    suspend fun closeAndReveal(forceReveal: Boolean = false) {
        check(gateOpen) { "BlindSubmissionGate: gate is not open, cannot reveal" }
        if (!forceReveal && !allSubmitted()) {
            Napier.w("BlindSubmissionGate: closeAndReveal called before all groups submitted — force=$forceReveal")
            if (!forceReveal) return
        }

        gateOpen = false

        // Fill in missing groups with zero scores
        val finalScores = groupIds.associateWith { groupId ->
            pendingScores[groupId] ?: GroupRoundResult(
                groupId = groupId,
                roundIndex = currentRoundIndex,
                dimensionScores = emptyMap(),
                failureModesTriggered = emptyList(),
                automatedTestsPassed = 0,
                automatedTestsFailed = 0,
                isDefaultZeroScore = true
            )
        }

        val revealed = RevealedScores(
            roundIndex = currentRoundIndex,
            scores = finalScores,
            revealedAt = System.currentTimeMillis(),
            wasForced = forceReveal,
            missingGroupIds = groupIds.filter { it !in pendingScores }
        )

        _revealedScores.emit(revealed)
        Napier.i("BlindSubmissionGate: gate CLOSED for round $currentRoundIndex — scores revealed (forced=$forceReveal)")
        pendingScores.clear()
    }
}

/**
 * A single group's round results, held under the blind gate until reveal.
 *
 * @param groupId The group this result belongs to.
 * @param roundIndex The round index.
 * @param dimensionScores Score per dimension ID.
 * @param failureModesTriggered Failure mode IDs that were detected for this group.
 * @param automatedTestsPassed Count of automated tests that passed.
 * @param automatedTestsFailed Count of automated tests that failed.
 * @param isDefaultZeroScore True if this was auto-generated due to missing submission.
 */
@Serializable
data class GroupRoundResult(
    val groupId: String,
    val roundIndex: Int,
    val dimensionScores: Map<String, Double>,
    val failureModesTriggered: List<String>,
    val automatedTestsPassed: Int,
    val automatedTestsFailed: Int,
    val isDefaultZeroScore: Boolean = false
) {
    /** Total raw score before weighting. */
    val totalRaw: Double get() = dimensionScores.values.sum()
}

/**
 * The revealed scores emitted by [BlindSubmissionGate.revealedScores].
 *
 * @param roundIndex The round index.
 * @param scores Map of groupId -> [GroupRoundResult].
 * @param revealedAt Epoch ms when the gate opened.
 * @param wasForced True if this reveal was triggered by timeout rather than all groups submitting.
 * @param missingGroupIds Groups that did not submit before the gate closed.
 */
@Serializable
data class RevealedScores(
    val roundIndex: Int,
    val scores: Map<String, GroupRoundResult>,
    val revealedAt: Long,
    val wasForced: Boolean,
    val missingGroupIds: List<String>
)
