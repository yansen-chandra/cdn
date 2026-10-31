package io.github.yansenchandra.converter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A converter setting: [category] is a quantity id or [CURRENCY]; [from]/[to] are unit ids or codes. */
data class UnitPair(val category: String, val from: String, val to: String) {
    fun encode() = "$category|$from|$to"

    companion object {
        fun decode(s: String) = s.split("|").takeIf { it.size == 3 }?.let { UnitPair(it[0], it[1], it[2]) }
    }
}

/**
 * One history entry. Typed conversions keep their [query] and are replayed in the smart input;
 * converter entries keep [pair] and [amount] and are replayed in the converter.
 */
data class HistoryEntry(val label: String, val result: String, val query: String? = null, val pair: UnitPair? = null, val amount: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("label", label).put("result", result)
        .apply { query?.let { put("query", it) } }
        .apply { pair?.let { put("pair", it.encode()) } }
        .apply { amount?.let { put("amount", it) } }

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            label = o.getString("label"),
            result = o.getString("result"),
            query = o.optString("query").ifEmpty { null },
            pair = o.optString("pair").ifEmpty { null }?.let { UnitPair.decode(it) },
            amount = o.optString("amount").ifEmpty { null },
        )
    }
}

/** Everything the app remembers between launches. The home-screen widget reads it too. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("converter", Context.MODE_PRIVATE)

    var category: String?
        get() = prefs.getString("category", null)
        set(v) = prefs.edit().putString("category", v).apply()

    var amount: String?
        get() = prefs.getString("amount", null)
        set(v) = prefs.edit().putString("amount", v).apply()

    fun pair(category: String): UnitPair? =
        prefs.getString("pair.$category", null)?.split("|")?.takeIf { it.size == 2 }?.let { UnitPair(category, it[0], it[1]) }

    fun savePair(p: UnitPair) = prefs.edit().putString("pair.${p.category}", "${p.from}|${p.to}").apply()

    var recentUnits: List<String>
        get() = list("recents")
        set(v) = putList("recents", v)

    var recentCategories: List<String>
        get() = list("recentCategories")
        set(v) = putList("recentCategories", v)

    var favourites: List<UnitPair>
        get() = list("favourites", "\n").mapNotNull { UnitPair.decode(it) }
        set(v) = putList("favourites", v.map { it.encode() }, "\n")

    var recentPairs: List<UnitPair>
        get() = list("recentPairs", "\n").mapNotNull { UnitPair.decode(it) }
        set(v) = putList("recentPairs", v.map { it.encode() }, "\n")

    var history: List<HistoryEntry>
        get() = runCatching {
            val a = JSONArray(prefs.getString("history", "[]"))
            (0 until a.length()).map { HistoryEntry.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(v) = prefs.edit().putString("history", JSONArray().also { a -> v.forEach { a.put(it.toJson()) } }.toString()).apply()

    /** The pair the widget shows: first favourite, else whatever the converter shows now. */
    fun widgetPair(): UnitPair? = favourites.firstOrNull() ?: category?.let { pair(it) }

    private fun list(key: String, sep: String = ","): List<String> =
        prefs.getString(key, "")!!.split(sep).filter { it.isNotBlank() }

    private fun putList(key: String, v: List<String>, sep: String = ",") = prefs.edit().putString(key, v.joinToString(sep)).apply()
}
