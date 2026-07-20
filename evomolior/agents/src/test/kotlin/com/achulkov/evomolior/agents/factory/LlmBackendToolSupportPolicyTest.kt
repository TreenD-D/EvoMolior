package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.config.LlmBackend
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmBackendToolSupportPolicyTest {
    @Test
    fun `qwen cloud enables tool support by default`() {
        val previous = System.getProperty(LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY)
        try {
            System.clearProperty(LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY)

            val support = LlmBackendToolSupportPolicy.resolve(LlmBackend.QWEN_CLOUD)

            assertTrue(support.toolsEnabled)
            assertFalse(support.textOnlyCompatibilityMode)
        } finally {
            if (previous == null) {
                System.clearProperty(LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY)
            } else {
                System.setProperty(LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY, previous)
            }
        }
    }

    @Test
    fun `qwen cloud can be forced into text only compatibility`() {
        val previous = System.getProperty(LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY)
        try {
            System.setProperty(LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY, "false")

            val support = LlmBackendToolSupportPolicy.resolve(LlmBackend.QWEN_CLOUD)

            assertFalse(support.toolsEnabled)
            assertTrue(support.textOnlyCompatibilityMode)
        } finally {
            if (previous == null) {
                System.clearProperty(LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY)
            } else {
                System.setProperty(LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY, previous)
            }
        }
    }

    @Test
    fun `codex chatgpt uses bridged code mode tools`() {
        val support = LlmBackendToolSupportPolicy.resolve(
            backend = LlmBackend.CODEX_CHATGPT,
            qwenToolSupportEnabled = true
        )

        assertTrue(support.toolsEnabled)
        assertFalse(support.textOnlyCompatibilityMode)
        assertTrue(support.codeModeEnabled)
    }
}
