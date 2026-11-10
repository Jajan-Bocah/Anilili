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
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

class RemoteProviderConfigStore(
    context: Context,
    private val json: Json,
    private val validator: RemoteProviderConfigValidator = RemoteProviderConfigValidator.DEFAULT,
) {
    private val appContext = context.applicationContext
    private val storeDirectory = File(appContext.filesDir, STORE_DIRECTORY).apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val currentConfigRef = AtomicReference<RemoteProviderList?>(null)
    private val lastKnownGoodRef = AtomicReference<RemoteProviderList?>(null)
    private val configOriginUrlRef = AtomicReference<String?>(null)

    private val currentFile = File(storeDirectory, CURRENT_FILE_NAME)
    private val backupFile = File(storeDirectory, BACKUP_FILE_NAME)
    private val originUrlFile = File(storeDirectory, ORIGIN_URL_FILE_NAME)
    private val lastKnownGoodFile = File(storeDirectory, LKG_FILE_NAME)

    init {
        scope.launch {
            loadFromDisk()
        }
    }

    suspend fun getCurrentConfig(): RemoteProviderList? = currentConfigRef.get()

    suspend fun getLastKnownGood(): RemoteProviderList? = lastKnownGoodRef.get()

    suspend fun getConfigOriginUrl(): String? = configOriginUrlRef.get()

    suspend fun hasConfig(): Boolean = currentConfigRef.get() != null

    suspend fun updateConfig(
        config: RemoteProviderConfig,
        originalUrl: String,
        etag: String? = null,
        lastModified: String? = null,
    ): UpdateResult {
        return writeLock.withLock {
            val validation = validator.validate(config, originalUrl)
            if (!validation.isValid) {
                return@withLock UpdateResult.failure(validation.errors)
            }

            val previousConfig = currentConfigRef.get()
            val previousLkg = lastKnownGoodRef.get()
            val previousOriginUrl = configOriginUrlRef.get()

            val newList = RemoteProviderList(
                config = config,
                originalUrl = originalUrl,
                fetchedAt = System.currentTimeMillis(),
                etag = etag,
                lastModified = lastModified,
            )

            val writeResult = writeToDisk(newList, previousConfig, previousLkg, previousOriginUrl)
            if (!writeResult.success) {
                return@withLock UpdateResult.failure(writeResult.errors)
            }

            currentConfigRef.set(newList)
            configOriginUrlRef.set(originalUrl)

            if (previousConfig == null) {
                lastKnownGoodRef.set(newList)
                writeLastKnownGood(newList)
            }

            return@withLock UpdateResult.success(
                isFirstConfig = previousConfig == null,
                wasRollback = false,
            )
        }
    }

    suspend fun rollbackToLastKnownGood(): RollbackResult {
        return writeLock.withLock {
            val lkg = lastKnownGoodRef.get() ?: return@withLock RollbackResult.failure("No last-known-good config available")
            val current = currentConfigRef.get()

            val writeResult = writeToDisk(lkg, current, lkg, configOriginUrlRef.get())
            if (!writeResult.success) {
                return@withLock RollbackResult.failure(writeResult.errors.firstOrNull() ?: "Write failed")
            }

            currentConfigRef.set(lkg)
            return@withLock RollbackResult.success(previousConfig = current)
        }
    }

    suspend fun clearConfig(): Boolean {
        return writeLock.withLock {
            currentConfigRef.set(null)
            configOriginUrlRef.set(null)
            currentFile.delete()
            originUrlFile.delete()
            true
        }
    }

    private suspend fun loadFromDisk() {
        try {
            val current = readCurrent()
            val lkg = readLastKnownGood()
            val originUrl = readOriginUrl()

            current?.let { currentConfigRef.set(it) }
            lkg?.let { lastKnownGoodRef.set(it) }
            originUrl?.let { configOriginUrlRef.set(it) }

            if (current == null && lkg != null) {
                currentConfigRef.set(lkg)
                writeCurrent(lkg)
            }
        } catch (e: Exception) {
            DiagnosticsLog.throwable("RemoteProviderConfigStore load failed", e)
        }
    }

    private fun readCurrent(): RemoteProviderList? = withContext(Dispatchers.IO) {
        readFile(currentFile)
    }

    private fun readLastKnownGood(): RemoteProviderList? = withContext(Dispatchers.IO) {
        readFile(lastKnownGoodFile)
    }

    private fun readOriginUrl(): String? = withContext(Dispatchers.IO) {
        if (!originUrlFile.isFile) return@withContext null
        FileInputStream(originUrlFile).use { input ->
            input.readBytes().decodeToString().trim().takeIf { it.isNotBlank() }
        }
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

    private suspend fun writeToDisk(
        newList: RemoteProviderList,
        previousConfig: RemoteProviderList?,
        previousLkg: RemoteProviderList?,
        previousOriginUrl: String?,
    ): WriteResult {
        return withContext(Dispatchers.IO) {
            val tempCurrent = File(storeDirectory, ".${CURRENT_FILE_NAME}.${System.nanoTime()}.tmp")
            val tempOrigin = File(storeDirectory, ".${ORIGIN_URL_FILE_NAME}.${System.nanoTime()}.tmp")
            val tempLkg = File(storeDirectory, ".${LKG_FILE_NAME}.${System.nanoTime()}.tmp")

            try {
                writeFile(tempCurrent, newList)
                writeFile(tempOrigin, newList.originalUrl)

                if (previousLkg == null) {
                    writeFile(tempLkg, newList)
                }

                atomicReplace(tempCurrent, currentFile)
                atomicReplace(tempOrigin, originUrlFile)
                if (previousLkg == null) {
                    atomicReplace(tempLkg, lastKnownGoodFile)
                }

                WriteResult.success()
            } catch (e: Exception) {
                tempCurrent.delete()
                tempOrigin.delete()
                tempLkg.delete()
                WriteResult.failure("Write failed: ${e.message}")
            }
        }
    }

    private fun writeCurrent(list: RemoteProviderList) = withContext(Dispatchers.IO) {
        val temp = File(storeDirectory, ".${CURRENT_FILE_NAME}.${System.nanoTime()}.tmp")
        try {
            writeFile(temp, list)
            atomicReplace(temp, currentFile)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun writeLastKnownGood(list: RemoteProviderList) = withContext(Dispatchers.IO) {
        val temp = File(storeDirectory, ".${LKG_FILE_NAME}.${System.nanoTime()}.tmp")
        try {
            writeFile(temp, list)
            atomicReplace(temp, lastKnownGoodFile)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun writeFile(file: File, data: Any) {
        storeDirectory.mkdirs()
        FileOutputStream(file).use { output ->
            val jsonString = when (data) {
                is RemoteProviderList -> json.encodeToString(RemoteProviderList.serializer(), data)
                is String -> data
                else -> throw IllegalArgumentException("Unsupported data type")
            }
            output.write(jsonString.toByteArray(Charsets.UTF_8))
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

    private companion object {
        const val STORE_DIRECTORY = "remote-provider-config"
        const val CURRENT_FILE_NAME = "current.json"
        const val BACKUP_FILE_NAME = "backup.json"
        const val LKG_FILE_NAME = "last-known-good.json"
        const val ORIGIN_URL_FILE_NAME = "origin-url.txt"
    }
}

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