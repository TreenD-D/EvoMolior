package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodexCodeModeToolBridgeTest {
    @Test
    fun `runtime decodes typed arguments executes tool and intercepts call`() = runBlocking {
        val observedActions = mutableListOf<AgentAction>()
        val runtime = runtime(observedActions)

        val result = runtime.execute("echo_probe", """{"value":"bridge-ok"}""")

        assertTrue(result.contains("TOOL_RESULT:bridge-ok"))
        assertEquals(
            listOf(
                AgentAction(
                    type = ActionEventType.TOOL_CALL,
                    agentId = "agent-code-mode",
                    detail = "echo_probe"
                )
            ),
            observedActions
        )
    }

    @Test
    fun `runtime rejects unknown tools and malformed typed arguments`() = runBlocking {
        val runtime = runtime(mutableListOf())

        assertFailsWith<Throwable> {
            runtime.execute("missing_tool", "{}")
        }
        assertFailsWith<Throwable> {
            runtime.execute("echo_probe", """{"unexpected":"value"}""")
        }
    }

    @Test
    fun `filesystem helper invokes real runtime and workspace is removed on close`() = runBlocking {
        val observedActions = mutableListOf<AgentAction>()
        val session = CodexCodeModeSession.create(runtime(observedActions))
        val workspace = session.workspace
        try {
            val process = ProcessBuilder(
                session.bridgeCommand.toString(),
                "echo_probe",
                """{"value":"filesystem-ok"}"""
            ).redirectErrorStream(true).start()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (process.isAlive && System.nanoTime() < deadline) {
                session.processPendingRequests()
                delay(10)
            }
            session.processPendingRequests()

            assertTrue(process.waitFor(2, TimeUnit.SECONDS), "Bridge helper did not complete")
            assertEquals(0, process.exitValue())
            assertTrue(process.inputStream.bufferedReader().readText().contains("TOOL_RESULT:filesystem-ok"))
            assertEquals(1, observedActions.size)
        } finally {
            session.close()
        }
        assertFalse(workspace.toFile().exists())
    }

    @Test
    fun `filesystem helper receives bounded timeout for a hung tool`() = runBlocking {
        val registry = ToolRegistry { tool(HangingProbeTool()) }
        val runtime = CodexCodeModeToolRuntime(
            toolRegistry = registry,
            actionGate = ConstitutionalActionGate.NONE,
            agentId = "agent-code-mode"
        )
        val session = CodexCodeModeSession.create(runtime, toolRequestTimeoutMillis = 50)
        try {
            val process = ProcessBuilder(
                session.bridgeCommand.toString(),
                "hanging_probe",
                "{}"
            ).redirectErrorStream(true).start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (process.isAlive && System.nanoTime() < deadline) {
                session.processPendingRequests()
                delay(10)
            }

            assertTrue(process.waitFor(2, TimeUnit.SECONDS), "Bridge helper did not receive timeout")
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(output.contains("timed out after 50ms"), output)
        } finally {
            session.close()
        }
    }

    private fun runtime(observedActions: MutableList<AgentAction>): CodexCodeModeToolRuntime {
        val registry = ToolRegistry {
            tool(EchoProbeTool())
        }
        return CodexCodeModeToolRuntime(
            toolRegistry = registry,
            actionGate = ConstitutionalActionGate { action -> observedActions += action },
            agentId = "agent-code-mode"
        )
    }

    private class EchoProbeTool : SimpleTool<EchoProbeTool.Args>(
        argsType = typeToken<Args>(),
        name = "echo_probe",
        description = "Returns a typed bridge probe result."
    ) {
        @Serializable
        data class Args(val value: String)

        override suspend fun execute(args: Args): String = "TOOL_RESULT:${args.value}"
    }

    private class HangingProbeTool : SimpleTool<HangingProbeTool.Args>(
        argsType = typeToken<Args>(),
        name = "hanging_probe",
        description = "Never returns."
    ) {
        @Serializable
        class Args

        override suspend fun execute(args: Args): String = awaitCancellation()
    }
}
