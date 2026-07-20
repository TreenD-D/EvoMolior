package com.achulkov.evomolior.core.config

import com.achulkov.evomolior.core.model.Role
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ModelSelectionConfigTest {
    @Test
    fun `default runtime assignments are sampled from the configured role pools`() {
        val defaults = ModelSelectionConfig.defaultRuntimeAssignments(Random(1))
        val pools = ModelSelectionConfig.defaultRuntimeModelPools()

        assertTrue(defaults.isNotEmpty())
        assertTrue(defaults.values.none(ModelSelectionConfig::isExplicitOptInModel))
        assertEquals(
            listOf(
                "qwen/qwen3-next-80b-a3b-instruct",
                "deepseek/deepseek-v3.2",
                "deepseek/deepseek-v4-flash"
            ),
            pools[Role.Planner.name]
        )
        assertEquals(
            listOf(
                "deepseek/deepseek-v4-flash",
                "deepseek/deepseek-v3.2",
                "openai/gpt-oss-20b"
            ),
            pools[Role.Researcher.name]
        )
        assertEquals(
            listOf(
                "deepseek/deepseek-v4-flash",
                "deepseek/deepseek-v4-flash",
                "openai/gpt-oss-20b"
            ),
            pools[Role.Outlier.name]
        )
        assertTrue(defaults[Role.Researcher.name] in pools[Role.Researcher.name]!!)
        assertTrue(defaults[Role.Builder.name] in pools[Role.Builder.name]!!)
        assertTrue(defaults[Role.Critic.name] in pools[Role.Critic.name]!!)
        assertTrue(defaults[Role.Archivist.name] in pools[Role.Archivist.name]!!)
        assertTrue(defaults[Role.Outlier.name] in pools[Role.Outlier.name]!!)
        assertTrue(defaults[Role.PerspectiveTaker.name] in pools[Role.PerspectiveTaker.name]!!)
        assertTrue(defaults[Role.MetaPlanner.name] in pools[Role.MetaPlanner.name]!!)
    }

    @Test
    fun `explicit opt-in models are marked as such`() {
        assertFalse(ModelSelectionConfig.isExplicitOptInModel("anthropic/claude-sonnet-4.6"))
        assertTrue(ModelSelectionConfig.isExplicitOptInModel("openai/gpt-5"))
        assertFalse(ModelSelectionConfig.isExplicitOptInModel("openai/gpt-oss-120b"))
        assertFalse(ModelSelectionConfig.isExplicitOptInModel("openai/gpt-5-mini"))
    }

    @Test
    fun `runtime defaults use the configured council and utility models`() {
        assertEquals("x-ai/grok-4.3", ModelSelectionConfig.DEFAULT_COUNCIL_MODEL)
        assertEquals("deepseek/deepseek-v4-flash", ModelSelectionConfig.DEFAULT_GRADER_MODEL)
        assertEquals("openai/gpt-oss-20b", ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL)
        assertEquals("openai/gpt-oss-20b", ModelSelectionConfig.DEFAULT_LEAK_TRANSFORM_MODEL)
        assertEquals("deepseek/deepseek-v4-flash", ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL)
        assertTrue(ModelSelectionConfig.runtimeModelPoolFor(Role.Researcher).contains("deepseek/deepseek-v4-flash"))
        assertTrue(ModelSelectionConfig.runtimeModelPoolFor(Role.Planner).contains("deepseek/deepseek-v3.2"))
        assertTrue(ModelSelectionConfig.runtimeModelPoolFor(Role.PerspectiveTaker).contains("deepseek/deepseek-v3.2"))
        assertTrue(ModelSelectionConfig.runtimeModelPoolFor(Role.MetaPlanner).contains("deepseek/deepseek-v4-flash"))
        assertTrue(ModelSelectionConfig.defaultRuntimeModelPools().values.none { pool ->
            pool.contains("anthropic/claude-sonnet-4.6") ||
                pool.contains("x-ai/grok-4-fast") ||
                pool.contains("nvidia/nemotron-3-nano-30b-a3b") ||
                pool.contains("mistralai/mistral-small-creative")
        })
    }

    @Test
    fun `fast runtime defaults avoid live-run tool-call incompatible models`() {
        val assignments = FastModelSelectionConfig.fastRuntimeAssignments()

        assertEquals("openai/gpt-oss-120b", assignments[Role.Builder.name])
        assertEquals("deepseek/deepseek-v4-flash", assignments[Role.Researcher.name])
        assertEquals("deepseek/deepseek-v4-flash", assignments[Role.Planner.name])
        assertEquals("deepseek/deepseek-v4-flash", assignments[Role.Connector.name])
        assertEquals("deepseek/deepseek-v4-flash", assignments[Role.PerspectiveTaker.name])
        assertFalse(
            "qwen/qwen3.5-flash-02-23" in FastModelSelectionConfig.approvedModelIds,
            "Live fast runs showed qwen/qwen3.5-flash-02-23 produces invalid tool-call arguments through OpenRouter."
        )
        assertTrue(assignments.values.all { it in FastModelSelectionConfig.approvedModelIds })
        assertTrue(FastModelSelectionConfig.approvedModelIds.contains("openai/gpt-oss-120b"))
        assertTrue(FastModelSelectionConfig.approvedModelIds.contains("deepseek/deepseek-v4-flash"))
    }

    @Test
    fun `qwen backend parser and runtime assignments use qwen cloud model ids`() {
        assertEquals(LlmBackend.QWEN_CLOUD, LlmBackend.parse("qwen-cloud"))
        assertEquals(LlmBackend.QWEN_CLOUD, LlmBackend.parse("QWEN_CLOUD"))
        assertEquals(LlmBackend.CODEX_CHATGPT, LlmBackend.parse("codex"))
        assertEquals(LlmBackend.CODEX_CHATGPT, LlmBackend.parse("chatgpt"))
        assertEquals(LlmBackend.CODEX_CHATGPT, LlmBackend.parse("codex-chatgpt"))
        assertEquals("QWEN_API_KEY", LlmBackend.QWEN_CLOUD.apiKeyEnvVar)
        assertEquals("qwen-image-2.0-pro", ModelSelectionConfig.QWEN_CLOUD_IMAGE_MODEL)
        assertEquals("qwen3.6-flash", ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL)
        assertEquals("gpt-5.6-luna", ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL)

        val codexAssignments = ModelSelectionConfig.codexChatGptRuntimeAssignments()
        assertEquals(
            Role.entries.filterNot(Role::isCouncilRole).mapTo(mutableSetOf(), Role::name),
            codexAssignments.keys
        )
        assertTrue(codexAssignments.keys.none { roleName ->
            Role.valueOf(roleName).isCouncilRole
        })
        assertTrue(
            codexAssignments.values.all {
                it == ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
            }
        )

        val assignments = ModelSelectionConfig.qwenCloudRuntimeAssignments()
        assertEquals("qwen3.7-plus", assignments[Role.Planner.name])
        assertEquals("qwen3.7-plus", assignments[Role.Critic.name])
        assertEquals("deepseek-v4-flash", assignments[Role.Auditor.name])
        assertNotEquals(
            assignments.size,
            assignments.values.count { it == "qwen3.7-plus" },
            "Qwen society defaults must remain a Qwen-prioritized mixed Qwen/DeepSeek profile; Plus-only is a baseline/control shape."
        )
        assertTrue(assignments.values.count { it == "qwen3.7-plus" } > assignments.values.count { it == "deepseek-v4-flash" })
        assertTrue(assignments.values.all { it in setOf("qwen3.7-plus", "deepseek-v4-flash") })

        val fastAssignments = FastModelSelectionConfig.fastQwenCloudRuntimeAssignments()
        assertNotEquals(
            fastAssignments.size,
            fastAssignments.values.count { it == "qwen3.7-plus" },
            "Fast Qwen society defaults must also retain at least one DeepSeek role."
        )
        assertTrue(fastAssignments.values.count { it == "qwen3.7-plus" } > fastAssignments.values.count { it == "deepseek-v4-flash" })
        assertTrue(fastAssignments.values.all { it in setOf("qwen3.7-plus", "deepseek-v4-flash") })
    }

    @Test
    fun `codex cli resolver accepts executable candidate without live session check`() {
        val executable = kotlin.io.path.createTempFile("codex-cli-", ".sh").toFile()
            .also { it.setExecutable(true) }

        assertEquals(
            executable.absolutePath,
            LlmBackend.resolveCodexCliPath(sequenceOf(executable.absolutePath))
        )
    }

    @Test
    fun `codex cli candidates include ChatGPT desktop executable before legacy Codex app`() {
        val candidates = LlmBackend.codexCliCandidates { null }.toList()

        assertTrue(LlmBackend.CHATGPT_APP_CODEX_CLI_PATH in candidates)
        assertTrue(
            candidates.indexOf(LlmBackend.CHATGPT_APP_CODEX_CLI_PATH) <
                candidates.indexOf(LlmBackend.DEFAULT_CODEX_APP_CLI_PATH)
        )
    }
}
