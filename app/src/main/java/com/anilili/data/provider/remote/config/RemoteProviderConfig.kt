package com.anilili.data.provider.remote.config

import kotlinx.serialization.Serializable

@Serializable
data class RemoteProviderConfig(
    val schemaVersion: Int,
    val providers: List<RemoteProviderEntry>,
    val metadata: ConfigMetadata,
)

@Serializable
data class RemoteProviderEntry(
    val id: String,
    val name: String,
    val endpoint: String,
    val source: RemoteSourceType,
    val capabilities: ProviderCapabilities,
    val category: RemoteCategory = RemoteCategory.GENERAL,
    val languages: List<String> = emptyList(),
    val subtitleVariants: List<RemoteSubtitleVariant> = emptyList(),
    val priority: Int = 0,
    val enabled: Boolean = true,
)

@Serializable
enum class RemoteSourceType(val value: String) {
    MIRURO("miruro"),
    ANIVEXA("anivexa"),
    GENERIC_JSON("generic_json"),
    GENERIC_HTML("generic_html"),
}

@Serializable
data class ProviderCapabilities(
    val supportsEpisodes: Boolean = true,
    val supportsSources: Boolean = true,
    val supportsSubtitles: Boolean = false,
    val supportsSkipTimes: Boolean = false,
    val supportsDownload: Boolean = false,
    val supportsSearch: Boolean = false,
    val requiresWebView: Boolean = false,
    val multiAudio: Boolean = false,
    val shortLivedStreamUrls: Boolean = false,
    val countryLocked: String? = null,
    val adultOnly: Boolean = false,
)

@Serializable
enum class RemoteCategory(val value: String) {
    GENERAL("general"),
    ANIME("anime"),
    DONGHUA("donghua"),
    ADULT("adult"),
    SPECIAL("special"),
}

@Serializable
enum class RemoteSubtitleVariant(val value: String) {
    HARD_SUB("hsub"),
    SOFT_SUB("ssub"),
    GENERIC_SUB("sub"),
    DUB("dub"),
}

@Serializable
data class ConfigMetadata(
    val name: String,
    val description: String,
    val author: String?,
    val version: String,
    val sourceUrl: String,
    val fetchedAt: Long,
    val checksum: String?,
    val minAppVersion: String = "0.1.0",
    val maxAppVersion: String? = null,
)

@Serializable
data class RemoteProviderList(
    val config: RemoteProviderConfig,
    val originalUrl: String,
    val fetchedAt: Long,
    val etag: String? = null,
    val lastModified: String? = null,
)