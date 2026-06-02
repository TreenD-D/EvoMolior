package com.achulkov.evomolior.evokammer.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.achulkov.evokammer.substrate.db.entity.DeadEndsEntity
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.KGSchemaEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evokammer.substrate.db.entity.LegacyProtocolEntity
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evokammer.substrate.db.entity.TrustScoreEntity
import com.achulkov.evokammer.substrate.db.entity.WipTaskEntity
import com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val DATE_FMT = SimpleDateFormat("MMM d, HH:mm", Locale.ROOT)

/**
 * Substrate Browser Screen — full persistent database viewer for all 9 substrate layers.
 *
 * Replaces the previous event-stream projection with direct Room DB reads.
 * Supports all historical runs discovered on disk, with run + group selectors.
 *
 * Tabs: KB Docs | KG Facts | Tools | Staging | Event Log | WIP Tasks | Dead Ends | Legacy | Trust | KG Schema
 *
 * @param viewModel The shared EvoKammer view model.
 * @param initialRunId Optional run to pre-select when the screen opens.
 * @param initialGroupId Optional group to pre-select when the screen opens.
 * @param onBack Back navigation callback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvoKammerSubstrateBrowserScreen(
    viewModel: EvoKammerViewModel,
    initialRunId: String? = null,
    initialGroupId: String? = null,
    onBack: () -> Unit
) {
    val availableRuns by viewModel.availableRuns.collectAsState()
    val selectedRunId by viewModel.selectedBrowserRunId.collectAsState()
    val selectedGroupId by viewModel.selectedBrowserGroupId.collectAsState()
    val snapshot by viewModel.substrateSnapshot.collectAsState()
    val isLoading by viewModel.substrateLoading.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var runDropdownOpen by remember { mutableStateOf(false) }

    // Apply the requested historical run/group when the browser opens.
    LaunchedEffect(initialRunId, initialGroupId, availableRuns) {
        if (initialRunId != null && selectedRunId != initialRunId) {
            viewModel.selectBrowserRun(initialRunId)
            return@LaunchedEffect
        }
        if (initialGroupId != null && selectedGroupId != initialGroupId) {
            viewModel.selectBrowserGroup(initialGroupId)
        }
    }

    // Current run info for group chips
    val currentRunInfo = remember(selectedRunId, availableRuns) {
        availableRuns.find { it.runId == selectedRunId }
    }

    val tabs = listOf("KB Docs", "KG Facts", "Tools", "Staging", "Event Log", "WIP Tasks", "Dead Ends", "Legacy", "Trust", "KG Schema")
    val tabCounts = remember(snapshot) {
        listOf(
            snapshot?.kbEntries?.size ?: 0,
            snapshot?.kgFacts?.size ?: 0,
            snapshot?.tools?.size ?: 0,
            snapshot?.stagingSubmissions?.size ?: 0,
            snapshot?.eventLogEntries?.size ?: 0,
            snapshot?.wipTasks?.size ?: 0,
            snapshot?.deadEnds?.size ?: 0,
            snapshot?.legacyProtocols?.size ?: 0,
            snapshot?.trustScores?.size ?: 0,
            if (snapshot?.kgSchema != null) 1 else 0
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Substrate Browser", style = MaterialTheme.typography.titleMedium)
                        if (selectedRunId != null) {
                            Text(
                                "Run: ${selectedRunId?.take(12)}…  •  Group: ${selectedGroupId ?: "—"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.refreshAvailableRuns()
                            viewModel.refreshSubstrate()
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            // ── Loading indicator ──────────────────────────────────────────
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // ── Run selector ───────────────────────────────────────────────
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Run:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Box {
                        TextButton(
                            onClick = { runDropdownOpen = true }
                        ) {
                            Text(
                                text = if (selectedRunId != null)
                                    "${selectedRunId!!.take(16)}… (${currentRunInfo?.groupIds?.size ?: 0} groups)"
                                else "Select run…",
                                maxLines = 1
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = runDropdownOpen,
                            onDismissRequest = { runDropdownOpen = false }
                        ) {
                            if (availableRuns.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No runs found on disk", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = { runDropdownOpen = false }
                                )
                            } else {
                                availableRuns.forEach { run ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    "${run.runId.take(20)}…",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                                Text(
                                                    "${run.groupIds.size} groups  •  ${DATE_FMT.format(Date(run.lastModifiedMs))}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.selectBrowserRun(run.runId)
                                            runDropdownOpen = false
                                        },
                                        trailingIcon = if (run.runId == selectedRunId) {
                                            { Text("✓", color = MaterialTheme.colorScheme.primary) }
                                        } else null
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    if (snapshot?.loaded == true) {
                        Text(
                            text = "${snapshot!!.totalRecords} records",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Group chips ────────────────────────────────────────────────
            if (currentRunInfo != null && currentRunInfo.groupIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Group:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    currentRunInfo.groupIds.forEach { gid ->
                        FilterChip(
                            selected = gid == selectedGroupId,
                            onClick = { viewModel.selectBrowserGroup(gid) },
                            label = { Text(gid, maxLines = 1) }
                        )
                    }
                }
            }

            // ── Search bar ─────────────────────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search across all layers…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } }
                } else null,
                singleLine = true
            )

            // ── No run selected / no substrate found states ────────────────
            if (availableRuns.isEmpty() && !isLoading) {
                EmptySubstrateState(
                    message = "No runs found on disk",
                    detail = "Start a run to create substrate data, or check your data directory."
                )
                return@Scaffold
            }

            if (selectedRunId == null) {
                EmptySubstrateState(message = "Select a run above to browse its substrate")
                return@Scaffold
            }

            if (selectedGroupId == null) {
                EmptySubstrateState(message = "Select a group to view its substrate data")
                return@Scaffold
            }

            if (snapshot != null && !snapshot!!.loaded && !isLoading) {
                EmptySubstrateState(
                    message = "No substrate data found",
                    detail = "The DB file may not exist yet for run=${selectedRunId?.take(12)}, group=$selectedGroupId."
                )
                return@Scaffold
            }

            // ── Tab row ────────────────────────────────────────────────────
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp
            ) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = {
                            val count = tabCounts.getOrElse(idx) { 0 }
                            if (count > 0) {
                                BadgedBox(badge = {
                                    Badge { Text(if (count > 999) "999+" else count.toString()) }
                                }) {
                                    Text(label)
                                }
                            } else {
                                Text(label)
                            }
                        }
                    )
                }
            }

            // ── Tab content ────────────────────────────────────────────────
            // SelectionContainer wraps ALL tab content so any text in the substrate
            // browser can be selected and copied (Ctrl+C / Cmd+C).
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val s = snapshot
                    when (selectedTab) {
                        0 -> KBDocsTab(s?.kbEntries ?: emptyList(), searchQuery, s?.loaded == true)
                        1 -> KGFactsTab(s?.kgFacts ?: emptyList(), searchQuery, s?.loaded == true)
                        2 -> ToolsTab(s?.tools ?: emptyList(), searchQuery, s?.loaded == true)
                        3 -> StagingTab(s?.stagingSubmissions ?: emptyList(), searchQuery, s?.loaded == true)
                        4 -> EventLogTab(s?.eventLogEntries ?: emptyList(), searchQuery, s?.loaded == true)
                        5 -> WipTasksTab(s?.wipTasks ?: emptyList(), searchQuery, s?.loaded == true)
                        6 -> DeadEndsTab(s?.deadEnds ?: emptyList(), searchQuery, s?.loaded == true)
                        7 -> LegacyTab(s?.legacyProtocols ?: emptyList(), searchQuery, s?.loaded == true)
                        8 -> TrustTab(s?.trustScores ?: emptyList(), searchQuery, s?.loaded == true)
                        9 -> KGSchemaTab(s?.kgSchema, s?.loaded == true)
                    }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Tab 1: KB Docs
// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun KBDocsTab(entries: List<KnowledgeBaseEntryEntity>, query: String, loaded: Boolean) {
    val filtered = remember(entries, query) {
        entries.filter { e ->
            query.isBlank() ||
                e.domain.contains(query, ignoreCase = true) ||
                e.content.contains(query, ignoreCase = true) ||
                e.sourceAgentId.contains(query, ignoreCase = true)
        }
    }
    if (!loaded) { LoadingTabState(); return }
    if (filtered.isEmpty()) { EmptyTabState("No KB documents"); return }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filtered, key = { it.id }) { entry ->
            KBDocCard(entry)
        }
    }
}

@Composable
private fun KBDocCard(entry: KnowledgeBaseEntryEntity) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (entry.status) {
        "active" -> Color(0xFF2E7D32)
        "archived" -> Color(0xFF9E9E9E)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    SubstrateCard(
        onClick = { expanded = !expanded }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TagBadge(entry.domain.ifBlank { "unknown" }, MaterialTheme.colorScheme.secondary)
                    if (entry.isDeadEnd) TagBadge("DEAD END", MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (expanded) entry.content else entry.content.take(180) + if (entry.content.length > 180) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                TagBadge(entry.status, statusColor)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Trust ${(entry.trustScore * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "⌥ ${entry.citationCount} cites",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Gen ${entry.generation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                MetaRow("ID", entry.id)
                MetaRow("Agent", entry.sourceAgentId)
                entry.previousVersionId?.let { MetaRow("Prev", it) }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Tab 2: KG Facts
// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun KGFactsTab(facts: List<KnowledgeGraphFactEntity>, query: String, loaded: Boolean) {
    val filtered = remember(facts, query) {
        facts.filter { f ->
            query.isBlank() ||
                f.subject.contains(query, ignoreCase = true) ||
                f.relation.contains(query, ignoreCase = true) ||
                f.objectNode.contains(query, ignoreCase = true)
        }
    }
    if (!loaded) { LoadingTabState(); return }
    if (filtered.isEmpty()) { EmptyTabState("No KG facts"); return }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filtered, key = { it.id }) { fact ->
            KGFactCard(fact)
        }
    }
}

@Composable
private fun KGFactCard(fact: KnowledgeGraphFactEntity) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (fact.status) {
        "active" -> Color(0xFF1565C0)
        "disputed" -> Color(0xFFEF6C00)
        "rejected" -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    SubstrateCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        fact.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        "  —${fact.relation}→  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        fact.objectNode,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (fact.speculative) TagBadge("SPECULATIVE", Color(0xFFEF6C00))
                    TagBadge(fact.status, statusColor)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Conf ${(fact.confidence * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Trust ${(fact.trustScore * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Gen ${fact.generation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                MetaRow("ID", fact.id)
                MetaRow("Agent", fact.sourceAgentId)
                if (fact.provenanceJson.isNotBlank()) MetaRow("Prov", fact.provenanceJson.take(120))
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Tab 3: Tools
// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ToolsTab(tools: List<ToolRegistryEntryEntity>, query: String, loaded: Boolean) {
    val filtered = remember(tools, query) {
        tools.filter { t ->
            query.isBlank() ||
                t.name.contains(query, ignoreCase = true) ||
                t.domain.contains(query, ignoreCase = true) ||
                t.authorAgentId.contains(query, ignoreCase = true)
        }
    }
    if (!loaded) { LoadingTabState(); return }
    if (filtered.isEmpty()) { EmptyTabState("No tools registered"); return }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filtered, key = { it.id }) { tool ->
            ToolCard(tool)
        }
    }
}

@Composable
private fun ToolCard(tool: ToolRegistryEntryEntity) {
    var expanded by remember { mutableStateOf(false) }
    val successPct = (tool.successRate * 100).roundToInt()
    val successColor = when {
        successPct >= 80 -> Color(0xFF2E7D32)
        successPct >= 50 -> Color(0xFFEF6C00)
        else -> Color(0xFFC62828)
    }
    SubstrateCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tool.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TagBadge("v${tool.version}", MaterialTheme.colorScheme.tertiary)
                    TagBadge(tool.domain.ifBlank { "general" }, MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Author: ${tool.authorAgentId.take(12)}…  •  ${tool.codeLanguage}  •  Gen ${tool.generation}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                TagBadge("$successPct% success", successColor)
                Text(
                    tool.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                MetaRow("ID", tool.id)
                if (tool.inputSchemaJson.isNotBlank()) MetaRow("Input", tool.inputSchemaJson.take(120))
                if (tool.failureLogJson.isNotBlank()) MetaRow("Failures", tool.failureLogJson.take(120))
                if (tool.code.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tool.code.take(400),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Tab 4: Staging
// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun StagingTab(items: List<StagingSubmissionEntity>, query: String, loaded: Boolean) {
    val filtered = remember(items, query) {
        items.filter { s ->
            query.isBlank() ||
                s.type.contains(query, ignoreCase = true) ||
                s.status.contains(query, ignoreCase = true) ||
                s.content.contains(query, ignoreCase = true) ||
                s.agentId.contains(query, ignoreCase = true)
        }
    }
    if (!loaded) { LoadingTabState(); return }
    if (filtered.isEmpty()) { EmptyTabState("No staging submissions"); return }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filtered, key = { it.id }) { sub ->
            StagingCard(sub)
        }
    }
}

@Composable
private fun StagingCard(sub: StagingSubmissionEntity) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (sub.status) {
        "promoted" -> Color(0xFF2E7D32)
        "rejected" -> Color(0xFFC62828)
        "pending" -> Color(0xFF1565C0)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    SubstrateCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagBadge(sub.type, MaterialTheme.colorScheme.tertiary)
                    TagBadge(sub.status, statusColor)
                    if (sub.speculative) TagBadge("SPECULATIVE", Color(0xFFEF6C00))
                    if (sub.outlier) TagBadge("OUTLIER", Color(0xFF7B1FA2))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    sub.content.take(180) + if (sub.content.length > 180) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (sub.status == "rejected" && !sub.rejectionReason.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "⚠ ${sub.rejectionReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Gen ${sub.generation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                sub.initialTrustScore?.let { trust ->
                    Text(
                        "Trust ${(trust * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                MetaRow("ID", sub.id)
                MetaRow("Agent", sub.agentId)
                MetaRow("Role", sub.role)
                sub.wipTaskRef?.let { MetaRow("WIP Ref", it) }
                sub.criticAgentId?.let { MetaRow("Critic", it) }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Tab 5: Event Log
// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun EventLogTab(entries: List<EventLogEntryEntity>, query: String, loaded: Boolean) {
    val filtered = remember(entries, query) {
        entries.filter { e ->
            query.isBlank() ||
                e.eventType.contains(query, ignoreCase = true) ||
                e.agentId.orEmpty().contains(query, ignoreCase = true) ||
                e.payloadJson.contains(query, ignoreCase = true)
        }
    }
    if (!loaded) { LoadingTabState(); return }
    if (filtered.isEmpty()) { EmptyTabState("No event log entries"); return }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(filtered, key = { it.id }) { entry ->
            EventLogEntryCard(entry)
        }
    }
}

@Composable
private fun EventLogEntryCard(entry: EventLogEntryEntity) {
    var expanded by remember { mutableStateOf(false) }
    val eventColor = when {
        entry.eventType.contains("ERROR", ignoreCase = true) ||
        entry.eventType.contains("FAIL", ignoreCase = true) -> MaterialTheme.colorScheme.error
        entry.eventType.contains("WARN", ignoreCase = true) -> Color(0xFFEF6C00)
        entry.eventType.startsWith("Round") || entry.eventType.startsWith("Run") -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    SubstrateCard(onClick = { expanded = !expanded }, elevation = 0.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TagBadge(entry.eventType, eventColor)
                    Text(
                        entry.agentId.orEmpty().let { aid -> aid.take(12) + if (aid.length > 12) "…" else "" },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (expanded && entry.payloadJson.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.payloadJson.take(400),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Gen ${entry.generation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(entry.timestampMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Tab 6: WIP Tasks
// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun WipTasksTab(tasks: List<WipTaskEntity>, query: String, loaded: Boolean) {
    val filtered = remember(tasks, query) {
        tasks.filter { t ->
            query.isBlank() ||
                t.description.contains(query, ignoreCase = true) ||
                t.status.contains(query, ignoreCase = true) ||
                t.claimedByAgentId.orEmpty().contains(query, ignoreCase = true)
        }
    }
    if (!loaded) { LoadingTabState(); return }
    if (filtered.isEmpty()) { EmptyTabState("No WIP tasks"); return }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filtered, key = { it.nodeId }) { task ->
            WipTaskCard(task)
        }
    }
}

@Composable
private fun WipTaskCard(task: WipTaskEntity) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (task.status) {
        "unclaimed" -> MaterialTheme.colorScheme.onSurfaceVariant
        "claimed" -> Color(0xFF1565C0)
        "completed" -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    SubstrateCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagBadge(task.status, statusColor)
                    if (task.abandonCount > 0) {
                        TagBadge("abandoned ×${task.abandonCount}", MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    task.description.take(200) + if (task.description.length > 200) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 3
                )
                task.claimedByAgentId?.let { agentId ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Claimed by: $agentId",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Gen ${task.lastChangedGeneration}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                MetaRow("Node ID", task.nodeId)
                task.linkedNodeId?.let { MetaRow("Linked", it) }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Tab 7: Dead Ends
// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeadEndsTab(deadEnds: List<DeadEndsEntity>, query: String, loaded: Boolean) {
    val filtered = remember(deadEnds, query) {
        deadEnds.filter { d ->
            query.isBlank() ||
                d.approach.contains(query, ignoreCase = true) ||
                d.reason.contains(query, ignoreCase = true) ||
                d.domain.contains(query, ignoreCase = true) ||
                d.sourceAgentId.contains(query, ignoreCase = true)
        }
    }
    if (!loaded) { LoadingTabState(); return }
    if (filtered.isEmpty()) { EmptyTabState("No dead ends recorded"); return }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filtered, key = { it.id }) { de ->
            DeadEndCard(de)
        }
    }
}

@Composable
private fun DeadEndCard(de: DeadEndsEntity) {
    var expanded by remember { mutableStateOf(false) }
    SubstrateCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagBadge("DEAD END", MaterialTheme.colorScheme.error)
                    TagBadge(de.domain.ifBlank { "unknown" }, MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Approach: ${de.approach.take(120)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Reason: ${de.reason.take(120)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Gen ${de.generation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (de.referenceCount > 0) {
                    Text(
                        "×${de.referenceCount} refs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                MetaRow("ID", de.id)
                MetaRow("Agent", de.sourceAgentId.ifBlank { "unknown" })
                MetaRow("Created", DATE_FMT.format(Date(de.createdAtMs)))
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Tab 8: Legacy Protocols
// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LegacyTab(protocols: List<LegacyProtocolEntity>, query: String, loaded: Boolean) {
    val filtered = remember(protocols, query) {
        protocols.filter { p ->
            query.isBlank() ||
                p.role.contains(query, ignoreCase = true) ||
                p.reasoningPosture.contains(query, ignoreCase = true) ||
                p.toolHeuristicsJson.contains(query, ignoreCase = true)
        }
    }
    if (!loaded) { LoadingTabState(); return }
    if (filtered.isEmpty()) { EmptyTabState("No legacy protocols extracted"); return }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filtered, key = { it.id }) { proto ->
            LegacyProtocolCard(proto)
        }
    }
}

@Composable
private fun LegacyProtocolCard(proto: LegacyProtocolEntity) {
    var expanded by remember { mutableStateOf(false) }
    SubstrateCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagBadge(proto.role, MaterialTheme.colorScheme.secondary)
                    TagBadge("Gen ${proto.extractedFromGeneration} → ${proto.expiresAtGeneration}", MaterialTheme.colorScheme.tertiary)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    proto.reasoningPosture.take(200) + if (proto.reasoningPosture.length > 200) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (proto.toolHeuristicsJson.isNotBlank() && proto.toolHeuristicsJson != "{}") {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Tools: ${proto.toolHeuristicsJson.take(100)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                MetaRow("ID", proto.id)
                MetaRow("Sources", proto.sourceAgentIds)
                if (proto.failureMapJson.isNotBlank() && proto.failureMapJson != "{}") {
                    MetaRow("Failures", proto.failureMapJson.take(200))
                }
                if (proto.kgHabitsJson.isNotBlank() && proto.kgHabitsJson != "{}") {
                    MetaRow("KG Habits", proto.kgHabitsJson.take(200))
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Tab 9: Trust Scores
// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrustTab(scores: List<TrustScoreEntity>, query: String, loaded: Boolean) {
    val filtered = remember(scores, query) {
        scores.filter { s ->
            query.isBlank() ||
                s.sourceId.contains(query, ignoreCase = true) ||
                s.sourceType.contains(query, ignoreCase = true)
        }
    }
    if (!loaded) { LoadingTabState(); return }
    if (filtered.isEmpty()) { EmptyTabState("No trust score records"); return }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(filtered, key = { it.id }) { score ->
            TrustScoreCard(score)
        }
    }
}

@Composable
private fun TrustScoreCard(score: TrustScoreEntity) {
    val trustPct = (score.score * 100).roundToInt()
    val trustColor = when {
        trustPct >= 80 -> Color(0xFF2E7D32)
        trustPct >= 50 -> Color(0xFFEF6C00)
        else -> Color(0xFFC62828)
    }
    SubstrateCard(onClick = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TagBadge(score.sourceType, MaterialTheme.colorScheme.secondary)
                    Text(
                        score.sourceId.take(20) + if (score.sourceId.length > 20) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Promotions: ${score.promotionCount}  •  Rejections: ${score.rejectionCount}  •  " +
                    "Decays: ${score.decayCount}  •  " +
                    "Last: ${DATE_FMT.format(Date(score.lastUpdatedMs))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(trustColor.copy(alpha = 0.15f), MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$trustPct%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = trustColor
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Tab 10: KG Schema
// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun KGSchemaTab(schema: KGSchemaEntity?, loaded: Boolean) {
    if (!loaded) { LoadingTabState(); return }
    if (schema == null) { EmptyTabState("No KG schema defined yet"); return }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { KGSchemaCard(schema) }
    }
}

@Composable
private fun KGSchemaCard(schema: KGSchemaEntity) {
    var expanded by remember { mutableStateOf(true) }
    SubstrateCard(onClick = { expanded = !expanded }) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TagBadge("v${schema.schemaVersion}", MaterialTheme.colorScheme.primary)
                Text(
                    "KG Schema",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                DATE_FMT.format(Date(schema.createdAtMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Entity types
                KGSchemaSection(
                    title = "Entity Types",
                    color = Color(0xFF1565C0),
                    jsonArray = schema.entityTypesJson
                )

                // Relation types
                KGSchemaSection(
                    title = "Relation Types",
                    color = Color(0xFF2E7D32),
                    jsonArray = schema.relationTypesJson
                )

                // Hard constraints
                KGSchemaConstraintSection(
                    title = "Hard Constraints",
                    color = MaterialTheme.colorScheme.error,
                    constraintsJson = schema.hardConstraintsJson
                )

                // Soft constraints
                KGSchemaConstraintSection(
                    title = "Soft Constraints",
                    color = Color(0xFFEF6C00),
                    constraintsJson = schema.softConstraintsJson
                )

                // Footer
                MetaRow("Schema ID", schema.id)
            }
        }
    }
}

@Composable
private fun KGSchemaSection(title: String, color: Color, jsonArray: String) {
    // Parse simple string array e.g. ["Concept","Algorithm"]
    val items = remember(jsonArray) {
        try {
            jsonArray
                .trim('[', ']', ' ')
                .split(",")
                .map { it.trim().trim('"') }
                .filter { it.isNotBlank() }
        } catch (_: Exception) { listOf(jsonArray) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
        if (items.isEmpty()) {
            Text(
                "none",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items.forEach { label -> TagBadge(label, color) }
            }
        }
    }
}

@Composable
private fun KGSchemaConstraintSection(title: String, color: Color, constraintsJson: String) {
    // Each constraint is a JSON object { "id": "HC-001", "description": "...", ... }
    val lines = remember(constraintsJson) {
        try {
            // Simple extraction: find all "description":"..." values
            val descRegex = Regex(""""description"\s*:\s*"([^"]+)"""")
            val idRegex = Regex(""""id"\s*:\s*"([^"]+)"""")
            val penaltyRegex = Regex(""""penaltyFactor"\s*:\s*([\d.]+)""")

            // Split by object boundaries (crude but avoids a serialization dep)
            val objects = constraintsJson
                .split(Regex("""\},\s*\{"""))
                .map { it.trim('{', '}', '[', ']', ' ') }
                .filter { it.isNotBlank() }

            objects.map { obj ->
                val id = idRegex.find(obj)?.groupValues?.get(1) ?: "?"
                val desc = descRegex.find(obj)?.groupValues?.get(1) ?: obj.take(60)
                val penalty = penaltyRegex.find(obj)?.groupValues?.get(1)
                if (penalty != null) "$id — $desc  [penalty ×${penalty}]" else "$id — $desc"
            }
        } catch (_: Exception) { listOf(constraintsJson.take(200)) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
        if (lines.isEmpty()) {
            Text(
                "none",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            lines.forEach { line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(5.dp)
                            .background(color.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
                    )
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Shared primitives
// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SubstrateCard(
    onClick: (() -> Unit)?,
    elevation: androidx.compose.ui.unit.Dp = 1.dp,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

@Composable
private fun TagBadge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun EmptyTabState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LoadingTabState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Loading substrate data…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptySubstrateState(message: String, detail: String? = null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (detail != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
