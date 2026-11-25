package com.alphaai.app.data.agent

import com.alphaai.app.domain.agent.AgentToolCall
import com.alphaai.app.domain.agent.ToolValidationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AgentToolValidatorTest {
    @Test fun acceptsWellFormedFileTool() {
        val call = AgentToolCall("call-1", "write_file", buildJsonObject {
            put("path", "reports/result.md")
            put("content", "# done")
        })
        assertDoesNotThrow { AgentToolValidator.validate(call) }
    }

    @Test fun rejectsPathTraversalAndAbsolutePaths() {
        listOf("../private", "/etc/passwd", "folder/../../secret").forEach { path ->
            val call = AgentToolCall("x", "read_file", buildJsonObject { put("path", path) })
            assertThrows(ToolValidationException::class.java) { AgentToolValidator.validate(call) }
        }
    }

    @Test fun rejectsUnknownToolsAndMissingFields() {
        assertThrows(ToolValidationException::class.java) {
            AgentToolValidator.validate(AgentToolCall("x", "delete_everything", buildJsonObject {}))
        }
        assertThrows(ToolValidationException::class.java) {
            AgentToolValidator.validate(AgentToolCall("x", "shell", buildJsonObject {}))
        }
    }

    @Test fun rejectsPrivateHttpDestinations() {
        val call = AgentToolCall("x", "http_request", buildJsonObject { put("url", "http://127.0.0.1/admin") })
        assertThrows(IllegalArgumentException::class.java) { AgentToolValidator.validate(call) }
    }
}
