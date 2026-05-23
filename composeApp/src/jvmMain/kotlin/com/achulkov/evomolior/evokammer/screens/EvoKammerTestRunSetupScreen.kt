package com.achulkov.evomolior.evokammer.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evomolior.common.BuildConfig
import com.achulkov.evomolior.evokammer.EvoKammerRoute
import com.achulkov.evomolior.evokammer.LocalEvoKammerNavigator
import com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModel
import com.achulkov.evomolior.evokammer.viewmodel.StubEvoKammerEngineService

// Fixed test run parameters — intentionally not configurable to keep diagnostics reproducible.
private const val TEST_BUDGET = 200.0
private const val TEST_AGENTS = 3
private const val TEST_ROUNDS = 1
private val TEST_GROUP_PROFILES = listOf(GroupProfileType.Library)

/**
 * Test Run Setup screen — a minimal diagnostic launcher.
 *
 * Pre-configures a single-group, single-round run with 3 agents and a 200-point budget,
 * bypassing the Genesis Council. The objective is the only user-supplied input.
 *
 * Intended for verifying that agents receive tasks, KB entries are written, tools are
 * registered, and death notes are properly committed to the substrate — without spending
 * real LLM budget on council orchestration or multi-group competition logic.
 */
@Composable
fun EvoKammerTestRunSetupScreen(
    viewModel: EvoKammerViewModel = remember { EvoKammerViewModel(StubEvoKammerEngineService()) }
) {
    val navigate = LocalEvoKammerNavigator.current

    var objective by remember {
        mutableStateOf("Design a distributed, fault-tolerant knowledge synthesis system")
    }
    var apiKey by remember { mutableStateOf(BuildConfig.OPENROUTER_API_KEY) }
    var launchError by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { navigate(EvoKammerRoute.Dashboard) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column {
                    Text(
                        "Test Run",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Minimal diagnostic run — verifies agent pipeline end-to-end",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── Fixed parameter summary ────────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Fixed Test Parameters",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        TestParamItem(label = "Groups", value = "1 (Library)")
                        TestParamItem(label = "Agents", value = "$TEST_AGENTS")
                        TestParamItem(label = "Rounds", value = "$TEST_ROUNDS")
                        TestParamItem(label = "Budget", value = "${TEST_BUDGET.toInt()} pts")
                    }

                    Text(
                        "Genesis Council is bypassed — defaults are used directly. " +
                            "All substrate writes (KB, KG, tools, death notes) are logged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // ── Objective ─────────────────────────────────────────────────────────
        item {
            SectionCard(title = "Objective") {
                OutlinedTextField(
                    value = objective,
                    onValueChange = { objective = it },
                    label = { Text("Starting Prompt") },
                    placeholder = { Text("E.g. Solve climate change through distributed research") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                Text(
                    "The agents will receive this prompt as their civilisation goal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── LLM Config ────────────────────────────────────────────────────────
        item {
            SectionCard(title = "LLM Configuration") {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("OpenRouter API Key") },
                    placeholder = {
                        Text(
                            if (BuildConfig.OPENROUTER_API_KEY.isNotEmpty())
                                "Pre-filled from build config"
                            else "sk-or-v1-…"
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        }

        // ── What to verify ────────────────────────────────────────────────────
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Verification Checklist",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    HorizontalDivider()
                    ChecklistRow("Agents receive tasks via the Staging DB / WIP queue")
                    ChecklistRow("Genesis Council emits the GENESIS_COMPLETED event (via defaults)")
                    ChecklistRow("KB entries are written and readable in the Substrate Browser")
                    ChecklistRow("KG facts are emitted and stored correctly")
                    ChecklistRow("Tools are registered in the Tool Registry")
                    ChecklistRow("Death Notes are committed to the substrate on agent death")
                    ChecklistRow("Budget decrements are reflected in the Group Summary card")
                }
            }
        }

        // ── Error display ─────────────────────────────────────────────────────
        item {
            launchError?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // ── Launch button ─────────────────────────────────────────────────────
        item {
            Button(
                onClick = {
                    if (apiKey.isBlank()) {
                        launchError = "OpenRouter API key is required"
                        return@Button
                    }
                    if (objective.isBlank()) {
                        launchError = "Objective / starting prompt cannot be empty"
                        return@Button
                    }
                    val config = RunConfig(
                        runId = java.util.UUID.randomUUID().toString(),
                        objective = objective,
                        groupCount = TEST_GROUP_PROFILES.size,
                        groupProfiles = TEST_GROUP_PROFILES,
                        rounds = TEST_ROUNDS,
                        roundDurationMinutes = 5,
                        maxAgentsPerGroup = TEST_AGENTS,
                        scoringDimensions = defaultTestScoringDimensions(),
                        openRouterApiKey = apiKey,
                        isTestRun = true,
                        budgetOverride = TEST_BUDGET
                    )
                    viewModel.startRun(config)
                    navigate(EvoKammerRoute.RunMonitor(config.runId))
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Start Test Run",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

/**
 * Titled card section — mirrors the private SectionCard in EvoKammerRunSetupScreen.
 *
 * @param title Section heading text.
 * @param content Composable content rendered below the divider.
 */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            content()
        }
    }
}

/**
 * Compact label + value row used in the fixed parameter summary card.
 *
 * @param label Human-readable parameter name.
 * @param value The fixed value to display.
 */
@Composable
private fun TestParamItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/**
 * A single line in the verification checklist, prefixed with a ✅ icon.
 *
 * @param text Description of what to verify.
 */
@Composable
private fun ChecklistRow(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("✅", style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Minimal scoring dimensions used for the test run.
 *
 * These are sufficient to exercise the scoring pipeline without requiring
 * real grader group output.
 */
private fun defaultTestScoringDimensions(): List<ScoringDimension> = listOf(
    ScoringDimension("kb_density", "KB Density", "Volume and quality of KB entries", 0.5),
    ScoringDimension("tool_utility", "Tool Utility", "Quality of custom tools built", 0.5)
)
