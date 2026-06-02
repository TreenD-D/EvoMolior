package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.model.RunInputArtifactUpload
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvoKammerRunServiceArtifactAllowlistTest {
    @Test
    fun `materializeRunInputArtifacts extracts hosts from raw file bytes before preview truncation`() = runTest {
        val root = createTempDirectory("artifact-allowlist-test-").toFile()
        val service = EvoKammerRunService(
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
        val artifact = artifacts.single() as com.achulkov.evokammer.core.contract.RunInputArtifact
        assertFalse(
            artifact.textPreview.orEmpty().contains("late.example.com"),
            "The compact preview should not need to carry the late URL once the raw bytes have been scanned"
        )
    }
}
