package com.alphaai.app.data.agent

import com.alphaai.app.domain.agent.AgentToolCall
import com.alphaai.app.domain.agent.ToolValidationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Pure validation layer run before any tool side effects. */
object AgentToolValidator {
    fun validate(call: AgentToolCall) {
        if (call.id.isBlank() || call.id.length > 100) throw ToolValidationException("invalid tool call id")
        val args = call.arguments
        fun required(name: String, max: Int = 16_000): String {
            val value = args[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?: throw ToolValidationException("${call.name}: missing string '$name'")
            if (value.length > max) throw ToolValidationException("${call.name}: '$name' exceeds $max characters")
            return value
        }
        fun safePath(): String {
            val path = required("path", 1_024)
            if (path.isBlank() || path.startsWith('/') || path.contains('\\') ||
                path.split('/').any { it == ".." } || path.any { it.code == 0 }) {
                throw ToolValidationException("${call.name}: path must be relative and stay within the sandbox")
            }
            return path
        }
        when (call.name) {
            "shell" -> required("command", 8_000)
            "python", "node" -> required("code", 32_000)
            "read_file", "list_dir" -> safePath()
            "write_file" -> { safePath(); required("content", 200_000) }
            "patch_file" -> { safePath(); required("search", 32_000); required("replace", 32_000) }
            "browser" -> {
                SsrfGuard.validate(required("url", 2_000))
                if (((args["actions"] as? JsonArray)?.size ?: 0) > 30) throw ToolValidationException("browser: max 30 actions")
            }
            "http_request" -> {
                SsrfGuard.validate(required("url", 2_000))
                val method = (args["method"] as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "GET"
                if (method !in setOf("GET", "POST", "HEAD")) throw ToolValidationException("http_request: method must be GET, POST, or HEAD")
                (args["body"] as? JsonPrimitive)?.contentOrNull?.let {
                    if (it.length > 16_000) throw ToolValidationException("http_request body exceeds 16000 characters")
                }
            }
            "web_search" -> required("query", 500)
            "finish" -> {
                required("summary", 8_000)
                val artifacts = args["artifacts"]
                if (artifacts != null && artifacts !is JsonArray) throw ToolValidationException("finish: artifacts must be an array")
                (artifacts as? JsonArray)?.forEach { item ->
                    val path = (item as? JsonPrimitive)?.contentOrNull ?: throw ToolValidationException("finish: artifact paths must be strings")
                    if (path.isBlank() || path.startsWith('/') || path.split('/').any { it == ".." }) throw ToolValidationException("finish: artifact path must be relative")
                }
            }
            else -> throw ToolValidationException("unknown tool '${call.name}'")
        }
    }
}
