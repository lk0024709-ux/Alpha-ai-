package com.alphaai.app.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sole holder of API keys. Backed by EncryptedSharedPreferences with an Android
 * Keystore master key. Keys are never written to Room, logs, or any UI state
 * snapshot — callers read them transiently at request-build time only.
 */
@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext context: Context
) : ApiKeyProvider {
    enum class Secret(val storageKey: String) {
        GROQ("groq_api_key"),
        GEMINI("gemini_api_key"),
        HF("hf_api_key"),
        E2B("e2b_api_key"),
        SERPER("serper_api_key")
    }

    // security-crypto 1.0.0 exposes only the deprecated String-based overload;
    // the MasterKey.Builder(Context) API arrived in 1.1.0-alpha and is not used here.
    @Suppress("DEPRECATION")
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            FILE_NAME,
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun get(secret: Secret): String? =
        prefs.getString(secret.storageKey, null)?.takeIf { it.isNotBlank() }

    fun set(secret: Secret, value: String) {
        prefs.edit().putString(secret.storageKey, value.trim()).apply()
    }

    fun clear(secret: Secret) {
        prefs.edit().remove(secret.storageKey).apply()
    }

    override fun has(secret: Secret): Boolean = get(secret) != null

    /** UI-facing mask, e.g. "gsk_…wxyz". Never returns the full key. */
    fun masked(secret: Secret): String? {
        val k = get(secret) ?: return null
        if (k.length <= 8) return "••••"
        return k.take(4) + "…" + k.takeLast(4)
    }

    private companion object {
        // File name must match the exclusions in backup_rules.xml / data_extraction_rules.xml.
        const val FILE_NAME = "alphaai_secure_keys"
    }
}
