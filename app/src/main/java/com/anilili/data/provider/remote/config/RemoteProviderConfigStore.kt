package com.anilili.data.provider.remote.config

import android.content.Context
import com.anilili.data.cache.AppCache
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType
import okhttp3.RequestBody
import androidx.work.WorkManager
import androidx.work.PeriodicWorkRequest
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import java.util.concurrent.TimeUnit
import com.anilili.data.provider.remote.config.RemoteConfigUpdateWorker

class RemoteProviderConfigStore(
    context: Context,
    private val json: Json,
    private val validator: RemoteProviderConfigValidator = RemoteProviderConfigValidator.DEFAULT,
) {
    private val appContext = context.applicationContext
    private val storeDirectory = File(appContext.filesDir, STORE_DIRECTORY).apply { mkdirs() }
    private val sourcesDirectory = File(storeDirectory, SOURCES_DIRECTORY).apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    /** Per-source state, kept in-memory keyed by sourceId. */
    private val sourceConfigs = AtomicReference<Map<String, RemoteProviderList>>(emptyMap())
    private val sourceTrusts = AtomicReference<Map<String, RemoteSourceTrust?>>(emptyMap())
    private val sourceOrigins = AtomicReference<Map<String, String?>>(emptyMap())
    private val sourceEnabled = AtomicReference<Map<String, Boolean>>(emptyMap())

    /** Legacy single-source refs (backward compat — represents the first/default source). */
    private val currentConfigRef = AtomicReference<RemoteProviderList?>(null)
    private val lastKnownGoodRef = AtomicReference<RemoteProviderList?>(null)
    private val configOriginUrlRef = AtomicReference<String?>(null)
    private val trustRef = AtomicReference<RemoteSourceTrust?>(null)
    private val lastKnownGoodTrustRef = AtomicReference<RemoteSourceTrust?>(null)

    private val manifestFile = File(storeDirectory, MANIFEST_FILE_NAME)
    private val revokedOriginsFile = File(storeDirectory, REVOKED_ORIGINS_FILE_NAME)

    init {
        scope.launch {
            loadFromDisk()
        }
    }

    // ── Public API: multi-source ──────────────────────────────────────────

    /** Returns all installed source entries (including disabled). */
    suspend fun getInstalledSources(): List<SourceEntry> {
        val manifest = readManifest()
        return manifest.sources
    }

    /** Returns the current config for a single source, or null if not installed. */
    fun getSourceConfig(sourceId: String): RemoteProviderList? {
        return sourceConfigs.get()[sourceId]
    }

    /** Returns the trust grant for a single source, or null. */
    fun getSourceTrust(sourceId: String): RemoteSourceTrust? {
        return sourceTrusts.get()[sourceId]
    }

    /** Returns true if the source is installed and enabled. */
    fun isSourceEnabled(sourceId: String): Boolean {
        return sourceEnabled.get()[sourceId] == true
    }

    /** All current configs across sources (keyed by sourceId). */
    fun getAllCurrentConfigs(): Map<String, RemoteProviderList> {
        return sourceConfigs.get()
    }

    /** All trusts across sources (keyed by sourceId). */
    fun getAllTrusts(): Map<String, RemoteSourceTrust?> {
        return sourceTrusts.get()
    }

    /** Installs or updates a single source atomically. Returns failure if provider IDs collide. */
    suspend fun updateSource(
        sourceId: String,
        config: RemoteProviderConfig,
        configUrl: String,
        etag: String? = null,
        lastModified: String? = null,
        trust: RemoteSourceTrust? = null,
    ): UpdateResult {
        return withContext(Dispatchers.IO) {
            writeLock.withLock {
                // 1. Collision check against *other* installed sources
                val collision = sourceConfigs.get().entries
                    .filter { (id, _) -> id != sourceId }
                    .flatMap { (_, list) -> list.config.providers.map { it.id } }
                    .firstOrNull { id -> config.providers.any { it.id.equals(id, ignoreCase = true) } }
                if (collision != null) {
                    return@withLock UpdateResult.failure(
                        listOf("Provider ID collision: '$collision' already registered in another source")
                    )
                }

                // 2. Validate the config itself
                val validation = validator.validate(config, configUrl, trust)
                if (!validation.isValid) {
                    return@withLock UpdateResult.failure(validation.errors)
                }

                // 3. Origin tombstone check — cannot install into a revoked origin
                val origin = RemoteSourceTrust.parseOrigin(configUrl)?.origin ?: configUrl
                if (isOriginRevokedLocked(origin)) {
                    return@withLock UpdateResult.failure(
                        listOf("Origin '$origin' is revoked and cannot be re-installed without new user consent")
                    )
                }

                val previous = sourceConfigs.get()[sourceId]
                val newList = RemoteProviderList(
                    config = config,
                    originalUrl = configUrl,
                    fetchedAt = System.currentTimeMillis(),
                    etag = etag,
                    lastModified = lastModified,
                )

                val sourceDir = File(sourcesDirectory, sourceId)
                sourceDir.mkdirs()

                val result = writeSourceToDisk(sourceDir, sourceId, newList, previous, trust)
                if (!result.success) {
                    return@withLock UpdateResult.failure(result.errors)
                }

                // 4. Update in-memory state
                val updatedConfigs = sourceConfigs.get().toMutableMap().apply { put(sourceId, newList) }
                val updatedTrusts = sourceTrusts.get().toMutableMap().apply { put(sourceId, trust) }
                val updatedOrigins = sourceOrigins.get().toMutableMap().apply { put(sourceId, origin) }
                val updatedEnabled = sourceEnabled.get().toMutableMap().apply {
                    if (!containsKey(sourceId)) put(sourceId, true)
                }

                sourceConfigs.set(updatedConfigs)
                sourceTrusts.set(updatedTrusts)
                sourceOrigins.set(updatedOrigins)
                sourceEnabled.set(updatedEnabled)

                // 5. Update manifest
                val manifest = readManifest()
                val previousEntry = manifest.sources.firstOrNull { it.sourceId == sourceId }
                val entry = SourceEntry(
                    sourceId = sourceId,
                    configUrl = configUrl,
                    // Re-fetching must not re-enable a source the user disabled.
                    enabled = sourceEnabled.get()[sourceId] ?: true,
                    installedAt = previousEntry?.installedAt ?: System.currentTimeMillis(),
                    lastUpdated = System.currentTimeMillis(),
                    providerIds = config.providers.map { it.id },
                    autoUpdateEnabled = previousEntry?.autoUpdateEnabled ?: true,
                    updateIntervalHours = previousEntry?.updateIntervalHours ?: 24,
                    lastChecked = previousEntry?.lastChecked ?: 0,
                    lastError = null,
                )
                val updatedSources = manifest.sources.filter { it.sourceId != sourceId } + entry
                writeManifest(manifest.copy(sources = updatedSources))

                // 6. Backward-compat: if this is the first/default source, mirror into legacy refs
                if (sourceConfigs.get().size == 1) {
                    currentConfigRef.set(newList)
                    configOriginUrlRef.set(configUrl)
                    trustRef.set(trust)
                    lastKnownGoodRef.set(newList)
                    lastKnownGoodTrustRef.set(trust)
                }

                return@withLock UpdateResult.success(
                    isFirstConfig = previous == null,
                    wasRollback = false,
                )
            }
        }
    }

    /** Removes a single source completely (config, trust, manifest entry). */
    suspend fun removeSource(sourceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            writeLock.withLock {
                val sourceDir = File(sourcesDirectory, sourceId)
                deleteRecursively(sourceDir)

                val updatedConfigs = sourceConfigs.get().toMutableMap().apply { remove(sourceId) }
                val updatedTrusts = sourceTrusts.get().toMutableMap().apply { remove(sourceId) }
                val updatedOrigins = sourceOrigins.get().toMutableMap().apply { remove(sourceId) }
                val updatedEnabled = sourceEnabled.get().toMutableMap().apply { remove(sourceId) }

                sourceConfigs.set(updatedConfigs)
                sourceTrusts.set(updatedTrusts)
                sourceOrigins.set(updatedOrigins)
                sourceEnabled.set(updatedEnabled)

                val manifest = readManifest()
                writeManifest(manifest.copy(sources = manifest.sources.filter { it.sourceId != sourceId }))

                // Backward-compat: if we removed the default source, clear legacy refs
                if (sourceConfigs.get().isEmpty()) {
                    currentConfigRef.set(null)
                    configOriginUrlRef.set(null)
                    trustRef.set(null)
                    lastKnownGoodRef.set(null)
                    lastKnownGoodTrustRef.set(null)
                } else if (sourceId == getDefaultSourceId()) {
                    // Another source becomes default
                    val defaultId = sourceConfigs.get().keys.first()
                    val defaultConfig = sourceConfigs.get()[defaultId]!!
                    currentConfigRef.set(defaultConfig)
                    configOriginUrlRef.set(sourceOrigins.get()[defaultId])
                    trustRef.set(sourceTrusts.get()[defaultId])
                    lastKnownGoodRef.set(defaultConfig)
                    lastKnownGoodTrustRef.set(sourceTrusts.get()[defaultId])
                }

                true
            }
        }
    }

    /** Rollback a single source to its last-known-good. Tombstone origins are never restored. */
    suspend fun rollbackSource(sourceId: String): RollbackResult {
        return withContext(Dispatchers.IO) {
            writeLock.withLock {
                val config = sourceConfigs.get()[sourceId] ?: return@withLock RollbackResult.failure("No config for source $sourceId")
                val sourceDir = File(sourcesDirectory, sourceId)
                val lkgFile = File(sourceDir, LKG_FILE_NAME)
                if (!lkgFile.isFile) {
                    return@withLock RollbackResult.failure("No last-known-good for source $sourceId")
                }

                val lkg = readFile(lkgFile) ?: return@withLock RollbackResult.failure("LKG file unreadable for source $sourceId")
                val lkgOrigin = sourceOrigins.get()[sourceId] ?: config.originalUrl

                // Tombstone check: never restore a revoked origin
                if (isOriginRevokedLocked(lkgOrigin)) {
                    return@withLock RollbackResult.failure("Origin '$lkgOrigin' is revoked; rollback blocked")
                }

                val writeResult = writeSourceToDisk(sourceDir, sourceId, lkg, config, null)
                if (!writeResult.success) {
                    return@withLock RollbackResult.failure(writeResult.errors.firstOrNull() ?: "Write failed")
                }

                val updatedConfigs = sourceConfigs.get().toMutableMap().apply { put(sourceId, lkg) }
                sourceConfigs.set(updatedConfigs)

                // Backward-compat default mirror
                if (getDefaultSourceId() == sourceId) {
                    currentConfigRef.set(lkg)
                    configOriginUrlRef.set(lkgOrigin)
                    trustRef.set(sourceTrusts.get()[sourceId])
                }

                return@withLock RollbackResult.success(previousConfig = config)
            }
        }
    }

    /** Enable or disable a single source. Registry refreshes on next access. */
    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean): Boolean {
        return withContext(Dispatchers.IO) {
            writeLock.withLock {
                if (!sourceConfigs.get().containsKey(sourceId)) return@withLock false
                val updated = sourceEnabled.get().toMutableMap().apply { put(sourceId, enabled) }
                sourceEnabled.set(updated)
                // Persist the flag to the manifest so it survives an app restart.
                val manifest = readManifest()
                if (manifest.sources.any { it.sourceId == sourceId }) {
                    val updatedSources = manifest.sources.map { entry ->
                        if (entry.sourceId == sourceId) entry.copy(enabled = enabled) else entry
                    }
                    writeManifest(manifest.copy(sources = updatedSources))
                }
                // Also cancel/enable automatic updates
                if (enabled) {
                    val entry = manifest.sources.firstOrNull { it.sourceId == sourceId }
                    if (entry != null && entry.autoUpdateEnabled) {
                        schedulePeriodicUpdate(sourceId, entry.configUrl, entry.updateIntervalHours)
                    }
                } else {
                    cancelPeriodicUpdate(sourceId)
                }
                true
            }
        }
    }

    /** Schedule periodic update for a source via WorkManager. */
    fun schedulePeriodicUpdate(sourceId: String, configUrl: String, intervalHours: Long) {
        RemoteConfigUpdateWorker.schedulePeriodicUpdate(appContext, sourceId, configUrl, intervalHours)
    }

    /** Cancel periodic update for a source. */
    fun cancelPeriodicUpdate(sourceId: String) {
        RemoteConfigUpdateWorker.cancelPeriodicUpdate(appContext, sourceId)
    }

    /** Revoke trust for a single source. Adds its origin to the tombstone so rollback cannot restore it. */
    suspend fun revokeSourceTrust(sourceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            writeLock.withLock {
                val trust = sourceTrusts.get()[sourceId]
                val origin = sourceOrigins.get()[sourceId] ?: configOriginUrlRef.get()

                // Clear per-source trust
                val updatedTrusts = sourceTrusts.get().toMutableMap().apply { put(sourceId, null) }
                sourceTrusts.set(updatedTrusts)

                // Add origin to tombstone if we know it
                if (origin != null) {
                    val revoked = readRevokedOrigins().toMutableSet().apply { add(origin) }
                    writeRevokedOrigins(revoked)
                }

                // Backward-compat: if this was the default source's trust, clear legacy ref
                if (getDefaultSourceId() == sourceId) {
                    trustRef.set(null)
                }

                true
            }
        }
    }

    /** Checks whether an origin is in the revoked tombstone (survives rollback). */
    fun isOriginRevoked(origin: String): Boolean {
        return isOriginRevokedLocked(origin)
    }

    private fun isOriginRevokedLocked(origin: String): Boolean {
        return readRevokedOrigins().any { it.equals(origin, ignoreCase = true) }
    }

    // ── Public API: legacy single-source (backward compat) ────────────────

    /** Returns the default/first source's config (legacy `current.json` view). */
    fun getCurrentConfig(): RemoteProviderList? = currentConfigRef.get()

    fun getLastKnownGood(): RemoteProviderList? = lastKnownGoodRef.get()

    fun getConfigOriginUrl(): String? = configOriginUrlRef.get()

    fun getTrust(sourceId: String): RemoteSourceTrust? = sourceTrusts.get()[sourceId]

    /** Returns true if any source is installed. */
    fun hasConfig(): Boolean = sourceConfigs.get().isNotEmpty()

    /** Legacy single-source update: installs into `sources/default/` for backward compat. */
    suspend fun updateConfig(
        config: RemoteProviderConfig,
        originalUrl: String,
        etag: String? = null,
        lastModified: String? = null,
        trust: RemoteSourceTrust? = null,
    ): UpdateResult {
        return updateSource("default", config, originalUrl, etag, lastModified, trust)
    }

    /** Legacy rollback: rolls back the default source. */
    suspend fun rollbackToLastKnownGood(): RollbackResult {
        return rollbackSource("default")
    }

    /** Legacy clear: removes ALL sources and revokes all origins. */
    suspend fun clearConfig(): Boolean {
        return withContext(Dispatchers.IO) {
            writeLock.withLock {
                val allIds = sourceConfigs.get().keys.toList()
                allIds.forEach { removeSource(it) }
                writeRevokedOrigins(emptySet()) // clear tombstone on full clear
                true
            }
        }
    }

    suspend fun revokeTrust(): Boolean {
        return clearConfig()
    }

    // ── Public: manifest access ────────────────────────────────────────────────

    /** Reads the current manifest from disk. */
    suspend fun readManifest(): SourceManifest {
        return withContext(Dispatchers.IO) {
            if (!manifestFile.isFile) return@withContext SourceManifest()
            runCatching {
                FileInputStream(manifestFile).use { input ->
                    json.decodeFromString<SourceManifest>(
                        SourceManifest.serializer(),
                        input.readBytes().decodeToString()
                    )
                }
            }.onFailure {
                DiagnosticsLog.throwable("RemoteProviderConfigStore manifest read failed", it)
            }.getOrDefault(SourceManifest())
        }
    }

    /** Writes the manifest to disk atomically. */
    suspend fun writeManifest(manifest: SourceManifest) {
        withContext(Dispatchers.IO) {
            val temp = File(storeDirectory, ".${MANIFEST_FILE_NAME}.${System.nanoTime()}.tmp")
            try {
                writeFile(temp, manifest)
                atomicReplace(temp, manifestFile)
            } catch (e: Exception) {
                DiagnosticsLog.throwable("RemoteProviderConfigStore manifest write failed", e)
                temp.delete()
            }
        }
    }

    // ── Private: disk I/O ─────────────────────────────────────────────────

    private fun readRevokedOrigins(): List<String> {
        if (!revokedOriginsFile.isFile) return emptyList()
        return runCatching {
            val raw = revokedOriginsFile.readText()
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(raw)
        }.onFailure {
            DiagnosticsLog.throwable("RemoteProviderConfigStore revoked-origins read failed", it)
        }.getOrDefault(emptyList())
    }

    private fun writeRevokedOrigins(origins: Set<String>) {
        val temp = File(storeDirectory, ".${REVOKED_ORIGINS_FILE_NAME}.${System.nanoTime()}.tmp")
        try {
            val jsonArray = kotlinx.serialization.json.buildJsonArray {
                origins.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            }
            writeFile(temp, jsonArray.toString())
            atomicReplace(temp, revokedOriginsFile)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private suspend fun writeSourceToDisk(
        sourceDir: File,
        sourceId: String,
        newList: RemoteProviderList,
        previous: RemoteProviderList?,
        trust: RemoteSourceTrust? = null,
    ): WriteResult {
        val tempConfig = File(sourceDir, ".${CONFIG_FILE_NAME}.${System.nanoTime()}.tmp")
        val tempLkg = File(sourceDir, ".${LKG_FILE_NAME}.${System.nanoTime()}.tmp")
        val tempTrust = File(sourceDir, ".${TRUST_FILE_NAME}.${System.nanoTime()}.tmp")
        val tempLkgTrust = File(sourceDir, ".${LKG_TRUST_FILE_NAME}.${System.nanoTime()}.tmp")
        val tempOrigin = File(sourceDir, ".${ORIGIN_URL_FILE_NAME}.${System.nanoTime()}.tmp")

        try {
            writeFile(tempConfig, newList)
            writeFile(tempOrigin, newList.originalUrl)

            if (previous == null) {
                writeFile(tempLkg, newList)
            }

            if (trust != null) {
                writeFile(tempTrust, trust)
                if (previous == null) {
                    writeFile(tempLkgTrust, trust)
                }
            }

            atomicReplace(tempConfig, File(sourceDir, CONFIG_FILE_NAME))
            atomicReplace(tempOrigin, File(sourceDir, ORIGIN_URL_FILE_NAME))

            if (previous == null) {
                atomicReplace(tempLkg, File(sourceDir, LKG_FILE_NAME))
            }

            if (trust != null) {
                atomicReplace(tempTrust, File(sourceDir, TRUST_FILE_NAME))
                if (previous == null) {
                    atomicReplace(tempLkgTrust, File(sourceDir, LKG_TRUST_FILE_NAME))
                }
            } else {
                File(sourceDir, TRUST_FILE_NAME).delete()
            }

            return WriteResult.success()
        } catch (e: Exception) {
            tempConfig.delete()
            tempLkg.delete()
            tempTrust.delete()
            tempLkgTrust.delete()
            tempOrigin.delete()
            return WriteResult.failure("Write failed: ${e.message}")
        }
    }

    private suspend fun writeLastKnownGoodTrust(trust: RemoteSourceTrust?) {
        val defaultDir = File(sourcesDirectory, "default")
        if (!defaultDir.exists()) return
        val lkgTrustFile = File(defaultDir, LKG_TRUST_FILE_NAME)
        if (trust == null) {
            lkgTrustFile.delete()
            return
        }
        val temp = File(defaultDir, ".${LKG_TRUST_FILE_NAME}.${System.nanoTime()}.tmp")
        try {
            writeFile(temp, trust)
            atomicReplace(temp, lkgTrustFile)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private suspend fun writeLastKnownGood(list: RemoteProviderList) {
        val defaultDir = File(sourcesDirectory, "default")
        if (!defaultDir.exists()) return
        val lkgFile = File(defaultDir, LKG_FILE_NAME)
        val temp = File(defaultDir, ".${LKG_FILE_NAME}.${System.nanoTime()}.tmp")
        try {
            writeFile(temp, list)
            atomicReplace(temp, lkgFile)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private suspend fun writeCurrent(list: RemoteProviderList) {
        val defaultDir = File(sourcesDirectory, "default")
        if (!defaultDir.exists()) return
        val currentFile = File(defaultDir, CONFIG_FILE_NAME)
        val temp = File(defaultDir, ".${CONFIG_FILE_NAME}.${System.nanoTime()}.tmp")
        try {
            writeFile(temp, list)
            atomicReplace(temp, currentFile)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun writeFile(file: File, data: Any) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            val jsonString = when (data) {
                is RemoteProviderList -> json.encodeToString(RemoteProviderList.serializer(), data)
                is SourceManifest -> json.encodeToString(SourceManifest.serializer(), data)
                is RemoteSourceTrust -> json.encodeToString(RemoteSourceTrust.serializer(), data)
                is String -> data
                else -> throw IllegalArgumentException("Unsupported data type: ${data.javaClass.name}")
            }
            output.write(jsonString.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun atomicReplace(source: File, target: File) {
        runCatching { android.system.Os.rename(source.absolutePath, target.absolutePath) }
            .getOrElse {
                if (target.exists() && !target.delete()) error("Could not replace $target")
                if (!source.renameTo(target)) error("Could not publish $target")
            }
    }

    private fun deleteRecursively(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) deleteRecursively(file) else file.delete()
        }
        dir.delete()
    }

    private fun readFile(file: File): RemoteProviderList? {
        if (!file.isFile) return null
        return runCatching {
            FileInputStream(file).use { input ->
                json.decodeFromString<RemoteProviderList>(RemoteProviderList.serializer(), input.readBytes().decodeToString())
            }
        }.onFailure {
            DiagnosticsLog.throwable("RemoteProviderConfigStore read failed: ${file.name}", it)
            file.delete()
        }.getOrNull()
    }

    private fun readTrustFile(file: File): RemoteSourceTrust? {
        if (!file.isFile) return null
        return runCatching {
            FileInputStream(file).use { input ->
                json.decodeFromString<RemoteSourceTrust>(RemoteSourceTrust.serializer(), input.readBytes().decodeToString())
            }
        }.onFailure {
            DiagnosticsLog.throwable("RemoteProviderConfigStore trust read failed: ${file.name}", it)
            file.delete()
        }.getOrNull()
    }

    private fun getDefaultSourceId(): String {
        return sourceConfigs.get().keys.firstOrNull() ?: "default"
    }

    // ── Migration from legacy single-source ─────────────────────────────

    private suspend fun loadFromDisk() {
        val manifest = readManifest()
        val revoked = readRevokedOrigins()

        // Check for legacy single-source migration
        val legacyCurrent = File(storeDirectory, CURRENT_FILE_NAME)
        if (manifest.sources.isEmpty() && legacyCurrent.isFile) {
            // Migrate legacy current.json → sources/default/
            val legacyConfig = readFile(legacyCurrent)
            if (legacyConfig != null) {
                val legacyOrigin = File(storeDirectory, ORIGIN_URL_FILE_NAME)
                val originUrl = if (legacyOrigin.isFile) {
                    legacyOrigin.readText().trim()
                } else legacyConfig.originalUrl
                val legacyTrustFile = File(storeDirectory, TRUST_FILE_NAME)
                val legacyTrust = if (legacyTrustFile.isFile) {
                    readTrustFile(legacyTrustFile)
                } else null
                val defaultDir = File(sourcesDirectory, "default")
                defaultDir.mkdirs()
                writeSourceToDisk(defaultDir, "default", legacyConfig, null, legacyTrust)
                val origin = RemoteSourceTrust.parseOrigin(originUrl)?.origin ?: originUrl
                val entry = SourceEntry(
                    sourceId = "default",
                    configUrl = originUrl,
                    enabled = true,
                    installedAt = System.currentTimeMillis(),
                    lastUpdated = System.currentTimeMillis(),
                    providerIds = legacyConfig.config.providers.map { it.id },
                    autoUpdateEnabled = true,
                    updateIntervalHours = 24,
                    lastChecked = 0,
                    lastError = null,
                )
                writeManifest(manifest.copy(sources = listOf(entry)))
                // Set in-memory state
                val configs = mapOf("default" to legacyConfig)
                val trusts = mapOf("default" to legacyTrust)
                val origins = mapOf("default" to origin)
                val enabled = mapOf("default" to true)
                sourceConfigs.set(configs)
                sourceTrusts.set(trusts)
                sourceOrigins.set(origins)
                sourceEnabled.set(enabled)
                currentConfigRef.set(legacyConfig)
                configOriginUrlRef.set(originUrl)
                trustRef.set(legacyTrust)
                lastKnownGoodRef.set(legacyConfig)
                lastKnownGoodTrustRef.set(legacyTrust)
            }
        } else {
            // Normal multi-source load
            manifest.sources.forEach { entry ->
                val sourceDir = File(sourcesDirectory, entry.sourceId)
                val configFile = File(sourceDir, CONFIG_FILE_NAME)
                val trustFile = File(sourceDir, TRUST_FILE_NAME)
                val originFile = File(sourceDir, ORIGIN_URL_FILE_NAME)

                val config = if (configFile.isFile) readFile(configFile) else null
                if (config == null) return@forEach
                val trust = if (trustFile.isFile) readTrustFile(trustFile) else null
                val originUrl = if (originFile.isFile) originFile.readText().trim() else entry.configUrl
                val origin = RemoteSourceTrust.parseOrigin(originUrl)?.origin ?: originUrl

                val configs = sourceConfigs.get().toMutableMap().apply { put(entry.sourceId, config) }
                val trusts = sourceTrusts.get().toMutableMap().apply { put(entry.sourceId, trust) }
                val origins = sourceOrigins.get().toMutableMap().apply { put(entry.sourceId, origin) }
                val enabledMap = sourceEnabled.get().toMutableMap().apply { put(entry.sourceId, entry.enabled) }

                sourceConfigs.set(configs)
                sourceTrusts.set(trusts)
                sourceOrigins.set(origins)
                sourceEnabled.set(enabledMap)
            }
        }

        // Backward-compat default mirror
        val defaultConfig = sourceConfigs.get()[getDefaultSourceId()]
        currentConfigRef.set(defaultConfig)
        configOriginUrlRef.set(sourceOrigins.get()[getDefaultSourceId()])
        trustRef.set(sourceTrusts.get()[getDefaultSourceId()])
        lastKnownGoodRef.set(defaultConfig ?: currentConfigRef.get())
        lastKnownGoodTrustRef.set(sourceTrusts.get()[getDefaultSourceId()])

        // Persist revoked origins set (ensure file exists even if empty)
        if (revoked.isNotEmpty() && !revokedOriginsFile.isFile) {
            writeRevokedOrigins(revoked.toSet())
        }
    }

    private companion object {
        const val STORE_DIRECTORY = "remote-provider-config"
        const val SOURCES_DIRECTORY = "sources"
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val REVOKED_ORIGINS_FILE_NAME = "revoked-origins.json"
        const val CURRENT_FILE_NAME = "current.json"
        const val CONFIG_FILE_NAME = "config.json"
        const val BACKUP_FILE_NAME = "backup.json"
        const val LKG_FILE_NAME = "lkg.json"
        const val ORIGIN_URL_FILE_NAME = "origin-url.txt"
        const val TRUST_FILE_NAME = "trust.json"
        const val LKG_TRUST_FILE_NAME = "lkg-trust.json"
    }

}

/** Manifest of installed sources. */
@Serializable
data class SourceManifest(
    val sources: List<SourceEntry> = emptyList(),
)

@Serializable
data class SourceEntry(
    val sourceId: String,
    val configUrl: String,
    val enabled: Boolean = true,
    val installedAt: Long,
    val lastUpdated: Long,
    val providerIds: List<String> = emptyList(),
    // Auto-update metadata
    val autoUpdateEnabled: Boolean = true,
    val updateIntervalHours: Long = 24,
    val lastChecked: Long = 0,
    val lastError: String? = null,
)

data class UpdateResult(
    val success: Boolean,
    val errors: List<String>,
    val isFirstConfig: Boolean = false,
    val wasRollback: Boolean = false,
) {
    companion object {
        fun success(isFirstConfig: Boolean = false, wasRollback: Boolean = false) =
            UpdateResult(true, emptyList(), isFirstConfig, wasRollback)
        fun failure(errors: List<String>) = UpdateResult(false, errors)
    }
}

data class RollbackResult(
    val success: Boolean,
    val error: String? = null,
    val previousConfig: RemoteProviderList? = null,
) {
    companion object {
        fun success(previousConfig: RemoteProviderList?) = RollbackResult(true, null, previousConfig)
        fun failure(error: String) = RollbackResult(false, error)
    }
}

private data class WriteResult(
    val success: Boolean,
    val errors: List<String> = emptyList(),
) {
    companion object {
        fun success() = WriteResult(true)
        fun failure(error: String) = WriteResult(false, listOf(error))
    }
}
