package io.github.yansenchandra.converter.engine

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayTest {
    private val en = Locale.US
    private val de = Locale.GERMANY

    private fun show(x: String, locale: Locale = en) = display(exact(x), 6, locale)

    @Test
    fun everydayNumbersAreGrouped() {
        assertEquals(Shown("120", "120"), show("120"))
        assertEquals(Shown("1,000", "1000"), show("1000"))
        assertEquals(Shown("10,666.7", "10666.7"), show("32000/3"))
        assertEquals(Shown("-273.15", "-273.15"), show("-273.15"))
        assertEquals(Shown("0.333333", "0.333333"), show("1/3"))
        assertEquals(Shown("9,460,730,000", "9460730000"), show("9460730472.5808"))
    }

    @Test
    fun localeSeparators() {
        assertEquals(Shown("10.666,7", "10666.7"), show("32000/3", de))
        assertEquals(Shown("2,5", "2.5"), show("2.5", de))
    }

    @Test
    fun extremesUseScientificNotation() {
        assertEquals(Shown("1.60218 × 10⁻¹⁹", "1.60218E-19"), show("0.0000000000000000001602176634"))
        assertEquals(Shown("9.46073 × 10¹⁵", "9.46073E15"), show("9460730472580800"))
        assertEquals(Shown("1 × 10¹²", "1E12"), show("1000000000000"))
        assertEquals(Shown("-1.5 × 10⁻⁷", "-1.5E-7"), show("-0.00000015"))
        assertEquals(Shown("1,60218 × 10⁻¹⁹", "1.60218E-19"), show("0.0000000000000000001602176634", de))
    }

    @Test
    fun boundaries() {
        assertEquals("999,999,000,000", show("999999000000").text)
        assertEquals("0.000001", show("0.000001").text)
        assertEquals("9.99999 × 10⁻⁷", show("0.000000999999").text)
        assertEquals(Shown("0", "0"), show("0"))
    }

    @Test
    fun decimalCommaInput() {
        assertEquals("2.5 kg to lb", normalizeDecimalComma("2,5 kg to lb", ','))
        assertEquals("1000.5 eur to sgd", normalizeDecimalComma("1.000,5 eur to sgd", ','))
        assertEquals("100 sgd to jpy, myr", normalizeDecimalComma("100 sgd to jpy, myr", ','))
        assertEquals("2,5 kg", normalizeDecimalComma("2,5 kg", '.'))
        assertEquals("1 1/2 cups", normalizeDecimalComma("1 1/2 cups", ','))
    }
}
