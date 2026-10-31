package sg.pixeltools.converter.engine

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/** Raised for anything the user should be told about (bad input, wrong units). */
class ConversionError(message: String) : Exception(message)

/** Exact rational number, always in lowest terms with a positive denominator. */
class Rational private constructor(val num: BigInteger, val den: BigInteger) : Comparable<Rational> {

    operator fun plus(o: Rational) = of(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: Rational) = of(num * o.den - o.num * den, den * o.den)
    operator fun times(o: Rational) = of(num * o.num, den * o.den)
    operator fun div(o: Rational): Rational {
        if (o.num.signum() == 0) throw ArithmeticException("Division by zero")
        return of(num * o.den, den * o.num)
    }
    operator fun unaryMinus() = Rational(num.negate(), den)

    val isZero get() = num.signum() == 0

    override fun compareTo(other: Rational) = (num * other.den).compareTo(other.num * den)
    override fun equals(other: Any?) = other is Rational && num == other.num && den == other.den
    override fun hashCode() = 31 * num.hashCode() + den.hashCode()
    override fun toString() = "$num/$den"

    companion object {
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)

        fun of(n: BigInteger, d: BigInteger = BigInteger.ONE): Rational {
            require(d.signum() != 0) { "zero denominator" }
            val g = n.gcd(d).let { if (it.signum() == 0) BigInteger.ONE else it }
            val sign = if (d.signum() < 0) BigInteger.ONE.negate() else BigInteger.ONE
            return Rational(n / g * sign, d / g * sign)
        }

        fun of(n: Long) = of(BigInteger.valueOf(n))

        /** '0.3048', '-40', '1500' -> exact value. */
        fun ofDecimal(s: String): Rational {
            val d = BigDecimal(s.trim())
            return if (d.scale() >= 0) of(d.unscaledValue(), BigInteger.TEN.pow(d.scale()))
            else of(d.unscaledValue() * BigInteger.TEN.pow(-d.scale()))
        }
    }
}

/** '0.3048' or '1852/3600' -> exact Rational. */
fun exact(s: String): Rational {
    val i = s.indexOf('/')
    if (i < 0) return Rational.ofDecimal(s)
    return Rational.ofDecimal(s.substring(0, i)) / Rational.ofDecimal(s.substring(i + 1))
}

private val SUFFIX = mapOf('k' to 1_000L, 'm' to 1_000_000L, 'b' to 1_000_000_000L)
private val VALUE_RE = pyRegex(
    "^(?<sign>-)?" +
        "(?:(?<whole>\\d+)\\s+)?" +        // "1 " in "1 1/2"
        "(?<num>\\d+(?:\\.\\d+)?)" +
        "(?:/(?<den>\\d+(?:\\.\\d+)?))?" +
        "(?<suffix>[kmb])?$",
)

/**
 * Parse '2.5', '1,000', '1/2', '1 1/2', '-40', '2.5k'.
 *
 * Commas are treated as thousands separators. If you support locales that
 * use a decimal comma ('2,5'), normalise before calling this.
 */
fun parseValue(text: String): Rational {
    val s = text.trim().replace(",", "").replace("_", "")
    val m = VALUE_RE.matcher(s)
    if (!m.find()) throw ConversionError("Not a number: ${pyRepr(text)}")
    var value = Rational.ofDecimal(m.group("num"))
    m.group("den")?.let {
        val den = Rational.ofDecimal(it)
        if (den.isZero) throw ConversionError("Division by zero in fraction")
        value /= den
    }
    m.group("whole")?.let {
        if (m.group("den") == null) throw ConversionError("Not a number: ${pyRepr(text)}")
        value += Rational.ofDecimal(it)
    }
    m.group("suffix")?.let { value *= Rational.of(SUFFIX.getValue(it.lowercase()[0])) }
    return if (m.group("sign") != null) -value else value
}

/** Round to [sig] significant digits, no exponent for everyday magnitudes. */
fun fmt(x: Rational, sig: Int = 6): String {
    if (x.isZero) return "0"
    val d = BigDecimal(x.num).divide(BigDecimal(x.den), MathContext(sig, RoundingMode.HALF_EVEN))
    var s = d.toPlainString()
    if ('.' in s) s = s.trimEnd('0').trimEnd('.')
    return s
}

/** Same semantics as the Python reference: Unicode-aware \d, \s, \b and case folding. */
internal fun pyRegex(pattern: String): java.util.regex.Pattern = java.util.regex.Pattern.compile(
    pattern,
    java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE or
        java.util.regex.Pattern.UNICODE_CHARACTER_CLASS,
)

/** Python's repr() for plain strings, so error messages match the reference engine. */
internal fun pyRepr(s: String): String {
    val q = if ('\'' in s && '"' !in s) '"' else '\''
    val body = s.replace("\\", "\\\\").let { if (q == '\'') it.replace("'", "\\'") else it }
    return "$q$body$q"
}
