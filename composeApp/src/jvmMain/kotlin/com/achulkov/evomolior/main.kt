package com.achulkov.evomolior

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.achulkov.evokammer.agents.factory.CouncilRunner
import com.achulkov.evokammer.agents.factory.EvoKammerAgentFactory
import com.achulkov.evokammer.agents.runner.DeathNoteProtocol
import com.achulkov.evokammer.api.grader.GraderRunner
import com.achulkov.evokammer.api.service.EvoKammerRunService
import com.achulkov.evokammer.orchestration.council.GenesisCouncil
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.evokammer.EvoKammerApp
import com.achulkov.evomolior.evokammer.debug.DebugLogBridge
import com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModel
import com.achulkov.evomolior.root.AppInitializer
import io.github.aakira.napier.Napier
import java.io.File

/**
 * Desktop application entry point.
 *
 * Wires the real [EvoKammerRunService] in-process for the JVM desktop target,
 * connecting all engine subsystems (substrate, orchestration, competition, sandbox).
 *
 * ### Execution mode
 * All agents run **in-process** via [EvoKammerAgentFactory] — no Shuru VMs are required.
 * Shuru is checked at startup for informational purposes only; the run continues regardless.
 */
fun main() = application {
    AppInitializer.initialize {}

    // ── Debug log bridge (Napier → Compose UI) ───────────────────────────────
    // Install as a second Napier antilog so the UI's debug panel can capture logs.
    // Always-on: bridge.enabled defaults to true so all logs are captured from startup.
    Napier.base(DebugLogBridge.antilog)
    DebugLogBridge.enabled = true // ensure always-on even if default flipped

    // ── EvoKammer engine wiring ──────────────────────────────────────────────
    // Data root: ~/.evokammer (created on first run)
    val dataRootDir = File(System.getProperty("user.home"), ".evokammer").also { it.mkdirs() }
    Napier.i("EvoKammer: data root = ${dataRootDir.absolutePath}")

    val substrateFactory = SubstrateDatabaseFactory(dataRootDir)
    val shuruVmManager = ShuruVmManager()

    val engineService = EvoKammerRunService(
        lifecycleManagerProvider = { runId, groupId, openRouterApiKey, agentJarPath, groupProfile ->
            Napier.i("EvoKammer: creating LifecycleManager for runId=$runId groupId=$groupId profile=${groupProfile.profileType} (in-process mode)")

            // Each group gets its own database handle and workspace directory.
            val groupDb = substrateFactory.get(runId, groupId)
            val budgetConfig = BudgetConfig(groupId = groupId, profile = groupProfile)
            val workspaceDir = File(dataRootDir, "workspace/$groupId").also { it.mkdirs() }

            // EvoKammerAgentFactory creates Koog AIAgent instances wired to OpenRouter.
            val agentFactory = EvoKammerAgentFactory(
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
                    Napier.i("EvoKammer [$groupId]: launching agent $agentId (${role.name}) model=$model allocatedBudget=${bootContext.allocatedBudget}")

                    // Early guard: blank API key means the OpenRouter executor will throw at
                    // construction time (simpleOpenRouterExecutor("") is evaluated inside
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
                            "EvoKammer [$groupId]: agent $agentId (${role.name}) CONSTRUCT FAILED " +
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
                    Napier.i("EvoKammer [$groupId]: agent $agentId BudgetLedger registered (initial=${agentContext.budgetLedger.initialBudget})")

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
                            "You are an EvoKammer ${role.name} agent. Fulfill your role objectives for this round."
                        })
                    }
                    Napier.d("EvoKammer [$groupId]: agent $agentId task prompt (${taskPrompt.length} chars): ${taskPrompt.take(200)}…")

                    // ── Phase 2: Agent execution ───────────────────────────────────────
                    try {
                        val result = agentContext.agent.run(taskPrompt)
                        val durationMs = System.currentTimeMillis() - startMs
                        val ledgerSnapshot = agentContext.budgetLedger.snapshot()
                        val spentBudget = String.format(java.util.Locale.ROOT, "%.2f", ledgerSnapshot.initialBudget - ledgerSnapshot.currentBalance)
                        val remainingBudget = String.format(java.util.Locale.ROOT, "%.2f", ledgerSnapshot.currentBalance)
                        Napier.i(
                            "EvoKammer [$groupId]: agent $agentId completed in ${durationMs}ms — " +
                            "budget: spent=$spentBudget remaining=$remainingBudget " +
                            "actions=${ledgerSnapshot.actionLog.size} " +
                            "result=${result.take(100)}"
                        )
                    } catch (e: Exception) {
                        val durationMs = System.currentTimeMillis() - startMs
                        Napier.e(
                            "EvoKammer [$groupId]: agent $agentId (${role.name}) RUN FAILED " +
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
                                errorMessage = e.message ?: "unknown error"
                            )
                        }
                        throw e
                    }
                }
            )
        },
        groupCoordinatorProvider = { groupIds, dimensions ->
            Napier.i("EvoKammer: creating GroupCoordinator for groups=$groupIds")
            GroupCoordinator(groupIds = groupIds, dimensions = dimensions)
        },
        graderGroupManagerProvider = { dimensions ->
            Napier.i("EvoKammer: creating GraderGroupManager with ${dimensions.size} dimensions")
            GraderGroupManager(dimensions = dimensions)
        },
        // Legacy graderRunner disabled — new two-phase architecture used instead.
        graderRunner = null,
        graderCriteriaRunner = { runId, openRouterApiKey, graderGroupIds, roundIndex, runObjective, scoringDimensions, onProgress ->
            // Phase A: criteria development (runs DURING round in parallel with main agents).
            // Grader agents develop the scoring rubric without accessing any competing group.
            if (openRouterApiKey.isBlank()) {
                Napier.w("GraderRunner: API key blank — skipping criteria phase run=$runId round=$roundIndex")
                return@EvoKammerRunService
            }
            val criteriaRunner = GraderRunner(
                openRouterApiKey = openRouterApiKey,
                substrateFactory = substrateFactory,
                localAgentRunner = { graderContext ->
                    val agentId = graderContext.agentId
                    val role = graderContext.role
                    Napier.i("GraderRunner.criteria: launching $agentId (${role.name})")
                    val startMs = System.currentTimeMillis()
                    try {
                        val result = graderContext.agent.run(graderContext.taskPrompt)
                        Napier.i("GraderRunner.criteria: $agentId completed in ${System.currentTimeMillis() - startMs}ms")
                        result
                    } catch (e: Exception) {
                        Napier.w("GraderRunner.criteria: $agentId failed: ${e.message}")
                        "[${role.name} criteria failed: ${e.message?.take(200)}]"
                    }
                }
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
        graderJudgingRunner = { runId, openRouterApiKey, graderGroupIds, competingGroupIds, roundIndex, runObjective, scoringDimensions, graderGroupManager, onProgress ->
            // Phase B: RoundJudge — reads criteria from grader staging DB + scores all competing groups.
            if (openRouterApiKey.isBlank()) {
                Napier.w("GraderRunner: API key blank — skipping judging phase run=$runId round=$roundIndex")
                return@EvoKammerRunService
            }
            val judgingRunner = GraderRunner(
                openRouterApiKey = openRouterApiKey,
                substrateFactory = substrateFactory,
                localAgentRunner = { graderContext ->
                    val agentId = graderContext.agentId
                    val role = graderContext.role
                    Napier.i("GraderRunner.judge: launching $agentId (${role.name}) targeting ${graderContext.targetGroupId}")
                    val startMs = System.currentTimeMillis()
                    try {
                        val result = graderContext.agent.run(graderContext.taskPrompt)
                        Napier.i("GraderRunner.judge: $agentId completed in ${System.currentTimeMillis() - startMs}ms")
                        result
                    } catch (e: Exception) {
                        Napier.w("GraderRunner.judge: $agentId failed: ${e.message}")
                        "[${role.name} judge failed: ${e.message?.take(200)}]"
                    }
                }
            )
            judgingRunner.runJudgingPhase(
                runId = runId,
                graderGroupIds = graderGroupIds,
                competingGroupIds = competingGroupIds,
                roundIndex = roundIndex,
                runObjective = runObjective,
                scoringDimensions = scoringDimensions,
                graderGroupManager = graderGroupManager,
                onProgress = onProgress
            )
        },
        substrateFactory = substrateFactory,
        shuruVmManager = shuruVmManager,
        genesisCouncilRunner = { runId, objective, openRouterApiKey, onProgress ->
            // Wire Genesis Council to the real CouncilRunner backed by OpenRouter.
            // The API key flows from RunConfig → EvoKammerRunService → this lambda.
            Napier.i("GenesisCouncil: starting council for run=$runId objective='${objective.take(80)}'")
            onProgress?.invoke("Genesis Council initialising — assembling council agents…")

            val council = GenesisCouncil(objective = objective, openRouterApiKey = openRouterApiKey)
            val runner = CouncilRunner(openRouterApiKey = openRouterApiKey)

            Napier.i("GenesisCouncil: running council agents (this may take 1–3 minutes)")
            onProgress?.invoke("Genesis Council running 8 specialist agents in sequence…")

            try {
                val startingField = council.run(
                    agentRunner = runner.agentRunner(),
                    onProgress = onProgress
                )
                Napier.i("GenesisCouncil: council complete — groups=${startingField.groupCount} " +
                    "models=${startingField.modelAssignment.size} " +
                    "stages=${startingField.stageContents.size}")
                onProgress?.invoke("Genesis Council complete — ${startingField.groupCount} groups designed")
                // Overlay runId from the active run (council generates its own placeholder)
                startingField.copy(runId = runId)
            } catch (e: Exception) {
                Napier.e("GenesisCouncil: council run failed: ${e.message}", e)
                onProgress?.invoke("Genesis Council failed: ${e.message} — using defaults")
                com.achulkov.evokammer.core.contract.StartingField.default(objective, runId)
            }
        }
    )

    val viewModel = EvoKammerViewModel(
        engineService = engineService,
        substrateFactory = substrateFactory,
        dataRootDir = dataRootDir
    )

    val windowState = rememberWindowState(size = DpSize(1400.dp, 900.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "EvoKammer — Civilisational AI Control Panel",
        state = windowState
    ) {
        EvoKammerApp(viewModel = viewModel)
    }
}
