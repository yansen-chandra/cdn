package io.github.yansenchandra.converter.engine

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Known answers from the Python reference engine (converter/golden.json,
 * written by converter/make_golden.py). Every entry must match exactly.
 */
class GoldenTest {
    private val dir = File(System.getProperty("converter.dir") ?: "../../converter")
    private val ml = MasterList.parse(File(dir, "master-list.sample.json").readText())
    private val golden = JSONObject(File(dir, "golden.json").readText())
    private val rates = Rates.parse(golden.getJSONObject("rates").toString())

    @Test
    fun masterListIsConsistent() = assertEquals(emptyList(), ml.validate())

    @Test
    fun parseValue() = cases("parse_value") { c ->
        val expected = c.opt("value").takeIf { it != JSONObject.NULL } as String?
        val actual = try {
            parseValue(c.getString("text")).toString()
        } catch (e: ConversionError) {
            null
        }
        assertEquals(expected, actual, c.getString("text"))
    }

    @Test
    fun fmt() = cases("fmt") { c ->
        assertEquals(c.getString("text"), fmt(exact(c.getString("value")), c.getInt("sig")), c.toString())
    }

    @Test
    fun convert() = cases("convert") { c ->
        val actual = try {
            "result" to ml.convert(
                listOf(parseValue(c.getString("value")) to c.getString("from")),
                c.getString("to"),
                c.optStringOrNull("ingredient"),
            ).toString()
        } catch (e: ConversionError) {
            "error" to e.message
        }
        val expected = if (c.has("result")) "result" to c.getString("result") else "error" to c.getString("error")
        assertEquals(expected, actual, c.toString())
    }

    @Test
    fun resolve() = cases("resolve") { c ->
        val r = ml.resolve(c.getString("term"), c.getString("region"), preferQuantity = c.optStringOrNull("prefer_quantity"))
        val e = c.optJSONObject("result")
        val expected = e?.let { Resolved(it.getString("id"), it.getString("domain"), it.optStringOrNull("note")) }
        assertEquals(expected, r, c.toString())
    }

    @Test
    fun texts() = cases("texts") { c ->
        val text = c.getString("text")
        val region = c.getString("region")
        val req = fastParse(text)
        assertEquals(c.optJSONObject("fast_parse")?.toRequest(), req, "fast_parse $text")
        assertEquals(c.getBoolean("fast_path"), fastPath(text, ml, region) != null, "fast_path $text")
        if (req != null) {
            assertEquals(c.getJSONObject("outcome").toOutcome(), process(req, ml, region, rates).display(), text)
        }
    }

    @Test
    fun requests() = cases("requests") { c ->
        val req = c.getJSONObject("request").toRequest()
        val actual = process(req, ml, c.getString("region"), rates).display()
        assertEquals(c.getJSONObject("outcome").toOutcome(), actual, c.toString())
    }

    @Test
    fun goldenFileIsNotEmpty() {
        for (k in listOf("parse_value", "fmt", "convert", "resolve", "texts", "requests")) {
            assertTrue(golden.getJSONArray(k).length() > 0, k)
        }
    }

    private fun cases(name: String, check: (JSONObject) -> Unit) {
        val failures = golden.getJSONArray(name).objects().mapNotNull { c ->
            try {
                check(c); null
            } catch (e: AssertionError) {
                e.message
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} $name case(s) differ:\n" + failures.joinToString("\n"))
    }

    // The exact Rational isn't in golden.json (Python drops it before writing).
    private fun Outcome.display() = copy(results = results.map { it.copy(exact = Rational.ZERO) })

    private fun JSONObject.toRequest() = Request(
        intent = getString("intent"),
        domain = optStringOrNull("domain"),
        parts = getJSONArray("parts").objects().map { Part(it.getString("value"), it.getString("unit")) },
        to = getJSONArray("to").strings(),
        ingredient = optStringOrNull("ingredient"),
    )

    private fun JSONObject.toOutcome() = Outcome(
        results = getJSONArray("results").objects().map {
            ResultItem(it.getString("value"), it.getString("unit"), it.optStringOrNull("symbol"), Rational.ZERO)
        },
        notes = getJSONArray("notes").strings(),
        unresolved = getJSONArray("unresolved").strings(),
        error = optStringOrNull("error"),
    )

    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else getString(k)
}
