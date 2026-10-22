package com.achulkov.evomolior.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.createTempFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexAuthCommandTest {
    @Test
    fun `explicit external Codex home controls auth file location`() {
        assertEquals(java.nio.file.Path.of("/external/codex/auth.json"),
            CodexAuthCommand.defaultAuthPath("/external/codex", "/home/user"))
        assertEquals(java.nio.file.Path.of("/home/user/.codex/auth.json"),
            CodexAuthCommand.defaultAuthPath(null, "/home/user"))
    }

    @Test
    fun `gptauth status runs login status and prints safe metadata only`() {
        val authPath = createTempFile("codex-auth-", ".json")
        authPath.writeText(
            """
            {
              "auth_mode": "chatgpt",
              "tokens": {
                "account_id": "acct-safe",
                "access_token": "secret-access-token",
                "id_token": "secret-id-token",
                "refresh_token": "secret-refresh-token"
              }
            }
            """.trimIndent()
        )
        val runner = RecordingCodexAuthProcessRunner(
            captured = CodexAuthProcessResult(
                exitCode = 0,
                stdout = "Logged in using ChatGPT\n",
                stderr = ""
            )
        )
        val out = StringBuilder()
        val codexCliPath = executableCodexPath()

        val exitCode = CodexAuthCommand.run(
            args = arrayOf("status", "--codex-cli", codexCliPath),
            processRunner = runner,
            out = out,
            authPath = authPath
        )

        assertEquals(0, exitCode)
        assertEquals(listOf(listOf(codexCliPath, "login", "status")), runner.capturedCommands)
        assertTrue(out.toString().contains("Logged in using ChatGPT"))
        assertTrue(out.toString().contains("chatgpt"))
        assertTrue(out.toString().contains("acct-safe"))
        assertFalse(out.toString().contains("secret-access-token"))
        assertFalse(out.toString().contains("secret-id-token"))
        assertFalse(out.toString().contains("secret-refresh-token"))
    }

    @Test
    fun `gptauth default checks status without starting login flow`() {
        val runner = RecordingCodexAuthProcessRunner(
            captured = CodexAuthProcessResult(0, "Logged in using ChatGPT\n", "")
        )
        val codexCliPath = executableCodexPath()

        val exitCode = CodexAuthCommand.run(
            args = arrayOf("--codex-cli", codexCliPath),
            processRunner = runner,
            out = StringBuilder(),
            authPath = createTempFile("missing-codex-auth-", ".json")
        )

        assertEquals(0, exitCode)
        assertEquals(listOf(listOf(codexCliPath, "login", "status")), runner.capturedCommands)
    }

    @Test
    fun `gptauth rejects device auth because login flow can revoke app sessions`() {
        val runner = RecordingCodexAuthProcessRunner(
            captured = CodexAuthProcessResult(0, "Logged in using ChatGPT\n", "")
        )
        val codexCliPath = executableCodexPath()

        val failure = kotlin.test.assertFailsWith<CliUsageException> {
            CodexAuthCommand.run(
                args = arrayOf("--codex-cli", codexCliPath, "--device-auth"),
                processRunner = runner,
                out = StringBuilder(),
                authPath = createTempFile("missing-codex-auth-", ".json")
            )
        }

        assertTrue(failure.message.orEmpty().contains("does not use Codex device auth"))
        assertEquals(emptyList(), runner.capturedCommands)
    }

    @Test
    fun `gptauth login uses browser oauth runner and writes codex auth file without printing tokens`() {
        val authPath = createTempFile("codex-auth-login-", ".json")
        val runner = RecordingCodexAuthProcessRunner(
            captured = CodexAuthProcessResult(0, "Logged in using ChatGPT\n", "")
        )
        val oauthRunner = RecordingOpenAIBrowserOAuthRunner(
            result = OpenAIBrowserOAuthResult(
                tokens = OpenAIOAuthTokens(
                    idToken = "secret-id-token",
                    accessToken = "secret-access-token",
                    refreshToken = "secret-refresh-token",
                    expiresAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                    accountId = "acct-safe",
                    email = "safe@example.com"
                )
            )
        )
        val out = StringBuilder()

        val exitCode = CodexAuthCommand.run(
            args = arrayOf("login", "--no-open", "--timeout-seconds", "60"),
            processRunner = runner,
            oauthRunner = oauthRunner,
            out = out,
            authPath = authPath
        )

        val authJson = authPath.readText()
        val parsedAuthJson = Json.parseToJsonElement(authJson).jsonObject
        val parsedTokens = parsedAuthJson["tokens"]!!.jsonObject
        assertEquals(0, exitCode)
        assertEquals(emptyList(), runner.capturedCommands)
        assertEquals(false, oauthRunner.capturedOptions.single().openBrowser)
        assertEquals(60, oauthRunner.capturedOptions.single().timeoutSeconds)
        assertEquals("chatgpt", parsedAuthJson["auth_mode"]!!.jsonPrimitive.content)
        assertEquals("secret-id-token", parsedTokens["id_token"]!!.jsonPrimitive.content)
        assertEquals("secret-access-token", parsedTokens["access_token"]!!.jsonPrimitive.content)
        assertEquals("secret-refresh-token", parsedTokens["refresh_token"]!!.jsonPrimitive.content)
        assertEquals("acct-safe", parsedTokens["account_id"]!!.jsonPrimitive.content)
        assertFalse(out.toString().contains("secret-id-token"))
        assertFalse(out.toString().contains("secret-access-token"))
        assertFalse(out.toString().contains("secret-refresh-token"))
        assertTrue(out.toString().contains("Codex/ChatGPT browser login complete"))
    }

    @Test
    fun `codex auth file store supports auth path without parent directory`() {
        val authPath = java.nio.file.Path.of("codex-auth-parentless-${System.nanoTime()}.json")
        try {
            CodexAuthFileStore.save(
                authPath = authPath,
                tokens = OpenAIOAuthTokens(
                    idToken = "id-parentless",
                    accessToken = "access-parentless",
                    refreshToken = "refresh-parentless",
                    expiresAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                    accountId = null,
                    email = null
                )
            )

            val parsedAuthJson = Json.parseToJsonElement(authPath.readText()).jsonObject
            assertEquals("chatgpt", parsedAuthJson["auth_mode"]!!.jsonPrimitive.content)
            assertEquals("id-parentless", parsedAuthJson["tokens"]!!.jsonObject["id_token"]!!.jsonPrimitive.content)
        } finally {
            java.nio.file.Files.deleteIfExists(authPath)
        }
    }

    @Test
    fun `oauth callback server returns callback code and state`() {
        OpenAIBrowserOAuthCallbackServer.start("http://localhost:0/auth/callback").use { server ->
            val response = get("http://127.0.0.1:${server.port}/auth/callback?code=code-safe&state=state-safe")
            val callback = server.waitForCallback(1)

            assertEquals(200, response.statusCode())
            assertEquals("code-safe", callback.code)
            assertEquals("state-safe", callback.state)
        }
    }

    @Test
    fun `oauth callback server surfaces auth error description`() {
        OpenAIBrowserOAuthCallbackServer.start("http://localhost:0/auth/callback").use { server ->
            val response = get(
                "http://127.0.0.1:${server.port}/auth/callback?error=access_denied&error_description=Denied"
            )
            val failure = kotlin.test.assertFailsWith<CliUsageException> {
                server.waitForCallback(1)
            }

            assertEquals(400, response.statusCode())
            assertTrue(failure.message.orEmpty().contains("Denied"))
        }
    }

    @Test
    fun `oauth callback server cancel endpoint responds without completing auth`() {
        OpenAIBrowserOAuthCallbackServer.start("http://localhost:0/auth/callback").use { server ->
            val response = get("http://127.0.0.1:${server.port}/cancel")

            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("canceled"))
        }
    }

    @Test
    fun `oauth callback server retries after canceling stale listener on same port`() {
        val staleServer = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress("127.0.0.1", 0),
            0
        )
        val port = staleServer.address.port
        staleServer.createContext("/cancel") { exchange ->
            val bytes = "stale canceled".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            Thread {
                Thread.sleep(25)
                staleServer.stop(0)
            }.start()
        }
        staleServer.start()

        OpenAIBrowserOAuthCallbackServer.start("http://localhost:$port/auth/callback").use { server ->
            val response = get("http://127.0.0.1:${server.port}/auth/callback?code=code-retry")
            val callback = server.waitForCallback(1)

            assertEquals(200, response.statusCode())
            assertEquals("code-retry", callback.code)
        }
    }

    @Test
    fun `openai browser oauth authorize url uses pkce codex flags and state`() {
        val session = OpenAIBrowserOAuth.begin(
            issuer = "https://auth.openai.com/",
            clientId = "client-test"
        )

        assertTrue(session.authorizeUrl.startsWith("https://auth.openai.com/oauth/authorize?"))
        assertTrue(session.authorizeUrl.contains("response_type=code"))
        assertTrue(session.authorizeUrl.contains("client_id=client-test"))
        assertTrue(session.authorizeUrl.contains("code_challenge_method=S256"))
        assertTrue(session.authorizeUrl.contains("codex_cli_simplified_flow=true"))
        assertTrue(session.authorizeUrl.contains("id_token_add_organizations=true"))
        assertTrue(session.authorizeUrl.contains("originator=evomolior"))
        assertTrue(session.authorizeUrl.contains("state="))
        assertTrue(session.state.isNotBlank())
        assertTrue(session.codeVerifier.isNotBlank())
    }

    @Test
    fun `codex auth preflight returns null for active session`() {
        val runner = RecordingCodexAuthProcessRunner(
            captured = CodexAuthProcessResult(0, "Logged in using ChatGPT\n", "")
        )

        val reason = CodexAuthCommand.sessionUnavailableReason("/tmp/codex", runner)

        assertEquals(null, reason)
        assertEquals(listOf(listOf("/tmp/codex", "login", "status")), runner.capturedCommands)
    }

    @Test
    fun `codex auth preflight points to status failure`() {
        val runner = RecordingCodexAuthProcessRunner(
            captured = CodexAuthProcessResult(1, "", "Not logged in")
        )

        val reason = CodexAuthCommand.sessionUnavailableReason("/tmp/codex", runner)

        assertTrue(reason.orEmpty().contains("Not logged in"))
    }

    @Test
    fun `codex auth preflight rejects non positive logged in status text`() {
        val runner = RecordingCodexAuthProcessRunner(
            captured = CodexAuthProcessResult(0, "Not logged in\n", "")
        )

        val reason = CodexAuthCommand.sessionUnavailableReason("/tmp/codex", runner)

        assertTrue(reason.orEmpty().contains("Not logged in"))
    }

    @Test
    fun `codex cli locator accepts executable additional candidate`() {
        val executable = createTempFile("codex-cli-", ".sh").toFile()
        executable.setExecutable(true)

        val resolved = CodexAuthCommand.locateCli(sequenceOf(executable.absolutePath))

        assertEquals(executable.absolutePath, resolved)
    }

    @Test
    fun `gptauth explicit codex cli path must be executable`() {
        val out = StringBuilder()
        val failure = kotlin.test.assertFailsWith<CliUsageException> {
            CodexAuthCommand.run(
                args = arrayOf("--codex-cli", "/definitely/missing/codex"),
                processRunner = RecordingCodexAuthProcessRunner(
                    captured = CodexAuthProcessResult(0, "Logged in using ChatGPT\n", "")
                ),
                out = out
            )
        }

        assertTrue(failure.message.orEmpty().contains("not executable"))
    }
}

private class RecordingCodexAuthProcessRunner(
    private val captured: CodexAuthProcessResult
) : CodexAuthProcessRunner {
    val capturedCommands = mutableListOf<List<String>>()

    override fun runCapturing(command: List<String>): CodexAuthProcessResult {
        capturedCommands += command
        return captured
    }
}

private class RecordingOpenAIBrowserOAuthRunner(
    private val result: OpenAIBrowserOAuthResult
) : OpenAIBrowserOAuthRunner {
    val capturedOptions = mutableListOf<OpenAIBrowserOAuthOptions>()

    override fun login(options: OpenAIBrowserOAuthOptions, out: Appendable): OpenAIBrowserOAuthResult {
        capturedOptions += options
        return result
    }
}

private fun get(url: String): HttpResponse<String> =
    HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
    )

private fun executableCodexPath(): String =
    createTempFile("codex-cli-", ".sh").toFile()
        .also { it.setExecutable(true) }
        .absolutePath
