package com.achulkov.evomolior

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.achulkov.evomolior.agents.factory.CouncilRunner
import com.achulkov.evomolior.agents.factory.GraderAgentContext
import com.achulkov.evomolior.agents.factory.EvoMoliorAgentFactory
import com.achulkov.evomolior.agents.factory.requireProductiveAgentOutcome
import com.achulkov.evomolior.agents.runner.DeathNoteProtocol
import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.grader.GraderRunner
import com.achulkov.evomolior.api.service.EvoMoliorRunService
import com.achulkov.evomolior.api.service.RunArchiveService
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.orchestration.council.GenesisCouncil
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.common.BuildConfig
import com.achulkov.evomolior.evomolior.EvoMoliorApp
import com.achulkov.evomolior.evomolior.debug.DebugLogBridge
import com.achulkov.evomolior.evomolior.viewmodel.EvoMoliorViewModel
import com.achulkov.evomolior.root.AppInitializer
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File
import java.time.Instant
import java.net.URLDecoder

private const val OPENROUTER_PROVIDER_502_NEEDLE = "Provider returned error (code: 502)"
private const val OPENROUTER_PROVIDER_PARSE_NEEDLE = "JsonLiteral is not a JsonObject"
private const val OPENROUTER_PROVIDER_TOOL_PARSE_NEEDLE = "toolCallPartsOrNull"

/**
 * Desktop application entry point.
 *
 * Wires the real [EvoMoliorRunService] in-process for the JVM desktop target,
 * connecting all engine subsystems (substrate, orchestration, competition, sandbox).
 *
 * ### Execution mode
 * All agents run **in-process** via [EvoMoliorAgentFactory] — no Shuru VMs are required.
 * Shuru is checked at startup for informational purposes only; the run continues regardless.
 */
fun main() {
    AppInitializer.initialize {}

    // ── Debug log bridge (Napier → Compose UI) ───────────────────────────────
    // Install as a second Napier antilog so the UI's debug panel can capture logs.
    // Always-on: bridge.enabled defaults to true so all logs are captured from startup.
    Napier.base(DebugLogBridge.antilog)
    DebugLogBridge.enabled = true // ensure always-on even if default flipped

    // ── EvoMolior engine wiring ──────────────────────────────────────────────
    // Data root: ~/.evomolior (created on first run)
    val dataRootDir = File(System.getProperty("user.home"), ".evomolior").also { it.mkdirs() }
    Napier.i("EvoMolior: data root = ${dataRootDir.absolutePath}")

    val substrateFactory = SubstrateDatabaseFactory(dataRootDir)
    val shuruVmManager = ShuruVmManager()

    val engineService = EvoMoliorRunService(
        lifecycleManagerProvider = { runId, groupId, openRouterApiKey, agentJarPath, groupProfile ->
            Napier.i("EvoMolior: creating LifecycleManager for runId=$runId groupId=$groupId profile=${groupProfile.profileType} (in-process mode)")

            // Each group gets its own database handle and workspace directory.
            val groupDb = substrateFactory.get(runId, groupId)
            val budgetConfig = BudgetConfig(groupId = groupId, profile = groupProfile)
            val workspaceDir = File(dataRootDir, "workspace/$runId/$groupId").also { it.mkdirs() }

            // EvoMoliorAgentFactory creates Koog AIAgent instances wired to OpenRouter.
            val agentFactory = EvoMoliorAgentFactory(
                openRouterApiKey = openRouterApiKey,
                db = groupDb,
                toolSubSandbox = ToolSubSandbox(workspaceDir),
                budgetConfig = budgetConfig
            )

            LifecycleManager(
                runId = runId,
                groupId = groupId,
                shuruVmManager = shuruVmManager,
                substrateDatabaseFactory = substrateFactory,
                agentJarPath = agentJarPath,
                openRouterApiKey = openRouterApiKey,
                groupProfile = groupProfile,
                // In-process runner: create agent via factory and invoke it as a coroutine.
                // This bypasses Shuru VM spawning entirely.
                // The second parameter (onBudgetLedger) lets LifecycleManager register the
                // live BudgetLedger for real-time budget polling and UI updates.
                localAgentRunner = { bootContext, onBudgetLedger ->
                    val agentId = bootContext.agentId
                    val role = bootContext.role
                    val model = bootContext.modelAssignment
                    Napier.i("EvoMolior [$groupId]: launching agent $agentId (${role.name}) model=$model allocatedBudget=${bootContext.allocatedBudget}")

                    // Early guard: blank API key means the OpenRouter executor will throw at
                    // construction time (createOpenRouterPromptExecutor("") is evaluated inside
                    // AIAgent.builder().promptExecutor(...)), causing a cryptic 1ms failure.
                    // Throw immediately with a clear message so the CONSTRUCT FAILED log
                    // identifies the root cause without deciphering a Koog internal exception.
                    if (openRouterApiKey.isBlank()) {
                        throw IllegalArgumentException(
                            "OpenRouter API key is blank — enter a valid API key in Run Setup before starting a run."
                        )
                    }

                    // Build a DeathNoteProtocol for this in-process agent so that
                    // WriteDeathNoteTool can signal a successful write and cancel
                    // any emergency fallback paths.
                    val deathNoteProtocol = DeathNoteProtocol(bootContext, groupDb.stagingDao())

                    val startMs = System.currentTimeMillis()
                    // ── Phase 1: Agent construction ────────────────────────────────────
                    // Separate try/catch so construction failures ("FAILED after 1ms") are
                    // clearly labelled as [CONSTRUCT] rather than confused with LLM/tool errors.
                    val agentContext = try {
                        agentFactory.create(bootContext, deathNoteProtocol = deathNoteProtocol)
                    } catch (e: Exception) {
                        val durationMs = System.currentTimeMillis() - startMs
                        Napier.e(
                            "EvoMolior [$groupId]: agent $agentId (${role.name}) CONSTRUCT FAILED " +
                            "after ${durationMs}ms [${e::class.simpleName}]: ${e.message}",
                            e
                        )
                        agentFactory.writeEmergencyDeathNote(
                            bootContext = bootContext,
                            reason = "ConstructFailed:${e.javaClass.simpleName}",
                            errorMessage = e.message ?: "unknown error"
                        )
                        throw e  // propagate to LifecycleManager for handleAgentExit
                    }

                    // Register the live BudgetLedger so LifecycleManager can poll it
                    onBudgetLedger?.invoke(agentContext.budgetLedger)
                    Napier.i("EvoMolior [$groupId]: agent $agentId BudgetLedger registered (initial=${agentContext.budgetLedger.initialBudget})")

                    // The agent's task prompt is derived from its role prompt or stage disclosure.
                    val taskPrompt = buildString {
                        if (bootContext.stageDisclosure.isNotBlank()) {
                            appendLine("=== STAGE DISCLOSURE ===")
                            appendLine(bootContext.stageDisclosure)
                            appendLine()
                        }
                        bootContext.wipTaskClaim?.let { claim ->
                            appendLine("=== YOUR ASSIGNED TASK ===")
                            appendLine(claim.description)
                            appendLine()
                        }
                        append(bootContext.rolePrompt.ifBlank {
                            "You are an EvoMolior ${role.name} agent. Fulfill your role objectives for this round."
                        })
                    }
                    Napier.d("EvoMolior [$groupId]: agent $agentId task prompt (${taskPrompt.length} chars): ${taskPrompt.take(200)}…")

                    // ── Phase 2: Agent execution ───────────────────────────────────────
                    var finalResult: String? = null
                    try {
                        finalResult = runWithOpenRouterProviderRetryLocal("AgentRunner[$agentId]") {
                            agentContext.agent.run(taskPrompt)
                        }
                        requireProductiveAgentOutcome(
                            "AgentRunner[$agentId]",
                            finalResult!!,
                            agentContext.budgetLedger,
                            textOnlyCompatibilityMode = agentFactory.usesTextOnlyCompatibilityMode()
                        )
                        val qwenTextOnlySubmissionWritten = agentFactory.writeQwenTextOnlySubmission(
                            bootContext = bootContext,
                            finalResult = finalResult
                        )
                        val actionCount = agentContext.budgetLedger.snapshot().actionLog.size
                        if (actionCount == 0) {
                            val textOnlyCompatibilityCompletion = agentFactory.usesTextOnlyCompatibilityMode() &&
                                qwenTextOnlySubmissionWritten
                            agentFactory.writeEmergencyDeathNote(
                                bootContext = bootContext,
                                reason = if (textOnlyCompatibilityCompletion) {
                                    "TextOnlyCompatibilityCompletion"
                                } else {
                                    "WarningOnlyNoProgressCompletion"
                                },
                                errorMessage = if (textOnlyCompatibilityCompletion) {
                                    "text-only backend completion was preserved as a staged compatibility artifact"
                                } else {
                                    "agent completed without tool/write actions"
                                },
                                finalResult = finalResult,
                                noteKind = if (textOnlyCompatibilityCompletion) "text-only" else "warning-only"
                            )
                            deathNoteProtocol.markDeathNoteWritten()
                        }
                        val durationMs = System.currentTimeMillis() - startMs
                        val ledgerSnapshot = agentContext.budgetLedger.snapshot()
                        val spentBudget = String.format(java.util.Locale.ROOT, "%.2f", ledgerSnapshot.initialBudget - ledgerSnapshot.currentBalance)
                        val remainingBudget = String.format(java.util.Locale.ROOT, "%.2f", ledgerSnapshot.currentBalance)
                        Napier.i(
                            "EvoMolior [$groupId]: agent $agentId completed in ${durationMs}ms — " +
                            "budget: spent=$spentBudget remaining=$remainingBudget " +
                            "actions=${ledgerSnapshot.actionLog.size} " +
                            "result=${finalResult!!.take(100)}"
                        )
                    } catch (e: com.achulkov.evomolior.core.contract.BlockingAgentAbortException) {
                        val durationMs = System.currentTimeMillis() - startMs
                        Napier.e(
                            "EvoMolior [$groupId]: agent $agentId (${role.name}) RUN FAILED " +
                            "after ${durationMs}ms [${e::class.simpleName}]: ${e.message}",
                            e
                        )
                        if (!deathNoteProtocol.isWritten) {
                            agentFactory.writeEmergencyDeathNote(
                                bootContext = bootContext,
                                reason = e.javaClass.simpleName,
                                errorMessage = e.message ?: "unknown error",
                                finalResult = finalResult
                            )
                        }
                        throw e
                    } catch (e: Exception) {
                        val durationMs = System.currentTimeMillis() - startMs
                        Napier.e(
                            "EvoMolior [$groupId]: agent $agentId (${role.name}) RUN FAILED " +
                            "after ${durationMs}ms [${e::class.simpleName}]: ${e.message}",
                            e
                        )
                        // Best-effort: write an emergency death note so something always lands in the substrate.
                        // This preserves partial learning even when the agent crashes or hits the iteration limit.
                        // Only write if the agent didn't already write its own death note.
                        if (!deathNoteProtocol.isWritten) {
                            agentFactory.writeEmergencyDeathNote(
                                bootContext = bootContext,
                                reason = e.javaClass.simpleName,
                                errorMessage = e.message ?: "unknown error",
                                finalResult = finalResult
                            )
                        }
                        throw e
                    }
                }
            )
        },
        groupCoordinatorProvider = { groupIds, dimensions ->
            Napier.i("EvoMolior: creating GroupCoordinator for groups=$groupIds")
            GroupCoordinator(groupIds = groupIds, dimensions = dimensions)
        },
        graderGroupManagerProvider = { dimensions ->
            Napier.i("EvoMolior: creating GraderGroupManager with ${dimensions.size} dimensions")
            GraderGroupManager(dimensions = dimensions)
        },
        // Legacy graderRunner disabled — new two-phase architecture used instead.
        graderRunner = null,
        graderCriteriaRunner = { runId, openRouterApiKey, graderGroupIds, roundIndex, runObjective, scoringDimensions, onProgress ->
            // Phase A: criteria development (runs DURING round in parallel with main agents).
            // Grader agents develop the scoring rubric without accessing any competing group.
            if (openRouterApiKey.isBlank()) {
                Napier.w("GraderRunner: API key blank — skipping criteria phase run=$runId round=$roundIndex")
                return@EvoMoliorRunService
            }
            val criteriaRunner = GraderRunner(
                openRouterApiKey = openRouterApiKey,
                substrateFactory = substrateFactory,
                localAgentRunner = ::runGraderCriteriaAgent
            )
            criteriaRunner.runCriteriaPhase(
                runId = runId,
                graderGroupIds = graderGroupIds,
                roundIndex = roundIndex,
                runObjective = runObjective,
                scoringDimensions = scoringDimensions,
                onProgress = onProgress
            )
        },
        graderJudgingRunner = { runId, openRouterApiKey, graderGroupIds, competingGroupIds, roundIndex, runObjective, scoringDimensions, graderGroupManager, nodeAssignments, onNodeGradeBundles, onProgress ->
            // Phase B: RoundJudge — reads criteria from grader staging DB + scores all competing groups.
            if (openRouterApiKey.isBlank()) {
                Napier.w("GraderRunner: API key blank — skipping judging phase run=$runId round=$roundIndex")
                return@EvoMoliorRunService emptyMap()
            }
            val judgingRunner = GraderRunner(
                openRouterApiKey = openRouterApiKey,
                substrateFactory = substrateFactory,
                localAgentRunner = ::runGraderJudgeAgent
            )
            judgingRunner.runJudgingPhase(
                runId = runId,
                graderGroupIds = graderGroupIds,
                competingGroupIds = competingGroupIds,
                roundIndex = roundIndex,
                runObjective = runObjective,
                scoringDimensions = scoringDimensions,
                graderGroupManager = graderGroupManager,
                nodeGradingAssignments = nodeAssignments,
                onNodeGradeBundles = onNodeGradeBundles,
                onProgress = onProgress
            )
        },
        metaPlannerRunner = MetaPlannerRunner(),
        substrateFactory = substrateFactory,
        shuruVmManager = shuruVmManager,
        archiveService = RunArchiveService(substrateFactory),
        genesisCouncilRunner = {
                runId,
                objective,
                openRouterApiKey,
                executionEnvelope,
                inputArtifacts,
                onProgress,
                onInvocationStarted ->
            onInvocationStarted()
            // Wire Genesis Council to the real CouncilRunner backed by OpenRouter.
            // The API key flows from RunConfig → EvoMoliorRunService → this lambda.
            Napier.i("GenesisCouncil: starting council for run=$runId objective='${objective.take(80)}'")
            onProgress?.invoke("Genesis Council initialising — assembling council agents…")

            val council = GenesisCouncil(
                objective = objective,
                executionEnvelope = executionEnvelope
            )
            val runner = CouncilRunner(
                openRouterApiKey = openRouterApiKey,
                executionEnvelope = executionEnvelope
            )

            Napier.i("GenesisCouncil: running council agents (this may take 1–3 minutes)")
            onProgress?.invoke("Genesis Council running 8 specialist agents in sequence…")

            council.runCompiled(
                runId = runId,
                agentRunner = runner.agentRunner(),
                inputArtifacts = inputArtifacts,
                onProgress = onProgress
            )
        }
    )

    val autoReplayConfig = buildAutoReplayConfig()
    val autoReplayLogPath = buildAutoReplayLogPath(dataRootDir, autoReplayConfig)
    val viewModel = EvoMoliorViewModel(
        engineService = engineService,
        substrateFactory = substrateFactory,
        dataRootDir = dataRootDir
    )
    if (autoReplayConfig != null) {
        runHeadlessAutoReplay(engineService, autoReplayConfig, autoReplayLogPath)
        return
    }

    application {
        val windowState = rememberWindowState(size = DpSize(1400.dp, 900.dp))
        Window(
            onCloseRequest = ::exitApplication,
            title = "EvoMolior — Civilisational AI Control Panel",
            state = windowState
        ) {
            EvoMoliorApp(viewModel = viewModel)
        }
    }
}

private fun runHeadlessAutoReplay(
    engineService: EvoMoliorRunService,
    autoReplayConfig: RunConfig,
    logPath: File?
) {
    runBlocking {
        println("AUTO_REPLAY_START runId=${autoReplayConfig.runId}")
        Napier.i("EvoMolior: headless auto replay enabled runId=${autoReplayConfig.runId}")
        if (logPath != null) {
            Napier.i("EvoMolior: headless auto replay log path=${logPath.absolutePath}")
        }
        val completionSignal = CompletableDeferred<Unit>()

        val eventLogger = launch(Dispatchers.Default) {
            engineService.events.collect { event ->
                logHeadlessEvent(event)
                if (event is EvoMoliorEvent.RunCompleted && !completionSignal.isCompleted) {
                    completionSignal.complete(Unit)
                }
            }
        }

        try {
            yield()
            engineService.startRun(autoReplayConfig)
            withTimeout(30 * 60_000L) {
                completionSignal.await()
            }
            delay(2_000)
            println("AUTO_REPLAY_COMPLETE runId=${autoReplayConfig.runId}")
            Napier.i("EvoMolior: headless auto replay completed runId=${autoReplayConfig.runId}")
        } finally {
            eventLogger.cancelAndJoin()
            dumpAutoReplayLog(logPath, autoReplayConfig.runId)
        }
    }
}

private fun logHeadlessEvent(event: EvoMoliorEvent) {
    when (event) {
        is EvoMoliorEvent.RunStarted -> {
            Napier.i("EvoMoliorViewModel: run started runId=${event.runId}")
            Napier.d("EvoMoliorViewModel: received event RunStarted")
        }
        is EvoMoliorEvent.RoundStarted -> {
            Napier.i("EvoMoliorViewModel: round started round=${event.roundIndex} runId=${event.runId}")
            Napier.d("EvoMoliorViewModel: received event RoundStarted")
        }
        is EvoMoliorEvent.RoundCompleted -> {
            Napier.i("EvoMoliorViewModel: round completed round=${event.result.roundIndex}")
            Napier.d("EvoMoliorViewModel: received event RoundCompleted")
        }
        is EvoMoliorEvent.RunCompleted -> {
            Napier.i("EvoMoliorViewModel: run completed runId=${event.runId} winner=${event.winnerId}")
            Napier.d("EvoMoliorViewModel: received event RunCompleted")
        }
        is EvoMoliorEvent.GraderRoundStarted -> {
            Napier.i("EvoMoliorViewModel: grader round started graderGroupId=${event.graderGroupId} round=${event.roundIndex}")
            Napier.d("EvoMoliorViewModel: received event GraderRoundStarted")
        }
        is EvoMoliorEvent.GraderRoundCompleted -> {
            Napier.d("EvoMoliorViewModel: unhandled event type GraderRoundCompleted")
            Napier.d("EvoMoliorViewModel: received event GraderRoundCompleted")
        }
        is EvoMoliorEvent.EngineProgress -> {
            Napier.d("EvoMoliorViewModel: engine progress phase=${event.phase} msg=${event.message}")
            Napier.d("EvoMoliorViewModel: received event EngineProgress")
        }
        else -> {
            Napier.d("EvoMoliorViewModel: unhandled event type ${event::class.simpleName}")
            Napier.d("EvoMoliorViewModel: received event ${event::class.simpleName}")
        }
    }
}

private fun buildAutoReplayLogPath(dataRootDir: File, autoReplayConfig: RunConfig?): File? {
    if (autoReplayConfig == null) return null
    val overridePath = readReplayStringProperty("evomolior.replay.logPath")
    return overridePath?.let { File(it) }
        ?: File(dataRootDir, "replay-logs/${autoReplayConfig.runId}.log")
}

private fun buildAutoReplayConfig(): RunConfig? {
    val enabled = System.getProperty("evomolior.replay.enabled")
        ?.equals("true", ignoreCase = true)
        ?: false
    if (!enabled) return null

    val runId = readReplayStringProperty("evomolior.replay.runId")
        ?: "replay-${System.currentTimeMillis()}"
    val objective = readReplayStringProperty("evomolior.replay.objective")
        ?: "Replay the full EvoMolior run lifecycle for log verification."
    val apiKey = BuildConfig.OPENROUTER_API_KEY.takeIf { it.isNotBlank() }
        ?: error("EvoMolior auto replay requires BuildConfig.OPENROUTER_API_KEY")
    val rounds = System.getProperty("evomolior.replay.rounds")
        ?.toIntOrNull()
        ?: 1
    val groupCount = System.getProperty("evomolior.replay.groupCount")
        ?.toIntOrNull()
        ?: 2
    val roundDurationMinutes = System.getProperty("evomolior.replay.roundDurationMinutes")
        ?.toIntOrNull()
        ?: 0
    val maxAgentsPerGroup = System.getProperty("evomolior.replay.maxAgentsPerGroup")
        ?.toIntOrNull()
        ?: 1

    val groupProfiles = when (groupCount) {
        2 -> listOf(GroupProfileType.Library, GroupProfileType.Factory)
        3 -> listOf(GroupProfileType.Library, GroupProfileType.Factory, GroupProfileType.Commune)
        else -> List(groupCount) { index ->
            when (index % 3) {
                0 -> GroupProfileType.Library
                1 -> GroupProfileType.Factory
                else -> GroupProfileType.Commune
            }
        }
    }

    return RunConfig(
        runId = runId,
        objective = objective,
        groupCount = groupCount,
        groupProfiles = groupProfiles,
        rounds = rounds,
        roundDurationMinutes = roundDurationMinutes,
        maxAgentsPerGroup = maxAgentsPerGroup,
        scoringDimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Reward evidence-based evaluation.",
                weight = 1.0
            )
        ),
        openRouterApiKey = apiKey,
        isTestRun = false
    )
}

private fun readReplayStringProperty(key: String): String? {
    val raw = System.getProperty(key)?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { URLDecoder.decode(raw, Charsets.UTF_8) }
        .getOrDefault(raw)
        .takeIf { it.isNotBlank() }
}

private fun dumpAutoReplayLog(logPath: File?, runId: String) {
    if (logPath == null) return
    logPath.parentFile?.mkdirs()
    val lines = DebugLogBridge.logs.value.joinToString(separator = "\n") { entry ->
            "${Instant.ofEpochMilli(entry.timestamp)} ${entry.level} ${entry.tag.orEmpty()} ${entry.message}"
        }
    logPath.writeText(lines)
    Napier.i("EvoMolior: wrote auto replay log for runId=$runId to ${logPath.absolutePath}")
}

private suspend fun <T> runWithOpenRouterProviderRetryLocal(
    agentLabel: String,
    maxRetries: Int = 2,
    retryDelayMs: Long = 500L,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            if (!isOpenRouterProviderFailureLocal(e)) {
                throw e
            }

            if (attempt >= maxRetries) {
                throw com.achulkov.evomolior.core.contract.BlockingAgentAbortException(
                    message = "$agentLabel aborted after ${attempt + 1} OpenRouter provider failure(s): ${e.message}",
                    cause = e
                )
            }

            attempt++
            Napier.w(e) { "$agentLabel OpenRouter provider failure (attempt $attempt/$maxRetries); retrying" }
            kotlinx.coroutines.delay(retryDelayMs * attempt)
        }
    }
}

private fun isOpenRouterProviderFailureLocal(throwable: Throwable): Boolean {
    return generateSequence(throwable) { it.cause }
        .any { candidate ->
            candidate.message?.contains(OPENROUTER_PROVIDER_502_NEEDLE) == true ||
                candidate.message?.contains("OpenRouter API error: Provider returned error (code: 502)") == true ||
                candidate.message?.contains(OPENROUTER_PROVIDER_PARSE_NEEDLE) == true ||
                candidate.message?.contains(OPENROUTER_PROVIDER_TOOL_PARSE_NEEDLE) == true
        }
}

private suspend fun runGraderCriteriaAgent(graderContext: GraderAgentContext): String {
    val agentId = graderContext.agentId
    val role = graderContext.role
    Napier.i("GraderRunner.criteria: launching $agentId (${role.name})")
    val startMs = System.currentTimeMillis()
    return try {
        val result = runWithOpenRouterProviderRetryLocal("GraderCriteria[$agentId]") {
            graderContext.agent.run(graderContext.taskPrompt)
        }
        requireProductiveAgentOutcome("GraderCriteria[$agentId]", result, graderContext.budgetLedger)
        Napier.i("GraderRunner.criteria: $agentId completed in ${System.currentTimeMillis() - startMs}ms")
        result
    } catch (e: com.achulkov.evomolior.core.contract.BlockingAgentAbortException) {
        Napier.e("GraderRunner.criteria: $agentId aborted due to blocking provider/runtime error", e)
        throw e
    } catch (e: Exception) {
        Napier.w("GraderRunner.criteria: $agentId failed: ${e.message}")
        "[${role.name} criteria failed: ${e.message?.take(200)}]"
    }
}

private suspend fun runGraderJudgeAgent(graderContext: GraderAgentContext): String {
    val agentId = graderContext.agentId
    val role = graderContext.role
    Napier.i("GraderRunner.judge: launching $agentId (${role.name}) targeting ${graderContext.targetGroupId}")
    val startMs = System.currentTimeMillis()
    return try {
        val result = runWithOpenRouterProviderRetryLocal("GraderJudge[$agentId]") {
            graderContext.agent.run(graderContext.taskPrompt)
        }
        requireProductiveAgentOutcome("GraderJudge[$agentId]", result, graderContext.budgetLedger)
        Napier.i("GraderRunner.judge: $agentId completed in ${System.currentTimeMillis() - startMs}ms")
        result
    } catch (e: com.achulkov.evomolior.core.contract.BlockingAgentAbortException) {
        Napier.e("GraderRunner.judge: $agentId aborted due to blocking provider/runtime error", e)
        throw e
    } catch (e: Exception) {
        Napier.w("GraderRunner.judge: $agentId failed: ${e.message}")
        "[${role.name} judge failed: ${e.message?.take(200)}]"
    }
}
