package com.achulkov.evokammer.core.model

import kotlinx.serialization.Serializable

/** Current lifecycle status of an agent. */
@Serializable
enum class AgentStatus {
    /** Agent VM is running and the agent is operating normally. */
    Active,

    /** Budget reached the floor; agent received SIGTERM; in grace period for Death Note. */
    DyingGracefully,

    /** Agent wrote Death Note and exited cleanly. */
    Completed,

    /** Agent exceeded grace period; VM was hard-killed. */
    Killed,

    /** Agent was killed due to a constitutional violation. */
    KilledConstitutionalViolation
}

/** Current lifecycle status of a run. */
@Serializable
enum class RunStatus {
    /** Run is configured but not yet started. */
    Pending,

    /** Run is actively executing. */
    Running,

    /** Run is paused (no new agents will be spawned; active agents continue). */
    Paused,

    /** Run completed successfully (all generations exhausted or final check-in closed). */
    Completed,

    /** Run was terminated manually before completion. */
    Terminated,

    /** Run failed due to an unrecoverable error. */
    Failed
}
