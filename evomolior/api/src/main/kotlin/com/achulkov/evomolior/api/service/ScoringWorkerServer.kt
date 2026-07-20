package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.ScoringWorkerHealth
import com.achulkov.evomolior.api.model.ScoringWorkerRequest
import com.achulkov.evomolior.api.model.ScoringWorkerResponse
import com.achulkov.evomolior.competition.grader.MetaJudge
import com.achulkov.evomolior.competition.grader.ExactGraderBundleResolver
import com.achulkov.evomolior.competition.scoring.BlindSubmissionGate
import com.achulkov.evomolior.competition.scoring.IncompleteBlindSubmissionException
import com.achulkov.evomolior.competition.scoring.IncompleteScoreEvidenceException
import com.achulkov.evomolior.competition.scoring.ScoringEngine
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

internal const val SCORING_WORKER_TOKEN_HEADER = "X-EvoMolior-Scoring-Token"

/**
 * Loopback-only scoring authority. No route exposes gate state, submissions, or partial scores.
 *
 * @param port Loopback port, normally zero so the child binds an available port atomically.
 * @param token Per-run bearer token.
 * @param groupIds Complete competing group set.
 * @param readyFile Bootstrap file where the bound ephemeral port is published.
 */
internal class ScoringWorkerServer(
    private val port: Int,
    private val token: String,
    private val groupIds: List<String>,
    private val readyFile: Path? = null
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val shutdown = CountDownLatch(1)

    fun run() {
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            install(ContentNegotiation) { json(json) }
            routing {
                get("/health") {
                    if (!call.authorized()) return@get
                    call.respond(ScoringWorkerHealth(ready = true, pid = ProcessHandle.current().pid()))
                }
                post("/score") {
                    if (!call.authorized()) return@post
                    try {
                        val request = call.receive<ScoringWorkerRequest>()
                        call.respond(score(request))
                    } catch (
                        error: IncompleteScoreEvidenceException
                    ) {
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            "Scoring evidence rejected: ${error.message.orEmpty()}".take(2_000)
                        )
                    } catch (
                        error: IncompleteBlindSubmissionException
                    ) {
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            "Blind submission rejected: ${error.message.orEmpty()}".take(2_000)
                        )
                    } catch (error: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            error.message.orEmpty().take(2_000)
                        )
                    } catch (error: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            "Scoring worker internal failure " +
                                "${error::class.simpleName}: ${error.message.orEmpty()}".take(2_000)
                        )
                    }
                }
                post("/shutdown") {
                    if (!call.authorized()) return@post
                    call.respond(HttpStatusCode.OK)
                    shutdown.countDown()
                }
            }
        }.start(wait = false)
        readyFile?.let { path ->
            val resolvedPort = runBlocking { server.engine.resolvedConnectors().single().port }
            Files.writeString(path, resolvedPort.toString())
        }
        shutdown.await()
        server.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
    }

    private suspend fun score(request: ScoringWorkerRequest): ScoringWorkerResponse {
        require(request.submissions.map { it.groupId }.toSet() == groupIds.toSet()) {
            "Scoring request must contain exactly one submission for every configured group"
        }
        require(request.submissions.all { it.roundIndex == request.roundIndex }) {
            "All submissions must target round ${request.roundIndex}"
        }
        require(request.gradingSheets.isNotEmpty()) { "At least one sealed grading sheet is required" }
        require(request.gradingSheets.all { it.roundIndex == request.roundIndex && it.isSealed }) {
            "All grading sheets must be sealed and target round ${request.roundIndex}"
        }
        require(
            request.expectedGraderGroupIds.isEmpty() ==
                request.exactGraderBundles.isEmpty()
        ) {
            "Exact grader quorum and bundles must be supplied together"
        }
        val exactGraderVerdict = request.exactGraderBundles
            .takeIf { it.isNotEmpty() }
            ?.let { bundles ->
                ExactGraderBundleResolver().resolve(
                    runId = bundles.first().runId,
                    round = request.roundIndex,
                    expectedGraderGroupIds = request.expectedGraderGroupIds,
                    expectedTargetGroupIds =
                        request.submissions.mapTo(linkedSetOf()) {
                            com.achulkov.evomolior.core.atlas.AtlasGroupId(it.groupId)
                        },
                    bundles = bundles
                )
            }

        val gate = BlindSubmissionGate(groupIds)
        val engine = ScoringEngine(MetaJudge(minGraderGroups = 1), gate)
        request.gradingSheets.forEach(engine::registerGradingSheet)

        gate.openGate(request.roundIndex)
        request.submissions.forEach { gate.submitGroupResults(it.groupId, it) }
        val sealedReveal = gate.closeAndRevealDetailed()
        val outcome = engine.processRevealedScoresDetailed(sealedReveal.revealedScores)
            ?: error("Isolated scoring authority could not produce a MetaJudge result")
        return ScoringWorkerResponse(
            result = outcome.result,
            failureTelemetry = outcome.failureTelemetry,
            deliverableNodeUpdates = outcome.deliverableNodeUpdates,
            sealAudit = checkNotNull(sealedReveal.sealAudit) {
                "Blind scoring audit was not materialised"
            },
            exactGraderVerdict = exactGraderVerdict
        )
    }

    private suspend fun io.ktor.server.application.ApplicationCall.authorized(): Boolean {
        if (request.headers[SCORING_WORKER_TOKEN_HEADER] == token) return true
        respond(HttpStatusCode.Unauthorized)
        return false
    }
}

/**
 * Dedicated child-JVM entry point for the isolated scoring authority.
 *
 * @param args Port (`0` for an atomic ephemeral bind), ready-file path, followed by group IDs.
 */
fun main(args: Array<String>) {
    require(args.size >= 3) {
        "Usage: ScoringWorkerServerKt <port> <readyFile> <groupId>..."
    }
    ScoringWorkerServer(
        port = args[0].toInt(),
        token = requireNotNull(System.getenv("EVOMOLIOR_SCORING_WORKER_TOKEN")) {
            "EVOMOLIOR_SCORING_WORKER_TOKEN is required"
        },
        groupIds = args.drop(2),
        readyFile = Path.of(args[1])
    ).run()
}
