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

    suspend fun fetchAndUpdate(configUrl: String, forceRefresh: Boolean = false): FetchResult {
        return withContext(Dispatchers.IO) {
            if (!isHttpsUrl(configUrl)) {
                return@withContext FetchResult.failure("Config URL must use HTTPS: $configUrl")
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

            val updateResult = store.updateConfig(config, configUrl, etag, lastModified)

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

    suspend fun manualUpdate(configUrl: String): FetchResult {
        return fetchAndUpdate(configUrl, forceRefresh = true)
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
        val forceRefresh = inputData.getBoolean(KEY_FORCE_REFRESH, false)

        val result = fetcher.fetchAndUpdate(configUrl, forceRefresh)

        return if (result.success) {
            Result.success()
        } else {
            DiagnosticsLog.event("RemoteConfigUpdateWorker failed: ${result.error}")
            if (result.isRetryable) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "RemoteProviderConfigUpdate"
        const val KEY_CONFIG_URL = "config_url"
        const val KEY_FORCE_REFRESH = "force_refresh"
        const val DEFAULT_INTERVAL_HOURS = 24L

        fun schedulePeriodicUpdate(
            context: Context,
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
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancelPeriodicUpdate(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun enqueueManualUpdate(context: Context, configUrl: String) {
            val request = androidx.work.OneTimeWorkRequest.Builder(RemoteConfigUpdateWorker::class.java)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString(KEY_CONFIG_URL, configUrl)
                        .putBoolean(KEY_FORCE_REFRESH, true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_manual",
                ExistingWorkPolicy.REPLACE,
                request,
            )
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