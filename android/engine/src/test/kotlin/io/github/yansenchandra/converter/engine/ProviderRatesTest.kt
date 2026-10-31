package io.github.yansenchandra.converter.engine

import org.json.JSONObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Rates.fromProvider: the app's direct Frankfurter refresh. Mirrors converter/test_fetch_rates.py. */
class ProviderRatesTest {
    private val today = LocalDate.of(2026, 10, 31)
    private val codes = listOf("AUD", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "GBP", "HKD", "HUF", "IDR",
        "INR", "JPY", "KRW", "MXN", "MYR", "NOK", "PHP", "PLN", "SEK", "SGD", "THB", "USD")

    // Test fixture, not real rates.
    private fun payload(base: String = "EUR", date: String = "2026-10-30", edit: (JSONObject) -> Unit = {}): String {
        val rates = JSONObject()
        codes.forEach { rates.put(it, 1.5) }
        rates.put("JPY", 160.0).put("IDR", 17000.0).put("KRW", 1.5e3)
        edit(rates)
        return JSONObject().put("amount", 1.0).put("base", base).put("date", date).put("rates", rates).toString()
    }

    @Test
    fun parsesFrankfurterResponse() {
        val r = Rates.fromProvider(payload(), today)
        assertEquals("EUR", r.base)
        assertEquals("2026-10-30", r.date)
        assertEquals("1500", r.rates["KRW"]) // no exponent, no trailing zeros
        assertEquals("1.5", r.rates["SGD"])
        assertEquals("160", r.rates["JPY"])
        assertEquals(r.rates.keys.sorted(), r.rates.keys.toList())
    }

    @Test
    fun resultWorksWithEngineAndCache() {
        val r = Rates.fromProvider(payload(), today)
        assertEquals(Rational.of(320), convertCurrency(parseValue("3"), "SGD", "JPY", r))
        assertEquals(r, Rates.parse(r.toJson()))
    }

    @Test
    fun baseIsNotListedAsARate() {
        val r = Rates.fromProvider(payload { it.put("EUR", 1.0) }, today)
        assertEquals(null, r.rates["EUR"])
    }

    @Test
    fun rejectsBadResponses() {
        val bad = listOf(
            payload(base = "eur"),
            payload(date = "yesterday"),
            payload(date = "2026-11-05"),
            payload { it.put("SGD", 0) },
            payload { it.put("SGD", "abc") },
            payload { it.put("sgd", 1.5) },
            JSONObject().put("base", "EUR").put("date", "2026-10-30").put("rates", JSONObject().put("SGD", 1.5)).toString(),
            """{"base":"EUR","date":"2026-10-30"}""",
        )
        for (json in bad) assertFailsWith<IllegalArgumentException>(json) { Rates.fromProvider(json, today) }
    }

    @Test
    fun oldRatesAreStillAccepted() {
        // Unlike the server job, the client takes the provider's newest rates however old they are.
        assertEquals("2026-01-02", Rates.fromProvider(payload(date = "2026-01-02"), today).date)
    }
}
