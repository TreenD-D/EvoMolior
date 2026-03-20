package com.achulkov.evomolior.evokammer.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModel

/**
 * Substrate Browser Screen — browse KB documents, KG facts, tool registry, and dead ends.
 *
 * Tabs: KB | KG Facts | Tools | Dead Ends | Legacy Protocols
 *
 * @param viewModel The shared EvoKammer view model.
 * @param groupId The group whose substrate to browse (null = all groups).
 * @param onBack Back navigation callback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvoKammerSubstrateBrowserScreen(
    viewModel: EvoKammerViewModel,
    groupId: String? = null,
    onBack: () -> Unit
) {
    val events by viewModel.recentEvents.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val tabs = listOf("KB Docs", "KG Facts", "Tools", "Dead Ends", "Legacy")

    // Derive substrate data from the event stream
    val kbEvents = remember(events) {
        events.filterIsInstance<EvoKammerEvent.StagingPromoted>()
            .filter { groupId == null || it.groupId == groupId }
    }
    val agentEvents = remember(events) {
        events.filterIsInstance<EvoKammerEvent.AgentSpawned>()
            .filter { groupId == null || it.groupId == groupId }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Substrate Browser", style = MaterialTheme.typography.titleMedium)
                        if (groupId != null) {
                            Text(
                                "Group: $groupId",
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
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search substrate...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                } else null,
                singleLine = true
            )

            // Tab row
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(label) }
                    )
                }
            }

            // Tab content
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> KBDocsTab(kbEvents, searchQuery)
                    1 -> KGFactsTab(events, groupId, searchQuery)
                    2 -> ToolsTab(events, groupId, searchQuery)
                    3 -> DeadEndsTab(events, groupId, searchQuery)
                    4 -> LegacyTab(events, groupId, searchQuery)
                }
            }
        }
    }
}

@Composable
private fun KBDocsTab(
    promotedEvents: List<EvoKammerEvent.StagingPromoted>,
    query: String
) {
    val filtered = remember(promotedEvents, query) {
        promotedEvents.filter {
            query.isBlank() || it.submissionId.contains(query, ignoreCase = true) ||
                    it.groupId.contains(query, ignoreCase = true)
        }
    }

    if (filtered.isEmpty()) {
        EmptySubstrateState("No KB documents yet")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered) { event ->
                SubstrateDocCard(
                    title = "Promoted Submission",
                    subtitle = "Group: ${event.groupId}  •  ID: ${event.submissionId.take(8)}…",
                    tag = "PROMOTED",
                    tagColor = androidx.compose.ui.graphics.Color(0xFF2E7D32)
                )
            }
        }
    }
}

@Composable
private fun KGFactsTab(
    events: List<EvoKammerEvent>,
    groupId: String?,
    query: String
) {
    val facts = remember(events) {
        events.filterIsInstance<EvoKammerEvent.KGFactEmitted>()
            .filter { groupId == null || it.groupId == groupId }
    }
    val filtered = remember(facts, query) {
        facts.filter { query.isBlank() || it.subject.contains(query, ignoreCase = true) ||
                it.relation.contains(query, ignoreCase = true) || it.`object`.contains(query, ignoreCase = true) }
    }

    if (filtered.isEmpty()) {
        EmptySubstrateState("No KG facts emitted yet")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered) { fact ->
                KGFactCard(fact)
            }
        }
    }
}

@Composable
private fun ToolsTab(
    events: List<EvoKammerEvent>,
    groupId: String?,
    query: String
) {
    val toolEvents = remember(events) {
        events.filterIsInstance<EvoKammerEvent.ToolRegistered>()
            .filter { groupId == null || it.groupId == groupId }
    }
    val filtered = remember(toolEvents, query) {
        toolEvents.filter { query.isBlank() || it.toolName.contains(query, ignoreCase = true) }
    }

    if (filtered.isEmpty()) {
        EmptySubstrateState("No tools registered yet")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered) { event ->
                SubstrateDocCard(
                    title = event.toolName,
                    subtitle = "Group: ${event.groupId}  •  v${event.version}  •  Author: ${event.authorAgentId.take(8)}…",
                    tag = "TOOL",
                    tagColor = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun DeadEndsTab(
    events: List<EvoKammerEvent>,
    groupId: String?,
    query: String
) {
    val deadEnds = remember(events) {
        events.filterIsInstance<EvoKammerEvent.DeadEndRecorded>()
            .filter { groupId == null || it.groupId == groupId }
    }
    val filtered = remember(deadEnds, query) {
        deadEnds.filter { query.isBlank() ||
                it.approach.contains(query, ignoreCase = true) ||
                it.reason.contains(query, ignoreCase = true) }
    }

    if (filtered.isEmpty()) {
        EmptySubstrateState("No dead ends recorded yet")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered) { event ->
                SubstrateDocCard(
                    title = "Dead End: ${event.approach.take(60)}",
                    subtitle = "Reason: ${event.reason.take(100)}  •  Agent: ${event.agentId.take(8)}…",
                    tag = "DEAD END",
                    tagColor = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun LegacyTab(
    events: List<EvoKammerEvent>,
    groupId: String?,
    query: String
) {
    val legacyEvents = remember(events) {
        events.filterIsInstance<EvoKammerEvent.LegacyProtocolExtracted>()
            .filter { groupId == null || it.groupId == groupId }
    }
    val filtered = remember(legacyEvents, query) {
        legacyEvents.filter { query.isBlank() ||
                it.role.name.contains(query, ignoreCase = true) ||
                it.groupId.contains(query, ignoreCase = true) }
    }

    if (filtered.isEmpty()) {
        EmptySubstrateState("No legacy protocols extracted yet")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered) { event ->
                SubstrateDocCard(
                    title = "Legacy Protocol — ${event.role.name}",
                    subtitle = "Group: ${event.groupId}  •  Gen ${event.fromGeneration} → expires Gen ${event.expiresAtGeneration}",
                    tag = "LEGACY",
                    tagColor = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

// ── Shared components ──────────────────────────────────────────────────────────

@Composable
private fun SubstrateDocCard(
    title: String,
    subtitle: String,
    tag: String,
    tagColor: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(tagColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = tagColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun KGFactCard(fact: EvoKammerEvent.KGFactEmitted) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = fact.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "  —${fact.relation}→  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = fact.`object`,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Group: ${fact.groupId}  •  Agent: ${fact.agentId.take(8)}…  •  " +
                            "Conf: ${(fact.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptySubstrateState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
