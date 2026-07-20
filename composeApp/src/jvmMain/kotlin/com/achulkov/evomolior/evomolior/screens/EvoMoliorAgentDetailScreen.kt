package com.achulkov.evomolior.evomolior.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.evomolior.EvoMoliorRoute
import com.achulkov.evomolior.evomolior.LocalEvoMoliorNavigator
import com.achulkov.evomolior.evomolior.viewmodel.EvoMoliorViewModel
import com.achulkov.evomolior.evomolior.viewmodel.StubEvoMoliorEngineService

/**
 * Agent Detail Screen — deep-dive into a single agent's activity.
 *
 * Shows:
 * - Agent metadata (ID, role, group)
 * - All messages and tool calls emitted by this agent
 * - Budget events attributed to this agent's group
 * - Constitution violation events for this group
 */
@Composable
fun EvoMoliorAgentDetailScreen(
    agentId: String,
    groupId: String,
    role: Role,
    viewModel: EvoMoliorViewModel = remember { EvoMoliorViewModel(StubEvoMoliorEngineService()) }
) {
    val navigate = LocalEvoMoliorNavigator.current
    val events by viewModel.recentEvents.collectAsState()

    // Filter events for this specific agent
    val agentMessages = remember(events, agentId) {
        events.filterIsInstance<EvoMoliorEvent.AgentMessage>()
            .filter { it.agentId == agentId }
    }

    val toolCalls = remember(agentMessages) {
        agentMessages.filter { it.isToolCall }
    }

    val thoughts = remember(agentMessages) {
        agentMessages.filter { !it.isToolCall }
    }

    val groupBudgetEvents = remember(events, groupId) {
        events.filterIsInstance<EvoMoliorEvent.GroupBudgetChanged>()
            .filter { it.groupId == groupId }
    }

    val violationEvents = remember(events, groupId) {
        events.filterIsInstance<EvoMoliorEvent.ConstitutionalViolation>()
            .filter { it.groupId == groupId }
    }

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        "Messages (${thoughts.size})",
        "Tool Calls (${toolCalls.size})",
        "Budget (${groupBudgetEvents.size})",
        "Violations (${violationEvents.size})"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = { navigate(EvoMoliorRoute.Dashboard) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text(
                    "Agent: ${agentId.take(12)}…",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoleBadge(role)
                    Text(
                        "Group: $groupId",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider()

        // ── Stats Row ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard(label = "Messages", value = "${thoughts.size}")
            StatCard(label = "Tool Calls", value = "${toolCalls.size}")
            StatCard(label = "Budget Events", value = "${groupBudgetEvents.size}")
            StatCard(label = "Violations", value = "${violationEvents.size}")
        }

        // ── Tab Row ───────────────────────────────────────────────────────────
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, style = MaterialTheme.typography.bodySmall) }
                )
            }
        }

        // ── Tab Content ───────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            when (selectedTab) {
                0 -> MessageList(thoughts)
                1 -> MessageList(toolCalls, isToolTab = true)
                2 -> BudgetEventList(groupBudgetEvents)
                3 -> ViolationList(violationEvents)
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<EvoMoliorEvent.AgentMessage>,
    isToolTab: Boolean = false
) {
    if (messages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (isToolTab) "No tool calls recorded." else "No messages recorded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(messages) { msg ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (msg.isToolCall) "🔧 Tool Call" else "💬 Message",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            msg.role.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        msg.content,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetEventList(events: List<EvoMoliorEvent.GroupBudgetChanged>) {
    if (events.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No budget events recorded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(events) { evt ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("💰", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Budget → ${String.format("%.2f", evt.remainingBudget)} (group: ${evt.groupId})",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                )
            }
        }
    }
}

@Composable
private fun ViolationList(events: List<EvoMoliorEvent.ConstitutionalViolation>) {
    if (events.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No constitutional violations — this group is compliant.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(events) { evt ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Law: ${evt.lawId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        evt.description,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleBadge(role: Role) {
    val color = when {
        role == Role.Critic || role == Role.Auditor -> MaterialTheme.colorScheme.error
        role == Role.Outlier -> MaterialTheme.colorScheme.outline
        role == Role.Inspector || role == Role.MetaPlanner -> MaterialTheme.colorScheme.tertiary
        role == Role.Archivist || role == Role.Educator -> MaterialTheme.colorScheme.secondary
        role.isCouncilRole -> MaterialTheme.colorScheme.tertiary
        role.isGraderRole -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            role.name,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    ElevatedCard(modifier = Modifier.width(100.dp)) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
