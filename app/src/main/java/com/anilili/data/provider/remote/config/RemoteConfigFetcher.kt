package com.anilili.data.provider.remote.config

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.NetworkType
import androidx.work.Constraints
import com.anilili.BuildConfig
import com.anilili.data.AppGraph
import com.anilili.data.remote.ProviderResponseLimitInterceptor
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class RemoteConfigFetcher(
    private val context: Context,
    private val store: RemoteProviderConfigStore,
    private val validator: RemoteProviderConfigValidator = RemoteProviderConfigValidator.DEFAULT,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true },
    private val okHttpClient: OkHttpClient? = null,
) {
    private val client = okHttpClient ?: buildDefaultClient()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val MAX_RESPONSE_SIZE = 512L * 1024
        private const val FETCH_TIMEOUT_MS = 30_000L
    }

    private fun buildDefaultClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(ProviderResponseLimitInterceptor(MAX_RESPONSE_SIZE))
            .connectTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun fetchAndUpdate(
        configUrl: String,
        forceRefresh: Boolean = false,
        sourceId: String,
        trust: RemoteSourceTrust? = null,
    ): FetchResult {
        return withContext(Dispatchers.IO) {
            val activeTrust = trust ?: store.getTrust(sourceId)
            val parsedOrigin = RemoteSourceTrust.parseOrigin(configUrl)
                ?: return@withContext FetchResult.failure("Invalid config URL format: $configUrl")

            if (parsedOrigin.isLoopback) {
                return@withContext FetchResult.failure("Loopback addresses are strictly forbidden: $configUrl")
            }

            if (parsedOrigin.protocol == "http") {
                val isTrustedHttp = activeTrust != null && activeTrust.allowHttpConfig && activeTrust.isConfigFetchAllowed(configUrl)
                if (!isTrustedHttp) {
                    return@withContext FetchResult.failure("Config URL requires explicit user trust for HTTP: $configUrl")
                }
            } else if (parsedOrigin.protocol == "https") {
                if (parsedOrigin.isPrivateOrLocal) {
                    val isTrustedPrivate = activeTrust != null && activeTrust.allowPrivateIpConfig
                    if (!isTrustedPrivate) {
                        return@withContext FetchResult.failure("Local network HTTPS source requires explicit user trust: $configUrl")
                    }
                }
            } else {
                return@withContext FetchResult.failure("Only HTTP and HTTPS protocols are supported: $configUrl")
            }

            val request = Request.Builder()
                .url(configUrl)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Anilili/${AppGraph.isReady?.let { "v${BuildConfig.VERSION_NAME}" } ?: "unknown"}")
                .build()

            val response = runCatching { client.newCall(request).execute() }
                .onFailure { DiagnosticsLog.throwable("Remote config fetch failed for $configUrl", it) }
                .getOrNull()
                ?: return@withContext FetchResult.failure("Network request failed")

            val responseBody = response.body?.use { it.string() } ?: return@withContext FetchResult.failure("Empty response")

            if (!response.isSuccessful) {
                return@withContext FetchResult.failure("HTTP ${response.code}: ${response.message}")
            }

            val etag = response.header("ETag")
            val lastModified = response.header("Last-Modified")

            val currentConfig = store.getCurrentConfig()
            if (!forceRefresh && currentConfig != null) {
                val shouldSkip = (etag != null && etag == currentConfig.etag) ||
                    (lastModified != null && lastModified == currentConfig.lastModified)
                if (shouldSkip) {
                    return@withContext FetchResult.unchanged("Config unchanged (ETag/Last-Modified match)")
                }
            }

            val config = runCatching { json.decodeFromString<RemoteProviderConfig>(RemoteProviderConfig.serializer(), responseBody) }
                .onFailure { DiagnosticsLog.throwable("Remote config JSON parse failed", it) }
                .getOrNull()
                ?: return@withContext FetchResult.failure("Invalid JSON format")

            val updateResult = store.updateConfig(config, configUrl, etag, lastModified, trust = activeTrust)

            // Update lastChecked and lastError in SourceEntry
            val manifest = store.readManifest()
            val updatedSources = manifest.sources.map { entry ->
                if (entry.sourceId == sourceId) {
                    entry.copy(
                        lastChecked = System.currentTimeMillis(),
                        lastError = if (updateResult.success) null else updateResult.errors.joinToString("; ")
                    )
                } else entry
            }
            store.writeManifest(manifest.copy(sources = updatedSources))

            if (updateResult.success) {
                FetchResult.success(
                    isFirstConfig = updateResult.isFirstConfig,
                    wasRollback = updateResult.wasRollback,
                    providerCount = config.providers.size,
                )
            } else {
                FetchResult.failure(updateResult.errors.joinToString("; "))
            }
        }
    }

    suspend fun previewConfig(
        configUrl: String,
        trust: RemoteSourceTrust? = null,
        sourceId: String,
    ): ConfigPreviewResult {
        return withContext(Dispatchers.IO) {
            val parsedOrigin = RemoteSourceTrust.parseOrigin(configUrl)
                ?: return@withContext ConfigPreviewResult.failure("Invalid config URL format: $configUrl")

            if (parsedOrigin.isLoopback) {
                return@withContext ConfigPreviewResult.failure("Loopback addresses are strictly forbidden: $configUrl", isLoopback = true)
            }

            val isHttp = parsedOrigin.protocol == "http"
            val isPrivate = parsedOrigin.isPrivateOrLocal

            val activeTrust = trust ?: store.getTrust(sourceId)
            val isTrusted = if (isHttp) {
                activeTrust != null && activeTrust.allowHttpConfig && activeTrust.isConfigFetchAllowed(configUrl)
            } else if (isPrivate) {
                activeTrust != null && activeTrust.allowPrivateIpConfig
            } else {
                true // Public HTTPS
            }

            if (!isTrusted) {
                return@withContext ConfigPreviewResult.requiresApproval(
                    targetOrigin = parsedOrigin.origin,
                    targetHost = parsedOrigin.host,
                    targetPort = parsedOrigin.port,
                    requiresHttpConfig = isHttp,
                    requiresPrivateIpConfig = isPrivate,
                )
            }

            val request = Request.Builder()
                .url(configUrl)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Anilili/${AppGraph.isReady?.let { "v${BuildConfig.VERSION_NAME}" } ?: "unknown"}")
                .build()

            val response = runCatching { client.newCall(request).execute() }
                .onFailure { DiagnosticsLog.throwable("Remote config preview fetch failed for $configUrl", it) }
                .getOrNull()
                ?: return@withContext ConfigPreviewResult.failure("Network request failed")

            val responseBody = response.body?.use { it.string() }
                ?: return@withContext ConfigPreviewResult.failure("Empty response")

            if (!response.isSuccessful) {
                return@withContext ConfigPreviewResult.failure("HTTP ${response.code}: ${response.message}")
            }

            val config = runCatching { json.decodeFromString<RemoteProviderConfig>(RemoteProviderConfig.serializer(), responseBody) }
                .onFailure { DiagnosticsLog.throwable("Remote config preview JSON parse failed", it) }
                .getOrNull()
                ?: return@withContext ConfigPreviewResult.failure("Invalid JSON format")

            val validation = validator.validate(config, configUrl, activeTrust)
            if (!validation.isValid) {
                return@withContext ConfigPreviewResult.failure("Validation failed: ${validation.errors.joinToString("; ")}")
            }

            val discoveredHttpEndpoints = mutableListOf<String>()
            val discoveredPrivateIpEndpoints = mutableListOf<String>()

            config.providers.forEach { provider ->
                val epOrigin = RemoteSourceTrust.parseOrigin(provider.endpoint)
                if (epOrigin != null) {
                    if (epOrigin.protocol == "http") {
                        discoveredHttpEndpoints.add(epOrigin.origin)
                    }
                    if (epOrigin.isPrivateOrLocal) {
                        discoveredPrivateIpEndpoints.add(epOrigin.origin)
                    }
                }
            }

            ConfigPreviewResult.success(
                config = config,
                warnings = validation.warnings,
                discoveredHttpEndpoints = discoveredHttpEndpoints.distinct(),
                discoveredPrivateIpEndpoints = discoveredPrivateIpEndpoints.distinct(),
            )
        }
    }

    suspend fun manualUpdate(configUrl: String, sourceId: String, trust: RemoteSourceTrust? = null): FetchResult {
        return fetchAndUpdate(configUrl, forceRefresh = true, sourceId = sourceId, trust = trust)
    }

    private fun isHttpsUrl(url: String): Boolean {
        return try {
            java.net.URL(url).protocol.equals("https", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }
}

class RemoteConfigUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val fetcher: RemoteConfigFetcher by lazy {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        val store = RemoteProviderConfigStore(applicationContext, json)
        RemoteConfigFetcher(applicationContext, store, json = json)
    }

    override suspend fun doWork(): Result {
        val configUrl = inputData.getString(KEY_CONFIG_URL) ?: return Result.failure()
        val sourceId = inputData.getString(KEY_SOURCE_ID) ?: return Result.failure()
        val forceRefresh = inputData.getBoolean(KEY_FORCE_REFRESH, false)

        val result = fetcher.fetchAndUpdate(configUrl, forceRefresh, sourceId)

        return if (result.success) {
            Result.success()
        } else {
            DiagnosticsLog.event("RemoteConfigUpdateWorker failed for $sourceId: ${result.error}")
            if (result.isRetryable) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_PREFIX = "RemoteProviderConfigUpdate_"
        const val KEY_CONFIG_URL = "config_url"
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_FORCE_REFRESH = "force_refresh"
        const val DEFAULT_INTERVAL_HOURS = 24L

        private fun workName(sourceId: String) = "$WORK_NAME_PREFIX$sourceId"

        fun schedulePeriodicUpdate(
            context: Context,
            sourceId: String,
            configUrl: String,
            intervalHours: Long = DEFAULT_INTERVAL_HOURS,
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequest.Builder(
                RemoteConfigUpdateWorker::class.java,
                intervalHours, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString(KEY_CONFIG_URL, configUrl)
                        .putString(KEY_SOURCE_ID, sourceId)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                workName(sourceId),
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancelPeriodicUpdate(context: Context, sourceId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(sourceId))
        }

        fun cancelAllPeriodicUpdates(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.getWorkInfosByTagLiveData(WORK_NAME_PREFIX).observeForever { infos ->
                infos.forEach { info ->
                    workManager.cancelUniqueWork(info.id.toString())
                }
            }
        }

        fun enqueueManualUpdate(context: Context, sourceId: String, configUrl: String) {
            val request = androidx.work.OneTimeWorkRequest.Builder(RemoteConfigUpdateWorker::class.java)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString(KEY_CONFIG_URL, configUrl)
                        .putString(KEY_SOURCE_ID, sourceId)
                        .putBoolean(KEY_FORCE_REFRESH, true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${workName(sourceId)}_manual",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun restoreSchedulesFromSources(context: Context, sources: List<SourceEntry>) {
            sources.forEach { entry ->
                if (entry.enabled) {
                    schedulePeriodicUpdate(context, entry.sourceId, entry.configUrl)
                }
            }
        }
    }
}

data class FetchResult(
    val success: Boolean,
    val error: String? = null,
    val isRetryable: Boolean = false,
    val isFirstConfig: Boolean = false,
    val wasRollback: Boolean = false,
    val providerCount: Int = 0,
    val message: String? = null,
) {
    companion object {
        fun success(
            isFirstConfig: Boolean = false,
            wasRollback: Boolean = false,
            providerCount: Int = 0,
        ) = FetchResult(
            true,
            isFirstConfig = isFirstConfig,
            wasRollback = wasRollback,
            providerCount = providerCount,
            message = if (isFirstConfig) "Initial config loaded ($providerCount providers)" else "Config updated ($providerCount providers)",
        )
        fun failure(error: String, isRetryable: Boolean = false) = FetchResult(false, error, isRetryable)
        fun unchanged(message: String) = FetchResult(true, message = message)
    }
}

data class ConfigPreviewResult(
    val success: Boolean,
    val config: RemoteProviderConfig? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val requiresHttpConfig: Boolean = false,
    val requiresPrivateIpConfig: Boolean = false,
    val isLoopback: Boolean = false,
    val targetOrigin: String? = null,
    val targetHost: String? = null,
    val targetPort: Int = -1,
    val discoveredHttpEndpoints: List<String> = emptyList(),
    val discoveredPrivateIpEndpoints: List<String> = emptyList(),
) {
    companion object {
        fun success(
            config: RemoteProviderConfig,
            warnings: List<String> = emptyList(),
            discoveredHttpEndpoints: List<String> = emptyList(),
            discoveredPrivateIpEndpoints: List<String> = emptyList(),
        ) = ConfigPreviewResult(
            success = true,
            config = config,
            warnings = warnings,
            discoveredHttpEndpoints = discoveredHttpEndpoints,
            discoveredPrivateIpEndpoints = discoveredPrivateIpEndpoints,
        )

        fun requiresApproval(
            targetOrigin: String,
            targetHost: String,
            targetPort: Int,
            requiresHttpConfig: Boolean,
            requiresPrivateIpConfig: Boolean,
        ) = ConfigPreviewResult(
            success = false,
            requiresHttpConfig = requiresHttpConfig,
            requiresPrivateIpConfig = requiresPrivateIpConfig,
            targetOrigin = targetOrigin,
            targetHost = targetHost,
            targetPort = targetPort,
            error = "Explicit user trust required before fetching this source",
        )

        fun failure(error: String, isLoopback: Boolean = false) =
            ConfigPreviewResult(success = false, error = error, isLoopback = isLoopback)
    }
}