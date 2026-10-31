package io.github.yansenchandra.converter.engine

import org.json.JSONArray
import org.json.JSONObject

data class UnitDef(
    val id: String,
    val quantity: String,
    val kind: String,
    val factor: Rational,
    val offset: Rational,
    val symbol: String,
    val nameOne: String,
    val nameOther: String,
    val aliases: List<String>,
    val regions: List<String>,
    val approximate: Boolean,
    val system: String,
) {
    fun toBase(v: Rational): Rational = when (kind) {
        "linear" -> v * factor
        "affine" -> v * factor + offset
        else -> {
            if (v.isZero) throw ConversionError("0 $symbol has no equivalent")
            factor / v // reciprocal
        }
    }

    fun fromBase(b: Rational): Rational = when (kind) {
        "linear" -> b / factor
        "affine" -> (b - offset) / factor
        else -> {
            if (b.isZero) throw ConversionError("Result has no equivalent in $symbol")
            factor / b // reciprocal
        }
    }
}

data class Quantity(val id: String, val baseUnit: String, val name: String, val commonTargets: List<String>)

data class Density(val id: String, val name: String, val aliases: List<String>, val gPerMl: Rational)

data class Ambiguity(
    val alias: String,
    val domain: String,
    val candidates: List<String>,
    val defaults: Map<String, String>,
    val note: String?,
)

/** A unit id or ISO currency code, plus a note and the alternatives when an assumption was made. */
data class Resolved(
    val id: String,
    val domain: String,
    val note: String? = null,
    val candidates: List<String> = emptyList(),
)

class MasterList(val data: JSONObject) {
    val version: String = data.getString("version")
    val quantities: Map<String, Quantity>
    val units: Map<String, UnitDef>
    val currencyCodes: List<String>
    val currencyAliasesByCode: Map<String, List<String>>
    val densities: Map<String, Density>
    val attribution: String

    private val unitAlias = HashMap<String, String>()
    private val currencyAlias = HashMap<String, String>()
    private val densityAlias = HashMap<String, String>()
    private val ambiguous = HashMap<Pair<String, String>, Ambiguity>()

    init {
        quantities = LinkedHashMap<String, Quantity>().also { out ->
            data.getJSONArray("quantities").objects().forEach { q ->
                out[q.getString("id")] = Quantity(
                    id = q.getString("id"),
                    baseUnit = q.getString("base_unit"),
                    name = q.getJSONObject("names").optString("en", q.getString("id")),
                    commonTargets = q.optJSONArray("common_targets").strings(),
                )
            }
        }

        units = LinkedHashMap<String, UnitDef>().also { out ->
            data.getJSONArray("units").objects().forEach { u ->
                val en = u.getJSONObject("names").optJSONObject("en")
                val id = u.getString("id")
                out[id] = UnitDef(
                    id = id,
                    quantity = u.getString("quantity"),
                    kind = u.getString("kind"),
                    factor = exact(u.getString("factor")),
                    offset = exact(u.optString("offset", "0")),
                    symbol = u.getString("symbol"),
                    nameOne = en?.getString("one") ?: id,
                    nameOther = en?.getString("other") ?: id,
                    aliases = u.getJSONArray("aliases").strings(),
                    regions = u.optJSONArray("regions").strings(),
                    approximate = u.optBoolean("approximate", false),
                    system = u.getString("system"),
                )
            }
        }

        // Unit ids count as aliases too, so the LLM can return either.
        for (u in units.values) for (a in listOf(u.id) + u.aliases) unitAlias.putIfAbsent(key(a), u.id)

        val currency = data.getJSONObject("currency")
        attribution = currency.getJSONObject("provider").getString("attribution")
        currencyAliasesByCode = LinkedHashMap<String, List<String>>().also { out ->
            currency.getJSONArray("aliases").objects().forEach { c ->
                out[c.getString("code")] = c.getJSONArray("aliases").strings()
            }
        }
        currencyCodes = currencyAliasesByCode.keys.toList()
        for ((code, aliases) in currencyAliasesByCode) {
            for (a in listOf(code) + aliases) currencyAlias.putIfAbsent(key(a), code)
        }

        data.getJSONArray("ambiguous_aliases").objects().forEach { a ->
            val defaults = a.getJSONObject("defaults")
            val amb = Ambiguity(
                alias = a.getString("alias"),
                domain = a.getString("domain"),
                candidates = a.getJSONArray("candidates").strings(),
                defaults = defaults.keys().asSequence().associateWith { defaults.getString(it) },
                note = if (a.has("note")) a.getString("note") else null,
            )
            ambiguous[amb.domain to key(amb.alias)] = amb
        }

        densities = LinkedHashMap<String, Density>().also { out ->
            data.getJSONArray("densities").objects().forEach { d ->
                out[d.getString("id")] = Density(
                    id = d.getString("id"),
                    name = d.getJSONObject("names").optString("en", d.getString("id")),
                    aliases = d.getJSONArray("aliases").strings(),
                    gPerMl = exact(d.getString("g_per_ml")),
                )
            }
        }
        for (d in densities.values) for (a in listOf(d.id) + d.aliases) densityAlias.putIfAbsent(key(a), d.id)
    }

    /** Ambiguous aliases, e.g. "gallons" or "$", for search and autocomplete. */
    val ambiguousAliases: Collection<Ambiguity> get() = ambiguous.values

    // ---------------- validation (run in CI on every master-list change) ---

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        fun dupes(items: List<String>) = items.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

        val rawUnits = data.getJSONArray("units").objects()
        val rawQuantities = data.getJSONArray("quantities").objects()
        dupes(rawUnits.map { it.getString("id") }).forEach { errors += "duplicate unit id: $it" }
        dupes(rawQuantities.map { it.getString("id") }).forEach { errors += "duplicate quantity id: $it" }

        for (q in quantities.values) {
            val base = units[q.baseUnit]
            if (base == null) {
                errors += "quantity ${q.id}: base unit ${q.baseUnit} missing"
            } else if (base.quantity != q.id || base.kind != "linear" || base.factor != Rational.ONE) {
                errors += "quantity ${q.id}: base unit must be linear, factor 1, same quantity"
            }
            for (t in q.commonTargets) {
                if (units[t]?.quantity != q.id) errors += "quantity ${q.id}: bad common target $t"
            }
        }

        for (u in units.values) {
            if (u.quantity !in quantities) errors += "unit ${u.id}: unknown quantity ${u.quantity}"
            if (u.factor.isZero) errors += "unit ${u.id}: factor is zero"
        }

        // Every alias must point to exactly one thing within its domain.
        fun terms(items: Map<String, List<String>>): Map<String, Set<String>> {
            val out = sortedMapOf<String, MutableSet<String>>()
            for ((id, aliases) in items) for (a in listOf(id) + aliases) out.getOrPut(key(a)) { sortedSetOf() } += id
            return out
        }
        val unitTerms = terms(units.mapValues { it.value.aliases })
        unitTerms.filterValues { it.size > 1 }.forEach { (a, ids) ->
            errors += "unit alias ${pyRepr(a)} maps to ${ids.pyList()}; move it to ambiguous_aliases"
        }
        val curTerms = terms(currencyAliasesByCode)
        curTerms.filterValues { it.size > 1 }.forEach { (a, codes) ->
            errors += "currency alias ${pyRepr(a)} maps to ${codes.pyList()}"
        }
        terms(densities.mapValues { it.value.aliases }).filterValues { it.size > 1 }.forEach { (a, ids) ->
            errors += "ingredient alias ${pyRepr(a)} maps to ${ids.pyList()}"
        }

        for ((k, amb) in ambiguous) {
            val (domain, alias) = k
            val plain = if (domain == "unit") unitTerms else curTerms
            if (alias in plain) errors += "ambiguous alias ${pyRepr(alias)} also listed as a plain $domain alias"
            val known: Collection<String> = if (domain == "unit") units.keys else currencyCodes
            for (c in amb.candidates) {
                if (c !in known) errors += "ambiguous alias ${pyRepr(alias)}: unknown candidate $c"
            }
            for ((region, choice) in amb.defaults) {
                if (choice !in amb.candidates) {
                    errors += "ambiguous alias ${pyRepr(alias)}: default $region->$choice not a candidate"
                }
            }
        }
        return errors
    }

    // ---------------- resolution ----------------------------------------

    /**
     * Map a user/LLM term to a unit id or currency code.
     *
     * Order: exact alias -> ambiguous alias with region default. When the
     * other side of the conversion is known ([preferQuantity]), ambiguous
     * candidates of that quantity win ('oz' -> fluid ounce when the target
     * is a volume).
     */
    fun resolve(term: String, region: String = "*", domain: String? = null, preferQuantity: String? = null): Resolved? {
        val t = key(term)
        if (t.isEmpty()) return null
        val domains = if (domain != null) listOf(domain) else listOf("unit", "currency")
        for (dom in domains) {
            if (dom == "unit") unitAlias[t]?.let { return Resolved(it, "unit") }
            if (dom == "currency") currencyAlias[t]?.let { return Resolved(it, "currency") }
            val amb = ambiguous[dom to t] ?: continue
            var choice = amb.defaults[region] ?: amb.defaults.getValue("*")
            if (dom == "unit" && preferQuantity != null && units.getValue(choice).quantity != preferQuantity) {
                val sameQ = amb.candidates.filter { units.getValue(it).quantity == preferQuantity }
                if (sameQ.isNotEmpty()) {
                    val regional = sameQ.filter { region in units.getValue(it).regions }
                    choice = regional.ifEmpty { sameQ }[0]
                }
            }
            val label = if (dom == "unit") units.getValue(choice).nameOne else choice
            val note = (amb.note ?: "Assumed {unit}").replace("{unit}", label)
            return Resolved(choice, dom, note, amb.candidates)
        }
        return null
    }

    fun resolveIngredient(term: String?): String? = if (term.isNullOrEmpty()) null else densityAlias[key(term)]

    // ---------------- conversion -----------------------------------------

    /** Convert one or more (value, unit id) parts to [toId]. */
    fun convert(parts: List<Pair<Rational, String>>, toId: String, ingredient: String? = null): Rational {
        if (parts.isEmpty()) throw ConversionError("Nothing to convert")
        val src = parts.map { units.getValue(it.second) }
        val dst = units.getValue(toId)
        val q = src[0].quantity
        if (src.any { it.quantity != q }) throw ConversionError("Mixed quantities in one amount")
        if (parts.size > 1 && src.any { it.kind != "linear" }) {
            throw ConversionError("Only plain units can be added together")
        }

        var base = parts.zip(src).fold(Rational.ZERO) { acc, (p, u) -> acc + u.toBase(p.first) }

        if (dst.quantity == q) return dst.fromBase(base)

        // volume <-> mass via ingredient density. Bases: liter and kilogram.
        // kg = L * 1000 mL/L * g/mL / 1000 g/kg = L * g_per_ml
        if (setOf(q, dst.quantity) == setOf("volume", "mass")) {
            if (ingredient == null) {
                throw ConversionError("Converting volume to weight needs an ingredient (e.g. rice, flour)")
            }
            val gPerMl = densities.getValue(ingredient).gPerMl
            base = if (q == "volume") base * gPerMl else base / gPerMl
            return dst.fromBase(base)
        }

        throw ConversionError(
            "Can't convert ${quantities.getValue(q).name.lowercase()} " +
                "to ${quantities.getValue(dst.quantity).name.lowercase()}",
        )
    }

    companion object {
        fun parse(json: String) = MasterList(JSONObject(json))

        internal fun key(s: String) = s.trim().lowercase()
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

private fun Collection<String>.pyList() = joinToString(", ", "[", "]") { pyRepr(it) }
