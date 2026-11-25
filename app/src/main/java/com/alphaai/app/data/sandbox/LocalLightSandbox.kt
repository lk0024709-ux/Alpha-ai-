package com.alphaai.app.data.sandbox

import android.content.Context
import com.alphaai.app.BuildConfig
import com.alphaai.app.util.SafeLog
import com.alphaai.app.util.JsonUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LIGHT profile: everything lives under app-private `files/artifacts/`.
 *  - filesystem tools: scoped to that root (canonical-path traversal guard),
 *  - python: Chaquopy via reflection — only in the `fullPython` flavor
 *    (BuildConfig.FULL_PYTHON). In the light flavor it returns a clear error,
 *  - node: QuickJS if the host app bundles a QuickJS dependency (reflection);
 *    otherwise a clear, documented error,
 *  - shell / browser: NOT available on-device.
 *
 * No outbound HTTP here — the agent's http_request/web_search tools are the
 * only network paths, and they are governed by the SSRF guard + the user's
 * allowlist (see HttpRequestTool).
 */
@Singleton
class LocalLightSandbox @Inject constructor(
    @ApplicationContext private val context: Context
) : SandboxClient {

    override val requiresHttpAllowlist: Boolean = true

    val rootDir: File by lazy {
        File(context.filesDir, "artifacts").apply { mkdirs() }
    }

    override fun capabilitySummary(): String =
        "LIGHT MODE (on-device, offline): file tools scoped to app-private artifacts/, " +
            (if (BuildConfig.FULL_PYTHON) "python3 via Chaquopy (numpy/pandas/matplotlib), " else "") +
            "javascript via QuickJS if bundled. shell, browser and unrestricted HTTP are NOT available. " +
            "http_request is limited to user-allowlisted hosts."

    override fun workingDirectory(): String = "artifacts/ (app-private, on-device)"

    private fun resolveInRoot(path: String): File {
        val clean = path.trim().trimStart('/')
        val target = File(rootDir, clean).canonicalFile
        val rootCanonical = rootDir.canonicalFile
        if (target != rootCanonical && !target.path.startsWith(rootCanonical.path + File.separator)) {
            throw IllegalArgumentException("path escapes sandbox root: $path")
        }
        return target
    }

    override suspend fun runShell(command: String, timeoutMs: Long): SandboxResult =
        SandboxResult(
            "shell is not available in LIGHT MODE. Use read_file/write_file/list_dir/patch_file, " +
                "python (fullPython flavor), or switch this task to the REMOTE profile.",
            exitCode = 127, isError = true
        )

    override suspend fun runPython(code: String, timeoutMs: Long): SandboxResult = withContext(Dispatchers.IO) {
        if (!BuildConfig.FULL_PYTHON) {
            return@withContext SandboxResult(
                "python is not bundled in the light flavor. Build the fullPython flavor " +
                    "(./gradlew assembleFullPythonDebug) or switch to the REMOTE profile.",
                exitCode = 127, isError = true
            )
        }
        try {
            executeChaquopy(code)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            SafeLog.w(TAG, "chaquopy failed: ${e.message}")
            SandboxResult("python error: ${e.message}", exitCode = 1, isError = true)
        }
    }

    /**
     * Executes [code] under Chaquopy using reflection so the light flavor compiles
     * without the Chaquopy dependency. Stdout/stderr are captured via contextlib.
     */
    private fun executeChaquopy(code: String): SandboxResult {
        val contextClass = android.content.Context::class.java

        val pythonClass = Class.forName("com.chaquo.python.Python")
        val androidPlatformClass = Class.forName("com.chaquo.python.android.AndroidPlatform")

        // Start Chaquopy once per process.
        val isStarted = pythonClass.getMethod("isStarted").invoke(null) as Boolean
        if (!isStarted) {
            val platform = androidPlatformClass.getConstructor(contextClass).newInstance(context)
            pythonClass.getMethod("start", androidPlatformClass).invoke(null, platform)
        }

        val codeJson = JsonUtil.json.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(),
            kotlinx.serialization.json.JsonPrimitive(code)
        )

        val runner = """
import sys, io, json, contextlib
_buf = io.StringIO(); _err = io.StringIO(); _code = json.loads($codeJson)
try:
    with contextlib.redirect_stdout(_buf), contextlib.redirect_stderr(_err):
        exec(compile(_code, '<agent>', 'exec'), {'__name__': '__main__'})
    _out = json.dumps({'ok': True, 'stdout': _buf.getvalue(), 'stderr': _err.getvalue()})
except Exception as _e:
    _out = json.dumps({'ok': False, 'error': type(_e).__name__ + ': ' + str(_e), 'stdout': _buf.getvalue(), 'stderr': _err.getvalue()})
""".trimIndent()

        val python = pythonClass.getMethod("getInstance").invoke(null)
        val mainModule = python.javaClass.getMethod("getModule", String::class.java).invoke(python, "__main__")
        val callAttr = mainModule.javaClass.methods.firstOrNull { it.name == "callAttr" && it.parameterTypes.size == 2 }
            ?: throw IllegalStateException("Chaquopy callAttr API unavailable")
        callAttr.invoke(mainModule, "exec", arrayOf(runner))
        val outJson = callAttr.invoke(mainModule, "eval", arrayOf("_out")).toString()

        val obj = JsonUtil.json.parseToJsonElement(outJson) as kotlinx.serialization.json.JsonObject
        val ok = (obj["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        val stdout = (obj["stdout"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val stderr = (obj["stderr"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val error = (obj["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content

        // Harvest any matplotlib figures saved by user code into the artifacts root.
        val merged = buildString {
            append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr)
            }
            if (error != null) {
                if (isNotEmpty()) append('\n')
                append(error)
            }
        }.take(12_000)

        return SandboxResult(merged.ifBlank { "(no output)" }, if (ok) 0 else 1, !ok)
    }

    override suspend fun runNode(code: String, timeoutMs: Long): SandboxResult = withContext(Dispatchers.IO) {
        // QuickJS is an optional dependency; resolve it reflectively.
        val candidates = listOf(
            "app.cash.quickjs.QuickJs",
            "app.cash.quickjs.QuickJS",
            "org.quickjs.android.QuickJS",
            "com.dokar.quickjs.QuickJS"
        )
        for (className in candidates) {
            try {
                val clazz = Class.forName(className)
                val instance = runCatching { clazz.getMethod("create").invoke(null) }
                    .getOrElse { clazz.getDeclaredConstructor().newInstance() }
                val eval = clazz.methods.firstOrNull { (it.name == "evaluate" || it.name == "eval") && it.parameterTypes.size == 1 }
                if (eval != null) {
                    val result = eval.invoke(instance, code)
                    runCatching { clazz.getMethod("close").invoke(instance) }
                    return@withContext SandboxResult(result?.toString() ?: "(undefined)")
                }
            } catch (_: ClassNotFoundException) {
                // try next candidate
            } catch (e: Exception) {
                return@withContext SandboxResult("node error: ${e.message}", 1, true)
            }
        }
        SandboxResult(
            "JavaScript engine not bundled. Add a QuickJS Android dependency " +
                "(see README → LIGHT profile) or switch this task to the REMOTE profile.",
            exitCode = 127, isError = true
        )
    }

    override suspend fun readFile(path: String): SandboxResult = withContext(Dispatchers.IO) {
        try {
            val f = resolveInRoot(path)
            if (!f.exists()) SandboxResult("file not found: $path", 2, true)
            else SandboxResult(f.readText(Charsets.UTF_8).take(MAX_OUTPUT))
        } catch (e: Exception) {
            SandboxResult("read_file error: ${e.message}", 1, true)
        }
    }

    override suspend fun readFileBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val file = resolveInRoot(path)
        require(file.exists() && file.isFile) { "file not found: $path" }
        file.readBytes()
    }

    override suspend fun writeFile(path: String, content: String): SandboxResult = withContext(Dispatchers.IO) {
        try {
            val f = resolveInRoot(path)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            SandboxResult("wrote ${f.length()} bytes to ${f.relativeTo(rootDir)}")
        } catch (e: Exception) {
            SandboxResult("write_file error: ${e.message}", 1, true)
        }
    }

    override suspend fun listDir(path: String): SandboxResult = withContext(Dispatchers.IO) {
        try {
            val dir = if (path.isBlank() || path == ".") rootDir else resolveInRoot(path)
            if (!dir.exists()) SandboxResult("directory not found: $path", 2, true)
            else if (!dir.isDirectory) SandboxResult("not a directory: $path", 2, true)
            else {
                val sb = StringBuilder()
                sb.append(dir.canonicalPath).append('\n')
                dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?.forEach { f ->
                        sb.append(if (f.isDirectory) "d " else "f ")
                            .append(f.length().toString().padStart(10))
                            .append("  ").append(f.name).append('\n')
                    }
                SandboxResult(sb.toString().take(MAX_OUTPUT))
            }
        } catch (e: Exception) {
            SandboxResult("list_dir error: ${e.message}", 1, true)
        }
    }

    override suspend fun patchFile(path: String, search: String, replace: String): SandboxResult =
        withContext(Dispatchers.IO) {
            try {
                val f = resolveInRoot(path)
                if (!f.exists()) return@withContext SandboxResult("file not found: $path", 2, true)
                val src = f.readText(Charsets.UTF_8)
                val idx = src.indexOf(search)
                if (idx == -1) return@withContext SandboxResult("search string not found in $path", 3, true)
                f.writeText(src.substring(0, idx) + replace + src.substring(idx + search.length), Charsets.UTF_8)
                SandboxResult("patched $path")
            } catch (e: Exception) {
                SandboxResult("patch_file error: ${e.message}", 1, true)
            }
        }

    override suspend fun browserScreenshot(url: String, actions: List<String>): SandboxResult =
        SandboxResult(
            "browser is not available in LIGHT MODE. Switch this task to the REMOTE profile " +
                "to use headless Chromium via Playwright.",
            exitCode = 127, isError = true
        )

    override suspend fun close() { /* nothing to release on-device */ }

    companion object {
        private const val TAG = "LocalLightSandbox"
        private const val MAX_OUTPUT = 12_000
    }
}
