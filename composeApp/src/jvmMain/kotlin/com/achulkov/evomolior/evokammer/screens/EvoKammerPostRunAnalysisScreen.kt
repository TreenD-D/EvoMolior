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
import com.achulkov.evokammer.api.service.MetaArchiveEntryRecord
import com.achulkov.evokammer.api.service.MetaArchiveSummary
import com.achulkov.evokammer.core.model.GradingResult
import com.achulkov.evokammer.core.model.RoundScore
import com.achulkov.evomolior.evokammer.LocalEvoKammerNavigator
import com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Post-Run Analysis screen — displays final scores, cross-group KB comparison,
 * meta-archive contents, and the civilisational winner summary.
 */
@Composable
fun EvoKammerPostRunAnalysisScreen(
    viewModel: EvoKammerViewModel,
    runId: String? = null
) {
    val navigator = LocalEvoKammerNavigator.current
    val gradingHistory by viewModel.gradingHistory.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val metaArchiveSummary by viewModel.metaArchiveSummary.collectAsState()
    val roundSummaryEntries by viewModel.roundSummaryEntries.collectAsState()
    val runCompletionEntries by viewModel.runCompletionEntries.collectAsState()

    LaunchedEffect(runId) {
        if (runId != null) {
            viewModel.loadArchivedRun(runId)
        }
    }

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Final Scores", "Round History", "Group Comparison", "Round Summaries", "Meta-Archive")
    val effectiveHistory = remember(runId, gradingHistory, roundSummaryEntries) {
        if (runId != null) buildArchivedGradingHistory(roundSummaryEntries) else gradingHistory
    }

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
            0 -> FinalScoresTab(effectiveHistory, roundSummaryEntries, runCompletionEntries)
            1 -> RoundHistoryTab(effectiveHistory)
            2 -> GroupComparisonTab(effectiveHistory)
            3 -> RoundSummariesTab(roundSummaryEntries)
            4 -> MetaArchiveTab(summary = metaArchiveSummary)
        }
    }
}

private val roundSummaryJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Serializable
internal data class FinalAnswerArchiveRecord(
    val runId: String,
    val winnerId: String? = null,
    val finalAnswerSummary: String = "",
    val judgeOutputsByGrader: Map<String, String> = emptyMap(),
    val submissionSummariesByGroup: Map<String, String?> = emptyMap()
)

@Serializable
private data class RoundSummaryArchiveRecord(
    val runId: String,
    val roundIndex: Int,
    val roundWinnerId: String? = null,
    val judgeOutputsByGrader: Map<String, String> = emptyMap(),
    val groupSummaries: List<RoundGroupSummaryArchiveRecord> = emptyList()
)

@Serializable
private data class RoundGroupSummaryArchiveRecord(
    val groupId: String,
    val criticPromoted: Int = 0,
    val criticRejected: Int = 0,
    val criticArbitrated: Int = 0,
    val fitnessScoredAgents: Int = 0,
    val fitnessTopEligibleAgents: Int = 0,
    val legacyProtocolsExtracted: Int = 0,
    val legacyProtocolsExpired: Int = 0,
    val deadEndsInserted: Int = 0,
    val submissionSummary: String? = null,
    val deterministicScore: DeterministicAssessmentArchiveRecord = DeterministicAssessmentArchiveRecord(),
    val disclosureCheckin: DisclosureCheckinArchiveRecord = DisclosureCheckinArchiveRecord(),
    val blindScore: BlindScoreArchiveRecord = BlindScoreArchiveRecord()
)

@Serializable
private data class DeterministicAssessmentArchiveRecord(
    val overallScore: Double = 0.0,
    val automatedTestsPassed: Int = 0,
    val automatedTestsFailed: Int = 0,
    val mandatoryFailure: Boolean = false,
    val gradingSheetApplied: String = "",
    val failedTestIds: List<String> = emptyList(),
    val dimensionScores: Map<String, Double> = emptyMap()
)

@Serializable
private data class DisclosureCheckinArchiveRecord(
    val overallScore: Double = 0.0,
    val passed: Boolean = false,
    val testPassRate: Double = 0.0,
    val failedTestCount: Int = 0,
    val gradingSheetApplied: String = "",
    val stageAdvanced: Boolean = false,
    val dimensionScores: Map<String, Double> = emptyMap()
)

@Serializable
private data class BlindScoreArchiveRecord(
    val weightedTotal: Double = 0.0,
    val rank: Int = 0,
    val dimensionScores: Map<String, Double> = emptyMap()
)

internal data class FinalAnswerCardState(
    val winnerId: String,
    val finalAnswerSummary: String,
    val judgeOutputsByGrader: Map<String, String>,
    val submissionSummariesByGroup: Map<String, String?>
)

internal fun buildFinalAnswerCardState(
    roundSummaryEntries: List<MetaArchiveEntryRecord>,
    runCompletionEntries: List<MetaArchiveEntryRecord>
): FinalAnswerCardState? {
    val latestCompletion = runCompletionEntries
        .asReversed()
        .firstOrNull()
        ?.let { entry ->
            runCatching {
                roundSummaryJson.decodeFromString(FinalAnswerArchiveRecord.serializer(), entry.contentJson)
            }.getOrNull()
        }

    val latestRoundSummary = roundSummaryEntries
        .asReversed()
        .firstOrNull()
        ?.let { entry ->
            runCatching {
                roundSummaryJson.decodeFromString(RoundSummaryArchiveRecord.serializer(), entry.contentJson)
            }.getOrNull()
        }

    return when {
        latestCompletion != null -> FinalAnswerCardState(
            winnerId = latestCompletion.winnerId ?: "unknown",
            finalAnswerSummary = latestCompletion.finalAnswerSummary,
            judgeOutputsByGrader = latestCompletion.judgeOutputsByGrader,
            submissionSummariesByGroup = latestCompletion.submissionSummariesByGroup
        )
        latestRoundSummary != null -> FinalAnswerCardState(
            winnerId = latestRoundSummary.roundWinnerId ?: "unknown",
            finalAnswerSummary = "",
            judgeOutputsByGrader = latestRoundSummary.judgeOutputsByGrader,
            submissionSummariesByGroup = latestRoundSummary.groupSummaries
                .associate { it.groupId to it.submissionSummary }
        )
        else -> null
    }
}

internal fun buildArchivedGradingHistory(entries: List<MetaArchiveEntryRecord>): List<GradingResult> {
    return entries.mapNotNull { entry ->
        runCatching {
            roundSummaryJson.decodeFromString(RoundSummaryArchiveRecord.serializer(), entry.contentJson)
        }.getOrNull()
    }.sortedBy { it.roundIndex }.map { summary ->
        GradingResult(
            roundIndex = summary.roundIndex,
            scores = summary.groupSummaries.map { group ->
                RoundScore(
                    groupId = group.groupId,
                    roundIndex = summary.roundIndex,
                    dimensionScores = group.blindScore.dimensionScores.map { (dimensionId, rawScore) ->
                        com.achulkov.evokammer.core.model.DimensionScore(
                            dimensionId = dimensionId,
                            rawScore = rawScore,
                            justification = ""
                        )
                    },
                    weightedTotal = group.blindScore.weightedTotal,
                    rank = group.blindScore.rank
                )
            }.sortedBy { it.rank },
            winnerId = summary.roundWinnerId ?: summary.groupSummaries
                .minByOrNull { it.blindScore.rank }
                ?.groupId
                ?: "unknown"
        )
    }
}

// ── Final Scores tab ────────────────────────────────────────────────────────

@Composable
private fun FinalScoresTab(
    gradingHistory: List<GradingResult>,
    roundSummaryEntries: List<MetaArchiveEntryRecord>,
    runCompletionEntries: List<MetaArchiveEntryRecord>
) {
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

    val finalAnswerCardState = buildFinalAnswerCardState(roundSummaryEntries, runCompletionEntries)
    val latestRoundSummary = roundSummaryEntries
        .asReversed()
        .firstOrNull()
        ?.let { entry ->
            runCatching {
                roundSummaryJson.decodeFromString(RoundSummaryArchiveRecord.serializer(), entry.contentJson)
            }.getOrNull()
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
        finalAnswerCardState?.let { completion ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Final Answer",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Winner: ${completion.winnerId}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (completion.finalAnswerSummary.isNotBlank()) {
                            Text(
                                completion.finalAnswerSummary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (completion.judgeOutputsByGrader.isNotEmpty()) {
                            completion.judgeOutputsByGrader.forEach { (graderId, output) ->
                                Text(
                                    graderId,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(output, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (completion.submissionSummariesByGroup.isNotEmpty()) {
                            completion.submissionSummariesByGroup.forEach { (groupId, summary) ->
                                summary?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        "Submission summary: $groupId",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        latestRoundSummary?.let { summary ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Round ${summary.roundIndex + 1} recap",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (summary.judgeOutputsByGrader.isEmpty()) {
                            Text(
                                "No judge output was archived for the last round.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        summary.groupSummaries.firstOrNull { it.submissionSummary != null }?.submissionSummary?.let { submissionSummary ->
                            Text(
                                "Submission summary",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(submissionSummary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

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
private fun MetaArchiveTab(summary: MetaArchiveSummary?) {
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
            if (summary != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Total archived entries: ${summary.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Map each archive category to its DB entryType key and icon
        data class ArchiveCategory(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val desc: String, val typeKey: String)
        val archiveCategories = listOf(
            ArchiveCategory(Icons.Default.Star, "Winning KB Snapshots", "Compressed snapshots of winning group knowledge bases", "WINNING_KB"),
            ArchiveCategory(Icons.Default.Warning, "Dead Ends Registry", "Approaches proven unproductive — shared as negative priors", "DEAD_ENDS"),
            ArchiveCategory(Icons.Default.Settings, "StartingFields", "Genesis Council outputs used for each run", "STARTING_FIELD"),
            ArchiveCategory(Icons.Default.CheckCircle, "GradingSheets", "Sealed evaluation rubrics from Grader civilisations", "GRADING_SHEET"),
            ArchiveCategory(Icons.Default.Share, "Legacy Protocols", "Top-fitness agent reasoning postures for epigenetic injection", "LEGACY_PROTOCOL"),
            ArchiveCategory(Icons.Default.List, "Round Summaries", "Per-group end-of-round submission summaries", "ROUND_SUMMARY"),
        )

        items(archiveCategories) { category ->
            val count = summary?.byType?.get(category.typeKey)
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
                        category.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(category.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(category.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                if (summary == null) "—" else "${count ?: 0} entries",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
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

// ── Round Summaries tab ─────────────────────────────────────────────────────

@Composable
private fun RoundSummariesTab(entries: List<MetaArchiveEntryRecord>) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No round summaries archived yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Round Summaries",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "Each round now archives a concise, checkable summary for every group so the round-end answer is accessible outside the live logs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(entries) { entry ->
            val summary = runCatching {
                roundSummaryJson.decodeFromString(RoundSummaryArchiveRecord.serializer(), entry.contentJson)
            }.getOrNull()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append("group=")
                            append(entry.groupId ?: "round")
                            append(" round=")
                            append(entry.generation + 1)
                            if (entry.finalScore != null) {
                                append(" score=")
                                append("%.1f".format(entry.finalScore))
                            }
                            if (entry.isFromWinner) {
                                append(" winner")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (summary != null) {
                        summary.roundWinnerId?.let { winner ->
                            Text(
                                "Winner: $winner",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (summary.judgeOutputsByGrader.isNotEmpty()) {
                            Text(
                                "Judge output",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            summary.judgeOutputsByGrader.forEach { (graderId, output) ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(graderId, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                        Text(output, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        if (summary.groupSummaries.isNotEmpty()) {
                            Text(
                                "Per-group submission summary",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            summary.groupSummaries.forEach { group ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        buildString {
                                            append(group.groupId)
                                            append(": critic ")
                                            append(group.criticPromoted)
                                            append("/")
                                            append(group.criticRejected)
                                            append("/")
                                            append(group.criticArbitrated)
                                            append(", fitness ")
                                            append(group.fitnessScoredAgents)
                                            append("/")
                                            append(group.fitnessTopEligibleAgents)
                                            append(", deadEnds=")
                                            append(group.deadEndsInserted)
                                            append(", tests=")
                                            append(group.deterministicScore.automatedTestsPassed)
                                            append("/")
                                            append(group.deterministicScore.automatedTestsFailed)
                                            if (group.deterministicScore.mandatoryFailure) {
                                                append(" mandatory-fail")
                                            }
                                            append(", blind=")
                                            append("%.1f".format(group.blindScore.weightedTotal))
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    group.submissionSummary?.let { submissionSummary ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                            )
                                        ) {
                                            Text(
                                                submissionSummary,
                                                modifier = Modifier.padding(10.dp),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            entry.contentJson.replace(Regex("\\s+"), " ").take(320),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
