package io.github.yansenchandra.converter.engine

import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

// --------------------------------------------------------------------------
// Currency
// --------------------------------------------------------------------------

/** The rates.json the server job publishes: {"base", "date", "rates": {CODE: "rate"}}. */
data class Rates(val base: String, val date: String, val rates: Map<String, String>) {
    val codes: Set<String> get() = rates.keys + base

    /** Same shape as the server's rates.json, so the cache reads back with [parse]. */
    fun toJson(): String = JSONObject()
        .put("base", base)
        .put("date", date)
        .put("rates", JSONObject().also { o -> rates.forEach { (k, v) -> o.put(k, v) } })
        .toString()

    companion object {
        const val MIN_CURRENCIES = 20

        /**
         * Validates a Frankfurter response ({"amount", "base", "date", "rates": {CODE: number}})
         * and converts it to exact decimal strings. Same checks as converter/fetch_rates.py,
         * except no maximum age: the newest rates the provider has are still the best we can get.
         */
        fun fromProvider(json: String, today: LocalDate = LocalDate.now()): Rates {
            val o = JSONObject(json)
            val base = o.optString("base")
            require(CODE.matches(base)) { "Bad base currency: $base" }
            val date = o.optString("date")
            val day = try {
                LocalDate.parse(date)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("Bad date: $date")
            }
            require(!day.isAfter(today.plusDays(1))) { "Rate date $date is in the future" }

            val raw = o.optJSONObject("rates") ?: throw IllegalArgumentException("Response has no rates")
            val rates = sortedMapOf<String, String>()
            for (code in raw.keys()) {
                require(CODE.matches(code)) { "Bad currency code: $code" }
                val d = try {
                    BigDecimal(raw.get(code).toString())
                } catch (e: NumberFormatException) {
                    throw IllegalArgumentException("Bad rate for $code")
                }
                require(d.signum() > 0) { "Bad rate for $code" }
                if (code != base) rates[code] = d.stripTrailingZeros().toPlainString()
            }
            require(rates.size >= MIN_CURRENCIES) { "Only ${rates.size} rates, expected at least $MIN_CURRENCIES" }
            return Rates(base, date, rates)
        }

        private val CODE = Regex("[A-Z]{3}")

        fun parse(json: String): Rates {
            val o = JSONObject(json)
            val r = o.getJSONObject("rates")
            val rates = r.keys().asSequence().associateWith { r.get(it).toString() }
            val parsed = Rates(o.getString("base"), o.getString("date"), rates)
            rates.values.forEach { exact(it) } // fail early on a bad file
            return parsed
        }
    }
}

fun convertCurrency(amount: Rational, src: String, dst: String, rates: Rates): Rational {
    val table = rates.rates.mapValues { exact(it.value) } + (rates.base to Rational.ONE)
    for (code in listOf(src, dst)) if (code !in table) throw ConversionError("No rate for $code")
    return amount / table.getValue(src) * table.getValue(dst)
}

// --------------------------------------------------------------------------
// Fast parser: handles clean input without calling the LLM.
// Returns the same contract as the LLM (see converter/parse-prompt.md) or null.
// --------------------------------------------------------------------------

data class Part(val value: String, val unit: String)

data class Request(
    val intent: String,
    val domain: String?,
    val parts: List<Part>,
    val to: List<String>,
    val ingredient: String?,
)

// No k/m/b suffix here: "5km" and "3m" must stay units. The LLM handles "2.5k".
private const val NUM = "-?\\d[\\d,]*(?:\\.\\d+)?(?:\\s+\\d+/\\d+|/\\d+)?"
private const val SYMBOL = "(?:s\\$|us\\$|a\\$|hk\\$|[\$€£¥₹฿₩₱])"
private const val SEP = "\\s+(?:to|in|into|as)\\s+|\\s*(?:->|=|→)\\s*"
private val FAST_RE = pyRegex(
    "^\\s*(?<sym>$SYMBOL)?\\s*(?<num>$NUM)\\s*(?<unit>[^\\d\\s=][^\\d=]*?)?" +
        "(?:(?:$SEP)(?<to>.+?))?\\s*\\??\\s*$",
)
private val SYMBOL_RE = pyRegex(
    "^\\s*(?<sym>$SYMBOL)\\s*(?<num>$NUM)(?:(?:$SEP)(?<to>.+?))?\\s*\\??\\s*$",
)
private val TO_SPLIT = pyRegex("\\s*(?:,|\\band\\b|&)\\s*")

fun fastParse(text: String): Request? {
    var sym: String? = null
    var unit: String? = null
    val num: String
    val to: String?
    val s = SYMBOL_RE.matcher(text)
    if (s.find()) {
        sym = s.group("sym"); num = s.group("num"); to = s.group("to")
    } else {
        val f = FAST_RE.matcher(text)
        if (!f.find()) return null
        sym = f.group("sym"); unit = f.group("unit"); num = f.group("num"); to = f.group("to")
    }
    if (!sym.isNullOrEmpty() && !unit.isNullOrEmpty()) return null // "$50 sgd to jpy": let the LLM handle it
    val u = (sym?.ifEmpty { null } ?: unit ?: "").trim().lowercase()
    if (u.isEmpty()) return null
    val targets = TO_SPLIT.split(to ?: "").filter { it.isNotEmpty() }.map { it.trim().lowercase() }
    return Request("convert", null, listOf(Part(num.trim(), u)), targets, null)
}

/**
 * Use the fast parser only when every term resolves; otherwise return null
 * so the caller can fall back (manual picker, or the LLM in premium).
 */
fun fastPath(text: String, ml: MasterList, region: String = "*"): Request? {
    val req = fastParse(text) ?: return null
    val terms = req.parts.map { it.unit } + req.to
    return if (terms.all { ml.resolve(it, region) != null }) req else null
}

// --------------------------------------------------------------------------
// End to end: request (fastParse or LLM output) -> results for display
// --------------------------------------------------------------------------

/** [symbol] is null for currencies; [unit] is the unit id or currency code. */
data class ResultItem(val value: String, val unit: String, val symbol: String?, val exact: Rational)

/** An ambiguous [term] the region decided; [role] is "from" or "to". */
data class Assumption(val role: String, val term: String, val chosen: String, val candidates: List<String>)

data class Outcome(
    val results: List<ResultItem> = emptyList(),
    val notes: List<String> = emptyList(),
    val unresolved: List<String> = emptyList(),
    val error: String? = null,
    val assumptions: List<Assumption> = emptyList(),
)

fun process(request: Request, ml: MasterList, region: String = "*", rates: Rates? = null, sig: Int = 6): Outcome {
    if (request.intent != "convert") {
        return Outcome(error = if (request.intent == "incomplete") "incomplete" else "unsupported")
    }
    val domain = request.domain
    val notes = mutableListOf<String>()
    val unresolved = mutableListOf<String>()
    val assumptions = mutableListOf<Assumption>()
    fun assume(role: String, term: String, r: Resolved) {
        if (r.note == null) return
        notes += r.note
        assumptions += Assumption(role, term.trim().lowercase(), r.id, r.candidates)
    }

    val parts = try {
        request.parts.map { parseValue(it.value) to it.unit }
    } catch (e: ConversionError) {
        return Outcome(error = e.message)
    }

    // Resolve targets first: their quantity helps pick ambiguous source units.
    var targets = mutableListOf<Resolved>()
    val targetTerms = mutableListOf<String>()
    for (t in request.to) {
        val r = ml.resolve(t, region, domain)
        if (r == null) unresolved += t else { targets += r; targetTerms += t }
    }
    val targetQ = targets.firstOrNull { it.domain == "unit" }?.let { ml.units.getValue(it.id).quantity }

    val sources = mutableListOf<Pair<Rational, Resolved>>()
    val sourceTerms = mutableListOf<String>()
    for ((v, term) in parts) {
        val r = ml.resolve(term, region, domain, preferQuantity = targetQ)
        if (r == null) unresolved += term else { sources += v to r; sourceTerms += term }
    }
    if (unresolved.isNotEmpty()) return Outcome(unresolved = unresolved)
    if (sources.isEmpty()) return Outcome(error = "Nothing to convert")

    val srcDomain = sources[0].second.domain
    if (sources.any { it.second.domain != srcDomain }) return Outcome(error = "Mixed units and currencies")
    sources.zip(sourceTerms).forEach { (s, term) -> assume("from", term, s.second) }

    if (targets.isEmpty()) { // no target given: use the quantity's common targets
        if (srcDomain != "unit") return Outcome(notes = notes, error = "Pick a target currency", assumptions = assumptions)
        val q = ml.units.getValue(sources[0].second.id).quantity
        val srcIds = sources.map { it.second.id }.toSet()
        targets = ml.quantities.getValue(q).commonTargets.filter { it !in srcIds }
            .map { Resolved(it, "unit") }.toMutableList()
    }
    targets.zip(targetTerms).forEach { (r, term) -> assume("to", term, r) } // common targets are never ambiguous

    val ingredient = ml.resolveIngredient(request.ingredient)
    if (!request.ingredient.isNullOrEmpty() && ingredient == null) {
        return Outcome(notes = notes, unresolved = listOf(request.ingredient), assumptions = assumptions)
    }

    val results = mutableListOf<ResultItem>()
    try {
        for (r in targets) {
            if (srcDomain == "currency") {
                if (r.domain != "currency") throw ConversionError("Can't convert currency to a unit")
                if (rates == null) throw ConversionError("No exchange rates loaded")
                val amount = sources.fold(Rational.ZERO) { acc, (v, _) -> acc + v }
                val value = convertCurrency(amount, sources[0].second.id, r.id, rates)
                results += ResultItem(fmt(value, sig), r.id, null, value)
            } else {
                if (r.domain != "unit") throw ConversionError("Can't convert a unit to a currency")
                val value = ml.convert(sources.map { (v, s) -> v to s.id }, r.id, ingredient)
                results += ResultItem(fmt(value, sig), r.id, ml.units.getValue(r.id).symbol, value)
            }
        }
    } catch (e: ConversionError) {
        return Outcome(results, notes, error = e.message, assumptions = assumptions)
    }

    if (srcDomain == "currency") notes += ml.attribution.replace("{date}", rates!!.date)
    if (ingredient != null) {
        notes += "Approximate: based on ${ml.densities.getValue(ingredient).name.lowercase()} density"
    }
    val used = targets.map { it.id } + sources.map { it.second.id }
    if (srcDomain == "unit" && used.any { referenceOnly(ml.units.getValue(it)) }) notes += REFERENCE_ONLY
    return Outcome(results, notes.distinct(), assumptions = assumptions)
}

const val REFERENCE_ONLY = "Reference only: only metric units are permitted for trade in Singapore"

/** Singapore/Malaysia customary units: shown, but not legal for trade in Singapore. */
fun referenceOnly(u: UnitDef) = u.system in setOf("regional", "historical") && "SG" in u.regions
