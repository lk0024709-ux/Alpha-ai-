package com.alphaai.app.domain.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject
)

@Serializable
data class ToolOutput(
    val text: String,
    val isError: Boolean = false,
    val artifactPath: String? = null,
    val mimeType: String? = null
)

class ToolValidationException(message: String) : IllegalArgumentException(message)
