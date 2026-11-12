package com.anilili.data.provider.remote.config

import android.content.Context
import com.anilili.data.AppGraph
import com.anilili.data.provider.registry.UnifiedProviderRegistry
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import okhttp3.OkHttpClient

class RemoteProviderManager(
    context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true },
    private val validator: RemoteProviderConfigValidator = RemoteProviderConfigValidator.DEFAULT,
    customRegistry: UnifiedProviderRegistry? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val configStore = RemoteProviderConfigStore(appContext, json, validator)
    val fetcher = RemoteConfigFetcher(appContext, configStore, validator, json, okHttpClient)
    val registry: UnifiedProviderRegistry = customRegistry ?: UnifiedProviderRegistry(configStore, okHttpClient, json)

    var isInitialized = false

    fun init() {
        if (isInitialized) return
        isInitialized = true

        scope.launch {
            if (configStore.hasConfig()) {
                registry.refreshFromStore()
                DiagnosticsLog.event("RemoteProviderManager initialized with existing config")
            }
        }
    }

    suspend fun previewCustomSource(configUrl: String, trust: RemoteSourceTrust? = null): ConfigPreviewResult {
        return fetcher.previewConfig(configUrl, trust)
    }

    /** Installs a single source. Generates a sourceId from the config URL. */
    suspend fun addCustomSource(configUrl: String, trust: RemoteSourceTrust? = null): AddSourceResult {
        val sourceId = sourceIdFromUrl(configUrl)
        val config = fetchConfig(configUrl, trust)
            ?: return AddSourceResult.failure("Failed to fetch or parse config from $configUrl")

        val result = configStore.updateSource(
            sourceId = sourceId,
            config = config,
            configUrl = configUrl,
            trust = trust,
        )
        if (result.success) {
            registry.refreshFromStore()
            return AddSourceResult.success(
                isFirstConfig = result.isFirstConfig,
                wasRollback = result.wasRollback,
                providerCount = config.providers.size,
            )
        } else {
            return AddSourceResult.failure(result.errors.joinToString("; "))
        }
    }

    /** Removes a specific source by its sourceId. Backward compat: no args → removes "default". */
    suspend fun removeCustomSource(sourceId: String = "default", revokeTrust: Boolean = true): RemoveSourceResult {
        val current = configStore.getSourceConfig(sourceId)
        if (revokeTrust) {
            configStore.revokeSourceTrust(sourceId)
        }
        val removed = configStore.removeSource(sourceId)
        registry.refreshFromStore()
        return RemoveSourceResult.success(previousConfig = current)
    }

    /** Legacy full clear: removes all sources and clears the tombstone. */
    suspend fun revokeTrust(): Boolean {
        val cleared = configStore.clearConfig()
        registry.refreshFromStore()
        return cleared
    }

    /** Updates a single installed source by re-fetching its config URL. */
    suspend fun updateSource(sourceId: String): FetchResult {
        val entry = configStore.getInstalledSources().firstOrNull { it.sourceId == sourceId }
            ?: return FetchResult.failure("Source not found: $sourceId")
        val trust = configStore.getSourceTrust(sourceId)
        val config = fetchConfig(entry.configUrl, trust)
            ?: return FetchResult.failure("Failed to fetch updated config for $sourceId")

        val result = configStore.updateSource(
            sourceId = sourceId,
            config = config,
            configUrl = entry.configUrl,
            trust = trust,
        )
        if (result.success) {
            registry.refreshFromStore()
            return FetchResult(
                success = true,
                isFirstConfig = result.isFirstConfig,
                wasRollback = result.wasRollback,
                providerCount = config.providers.size,
                message = "Config updated for source $sourceId",
            )
        } else {
            return FetchResult.failure(result.errors.joinToString("; "))
        }
    }

    /** Enables or disables a source. Disabled sources are excluded from the registry. */
    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean): Boolean {
        val ok = configStore.setSourceEnabled(sourceId, enabled)
        if (ok) registry.refreshFromStore()
        return ok
    }

    fun getInstalledSources(): List<SourceEntry> = configStore.getInstalledSources()

    fun getTrust(): RemoteSourceTrust? = configStore.getTrust()

    /** Returns the trust for a specific source. */
    fun getSourceTrust(sourceId: String): RemoteSourceTrust? = configStore.getSourceTrust(sourceId)

    fun scheduleAutomaticUpdates(configUrl: String, intervalHours: Long = 24) {
        RemoteConfigUpdateWorker.schedulePeriodicUpdate(appContext, configUrl, intervalHours)
    }

    fun cancelAutomaticUpdates() {
        RemoteConfigUpdateWorker.cancelPeriodicUpdate(appContext)
    }

    fun triggerManualUpdate(configUrl: String) {
        RemoteConfigUpdateWorker.enqueueManualUpdate(appContext, configUrl)
    }

    fun shutdown() {
        scope.cancel()
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private suspend fun fetchConfig(configUrl: String, trust: RemoteSourceTrust?): RemoteProviderConfig? {
        return withContext(Dispatchers.IO) {
            try {
                val parsedOrigin = RemoteSourceTrust.parseOrigin(configUrl)
                    ?: return@withContext null
                if (parsedOrigin.isLoopback) return@withContext null

                val activeTrust = trust ?: configStore.getTrust()
                val isHttp = parsedOrigin.protocol == "http"
                val isPrivate = parsedOrigin.isPrivateOrLocal

                val isTrusted = if (isHttp) {
                    activeTrust != null && activeTrust.allowHttpConfig && activeTrust.isConfigFetchAllowed(configUrl)
                } else if (isPrivate) {
                    activeTrust != null && activeTrust.allowPrivateIpConfig
                } else true

                if (!isTrusted) return@withContext null

                val request = okhttp3.Request.Builder()
                    .url(configUrl)
                    .addHeader("Accept", "application/json")
                    .addHeader("User-Agent", "Anilili/unknown")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    kotlinx.serialization.json.Json
                        .decodeFromString<RemoteProviderConfig>(RemoteProviderConfig.serializer(), body)
                }
            } catch (e: Exception) {
                DiagnosticsLog.throwable("RemoteProviderManager fetchConfig failed: $configUrl", e)
                null
            }
        }
    }

    private fun sourceIdFromUrl(configUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(configUrl.toByteArray()).joinToString("") { "%02x".format(it) }
        return hash.take(16)
    }
}

data class AddSourceResult(
    val success: Boolean,
    val error: String? = null,
    val isFirstConfig: Boolean = false,
    val wasRollback: Boolean = false,
    val providerCount: Int = 0,
) {
    companion object {
        fun success(isFirstConfig: Boolean = false, wasRollback: Boolean = false, providerCount: Int = 0) =
            AddSourceResult(true, null, isFirstConfig, wasRollback, providerCount)
        fun failure(error: String) = AddSourceResult(false, error)
    }
}

data class RemoveSourceResult(
    val success: Boolean = true,
    val error: String? = null,
    val previousConfig: RemoteProviderList? = null,
) {
    companion object {
        fun success(previousConfig: RemoteProviderList?) = RemoveSourceResult(true, null, previousConfig)
        fun failure(error: String) = RemoveSourceResult(false, error)
    }
}
