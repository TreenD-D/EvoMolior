package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.contract.RunInputArtifactKind
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportSourceInputsTest {
    @Test
    fun `verified UTF8 source preserves shell bytes and ignores derived preview`() = runTest {
        withRoot { root ->
            val source = "trap 'rm -rf \"\$SNAPSHOT_DIR\"' EXIT\nexec \"\$JAVACMD\" \"\$@\"\n`echo x`\n\n"
            val input = input(root, "launcher", source.toByteArray())
            val loaded = ReportSourceInputs.load(listOf(input.copy(textPreview = "corrupt preview")), root.toString()).single()
            assertEquals(source, loaded.text)
            assertNull(loaded.unavailableReason)
            assertEquals(source, Files.readString(root.resolve("launcher")))
        }
    }

    @Test
    fun `hash mismatch size mismatch and malformed UTF8 are unavailable`() = runTest {
        withRoot { root ->
            val original = input(root, "source", "safe".toByteArray())
            val invalidUtf8 = input(root, "invalid", byteArrayOf(0xC3.toByte(), 0x28))
            val cases = listOf(
                original.copy(sha256 = "0".repeat(64)),
                original.copy(sizeBytes = original.sizeBytes + 1),
                invalidUtf8
            )
            val loaded = ReportSourceInputs.load(cases, root.toString())
            assertTrue(loaded.all { it.text == null && it.unavailableReason != null })
            assertTrue(loaded[0].unavailableReason!!.contains("SHA-256"))
            assertTrue(loaded[1].unavailableReason!!.contains("size"))
            assertTrue(loaded[2].unavailableReason!!.contains("UTF-8"))
        }
    }

    @Test
    fun `traversal absolute paths and symlink files and directories cannot provide source authority`() = runTest {
        withRoot { root ->
            val original = input(root, "source", "private".toByteArray())
            Files.createSymbolicLink(root.resolve("link"), root.resolve("source"))
            Files.createDirectories(root.resolve("directory"))
            Files.writeString(root.resolve("directory/source"), "private")
            Files.createSymbolicLink(root.resolve("linked-directory"), root.resolve("directory"))
            val cases = listOf(
                original.copy(relativePath = "../outside"),
                original.copy(relativePath = root.resolve("source").toString()),
                original.copy(relativePath = "link"),
                original.copy(relativePath = "linked-directory/source")
            )
            assertTrue(ReportSourceInputs.load(cases, root.toString()).all { it.text == null })
        }
    }

    @Test
    fun `missing mount and oversized metadata do not use previews`() = runTest {
        withRoot { root ->
            val original = input(root, "source", "safe".toByteArray()).copy(textPreview = "preview")
            assertNull(ReportSourceInputs.load(listOf(original), null).single().text)
            val oversized = original.copy(sizeBytes = 2L * 1024 * 1024 + 1)
            val loaded = ReportSourceInputs.load(listOf(oversized), root.toString()).single()
            assertNull(loaded.text)
            assertTrue(loaded.unavailableReason!!.contains("2 MiB"))
        }
    }

    @Test
    fun `actual oversized source cannot evade bounds through forged small metadata`() = runTest {
        withRoot { root ->
            val original = input(root, "large", ByteArray(2 * 1024 * 1024 + 1) { 65 })
            val loaded = ReportSourceInputs.load(listOf(original.copy(sizeBytes = 1)), root.toString()).single()
            assertNull(loaded.text)
            assertTrue(loaded.unavailableReason!!.contains("bounded"))
        }
    }

    @Test
    fun `combined verified source text is limited to sixteen MiB`() = runTest {
        withRoot { root ->
            val bytes = ByteArray(2 * 1024 * 1024) { 65 }
            val inputs = (1..9).map { input(root, "source-$it", bytes) }
            val loaded = ReportSourceInputs.load(inputs, root.toString())
            assertEquals(8, loaded.count { it.text != null })
            assertTrue(loaded.last().unavailableReason!!.contains("16 MiB"))
        }
    }

    private fun input(root: Path, name: String, bytes: ByteArray): RunInputArtifact {
        Files.write(root.resolve(name), bytes)
        return RunInputArtifact(
            relativePath = name,
            sourceName = name,
            mimeType = "text/plain",
            sizeBytes = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            kind = RunInputArtifactKind.TEXT
        )
    }

    private suspend fun withRoot(block: suspend (Path) -> Unit) {
        val root = createTempDirectory("report-source-inputs-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
