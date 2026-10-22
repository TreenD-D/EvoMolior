package com.achulkov.evomolior.cli

import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.CodexRuntimePreset
import com.achulkov.evomolior.core.config.LlmBackend
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexPresetCliTest {
    private fun parse(vararg extra: String): CliOptions = assertNotNull(
        CliArguments.parse(
            arrayOf(
                "-prompt", "Produce a report.",
                "-input", createTempDirectory("preset-input-").toString(),
                "-output", createTempDirectory("preset-output-").toString(),
                "-llm-backend", "codex-chatgpt",
                "-rounds", "1",
            ) + extra,
            codexCliLocator = { "/tmp/codex" },
            codexSessionPreflight = {},
            codexReasoningEffortEnvironment = { null },
        )
    )

    @Test
    fun `no preset preserves legacy defaults`() {
        val options = parse()
        assertNull(options.codexRuntimePreset)
        assertEquals(CodexReasoningEffort.HIGH, options.codexReasoningEffort)
        assertTrue(options.modelOverrides.isEmpty())
    }

    @Test
    fun `named presets allow only bounded effort and explicit supported overrides`() {
        for (preset in CodexRuntimePreset.entries) {
            for (effort in listOf("low", "medium", "high")) {
                val options = parse("-fast", "-codex-preset", preset.id, "-codex-reasoning-effort", effort)
                assertEquals(preset, options.codexRuntimePreset)
                assertTrue(options.modelOverrides.isEmpty())
            }
        }
        val options = parse("-fast", "--codex-preset", "mixed-families", "-model", "Critic=gpt-5.6-sol")
        assertEquals(mapOf("Critic" to "gpt-5.6-sol"), options.modelOverrides)
        for (effort in listOf("xhigh", "max", "ultra", "maximum")) {
            assertFailsWith<CliUsageException> {
                parse("-codex-preset", "full-luna", "-codex-reasoning-effort", effort)
            }
        }
        assertFailsWith<CliUsageException> { parse("-codex-preset", "unknown") }
        assertFailsWith<CliUsageException> { parse("-codex-preset") }
        assertFailsWith<CliUsageException> {
            parse("-codex-preset", "full-luna", "-model", "Critic=gpt-5.6-sol")
        }
    }

    @Test
    fun `presets reject backend mismatch and mixed acceptance authority`() {
        assertFailsWith<CliUsageException> {
            parse("-codex-preset", "full-luna", "-llm-backend", "openrouter")
        }
        assertFailsWith<CliUsageException> {
            validateCodexPreset(CodexRuntimePreset.FULL_LUNA, LlmBackend.OPENROUTER, emptyMap(), AcceptanceProfile.NORMAL)
        }
        for (profile in AcceptanceProfile.entries.filter { it != AcceptanceProfile.NORMAL }) {
            assertFailsWith<CliUsageException> {
                validateCodexPreset(CodexRuntimePreset.MIXED_FAMILIES, LlmBackend.CODEX_CHATGPT, emptyMap(), profile)
            }
            validateCodexPreset(CodexRuntimePreset.FULL_LUNA, LlmBackend.CODEX_CHATGPT, emptyMap(), profile)
        }
    }

    @Test
    fun `comparisons reject preset flags before evaluating benchmark configuration`() {
        for (flag in listOf("-codex-preset", "--codex-preset")) {
            assertFailsWith<CliUsageException> {
                ComparativeBenchmarkCliArguments.parse(arrayOf(flag, "full-luna"))
            }
            assertFailsWith<CliUsageException> {
                BenchmarkCliArguments.parse(arrayOf(flag, "full-luna"))
            }
        }
    }
}
