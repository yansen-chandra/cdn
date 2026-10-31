package io.github.yansenchandra.converter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.yansenchandra.converter.engine.Rates
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exchange rates from the CDN file the daily job publishes. The phone never
 * calls the rates provider. The last good file is cached so currency works offline.
 */
class RatesRepository(private val cacheDir: File, private val urls: List<String>) {
    private val cache get() = File(cacheDir, "rates.json")

    /** Rates from the last successful download, or null on first launch. */
    suspend fun cached(): Rates? = withContext(Dispatchers.IO) {
        runCatching { Rates.parse(cache.readText()) }.getOrNull()
    }

    /** Age of the cached file in ms, or null if there is none. */
    fun cacheAgeMs(): Long? = cache.takeIf { it.exists() }?.let { System.currentTimeMillis() - it.lastModified() }

    /** Tries each URL in turn; on success the file replaces the cache. */
    suspend fun download(): Rates = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (url in urls) {
            try {
                val text = get(url)
                val rates = Rates.parse(text) // reject a bad file before it replaces a good one
                val tmp = File(cacheDir, "rates.json.tmp")
                tmp.writeText(text)
                tmp.renameTo(cache)
                return@withContext rates
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("No rates URL configured")
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode} from $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
