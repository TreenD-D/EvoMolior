package com.achulkov.evomolior.core.contract

/**
 * Signals that an agent must be aborted immediately because it hit a blocking runtime error.
 *
 * This is used for fatal tool-validation / tool-call failures so the lifecycle manager can
 * stop the agent without treating the exit as a normal completion.
 *
 * @param message Human-readable reason for the abort.
 * @param cause Optional underlying failure that triggered the abort.
 */
class BlockingAgentAbortException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
