package com.alphaai.app.data.sandbox

import com.alphaai.app.data.security.SecureKeyStore
import com.alphaai.app.data.security.ApiKeyProvider
import com.alphaai.app.data.security.SecureAuthTag
import com.alphaai.app.util.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * E2B (e2b.dev) remote sandbox over its REST API.
 *
 * An ephemeral sandbox is created lazily on first use for the task and killed on [close].
 * The E2B API key comes from SecureKeyStore (Settings) and is sent as the X-API-Key header.
 *
 * NOTE: E2B's public REST surface evolves; the endpoints below follow the documented
 * v1 shapes at the time of writing (see README → "E2B notes"). All file operations are
 * implemented through shell primitives (base64) so they do not depend on a separate
 * files API. If your E2B plan exposes different paths, adjust ENDPOINT constants only.
 */
class RemoteSandbox(
    private val okHttpClient: OkHttpClient,
    private val keyStore: ApiKeyProvider
) : SandboxClient {

    internal constructor(okHttpClient: OkHttpClient, keyStore: ApiKeyProvider, baseUrl: String) : this(okHttpClient, keyStore) {
        this.baseUrl = baseUrl.trimEnd('/')
    }

    override val requiresHttpAllowlist: Boolean = false

    private var baseUrl = "https://api.e2b.dev"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var sandboxId: String? = null

    override fun capabilitySummary(): String =
        "REMOTE sandbox (E2B ephemeral VM): full shell, python3 with pip auto-install, " +
            "node, and headless Chromium via Playwright. Network access is unrestricted " +
            "inside the sandbox. Files persist for the lifetime of this task only."

    override fun workingDirectory(): String = "/home/user (remote E2B sandbox)"

    private fun requireKey() {
        if (!keyStore.has(SecureKeyStore.Secret.E2B)) {
            throw IllegalStateException("No E2B API key set — add it in Settings to use the Remote sandbox profile")
        }
    }

    private suspend fun ensureSandbox(): String {
        sandboxId?.let { return it }
        requireKey()
        val body = buildJsonObject {
            put("templateID", DEFAULT_TEMPLATE)
            put("timeout", SANDBOX_TIMEOUT_SECONDS)
        }
        val request = Request.Builder()
            .url("$baseUrl/sandboxes")
            .tag(SecureAuthTag::class.java, SecureAuthTag(SecureKeyStore.Secret.E2B, "x-api-key"))
            .header("Content-Type", "application/json")
            .post(Json.encodeToString(body).toRequestBody(JSON_MEDIA))
            .build()
        val id = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("E2B sandbox create failed: HTTP ${resp.code} ${text.take(300)}")
                }
                json.parseToJsonElement(text).jsonObjectSafe()["sandboxID"]?.jsonPrimitive?.content
                    ?: json.parseToJsonElement(text).jsonObjectSafe()["sandboxId"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException("E2B response missing sandboxID")
            }
        }
        sandboxId = id
        SafeLog.d(TAG, "e2b sandbox created: $id")
        return id
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe() =
        this as? kotlinx.serialization.json.JsonObject
            ?: throw IllegalStateException("Unexpected E2B response shape")

    override suspend fun runShell(command: String, timeoutMs: Long): SandboxResult = try {
        withTimeout(timeoutMs) {
            val id = ensureSandbox()
            execViaShell(id, command)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (e: Exception) {
        SandboxResult("shell error: ${e.message}", exitCode = 1, isError = true)
    }

    override suspend fun runPython(code: String, timeoutMs: Long): SandboxResult {
        // First attempt; on ModuleNotFoundError, auto pip-install the missing module once and retry.
        val first = runShell(pythonInvocation(code), timeoutMs)
        if (!first.isError) return first
        val missing = Regex("No module named ['\"]([A-Za-z0-9_.-]+)['\"]")
            .find(first.output)?.groupValues?.getOrNull(1)
            ?: return first
        SafeLog.d(TAG, "auto pip install: $missing")
        val install = runShell("pip install --quiet '$missing' 2>&1 || pip3 install --quiet '$missing' 2>&1", timeoutMs)
        if (install.isError) return SandboxResult("pip install $missing failed:\n${install.output}\n---\n${first.output}", 1, true)
        return runShell(pythonInvocation(code), timeoutMs)
    }

    private fun pythonInvocation(code: String): String {
        // Base64-encode the user code to avoid all quoting issues.
        val b64 = android.util.Base64.encodeToString(code.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return "python3 -c \"import base64;exec(base64.b64decode('$b64').decode('utf-8'))\""
    }

    override suspend fun runNode(code: String, timeoutMs: Long): SandboxResult {
        val b64 = android.util.Base64.encodeToString(code.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return runShell("node -e \"eval(Buffer.from('$b64','base64').toString('utf8'))\"", timeoutMs)
    }

    override suspend fun readFile(path: String): SandboxResult = try {
        SandboxResult(String(readFileBytes(path), Charsets.UTF_8))
    } catch (e: Exception) {
        SandboxResult("read_file error: ${e.message}", 1, true)
    }

    override suspend fun readFileBytes(path: String): ByteArray {
        val result = runShell("base64 ${shellQuote(path)}")
        if (result.isError) throw IllegalStateException(result.output)
        return android.util.Base64.decode(result.output.trim(), android.util.Base64.DEFAULT)
    }

    override suspend fun writeFile(path: String, content: String): SandboxResult {
        val b64 = android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val parent = path.substringBeforeLast('/', ".").ifBlank { "." }
        return runShell("mkdir -p ${shellQuote(parent)} && printf '%s' '$b64' | base64 -d > ${shellQuote(path)}")
    }

    override suspend fun listDir(path: String): SandboxResult =
        runShell("ls -la ${shellQuote(path)} 2>&1")

    override suspend fun patchFile(path: String, search: String, replace: String): SandboxResult {
        // Patch via python to avoid sed escaping hell.
        val s = pyQuote(search)
        val r = pyQuote(replace)
        val p = pyQuote(path)
        val code = """
import sys
p, s, r = $p, $s, $r
src = open(p, encoding='utf-8').read()
if s not in src:
    print('PATCH_MISS: search string not found in ' + p); sys.exit(3)
open(p, 'w', encoding='utf-8').write(src.replace(s, r, 1))
print('PATCH_OK')
""".trimIndent()
        val res = runPython(code)
        return if (res.output.contains("PATCH_MISS")) {
            SandboxResult("search string not found in $path", 3, true)
        } else res
    }

    override suspend fun browserScreenshot(url: String, actions: List<String>): SandboxResult {
        // Build a Playwright sync-API script; actions are plain strings the model emits,
        // e.g. "click text=Sign in", "fill input[name=q] :: hello", "wait 2000".
        val actionsPy = actions.joinToString("\n") { a ->
            val click = Regex("^click\\s+(.+)$").matchEntire(a.trim())
            val fill = Regex("^fill\\s+(.+?)\\s*::\\s*(.*)$").matchEntire(a.trim())
            val wait = Regex("^wait\\s+(\\d+)$").matchEntire(a.trim())
            when {
                click != null -> "    page.click(${pyQuote(click.groupValues[1].trim())})"
                fill != null -> "    page.fill(${pyQuote(fill.groupValues[1].trim())}, ${pyQuote(fill.groupValues[2])})"
                wait != null -> "    page.wait_for_timeout(${wait.groupValues[1]})"
                else -> "    pass  # unsupported action: ${a.replace("\n", " ")}"
            }
        }
        val code = """
import base64, sys
try:
    from playwright.sync_api import sync_playwright
except ModuleNotFoundError:
    print('PLAYWRIGHT_MISSING'); sys.exit(4)
with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    page = b.new_page(viewport={'width': 1280, 'height': 800})
    page.goto(${pyQuote(url)}, timeout=45000, wait_until='domcontentloaded')
$actionsPy
    page.screenshot(path='/tmp/screenshot.png', full_page=False)
    b.close()
print(base64.b64encode(open('/tmp/screenshot.png','rb').read()).decode('ascii'))
""".trimIndent()
        val result = runPython(code, timeoutMs = 120_000)
        if (result.output.trim() == "PLAYWRIGHT_MISSING" || result.output.contains("PLAYWRIGHT_MISSING")) {
            val install = runShell("pip install --quiet playwright 2>&1 && python3 -m playwright install chromium 2>&1 | tail -1", 300_000)
            if (install.isError) return SandboxResult("playwright install failed:\n${install.output}", 1, true)
            return browserScreenshot(url, actions)
        }
        val b64 = result.output.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (result.isError || b64.length < 100) {
            return SandboxResult("browser failed:\n${result.output.take(2000)}", 1, true)
        }
        return SandboxResult("Screenshot captured (${url})", 0, false, screenshotBase64 = b64)
    }

    override suspend fun close() {
        val id = sandboxId ?: return
        if (!keyStore.has(SecureKeyStore.Secret.E2B)) return
        runCatching {
            withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("$baseUrl/sandboxes/$id")
                    .tag(SecureAuthTag::class.java, SecureAuthTag(SecureKeyStore.Secret.E2B, "x-api-key"))
                    .delete()
                    .build()
                okHttpClient.newCall(req).execute().use { /* best-effort */ }
            }
        }
        sandboxId = null
    }

    // ---------------- internals ----------------

    private suspend fun execViaShell(sandboxId: String, command: String): SandboxResult {
        requireKey()
        val cmdB64 = android.util.Base64.encodeToString(command.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val body = buildJsonObject {
            // exec endpoint takes the command; we wrap in bash -lc with base64 to survive quoting.
            put("command", "echo $cmdB64 | base64 -d | bash -lc 'bash /dev/stdin'")
        }
        val request = Request.Builder()
            .url("$baseUrl/sandboxes/$sandboxId/exec")
            .tag(SecureAuthTag::class.java, SecureAuthTag(SecureKeyStore.Secret.E2B, "x-api-key"))
            .header("Content-Type", "application/json")
            .post(Json.encodeToString(body).toRequestBody(JSON_MEDIA))
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    SandboxResult("E2B exec failed: HTTP ${resp.code} ${text.take(300)}", resp.code, true)
                } else {
                    parseExecOutput(text)
                }
            }
        }
    }

    private fun parseExecOutput(raw: String): SandboxResult {
        // Accept either {"output"/"stdout"/"result": ..., "exitCode": n} or raw text.
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObjectSafe() }.getOrNull()
        if (parsed == null) return SandboxResult(raw.take(MAX_OUTPUT))
        val stdout = parsed["stdout"]?.jsonPrimitive?.content
            ?: parsed["output"]?.jsonPrimitive?.content
            ?: parsed["result"]?.jsonPrimitive?.content
            ?: raw
        val stderr = parsed["stderr"]?.jsonPrimitive?.content.orEmpty()
        val exitCode = parsed["exitCode"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: parsed["exit_code"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: 0
        val merged = buildString {
            append(stdout.take(MAX_OUTPUT))
            if (stderr.isNotBlank()) {
                append("\n--- stderr ---\n")
                append(stderr.take(MAX_OUTPUT / 2))
            }
        }
        return SandboxResult(merged.ifBlank { "(no output)" }, exitCode, exitCode != 0)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun pyQuote(s: String): String {
        // Produce a double-quoted Python literal via JSON (JSON string escaping ⊂ Python).
        return Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(s))
    }

    /**
     * Factory so each task gets its own RemoteSandbox instance (created lazily).
     *
     * Declared at file level rather than inside the companion object: KSP cannot
     * resolve @Inject constructors of companion-nested classes and reports them as
     * `error.NonExistentClass`, which fails the Hilt/Room processing steps.
     */
    @Singleton
    class Factory @Inject constructor(
        private val okHttpClient: OkHttpClient,
        private val keyStore: ApiKeyProvider
    ) {
        fun create(): RemoteSandbox = RemoteSandbox(okHttpClient, keyStore)
    }

    companion object {
        private const val TAG = "RemoteSandbox"
        private const val DEFAULT_TEMPLATE = "code-interpreter-v1"
        private const val SANDBOX_TIMEOUT_SECONDS = 3600
        private const val MAX_OUTPUT = 12_000
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
