package io.github.yansenchandra.converter

import android.util.Log
import io.github.yansenchandra.converter.engine.Rates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exchange rates: downloaded straight from Frankfurter on refresh, with this repo's
 * rates.json (published by converter/fetch_rates.py) as a backup source. The last good
 * rates are cached on the phone so currency works offline.
 */
class RatesRepository(
    private val cacheDir: File,
    private val providerUrl: String,
    private val backupUrls: List<String>,
) {
    /** [changed] is false when the download had nothing newer than the cache. */
    data class Refresh(val rates: Rates, val source: String, val changed: Boolean)

    private val cache get() = File(cacheDir, "rates.json")

    /** Rates from the last successful download, or null on first launch. */
    suspend fun cached(): Rates? = withContext(Dispatchers.IO) {
        runCatching { Rates.parse(cache.readText()) }.getOrNull()
    }

    /** Age of the cached file in ms, or null if there is none. */
    fun cacheAgeMs(): Long? = cache.takeIf { it.exists() }?.let { System.currentTimeMillis() - it.lastModified() }

    /** Frankfurter first, then the backup files; a good result replaces the cache unless it is older. */
    suspend fun download(): Refresh = withContext(Dispatchers.IO) {
        val (fresh, source) = fetchFirst()
        val old = runCatching { Rates.parse(cache.readText()) }.getOrNull()
        if (old != null && old.date > fresh.date) { // ISO dates compare as strings
            Log.i(TAG, "kept cached rates ${old.date}; $source only had ${fresh.date}")
            return@withContext Refresh(old, source, changed = false)
        }
        val tmp = File(cacheDir, "rates.json.tmp")
        tmp.writeText(fresh.toJson())
        if (!tmp.renameTo(cache)) throw IllegalStateException("Couldn't save rates")
        Log.i(TAG, "saved rates ${fresh.date} from $source (${fresh.rates.size} currencies)")
        Refresh(fresh, source, changed = old != fresh)
    }

    private fun fetchFirst(): Pair<Rates, String> {
        val errors = mutableListOf<String>()
        try {
            return Rates.fromProvider(get(providerUrl)) to "Frankfurter"
        } catch (e: Exception) {
            errors += "$providerUrl: ${e.message}"
        }
        for (url in backupUrls) {
            try {
                return Rates.parse(get(url)) to "backup"
            } catch (e: Exception) {
                errors += "$url: ${e.message}"
            }
        }
        Log.w(TAG, "rates download failed: $errors")
        throw IllegalStateException(errors.joinToString("; "))
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.useCaches = false
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "Rates"
    }
}
