package com.achulkov.evomolior.util

import com.achulkov.evokammer.api.util.bundleBasePath
import com.achulkov.evokammer.api.util.toRunInputArtifactUpload
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunInputArtifactPickerTest {

    @Test
    fun `selected files are uploaded with bundle relative paths`() {
        val root = createTempDirectory("artifact-picker-test-").toFile()
        val docsDir = root.resolve("docs").apply { mkdirs() }
        val notesDir = root.resolve("notes").apply { mkdirs() }
        val contextFile = docsDir.resolve("context.txt").apply { writeText("alpha context") }
        val guideFile = notesDir.resolve("guide.md").apply { writeText("beta guide") }

        val bundleBase = listOf(contextFile, guideFile).bundleBasePath()
        val contextUpload = contextFile.toRunInputArtifactUpload(bundleBase)
        val guideUpload = guideFile.toRunInputArtifactUpload(bundleBase)

        assertTrue(bundleBase != null)
        assertEquals("docs/context.txt", contextUpload.relativePath)
        assertEquals("notes/guide.md", guideUpload.relativePath)
    }
}
