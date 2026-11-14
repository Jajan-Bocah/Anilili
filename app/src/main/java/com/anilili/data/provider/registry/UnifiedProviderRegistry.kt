package com.anilili.data.provider.registry

import com.anilili.data.ProviderCatalog
import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import com.anilili.data.provider.remote.config.RemoteProviderConfig
import com.anilili.data.provider.remote.config.RemoteProviderConfigStore
import com.anilili.data.provider.remote.config.RemoteProviderEntry
import com.anilili.data.provider.remote.config.RemoteProviderList
import com.anilili.data.provider.remote.config.RemoteSourceTrust
import com.anilili.data.provider.remote.runtime.RemoteProviderRuntime
import com.anilili.data.provider.remote.runtime.WorkflowRemoteProviderRuntime
import com.anilili.data.provider.remote.runtime.RemoteProviderRuntimeFactory
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private var cachedConfigSnapshot: Map<String, RemoteProviderList>? = null

    private data class RuntimeEntry(
        val runtime: Any,
        val providerId: String,
        val sourceId: String,
    )

    suspend fun refreshFromStore() {
        val allConfigs = remoteConfigStore.getAllCurrentConfigs()
        val allTrusts = remoteConfigStore.getAllTrusts()

        // Build config snapshot for change detection (only enabled sources)
        val enabledConfigs = allConfigs.filter { (sourceId, _) -> remoteConfigStore.isSourceEnabled(sourceId) }
        if (enabledConfigs == cachedConfigSnapshot && cachedRuntimes.isNotEmpty()) return
        cachedConfigSnapshot = enabledConfigs

        val runtimes = mutableListOf<RuntimeEntry>()
        val factory = RemoteProviderRuntimeFactory(okHttpClient, json)

        enabledConfigs.forEach { (sourceId, list) ->
            val config = list.config
            val trust = allTrusts[sourceId]
            val securityConfig = config.security

            config.providers.forEach { entry ->
                val runtime = factory.createRuntime(entry, config, trust, securityConfig, sourceId)
                if (runtime != null) {
                    runtimes.add(RuntimeEntry(runtime, entry.id, sourceId))
                } else {
                    DiagnosticsLog.event("UnifiedProviderRegistry: no workflow runtime for ${entry.id} from source $sourceId")
                }
            }
        }

        cachedRuntimes = runtimes.map { it.runtime }
        DiagnosticsLog.event("UnifiedProviderRegistry refreshed: ${cachedRuntimes.size} remote providers from ${enabledConfigs.size} enabled sources")
    }

    suspend fun getRemoteRuntimes(): List<RemoteProviderRuntime> {
        refreshFromStore()
        return cachedRuntimes.filterIsInstance<RemoteProviderRuntime>()
    }

    suspend fun getWorkflowRuntimes(): List<WorkflowRemoteProviderRuntime> {
        refreshFromStore()
        return cachedRuntimes.filterIsInstance<WorkflowRemoteProviderRuntime>()
    }

    suspend fun getAllProviderIds(): List<String> {
        refreshFromStore()
        val builtIn = ProviderCatalog.selectableProviders(false)
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .map { it.entry.id }
        return (builtIn + remote).distinct()
    }

    suspend fun getSelectableProviders(hideAdult: Boolean): List<String> {
        refreshFromStore()
        val builtIn = ProviderCatalog.selectableProviders(hideAdult)
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .filter { !hideAdult || !it.resolvedCapabilities.adultOnly }
            .map { it.entry.id }
        return (builtIn + remote).distinct()
    }

    suspend fun isRemoteProvider(providerId: String): Boolean {
        refreshFromStore()
        return cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .any { it.entry.id == providerId }
    }

    suspend fun getRemoteRuntime(providerId: String): RemoteProviderRuntime? {
        refreshFromStore()
        return cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
    }

    suspend fun getWorkflowRuntime(providerId: String): WorkflowRemoteProviderRuntime? {
        refreshFromStore()
        return cachedRuntimes
            .filterIsInstance<WorkflowRemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
    }

    suspend fun getSourceType(providerId: String): UnifiedSourceType {
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

    suspend fun sourceVariants(providerId: String, category: Category): List<SourceVariant> {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.categoryVariants?.getOrDefault(category, emptyList())
            ?: ProviderCatalog.sourceVariants(providerId, category)
    }

    suspend fun declaredLanguage(providerId: String): String? {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.declaredLanguage
            ?: ProviderCatalog.declaredLanguage(providerId)
    }

    suspend fun providerForLanguage(language: String?): String? {
        refreshFromStore()
        val remoteMatch = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.resolvedCapabilities.declaredLanguage?.equals(language, ignoreCase = true) == true }
        if (remoteMatch != null) return remoteMatch.entry.id
        return ProviderCatalog.providerForLanguage(language)
    }

    suspend fun isEmbed(providerId: String): Boolean {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.requiresWebView == true
            || ProviderCatalog.isEmbed(providerId)
    }

    suspend fun requiresResolverWebView(providerId: String): Boolean {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.requiresWebView == true
            || ProviderCatalog.requiresResolverWebView(providerId)
    }

    suspend fun supportsOfflineDownload(providerId: String): Boolean {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.supportsDownload == true
            || ProviderCatalog.supportsOfflineDownload(providerId)
    }

    suspend fun hasShortLivedStreamUrls(providerId: String): Boolean {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.shortLivedStreamUrls == true
            || ProviderCatalog.hasShortLivedStreamUrls(providerId)
    }

    suspend fun isMultiAudio(providerId: String): Boolean {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.multiAudio == true
            || ProviderCatalog.multiAudioProviders.contains(providerId)
    }

    suspend fun dubCapableCategory(providerId: String, category: Category): Category {
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

    suspend fun isAdultOnly(providerId: String): Boolean {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.resolvedCapabilities?.adultOnly == true
            || ProviderCatalog.isAdultOnly(providerId)
    }

    suspend fun providersForCountry(providers: List<String>, countryOfOrigin: String?): List<String> {
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

    suspend fun defaultPriority(): List<String> {
        refreshFromStore()
        val builtIn = ProviderCatalog.defaultPriority
        val remotePriority = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .sortedByDescending { it.entry.priority }
            .map { it.entry.id }
        return (builtIn + remotePriority).distinct()
    }

    suspend fun defaultPriorityRank(providerId: String): Int {
        val priority = defaultPriority()
        return priority.indexOf(providerId).let { if (it >= 0) it else Int.MAX_VALUE }
    }

    suspend fun label(providerId: String): String {
        refreshFromStore()
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return remote?.entry?.name ?: ProviderCatalog.label(providerId)
    }

    suspend fun sortKey(providerId: String): Int {
        val priority = defaultPriority()
        return priority.indexOf(providerId).let { if (it >= 0) it else Int.MAX_VALUE }
    }

    suspend fun enabledProviders(providers: List<String>, disabled: Set<String>): List<String> {
        refreshFromStore()
        return ProviderCatalog.enabledProviders(providers, disabled)
    }

    suspend fun anivexaProvidersFor(hideAdult: Boolean): List<String> {
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

    suspend fun fastProviders(): Set<String> {
        refreshFromStore()
        val builtIn = ProviderCatalog.fastProviders
        val remote = cachedRuntimes
            .filterIsInstance<RemoteProviderRuntime>()
            .filter { it.entry.priority > 0 }
            .map { it.entry.id }
            .toSet()
        return builtIn + remote
    }

    suspend fun isFast(providerId: String): Boolean {
        return providerId in fastProviders()
    }

    suspend fun fingerprint(providers: List<String>): String {
        refreshFromStore()
        return ProviderCatalog.fingerprint(providers)
    }

    val webViewResolverProviders: Set<String>
        get() {
            val remote = cachedRuntimes
                .filterIsInstance<RemoteProviderRuntime>()
                .filter { it.resolvedCapabilities.requiresWebView }
                .map { it.entry.id }
                .toSet()
            return ProviderCatalog.webViewResolverProviders + remote
        }

    val multiAudioProviders: Set<String>
        get() {
            val remote = cachedRuntimes
                .filterIsInstance<RemoteProviderRuntime>()
                .filter { it.resolvedCapabilities.multiAudio }
                .map { it.entry.id }
                .toSet()
            return ProviderCatalog.multiAudioProviders + remote
        }

    val fastAnivexaProviders: List<String>
        get() {
            val remote = cachedRuntimes
                .filterIsInstance<RemoteProviderRuntime>()
                .filter { it.resolvedCapabilities.sourceType == com.anilili.data.provider.remote.runtime.ResolvedSourceType.ANIVEXA ||
                       it.resolvedCapabilities.sourceType == com.anilili.data.provider.remote.runtime.ResolvedSourceType.GENERIC_JSON }
                .filter { it.entry.priority > 0 }
                .map { it.entry.id }
            return ProviderCatalog.fastAnivexaProviders + remote
        }

    suspend fun unpublishedSubtitleVariant(providerId: String, category: Category): SourceVariant? {
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

    // Non-suspend accessors for Compose UI — reads cached state without refresh.
    // The init/add/remove flow already calls refreshFromStore().

    /** Cached remote provider IDs, safe to call from Compose without suspend. */
    fun cachedRemoteProviderIds(): List<String> =
        cachedRuntimes.filterIsInstance<RemoteProviderRuntime>().map { it.entry.id }

    /** Label for a provider, reading the cache. Falls back to ProviderCatalog for built-ins. */
    fun cachedLabel(providerId: String): String =
        cachedRuntimes.filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }?.entry?.name
            ?: ProviderCatalog.label(providerId)

    /** Whether a provider is fast, reading the cache. */
    fun cachedIsFast(providerId: String): Boolean {
        val remote = cachedRuntimes.filterIsInstance<RemoteProviderRuntime>()
            .firstOrNull { it.entry.id == providerId }
        return if (remote != null) remote.entry.priority > 0 else ProviderCatalog.isFast(providerId)
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