package com.achulkov.evomolior.util

import com.achulkov.evomolior.api.model.RunInputArtifactUpload
import com.achulkov.evomolior.api.util.bundleBasePath
import com.achulkov.evomolior.api.util.toRunInputArtifactUpload
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser

/**
 * Opens a native file picker and converts the selected files into run-start context uploads.
 */
fun pickRunInputArtifacts(): List<RunInputArtifactUpload> {
    val chooser = JFileChooser().apply {
        isMultiSelectionEnabled = true
        fileSelectionMode = JFileChooser.FILES_ONLY
        dialogTitle = "Add Run Context Files"
    }

    val result = chooser.showOpenDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return emptyList()

    val selectedFiles: List<File> = chooser.selectedFiles.toList().takeIf { it.isNotEmpty() }
        ?: chooser.selectedFile?.let { listOf(it) }
        ?: emptyList()

    val bundleBasePath = selectedFiles.bundleBasePath()
    return selectedFiles.map { it.toRunInputArtifactUpload(bundleBasePath) }
}
