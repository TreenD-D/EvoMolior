package com.achulkov.evomolior.evokammer.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.achulkov.evokammer.api.model.RunInputArtifactUpload
import com.achulkov.evomolior.util.pickRunInputArtifacts

/**
 * Shared run-start context upload section used by both run setup screens.
 *
 * The selected files are converted into [RunInputArtifactUpload] objects and passed to the
 * run config so the engine can materialize them as runtime context at startup.
 */
@Composable
internal fun RunContextArtifactsSection(
    artifacts: List<RunInputArtifactUpload>,
    onArtifactsChanged: (List<RunInputArtifactUpload>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Run Context Files",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            "Upload text, code, or reference files to seed the run's context. Text files are also indexed into the KB at startup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TextButton(
            onClick = {
                val picked = pickRunInputArtifacts()
                if (picked.isNotEmpty()) {
                    onArtifactsChanged(artifacts + picked)
                }
            }
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Add Context Files")
        }

        if (artifacts.isEmpty()) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "No context files uploaded.",
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                artifacts.forEachIndexed { index, artifact ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.fillMaxWidth(0.85f)) {
                                Text(
                                    artifact.sourceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    artifact.relativePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${artifact.kind.name.lowercase()} • ${artifact.mimeType}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onArtifactsChanged(artifacts.toMutableList().also { it.removeAt(index) }) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove context file")
                            }
                        }
                    }
                }

                TextButton(onClick = { onArtifactsChanged(emptyList()) }) {
                    Text("Clear All")
                }
            }
        }
    }
}
