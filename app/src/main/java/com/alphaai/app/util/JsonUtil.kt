package com.alphaai.app.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

object JsonUtil {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Strips Markdown code fences and returns the first balanced {...} block, or null. */
    fun extractJsonObject(raw: String): String? {
        var text = raw.trim()
        // Remove ```json ... ``` fences if present
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fence.find(text)?.let { text = it.groupValues[1].trim() }

        val start = text.indexOf('{')
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escape -> escape = false
                c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun parseJsonObjectOrNull(raw: String): JsonObject? =
        extractJsonObject(raw)?.let { s -> runCatching { json.parseToJsonElement(s).jsonObject }.getOrNull() }

    fun pretty(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    /** Canonical JSON with sorted keys — used to detect repeated identical tool calls. */
    fun canonicalize(raw: String): String = try {
        val el = json.parseToJsonElement(raw)
        canonical(el)
    } catch (t: Throwable) {
        raw.trim()
    }

    private fun canonical(el: JsonElement): String = when (el) {
        is JsonObject -> el.toMap().toSortedMap().entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":${canonical(v)}"
        }
        is kotlinx.serialization.json.JsonArray ->
            el.jsonArray.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> el.toString()
    }

    fun stringField(obj: JsonObject, name: String): String? =
        obj[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    fun arrayField(obj: JsonObject, name: String): kotlinx.serialization.json.JsonArray? =
        obj[name]?.let { runCatching { it.jsonArray }.getOrNull() }
}
