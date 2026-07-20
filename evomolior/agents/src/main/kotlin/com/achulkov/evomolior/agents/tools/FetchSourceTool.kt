package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.sandbox.network.ContentSanitiser
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import com.achulkov.evomolior.sandbox.network.AllowlistDecision
import com.achulkov.evomolior.sandbox.network.AllowlistProxy
import com.achulkov.evomolior.sandbox.network.SourceAccessDecision
import com.achulkov.evomolior.sandbox.network.SourceAccessDecisionRecord
import com.achulkov.evomolior.sandbox.network.SourceAccessDecisionStore
import com.achulkov.evomolior.sandbox.network.SourceAccessPolicy
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.db.dao.StagingDao
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Koog tool that fetches a public source URL after applying EvoMolior source-access policy.
 *
 * @param runId Run identifier used in the source-access audit log.
 * @param groupId Group identifier used in the source-access audit log.
 * @param agentId Agent identifier used in the source-access audit log.
 * @param role Agent role used in the source-access audit log.
 * @param generation Current generation used in the source-access audit log.
 * @param runObjective Objective used to infer subject-relevant hosts.
 * @param currentAllowedHosts Hosts approved before this agent spawn.
 * @param auditFilePath Append-only decision log path.
 * @param contentSanitiser Sanitiser applied before page text enters the model context.
 * @param fetcher HTTP fetch abstraction for deterministic tests.
 */
class FetchSourceTool(
    private val runId: String,
    private val groupId: String,
    private val agentId: String,
    private val role: String,
    private val generation: Int,
    private val runObjective: String,
    private val currentAllowedHosts: Set<String>,
    auditFilePath: String?,
    private val stagingDao: StagingDao? = null,
    private val contentSanitiser: ContentSanitiser = ContentSanitiser(),
    private val fetcher: SourceFetcher = JavaNetSourceFetcher(),
    private val allowlistProxy: AllowlistProxy = AllowlistProxy(),
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE
) : SimpleTool<FetchSourceTool.Args>(
    argsType = typeToken<Args>(),
    name = "fetch_source",
    description = """
        Fetch a public http(s) source URL after source-access policy approval.
        The returned content is stripped of HTML/script/style markup, prompt-injection scanned,
        truncated for context safety, and source-access decisions are audited.
        Cost: FREE.
    """.trimIndent()
) {
    private val decisionStore = auditFilePath?.takeIf { it.isNotBlank() }?.let { SourceAccessDecisionStore(File(it)) }

    @Serializable
    data class Args(
        val url: String? = null,
        val purpose: String? = null,
        val value: ArgsValue? = null
    )

    @Serializable
    data class ArgsValue(
        val url: String? = null,
        val purpose: String? = null
    )

    override suspend fun execute(args: Args): String {
        val url = args.url ?: args.value?.url
            ?: return "BLOCKED: missing url. Provide fetch_source(url, purpose)."
        val purpose = (args.purpose ?: args.value?.purpose).orEmpty().trim()
        if (purpose.length < 12) {
            return "BLOCKED: purpose is too short. Explain why this source is needed for the run objective."
        }

        val allowedHosts = currentAllowedHosts + decisionStore.orEmptyApprovedHosts()
        val resolvedRole = runCatching { Role.valueOf(role) }.getOrNull()
        if (resolvedRole == null) {
            return "BLOCKED: unknown agent role '$role'."
        }
        val proxyDecision = allowlistProxy.evaluate(url, resolvedRole, allowedHosts)
        val decision = when (proxyDecision) {
            is AllowlistDecision.Allowed -> SourceAccessDecision.Allowed(
                url = proxyDecision.url,
                host = proxyDecision.host,
                reason = "Approved by the run source policy and role egress policy."
            )
            is AllowlistDecision.Denied -> SourceAccessDecision.Blocked(
                url = url,
                host = SourceAccessPolicy.normalizeHost(url),
                reason = proxyDecision.reason
            )
        }
        audit(decision, purpose)
        actionGate.intercept(
            AgentAction(
                type = ActionEventType.NETWORK_EGRESS,
                agentId = agentId,
                detail = decision.url,
                allowed = decision is SourceAccessDecision.Allowed
            )
        )
        if (decision !is SourceAccessDecision.Allowed) {
            return "BLOCKED: ${decision.host ?: url}. ${decision.reason}"
        }
        actionGate.intercept(
            AgentAction(
                type = ActionEventType.WEB_REQUEST,
                agentId = agentId,
                detail = decision.url,
                allowed = true
            )
        )

        return runCatching {
            fetcher.fetch(decision.url)
        }.fold(
            onSuccess = { fetched ->
                val sanitised = contentSanitiser.sanitise(fetched.body, sourceUrl = decision.url)
                persistFetchedSource(decision, fetched, sanitised.content)
                buildString {
                    appendLine("SOURCE_FETCHED")
                    appendLine("URL: ${decision.url}")
                    appendLine("Host: ${decision.host}")
                    appendLine("Status: ${fetched.statusCode}")
                    appendLine("Content-Type: ${fetched.contentType ?: "unknown"}")
                    appendLine("Sanitised: injectionDetected=${sanitised.injectionDetected}, truncated=${sanitised.truncated}, originalLength=${sanitised.originalLength}")
                    appendLine()
                    appendLine(sanitised.content)
                }
            },
            onFailure = { error ->
                "FETCH_FAILED: ${decision.url}. ${error.message ?: error::class.simpleName.orEmpty()}"
            }
        )
    }

    private suspend fun persistFetchedSource(
        decision: SourceAccessDecision.Allowed,
        fetched: FetchedSource,
        sanitisedContent: String
    ) {
        val dao = stagingDao ?: return
        val nowMs = System.currentTimeMillis()
        dao.insert(
            StagingSubmissionEntity(
                id = "source-fetch-${agentId}-$nowMs",
                agentId = agentId,
                role = role,
                type = "PLAN_UPDATE",
                content = buildString {
                    appendLine("SOURCE FETCH EVIDENCE")
                    appendLine("URL: ${decision.url}")
                    appendLine("Host: ${decision.host}")
                    appendLine("Generation: $generation")
                    appendLine("HTTP status: ${fetched.statusCode}")
                    appendLine("Content-Type: ${fetched.contentType ?: "unknown"}")
                    appendLine()
                    appendLine("Sanitised excerpt:")
                    appendLine(sanitisedContent.take(2_000))
                },
                wipTaskRef = null,
                speculative = false,
                outlier = role.equals("Outlier", ignoreCase = true),
                budgetSignature = "SOURCE_FETCH:$agentId:$nowMs",
                status = "pending",
                initialTrustScore = 0.35,
                generation = generation,
                groupId = groupId,
                runId = runId,
                submittedAtMs = nowMs
            )
        )
    }

    private fun SourceAccessDecisionStore?.orEmptyApprovedHosts(): Set<String> =
        this?.approvedHosts().orEmpty()

    private fun audit(decision: SourceAccessDecision, purpose: String) {
        decisionStore?.append(
            SourceAccessDecisionRecord(
                runId = runId,
                groupId = groupId,
                agentId = agentId,
                role = role,
                generation = generation,
                url = decision.url,
                host = decision.host,
                purpose = purpose,
                allowed = decision is SourceAccessDecision.Allowed,
                reason = decision.reason
            )
        )
    }
}

/** Fetches a URL and returns the raw response body. */
fun interface SourceFetcher {
    suspend fun fetch(url: String): FetchedSource
}

/** Raw HTTP source response. */
data class FetchedSource(
    val statusCode: Int,
    val contentType: String?,
    val body: String
)

internal class JavaNetSourceFetcher : SourceFetcher {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        // Redirects are not followed implicitly: every destination requires a fresh policy check.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override suspend fun fetch(url: String): FetchedSource = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "EvoMoliorSourceFetcher/1.0")
            .header("Accept", "text/html,text/plain,application/json;q=0.8,*/*;q=0.5")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 300..399) {
            error("HTTP redirect blocked; approve and fetch the destination URL explicitly")
        }
        if (response.statusCode() !in 200..299) {
            error("HTTP ${response.statusCode()}")
        }
        FetchedSource(
            statusCode = response.statusCode(),
            contentType = response.headers().firstValue("content-type").orElse(null),
            body = response.body()
        )
    }
}
