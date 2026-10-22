package com.achulkov.evomolior.core.config

/**
 * LLM backend used by EvoMolior runtime agents.
 *
 * `OPENROUTER` is the historical default. `QWEN_CLOUD` targets Alibaba Dashscope / Qwen Cloud
 * through Koog's Dashscope client when that dependency is available on the runtime classpath.
 */
enum class LlmBackend(
    val id: String,
    val apiKeyEnvVar: String
) {
    OPENROUTER("openrouter", "OPENROUTER_API_KEY"),
    QWEN_CLOUD("qwen-cloud", "QWEN_API_KEY"),
    CODEX_CHATGPT("codex-chatgpt", "EVOMOLIOR_CODEX_CLI");

    /**
     * Returns whether this backend can execute text agents in the current runtime classpath.
     */
    fun isTextRuntimeAvailable(): Boolean = when (this) {
        OPENROUTER -> true
        QWEN_CLOUD -> runCatching {
            val clientClass = Class.forName(DASHSCOPE_CLIENT_CLASS_NAME)
            val factoryClass = Class.forName(KOOG_HTTP_CLIENT_FACTORY_CLASS_NAME)
            Class.forName(KTOR_KOOG_HTTP_CLIENT_FACTORY_CLASS_NAME)
            clientClass.getConstructor(String::class.java, factoryClass)
        }.isSuccess
        CODEX_CHATGPT -> resolveCodexCliPath() != null
    }

    /**
     * Returns a human-readable unavailability reason, or null when the backend is runnable.
     */
    fun textRuntimeUnavailableReason(): String? = when {
        isTextRuntimeAvailable() -> null
        this == QWEN_CLOUD -> "Qwen Cloud text backend requires Koog Dashscope client " +
            "($DASHSCOPE_CLIENT_CLASS_NAME) on the runtime classpath. The repo is pinned to Koog " +
            "1.0.0, while the published Dashscope artifacts currently stop at 1.0.0-beta-preview7."
        this == CODEX_CHATGPT -> "Codex/ChatGPT backend requires the Codex CLI. Set " +
            "$CODEX_CLI_ENV_VAR or $apiKeyEnvVar, install the Codex desktop app at " +
            "$DEFAULT_CODEX_APP_CLI_PATH, or put 'codex' on PATH."
        else -> null
    }

    companion object {
        const val DASHSCOPE_CLIENT_CLASS_NAME =
            "ai.koog.prompt.executor.clients.dashscope.DashscopeLLMClient"
        const val KOOG_HTTP_CLIENT_FACTORY_CLASS_NAME =
            "ai.koog.http.client.KoogHttpClient\$Factory"
        const val KTOR_KOOG_HTTP_CLIENT_FACTORY_CLASS_NAME =
            "ai.koog.http.client.ktor.KtorKoogHttpClient\$Factory"
        const val CODEX_CLI_ENV_VAR = "CODEX_CLI_PATH"
        const val DEFAULT_CODEX_APP_CLI_PATH = "/Applications/Codex.app/Contents/Resources/codex"
        const val CHATGPT_APP_CODEX_CLI_PATH = "/Applications/ChatGPT.app/Contents/Resources/codex"
        const val CHATGPT_APP_NESTED_CODEX_CLI_PATH =
            "/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex"

        fun parse(value: String): LlmBackend = entries.firstOrNull {
            it.id.equals(value, ignoreCase = true) ||
                it.name.equals(value, ignoreCase = true) ||
                aliases[it]!!.any { alias -> alias.equals(value, ignoreCase = true) }
        } ?: error(
            "Unsupported LLM backend '$value'. Supported backends: ${entries.joinToString(", ") { it.id }}"
        )

        private val aliases: Map<LlmBackend, Set<String>> = mapOf(
            OPENROUTER to emptySet(),
            QWEN_CLOUD to setOf("qwen"),
            CODEX_CHATGPT to setOf("codex", "chatgpt", "codex-chat-gpt")
        )

        fun resolveCodexCliPath(candidates: Sequence<String> = codexCliCandidates()): String? = candidates
            .firstOrNull(::canExecuteCommand)

        fun codexCliCandidates(getenv: (String) -> String? = System::getenv): Sequence<String> = sequenceOf(
            getenv("EVOMOLIOR_CODEX_CLI"),
            getenv(CODEX_CLI_ENV_VAR),
            CHATGPT_APP_NESTED_CODEX_CLI_PATH,
            CHATGPT_APP_CODEX_CLI_PATH,
            DEFAULT_CODEX_APP_CLI_PATH,
            "codex"
        ).filterNotNull()

        fun canExecuteCommand(command: String): Boolean =
            if (command.contains('/')) {
                java.io.File(command).canExecute()
            } else {
                System.getenv("PATH").orEmpty()
                    .split(java.io.File.pathSeparator)
                    .filter { it.isNotBlank() }
                    .map { java.io.File(it, command) }
                    .any { it.canExecute() }
            }
    }
}
