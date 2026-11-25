package com.alphaai.app.data.remote

import com.alphaai.app.domain.model.Provider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SseTest {
    @Test fun forwardsEventsAndClosesCleanly() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n")
        )
        server.start()
        try {
            val received = mutableListOf<String>()
            collectSse(OkHttpClient(), Provider.GROQ, Request.Builder().url(server.url("/stream")).build()) { received += it }
            assertEquals(listOf("{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}", "[DONE]"), received)
        } finally {
            server.shutdown()
        }
    }

    @Test fun surfacesHttpErrors() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        server.start()
        try {
            val error = org.junit.jupiter.api.Assertions.assertThrows(ProviderHttpException::class.java) {
                runBlocking { collectSse(OkHttpClient(), Provider.GROQ, Request.Builder().url(server.url("/stream")).build()) { } }
            }
            assertEquals(true, error.isRateLimited)
        } finally {
            server.shutdown()
        }
    }
}
