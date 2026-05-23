package com.achulkov.evokammer.core.budget

import com.achulkov.evokammer.core.model.ActionType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Thread-safe budget ledger for a single agent instance.
 *
 * Uses a Mutex to ensure atomic decrements under concurrent coroutine execution.
 * Emits a kill signal (by returning [BudgetDeductResult.FLOOR_REACHED]) when the balance
 * falls to or below the floor threshold.
 *
 * The Death Note reserved slice ensures [ActionType.WRITE_DEATH_NOTE] always executes
 * even when the main budget is exhausted.
 *
 * @param agentId The agent this ledger belongs to.
 * @param initialBudget The starting budget.
 * @param floor The balance threshold at or below which a kill signal is emitted.
 * @param config The [BudgetConfig] used to compute action costs.
 * @param runSecret A per-run secret key for HMAC-SHA256 signing of staging tokens.
 *   Should be generated once per run by `EvoKammerRunService` and threaded through
 *   `LifecycleManager` → `EvoKammerAgentFactory` → `BudgetLedger`. Defaults to a
 *   random UUID if not provided (sufficient for single-process deployments).
 */
class BudgetLedger(
    val agentId: String,
    val initialBudget: Double,
    val floor: Double,
    private val config: BudgetConfig,
    private val runSecret: String = java.util.UUID.randomUUID().toString()
) {
    private val mutex = Mutex()
    private var _balance: Double = initialBudget
    private val _actionLog = mutableListOf<BudgetDeduction>()
    private val retryTracker = RetryTracker(config.retryHashWindowSize)

    /** Current balance (snapshot; may be outdated by time it's read under concurrency). */
    val balance: Double get() = _balance

    /** True if the balance is at or below the kill floor. */
    val isAtFloor: Boolean get() = _balance <= floor

    /** Immutable snapshot of the action log. */
    val actionLog: List<BudgetDeduction> get() = _actionLog.toList()

    /**
     * Deducts the cost for [actionType] from the current balance.
     *
     * Returns:
     * - [BudgetDeductResult.OK] if the deduction succeeded and balance remains above floor
     * - [BudgetDeductResult.FLOOR_REACHED] if balance fell to or below floor after deduction
     * - [BudgetDeductResult.BELOW_FLOOR] if balance was already at or below floor
     *
     * WRITE_DEATH_NOTE and other always-free actions always return OK immediately.
     *
     * @param actionType The action to price.
     * @param tokenCount For LLM_CALL, the number of output tokens.
     * @param toolKey For retry detection: "$toolName:$argsHash". Null for non-tool actions.
     * @return The deduction result.
     */
    suspend fun deduct(
        actionType: ActionType,
        tokenCount: Int = 0,
        toolKey: String? = null
    ): BudgetDeductResult = mutex.withLock {
        // Always-free actions never deduct
        if (actionType.isAlwaysFree) {
            _actionLog.add(BudgetDeduction(actionType, 0.0, _balance))
            return@withLock BudgetDeductResult.OK
        }

        // Check if already at floor
        if (_balance <= floor) {
            return@withLock BudgetDeductResult.BELOW_FLOOR
        }

        // Determine actual action type (retry detection)
        val resolvedActionType = if (toolKey != null && actionType == ActionType.TOOL_EXEC_SUCCESS) {
            if (retryTracker.isRetry(toolKey)) ActionType.TOOL_EXEC_RETRY else actionType
        } else if (toolKey != null && actionType == ActionType.TOOL_EXEC_FAILURE) {
            if (retryTracker.isRetry(toolKey)) ActionType.TOOL_EXEC_RETRY else actionType
        } else {
            actionType
        }

        // Register tool key for future retry detection
        if (toolKey != null) retryTracker.register(toolKey)

        val cost = config.costFor(resolvedActionType, tokenCount)
        val newBalance = _balance - cost
        _balance = newBalance
        _actionLog.add(BudgetDeduction(resolvedActionType, cost, newBalance))

        if (newBalance <= floor) BudgetDeductResult.FLOOR_REACHED else BudgetDeductResult.OK
    }

    /**
     * Produces a signed budget token for the Staging Area's validation.
     * The token encodes (agentId, submissionType, timestamp) and is signed with a shared secret.
     *
     * In the JVM engine, this is verified by the StagingRepository before accepting a submission.
     */
    suspend fun generateStagingToken(submissionType: String): String = mutex.withLock {
        // Compose the message: agentId, submissionType, balance snapshot, and timestamp
        val timestamp = System.currentTimeMillis()
        val message = "$agentId:$submissionType:${_balance}:$timestamp"
        // Sign with HMAC-SHA256 using the per-run secret key
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(runSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val sigHex = signature.joinToString("") { "%02x".format(it) }
        // Return as "message.signature" — verifiable by the StagingRepository using the same secret
        "${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(message.toByteArray())}.$sigHex"
    }

    /**
     * Directly deducts a raw amount from the balance (for sub-agent budget carve-outs).
     *
     * This bypasses ActionType-based cost resolution and is intended only for budget
     * allocations that transfer funds to a child ledger.
     *
     * @param amount The amount to deduct.
     * @param reason Human-readable reason label for the action log.
     * @return [BudgetDeductResult.OK] or [BudgetDeductResult.FLOOR_REACHED].
     */
    suspend fun deductRaw(amount: Double, @Suppress("UNUSED_PARAMETER") reason: String): BudgetDeductResult = mutex.withLock {
        if (_balance <= floor) return@withLock BudgetDeductResult.BELOW_FLOOR
        val newBalance = _balance - amount
        _balance = newBalance
        _actionLog.add(BudgetDeduction(ActionType.WRITE_STAGING, amount, newBalance)) // proxy action type for logging
        if (newBalance <= floor) BudgetDeductResult.FLOOR_REACHED else BudgetDeductResult.OK
    }

    /** Returns a serialisable snapshot of this ledger for persistence. */
    fun snapshot(): BudgetLedgerSnapshot = BudgetLedgerSnapshot(
        agentId = agentId,
        initialBudget = initialBudget,
        currentBalance = _balance,
        floor = floor,
        actionLog = _actionLog.toList()
    )
}

/** Result of a budget deduction attempt. */
enum class BudgetDeductResult {
    /** Deduction succeeded; balance is still above floor. */
    OK,

    /** Deduction succeeded; balance is now at or below floor. Kill signal should be emitted. */
    FLOOR_REACHED,

    /** Balance was already at or below floor before the deduction was attempted. */
    BELOW_FLOOR
}

/** A single budget deduction event. */
@Serializable
data class BudgetDeduction(
    val actionType: ActionType,
    val cost: Double,
    val balanceAfter: Double
)

/** Serialisable snapshot of a BudgetLedger state. */
@Serializable
data class BudgetLedgerSnapshot(
    val agentId: String,
    val initialBudget: Double,
    val currentBalance: Double,
    val floor: Double,
    val actionLog: List<BudgetDeduction>
)

/**
 * Tracks recent (toolName, argsHash) pairs to detect retry calls.
 *
 * A call is a "retry" if the same key has been seen in the current agent lifecycle within
 * the last [windowSize] tool calls.
 */
internal class RetryTracker(private val windowSize: Int) {
    private val recentKeys = ArrayDeque<String>(windowSize)
    private val seenKeys = mutableSetOf<String>()

    fun isRetry(key: String): Boolean = seenKeys.contains(key)

    fun register(key: String) {
        if (recentKeys.size >= windowSize) {
            recentKeys.removeFirst()
            // Rebuild seenKeys when evicting from window
            seenKeys.clear()
            seenKeys.addAll(recentKeys)
        }
        recentKeys.addLast(key)
        seenKeys.add(key)
    }
}
