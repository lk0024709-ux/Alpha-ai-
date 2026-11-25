package com.alphaai.app.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alphaai.app.domain.model.ModelPreference
import com.alphaai.app.domain.model.SandboxProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "app_settings")

/** Non-secret user preferences. Secrets live only in SecureKeyStore. */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val costCapKey = doublePreferencesKey("cost_cap_usd")
    private val defaultProfileKey = stringPreferencesKey("default_profile")
    private val defaultModelKey = stringPreferencesKey("default_model")
    private val themeModeKey = stringPreferencesKey("theme_mode") // SYSTEM|DARK|LIGHT
    private val httpAllowlistKey = stringPreferencesKey("http_allowlist") // comma-separated hosts
    private val hasCompletedOnboardingKey = booleanPreferencesKey("onboarding_done")

    val costCapUsd: Flow<Double> = context.dataStore.data.map { it[costCapKey] ?: 0.50 }
    val defaultProfile: Flow<SandboxProfile> = context.dataStore.data.map {
        runCatching { SandboxProfile.valueOf(it[defaultProfileKey] ?: "REMOTE") }.getOrDefault(SandboxProfile.REMOTE)
    }
    val defaultModel: Flow<ModelPreference> = context.dataStore.data.map {
        runCatching { ModelPreference.valueOf(it[defaultModelKey] ?: "AUTO") }.getOrDefault(ModelPreference.AUTO)
    }
    val themeMode: Flow<String> = context.dataStore.data.map { it[themeModeKey] ?: "DARK" }
    val httpAllowlist: Flow<Set<String>> = context.dataStore.data.map {
        (it[httpAllowlistKey] ?: "").split(',').map { h -> h.trim().lowercase() }.filter { h -> h.isNotEmpty() }.toSet()
    }
    val hasCompletedOnboarding: Flow<Boolean> = context.dataStore.data.map { it[hasCompletedOnboardingKey] ?: false }

    suspend fun setCostCap(value: Double) = context.dataStore.edit { it[costCapKey] = value.coerceIn(0.01, 0.50) }
    suspend fun setDefaultProfile(value: SandboxProfile) = context.dataStore.edit { it[defaultProfileKey] = value.name }
    suspend fun setDefaultModel(value: ModelPreference) = context.dataStore.edit { it[defaultModelKey] = value.name }
    suspend fun setThemeMode(value: String) = context.dataStore.edit { it[themeModeKey] = value }
    suspend fun setHttpAllowlist(hosts: Set<String>) = context.dataStore.edit { it[httpAllowlistKey] = hosts.joinToString(",") }
    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[hasCompletedOnboardingKey] = done }
}
