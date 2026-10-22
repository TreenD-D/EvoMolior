package com.achulkov.evomolior.api.util

import com.achulkov.evomolior.core.contract.RunInputArtifactKind
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunInputArtifactFilesTest {

    @Test
    fun `extensionless executable shebang script is text with unchanged bytes`() {
        val root = createTempDirectory("artifact-script-test-").toFile()
        try {
            val bytes = "#!/usr/bin/env sh\n# UTF-8 café\nexec java \"\$@\"\n".toByteArray()
            val file = root.resolve("generated-evomolior").apply {
                writeBytes(bytes)
                assertTrue(setExecutable(true))
            }

            val upload = file.toRunInputArtifactUpload()

            assertEquals(RunInputArtifactKind.TEXT, upload.kind)
            assertEquals("text/plain", upload.mimeType)
            assertEquals("#!/usr/bin/env sh # UTF-8 café exec java \"\$@\"", upload.textPreview)
            assertContentEquals(bytes, Base64.getDecoder().decode(upload.contentBase64))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `shell extensions identify valid UTF-8 text even without a shebang`() {
        val root = createTempDirectory("artifact-shell-test-").toFile()
        try {
            for (extension in listOf("sh", "bash", "zsh", "ksh", "dash", "fish", "SH")) {
                val upload = root.resolve("script.$extension").apply {
                    writeText("echo café\n")
                }.toRunInputArtifactUpload()

                assertEquals(RunInputArtifactKind.TEXT, upload.kind, extension)
                assertEquals("text/plain", upload.mimeType, extension)
                assertEquals("echo café", upload.textPreview, extension)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `binary bytes are not promoted to script text`() {
        val root = createTempDirectory("artifact-binary-test-").toFile()
        try {
            val header = "#!/bin/sh\n".toByteArray()
            val binaries = listOf(
                byteArrayOf(0, 1, 2),
                byteArrayOf(0xc3.toByte(), 0x28),
                header + byteArrayOf(0),
                header + byteArrayOf(0xc3.toByte(), 0x28),
                header + "x".repeat(1024).toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)
            )
            for (name in listOf("generated-evomolior", "data.bin", "script.sh")) {
                for (bytes in binaries) {
                    val upload = root.resolve(name).apply { writeBytes(bytes) }.toRunInputArtifactUpload()

                    assertEquals(RunInputArtifactKind.BINARY, upload.kind, name)
                    assertTrue(upload.mimeType.startsWith("application/"), name)
                    assertNull(upload.textPreview, name)
                    assertContentEquals(bytes, Base64.getDecoder().decode(upload.contentBase64))
                }
            }
            for (text in listOf("ordinary UTF-8 text", "#!\n", "#!relative/interpreter\n", "#!/\n")) {
                val upload = root.resolve("extensionless").apply { writeText(text) }.toRunInputArtifactUpload()
                assertEquals(RunInputArtifactKind.BINARY, upload.kind, text)
                assertNull(upload.textPreview, text)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `image and PDF classifications are preserved even for shebang-like contents`() {
        val root = createTempDirectory("artifact-media-test-").toFile()
        try {
            for ((extension, mime) in mapOf(
                "png" to "image/png",
                "jpg" to "image/jpeg",
                "gif" to "image/gif",
                "webp" to "image/webp",
                "pdf" to "application/pdf"
            )) {
                val bytes = "#!/bin/sh\necho not a script upload\n".toByteArray()
                val upload = root.resolve("document.$extension").apply {
                    writeBytes(bytes)
                }.toRunInputArtifactUpload()

                assertEquals(RunInputArtifactKind.IMAGE, upload.kind, extension)
                assertEquals(mime, upload.mimeType, extension)
                assertNull(upload.textPreview, extension)
                assertContentEquals(bytes, Base64.getDecoder().decode(upload.contentBase64))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `script previews retain six line and 512 character limits`() {
        val root = createTempDirectory("artifact-preview-test-").toFile()
        try {
            val file = root.resolve("launcher")
            file.writeText("#!/bin/sh\n\n  one  \n two\nthree\nfour\nfive\nsix\n")
            assertEquals("#!/bin/sh one two three four five", file.toRunInputArtifactUpload().textPreview)

            file.writeText("#!/bin/sh\n" + "x".repeat(600))
            assertEquals(512, file.toRunInputArtifactUpload().textPreview?.length)
        } finally {
            root.deleteRecursively()
        }
    }

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
