package com.achulkov.evomolior.competition.scoring

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
    private val _sealAuditEvents = MutableSharedFlow<ScoreSealAudit>(extraBufferCapacity = 32)

    /** Emitted when the gate closes and scores are revealed to all groups. */
    val revealedScores: SharedFlow<RevealedScores> = _revealedScores.asSharedFlow()

    /** Emitted when the gate closes and the sealed-score audit record is materialised. */
    val sealAuditEvents: SharedFlow<ScoreSealAudit> = _sealAuditEvents.asSharedFlow()

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
     * @param forceReveal If true, reveals even if not all groups have submitted (e.g. on timeout).
     * @throws IncompleteBlindSubmissionException when any expected group is absent. A timeout may
     * close and audit the gate, but it cannot convert missing evidence into an authoritative score.
     */
    suspend fun closeAndReveal(forceReveal: Boolean = false): RevealedScores {
        return closeAndRevealDetailed(forceReveal).revealedScores
    }

    /**
     * Closes the gate and returns the reveal together with its synchronously materialised audit.
     *
     * The audit flow remains available for observers, but scoring authority must use this return
     * value so buffered flow delivery cannot race final response construction.
     *
     * @param forceReveal If true, attempts a forced reveal at the timeout boundary.
     * @return Reveal and its audit. The audit is null only when a non-forced premature close keeps
     * the gate open and returns no scores.
     */
    suspend fun closeAndRevealDetailed(forceReveal: Boolean = false): BlindRevealOutcome {
        check(gateOpen) { "BlindSubmissionGate: gate is not open, cannot reveal" }
        if (!forceReveal && !allSubmitted()) {
            Napier.w("BlindSubmissionGate: closeAndReveal called before all groups submitted — force=$forceReveal")
            return BlindRevealOutcome(
                revealedScores = RevealedScores(
                    roundIndex = currentRoundIndex,
                    scores = emptyMap(),
                    revealedAt = System.currentTimeMillis(),
                    wasForced = false,
                    missingGroupIds = groupIds.filter { it !in pendingScores }
                ),
                sealAudit = null
            )
        }

        val missingGroupIds = groupIds.filter { it !in pendingScores }
        gateOpen = false

        val revealed = RevealedScores(
            roundIndex = currentRoundIndex,
            scores = pendingScores.toMap(),
            revealedAt = System.currentTimeMillis(),
            wasForced = forceReveal,
            missingGroupIds = missingGroupIds
        )

        val sealAudit = ScoreSealAudit(
            roundIndex = currentRoundIndex,
            expectedGroupCount = groupIds.size,
            sealedGroupCount = pendingScores.size,
            missingGroupIds = revealed.missingGroupIds,
            forcedReveal = forceReveal,
            gateOpenedAt = gateOpenedAt,
            sealedAt = revealed.revealedAt,
            sealLatencyMs = revealed.revealedAt - gateOpenedAt
        )
        _sealAuditEvents.emit(sealAudit)

        pendingScores.clear()
        if (missingGroupIds.isNotEmpty()) {
            Napier.e(
                "BlindSubmissionGate: gate CLOSED without authority for round $currentRoundIndex " +
                    "missing=$missingGroupIds forced=$forceReveal"
            )
            throw IncompleteBlindSubmissionException(currentRoundIndex, missingGroupIds, forceReveal)
        }
        _revealedScores.emit(revealed)
        Napier.i("BlindSubmissionGate: gate CLOSED for round $currentRoundIndex — scores revealed (forced=$forceReveal)")
        return BlindRevealOutcome(revealedScores = revealed, sealAudit = sealAudit)
    }
}

/**
 * Atomic blind-gate close result used by final scoring authority.
 *
 * @param revealedScores Complete reveal produced by the gate.
 * @param sealAudit Synchronous seal audit, or null when a premature non-forced close left the gate
 * open.
 */
data class BlindRevealOutcome(
    val revealedScores: RevealedScores,
    val sealAudit: ScoreSealAudit?
)

/**
 * A single group's round results, held under the blind gate until reveal.
 *
 * @param groupId The group this result belongs to.
 * @param roundIndex The round index.
 * @param dimensionScores Score per dimension ID.
 * @param dimensionJustifications Grader-authored rationale per dimension ID, when available.
 * @param failureModesTriggered Failure mode IDs that were detected for this group.
 * @param automatedTestsPassed Count of automated tests that passed.
 * @param automatedTestsFailed Count of automated tests that failed.
 * @param isDefaultZeroScore Legacy decoding field. New authority paths never synthesize scores.
 */
@Serializable
data class GroupRoundResult(
    val groupId: String,
    val roundIndex: Int,
    val dimensionScores: Map<String, Double>,
    val dimensionJustifications: Map<String, String> = emptyMap(),
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

/**
 * Audit record emitted when the blind gate seals a round.
 *
 * @param roundIndex Round that was sealed.
 * @param expectedGroupCount Number of groups expected to submit.
 * @param sealedGroupCount Number of groups that submitted before reveal.
 * @param missingGroupIds Groups that did not submit before the gate closed.
 * @param forcedReveal True if the reveal happened due to timeout or explicit force.
 * @param gateOpenedAt Epoch ms when the gate opened.
 * @param sealedAt Epoch ms when the gate closed and scores were revealed.
 * @param sealLatencyMs Duration between open and seal in milliseconds.
 */
@Serializable
data class ScoreSealAudit(
    val roundIndex: Int,
    val expectedGroupCount: Int,
    val sealedGroupCount: Int,
    val missingGroupIds: List<String>,
    val forcedReveal: Boolean,
    val gateOpenedAt: Long,
    val sealedAt: Long,
    val sealLatencyMs: Long
)

/**
 * Raised when a blind round closes without every expected group submission.
 *
 * @param roundIndex Incomplete round.
 * @param missingGroupIds Expected groups that supplied no sealed result.
 * @param forcedReveal Whether timeout/force caused the attempted close.
 */
class IncompleteBlindSubmissionException(
    val roundIndex: Int,
    val missingGroupIds: List<String>,
    val forcedReveal: Boolean
) : IllegalStateException(
    "Blind round=$roundIndex is incomplete; missing submissions=${missingGroupIds.sorted()} " +
        "forced=$forcedReveal"
)
