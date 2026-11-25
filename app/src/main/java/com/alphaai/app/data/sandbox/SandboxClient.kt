package com.alphaai.app.data.sandbox

/** Result of a sandboxed command/file operation. */
data class SandboxResult(
    val output: String,
    val exitCode: Int = 0,
    val isError: Boolean = false,
    /** Base64-encoded screenshot PNG when the browser tool captured one. */
    val screenshotBase64: String? = null
)

/**
 * Abstraction over the two execution profiles.
 *
 * REMOTE (E2B): ephemeral per-task VM with full shell, python, node, playwright.
 * LIGHT (on-device): scoped filesystem under app-private artifacts/, Python via the
 * fullPython flavor (Chaquopy), JS via an optional QuickJS dependency, no shell/browser.
 */
interface SandboxClient {
    /** LIGHT profile requires the user's host allowlist for every HTTP request. */
    val requiresHttpAllowlist: Boolean

    /** Environment description injected into the system prompt so the model knows its limits. */
    fun capabilitySummary(): String

    /** Root description used in prompts, e.g. "/sandbox (remote E2B VM)" or "artifacts/ (on-device)". */
    fun workingDirectory(): String

    suspend fun runShell(command: String, timeoutMs: Long = 120_000): SandboxResult

    suspend fun runPython(code: String, timeoutMs: Long = 120_000): SandboxResult

    suspend fun runNode(code: String, timeoutMs: Long = 60_000): SandboxResult

    suspend fun readFile(path: String): SandboxResult

    /** Exact bytes, used when importing image/PDF/binary artifacts into app-private storage. */
    suspend fun readFileBytes(path: String): ByteArray

    suspend fun writeFile(path: String, content: String): SandboxResult

    suspend fun listDir(path: String): SandboxResult

    /** Replaces the first exact occurrence of [search] with [replace] in [path]. */
    suspend fun patchFile(path: String, search: String, replace: String): SandboxResult

    /** Runs a Python+Playwright snippet and returns a base64 PNG screenshot, if any. */
    suspend fun browserScreenshot(url: String, actions: List<String>): SandboxResult

    /** Releases any per-task remote resources. No-op for the local profile. */
    suspend fun close()
}
