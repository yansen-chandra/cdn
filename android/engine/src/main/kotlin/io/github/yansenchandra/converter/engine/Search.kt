package io.github.yansenchandra.converter.engine

import java.util.Currency
import java.util.Locale

/** A search hit: unit id or currency code, its domain ("unit" or "currency"), and the term that matched. */
data class Match(val id: String, val domain: String, val term: String)

/**
 * Finds units and currencies by id, symbol, name or alias; best match first.
 *
 * [extraCurrencies] are codes that have rates but no master-list entry (e.g. a bigger rates
 * feed): they are still found by code and by their name in [locale] or English.
 */
class Search(
    ml: MasterList,
    region: String = "*",
    extraCurrencies: Collection<String> = emptyList(),
    locale: Locale = Locale.getDefault(),
) {
    private data class Entry(val term: String, val id: String, val domain: String)

    private val index: List<Entry> = buildList {
        for (u in ml.units.values) {
            for (t in listOf(u.id, u.symbol, u.nameOne, u.nameOther) + u.aliases) add(Entry(key(t), u.id, "unit"))
        }
        for (code in (ml.currencyCodes + extraCurrencies).distinct()) {
            val terms = listOf(code) + currencyNames(code, locale) + ml.currencyAliasesByCode[code].orEmpty()
            for (t in terms) add(Entry(key(t), code, "currency"))
        }
        for (a in ml.ambiguousAliases) {
            val r = ml.resolve(a.alias, region, a.domain) ?: continue
            add(Entry(key(a.alias), r.id, r.domain))
        }
    }

    fun search(text: String, limit: Int = 50): List<Match> {
        val t = key(text)
        if (t.isEmpty()) return emptyList()
        return index.mapNotNull { e ->
            val score = when {
                e.term == t -> 0
                e.term.startsWith(t) -> 1
                e.term.contains(t) -> 2
                else -> return@mapNotNull null
            }
            Triple(score, e.term.length, e)
        }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .distinctBy { it.third.domain to it.third.id }
            .take(limit)
            .map { (_, _, e) -> Match(e.id, e.domain, e.term) }
    }

    private fun key(s: String) = s.trim().lowercase()
}

/** Currency names from the platform (Java/Android), in [locale] and in English. */
fun currencyNames(code: String, locale: Locale = Locale.getDefault()): List<String> {
    val c = runCatching { Currency.getInstance(code) }.getOrNull() ?: return emptyList()
    return listOf(c.getDisplayName(locale), c.getDisplayName(Locale.ENGLISH)).distinct()
}
