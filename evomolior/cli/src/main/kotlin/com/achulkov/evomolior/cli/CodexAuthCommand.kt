package com.achulkov.evomolior.cli

import com.achulkov.evomolior.core.config.LlmBackend
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Desktop
import java.net.BindException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Instant

internal data class CodexAuthOptions(
    val action: CodexAuthAction = CodexAuthAction.STATUS,
    val cliPath: String? = null,
    val openBrowser: Boolean = true,
    val issuer: String = OpenAIBrowserOAuth.DEFAULT_ISSUER,
    val clientId: String = OpenAIBrowserOAuth.DEFAULT_CLIENT_ID,
    val timeoutSeconds: Long = OpenAIBrowserOAuth.DEFAULT_TIMEOUT_SECONDS
)

internal enum class CodexAuthAction {
    STATUS,
    LOGIN
}

internal interface CodexAuthProcessRunner {
    fun runCapturing(command: List<String>): CodexAuthProcessResult
}

internal data class CodexAuthProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

internal object DefaultCodexAuthProcessRunner : CodexAuthProcessRunner {
    override fun runCapturing(command: List<String>): CodexAuthProcessResult {
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        return CodexAuthProcessResult(process.waitFor(), stdout, stderr)
    }
}

internal object CodexAuthCommand {
    fun parse(args: Array<String>): CodexAuthOptions {
        var action = CodexAuthAction.STATUS
        var cliPath: String? = null
        var openBrowser = true
        var issuer = OpenAIBrowserOAuth.DEFAULT_ISSUER
        var clientId = OpenAIBrowserOAuth.DEFAULT_CLIENT_ID
        var timeoutSeconds = OpenAIBrowserOAuth.DEFAULT_TIMEOUT_SECONDS
        var index = 0
        while (index < args.size) {
            when (val arg = args[index]) {
                "status", "--status" -> {
                    action = CodexAuthAction.STATUS
                }
                "login" -> action = CodexAuthAction.LOGIN
                "--codex-cli" -> cliPath = args.getOrNull(++index)
                    ?: throw CliUsageException("Missing value for --codex-cli")
                "--no-open" -> openBrowser = false
                "--issuer" -> issuer = args.getOrNull(++index)
                    ?: throw CliUsageException("Missing value for --issuer")
                "--client-id" -> clientId = args.getOrNull(++index)
                    ?: throw CliUsageException("Missing value for --client-id")
                "--timeout-seconds" -> timeoutSeconds = args.getOrNull(++index)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?: throw CliUsageException("--timeout-seconds must be a positive integer")
                "--device-auth", "--no-device-auth" -> throw CliUsageException(
                    "EvoMolior does not use Codex device auth. Use `evomolior gptauth login` for " +
                        "the browser OAuth flow, or `evomolior gptauth status` to check an existing session."
                )
                "-h", "--help" -> throw CliUsageException(usage())
                else -> throw CliUsageException("Unknown gptauth argument: $arg")
            }
            index++
        }
        return CodexAuthOptions(action, cliPath, openBrowser, issuer, clientId, timeoutSeconds)
    }

    fun run(
        args: Array<String>,
        processRunner: CodexAuthProcessRunner = DefaultCodexAuthProcessRunner,
        out: Appendable = System.out,
        authPath: Path = Path.of(System.getProperty("user.home"), ".codex", "auth.json"),
        oauthRunner: OpenAIBrowserOAuthRunner = DefaultOpenAIBrowserOAuthRunner
    ): Int {
        val options = parse(args)
        if (options.action == CodexAuthAction.LOGIN) {
            return runBrowserLogin(options, out, authPath, oauthRunner)
        }
        return runStatus(options, processRunner, out, authPath)
    }

    private fun runStatus(
        options: CodexAuthOptions,
        processRunner: CodexAuthProcessRunner,
        out: Appendable,
        authPath: Path
    ): Int {
        val cliPath = options.cliPath?.let(::requireExecutableCli) ?: locateCli()
        val status = processRunner.runCapturing(listOf(cliPath, "login", "status"))
        if (status.exitCode != 0) {
            out.appendLine("Codex login status failed with exit code ${status.exitCode}.")
            out.appendLine("Run `evomolior gptauth login` to authenticate through OpenAI browser OAuth, then rerun `evomolior gptauth status`.")
            out.appendLine(status.stderr.ifBlank { status.stdout }.trim())
            return status.exitCode
        }
        status.stdout.ifBlank { status.stderr }.trim().takeIf { it.isNotBlank() }?.let(out::appendLine)
        safeSessionSummary(authPath)?.let { summary ->
            out.appendLine("Codex session: ${summary.authMode ?: "unknown auth mode"}")
            summary.accountId?.let { out.appendLine("Codex account: $it") }
            out.appendLine("Codex auth file: $authPath")
        }
        return 0
    }

    private fun runBrowserLogin(
        options: CodexAuthOptions,
        out: Appendable,
        authPath: Path,
        oauthRunner: OpenAIBrowserOAuthRunner
    ): Int {
        val result = oauthRunner.login(
            options = OpenAIBrowserOAuthOptions(
                issuer = options.issuer,
                clientId = options.clientId,
                openBrowser = options.openBrowser,
                timeoutSeconds = options.timeoutSeconds
            ),
            out = out
        )
        CodexAuthFileStore.save(authPath, result.tokens)
        out.appendLine("Codex/ChatGPT browser login complete.")
        result.tokens.accountId?.let { out.appendLine("Codex account: $it") }
        out.appendLine("Codex auth file: $authPath")
        out.appendLine("Token values were persisted but not printed.")
        return 0
    }

    fun sessionUnavailableReason(
        cliPath: String,
        processRunner: CodexAuthProcessRunner = DefaultCodexAuthProcessRunner
    ): String? {
        val status = runCatching {
            processRunner.runCapturing(listOf(cliPath, "login", "status"))
        }.getOrElse { cause ->
            return "Could not run Codex login status using '$cliPath': ${cause.message}"
        }
        if (status.exitCode != 0) {
            return "Codex login status failed with exit code ${status.exitCode}: " +
                status.stderr.ifBlank { status.stdout }.trim()
        }
        val statusText = status.stdout.ifBlank { status.stderr }.trim()
        return if (statusText.startsWith("Logged in", ignoreCase = true)) {
            null
        } else {
            "Codex login status did not report an active session: $statusText"
        }
    }

    fun requireUsableSession(
        cliPath: String,
        processRunner: CodexAuthProcessRunner = DefaultCodexAuthProcessRunner
    ) {
        sessionUnavailableReason(cliPath, processRunner)?.let { reason ->
            throw CliUsageException(
                "Codex/ChatGPT session is not available. Run `evomolior gptauth login` to " +
                    "authenticate through OpenAI browser OAuth, then rerun `evomolior gptauth status`. $reason"
            )
        }
    }

    fun usage(): String = """
        EvoMolior gptauth

        Usage:
          gptauth [status|--status] [--codex-cli /path/to/codex]
          gptauth login [--no-open] [--issuer https://auth.openai.com] [--client-id CLIENT_ID] [--timeout-seconds 900]

        status checks the local Codex/ChatGPT session and prints safe session metadata.
        login uses OpenAI browser OAuth with PKCE, writes ~/.codex/auth.json, and never prints token values.
        EvoMolior never shells out to `codex login` or `codex login --device-auth`.
    """.trimIndent()

    fun locateCli(additionalCandidates: Sequence<String> = emptySequence()): String =
        (additionalCandidates + LlmBackend.codexCliCandidates())
            .firstOrNull(LlmBackend::canExecuteCommand)
        ?: throw CliUsageException(
            "Codex CLI was not found. Set EVOMOLIOR_CODEX_CLI or ${LlmBackend.CODEX_CLI_ENV_VAR}, " +
                "install the Codex app at ${LlmBackend.DEFAULT_CODEX_APP_CLI_PATH}, or put 'codex' on PATH."
        )

    private fun requireExecutableCli(cliPath: String): String {
        if (LlmBackend.canExecuteCommand(cliPath)) return cliPath
        throw CliUsageException("Codex CLI is not executable or not on PATH: $cliPath")
    }

    private fun safeSessionSummary(authPath: Path): CodexSessionSummary? {
        if (!Files.isRegularFile(authPath)) return null
        val jsonObject = runCatching {
            Json.parseToJsonElement(Files.readString(authPath)).jsonObject
        }.getOrNull() ?: return null
        val tokens = jsonObject["tokens"] as? JsonObject
        return CodexSessionSummary(
            authMode = jsonObject["auth_mode"]?.jsonPrimitive?.content,
            accountId = tokens?.get("account_id")?.jsonPrimitive?.content
        )
    }
}

private data class CodexSessionSummary(
    val authMode: String?,
    val accountId: String?
)

internal data class OpenAIBrowserOAuthOptions(
    val issuer: String,
    val clientId: String,
    val openBrowser: Boolean,
    val timeoutSeconds: Long
)

internal data class OpenAIBrowserOAuthResult(
    val tokens: OpenAIOAuthTokens
)

internal interface OpenAIBrowserOAuthRunner {
    fun login(options: OpenAIBrowserOAuthOptions, out: Appendable): OpenAIBrowserOAuthResult
}

internal object DefaultOpenAIBrowserOAuthRunner : OpenAIBrowserOAuthRunner {
    override fun login(options: OpenAIBrowserOAuthOptions, out: Appendable): OpenAIBrowserOAuthResult {
        val session = OpenAIBrowserOAuth.begin(options.issuer, options.clientId)
        OpenAIBrowserOAuthCallbackServer.start(session.redirectUri).use { server ->
            out.appendLine("OpenAI browser OAuth URL:")
            out.appendLine(session.authorizeUrl)
            out.appendLine("Waiting for callback on ${session.redirectUri}")
            if (options.openBrowser) {
                OpenAIBrowserOAuth.openBrowser(session.authorizeUrl)
            } else {
                out.appendLine("Browser opening disabled; open the URL manually.")
            }
            val callback = server.waitForCallback(options.timeoutSeconds)
            val tokens = OpenAIBrowserOAuth.exchangeAuthorizationCode(
                issuer = options.issuer,
                clientId = options.clientId,
                session = session,
                callback = callback
            )
            return OpenAIBrowserOAuthResult(tokens)
        }
    }
}

internal data class OpenAIBrowserOAuthSession(
    val authorizeUrl: String,
    val redirectUri: String,
    val state: String,
    val codeVerifier: String
)

internal data class OpenAIBrowserOAuthCallback(
    val code: String,
    val state: String
)

internal data class OpenAIOAuthTokens(
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: Instant,
    val accountId: String?,
    val email: String?
)

internal object OpenAIBrowserOAuth {
    const val DEFAULT_ISSUER = "https://auth.openai.com"
    const val DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val DEFAULT_REDIRECT_URI = "http://localhost:1455/auth/callback"
    const val DEFAULT_TIMEOUT_SECONDS = 900L
    private const val ORIGINATOR = "evomolior"
    private val json = Json { ignoreUnknownKeys = true }
    private val clock: Clock = Clock.systemUTC()

    fun begin(issuer: String = DEFAULT_ISSUER, clientId: String = DEFAULT_CLIENT_ID): OpenAIBrowserOAuthSession {
        val normalizedIssuer = issuer.trim().trimEnd('/')
        val state = randomBase64Url(24)
        val codeVerifier = randomBase64Url(48)
        val challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        )
        val query = linkedMapOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to DEFAULT_REDIRECT_URI,
            "scope" to "openid profile email offline_access",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to ORIGINATOR,
            "state" to state
        ).entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        return OpenAIBrowserOAuthSession(
            authorizeUrl = "$normalizedIssuer/oauth/authorize?$query",
            redirectUri = DEFAULT_REDIRECT_URI,
            state = state,
            codeVerifier = codeVerifier
        )
    }

    fun parseCallback(urlOrQuery: String): OpenAIBrowserOAuthCallback {
        val input = urlOrQuery.trim()
        if (input.isBlank()) throw CliUsageException("OAuth callback is empty")
        val query = if (input.contains("://")) {
            URI(input).rawQuery.orEmpty()
        } else if (!input.contains("=")) {
            return OpenAIBrowserOAuthCallback(code = input, state = "")
        } else {
            input.removePrefix("?")
        }
        val params = query.split("&")
            .filter { it.isNotBlank() }
            .mapNotNull {
                val separatorIndex = it.indexOf('=')
                if (separatorIndex < 0) null else {
                    java.net.URLDecoder.decode(it.substring(0, separatorIndex), Charsets.UTF_8) to
                        java.net.URLDecoder.decode(it.substring(separatorIndex + 1), Charsets.UTF_8)
                }
            }
            .toMap()
        return OpenAIBrowserOAuthCallback(
            code = params["code"].orEmpty(),
            state = params["state"].orEmpty()
        )
    }

    fun exchangeAuthorizationCode(
        issuer: String,
        clientId: String,
        session: OpenAIBrowserOAuthSession,
        callback: OpenAIBrowserOAuthCallback,
        httpClient: java.net.http.HttpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build()
    ): OpenAIOAuthTokens {
        if (callback.code.isBlank()) throw CliUsageException("OAuth callback is missing code")
        if (callback.state.isNotBlank() && callback.state != session.state) {
            throw CliUsageException("OAuth state mismatch")
        }
        val body = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to callback.code,
            "redirect_uri" to session.redirectUri,
            "client_id" to clientId,
            "code_verifier" to session.codeVerifier
        ).entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        val request = java.net.http.HttpRequest.newBuilder(
            URI.create("${issuer.trim().trimEnd('/')}/oauth/token")
        )
            .timeout(java.time.Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        if (response.statusCode() / 100 != 2) {
            throw CliUsageException("OpenAI OAuth token exchange failed with status ${response.statusCode()}: ${response.body().take(400)}")
        }
        val payload = json.parseToJsonElement(response.body()).jsonObject
        val accessToken = payload["access_token"]?.jsonPrimitive?.content.orEmpty()
        val refreshToken = payload["refresh_token"]?.jsonPrimitive?.content.orEmpty()
        val idToken = payload["id_token"]?.jsonPrimitive?.content.orEmpty()
        if (accessToken.isBlank()) throw CliUsageException("OpenAI OAuth token response is missing access_token")
        if (refreshToken.isBlank()) throw CliUsageException("OpenAI OAuth token response is missing refresh_token")
        if (idToken.isBlank()) throw CliUsageException("OpenAI OAuth token response is missing id_token")
        val expiresIn = payload["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        val claims = jwtClaims(idToken)
        val authClaims = claims["https://api.openai.com/auth"] as? JsonObject
        val accountId = claims["chatgpt_account_id"]?.jsonPrimitive?.content
            ?: authClaims?.get("chatgpt_account_id")?.jsonPrimitive?.content
        val email = claims["email"]?.jsonPrimitive?.content
        return OpenAIOAuthTokens(
            idToken = idToken,
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = payload["token_type"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "Bearer",
            expiresAt = clock.instant().plusSeconds(expiresIn),
            accountId = accountId,
            email = email
        )
    }

    fun openBrowser(rawUrl: String) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(rawUrl))
            return
        }
        val command = when (System.getProperty("os.name").lowercase()) {
            in listOf("mac os x", "darwin") -> listOf("open", rawUrl)
            else -> if (System.getProperty("os.name").lowercase().contains("windows")) {
                listOf("rundll32", "url.dll,FileProtocolHandler", rawUrl)
            } else {
                listOf("xdg-open", rawUrl)
            }
        }
        ProcessBuilder(command).start()
    }

    private fun randomBase64Url(size: Int): String {
        val bytes = ByteArray(size)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun jwtClaims(token: String): JsonObject {
        val parts = token.split('.')
        if (parts.size != 3) return JsonObject(emptyMap())
        val payload = runCatching {
            String(java.util.Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        }.getOrNull() ?: return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(payload).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}

internal class OpenAIBrowserOAuthCallbackServer private constructor(
    private val server: com.sun.net.httpserver.HttpServer,
    private val callbackQueue: java.util.concurrent.ArrayBlockingQueue<Result<OpenAIBrowserOAuthCallback>>
) : AutoCloseable {
    val port: Int
        get() = server.address.port

    fun waitForCallback(timeoutSeconds: Long): OpenAIBrowserOAuthCallback {
        val result = callbackQueue.poll(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            ?: throw CliUsageException("OpenAI OAuth browser callback timed out")
        return result.getOrThrow()
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        fun start(redirectUri: String): OpenAIBrowserOAuthCallbackServer {
            val uri = URI.create(redirectUri)
            val queue = java.util.concurrent.ArrayBlockingQueue<Result<OpenAIBrowserOAuthCallback>>(1)
            val server = createServerWithRetry(uri.port)
            server.createContext(uri.path) { exchange ->
                val callback = runCatching {
                    val query = exchange.requestURI.rawQuery.orEmpty()
                    val params = parseQuery(query)
                    val authError = params["error"].orEmpty()
                    if (authError.isNotBlank()) {
                        throw CliUsageException(params["error_description"].orEmpty().ifBlank { authError })
                    }
                    val parsed = OpenAIBrowserOAuth.parseCallback(query)
                    if (parsed.code.isBlank()) throw CliUsageException("OAuth callback is missing code")
                    parsed
                }
                val response = if (callback.isSuccess) {
                    queue.offer(callback)
                    """
                    <!doctype html><html><head><title>Auth complete</title></head>
                    <body><h1>Auth complete</h1><p>You can close this tab now.</p></body></html>
                    """.trimIndent()
                } else {
                    queue.offer(callback)
                    "Authorization failed: ${callback.exceptionOrNull()?.message ?: "unknown error"}"
                }
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(if (callback.isSuccess) 200 else 400, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.createContext("/cancel") { exchange ->
                val bytes = "OAuth callback listener canceled".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            return OpenAIBrowserOAuthCallbackServer(server, queue)
        }

        private fun createServerWithRetry(port: Int): com.sun.net.httpserver.HttpServer {
            var cancelAttempted = false
            repeat(10) { attempt ->
                try {
                    return com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", port), 0)
                } catch (error: BindException) {
                    if (!cancelAttempted) {
                        sendCancel(port)
                        cancelAttempted = true
                    }
                    if (attempt == 9) throw CliUsageException("OAuth callback port 127.0.0.1:$port is already in use")
                    Thread.sleep(200)
                }
            }
            throw CliUsageException("OAuth callback port 127.0.0.1:$port is already in use")
        }

        private fun sendCancel(port: Int) {
            runCatching {
                java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2))
                    .build()
                    .send(
                        java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/cancel"))
                            .timeout(java.time.Duration.ofSeconds(2))
                            .GET()
                            .build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding()
                    )
            }
        }

        private fun parseQuery(query: String): Map<String, String> =
            query.split("&")
                .filter { it.isNotBlank() }
                .mapNotNull {
                    val separatorIndex = it.indexOf('=')
                    if (separatorIndex < 0) null else {
                        java.net.URLDecoder.decode(it.substring(0, separatorIndex), Charsets.UTF_8) to
                            java.net.URLDecoder.decode(it.substring(separatorIndex + 1), Charsets.UTF_8)
                    }
                }
                .toMap()
    }
}

internal object CodexAuthFileStore {
    private val authFileJson = Json { prettyPrint = true }

    fun save(authPath: Path, tokens: OpenAIOAuthTokens, now: Instant = Instant.now()) {
        val parent = authPath.parent
        parent?.let(Files::createDirectories)
        val payload = buildJsonObject {
            put("auth_mode", "chatgpt")
            put("OPENAI_API_KEY", JsonNull)
            putJsonObject("tokens") {
                put("id_token", tokens.idToken)
                put("access_token", tokens.accessToken)
                put("refresh_token", tokens.refreshToken)
                tokens.accountId?.let { put("account_id", it) }
            }
            put("last_refresh", now.toString())
        }
        val temp = if (parent == null) {
            Files.createTempFile(Path.of("").toAbsolutePath(), "auth-", ".json.tmp")
        } else {
            Files.createTempFile(parent, "auth-", ".json.tmp")
        }
        Files.writeString(temp, authFileJson.encodeToString(JsonObject.serializer(), payload))
        setOwnerOnlyPermissions(temp)
        Files.move(temp, authPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        setOwnerOnlyPermissions(authPath)
    }

    private fun setOwnerOnlyPermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
                )
            )
        }
    }
}
