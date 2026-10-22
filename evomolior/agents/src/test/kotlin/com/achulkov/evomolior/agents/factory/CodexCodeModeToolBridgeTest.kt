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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodexCodeModeToolBridgeTest {
    @Test
    fun `argv transport preserves literal shell source and exact tool response`() = runBlocking {
        assertSourceRoundTrip(SourceTransport.ARGV)
    }

    @Test
    fun `stdin transport preserves literal shell source and exact tool response`() = runBlocking {
        assertSourceRoundTrip(SourceTransport.STDIN)
    }

    @Test
    fun `documented quoted heredoc preserves literal shell source and exact tool response`() = runBlocking {
        assertSourceRoundTrip(SourceTransport.QUOTED_HEREDOC)
    }

    @Test
    fun `instructions explain source safe stdin invocation`() {
        CodexCodeModeSession.create(runtime(mutableListOf())).use { session ->
            val instructions = session.instructions()
            assertTrue(instructions.contains("<tool-name> - <<'EVOMOLIOR_JSON'"))
            assertTrue(instructions.contains("Always JSON-serialize"))
            assertTrue(instructions.contains("never use an unquoted heredoc"))
            assertTrue(instructions.contains("serialize that outer command string too"))
        }
    }

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
    fun `runtime rejects unknown tools and malformed typed arguments`() = runBlocking<Unit> {
        val runtime = runtime(mutableListOf())

        assertFailsWith<Throwable> {
            runtime.execute("missing_tool", "{}")
        }
        assertFailsWith<Throwable> {
            runtime.execute("echo_probe", """{"unexpected":"value"}""")
        }
    }

    @Test
    fun `bridged call rejected by constitutional gate never executes tool`() = runBlocking {
        var executed = false
        val registry = ToolRegistry {
            tool(EchoProbeTool { executed = true })
        }
        val runtime = CodexCodeModeToolRuntime(
            toolRegistry = registry,
            actionGate = ConstitutionalActionGate { error("blocked by probe") },
            agentId = "agent-code-mode"
        )
        assertFailsWith<IllegalStateException> {
            runtime.execute("echo_probe", """{"value":"must not execute"}""")
        }
        assertFalse(executed)
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

    private enum class SourceTransport { ARGV, STDIN, QUOTED_HEREDOC }

    private suspend fun assertSourceRoundTrip(transport: SourceTransport) {
        val source = """
            #!/bin/sh
            trap 'rm -rf "${'$'}SNAPSHOT_DIR"' EXIT HUP INT TERM
            exec "${'$'}JAVACMD" "${'$'}@"
            literal='single quotes'; double="double quotes"; slash='\\'
            substitutions=${'$'}(echo SHOULD_NOT_EXPAND) `echo SHOULD_NOT_EXPAND`
            unicode='é λ'
        """.trimIndent() + "\n\n"
        val rawArgs = buildJsonObject { put("value", source) }.toString()
        val observedActions = mutableListOf<AgentAction>()
        var received: String? = null
        val runtime = CodexCodeModeToolRuntime(
            toolRegistry = ToolRegistry { tool(EchoProbeTool { received = it }) },
            actionGate = ConstitutionalActionGate { observedActions += it },
            agentId = "agent-code-mode"
        )
        CodexCodeModeSession.create(runtime).use { session ->
            val command = when (transport) {
                SourceTransport.ARGV -> listOf(session.bridgeCommand.toString(), "echo_probe", rawArgs)
                SourceTransport.STDIN -> listOf(session.bridgeCommand.toString(), "echo_probe", "-")
                SourceTransport.QUOTED_HEREDOC -> {
                    val quotedBridge = "'" + session.bridgeCommand.toString().replace("'", "'\\''") + "'"
                    listOf("/bin/zsh", "-c", "$quotedBridge echo_probe - <<'EVOMOLIOR_JSON'\n$rawArgs\nEVOMOLIOR_JSON\n")
                }
            }
            val process = ProcessBuilder(command).redirectErrorStream(true).apply {
                environment()["SNAPSHOT_DIR"] = "SHOULD_NOT_EXPAND"
                environment()["JAVACMD"] = "SHOULD_NOT_EXPAND"
            }.start()
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use {
                    if (transport == SourceTransport.STDIN) it.write(rawArgs)
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (process.isAlive && System.nanoTime() < deadline) {
                    session.processPendingRequests()
                    delay(10)
                }
                assertTrue(process.waitFor(2, TimeUnit.SECONDS), "Bridge helper did not complete")
                val response = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
                assertEquals(0, process.exitValue(), response)
                assertEquals(source, received, "Decoded tool argument must preserve source exactly")
                assertEquals(1, observedActions.size)
                assertEquals(runtime.execute("echo_probe", rawArgs), response, "Filesystem response must be byte-faithful")
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
            }
        }
    }

    private class EchoProbeTool(
        private val onExecute: (String) -> Unit = {}
    ) : SimpleTool<EchoProbeTool.Args>(
        argsType = typeToken<Args>(),
        name = "echo_probe",
        description = "Returns a typed bridge probe result."
    ) {
        @Serializable
        data class Args(val value: String)

        override suspend fun execute(args: Args): String {
            onExecute(args.value)
            return "TOOL_RESULT:${args.value}"
        }
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
