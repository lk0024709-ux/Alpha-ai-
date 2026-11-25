package com.alphaai.app.data.remote.groq

import com.alphaai.app.data.remote.ChatRequest
import com.alphaai.app.data.security.ApiKeyProvider
import com.alphaai.app.data.security.SecureKeyStore
import com.alphaai.app.domain.model.ChatMessageDto
import com.alphaai.app.domain.model.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroqClientIntegrationTest {
    @Test fun postsOpenAiCompatibleMessagesAndAssemblesSseUsage() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"hello \"},\"finish_reason\":null}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"world\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}\n\n" +
                        "data: [DONE]\n\n"
                )
        )
        server.start()
        try {
            val client = GroqClient(
                OkHttpClient(),
                FakeKeys,
                server.url("/openai/v1").toString().trimEnd('/')
            )
            val deltas = mutableListOf<String>()
            val response = client.streamChat(
                ChatRequest(
                    model = "llama-3.3-70b-versatile",
                    messages = listOf(ChatMessageDto("user", "say hello")),
                    maxTokens = 32
                )
            ) { deltas += it }

            val posted = server.takeRequest()
            assertEquals("/openai/v1/chat/completions", posted.path)
            val requestJson = Json.parseToJsonElement(posted.body.readUtf8()).jsonObject
            assertEquals("llama-3.3-70b-versatile", requestJson["model"]?.jsonPrimitive?.content)
            assertEquals("say hello", requestJson["messages"]?.jsonArray?.first()?.jsonObject?.get("content")?.jsonPrimitive?.content)
            assertTrue(requestJson["stream"]?.jsonPrimitive?.content?.toBoolean() == true)
            assertEquals("hello world", response.content)
            assertEquals(listOf("hello ", "world"), deltas)
            assertEquals(12L, response.promptTokens)
            assertEquals(4L, response.completionTokens)
            assertEquals("stop", response.finishReason)
            assertEquals(Provider.GROQ, response.provider)
        } finally {
            server.shutdown()
        }
    }

    private object FakeKeys : ApiKeyProvider {
        override fun get(secret: SecureKeyStore.Secret): String? = if (secret == SecureKeyStore.Secret.GROQ) "test-key" else null
        override fun has(secret: SecureKeyStore.Secret): Boolean = get(secret) != null
    }
}
