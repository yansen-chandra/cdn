package app.dayroute.planner

import kotlin.test.Test
import kotlin.test.assertEquals

class ClockTimeTest {
    @Test
    fun `parses and prints 24 hour times`() {
        assertEquals("07:05", ClockTime.parse("7:05").toString())
        assertEquals(13 * 60 + 30, ClockTime.parse("13:30").minutes)
    }

    @Test
    fun `adding minutes rolls over the hour`() {
        assertEquals("10:15", (ClockTime.parse("09:50") + 25).toString())
    }

    @Test
    fun `times past midnight print as next day`() {
        assertEquals("00:30+1", (ClockTime.parse("23:45") + 45).toString())
    }
}

class LenientTimeTest {
    @Test
    fun `accepts the ways people type times`() {
        assertEquals("09:30", ClockTime.parseOrNull("9:30").toString())
        assertEquals("09:30", ClockTime.parseOrNull("930").toString())
        assertEquals("14:05", ClockTime.parseOrNull(" 1405 ").toString())
    }

    @Test
    fun `rejects blanks and nonsense`() {
        assertEquals(null, ClockTime.parseOrNull(""))
        assertEquals(null, ClockTime.parseOrNull("25:00"))
        assertEquals(null, ClockTime.parseOrNull("noon"))
    }
}
