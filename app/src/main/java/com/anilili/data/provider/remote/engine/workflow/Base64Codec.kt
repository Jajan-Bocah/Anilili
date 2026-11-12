package com.anilili.data.provider.remote.engine.workflow

/**
 * Universal Base64 decoder supporting both standard JVM environments (unit tests, Java 17)
 * and Android runtimes down to API 22 (Fire OS 5) where java.util.Base64 may not be available.
 */
object Base64Codec {
    fun decodeToString(base64: String): String {
        val clean = base64.substringAfter("base64,").trim()
        if (clean.isEmpty()) return ""
        return try {
            java.util.Base64.getDecoder().decode(clean).decodeToString()
        } catch (e: Throwable) {
            try {
                android.util.Base64.decode(clean, android.util.Base64.DEFAULT).decodeToString()
            } catch (fallbackError: Throwable) {
                // If decoding fails, return original string rather than crashing
                base64
            }
        }
    }

    fun decodeBytes(base64: String): ByteArray {
        val clean = base64.substringAfter("base64,").trim()
        if (clean.isEmpty()) return ByteArray(0)
        return try {
            java.util.Base64.getDecoder().decode(clean)
        } catch (e: Throwable) {
            try {
                android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            } catch (fallbackError: Throwable) {
                ByteArray(0)
            }
        }
    }
}
