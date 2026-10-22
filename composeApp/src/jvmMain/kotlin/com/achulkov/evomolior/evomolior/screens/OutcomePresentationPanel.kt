package com.achulkov.evomolior.evomolior.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.achulkov.evomolior.api.service.RunOutcomePresentation
import com.achulkov.evomolior.api.service.MechanismActivationState
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableSemanticBlock
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.core.metrics.MetricAvailability
import com.achulkov.evomolior.core.metrics.RunMetricObservation
import com.achulkov.evomolior.generated.resources.Res
import com.achulkov.evomolior.generated.resources.outcome_artifacts
import com.achulkov.evomolior.generated.resources.outcome_acceptance_status
import com.achulkov.evomolior.generated.resources.outcome_artifact_validation
import com.achulkov.evomolior.generated.resources.outcome_authority
import com.achulkov.evomolior.generated.resources.outcome_available
import com.achulkov.evomolior.generated.resources.outcome_canonical_metrics
import com.achulkov.evomolior.generated.resources.outcome_checklist_complete
import com.achulkov.evomolior.generated.resources.outcome_checklist_pending
import com.achulkov.evomolior.generated.resources.outcome_component
import com.achulkov.evomolior.generated.resources.outcome_components
import com.achulkov.evomolior.generated.resources.outcome_decision
import com.achulkov.evomolior.generated.resources.outcome_evidence
import com.achulkov.evomolior.generated.resources.outcome_evidence_count
import com.achulkov.evomolior.generated.resources.outcome_evidence_label
import com.achulkov.evomolior.generated.resources.outcome_execution_status
import com.achulkov.evomolior.generated.resources.outcome_manifest_hash
import com.achulkov.evomolior.generated.resources.outcome_mechanism_activated_no_use
import com.achulkov.evomolior.generated.resources.outcome_mechanism_activated_with_use
import com.achulkov.evomolior.generated.resources.outcome_mechanism_activation
import com.achulkov.evomolior.generated.resources.outcome_mechanism_attempted_failed
import com.achulkov.evomolior.generated.resources.outcome_mechanism_configured_not_reached
import com.achulkov.evomolior.generated.resources.outcome_mechanism_not_configured
import com.achulkov.evomolior.generated.resources.outcome_metrics_unavailable
import com.achulkov.evomolior.generated.resources.outcome_missing_authority_count
import com.achulkov.evomolior.generated.resources.outcome_no_decision_package
import com.achulkov.evomolior.generated.resources.outcome_no_metrics
import com.achulkov.evomolior.generated.resources.outcome_not_applicable
import com.achulkov.evomolior.generated.resources.outcome_overview
import com.achulkov.evomolior.generated.resources.outcome_package
import com.achulkov.evomolior.generated.resources.outcome_package_hash
import com.achulkov.evomolior.generated.resources.outcome_primary_artifact
import com.achulkov.evomolior.generated.resources.outcome_recovery_sources
import com.achulkov.evomolior.generated.resources.outcome_run
import com.achulkov.evomolior.generated.resources.outcome_startup_diagnostic
import com.achulkov.evomolior.generated.resources.outcome_status
import com.achulkov.evomolior.generated.resources.outcome_status_accepted
import com.achulkov.evomolior.generated.resources.outcome_status_provisional
import com.achulkov.evomolior.generated.resources.outcome_status_startup_diagnostic
import com.achulkov.evomolior.generated.resources.outcome_terminal_authority
import com.achulkov.evomolior.generated.resources.outcome_value
import com.achulkov.evomolior.generated.resources.outcome_withheld
import org.jetbrains.compose.resources.stringResource

/**
 * Compact desktop presentation of a verified terminal run outcome.
 *
 * @param outcome Verified operator-facing outcome projection to render.
 * @param modifier Modifier applied to the panel container.
 */
@Composable
fun OutcomePresentationPanel(
    outcome: RunOutcomePresentation,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable(outcome.runId, outcome.manifestHash) { mutableIntStateOf(0) }
    val tabLabels = listOf(
        stringResource(Res.string.outcome_overview),
        stringResource(Res.string.outcome_package),
        stringResource(Res.string.outcome_canonical_metrics),
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.outcome_run, outcome.runId),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.outcome_status, outcomeStatusLabel(outcome.outcomeClass)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = outcomeStatusLabel(outcome.outcomeClass),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabLabels.forEachIndexed { index, label ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(label, maxLines = 1) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        when (selectedTab) {
            0 -> OutcomeOverview(outcome = outcome, modifier = Modifier.weight(1f))
            1 -> OutcomeDecisionPackage(outcome = outcome, modifier = Modifier.weight(1f))
            else -> OutcomeMetrics(outcome = outcome, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun OutcomeOverview(
    outcome: RunOutcomePresentation,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutcomeSection(title = stringResource(Res.string.outcome_manifest_hash)) {
                OutcomeMonospaceValue(outcome.manifestHash)
            }
        }
        item {
            OutcomeSection(title = stringResource(Res.string.outcome_terminal_authority)) {
                OutcomeKeyValue(
                    stringResource(Res.string.outcome_execution_status),
                    outcome.executionStatus?.name
                        ?: stringResource(Res.string.outcome_withheld),
                )
                OutcomeKeyValue(
                    stringResource(Res.string.outcome_acceptance_status),
                    outcome.acceptanceStatus?.name
                        ?: stringResource(Res.string.outcome_withheld),
                )
                OutcomeKeyValue(
                    stringResource(Res.string.outcome_artifact_validation),
                    outcome.primaryArtifactValidationStatus?.name
                        ?: stringResource(Res.string.outcome_withheld),
                )
                outcome.primaryArtifactPath?.let { path ->
                    OutcomeKeyValue(
                        stringResource(Res.string.outcome_primary_artifact),
                        path,
                    )
                } ?: OutcomeKeyValue(
                    stringResource(Res.string.outcome_primary_artifact),
                    stringResource(Res.string.outcome_withheld),
                )
            }
        }
        item {
            OutcomeSection(title = stringResource(Res.string.outcome_artifacts)) {
                outcome.artifactPaths.forEach { path -> OutcomeMonospaceValue(path) }
            }
        }
        if (outcome.recoverySourceLabels.isNotEmpty()) {
            item {
                OutcomeSection(title = stringResource(Res.string.outcome_recovery_sources)) {
                    outcome.recoverySourceLabels.forEach { (source, label) ->
                        OutcomeKeyValue(source, label)
                    }
                }
            }
        }
        outcome.startupDiagnostic?.let { diagnostic ->
            item {
                OutcomeSection(
                    title = stringResource(Res.string.outcome_startup_diagnostic),
                ) {
                    OutcomeKeyValue(
                        stringResource(Res.string.outcome_authority),
                        diagnostic.packageHash,
                    )
                    diagnostic.issues.forEach { issue ->
                        HorizontalDivider()
                        Text(issue.code, style = MaterialTheme.typography.labelLarge)
                        Text(issue.publicMessage, style = MaterialTheme.typography.bodySmall)
                        Text(
                            issue.remediation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        outcome.mechanismActivationReport?.let { report ->
            item {
                OutcomeSection(
                    title = stringResource(Res.string.outcome_mechanism_activation),
                ) {
                    report.mechanisms.forEach { mechanism ->
                        OutcomeKeyValue(
                            mechanism.mechanismId,
                            mechanismActivationStateLabel(mechanism.state),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutcomeDecisionPackage(
    outcome: RunOutcomePresentation,
    modifier: Modifier,
) {
    val decisionPackage = outcome.decisionPackage
    if (decisionPackage == null) {
        OutcomeEmptyState(
            modifier = modifier,
            text = stringResource(Res.string.outcome_no_decision_package),
        )
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutcomeSection(title = stringResource(Res.string.outcome_decision)) {
                OutcomeKeyValue(
                    stringResource(Res.string.outcome_package_hash),
                    decisionPackage.packageHash,
                )
                OutcomeKeyValue(
                    stringResource(Res.string.outcome_components),
                    decisionPackage.components.size.toString(),
                )
                OutcomeKeyValue(
                    stringResource(Res.string.outcome_evidence_count),
                    decisionPackage.evidenceLedger.size.toString(),
                )
                OutcomeKeyValue(
                    stringResource(Res.string.outcome_missing_authority_count),
                    decisionPackage.missingAuthority.size.toString(),
                )
                Text(decisionPackage.audience, style = MaterialTheme.typography.bodySmall)
                Text(
                    decisionPackage.decisionOrUse,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(decisionPackage.components, key = { it.componentId }) { component ->
            OutcomeSection(
                title = stringResource(
                    Res.string.outcome_component,
                    component.componentId,
                    component.title,
                ),
            ) {
                OutcomeKeyValue(
                    stringResource(Res.string.outcome_status),
                    authorityStatusLabel(component.authorityClass),
                )
                OutcomeKeyValue(
                    stringResource(Res.string.outcome_authority),
                    component.contentHash,
                )
                OutcomeKeyValue(
                    stringResource(Res.string.outcome_evidence_label),
                    component.provenance.evidenceIds.joinToString(),
                )
                component.uncertainty?.let { uncertainty ->
                    Text(
                        uncertainty,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                component.blocks.forEach { block ->
                    SemanticBlockContent(block)
                }
            }
        }
    }
}

@Composable
private fun SemanticBlockContent(block: DeliverableSemanticBlock) {
    when (block) {
        is DeliverableSemanticBlock.Narrative ->
            Text(block.text, style = MaterialTheme.typography.bodySmall)

        is DeliverableSemanticBlock.Table -> {
            Text(
                block.columns.joinToString(separator = " | "),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            block.rows.forEach { row ->
                Text(
                    row.joinToString(separator = " | "),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        is DeliverableSemanticBlock.KeyValue ->
            block.entries.forEach { entry ->
                OutcomeKeyValue(label = entry.key, value = entry.value)
            }

        is DeliverableSemanticBlock.Checklist ->
            block.items.forEach { item ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (item.completed) {
                                    stringResource(Res.string.outcome_checklist_complete)
                                } else {
                                    stringResource(Res.string.outcome_checklist_pending)
                                },
                            )
                        },
                    )
                    Text(item.text, style = MaterialTheme.typography.bodySmall)
                }
            }
    }
}

@Composable
private fun OutcomeMetrics(
    outcome: RunOutcomePresentation,
    modifier: Modifier,
) {
    val metrics = outcome.metrics
    if (metrics == null) {
        OutcomeEmptyState(
            modifier = modifier,
            text = stringResource(Res.string.outcome_no_metrics),
        )
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(metrics.observations, key = { it.metricId }) { observation ->
            MetricRow(observation = observation)
        }
        if (metrics.closureObservations.isNotEmpty()) {
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            items(metrics.closureObservations, key = { it.metricId }) { observation ->
                MetricRow(observation = observation)
            }
        }
    }
}

@Composable
private fun MetricRow(observation: RunMetricObservation) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    observation.metricId,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = metricStatusLabel(observation.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            observation.value?.let { value ->
                Text(
                    text = stringResource(
                        Res.string.outcome_value,
                        value.toString(),
                        observation.unit.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            observation.unavailableReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    Res.string.outcome_evidence,
                    observation.evidenceRefs.joinToString(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutcomeSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun OutcomeKeyValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.width(132.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OutcomeMonospaceValue(value: String) {
    Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun OutcomeEmptyState(modifier: Modifier, text: String) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun outcomeStatusLabel(outcomeClass: RunOutcomeClass): String = when (outcomeClass) {
    RunOutcomeClass.ACCEPTED_DECISION_PACKAGE ->
        stringResource(Res.string.outcome_status_accepted)
    RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE ->
        stringResource(Res.string.outcome_status_provisional)
    RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE ->
        stringResource(Res.string.outcome_status_startup_diagnostic)
}

@Composable
private fun metricStatusLabel(status: MetricAvailability): String = when (status) {
    MetricAvailability.AVAILABLE -> stringResource(Res.string.outcome_available)
    MetricAvailability.UNAVAILABLE -> stringResource(Res.string.outcome_metrics_unavailable)
    MetricAvailability.NOT_APPLICABLE -> stringResource(Res.string.outcome_not_applicable)
}

@Composable
private fun authorityStatusLabel(authorityClass: ComponentAuthorityClass): String =
    when (authorityClass) {
        ComponentAuthorityClass.ACCEPTED ->
            stringResource(Res.string.outcome_status_accepted)
        ComponentAuthorityClass.PROVISIONAL ->
            stringResource(Res.string.outcome_status_provisional)
        ComponentAuthorityClass.WITHHELD ->
            stringResource(Res.string.outcome_withheld)
    }

@Composable
private fun mechanismActivationStateLabel(state: MechanismActivationState): String = when (state) {
    MechanismActivationState.NOT_CONFIGURED ->
        stringResource(Res.string.outcome_mechanism_not_configured)
    MechanismActivationState.CONFIGURED_NOT_REACHED ->
        stringResource(Res.string.outcome_mechanism_configured_not_reached)
    MechanismActivationState.ATTEMPTED_FAILED ->
        stringResource(Res.string.outcome_mechanism_attempted_failed)
    MechanismActivationState.ACTIVATED_NO_DOWNSTREAM_USE ->
        stringResource(Res.string.outcome_mechanism_activated_no_use)
    MechanismActivationState.ACTIVATED_WITH_DOWNSTREAM_USE ->
        stringResource(Res.string.outcome_mechanism_activated_with_use)
}
