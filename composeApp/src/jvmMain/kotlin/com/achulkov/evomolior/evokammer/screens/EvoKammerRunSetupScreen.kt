package com.achulkov.evomolior.evokammer.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import com.achulkov.evomolior.evokammer.EvoKammerRoute
import com.achulkov.evomolior.evokammer.LocalEvoKammerNavigator
import com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModel
import com.achulkov.evomolior.evokammer.viewmodel.StubEvoKammerEngineService
import com.achulkov.evomolior.evokammer.viewmodel.defaultRunConfig

/**
 * Run Setup screen — form to configure and start a new EvoKammer civilisation run.
 *
 * Sections:
 * - Objective (text field)
 * - OpenRouter API key
 * - Group count and profiles
 * - Rounds + round duration
 * - Scoring dimensions (add/remove/edit)
 * - Launch button
 */
@Composable
fun EvoKammerRunSetupScreen(
    viewModel: EvoKammerViewModel = remember { EvoKammerViewModel(StubEvoKammerEngineService()) }
) {
    val navigate = LocalEvoKammerNavigator.current

    var objective by remember { mutableStateOf("Design a distributed, fault-tolerant knowledge synthesis system") }
    var apiKey by remember { mutableStateOf("") }
    var groupCount by remember { mutableStateOf(3) }
    var groupProfiles by remember {
        mutableStateOf(listOf(GroupProfileType.Library, GroupProfileType.Factory, GroupProfileType.Commune))
    }
    var rounds by remember { mutableStateOf(5) }
    var roundDuration by remember { mutableStateOf(30) }
    var maxAgents by remember { mutableStateOf(6) }
    var dimensions by remember {
        mutableStateOf(
            listOf(
                ScoringDimension("kb_density", "KB Density", "Volume and quality of KB entries", 0.3),
                ScoringDimension("kg_coherence", "KG Coherence", "Knowledge graph consistency score", 0.25),
                ScoringDimension("tool_utility", "Tool Utility", "Quality of custom tools built", 0.25),
                ScoringDimension("plan_execution", "Plan Execution", "% of WiP tasks completed", 0.2)
            )
        )
    }
    var launchError by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { navigate(EvoKammerRoute.Dashboard) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("New Run Setup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }

        // Objective
        item {
            SectionCard(title = "Objective") {
                OutlinedTextField(
                    value = objective,
                    onValueChange = { objective = it },
                    label = { Text("Civilisation Objective") },
                    placeholder = { Text("E.g. Solve climate change through distributed research") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
            }
        }

        // API Key
        item {
            SectionCard(title = "LLM Configuration") {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("OpenRouter API Key") },
                    placeholder = { Text("sk-or-v1-...") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        }

        // Group configuration
        item {
            SectionCard(title = "Groups") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Group Count:", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = groupCount.toFloat(),
                            onValueChange = { v ->
                                val newCount = v.toInt().coerceIn(2, 8)
                                groupCount = newCount
                                groupProfiles = List(newCount) { idx ->
                                    groupProfiles.getOrElse(idx) { GroupProfileType.entries[idx % GroupProfileType.entries.size] }
                                }
                            },
                            valueRange = 2f..8f,
                            steps = 5,
                            modifier = Modifier.weight(1f)
                        )
                        Text("$groupCount", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }

                    Text("Group Profiles:", style = MaterialTheme.typography.bodyMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groupProfiles.forEachIndexed { idx, profile ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("Group $idx:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(60.dp))
                                GroupProfileType.entries.forEach { profileType ->
                                    FilterChip(
                                        selected = profile == profileType,
                                        onClick = {
                                            groupProfiles = groupProfiles.toMutableList().also { it[idx] = profileType }
                                        },
                                        label = { Text(profileType.name, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Run parameters
        item {
            SectionCard(title = "Run Parameters") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Rounds", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = rounds.toFloat(),
                                onValueChange = { rounds = it.toInt().coerceIn(1, 20) },
                                valueRange = 1f..20f,
                                steps = 18
                            )
                            Text("$rounds rounds", style = MaterialTheme.typography.labelSmall)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Round Duration", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = roundDuration.toFloat(),
                                onValueChange = { roundDuration = it.toInt().coerceIn(5, 120) },
                                valueRange = 5f..120f,
                                steps = 22
                            )
                            Text("$roundDuration min", style = MaterialTheme.typography.labelSmall)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Max Agents / Group", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = maxAgents.toFloat(),
                                onValueChange = { maxAgents = it.toInt().coerceIn(2, 12) },
                                valueRange = 2f..12f,
                                steps = 9
                            )
                            Text("$maxAgents agents", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // Scoring dimensions
        item {
            SectionCard(title = "Scoring Dimensions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dimensions.forEachIndexed { idx, dim ->
                        DimensionRow(
                            dimension = dim,
                            onWeightChange = { newWeight ->
                                dimensions = dimensions.toMutableList().also {
                                    it[idx] = dim.copy(weight = newWeight)
                                }
                            },
                            onDelete = {
                                if (dimensions.size > 1) {
                                    dimensions = dimensions.toMutableList().also { it.removeAt(idx) }
                                }
                            }
                        )
                    }
                    TextButton(
                        onClick = {
                            val newId = "dim_${System.currentTimeMillis()}"
                            dimensions = dimensions + ScoringDimension(newId, "New Dimension", "Description", 0.1)
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add Dimension")
                    }

                    val totalWeight = dimensions.sumOf { it.weight }
                    Text(
                        "Total weight: ${String.format("%.2f", totalWeight)} ${if (kotlin.math.abs(totalWeight - 1.0) < 0.01) "✓" else "⚠️ should sum to 1.0"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (kotlin.math.abs(totalWeight - 1.0) < 0.01)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Error message
        item {
            launchError?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Launch button
        item {
            Button(
                onClick = {
                    if (apiKey.isBlank()) {
                        launchError = "OpenRouter API key is required"
                        return@Button
                    }
                    if (objective.isBlank()) {
                        launchError = "Objective cannot be empty"
                        return@Button
                    }
                    val config = RunConfig(
                        runId = java.util.UUID.randomUUID().toString(),
                        objective = objective,
                        groupCount = groupCount,
                        groupProfiles = groupProfiles,
                        rounds = rounds,
                        roundDurationMinutes = roundDuration,
                        maxAgentsPerGroup = maxAgents,
                        scoringDimensions = dimensions,
                        openRouterApiKey = apiKey
                    )
                    viewModel.startRun(config)
                    navigate(EvoKammerRoute.RunMonitor(config.runId))
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Launch Civilisation Run", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun DimensionRow(
    dimension: ScoringDimension,
    onWeightChange: (Double) -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(dimension.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(dimension.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.weight(0.5f)) {
                Text("Weight: ${String.format("%.2f", dimension.weight)}", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = dimension.weight.toFloat(),
                    onValueChange = { onWeightChange(it.toDouble().coerceIn(0.01, 1.0)) },
                    valueRange = 0.01f..1.0f
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remove dimension", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            content()
        }
    }
}
