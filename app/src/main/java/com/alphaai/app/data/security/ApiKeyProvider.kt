package com.alphaai.app.data.security

/** Narrow secure-key read interface for provider clients; implementations must never log keys. */
interface ApiKeyProvider {
    fun get(secret: SecureKeyStore.Secret): String?
    fun has(secret: SecureKeyStore.Secret): Boolean
}
