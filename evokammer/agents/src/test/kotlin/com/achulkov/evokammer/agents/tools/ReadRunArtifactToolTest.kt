package com.achulkov.evokammer.agents.tools

import com.achulkov.evokammer.core.contract.RunInputArtifact
import com.achulkov.evokammer.core.contract.RunInputArtifactKind
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import java.security.MessageDigest

class ReadRunArtifactToolTest {
    @Test
    fun `lists and reads uploaded run artifacts`() = runTest {
        val root = createTempDirectory("run-artifacts-test-").toFile()
        val artifactFile = File(root, "docs/brief.txt")
        artifactFile.parentFile?.mkdirs()
        artifactFile.writeText("Hello run artifacts")

        val metadata = RunInputArtifact(
            relativePath = "docs/brief.txt",
            sourceName = "brief.txt",
            mimeType = "text/plain",
            sizeBytes = artifactFile.length(),
            sha256 = sha256Hex(artifactFile.readBytes()),
            kind = RunInputArtifactKind.TEXT,
            textPreview = "Hello run artifacts"
        )

        val tool = ReadRunArtifactTool(
            artifactsMountPath = root.absolutePath,
            inputArtifacts = listOf(metadata)
        )

        val listing = tool.execute(ReadRunArtifactTool.Args(listOnly = true))
        assertTrue(listing.contains("docs/brief.txt"))
        assertTrue(listing.contains("Hello run artifacts"))

        val content = tool.execute(ReadRunArtifactTool.Args(relativePath = "docs/brief.txt"))
        assertTrue(content.contains("Hello run artifacts"))
    }

    @Test
    fun `empty run-artifact registry returns an explicit no-artifacts message`() = runTest {
        val tool = ReadRunArtifactTool(
            artifactsMountPath = null,
            inputArtifacts = emptyList()
        )

        val listing = tool.execute(ReadRunArtifactTool.Args(listOnly = true))
        assertEquals("No uploaded run-start artifacts are available.", listing)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
