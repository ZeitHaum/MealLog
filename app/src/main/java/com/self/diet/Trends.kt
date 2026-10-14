package com.self.diet

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.*

enum class PredictionMode(val label: String, val key: String) {
    DAILY("逐日预测", "daily_recomputed"), CUMULATIVE("累计预测", "cumulative_recomputed")
}
data class ModelPrediction(val predicted: Double, val sourceDate: LocalDate, val sourceWeight: Double,
    val sourceWeightTime: String?, val sourceDeficit: Double?)
data class TrendDay(
    val date: LocalDate, val weight: Record?, val energy: EnergyDay,
    val intake: Double?, val hasRecords: Boolean,
    val prediction: ModelPrediction?, val error: PredictionError?,
    val mode: PredictionMode = PredictionMode.DAILY, val anchor: Record? = null,
    val blockedOn: LocalDate? = null, val predictionStatus: String = "missing_source"
) {
    val activity: Double? get() = energy.baseline?.let { it - energy.bmr!! }
}
data class PredictionError(val date: LocalDate, val actual: Record, val prediction: ModelPrediction) {
    // Signed difference in kilograms. Never divide by total weight or take abs here.
    val kilograms: Double get() = prediction.predicted - actual.amount!!
}
data class ErrorBin(val lower: Double, val upper: Double, val count: Int)
data class ErrorDistribution(val bins: List<ErrorBin>, val extent: Double)

object Trends {
    @Suppress("UNUSED_PARAMETER") // Archived snapshots are retained, but no longer gate forecasts.
    fun days(records: List<Record>, snapshots: List<AnalysisSnapshot>, profile: ModelProfile,
        start: LocalDate, end: LocalDate, mode: PredictionMode = PredictionMode.DAILY): List<TrendDay> {
        require(!end.isBefore(start))
        val count = ChronoUnit.DAYS.between(start, end)
        require(count < 36600) { "日期范围过长" }
        val byDay = records.groupBy { LocalDate.parse(it.date) }
        val anchor = if(mode == PredictionMode.CUMULATIVE) records.filter {
            EnergyModel.validWeight(it) && LocalDate.parse(it.date) in start..end
        }.minWithOrNull(compareBy<Record> { LocalDate.parse(it.date) }
            .thenBy { OffsetDateTime.parse(it.occurredAt).toInstant() }.thenBy { it.id }) else null
        val anchorDate = anchor?.let { LocalDate.parse(it.date) }
        var carried: ModelPrediction? = null
        var blocked: LocalDate? = null
        return (0..count).map { offset ->
            val date = start.plusDays(offset)
            val day = byDay[date].orEmpty()
            val actual = if(date == anchorDate) anchor else EnergyModel.latestWeight(day)
            val prediction = if(mode == PredictionMode.DAILY) {
                val prior = EnergyModel.calculate(byDay[date.minusDays(1)].orEmpty(), profile)
                prior.predicted?.let { ModelPrediction(it, date.minusDays(1), prior.weight!!, prior.weightTime, prior.deficit) }
            } else if(date == anchorDate) ModelPrediction(anchor!!.amount!!, date, anchor.amount, anchor.occurredAt, null)
            else carried
            val energy = if(mode == PredictionMode.DAILY) EnergyModel.calculate(day, profile)
                else EnergyModel.calculateAtWeight(day, profile, prediction?.predicted, if(date == anchorDate) anchor?.occurredAt else null)
            val status = when {
                mode == PredictionMode.DAILY -> if(prediction != null) "predicted" else "missing_source"
                anchor == null -> "no_anchor"
                date < anchorDate -> "before_anchor"
                date == anchorDate -> "anchor"
                prediction != null -> "predicted"
                else -> "blocked"
            }
            val result = TrendDay(date, actual, energy,
                energy.knownIntake.takeIf { energy.missingMeals == 0 }, day.isNotEmpty(), prediction,
                if(actual != null && prediction != null && date != anchorDate) PredictionError(date, actual, prediction) else null,
                mode, anchor, blocked, status)
            if(mode == PredictionMode.CUMULATIVE && anchorDate != null && date >= anchorDate) {
                carried = energy.predicted?.let { ModelPrediction(it, date, prediction!!.predicted, prediction.sourceWeightTime, energy.deficit) }
                if(carried == null && blocked == null) blocked = date
            }
            result
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
        require(count > 0 && errors.all { it.isFinite() })
        val extent = niceCeiling(maxOf(0.05, (errors.maxOfOrNull { abs(it) } ?: 0.0) * 1.05))
        val width = extent * 2 / count
        val bins = IntArray(count)
        errors.forEach { bins[floor((it + extent) / width).toInt().coerceIn(0, count - 1)]++ }
        return ErrorDistribution((0 until count).map { ErrorBin(-extent + it * width, -extent + (it + 1) * width, bins[it]) }, extent)
    }
    fun chartTables(days: List<TrendDay>, cumulative: Boolean = false): Map<String, String> {
        val daily = listOf(listOf("date", "day_index", "actual_weight_kg", "actual_measured_at", "intake_kcal", "bmr_kcal", "daily_activity_kcal", "known_extra_exercise_kcal", "total_expenditure_kcal", "deficit_kcal", "predicted_weight_kg", "prediction_mode", "prediction_status", "source_date", "source_weight_kg", "source_deficit_kcal", "anchor_record_id", "anchor_date", "anchor_weight_kg", "anchor_measured_at", "blocked_on", "error_kg", "energy_status")) + days.mapIndexed { i, d ->
            listOf(d.date.toString(), (i + 1).toString(), Export.number(d.weight?.amount), d.weight?.occurredAt ?: "", Export.number(d.intake), Export.number(d.energy.bmr), Export.number(d.activity), Export.number(d.energy.knownExercise), Export.number(d.energy.total), Export.number(d.energy.deficit), Export.number(d.prediction?.predicted), d.mode.key, d.predictionStatus, d.prediction?.sourceDate?.toString() ?: "", Export.number(d.prediction?.sourceWeight), Export.number(d.prediction?.sourceDeficit), d.anchor?.id ?: "", d.anchor?.date ?: "", Export.number(d.anchor?.amount), d.anchor?.occurredAt ?: "", d.blockedOn?.toString() ?: "", Export.number(d.error?.kilograms), if(d.energy.deficit == null) "incomplete" else "trial_current_settings")
        }
        val errors = listOf(listOf("date", "actual_record_id", "actual_measured_at", "actual_weight_kg", "prediction_mode", "source_date", "source_weight_kg", "source_deficit_kcal", "predicted_weight_kg", "error_kg")) + days.filter { it.error != null }.map { d ->
            val e = d.error!!
            listOf(e.date.toString(), e.actual.id, e.actual.occurredAt, Export.number(e.actual.amount), d.mode.key, e.prediction.sourceDate.toString(), Export.number(e.prediction.sourceWeight), Export.number(e.prediction.sourceDeficit), Export.number(e.prediction.predicted), Export.number(e.kilograms))
        }
        return linkedMapOf((if(cumulative) "cumulative_daily.csv" else "trend_daily.csv") to Export.csv(daily),
            (if(cumulative) "cumulative_errors.csv" else "prediction_errors.csv") to Export.csv(errors))
    }
}
