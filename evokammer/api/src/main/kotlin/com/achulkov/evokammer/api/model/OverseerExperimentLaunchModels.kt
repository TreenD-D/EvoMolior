package com.achulkov.evokammer.api.model

import kotlinx.serialization.Serializable

/**
 * Operator-triggered launch request for a scheduled Overseer experiment run.
 *
 * The caller supplies the concrete [runConfig] to hand to the existing run engine, while
 * [scheduledRunId] binds the launch to a previously scheduled deterministic launch intent.
 *
 * @param scheduledRunId Scheduled run identifier from the experiment schedule.
 * @param runConfig Concrete runtime configuration to launch.
 */
@Serializable
data class OverseerExperimentLaunchRequest(
    val scheduledRunId: String,
    val runConfig: RunConfig
)

/**
 * Response returned after the engine launches a scheduled Overseer experiment run.
 *
 * @param experimentPlanId Experiment plan that owns the schedule.
 * @param scheduledRunId Scheduled launch intent that was consumed.
 * @param runId Run identifier started by the engine.
 */
@Serializable
data class OverseerExperimentLaunchResponse(
    val experimentPlanId: String,
    val scheduledRunId: String,
    val runId: String
)
