package io.github.yansenchandra.converter.engine

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTest {
    private val dir = File(System.getProperty("converter.dir") ?: "../../converter")
    private val ml = MasterList.parse(File(dir, "master-list.sample.json").readText())
    private val search = Search(ml, "SG", locale = Locale.ENGLISH)

    private fun first(text: String) = search.search(text).firstOrNull()?.id

    @Test
    fun findsSwissFrancByCodeAndName() {
        assertEquals("CHF", first("chf"))
        assertEquals("CHF", first("CHF"))
        assertEquals("CHF", first("swiss"))
        assertEquals("CHF", first("franc"))
    }

    @Test
    fun findsUnitsAndCurrenciesByAlias() {
        assertEquals("pound", first("lbs"))
        assertEquals("SGD", first("sing dollar"))
        assertEquals("kati", first("kati"))
        assertEquals("SGD", first("$")) // ambiguous alias, SG default
        assertEquals("foot", first("ft"))
    }

    @Test
    fun everyCurrencyWithARateIsFindable() {
        val rates = Rates.parse(File(dir, "rates.json").readText())
        for (code in rates.codes) assertEquals(code, first(code.lowercase()), code)
    }

    @Test
    fun currenciesOutsideTheMasterListAreFoundByCodeAndName() {
        val s = Search(ml, "SG", extraCurrencies = listOf("BGN"), locale = Locale.ENGLISH)
        assertEquals("BGN", s.search("bgn").first().id)
        assertEquals("BGN", s.search("bulgarian").first().id)
        assertTrue(Search(ml, "SG", locale = Locale.ENGLISH).search("bulgarian").isEmpty())
    }

    @Test
    fun blankAndUnknownSearches() {
        assertTrue(search.search("  ").isEmpty())
        assertTrue(search.search("zzzz").isEmpty())
    }
}
