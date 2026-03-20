package com.achulkov.evomolior.evokammer.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.achulkov.evokammer.core.model.GradingResult
import com.achulkov.evokammer.core.model.RoundScore
import com.achulkov.evomolior.evokammer.LocalEvoKammerNavigator
import com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModel

/**
 * Post-Run Analysis screen — displays final scores, cross-group KB comparison,
 * meta-archive contents, and the civilisational winner summary.
 */
@Composable
fun EvoKammerPostRunAnalysisScreen(viewModel: EvoKammerViewModel) {
    val navigator = LocalEvoKammerNavigator.current
    val gradingHistory by viewModel.gradingHistory.collectAsState()
    val engineState by viewModel.engineState.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Final Scores", "Round History", "Group Comparison", "Meta-Archive")

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navigator(com.achulkov.evomolior.evokammer.EvoKammerRoute.Dashboard) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Post-Run Analysis",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.weight(1f))
            engineState.activeRunId?.let { runId ->
                Text(
                    "Run: ${runId.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Tab row
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { idx, title ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(title, fontSize = 13.sp) }
                )
            }
        }

        // Content
        when (selectedTab) {
            0 -> FinalScoresTab(gradingHistory)
            1 -> RoundHistoryTab(gradingHistory)
            2 -> GroupComparisonTab(gradingHistory)
            3 -> MetaArchiveTab()
        }
    }
}

// ── Final Scores tab ────────────────────────────────────────────────────────

@Composable
private fun FinalScoresTab(gradingHistory: List<GradingResult>) {
    if (gradingHistory.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "No completed rounds yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // Aggregate total weighted scores across all rounds
    val groupTotals = mutableMapOf<String, Double>()
    gradingHistory.forEach { result ->
        result.scores.forEach { score ->
            groupTotals[score.groupId] = (groupTotals[score.groupId] ?: 0.0) + score.weightedTotal
        }
    }
    val rankedGroups = groupTotals.entries.sortedByDescending { it.value }
    val overallWinner = gradingHistory.lastOrNull()?.winnerId

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Winner banner
        overallWinner?.let { winner ->
            item {
                WinnerBanner(winnerId = winner)
            }
        }

        // Aggregate leaderboard
        item {
            Text(
                "Aggregate Leaderboard (all rounds)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        items(rankedGroups.toList()) { (groupId, totalScore) ->
            val rank = rankedGroups.indexOfFirst { it.key == groupId } + 1
            LeaderboardRow(
                rank = rank,
                groupId = groupId,
                totalScore = totalScore,
                isWinner = groupId == overallWinner
            )
        }

        // Per-round best scores
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Per-Round Winners",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        items(gradingHistory) { result ->
            RoundWinnerChip(result)
        }
    }
}

@Composable
private fun WinnerBanner(winnerId: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Civilisational Winner",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    winnerId,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun LeaderboardRow(rank: Int, groupId: String, totalScore: Double, isWinner: Boolean) {
    val medalColors = listOf(
        Color(0xFFFFD700), Color(0xFFC0C0C0), Color(0xFFCD7F32)
    )
    val medalColor = if (rank <= 3) medalColors[rank - 1] else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isWinner)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "#$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = medalColor,
                modifier = Modifier.width(36.dp)
            )
            Text(
                groupId,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "%.1f pts".format(totalScore),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun RoundWinnerChip(result: GradingResult) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SuggestionChip(
            onClick = {},
            label = {
                Text(
                    "Round ${result.roundIndex + 1}: ${result.winnerId}",
                    style = MaterialTheme.typography.labelSmall
                )
            },
            icon = {
                Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        )
    }
}

// ── Round History tab ────────────────────────────────────────────────────────

@Composable
private fun RoundHistoryTab(gradingHistory: List<GradingResult>) {
    if (gradingHistory.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No rounds completed yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(gradingHistory) { result ->
            RoundResultCard(result)
        }
    }
}

@Composable
private fun RoundResultCard(result: GradingResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Round ${result.roundIndex + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                SuggestionChip(
                    onClick = {},
                    label = { Text("Winner: ${result.winnerId}", style = MaterialTheme.typography.labelSmall) }
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            result.scores.sortedBy { it.rank }.forEach { score ->
                GroupScoreRow(score)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun GroupScoreRow(score: RoundScore) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "#${score.rank}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
        Text(
            score.groupId,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            "%.1f".format(score.weightedTotal),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ── Group Comparison tab ─────────────────────────────────────────────────────

@Composable
private fun GroupComparisonTab(gradingHistory: List<GradingResult>) {
    if (gradingHistory.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No completed rounds yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // Collect all groups
    val allGroups = gradingHistory.flatMap { it.scores.map { s -> s.groupId } }.distinct().sorted()
    // Collect all dimensions
    val allDimensions = gradingHistory.flatMap { it.scores.flatMap { s -> s.dimensionScores.map { d -> d.dimensionId } } }.distinct().sorted()

    // Aggregate per group per dimension
    data class DimAvg(val dimensionId: String, val avg: Double)
    val groupStats: Map<String, List<DimAvg>> = allGroups.associateWith { groupId ->
        allDimensions.map { dimId ->
            val scores = gradingHistory.flatMap { r ->
                r.scores.filter { it.groupId == groupId }
                    .flatMap { s -> s.dimensionScores.filter { d -> d.dimensionId == dimId } }
            }
            val avg = if (scores.isEmpty()) 0.0 else scores.map { it.rawScore }.average()
            DimAvg(dimId, avg)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Dimension Averages per Group",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        items(allGroups) { groupId ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        groupId,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    groupStats[groupId]?.forEach { dimAvg ->
                        DimensionBar(
                            dimensionId = dimAvg.dimensionId,
                            avg = dimAvg.avg
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DimensionBar(dimensionId: String, avg: Double) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                dimensionId.replace("_", " ").replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "%.1f".format(avg),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        LinearProgressIndicator(
            progress = { (avg / 100.0).coerceIn(0.0, 1.0).toFloat() },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

// ── Meta-Archive tab ─────────────────────────────────────────────────────────

@Composable
private fun MetaArchiveTab() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Meta-Archive",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "The meta-archive stores winning KB snapshots, Dead Ends, StartingFields, and GradingSheets " +
                    "from completed runs — enabling future Genesis Councils to learn from past civilisations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val archiveCategories = listOf(
            Triple(Icons.Default.Star, "Winning KB Snapshots", "Compressed snapshots of winning group knowledge bases"),
            Triple(Icons.Default.Warning, "Dead Ends Registry", "Approaches proven unproductive — shared as negative priors"),
            Triple(Icons.Default.Settings, "StartingFields", "Genesis Council outputs used for each run"),
            Triple(Icons.Default.CheckCircle, "GradingSheets", "Sealed evaluation rubrics from Grader civilisations"),
            Triple(Icons.Default.Share, "Legacy Protocols", "Top-fitness agent reasoning postures for epigenetic injection"),
        )

        items(archiveCategories) { (icon, title, desc) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    // Placeholder badge showing "0 entries" until real meta-archive is wired
                    SuggestionChip(
                        onClick = {},
                        label = { Text("0 entries", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Meta-archive persistence is stored in the run database and exported as a " +
                            "compressed JSON bundle on run completion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
