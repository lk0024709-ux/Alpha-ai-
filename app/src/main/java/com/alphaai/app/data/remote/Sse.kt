package com.alphaai.app.data.remote

import com.alphaai.app.domain.model.Provider
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Drains an SSE stream, invoking [onData] for every `data:` payload.
 * Note: okhttp-sse invokes listener callbacks on its own dispatcher thread; we funnel
 * payloads through a channel and suspend the caller until the stream closes.
 *
 * Throws [ProviderHttpException] on HTTP error responses or transport failures.
 */
suspend fun collectSse(
    client: OkHttpClient,
    provider: Provider,
    request: Request,
    onData: suspend (String) -> Unit
) {
    val channel = Channel<String>(Channel.BUFFERED)
    var failure: String? = null
    var failureCode: Int? = null

    val source = EventSources.createFactory(client).newEventSource(
        request,
        object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (!response.isSuccessful) {
                    failureCode = response.code
                    failure = "HTTP ${response.code}"
                    channel.close()
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                channel.trySend(data)
            }

            override fun onClosed(eventSource: EventSource) {
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (failure == null) {
                    failureCode = response?.code
                    failure = t?.message ?: response?.let { "HTTP ${it.code}" } ?: "stream failed"
                }
                channel.close()
            }
        }
    )

    try {
        for (data in channel) {
            onData(data)
        }
    } finally {
        source.cancel()
    }

    failure?.let { throw ProviderHttpException(provider, failureCode, it) }
}
