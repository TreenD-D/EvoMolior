package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.contract.OverseerDeadEnd
import com.achulkov.evokammer.core.contract.OverseerKernelAuditEntry
import com.achulkov.evokammer.core.contract.OverseerDeadEndHistoryEntry
import com.achulkov.evokammer.api.model.OverseerExperimentLaunchRequest
import com.achulkov.evokammer.api.model.OverseerExperimentLaunchResponse
import com.achulkov.evokammer.core.contract.OverseerExperimentPlan
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryEntry
import com.achulkov.evokammer.core.contract.OverseerExperimentResult
import com.achulkov.evokammer.core.contract.OverseerHypothesis
import com.achulkov.evokammer.core.contract.OverseerKernelEligibility
import com.achulkov.evokammer.core.contract.OverseerKernelState
import com.achulkov.evokammer.api.service.MetaArchiveEntryRecord
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Typed client façade for the Overseer HTTP routes.
 *
 * This keeps callers from depending on raw Overseer route strings directly.
 *
 * @param baseUrl Base URL of the IPC server, e.g. `http://localhost:7400`.
 * @param httpClient HTTP client used for requests.
 */
class OverseerKernelClient(
    private val baseUrl: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Fetches the current durable Overseer state.
     */
    suspend fun getState(): OverseerKernelState = getJson("/overseer/state")

    /**
     * Returns the deterministic preflight verdict for a hypothesis.
     */
    suspend fun preflightHypothesis(hypothesis: OverseerHypothesis): OverseerKernelEligibility =
        postJsonResponse("/overseer/hypotheses/preflight", hypothesis)

    /**
     * Returns the deterministic preflight verdict for an experiment plan.
     */
    suspend fun preflightExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerExperimentPlanEligibility =
        postJsonResponse("/overseer/experiment-plans/preflight", experimentPlan)

    /**
     * Registers a hypothesis and returns the updated state snapshot.
     */
    suspend fun formalizeHypothesis(hypothesis: OverseerHypothesis): OverseerKernelState =
        postJsonResponse("/overseer/hypotheses", hypothesis)

    /**
     * Registers an experiment plan and returns the updated state snapshot.
     */
    suspend fun registerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerKernelState =
        postJsonResponse("/overseer/experiment-plans", experimentPlan)

    /**
     * Schedules all eligible experiment plans and returns the updated state snapshot.
     */
    suspend fun scheduleExperimentPlans(): OverseerKernelState =
        postJsonResponse("/overseer/experiment-plans/schedule")

    /**
     * Launches a scheduled Overseer experiment run and returns the launch result.
     */
    suspend fun launchExperimentRun(
        experimentPlanId: String,
        launchRequest: OverseerExperimentLaunchRequest
    ): OverseerExperimentLaunchResponse =
        postJsonResponse("/overseer/experiment-plans/$experimentPlanId/launch", launchRequest)

    /**
     * Records the deterministic outcome of an experiment and returns the updated state snapshot.
     */
    suspend fun recordExperimentResult(experimentResult: OverseerExperimentResult): OverseerKernelState =
        postJsonResponse("/overseer/experiment-results", experimentResult)

    /**
     * Records a dead end and returns the updated state snapshot.
     */
    suspend fun recordDeadEnd(deadEnd: OverseerDeadEnd): OverseerKernelState =
        postJsonResponse("/overseer/dead-ends", deadEnd)

    /**
     * Returns the append-only Overseer audit trail.
     */
    suspend fun getAuditEntries(): List<OverseerKernelAuditEntry> = getListJson("/overseer/audit")

    /**
     * Returns the lifecycle history for an experiment plan.
     */
    suspend fun getExperimentPlanHistory(experimentPlanId: String): List<OverseerExperimentPlanHistoryEntry> =
        getListJson("/overseer/experiment-plans/$experimentPlanId/history")

    /**
     * Returns the dead-end history for a hypothesis.
     */
    suspend fun getDeadEndHistory(hypothesisId: String): List<OverseerDeadEndHistoryEntry> =
        getListJson("/overseer/dead-ends/$hypothesisId/history")

    /**
     * Returns the archived mirror of the Overseer audit trail from the project meta-archive.
     */
    suspend fun getAuditArchiveEntries(): List<MetaArchiveEntryRecord> =
        getListJson("/overseer/audit/archive")

    private inline fun <reified T> getJson(path: String): T {
        val response = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.trimEnd('/') + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        require(response.statusCode() in 200..299) {
            "GET $path failed with HTTP ${response.statusCode()}"
        }
        return json.decodeFromString(response.body())
    }

    private inline fun <reified T : Any, reified R> postJsonResponse(path: String, body: T): R {
        val response = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.trimEnd('/') + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        require(response.statusCode() in 200..299) {
            "POST $path failed with HTTP ${response.statusCode()}"
        }
        return json.decodeFromString(response.body())
    }

    private inline fun <reified R> postJsonResponse(path: String): R {
        val response = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.trimEnd('/') + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        require(response.statusCode() in 200..299) {
            "POST $path failed with HTTP ${response.statusCode()}"
        }
        return json.decodeFromString(response.body())
    }

    private inline fun <reified T> getListJson(path: String): List<T> {
        val response = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.trimEnd('/') + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        require(response.statusCode() in 200..299) {
            "GET $path failed with HTTP ${response.statusCode()}"
        }
        return json.decodeFromString(response.body())
    }
}
