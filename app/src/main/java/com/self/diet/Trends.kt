package com.self.diet

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class TrendDay(
    val date: LocalDate, val weight: Record?, val energy: EnergyDay,
    val intake: Double?, val hasRecords: Boolean,
    val prediction: AnalysisSnapshot?, val error: PredictionError?
) {
    val activity: Double? get() = energy.baseline?.let { it - energy.bmr!! }
}

data class PredictionError(val date: LocalDate, val actual: Record, val snapshot: AnalysisSnapshot) {
    val kilograms: Double get() = snapshot.predicted - actual.amount!!
    val percent: Double get() = kilograms / actual.amount!! * 100.0
}

data class ErrorBin(val lower: Double, val upper: Double, val count: Int)
data class ErrorDistribution(val bins: List<ErrorBin>, val extent: Double)

object Trends {
    fun days(records: List<Record>, snapshots: List<AnalysisSnapshot>, profile: ModelProfile, start: LocalDate, end: LocalDate): List<TrendDay> {
        require(!end.isBefore(start))
        val byDay = records.groupBy { LocalDate.parse(it.date) }
        val predictions = snapshots.filter {
            // Only a saved estimate for its own day is a prospective next-day prediction.
            it.mode == "same_day_estimate" && OffsetDateTime.parse(it.generatedAt).toLocalDate() == LocalDate.parse(it.date) &&
                OffsetDateTime.parse(it.weightTime).toInstant() <= OffsetDateTime.parse(it.generatedAt).toInstant() && it.predicted.isFinite()
        }.groupBy { LocalDate.parse(it.date).plusDays(1) }
        return (0..ChronoUnit.DAYS.between(start, end)).map { offset ->
            val date = start.plusDays(offset)
            val day = byDay[date].orEmpty()
            val weights = day.filter { it.kind == Kinds.WEIGHT && it.amount != null }
            val actual = weights.maxByOrNull { OffsetDateTime.parse(it.occurredAt).toInstant() }
            // Reject snapshots saved after any target-day weigh-in or its earlier entry time.
            val cutoff = weights.flatMap { listOf(OffsetDateTime.parse(it.occurredAt).toInstant(), OffsetDateTime.parse(it.createdAt).toInstant()) }.minOrNull()
            val prediction = predictions[date].orEmpty().filter { cutoff == null || OffsetDateTime.parse(it.generatedAt).toInstant() < cutoff }
                .maxWithOrNull(compareBy<AnalysisSnapshot> { OffsetDateTime.parse(it.generatedAt).toInstant() }.thenBy { it.id })
            val energy = EnergyModel.calculate(day, profile)
            val intake = energy.intake.takeIf { day.any { r -> r.kind == Kinds.MEAL } && energy.missingMeals == 0 }
            TrendDay(date, actual, energy, intake, day.isNotEmpty(), prediction, if(actual != null && prediction != null) PredictionError(date, actual, prediction) else null)
        }
    }
    fun mean(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.average()
    fun weightChange(days: List<TrendDay>): Double? {
        val weights = days.mapNotNull { it.weight?.amount }
        return if(weights.size < 2) null else weights.last() - weights.first()
    }
    fun niceCeiling(value: Double): Double {
        if(value <= 0 || !value.isFinite()) return 1.0
        val scale = 10.0.pow(floor(log10(value)))
        val normalized = value / scale
        return (listOf(1.0, 2.0, 5.0, 10.0).firstOrNull { it >= normalized } ?: 10.0) * scale
    }
    fun distribution(errors: List<Double>, count: Int = 9): ErrorDistribution {
        require(count > 0)
        require(errors.all { it.isFinite() })
        val extent = niceCeiling(maxOf(0.05, (errors.maxOfOrNull { abs(it) } ?: 0.0) * 1.05))
        val width = extent * 2 / count
        val bins = IntArray(count)
        errors.forEach { value -> bins[floor((value + extent) / width).toInt().coerceIn(0, count - 1)]++ }
        return ErrorDistribution((0 until count).map { ErrorBin(-extent + it * width, -extent + (it + 1) * width, bins[it]) }, extent)
    }
    fun chartTables(days: List<TrendDay>): Map<String, String> {
        val daily = listOf(listOf("date", "day_index", "actual_weight_kg", "actual_measured_at", "intake_kcal", "bmr_kcal", "daily_activity_kcal", "known_extra_exercise_kcal", "total_expenditure_kcal", "deficit_kcal", "saved_prediction_kg", "prediction_snapshot_id", "energy_status")) + days.mapIndexed { i, d ->
            listOf(d.date.toString(), (i + 1).toString(), Export.number(d.weight?.amount), d.weight?.occurredAt ?: "", Export.number(d.intake), Export.number(d.energy.bmr), Export.number(d.activity), Export.number(d.energy.knownExercise), Export.number(d.energy.total), Export.number(d.energy.deficit), Export.number(d.prediction?.predicted), d.prediction?.id ?: "", if(d.energy.deficit == null) "incomplete" else "trial_current_settings")
        }
        val errors = listOf(listOf("date", "actual_record_id", "actual_measured_at", "actual_weight_kg", "snapshot_id", "snapshot_generated_at", "model_version", "predicted_weight_kg", "error_kg", "relative_error_percent")) + days.mapNotNull { it.error }.map { e ->
            listOf(e.date.toString(), e.actual.id, e.actual.occurredAt, Export.number(e.actual.amount), e.snapshot.id, e.snapshot.generatedAt, e.snapshot.modelVersion, Export.number(e.snapshot.predicted), Export.number(e.kilograms), Export.number(e.percent))
        }
        return linkedMapOf("trend_daily.csv" to Export.csv(daily), "prediction_errors.csv" to Export.csv(errors))
    }
}
