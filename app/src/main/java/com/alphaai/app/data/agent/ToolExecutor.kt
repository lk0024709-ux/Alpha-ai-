package com.alphaai.app.data.agent

import com.alphaai.app.data.datastore.AppPreferences
import com.alphaai.app.data.sandbox.SandboxClient
import com.alphaai.app.data.sandbox.SandboxResult
import com.alphaai.app.domain.agent.AgentToolCall
import com.alphaai.app.domain.agent.ToolOutput
import com.alphaai.app.domain.agent.ToolValidationException
import com.alphaai.app.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolExecutor @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val preferences: AppPreferences
) {
    data class Execution(val output: ToolOutput, val shouldFinish: Boolean = false)

    /** Validate before invoking any side effects. */
    fun validate(call: AgentToolCall) = AgentToolValidator.validate(call)

    suspend fun execute(call: AgentToolCall, sandbox: SandboxClient): Execution {
        validate(call)
        val a = call.arguments
        fun s(k: String, default: String = ""): String =
            (a[k] as? JsonPrimitive)?.contentOrNull ?: default
        fun mapped(r: SandboxResult) = ToolOutput(
            text = r.output.take(12_000), isError = r.isError,
            artifactPath = if (!r.isError && call.name in setOf("write_file", "python", "node")) s("path").takeIf { it.isNotBlank() } else null
        )
        return when (call.name) {
            "shell" -> Execution(mapped(sandbox.runShell(s("command"), 120_000)))
            "python" -> Execution(mapped(sandbox.runPython(s("code"), 120_000)))
            "node" -> Execution(mapped(sandbox.runNode(s("code"), 60_000)))
            "read_file" -> Execution(mapped(sandbox.readFile(s("path"))))
            "write_file" -> Execution(mapped(sandbox.writeFile(s("path"), s("content"))))
            "list_dir" -> Execution(mapped(sandbox.listDir(s("path"))))
            "patch_file" -> Execution(mapped(sandbox.patchFile(s("path"), s("search"), s("replace"))))
            "browser" -> {
                val actions = (a["actions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                val result = sandbox.browserScreenshot(s("url"), actions)
                Execution(ToolOutput(result.output, result.isError, mimeType = "image/png"))
            }
            "http_request" -> Execution(httpRequest(a, sandbox.requiresHttpAllowlist))
            "web_search" -> Execution(webSearch(s("query")))
            "finish" -> {
                val summary = s("summary")
                val paths = (a["artifacts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                Execution(ToolOutput(JsonUtil.json.encodeToString(FinishPayload.serializer(), FinishPayload(summary, paths))), shouldFinish = true)
            }
            else -> throw ToolValidationException("unknown tool '${call.name}'")
        }
    }

    private suspend fun httpRequest(args: JsonObject, requireAllowlist: Boolean): ToolOutput {
        val rawUrl = args.string("url")
        val allowlist = if (requireAllowlist) preferences.httpAllowlist.first() else null
        val allowed = SsrfGuard.validate(rawUrl, allowlist)
        val method = args.string("method", "GET").uppercase()
        val url = allowed.uri.toString()
        val bodyText = args.string("body", "")
        val request = Request.Builder().url(url).method(
            method,
            when (method) {
                "POST" -> bodyText.toRequestBodySafe()
                else -> null
            }
        ).header("User-Agent", "AlphaAI/1.0 (user-authorized agent)").build()
        return try {
            withContext(Dispatchers.IO) {
                // Disable redirects to prevent an allowed public URL redirecting to a private target.
                val pinnedHost = allowed.uri.host.lowercase()
                val pinnedAddresses = allowed.addresses
                val client = okHttpClient.newBuilder()
                    .followRedirects(false).followSslRedirects(false)
                    .dns { host -> if (host.equals(pinnedHost, true)) pinnedAddresses else okhttp3.Dns.SYSTEM.lookup(host) }
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.source()?.let { source ->
                        val buffer = okio.Buffer()
                        source.read(buffer, 64 * 1024L)
                        buffer.readUtf8()
                    }.orEmpty()
                    if (response.code in 300..399) ToolOutput("HTTP ${response.code}: redirects are blocked by SSRF protection", true)
                    else ToolOutput("HTTP ${response.code} ${response.message}\n${text.take(8_000)}", !response.isSuccessful)
                }
            }
        } catch (e: Exception) {
            ToolOutput("http_request failed: ${e.message}", true)
        }
    }

    private suspend fun webSearch(query: String): ToolOutput {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("https://html.duckduckgo.com/html/?q=$encoded".toHttpUrl())
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AlphaAI/1.0")
            .get().build()
        return try {
            withContext(Dispatchers.IO) {
                val client = okHttpClient.newBuilder().followRedirects(false).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use ToolOutput("DuckDuckGo search HTTP ${response.code}", true)
                    val html = response.body?.string().orEmpty().take(1_000_000)
                    val snippets = Regex("(?is)<a[^>]+class=\\\"result__a\\\"[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>|<a[^>]+class=\\\"result__snippet\\\"[^>]*>(.*?)</a>")
                        .findAll(html).take(12).map { match ->
                            val url = match.groupValues[1]
                            val title = stripHtml(match.groupValues[2])
                            val snippet = stripHtml(match.groupValues[3])
                            listOf(title, url, snippet).filter { it.isNotBlank() }.joinToString("\n")
                        }.toList()
                    ToolOutput(if (snippets.isEmpty()) "No parsed results. Raw response begins:\n${stripHtml(html).take(3000)}" else snippets.joinToString("\n\n").take(8_000))
                }
            }
        } catch (e: Exception) {
            ToolOutput("web_search failed: ${e.message}", true)
        }
    }

    private fun stripHtml(raw: String): String = raw
        .replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace(Regex("\\s+"), " ").trim()

    private fun JsonObject.string(key: String, default: String = ""): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: default

    private fun String.toRequestBodySafe() =
        this.toRequestBodyCompat("application/json; charset=utf-8".toMediaTypeCompat())

    @kotlinx.serialization.Serializable
    data class FinishPayload(val summary: String, val artifacts: List<String>)

    companion object {
        val TOOL_NAMES = listOf("shell", "read_file", "write_file", "list_dir", "patch_file", "python", "node", "browser", "web_search", "http_request", "finish")
    }
}

private fun String.toRequestBodyCompat(mediaType: okhttp3.MediaType) =
    okhttp3.RequestBody.create(mediaType, this)
private fun String.toMediaTypeCompat() = okhttp3.MediaType.parse(this)!!
