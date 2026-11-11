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
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class RemoteProviderManager(
    context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true },
    private val validator: RemoteProviderConfigValidator = RemoteProviderConfigValidator.DEFAULT,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val configStore = RemoteProviderConfigStore(appContext, json, validator)
    val fetcher = RemoteConfigFetcher(appContext, configStore, validator, json, okHttpClient)
    val registry = UnifiedProviderRegistry(configStore, okHttpClient, json)

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

    suspend fun addCustomSource(configUrl: String): AddSourceResult {
        return fetcher.manualUpdate(configUrl).let { fetchResult ->
            if (fetchResult.success) {
                registry.refreshFromStore()
                AddSourceResult.success(
                    isFirstConfig = fetchResult.isFirstConfig,
                    wasRollback = fetchResult.wasRollback,
                    providerCount = fetchResult.providerCount,
                )
            } else {
                AddSourceResult.failure(fetchResult.error ?: "Unknown error")
            }
        }
    }

    suspend fun removeCustomSource(): RemoveSourceResult {
        val current = configStore.getCurrentConfig()
        val rollbackResult = configStore.rollbackToLastKnownGood()

        return if (rollbackResult.success) {
            registry.refreshFromStore()
            RemoveSourceResult.success(previousConfig = rollbackResult.previousConfig)
        } else {
            RemoveSourceResult.failure(rollbackResult.error ?: "Rollback failed")
        }
    }

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
    val success: Boolean,
    val error: String? = null,
    val previousConfig: RemoteProviderList? = null,
) {
    companion object {
        fun success(previousConfig: RemoteProviderList?) = RemoveSourceResult(true, null, previousConfig)
        fun failure(error: String) = RemoveSourceResult(false, error)
    }
}