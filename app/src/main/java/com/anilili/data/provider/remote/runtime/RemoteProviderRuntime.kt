package com.anilili.data.provider.remote.runtime

import com.anilili.data.ProviderCatalog
import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import com.anilili.data.provider.remote.config.RemoteProviderConfig
import com.anilili.data.provider.remote.config.RemoteProviderEntry
import com.anilili.data.provider.remote.config.RemoteSourceType
import com.anilili.data.provider.remote.config.RemoteSubtitleVariant
import com.anilili.data.provider.remote.engine.security.RemoteRequestPolicy
import com.anilili.data.provider.remote.engine.workflow.WorkflowDefinition
import com.anilili.data.provider.remote.engine.workflow.WorkflowExecutor
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

interface RemoteProviderRuntime {
    val entry: RemoteProviderEntry
    val resolvedCapabilities: ResolvedCapabilities

    companion object {
        fun create(
            entry: RemoteProviderEntry,
            workflows: Map<String, WorkflowDefinition>,
            okHttpClient: OkHttpClient,
            json: Json,
            requestPolicy: RemoteRequestPolicy,
            slugStore: RemoteSlugStore = RemoteSlugStore(),
        ): WorkflowRemoteProviderRuntime {
            val baseRuntime = entry.toRuntime()
            val executor = WorkflowExecutor(okHttpClient, requestPolicy)
            return WorkflowRemoteProviderRuntime(baseRuntime, workflows, executor, json, slugStore)
        }
    }
}

data class BasicRemoteProviderRuntime(
    override val entry: RemoteProviderEntry,
    override val resolvedCapabilities: ResolvedCapabilities,
) : RemoteProviderRuntime

data class ResolvedCapabilities(
    val sourceType: ResolvedSourceType,
    val categoryVariants: Map<Category, List<SourceVariant>>,
    val supportsEpisodes: Boolean,
    val supportsSources: Boolean,
    val supportsSubtitles: Boolean,
    val supportsSkipTimes: Boolean,
    val supportsDownload: Boolean,
    val supportsSearch: Boolean,
    val requiresWebView: Boolean,
    val multiAudio: Boolean,
    val shortLivedStreamUrls: Boolean,
    val countryLocked: String?,
    val adultOnly: Boolean,
    val declaredLanguage: String?,
)

enum class ResolvedSourceType {
    MIRURO,
    ANIVEXA,
    GENERIC_JSON,
    GENERIC_HTML,
}

fun RemoteProviderEntry.toRuntime(): RemoteProviderRuntime {
    return BasicRemoteProviderRuntime(
        entry = this,
        resolvedCapabilities = resolveCapabilities(this),
    )
}

fun createWorkflowRuntime(
    entry: RemoteProviderEntry,
    workflows: Map<String, WorkflowDefinition>,
    okHttpClient: OkHttpClient,
    json: Json,
    requestPolicy: RemoteRequestPolicy,
    slugStore: RemoteSlugStore = RemoteSlugStore(),
): WorkflowRemoteProviderRuntime {
    val baseRuntime = entry.toRuntime()
    val executor = WorkflowExecutor(okHttpClient, requestPolicy)
    return WorkflowRemoteProviderRuntime(baseRuntime, workflows, executor, json, slugStore)
}

private fun RemoteProviderEntry.resolveCapabilities(entry: RemoteProviderEntry): ResolvedCapabilities {
    val sourceType = when (entry.source) {
        RemoteSourceType.MIRURO -> ResolvedSourceType.MIRURO
        RemoteSourceType.ANIVEXA -> ResolvedSourceType.ANIVEXA
        RemoteSourceType.GENERIC_JSON -> ResolvedSourceType.GENERIC_JSON
        RemoteSourceType.GENERIC_HTML -> ResolvedSourceType.GENERIC_HTML
    }

    val categoryVariants = mutableMapOf<Category, MutableList<SourceVariant>>()

    entry.subtitleVariants.forEach { variant ->
        val category = when (variant) {
            RemoteSubtitleVariant.DUB -> Category.DUB
            else -> Category.SUB
        }
        val existing = categoryVariants.getOrPut(category) { mutableListOf() }
        val sourceVariant = mapRemoteVariant(variant)
        if (sourceVariant !in existing) existing.add(sourceVariant)
    }

    if (categoryVariants.isEmpty()) {
        categoryVariants[Category.SUB] = mutableListOf(SourceVariant.GENERIC_SUB)
    }

    val declaredLanguage = entry.languages.firstOrNull()?.takeIf { it.isNotBlank() }

    return ResolvedCapabilities(
        sourceType = sourceType,
        categoryVariants = categoryVariants.mapValues { it.value.toList() },
        supportsEpisodes = entry.capabilities.supportsEpisodes,
        supportsSources = entry.capabilities.supportsSources,
        supportsSubtitles = entry.capabilities.supportsSubtitles,
        supportsSkipTimes = entry.capabilities.supportsSkipTimes,
        supportsDownload = entry.capabilities.supportsDownload,
        supportsSearch = entry.capabilities.supportsSearch,
        requiresWebView = entry.capabilities.requiresWebView,
        multiAudio = entry.capabilities.multiAudio,
        shortLivedStreamUrls = entry.capabilities.shortLivedStreamUrls,
        countryLocked = entry.capabilities.countryLocked,
        adultOnly = entry.capabilities.adultOnly,
        declaredLanguage = declaredLanguage,
    )
}

private fun mapRemoteVariant(variant: RemoteSubtitleVariant): SourceVariant = when (variant) {
    RemoteSubtitleVariant.HARD_SUB -> SourceVariant.HARD_SUB
    RemoteSubtitleVariant.SOFT_SUB -> SourceVariant.SOFT_SUB
    RemoteSubtitleVariant.GENERIC_SUB -> SourceVariant.GENERIC_SUB
    RemoteSubtitleVariant.DUB -> SourceVariant.DUB
}