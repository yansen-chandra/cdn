package io.github.yansenchandra.converter

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import io.github.yansenchandra.converter.engine.ConversionError
import io.github.yansenchandra.converter.engine.MasterList
import io.github.yansenchandra.converter.engine.Outcome
import io.github.yansenchandra.converter.engine.REFERENCE_ONLY
import io.github.yansenchandra.converter.engine.Rates
import io.github.yansenchandra.converter.engine.convertCurrency
import io.github.yansenchandra.converter.engine.fastParse
import io.github.yansenchandra.converter.engine.fmt
import io.github.yansenchandra.converter.engine.parseValue
import io.github.yansenchandra.converter.engine.process
import io.github.yansenchandra.converter.engine.referenceOnly
import java.text.DecimalFormatSymbols
import java.util.Currency
import java.util.Locale

const val CURRENCY = "currency"

/** Something the user can pick: a unit id or a currency code. [matched] is the alias that matched a search. */
data class Choice(val id: String, val domain: String, val label: String, val detail: String, val matched: String)

/** One result row: [value] is what gets copied. */
data class Line(val value: String, val unit: String, val label: String)

data class Manual(val main: Line? = null, val others: List<Line> = emptyList(), val error: String? = null, val notes: List<String> = emptyList())

data class Smart(val lines: List<Line> = emptyList(), val notes: List<String> = emptyList(), val message: String? = null)

enum class RatesState { Idle, Loading, Failed }

class ConverterViewModel(app: Application) : AndroidViewModel(app) {
    val ml = MasterList.parse(app.assets.open("master-list.json").bufferedReader().use { it.readText() })
    val region: String = Locale.getDefault().country.uppercase().ifBlank { "*" }
    val homeCurrency: String = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull()
        ?.takeIf { it in ml.currencyCodes } ?: "USD"

    private val prefs = app.getSharedPreferences("converter", Context.MODE_PRIVATE)
    private val ratesRepo = RatesRepository(app.filesDir, BuildConfig.FRANKFURTER_URL, BuildConfig.RATES_URLS.split(" "))

    val categories: List<Pair<String, String>> = ml.quantities.values.map { it.id to it.name } + (CURRENCY to "Currency")

    var query by mutableStateOf("")
        private set
    var category by mutableStateOf(prefs.getString("category", null)?.takeIf { c -> categories.any { it.first == c } } ?: "length")
        private set
    var amount by mutableStateOf(prefs.getString("amount", null) ?: "1")
        private set
    var from by mutableStateOf("")
        private set
    var to by mutableStateOf("")
        private set
    var rates by mutableStateOf<Rates?>(null)
        private set
    var ratesState by mutableStateOf(RatesState.Idle)
        private set

    /** Result of the last refresh, shown under the currency converter. */
    var ratesMessage by mutableStateOf<String?>(null)
        private set
    private var recents by mutableStateOf(prefs.getString("recents", "")!!.split(",").filter { it.isNotBlank() })

    init {
        loadPair(category)
        viewModelScope.launch {
            rates = ratesRepo.cached()
            val age = ratesRepo.cacheAgeMs()
            if (rates == null || age == null || age > REFRESH_AFTER_MS) refreshRates()
        }
    }

    // ---------------- actions ---------------------------------------------

    fun onQuery(text: String) {
        query = text
    }

    fun onAmount(text: String) {
        amount = text
        prefs.edit().putString("amount", text).apply()
    }

    fun selectCategory(c: String) {
        if (c == category) return
        category = c
        prefs.edit().putString("category", c).apply()
        loadPair(c)
    }

    fun swap() = setPair(to, from)

    /** Picking a unit from another quantity (via search) switches category. */
    fun pick(choice: Choice, side: Side) {
        val c = if (choice.domain == CURRENCY) CURRENCY else ml.units.getValue(choice.id).quantity
        if (c != category) {
            category = c
            prefs.edit().putString("category", c).apply()
            val (f, t) = defaultPair(c)
            val other = if (side == Side.From) t else f
            val fallback = options(c).first { it != choice.id }
            val o = if (other == choice.id) fallback else other
            if (side == Side.From) setPair(choice.id, o) else setPair(o, choice.id)
        } else if (side == Side.From) {
            setPair(choice.id, if (to == choice.id) from else to)
        } else {
            setPair(if (from == choice.id) to else from, choice.id)
        }
        recents = (listOf(choice.id) + recents.filter { it != choice.id }).take(MAX_RECENTS)
        prefs.edit().putString("recents", recents.joinToString(",")).apply()
    }

    /** Copies a smart-input conversion into the manual converter below. */
    fun applySmart() {
        val req = fastParse(query) ?: return
        val src = ml.resolve(req.parts[0].unit, region) ?: return
        val targetQ = if (src.domain == "unit") ml.units.getValue(src.id).quantity else null
        val dst = req.to.firstNotNullOfOrNull { ml.resolve(it, region, src.domain, targetQ) }
        val srcId = if (dst != null && src.domain == "unit") {
            ml.resolve(req.parts[0].unit, region, preferQuantity = ml.units.getValue(dst.id).quantity)!!.id
        } else {
            src.id
        }
        val c = if (src.domain == "currency") CURRENCY else ml.units.getValue(srcId).quantity
        category = c
        prefs.edit().putString("category", c).apply()
        val other = dst?.id ?: defaultPair(c).toList().first { it != srcId }
        setPair(srcId, other)
        onAmount(req.parts[0].value)
        query = ""
    }

    /** Downloads the latest rates (Frankfurter first) and saves them on the phone. */
    fun refreshRates() {
        if (ratesState == RatesState.Loading) return
        ratesState = RatesState.Loading
        viewModelScope.launch {
            ratesState = try {
                val r = ratesRepo.download()
                rates = r.rates
                ratesMessage = when {
                    !r.changed -> "Already up to date"
                    r.source == "Frankfurter" -> "Updated from Frankfurter"
                    else -> "Updated from backup (Frankfurter unreachable)"
                }
                RatesState.Idle
            } catch (e: Exception) {
                ratesMessage = null
                RatesState.Failed
            }
        }
    }

    // ---------------- derived state ---------------------------------------

    fun manual(): Manual {
        val text = normalizeAmount(amount)
        if (text.isBlank()) return Manual()
        val v = try {
            parseValue(text)
        } catch (e: ConversionError) {
            return Manual(error = "Not a number")
        }
        if (category == CURRENCY) {
            val r = rates ?: return Manual(
                error = if (ratesState == RatesState.Loading) "Downloading rates…" else "Rates not downloaded yet. Connect and tap Refresh.",
            )
            fun line(code: String) = runCatching {
                Line(fmt(convertCurrency(v, from, code, r)), code, currencyName(code))
            }.getOrNull()
            val main = line(to) ?: return Manual(error = "No rate for ${if (from in r.codes) to else from}")
            val others = favouriteCurrencies(r).filter { it != from && it != to }.take(5).mapNotNull { line(it) }
            return Manual(main, others, notes = listOf(ml.attribution.replace("{date}", r.date)))
        }

        fun line(id: String) = Line(fmt(ml.convert(listOf(v to from), id)), ml.units.getValue(id).symbol, unitName(id, plural = true))
        val main = try {
            line(to)
        } catch (e: ConversionError) {
            return Manual(error = e.message)
        }
        val others = ml.quantities.getValue(category).commonTargets
            .filter { it != from && it != to }
            .mapNotNull { runCatching { line(it) }.getOrNull() }
        val used = listOf(ml.units.getValue(from), ml.units.getValue(to))
        val notes = buildList {
            if (used.any { referenceOnly(it) }) add(REFERENCE_ONLY)
            if (used.any { it.approximate }) add("Approximate value")
        }
        return Manual(main, others, notes = notes)
    }

    fun smart(): Smart? {
        if (query.isBlank()) return null
        val req = fastParse(query) ?: return Smart(message = "Type an amount and a unit, e.g. 5 ft to cm")
        val o: Outcome = process(req, ml, region, rates)
        if (o.unresolved.isNotEmpty()) {
            return Smart(message = "Unknown unit: ${o.unresolved.joinToString(", ")}. Pick units below.")
        }
        o.error?.let { return Smart(notes = o.notes, message = it) }
        val lines = o.results.map { r ->
            if (r.symbol == null) Line(r.value, r.unit, currencyName(r.unit))
            else Line(r.value, r.symbol!!, unitName(r.unit, plural = true))
        }
        return Smart(lines, o.notes)
    }

    /** Autocomplete for the unit word being typed: "5 f" -> ft, °F, … */
    fun suggestions(): List<Choice> {
        val m = TARGET_TOKEN.find(query) ?: SOURCE_TOKEN.find(query) ?: return emptyList()
        val token = m.groupValues[1].trim().lowercase()
        if (token.isEmpty()) return emptyList()
        val found = search(token, limit = 6)
        return if (found.firstOrNull()?.matched == token) emptyList() else found
    }

    fun applySuggestion(choice: Choice) {
        val m = TARGET_TOKEN.find(query) ?: SOURCE_TOKEN.find(query) ?: return
        val range = m.groups[1]!!.range
        query = query.substring(0, range.first) + choice.matched + " "
    }

    /** Units and currencies whose id, alias, name or symbol match [text]. Best match first. */
    fun search(text: String, limit: Int = 50): List<Choice> {
        val t = text.trim().lowercase()
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
            .distinctBy { it.third.id }
            .take(limit)
            .map { (_, _, e) -> choice(e.id, e.domain, e.term) }
    }

    /** Picker list when the search box is empty: recents in this category, then the rest. */
    fun categoryChoices(): List<Choice> {
        val all = options(category)
        val recent = recents.filter { it in all }
        return (recent + all.filter { it !in recent }).map { choice(it, if (category == CURRENCY) CURRENCY else "unit", it) }
    }

    fun label(id: String): String = if (category == CURRENCY) "$id · ${currencyName(id)}" else unitName(id, plural = true)

    fun symbol(id: String): String = if (category == CURRENCY) id else ml.units[id]?.symbol ?: id

    // ---------------- helpers -------------------------------------------

    private data class Entry(val term: String, val id: String, val domain: String)

    private val index: List<Entry> by lazy {
        buildList {
            for (u in ml.units.values) {
                for (t in listOf(u.id, u.symbol, u.nameOne, u.nameOther) + u.aliases) add(Entry(t.lowercase(), u.id, "unit"))
            }
            for ((code, aliases) in ml.currencyAliasesByCode) {
                for (t in listOf(code, currencyName(code)) + aliases) add(Entry(t.lowercase(), code, CURRENCY))
            }
            for (a in ml.ambiguousAliases) {
                val r = ml.resolve(a.alias, region, a.domain) ?: continue
                add(Entry(a.alias.lowercase(), r.id, if (r.domain == "currency") CURRENCY else "unit"))
            }
        }
    }

    private fun choice(id: String, domain: String, matched: String): Choice = if (domain == CURRENCY) {
        Choice(id, CURRENCY, "$id · ${currencyName(id)}", "Currency", matched)
    } else {
        val u = ml.units.getValue(id)
        Choice(id, "unit", unitName(id, plural = true), "${u.symbol} · ${ml.quantities.getValue(u.quantity).name}", matched)
    }

    private fun options(c: String): List<String> = if (c == CURRENCY) {
        val codes = ml.currencyCodes + (rates?.codes?.sorted() ?: emptyList())
        (listOf(homeCurrency) + codes).distinct()
    } else {
        ml.units.values.filter { it.quantity == c }.map { it.id }
    }

    private fun favouriteCurrencies(r: Rates) =
        (listOf(homeCurrency) + FAVOURITE_CURRENCIES + ml.currencyCodes).distinct().filter { it in r.codes }

    private fun defaultPair(c: String): Pair<String, String> {
        if (c == CURRENCY) return homeCurrency to if (homeCurrency == "USD") "EUR" else "USD"
        DEFAULT_PAIRS[c]?.takeIf { (a, b) -> a in ml.units && b in ml.units }?.let { return it }
        val ids = options(c)
        return ids[0] to ids.getOrElse(1) { ids[0] }
    }

    private fun loadPair(c: String) {
        val saved = prefs.getString("pair.$c", null)?.split("|").orEmpty()
        val valid = saved.size == 2 && (c == CURRENCY || saved.all { ml.units[it]?.quantity == c })
        val (f, t) = if (valid) saved[0] to saved[1] else defaultPair(c)
        from = f
        to = t
    }

    private fun setPair(f: String, t: String) {
        from = f
        to = t
        prefs.edit().putString("pair.$category", "$f|$t").apply()
    }

    private fun unitName(id: String, plural: Boolean): String {
        val u = ml.units.getValue(id)
        return if (plural) u.nameOther else u.nameOne
    }

    fun currencyName(code: String): String =
        runCatching { Currency.getInstance(code).getDisplayName(Locale.getDefault()) }.getOrDefault(code)

    /** "2,5" means 2.5 where the decimal separator is a comma; the engine reads commas as thousands. */
    private fun normalizeAmount(s: String): String {
        val decimalComma = DecimalFormatSymbols.getInstance().decimalSeparator == ','
        return if (decimalComma && ',' in s && '.' !in s) s.replace(',', '.') else s
    }

    enum class Side { From, To }

    companion object {
        private const val REFRESH_AFTER_MS = 6 * 60 * 60 * 1000L
        private const val MAX_RECENTS = 8
        private val FAVOURITE_CURRENCIES = listOf("USD", "EUR", "SGD", "MYR", "JPY", "GBP", "CNY", "AUD")
        private val DEFAULT_PAIRS = mapOf(
            "length" to ("foot" to "centimeter"),
            "mass" to ("kilogram" to "pound"),
            "temperature" to ("celsius" to "fahrenheit"),
            "volume" to ("liter" to "cup-us"),
            "area" to ("square-meter" to "square-foot"),
            "speed" to ("kilometer-per-hour" to "mile-per-hour"),
            "fuel-economy" to ("liter-per-100-kilometer" to "mile-per-gallon-us"),
            "digital-storage" to ("gigabyte" to "megabyte"),
        )
        private val TARGET_TOKEN = Regex("""(?i)(?:\s(?:to|in|into|as)\s+|->|=|→)\s*([^\d,&]*)$""")
        private val SOURCE_TOKEN = Regex("""\d\s*([^\d\s=][^\d=]*)$""")
    }
}
