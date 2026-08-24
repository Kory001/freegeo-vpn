package org.freegeo.vpn.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class RegistryRepository(context: Context) {

    private val cacheFile = File(context.filesDir, "registry-cache.json")
    private val prefs = SecurePrefs(context)

    suspend fun fetch(): Result<Registry> = withContext(Dispatchers.IO) {
        val urlStr = prefs.registryUrl.ifBlank { DEFAULT_REGISTRY_URL }
        runCatching {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                if (conn.responseCode !in 200..299) {
                    error("HTTP ${conn.responseCode}")
                }
                val raw = conn.inputStream.bufferedReader().readText()
                RegistryParser.parse(raw).also { writeCache(raw) }
            } finally {
                conn.disconnect()
            }
        }.recoverCatching { e ->
            val cached = readCache() ?: throw e
            cached
        }
    }

    private fun readCache(): Registry? = runCatching {
        if (!cacheFile.exists()) return null
        RegistryParser.parse(cacheFile.readText())
    }.getOrNull()

    private fun writeCache(raw: String) {
        runCatching { cacheFile.writeText(raw) }
    }

    companion object {
        const val DEFAULT_REGISTRY_URL =
            "https://kory001.github.io/freegeo-vpn/registry.json"
    }
}
