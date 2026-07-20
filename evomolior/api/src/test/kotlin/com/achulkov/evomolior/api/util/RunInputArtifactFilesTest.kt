package com.achulkov.evomolior.api.util

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunInputArtifactFilesTest {

    @Test
    fun `loads a directory tree into stable relative artifact paths`() {
        val root = createTempDirectory("artifact-loader-test-").toFile()
        val docs = root.resolve("docs").apply { mkdirs() }
        val nested = docs.resolve("nested").apply { mkdirs() }
        root.resolve("top.txt").writeText("top level")
        docs.resolve("guide.md").writeText("guide")
        nested.resolve("notes.txt").writeText("notes")

        val uploads = loadRunInputArtifacts(root.toPath())

        assertEquals(3, uploads.size)
        assertEquals("docs/guide.md", uploads[0].relativePath)
        assertEquals("docs/nested/notes.txt", uploads[1].relativePath)
        assertEquals("top.txt", uploads[2].relativePath)
        assertTrue(uploads.all { it.contentBase64.isNotBlank() })
    }
}
