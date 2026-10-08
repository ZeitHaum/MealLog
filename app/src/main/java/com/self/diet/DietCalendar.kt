package com.self.diet

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import java.util.Locale

enum class CalendarMetric(val label: String, val unit: String) {
    WEIGHT("体重", "kg"), MEAL("饮食", "kcal"), EXERCISE("运动", "kcal"), DEFICIT("热量缺口", "kcal")
}

data class CalendarDay(
    val date: LocalDate, val value: Double?, val complete: Boolean,
    val change: Double?, val previousDate: LocalDate?,
    val needsAttention: Boolean, val hint: String, val hasRecords: Boolean
)

object DietCalendar {
    fun grid(month: YearMonth): List<LocalDate?> {
        val leading = month.atDay(1).dayOfWeek.value - 1
        val size = ((leading + month.lengthOfMonth() + 6) / 7) * 7
        return (0 until size).map { index -> (index - leading + 1).takeIf { it in 1..month.lengthOfMonth() }?.let { month.atDay(it) } }
    }

    fun month(records: List<Record>, profile: ModelProfile, month: YearMonth, metric: CalendarMetric, today: LocalDate): List<CalendarDay> {
        val grouped = records.groupBy { LocalDate.parse(it.date) }.toSortedMap()
        val trackingStart = (grouped.keys.minOrNull() ?: today).coerceAtMost(today)
        val calculated = mutableMapOf<LocalDate, CalendarDay>()
        var previousDate: LocalDate? = null
        var previousValue: Double? = null
        grouped.filterKeys { it <= month.atEndOfMonth() }.forEach { (date, rows) ->
            val energy = EnergyModel.calculate(rows, profile)
            val hasCategory = rows.any { it.kind == when(metric) { CalendarMetric.WEIGHT -> Kinds.WEIGHT; CalendarMetric.MEAL -> Kinds.MEAL; CalendarMetric.EXERCISE -> Kinds.EXERCISE; CalendarMetric.DEFICIT -> "" } }
            val value = when(metric) {
                CalendarMetric.WEIGHT -> energy.weight
                CalendarMetric.MEAL -> energy.knownIntake
                CalendarMetric.EXERCISE -> energy.knownExercise
                CalendarMetric.DEFICIT -> energy.deficit
            }
            val complete = value != null && when(metric) {
                CalendarMetric.MEAL -> energy.missingMeals == 0
                CalendarMetric.EXERCISE -> energy.missingExercise == 0
                else -> true
            }
            val hint = when {
                complete -> if(previousValue == null) "首记" else ""
                metric == CalendarMetric.DEFICIT -> "待补"
                !hasCategory -> "未记"
                metric == CalendarMetric.EXERCISE && energy.weight == null && rows.any { it.met != null && it.calories == null } -> "缺体重"
                else -> "待补"
            }
            calculated[date] = CalendarDay(date, value, complete, if(complete && previousValue != null) value!! - previousValue!! else null, if(complete) previousDate else null, !complete && date in trackingStart..today, hint, true)
            if(complete) { previousValue = value; previousDate = date }
        }
        return (1..month.lengthOfMonth()).map { day ->
            val date = month.atDay(day)
            calculated[date] ?: CalendarDay(date, null, false, null, null, date in trackingStart..today, if(date.isAfter(today)) "" else "未记", false)
        }
    }

    fun compact(value: Double, weight: Boolean = false, signed: Boolean = false): String {
        val places = if(weight) 1 else 0
        val rounded = if(abs(value) < if(weight) .05 else .5) 0.0 else value
        val sign = if(signed && rounded > 0) "+" else ""
        return if(abs(rounded) >= 10000) sign + String.format(Locale.US, "%.1fk", rounded / 1000.0) else sign + String.format(Locale.US, "%.${places}f", rounded)
    }
}
