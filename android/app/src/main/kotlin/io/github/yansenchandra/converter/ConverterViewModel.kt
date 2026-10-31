package io.github.yansenchandra.converter

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.yansenchandra.converter.engine.ConversionError
import io.github.yansenchandra.converter.engine.MasterList
import io.github.yansenchandra.converter.engine.REFERENCE_ONLY
import io.github.yansenchandra.converter.engine.Rates
import io.github.yansenchandra.converter.engine.Rational
import io.github.yansenchandra.converter.engine.Search
import io.github.yansenchandra.converter.engine.convertCurrency
import io.github.yansenchandra.converter.engine.display
import io.github.yansenchandra.converter.engine.fastParse
import io.github.yansenchandra.converter.engine.normalizeDecimalComma
import io.github.yansenchandra.converter.engine.parseValue
import io.github.yansenchandra.converter.engine.process
import io.github.yansenchandra.converter.engine.referenceOnly
import io.github.yansenchandra.converter.widget.ConverterWidget
import kotlinx.coroutines.launch
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.Locale

const val CURRENCY = "currency"

/** Something the user can pick: a unit id or a currency code. [matched] is the alias that matched a search. */
data class Choice(val id: String, val domain: String, val label: String, val detail: String, val matched: String)

/** One result row: [value] is shown, [copy] is what the clipboard gets. */
data class Line(val value: String, val copy: String, val unit: String, val label: String)

data class Manual(val main: Line? = null, val others: List<Line> = emptyList(), val error: String? = null, val notes: List<String> = emptyList())

/** An assumption the user can change: "[term]" could mean any of [variants]. */
data class Variant(val id: String, val label: String, val selected: Boolean)
data class Choosable(val term: String, val role: String, val variants: List<Variant>)

data class Smart(
    val lines: List<Line> = emptyList(),
    val notes: List<String> = emptyList(),
    val message: String? = null,
    val choices: List<Choosable> = emptyList(),
)

data class PairChip(val pair: UnitPair, val label: String, val favourite: Boolean)

data class CategoryTile(val id: String, val name: String, val count: Int)

enum class RatesState { Idle, Loading, Failed }

class ConverterViewModel(app: Application) : AndroidViewModel(app) {
    val ml = MasterList.parse(app.assets.open("master-list.json").bufferedReader().use { it.readText() })
    val region: String = Locale.getDefault().country.uppercase().ifBlank { "*" }
    val homeCurrency: String = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull()
        ?.takeIf { it in ml.currencyCodes } ?: "USD"

    private val store = Store(app)
    private val ratesRepo = RatesRepository(app.filesDir, BuildConfig.FRANKFURTER_URL, BuildConfig.RATES_URLS.split(" "))
    private val decimalSeparator = DecimalFormatSymbols.getInstance().decimalSeparator

    private val allCategories: List<Pair<String, String>> =
        ml.quantities.values.map { it.id to it.name } + (CURRENCY to text(R.string.category_currency))

    var query by mutableStateOf("")
        private set
    var category by mutableStateOf(store.category?.takeIf { c -> allCategories.any { it.first == c } } ?: "length")
        private set
    var amount by mutableStateOf(store.amount ?: "1")
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
    var history by mutableStateOf(store.history)
        private set
    private var favourites by mutableStateOf(store.favourites)
    private var recentPairs by mutableStateOf(store.recentPairs)
    private var recentCategories by mutableStateOf(store.recentCategories)
    private var recentUnits by mutableStateOf(store.recentUnits)

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
        store.amount = text
    }

    fun selectCategory(c: String) {
        if (c == category) return
        switchCategory(c)
        loadPair(c)
    }

    fun swap() = setPair(to, from)

    /** Picking a unit from another quantity (via search) switches category. */
    fun pick(choice: Choice, side: Side) {
        val c = if (choice.domain == CURRENCY) CURRENCY else ml.units.getValue(choice.id).quantity
        if (c != category) {
            switchCategory(c)
            val (f, t) = defaultPair(c)
            val other = if (side == Side.From) t else f
            val o = if (other == choice.id) options(c).first { it != choice.id } else other
            if (side == Side.From) setPair(choice.id, o) else setPair(o, choice.id)
        } else if (side == Side.From) {
            setPair(choice.id, if (to == choice.id) from else to)
        } else {
            setPair(if (from == choice.id) to else from, choice.id)
        }
        recentUnits = (listOf(choice.id) + recentUnits.filter { it != choice.id }).take(MAX_RECENT_UNITS)
        store.recentUnits = recentUnits
    }

    fun applyPair(p: UnitPair) {
        if (p.category != category) switchCategory(p.category)
        setPair(p.from, p.to)
    }

    fun isFavourite() = UnitPair(category, from, to) in favourites

    fun toggleFavourite() {
        val p = UnitPair(category, from, to)
        favourites = if (p in favourites) favourites - p else listOf(p) + favourites
        store.favourites = favourites
        updateWidget()
    }

    /** Copies a smart-input conversion into the converter below. */
    fun applySmart() {
        val req = fastParse(normalized(query)) ?: return
        val src = ml.resolve(req.parts[0].unit, region) ?: return
        val targetQ = if (src.domain == "unit") ml.units.getValue(src.id).quantity else null
        val dst = req.to.firstNotNullOfOrNull { ml.resolve(it, region, src.domain, targetQ) }
        val srcId = if (dst != null && src.domain == "unit") {
            ml.resolve(req.parts[0].unit, region, preferQuantity = ml.units.getValue(dst.id).quantity)!!.id
        } else {
            src.id
        }
        val c = if (src.domain == "currency") CURRENCY else ml.units.getValue(srcId).quantity
        switchCategory(c)
        val other = dst?.id ?: defaultPair(c).toList().first { it != srcId }
        setPair(srcId, other)
        onAmount(req.parts[0].value)
        query = ""
    }

    /** Rewrites the typed conversion so the ambiguous term means [id] ("pint" -> "imperial pint"). */
    fun chooseVariant(choice: Choosable, id: String) {
        val req = fastParse(normalized(query)) ?: return
        val replacement = unambiguousTerm(id)
        val src = req.parts[0]
        val newSrc = if (choice.role == "from" && src.unit == choice.term) replacement else src.unit
        val newTo = req.to.map { if (choice.role == "to" && it == choice.term) replacement else it }
        query = src.value + " " + newSrc + if (newTo.isEmpty()) "" else " to " + newTo.joinToString(" and ")
    }

    /** Downloads the latest rates (Frankfurter first) and saves them on the phone. */
    fun refreshRates() {
        if (ratesState == RatesState.Loading) return
        ratesState = RatesState.Loading
        viewModelScope.launch {
            ratesState = try {
                val r = ratesRepo.download()
                rates = r.rates
                ratesMessage = text(
                    when {
                        !r.changed -> R.string.rates_up_to_date
                        r.source == "Frankfurter" -> R.string.rates_updated_frankfurter
                        else -> R.string.rates_updated_backup
                    },
                )
                updateWidget()
                RatesState.Idle
            } catch (e: Exception) {
                ratesMessage = null
                RatesState.Failed
            }
        }
    }

    /** Called once the typed conversion has settled: remembers it in history. */
    fun recordSmart() {
        val s = smart() ?: return
        val first = s.lines.firstOrNull() ?: return
        val q = query.trim()
        remember(HistoryEntry(q, "${first.value} ${first.unit}", query = q))
    }

    /** Called once the converter has settled: remembers the pair and the conversion. */
    fun recordManual() {
        val main = manual().main ?: return
        val p = UnitPair(category, from, to)
        recentPairs = (listOf(p) + recentPairs.filter { it != p }).take(MAX_RECENT_PAIRS)
        store.recentPairs = recentPairs
        remember(HistoryEntry("${amount.trim()} ${symbol(from)} → ${symbol(to)}", "${main.value} ${main.unit}", pair = p, amount = amount.trim()))
    }

    fun applyHistory(e: HistoryEntry) {
        if (e.query != null) {
            query = e.query
        } else if (e.pair != null) {
            applyPair(e.pair)
            e.amount?.let { onAmount(it) }
        }
    }

    fun clearHistory() {
        history = emptyList()
        store.history = history
    }

    // ---------------- derived state ---------------------------------------

    /** Category chips: recently used first, then the rest in master-list order. */
    fun categories(): List<Pair<String, String>> {
        val recent = recentCategories.mapNotNull { id -> allCategories.firstOrNull { it.first == id } }
        return recent + allCategories.filter { it !in recent }
    }

    fun categoryTiles(): List<CategoryTile> = allCategories.map { (id, name) -> CategoryTile(id, name, options(id).size) }

    fun pairChips(): List<PairChip> {
        val favs = favourites.filter { valid(it) }.map { PairChip(it, pairLabel(it), favourite = true) }
        val recents = recentPairs.filter { valid(it) && it !in favourites }.map { PairChip(it, pairLabel(it), favourite = false) }
        return (favs + recents).take(MAX_PAIR_CHIPS)
    }

    fun manual(): Manual {
        val input = normalized(amount)
        if (input.isBlank()) return Manual()
        val v = try {
            parseValue(input)
        } catch (e: ConversionError) {
            return Manual(error = text(R.string.msg_not_number))
        }
        if (category == CURRENCY) {
            val r = rates ?: return Manual(
                error = text(if (ratesState == RatesState.Loading) R.string.msg_downloading else R.string.msg_no_rates),
            )
            fun currencyLine(code: String) =
                runCatching { line(convertCurrency(v, from, code, r), code, currencyName(code)) }.getOrNull()
            val main = currencyLine(to) ?: return Manual(error = text(R.string.msg_no_rate_for, if (from in r.codes) to else from))
            val others = favouriteCurrencies(r).filter { it != from && it != to }.take(5).mapNotNull { currencyLine(it) }
            return Manual(main, others, notes = listOf(ml.attribution.replace("{date}", r.date)))
        }

        fun unitLine(id: String) = line(ml.convert(listOf(v to from), id), ml.units.getValue(id).symbol, unitName(id))
        val main = try {
            unitLine(to)
        } catch (e: ConversionError) {
            return Manual(error = e.message)
        }
        val others = ml.quantities.getValue(category).commonTargets
            .filter { it != from && it != to }
            .mapNotNull { runCatching { unitLine(it) }.getOrNull() }
        val used = listOf(ml.units.getValue(from), ml.units.getValue(to))
        val notes = buildList {
            if (used.any { referenceOnly(it) }) add(REFERENCE_ONLY)
            if (used.any { it.approximate }) add(text(R.string.note_approximate))
        }
        return Manual(main, others, notes = notes)
    }

    fun smart(): Smart? {
        if (query.isBlank()) return null
        val req = fastParse(normalized(query)) ?: return Smart(message = text(R.string.msg_type_hint))
        val o = process(req, ml, region, rates)
        if (o.unresolved.isNotEmpty()) return Smart(message = text(R.string.msg_unknown_unit, o.unresolved.joinToString(", ")))
        val choices = o.assumptions.map { a ->
            val options = a.candidates.filter { c -> c in ml.units || rates?.codes?.contains(c) != false }
            Choosable(a.term, a.role, options.map { Variant(it, variantLabel(it), it == a.chosen) })
        }
        o.error?.let { return Smart(notes = o.notes, message = it, choices = choices) }
        val lines = o.results.map { r ->
            if (r.symbol == null) line(r.exact, r.unit, currencyName(r.unit)) else line(r.exact, r.symbol!!, unitName(r.unit))
        }
        return Smart(lines, o.notes, choices = choices)
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
    fun search(text: String, limit: Int = 50): List<Choice> =
        searcher().search(text, limit * 2)
            .filter { it.domain != CURRENCY || hasRate(it.id) }
            .take(limit)
            .map { choice(it.id, it.domain, it.term) }

    /** Picker list when the search box is empty: recents in this category, then the rest. */
    fun categoryChoices(): List<Choice> {
        val all = options(category)
        val recent = recentUnits.filter { it in all }
        return (recent + all.filter { it !in recent }).map { choice(it, if (category == CURRENCY) CURRENCY else "unit", it) }
    }

    fun label(id: String): String = if (category == CURRENCY) currencyName(id) else unitName(id)

    fun symbol(id: String): String = if (category == CURRENCY) id else ml.units[id]?.symbol ?: id

    /** Days since the rates' date, or null without rates. ECB skips weekends, so 3 days can be normal. */
    fun ratesAgeDays(): Long? = rates?.let { r ->
        runCatching { ChronoUnit.DAYS.between(LocalDate.parse(r.date), LocalDate.now()) }.getOrNull()
    }

    fun ratesStale() = (ratesAgeDays() ?: 0) > STALE_AFTER_DAYS

    // ---------------- helpers -------------------------------------------

    // Rebuilt when the rates change, so every currency with a rate is searchable.
    private var searchIndex: Search? = null
    private var searchCodes: Set<String>? = null

    private fun searcher(): Search {
        val codes = rates?.codes.orEmpty()
        if (searchIndex == null || codes != searchCodes) {
            searchIndex = Search(ml, region, codes)
            searchCodes = codes
        }
        return searchIndex!!
    }

    private fun line(value: Rational, unit: String, label: String): Line {
        val shown = display(value)
        return Line(shown.text, shown.copy, unit, label)
    }

    private fun choice(id: String, domain: String, matched: String): Choice = if (domain == CURRENCY) {
        Choice(id, CURRENCY, "$id · ${currencyName(id)}", text(R.string.category_currency), matched)
    } else {
        val u = ml.units.getValue(id)
        Choice(id, "unit", unitName(id), "${u.symbol} · ${ml.quantities.getValue(u.quantity).name}", matched)
    }

    /** Currencies without a rate can't be converted, so they're hidden once rates are loaded. */
    private fun hasRate(code: String) = rates?.codes?.contains(code) ?: true

    private fun options(c: String): List<String> = if (c == CURRENCY) {
        val codes = ml.currencyCodes + (rates?.codes?.sorted() ?: emptyList())
        (listOf(homeCurrency) + codes).distinct().filter { hasRate(it) }
    } else {
        ml.units.values.filter { it.quantity == c }.map { it.id }
    }

    private fun valid(p: UnitPair) = if (p.category == CURRENCY) {
        hasRate(p.from) && hasRate(p.to)
    } else {
        ml.units[p.from]?.quantity == p.category && ml.units[p.to]?.quantity == p.category
    }

    private fun pairLabel(p: UnitPair) = if (p.category == CURRENCY) {
        "${p.from} → ${p.to}"
    } else {
        "${ml.units.getValue(p.from).symbol} → ${ml.units.getValue(p.to).symbol}"
    }

    private fun variantLabel(id: String) = ml.units[id]?.nameOne ?: id

    /** A term that resolves to exactly [id] with no assumption, for rewriting typed text. */
    private fun unambiguousTerm(id: String): String {
        val u = ml.units[id] ?: return id.lowercase()
        return (listOf(u.nameOne) + u.aliases + id).firstOrNull { t ->
            t.none { it.isDigit() } && ml.resolve(t, region)?.let { it.id == id && it.note == null } == true
        } ?: id
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
        val saved = store.pair(c)
        val (f, t) = if (saved != null && valid(saved)) saved.from to saved.to else defaultPair(c)
        from = f
        to = t
    }

    private fun switchCategory(c: String) {
        category = c
        store.category = c
        recentCategories = (listOf(c) + recentCategories.filter { it != c }).take(MAX_RECENT_CATEGORIES)
        store.recentCategories = recentCategories
    }

    private fun setPair(f: String, t: String) {
        from = f
        to = t
        store.savePair(UnitPair(category, f, t))
        if (favourites.isEmpty()) updateWidget()
    }

    private fun remember(e: HistoryEntry) {
        history = (listOf(e) + history.filter { it.label != e.label }).take(MAX_HISTORY)
        store.history = history
    }

    private fun updateWidget() {
        viewModelScope.launch { runCatching { ConverterWidget().updateAll(getApplication()) } }
    }

    private fun unitName(id: String): String = ml.units.getValue(id).nameOther

    fun currencyName(code: String): String =
        runCatching { Currency.getInstance(code).getDisplayName(Locale.getDefault()) }.getOrDefault(code)

    private fun normalized(s: String) = normalizeDecimalComma(s, decimalSeparator)

    private fun text(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    enum class Side { From, To }

    companion object {
        private const val REFRESH_AFTER_MS = 6 * 60 * 60 * 1000L
        private const val STALE_AFTER_DAYS = 4L
        private const val MAX_RECENT_UNITS = 8
        private const val MAX_RECENT_PAIRS = 6
        private const val MAX_RECENT_CATEGORIES = 4
        private const val MAX_PAIR_CHIPS = 8
        private const val MAX_HISTORY = 30
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
            "time" to ("hour" to "minute"),
            "pressure" to ("psi" to "bar"),
            "energy" to ("kilocalorie" to "kilojoule"),
            "power" to ("horsepower" to "kilowatt"),
            "force" to ("newton" to "pound-force"),
            "frequency" to ("megahertz" to "kilohertz"),
            "angle" to ("degree" to "radian"),
            "data-rate" to ("megabit-per-second" to "megabyte-per-second"),
        )
        private val TARGET_TOKEN = Regex("""(?i)(?:\s(?:to|in|into|as)\s+|->|=|→)\s*([^\d,&]*)$""")
        private val SOURCE_TOKEN = Regex("""\d\s*([^\d\s=][^\d=]*)$""")
    }
}
