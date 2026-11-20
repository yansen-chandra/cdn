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
