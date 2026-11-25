package com.alphaai.app.data.security

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/** Request tag declaring which secure-store key and auth header a request needs.
 * The secret itself is never placed in Request, tags, Room, logs, or UI state. */
data class SecureAuthTag(
    val secret: SecureKeyStore.Secret,
    val headerName: String,
    val prefix: String = ""
)

@Singleton
class SecureAuthInterceptor @Inject constructor(private val keyStore: SecureKeyStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val auth = request.tag(SecureAuthTag::class.java) ?: return chain.proceed(request)
        val key = keyStore.get(auth.secret)
            ?: throw IllegalStateException("Missing API key for ${auth.secret.name}")
        val authorized = request.newBuilder()
            .header(auth.headerName, auth.prefix + key)
            .build()
        return chain.proceed(authorized)
    }
}
