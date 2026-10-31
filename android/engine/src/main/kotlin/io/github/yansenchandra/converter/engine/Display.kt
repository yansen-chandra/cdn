package io.github.yansenchandra.converter.engine

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

/** A result as shown ([text], locale-formatted) and as copied ([copy], plain digits with '.'). */
data class Shown(val text: String, val copy: String)

/** Magnitudes outside 10⁻⁶ ≤ |x| < 10¹² switch to scientific notation. */
private const val MAX_PLAIN_EXPONENT = 11
private const val MIN_PLAIN_EXPONENT = -6

/**
 * Formats [x] to [sig] significant digits (same rounding as [fmt]) for people:
 * grouped digits in the locale's style for everyday sizes, "1.60218 × 10⁻¹⁹" for extremes.
 */
fun display(x: Rational, sig: Int = 6, locale: Locale = Locale.getDefault()): Shown {
    if (x.isZero) return Shown("0", "0")
    val d = BigDecimal(x.num).divide(BigDecimal(x.den), MathContext(sig, RoundingMode.HALF_EVEN))
    val exponent = d.precision() - d.scale() - 1
    val symbols = DecimalFormatSymbols.getInstance(locale)

    if (exponent > MAX_PLAIN_EXPONENT || exponent < MIN_PLAIN_EXPONENT) {
        val mantissa = d.movePointLeft(exponent).stripTrailingZeros().toPlainString()
        val text = mantissa.replace('.', symbols.decimalSeparator) + " × 10" + superscript(exponent)
        return Shown(text, "${mantissa}E$exponent")
    }

    val plain = fmt(x, sig)
    val negative = plain.startsWith("-")
    val digits = plain.removePrefix("-")
    val whole = digits.substringBefore('.')
    val fraction = digits.substringAfter('.', "")
    val grouped = if (whole.length > 3) whole.reversed().chunked(3).joinToString(symbols.groupingSeparator.toString()).reversed() else whole
    val text = (if (negative) "-" else "") + grouped + (if (fraction.isEmpty()) "" else symbols.decimalSeparator + fraction)
    return Shown(text, plain)
}

private fun superscript(n: Int): String {
    val map = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    val digits = Math.abs(n).toString().map { map[it - '0'] }.joinToString("")
    return (if (n < 0) "⁻" else "") + digits
}

private val NUMBER = Regex("""\d[\d.,]*\d""")

/**
 * Where the decimal separator is a comma, people type "2,5" for 2.5 and "1.000,5" for 1000.5.
 * The engine reads commas as thousands separators, so rewrite each number to the "." form.
 * Commas between words ("sgd, myr") are left alone.
 */
fun normalizeDecimalComma(text: String, decimalSeparator: Char): String {
    if (decimalSeparator != ',') return text
    return NUMBER.replace(text) { m ->
        val t = m.value
        when {
            ',' in t && '.' in t -> t.replace(".", "").replace(',', '.')
            ',' in t -> t.replace(',', '.')
            else -> t
        }
    }
}
