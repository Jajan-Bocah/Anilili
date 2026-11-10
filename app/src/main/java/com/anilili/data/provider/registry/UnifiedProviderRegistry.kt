package com.anilili.data.provider.registry

import com.anilili.data.ProviderCatalog
import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import com.anilili.data.provider.remote.config.RemoteProviderConfig
import com.anilili.data.provider.remote.config.RemoteProviderList
import com.anilili.data.provider.remote.runtime.RemoteProviderRuntime
import com.anilili.data.provider.remote.runtime.WorkflowRemoteProviderRuntime
import com.anilili.data.provider.remote.runtime.RemoteProviderRuntimeFactory
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class UnifiedProviderRegistry(
    private val remoteConfigStore: RemoteProviderConfigStore,
    private val okHttpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var cachedRuntimes: List<Any> = emptyList() // Can be RemoteProviderRuntime or WorkflowRemoteProviderRuntime
    private var cachedConfig: RemoteProviderList? = null

    fun refreshFromStore() {
        val config = remoteConfigStore.getCurrentConfig()
        if (config != cachedConfig) {
            cachedConfig = config
            val providers = config?.config?.providers ?: emptyList()
            val runtimes = mutableListOf<Any>()

            scope.launch {
                val factory = RemoteProviderRuntimeFactory(okHttpClient, json)

                providers.forEach { entry ->
                    val runtime = factory.createRuntime(entry, config.config!!)
                    if (runtime != null) {
                        runtimes.add(runtime)
                    } else {
                        // Fallback to basic runtime
                        runtimes.add(entry.toRuntime())
                    }
                }

                cachedRuntimes = runtimes
                DiagnosticsLog.event("UnifiedProviderRegistry refreshed: ${cachedRuntimes.size} remote providers")
            }
        }
    }

    private fun getRemoteRuntimes(): List<RemoteProviderRuntime> {
        refreshFromStore()
        return cachedRuntimes.filterIsInstance<RemoteProviderRuntime>()
    }

    private fun getWorkflowRuntimes(): List<WorkflowRemoteProviderRuntime> {
        refreshFromStore()
        return cachedRuntimes.filterIsInstance<WorkflowRemoteProviderRuntime>()
    }

    fun getAllProviderIds(): List<String> {
        refreshFromStore()
        val builtIn = ProviderCatalog.selectableProviders(false)
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .map { it.entry.id }
        return (builtIn + remote).distinct()
    }

    fun getSelectableProviders(hideAdult: Boolean): List<String> {
        refreshFromStore()
        val builtIn = ProviderCatalog.selectableProviders(hideAdult)
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .filter { !hideAdult || !it.resolvedCapabilities.adultOnly }
            .map { it.entry.id }
        return (builtIn + remote).distinct()
    }

    fun isRemoteProvider(providerId: String): Boolean {
        refreshFromStore()
        return cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .any { it.entry.id == providerId }
    }

    fun getRemoteRuntime(providerId: String): RemoteProviderRuntime? {
        refreshFromStore()
        return cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
    }

    fun getWorkflowRuntime(providerId: String): WorkflowRemoteProviderRuntime? {
        refreshFromStore()
        return cachedRuntimes
            .filterIsInstance<WorkflowRemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
    }

    fun getSourceType(providerId: String): UnifiedSourceType {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return if (remote != null) {
            when (remote.resolvedCapabilities.sourceType) {
                com.anilili.data.provider.remote.runtime.ResolvedSourceType.MIRURO -> UnifiedSourceType.MIRURO
                com.anilili.data.provider.remote.runtime.ResolvedSourceType.ANIVEXA -> UnifiedSourceType.ANIVEXA
                com.anilili.data.provider.remote.runtime.ResolvedSourceType.GENERIC_JSON -> UnifiedSourceType.REMOTE_JSON
                com.anilili.data.provider.remote.runtime.ResolvedSourceType.GENERIC_HTML -> UnifiedSourceType.REMOTE_HTML
            }
        } else {
            when (ProviderCatalog.sourceOf(providerId)) {
                ProviderCatalog.Source.MIRURO -> UnifiedSourceType.MIRURO
                ProviderCatalog.Source.ANIVEXA -> UnifiedSourceType.ANIVEXA
            }
        }
    }

    fun sourceVariants(providerId: String, category: Category): List<SourceVariant> {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.categoryVariants?.getOrDefault(category, emptyList())
            ?: ProviderCatalog.sourceVariants(providerId, category)
    }

    fun declaredLanguage(providerId: String): String? {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.declaredLanguage
            ?: ProviderCatalog.declaredLanguage(providerId)
    }

    fun providerForLanguage(language: String?): String? {
        refreshFromStore()
        val remoteMatch = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.resolvedCapabilities.declaredLanguage?.equals(language, ignoreCase = true) == true }
        if (remoteMatch != null) return remoteMatch.entry.id
        return ProviderCatalog.providerForLanguage(language)
    }

    fun isEmbed(providerId: String): Boolean {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.requiresWebView == true
            || ProviderCatalog.isEmbed(providerId)
    }

    fun requiresResolverWebView(providerId: String): Boolean {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.requiresWebView == true
            || ProviderCatalog.requiresResolverWebView(providerId)
    }

    fun supportsOfflineDownload(providerId: String): Boolean {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.supportsDownload == true
            || ProviderCatalog.supportsOfflineDownload(providerId)
    }

    fun hasShortLivedStreamUrls(providerId: String): Boolean {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.shortLivedStreamUrls == true
            || ProviderCatalog.hasShortLivedStreamUrls(providerId)
    }

    fun isMultiAudio(providerId: String): Boolean {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.multiAudio == true
            || ProviderCatalog.multiAudioProviders.contains(providerId)
    }

    fun dubCapableCategory(providerId: String, category: Category): Category {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        if (remote != null) {
            if (category == Category.DUB && remote.resolvedCapabilities.categoryVariants[Category.DUB]?.isNotEmpty() != true) {
                return Category.SUB
            }
            return category
        }
        return ProviderCatalog.dubCapableCategory(providerId, category)
    }

    fun isAdultOnly(providerId: String): Boolean {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.adultOnly == true
            || ProviderCatalog.isAdultOnly(providerId)
    }

    fun providersForCountry(providers: List<String>, countryOfOrigin: String?): List<String> {
        refreshFromStore()
        var filtered = providers
        val remoteProviders = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .map { it.entry.id }
        val remoteInList = providers.filter { it in remoteProviders }
        val builtInInList = providers.filter { it !in remoteProviders }

        val filteredRemote = remoteInList.filter { providerId ->
            val runtime = cachedRuntimes
                .filterIsInstance<RemoteProviderRuntime>()
                .firstOrNull { it.entry.id == providerId }
            val countryLocked = runtime?.resolvedCapabilities?.countryLocked
            countryLocked == null || countryOfOrigin == null || countryLocked.equals(countryOfOrigin, ignoreCase = true)
        }

        val filteredBuiltIn = ProviderCatalog.providersForCountry(builtInInList, countryOfOrigin)

        return (filteredBuiltIn + filteredRemote).distinct()
    }

    fun defaultPriority(): List<String> {
        refreshFromStore()
        val builtIn = ProviderCatalog.defaultPriority
        val remotePriority = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .sortedByDescending { it.entry.priority }
            .map { it.entry.id }
        return (builtIn + remotePriority).distinct()
    }

    fun defaultPriorityRank(providerId: String): Int {
        val priority = defaultPriority()
        return priority.indexOf(providerId).let { if (it >= 0) it else Int.MAX_VALUE }
    }

    fun label(providerId: String): String {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.entry.name ?: ProviderCatalog.label(providerId)
    }

    fun sortKey(providerId: String): Int {
        val priority = defaultPriority()
        return priority.indexOf(providerId).let { if (it >= 0) it else Int.MAX_VALUE }
    }

    fun enabledProviders(providers: List<String>, disabled: Set<String>): List<String> {
        refreshFromStore()
        return ProviderCatalog.enabledProviders(providers, disabled)
    }

    fun anivexaProvidersFor(hideAdult: Boolean): List<String> {
        refreshFromStore()
        val builtIn = ProviderCatalog.anivexaProvidersFor(hideAdult)
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .filter { !hideAdult || !it.resolvedCapabilities.adultOnly }
            .filter { it.resolvedCapabilities.sourceType == com.anilili.data.provider.remote.runtime.ResolvedSourceType.ANIVEXA ||
                       it.resolvedCapabilities.sourceType == com.anilili.data.provider.remote.runtime.ResolvedSourceType.GENERIC_JSON }
            .map { it.entry.id }
        return (builtIn + remote).distinct()
    }

    fun fastProviders(): Set<String> {
        refreshFromStore()
        val builtIn = ProviderCatalog.fastProviders
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .filter { it.entry.priority > 0 }
            .map { it.entry.id }
            .toSet()
        return builtIn + remote
    }

    fun isFast(providerId: String): Boolean {
        return providerId in fastProviders()
    }

    fun fingerprint(providers: List<String>): String {
        refreshFromStore()
        return ProviderCatalog.fingerprint(providers)
    }

    val webViewResolverProviders: Set<String>
        get() {
            refreshFromStore()
            val remote = cachedRuntimes
                .filterIsInstance<RemoteProviderRuntime>()
                .filter { it.resolvedCapabilities.requiresWebView }
                .map { it.entry.id }
                .toSet()
            return ProviderCatalog.webViewResolverProviders + remote
        }

    val multiAudioProviders: Set<String>
        get() {
            refreshFromStore()
            val remote = cachedRuntimes
                .filterIsInstance<RemoteProviderRuntime>()
                .filter { it.resolvedCapabilities.multiAudio }
                .map { it.entry.id }
                .toSet()
            return ProviderCatalog.multiAudioProviders + remote
        }

    val fastAnivexaProviders: List<String>
        get() {
            refreshFromStore()
            val remote = cachedRuntimes
                .filterIsInstance<RemoteProviderRuntime>()
                .filter { it.resolvedCapabilities.sourceType == com.anilili.data.provider.remote.runtime.ResolvedSourceType.ANIVEXA ||
                           it.resolvedCapabilities.sourceType == com.anilili.data.provider.remote.runtime.ResolvedSourceType.GENERIC_JSON }
                .filter { it.entry.priority > 0 }
                .map { it.entry.id }
            return ProviderCatalog.fastAnivexaProviders + remote
        }

    fun unpublishedSubtitleVariant(providerId: String, category: Category): SourceVariant? {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        if (remote != null) {
            val variants = remote.resolvedCapabilities.categoryVariants[category] ?: emptyList()
            return when (variants.singleOrNull()) {
                SourceVariant.HARD_SUB -> SourceVariant.SOFT_SUB
                SourceVariant.SOFT_SUB -> SourceVariant.HARD_SUB
                else -> null
            }
        }
        return ProviderCatalog.unpublishedSubtitleVariant(providerId, category)
    }

    fun shutdown() {
        scope.cancel()
    }
}

enum class UnifiedSourceType {
    MIRURO,
    ANIVEXA,
    REMOTE_JSON,
    REMOTE_HTML,
}