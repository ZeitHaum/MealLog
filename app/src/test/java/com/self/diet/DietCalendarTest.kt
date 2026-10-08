package com.self.diet

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class DietCalendarTest {
    private val today = LocalDate.parse("2026-10-09")
    private val month = YearMonth.from(today)
    private val profile = ModelProfile(confirmed = true)
    private fun row(date: String, kind: String, value: Double? = null, met: Double? = null) = Record(
        kind = kind, date = date, occurredAt = "${date}T08:00:00+08:00", name = "测试",
        amount = if(kind == Kinds.WEIGHT) value else if(kind == Kinds.EXERCISE) 30.0 else null,
        calories = if(kind == Kinds.WEIGHT) null else value, met = met
    )
    private fun days(rows: List<Record>, metric: CalendarMetric, settings: ModelProfile = profile) = DietCalendar.month(rows, settings, month, metric, today)

    @Test fun monthGridsCoverEveryDateOnceWithCorrectWeekdaysIncludingLeapYears() {
        for(year in 2020..2030) for(m in 1..12) {
            val ym = YearMonth.of(year, m)
            val grid = DietCalendar.grid(ym)
            assertTrue(grid.size in listOf(28, 35, 42))
            assertEquals((1..ym.lengthOfMonth()).map { ym.atDay(it) }, grid.filterNotNull())
            grid.forEachIndexed { index, date -> if(date != null) assertEquals(date.dayOfWeek.value - 1, index % 7) }
        }
        assertEquals(29, DietCalendar.grid(YearMonth.of(2024, 2)).filterNotNull().size)
        assertEquals(28, DietCalendar.grid(YearMonth.of(2021, 2)).size)
        assertEquals(42, DietCalendar.grid(YearMonth.of(2026, 8)).size)
    }

    @Test fun weightUsesLatestInstantAndComparesAcrossMonthBoundaryWithoutFillingGaps() {
        val rows = listOf(row("2026-09-30", Kinds.WEIGHT, 90.0), row("2026-10-02", Kinds.WEIGHT, 89.6),
            row("2026-10-02", Kinds.WEIGHT, 89.8).copy(occurredAt = "2026-10-02T01:00:00Z"),
            row("2026-10-05", Kinds.WEIGHT, 89.7))
        val result = days(rows, CalendarMetric.WEIGHT)
        assertNull(result[0].value); assertTrue(result[0].needsAttention)
        assertEquals(89.8, result[1].value!!, 1e-8)
        assertEquals(-.2, result[1].change!!, 1e-8)
        assertEquals(LocalDate.parse("2026-09-30"), result[1].previousDate)
        assertNull(result[2].value)
        assertEquals(-.1, result[4].change!!, 1e-8)
        assertEquals(LocalDate.parse("2026-10-02"), result[4].previousDate)
    }

    @Test fun partialSumsStayVisibleButDoNotBecomeComparisonBaselines() {
        val rows = listOf(row("2026-10-01", Kinds.MEAL, 1800.0), row("2026-10-02", Kinds.MEAL, 500.0),
            row("2026-10-02", Kinds.MEAL), row("2026-10-03", Kinds.MEAL, 1700.0))
        val result = days(rows, CalendarMetric.MEAL)
        assertEquals(500.0, result[1].value!!, 0.0)
        assertFalse(result[1].complete); assertTrue(result[1].needsAttention); assertNull(result[1].change)
        assertEquals(-100.0, result[2].change!!, 0.0)
        assertEquals(LocalDate.parse("2026-10-01"), result[2].previousDate)
    }

    @Test fun warningsFollowSelectedMetricAndExcludeFutureOrPreTrackingDays() {
        val rows = listOf(row("2026-10-05", Kinds.WEIGHT, 90.0))
        val weights = days(rows, CalendarMetric.WEIGHT)
        assertFalse(weights[3].needsAttention); assertFalse(weights[4].needsAttention)
        assertTrue(weights[5].needsAttention); assertFalse(weights[9].needsAttention)
        assertTrue(days(rows, CalendarMetric.MEAL)[4].needsAttention)
        val empty = days(emptyList(), CalendarMetric.DEFICIT)
        assertEquals(listOf(today), empty.filter { it.needsAttention }.map { it.date })
        assertTrue(empty.all { it.value == null && it.change == null })
    }

    @Test fun exerciseMetRequiresSameDayWeightAndZeroIsNotMissing() {
        val rows = listOf(row("2026-10-01", Kinds.WEIGHT, 90.0), row("2026-10-02", Kinds.EXERCISE, met = 4.0),
            row("2026-10-03", Kinds.EXERCISE, 0.0), row("2026-10-04", Kinds.WEIGHT, 80.0), row("2026-10-04", Kinds.EXERCISE, met = 4.0))
        val result = days(rows, CalendarMetric.EXERCISE)
        assertNull(result[1].value); assertEquals("缺体重", result[1].hint); assertTrue(result[1].needsAttention)
        assertEquals(0.0, result[2].value!!, 0.0); assertFalse(result[2].needsAttention)
        assertEquals(168.0, result[3].value!!, 1e-8)
        assertEquals(168.0, result[3].change!!, 1e-8)
    }

    @Test fun deficitsUseExistingModelAndStayBlankForMissingDependencies() {
        val rows = listOf(row("2026-10-01", Kinds.WEIGHT, 90.0), row("2026-10-01", Kinds.MEAL, 1800.0),
            row("2026-10-01", Kinds.EXERCISE, 300.0), row("2026-10-02", Kinds.MEAL, 1700.0))
        val result = days(rows, CalendarMetric.DEFICIT)
        assertEquals(EnergyModel.calculate(rows.filter { it.date == "2026-10-01" }, profile).deficit!!, result[0].value!!, 0.0)
        assertNull(result[1].value); assertTrue(result[1].needsAttention)
        assertNull(days(rows, CalendarMetric.DEFICIT, profile.copy(confirmed = false))[0].value)
    }
}
