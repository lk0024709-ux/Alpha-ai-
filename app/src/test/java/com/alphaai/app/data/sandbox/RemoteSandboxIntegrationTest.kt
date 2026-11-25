package com.alphaai.app.data.sandbox

import com.alphaai.app.data.security.ApiKeyProvider
import com.alphaai.app.data.security.SecureAuthTag
import com.alphaai.app.data.security.SecureKeyStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteSandboxIntegrationTest {
    @Test fun createsEphemeralSandboxExecutesCommandAndDeletesIt() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201).setBody("{\"sandboxID\":\"sbx-test\"}"))
        server.enqueue(MockResponse().setBody("{\"stdout\":\"hello from sandbox\\n\",\"exitCode\":0}"))
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        try {
            val api = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val auth = request.tag(SecureAuthTag::class.java)
                val key = auth?.let { FakeKeys.get(it.secret) }
                chain.proceed(if (auth == null || key == null) request else request.newBuilder().header(auth.headerName, key).build())
            }.build()
            val sandbox = RemoteSandbox(api, FakeKeys, server.url("/v1").toString().trimEnd('/'))

            val result = sandbox.runShell("echo hello")
            assertEquals(0, result.exitCode)
            assertEquals("hello from sandbox\n", result.output)

            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/v1/sandboxes", create.path)
            assertEquals("fake-e2b-key", create.getHeader("x-api-key"))
            assertTrue(Json.parseToJsonElement(create.body.readUtf8()).jsonObject.containsKey("templateID"))

            val exec = server.takeRequest()
            assertEquals("POST", exec.method)
            assertEquals("/v1/sandboxes/sbx-test/exec", exec.path)
            assertEquals("fake-e2b-key", exec.getHeader("x-api-key"))

            sandbox.close()
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/v1/sandboxes/sbx-test", delete.path)
        } finally {
            server.shutdown()
        }
    }

    private object FakeKeys : ApiKeyProvider {
        override fun get(secret: SecureKeyStore.Secret): String? =
            if (secret == SecureKeyStore.Secret.E2B) "fake-e2b-key" else null
        override fun has(secret: SecureKeyStore.Secret): Boolean = get(secret) != null
    }
}
