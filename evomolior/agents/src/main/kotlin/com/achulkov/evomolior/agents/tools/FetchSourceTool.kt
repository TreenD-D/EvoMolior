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
import kotlinx.coroutines.CancellationException
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
        Redirects are followed only when each destination passes the same policy (at most 5 hops);
        a blocked redirect reports its destination so it can be approved and fetched explicitly.
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
        val initialDecision = authorize(url, resolvedRole, allowedHosts, purpose)
        if (initialDecision !is SourceAccessDecision.Allowed) {
            return "BLOCKED: ${initialDecision.host ?: url}. ${initialDecision.reason}"
        }

        // Each redirect hop is authorized, audited, and intercepted exactly like a fresh call
        // before any request reaches it, so an unreviewed destination never receives traffic.
        var decision: SourceAccessDecision.Allowed = initialDecision
        val redirectChain = mutableListOf<String>()
        while (true) {
            val fetched = try {
                fetcher.fetch(decision.url)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                return "FETCH_FAILED: ${decision.url}. ${error.message ?: error::class.simpleName.orEmpty()}"
            }
            if (fetched.statusCode !in 300..399) {
                return renderFetched(decision, fetched, redirectChain)
            }
            val destination = fetched.redirectLocation
                ?: return "FETCH_FAILED: ${decision.url}. HTTP ${fetched.statusCode} redirect without a Location header"
            if (destination == decision.url || destination in redirectChain) {
                return "FETCH_FAILED: ${decision.url}. Redirect loop detected at $destination"
            }
            if (redirectChain.size >= MAX_REDIRECT_HOPS) {
                return "FETCH_FAILED: ${decision.url}. More than $MAX_REDIRECT_HOPS redirects; " +
                    "fetch the final destination explicitly: $destination"
            }
            redirectChain += decision.url
            val hopDecision = authorize(
                destination,
                resolvedRole,
                allowedHosts,
                "Redirect from ${decision.url}: $purpose"
            )
            if (hopDecision !is SourceAccessDecision.Allowed) {
                return "REDIRECT_BLOCKED: ${decision.url} redirects to $destination. ${hopDecision.reason} " +
                    "If that destination is relevant, call request_source_access(url, purpose) for it " +
                    "and then fetch_source it directly."
            }
            decision = hopDecision
        }
    }

    /**
     * Applies run source policy plus role egress policy to one destination, recording the
     * decision and constitutional interception before any request may be sent to it.
     */
    private suspend fun authorize(
        url: String,
        resolvedRole: Role,
        allowedHosts: Set<String>,
        purpose: String
    ): SourceAccessDecision {
        val decision = when (val proxyDecision = allowlistProxy.evaluate(url, resolvedRole, allowedHosts)) {
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
        if (decision is SourceAccessDecision.Allowed) {
            actionGate.intercept(
                AgentAction(
                    type = ActionEventType.WEB_REQUEST,
                    agentId = agentId,
                    detail = decision.url,
                    allowed = true
                )
            )
        }
        return decision
    }

    private suspend fun renderFetched(
        decision: SourceAccessDecision.Allowed,
        fetched: FetchedSource,
        redirectChain: List<String>
    ): String {
        if (fetched.statusCode !in 200..299) {
            return "FETCH_FAILED: ${decision.url}. HTTP ${fetched.statusCode}"
        }
        val sanitised = contentSanitiser.sanitise(fetched.body, sourceUrl = decision.url)
        persistFetchedSource(decision, fetched, sanitised.content, redirectChain)
        return buildString {
            appendLine("SOURCE_FETCHED")
            appendLine("URL: ${decision.url}")
            if (redirectChain.isNotEmpty()) {
                appendLine("Redirected from: ${redirectChain.joinToString(" -> ")}")
            }
            appendLine("Host: ${decision.host}")
            appendLine("Status: ${fetched.statusCode}")
            appendLine("Content-Type: ${fetched.contentType ?: "unknown"}")
            appendLine("Sanitised: injectionDetected=${sanitised.injectionDetected}, truncated=${sanitised.truncated || fetched.bodyTruncated}, originalLength=${sanitised.originalLength}")
            appendLine()
            appendLine(sanitised.content)
        }
    }

    private suspend fun persistFetchedSource(
        decision: SourceAccessDecision.Allowed,
        fetched: FetchedSource,
        sanitisedContent: String,
        redirectChain: List<String>
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
                    if (redirectChain.isNotEmpty()) {
                        appendLine("Redirected from: ${redirectChain.joinToString(" -> ")}")
                    }
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

/** Maximum redirect hops [FetchSourceTool] follows, each after a fresh policy decision. */
internal const val MAX_REDIRECT_HOPS = 5

/** Maximum response bytes retained from one fetch; the sanitiser truncates further for context. */
internal const val MAX_SOURCE_BODY_BYTES = 4 * 1024 * 1024

/**
 * Fetches a URL without following redirects and returns the raw response.
 *
 * Implementations must report a 3xx response with its absolute [FetchedSource.redirectLocation]
 * instead of following it, so the caller can authorize the destination first.
 */
fun interface SourceFetcher {
    suspend fun fetch(url: String): FetchedSource
}

/**
 * Raw HTTP source response.
 *
 * @param statusCode HTTP status code.
 * @param contentType Response content type, if supplied.
 * @param body Decoded response body, bounded by [MAX_SOURCE_BODY_BYTES].
 * @param redirectLocation Absolute redirect destination for a 3xx response, if supplied.
 * @param bodyTruncated True when the body exceeded [MAX_SOURCE_BODY_BYTES].
 */
data class FetchedSource(
    val statusCode: Int,
    val contentType: String?,
    val body: String,
    val redirectLocation: String? = null,
    val bodyTruncated: Boolean = false
)

internal class JavaNetSourceFetcher : SourceFetcher {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        // Redirects are never followed implicitly: the tool authorizes each destination first.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override suspend fun fetch(url: String): FetchedSource = withContext(Dispatchers.IO) {
        val requestUri = URI.create(url)
        val request = HttpRequest.newBuilder(requestUri)
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "EvoMoliorSourceFetcher/1.0")
            .header("Accept", "text/html,text/plain,application/json;q=0.8,*/*;q=0.5")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val contentType = response.headers().firstValue("content-type").orElse(null)
        if (response.statusCode() in 300..399) {
            response.body().close()
            val location = response.headers().firstValue("location").orElse(null)
                ?.let { raw -> runCatching { requestUri.resolve(raw.trim()).toString() }.getOrNull() }
            return@withContext FetchedSource(
                statusCode = response.statusCode(),
                contentType = contentType,
                body = "",
                redirectLocation = location
            )
        }
        val bytes = response.body().use { it.readNBytes(MAX_SOURCE_BODY_BYTES + 1) }
        val truncated = bytes.size > MAX_SOURCE_BODY_BYTES
        val charset = contentType
            ?.let { Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.let { runCatching { java.nio.charset.Charset.forName(it.trim('"')) }.getOrNull() }
            ?: Charsets.UTF_8
        FetchedSource(
            statusCode = response.statusCode(),
            contentType = contentType,
            body = String(bytes, 0, minOf(bytes.size, MAX_SOURCE_BODY_BYTES), charset),
            bodyTruncated = truncated
        )
    }
}
