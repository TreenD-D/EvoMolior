package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunInputArtifactUpload
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvoMoliorRunServiceArtifactAllowlistTest {
    @Test
    fun `materializeRunInputArtifacts extracts hosts from raw file bytes before preview truncation`() = runTest {
        val root = createTempDirectory("artifact-allowlist-test-").toFile()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(root),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val longContext = buildString {
            appendLine("intro")
            repeat(250) { index ->
                appendLine("padding line $index")
            }
            appendLine("See https://late.example.com/final-reference for the actual source.")
        }

        val uploads = listOf(
            RunInputArtifactUpload(
                relativePath = "docs/long-context.txt",
                sourceName = "long-context.txt",
                mimeType = "text/plain",
                contentBase64 = Base64.getEncoder().encodeToString(longContext.toByteArray())
            )
        )

        val method = service::class.java.getDeclaredMethod(
            "materializeRunInputArtifacts",
            String::class.java,
            List::class.java
        ).apply { isAccessible = true }

        val materialized = method.invoke(service, "run-allowlist", uploads) as Any
        val artifactsProperty = materialized::class.java.getDeclaredField("artifacts").apply { isAccessible = true }
        val allowedHostsProperty = materialized::class.java.getDeclaredField("allowedHosts").apply { isAccessible = true }

        val artifacts = artifactsProperty.get(materialized) as List<*>
        val allowedHosts = allowedHostsProperty.get(materialized) as Set<*>

        assertEquals(1, artifacts.size)
        assertTrue(allowedHosts.contains("late.example.com"))
        val artifact = artifacts.single() as com.achulkov.evomolior.core.contract.RunInputArtifact
        assertFalse(
            artifact.textPreview.orEmpty().contains("late.example.com"),
            "The compact preview should not need to carry the late URL once the raw bytes have been scanned"
        )
    }

    @Test
    fun `run start allowlist combines artifact prompt and subject inferred hosts`() = runTest {
        val artifactHosts = setOf("artifact.example.com")
        val mergedArtifacts = listOf(
            RunInputArtifact(
                relativePath = "brief.md",
                sourceName = "brief.md",
                mimeType = "text/markdown",
                sizeBytes = 42,
                sha256 = "sha",
                kind = com.achulkov.evomolior.core.contract.RunInputArtifactKind.TEXT,
                textPreview = "Read https://preview.example.org/guide"
            )
        )
        val objective = """
            Use Qwen DashScope sources for the provider integration guide.
            Direct source: https://reference.example.com/resources
        """.trimIndent()

        val hosts = com.achulkov.evomolior.sandbox.network.SourceAccessPolicy.initialAllowedHosts(
            objective = objective + "\n" + mergedArtifacts.joinToString("\n") { it.textPreview.orEmpty() },
            artifactHosts = artifactHosts
        )

        assertTrue("artifact.example.com" in hosts)
        assertTrue("preview.example.org" in hosts)
        assertTrue("reference.example.com" in hosts)
        assertTrue("dashscope.aliyuncs.com" in hosts)
        assertTrue("help.aliyun.com" in hosts)
    }
}
