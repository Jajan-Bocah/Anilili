package com.anilili.data.provider.remote.config

import kotlinx.serialization.json.Json
import java.net.URL

open class RemoteProviderConfigValidator(
    private val currentSchemaVersion: Int = 1,
    private val minSupportedSchemaVersion: Int = 1,
    private val maxSupportedSchemaVersion: Int = 1,
    private val allowedOrigins: Set<String> = emptySet(),
    private val maxProviders: Int = 100,
    private val maxEndpointLength: Int = 2048,
    private val maxNameLength: Int = 100,
    private val maxIdLength: Int = 50,
) {

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
    ) {
        companion object {
            fun success(warnings: List<String> = emptyList()) = ValidationResult(true, emptyList(), warnings)
            fun failure(errors: List<String>, warnings: List<String> = emptyList()) = ValidationResult(false, errors, warnings)
        }
    }

    fun validate(config: RemoteProviderConfig, originalUrl: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        validateSchemaVersion(config.schemaVersion, errors, warnings)
        validateMetadata(config.metadata, originalUrl, errors, warnings)
        validateProviders(config.providers, errors, warnings)
        validateDuplicateIds(config.providers, errors)
        validateSecurity(config, originalUrl, errors, warnings)

        return if (errors.isEmpty()) {
            ValidationResult.success(warnings)
        } else {
            ValidationResult.failure(errors, warnings)
        }
    }

    private fun validateSchemaVersion(version: Int, errors: MutableList<String>, warnings: MutableList<String>) {
        if (version < minSupportedSchemaVersion) {
            errors.add("Schema version $version is below minimum supported version $minSupportedSchemaVersion")
        } else if (version > maxSupportedSchemaVersion) {
            errors.add("Schema version $version exceeds maximum supported version $maxSupportedSchemaVersion")
        } else if (version < currentSchemaVersion) {
            warnings.add("Schema version $version is older than current version $currentSchemaVersion; some features may be unavailable")
        }
    }

    private fun validateMetadata(metadata: ConfigMetadata, originalUrl: String, errors: MutableList<String>, warnings: MutableList<String>) {
        if (metadata.name.isBlank()) {
            errors.add("Metadata name is required")
        }
        if (metadata.version.isBlank()) {
            errors.add("Metadata version is required")
        }
        if (metadata.sourceUrl.isBlank()) {
            errors.add("Metadata sourceUrl is required")
        } else if (!isHttpsUrl(metadata.sourceUrl)) {
            errors.add("Metadata sourceUrl must use HTTPS: ${metadata.sourceUrl}")
        } else if (!urlsMatch(metadata.sourceUrl, originalUrl)) {
            warnings.add("Metadata sourceUrl (${metadata.sourceUrl}) differs from fetch URL ($originalUrl); preserving original fetch URL")
        }
        if (metadata.fetchedAt <= 0) {
            errors.add("Metadata fetchedAt must be a positive timestamp")
        }
        if (metadata.minAppVersion.isBlank()) {
            warnings.add("Metadata minAppVersion not specified; assuming compatibility with all versions")
        }
    }

    private fun validateProviders(providers: List<RemoteProviderEntry>, errors: MutableList<String>, warnings: MutableList<String>) {
        if (providers.isEmpty()) {
            warnings.add("No providers defined in config")
        }
        if (providers.size > maxProviders) {
            errors.add("Too many providers: ${providers.size} (max $maxProviders)")
        }

        providers.forEachIndexed { index, provider ->
            validateProvider(provider, index, errors, warnings)
        }
    }

    private fun validateProvider(provider: RemoteProviderEntry, index: Int, errors: MutableList<String>, warnings: MutableList<String>) {
        val prefix = "Provider[$index]"

        if (provider.id.isBlank()) {
            errors.add("$prefix: id is required")
        } else if (provider.id.length > maxIdLength) {
            errors.add("$prefix: id exceeds maximum length of $maxIdLength")
        } else if (!provider.id.matches(Regex("^[a-z0-9_-]+$"))) {
            errors.add("$prefix: id must contain only lowercase letters, numbers, underscores, and hyphens")
        }

        if (provider.name.isBlank()) {
            errors.add("$prefix: name is required")
        } else if (provider.name.length > maxNameLength) {
            errors.add("$prefix: name exceeds maximum length of $maxNameLength")
        }

        if (provider.endpoint.isBlank()) {
            errors.add("$prefix: endpoint is required")
        } else if (!isHttpsUrl(provider.endpoint)) {
            errors.add("$prefix: endpoint must use HTTPS: ${provider.endpoint}")
        } else if (provider.endpoint.length > maxEndpointLength) {
            errors.add("$prefix: endpoint exceeds maximum length of $maxEndpointLength")
        } else {
            try {
                URL(provider.endpoint)
            } catch (e: Exception) {
                errors.add("$prefix: endpoint is not a valid URL: ${provider.endpoint}")
            }
        }

        if (provider.source == RemoteSourceType.MIRURO && !provider.endpoint.contains("miruro", ignoreCase = true)) {
            warnings.add("$prefix: source is MIRURO but endpoint doesn't contain 'miruro'")
        }
        if (provider.source == RemoteSourceType.ANIVEXA && !provider.endpoint.contains("anivexa", ignoreCase = true)) {
            warnings.add("$prefix: source is ANIVEXA but endpoint doesn't contain 'anivexa'")
        }

        if (provider.priority < 0) {
            warnings.add("$prefix: priority should be non-negative")
        }
    }

    private fun validateDuplicateIds(providers: List<RemoteProviderEntry>, errors: MutableList<String>) {
        val seen = mutableSetOf<String>()
        providers.forEach { provider ->
            val lowerId = provider.id.lowercase()
            if (lowerId in seen) {
                errors.add("Duplicate provider id (case-insensitive): ${provider.id}")
            }
            seen.add(lowerId)
        }

        val systemIds = setOf(
            "bee", "sun", "pewe", "hop", "ally", "kiwi", "moo", "bonk", "knob",
            "nun", "bun", "cog", "twin", "telli",
            "anipm", "anichan", "zoko", "animepahe", "rareanimes", "anidbapp", "animeheaven",
            "xfdm", "anikoto", "kaa", "allanime", "animekai", "reanime", "anizone", "animegg",
            "anineko", "2dhive", "animedekho", "toonhub", "uniquestream", "animekhor", "kartoons",
            "animeav1", "rift", "hanime", "hentaihaven", "hstream", "onepace"
        )

        providers.forEach { provider ->
            if (provider.id.lowercase() in systemIds) {
                errors.add("Provider id '${provider.id}' conflicts with built-in provider; choose a different id")
            }
        }
    }

    private fun validateSecurity(config: RemoteProviderConfig, originalUrl: String, errors: MutableList<String>, warnings: MutableList<String>) {
        if (allowedOrigins.isNotEmpty()) {
            val urlOrigin = getOrigin(originalUrl)
            val urlHost = getHost(originalUrl)
            if (urlOrigin !in allowedOrigins && urlHost !in allowedOrigins && !allowedOrigins.any { urlHost.endsWith(".$it") }) {
                errors.add("Config origin '$urlOrigin' is not in allowed origins list")
            }
        }

        val securityConfig = config.security
        val allowedDomains = securityConfig?.allowedDomains?.toSet() ?: emptySet()

        config.providers.forEach { provider ->
            val endpointHost = getHost(provider.endpoint)
            if (allowedDomains.isNotEmpty() && endpointHost !in allowedDomains && !allowedDomains.any { endpointHost.endsWith(".$it") }) {
                warnings.add("Provider '${provider.id}' endpoint origin '$endpointHost' not in allowed origins")
            }
            if (provider.endpoint.contains("localhost", ignoreCase = true) || provider.endpoint.contains("127.0.0.1")) {
                errors.add("Provider '${provider.id}' endpoint points to localhost; not allowed")
            }
            // Private IP check is now done at runtime via DNS resolution in RemoteRequestPolicy
            // String-based check here is just a warning
            if (provider.endpoint.contains("10.", ignoreCase = true) || provider.endpoint.contains("192.168.", ignoreCase = true) || provider.endpoint.contains("172.16.", ignoreCase = true)) {
                warnings.add("Provider '${provider.id}' endpoint may point to private IP range (will be validated at runtime via DNS)")
            }
        }
    }

    private fun isHttpsUrl(url: String): Boolean {
        return try {
            URL(url).let { it.protocol.equals("https", ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }

    private fun urlsMatch(url1: String, url2: String): Boolean {
        return try {
            val u1 = URL(url1)
            val u2 = URL(url2)
            u1.host.equals(u2.host, ignoreCase = true) &&
            u1.path == u2.path &&
            u1.port == u2.port
        } catch (e: Exception) {
            false
        }
    }

    private fun getHost(url: String): String {
        return try {
            URL(url).host.lowercase()
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getOrigin(url: String): String {
        return try {
            URL(url).let { "${it.protocol}://${it.host}:${if (it.port != -1) it.port else (if (it.protocol == "https") 443 else 80)}" }
        } catch (e: Exception) {
            "unknown"
        }
    }

    companion object {
        val DEFAULT = RemoteProviderConfigValidator()
    }
}