package com.achulkov.evomolior.evomolior.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.achulkov.evomolior.evomolior.substrate.RunInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CONTINUATION_DATE_FMT = SimpleDateFormat("MMM d, HH:mm", Locale.ROOT)

/**
 * Dropdown selector for choosing a previously completed run as the baseline for a new launch.
 *
 * @param availableRuns Runs discovered on disk.
 * @param selectedRunId The currently selected continuation source run, or null for a fresh run.
 * @param onSelectedRunIdChanged Callback invoked when the user picks a different source run.
 * @param runFilter Optional predicate used to restrict which runs can be continued from.
 * @param helperText Optional explanatory text displayed under the selector.
 */
@Composable
internal fun ContinuationRunSelectorSection(
    availableRuns: List<RunInfo>,
    selectedRunId: String?,
    onSelectedRunIdChanged: (String?) -> Unit,
    modifier: Modifier = Modifier,
    runFilter: (RunInfo) -> Boolean = { it.isCompleted },
    helperText: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val selectableRuns = remember(availableRuns, runFilter) {
        availableRuns.filter(runFilter)
    }
    val selectedRun = remember(selectedRunId, selectableRuns) {
        selectableRuns.firstOrNull { it.runId == selectedRunId }
    }

    LaunchedEffect(selectedRunId, selectableRuns) {
        if (selectedRunId != null && selectedRun == null) {
            onSelectedRunIdChanged(null)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Continue From", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = selectedRun?.let {
                        "${it.runId.take(18)}… (${it.groupIds.size} groups)"
                    } ?: "Start fresh",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Start fresh") },
                    onClick = {
                        onSelectedRunIdChanged(null)
                        expanded = false
                    },
                    trailingIcon = if (selectedRunId == null) {
                        { Text("✓", color = MaterialTheme.colorScheme.primary) }
                    } else null
                )
                if (selectableRuns.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No eligible completed runs found", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { expanded = false },
                        enabled = false
                    )
                } else {
                    selectableRuns.forEach { run ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        run.runId,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${run.groupIds.size} groups  •  ${CONTINUATION_DATE_FMT.format(Date(run.lastModifiedMs))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onSelectedRunIdChanged(run.runId)
                                expanded = false
                            },
                            trailingIcon = if (selectedRunId == run.runId) {
                                { Text("✓", color = MaterialTheme.colorScheme.primary) }
                            } else null
                        )
                    }
                }
            }
        }

        helperText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()
    }
}
