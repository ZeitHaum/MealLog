package com.self.diet

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

// Generic placeholders only; users must confirm their own settings before calculation.
data class ModelProfile(val heightCm: Double = 170.0, val age: Int = 30, val sex: String = "male", val activityFactor: Double = 1.2, val lambda: Double = 7500.0, val confirmed: Boolean = false) {
    fun validate() {
        require(heightCm.isFinite() && heightCm in 50.0..250.0) { "请检查身高（cm）" }
        require(age in 18..100) { "此模型用于成人，请检查年龄" }
        require(sex in listOf("male", "female")) { "请选择公式参数" }
        require(activityFactor.isFinite() && activityFactor in 1.0..3.0) { "活动系数应在 1–3 之间" }
        require(lambda.isFinite() && lambda in 1000.0..20000.0) { "请检查每 kg 体重变化对应的热量" }
    }
}

data class EnergyDay(val weight: Double?, val weightTime: String?, val intake: Double, val missingMeals: Int, val missingExercise: Int, val bmr: Double?, val baseline: Double?, val exercise: Double, val total: Double?, val deficit: Double?, val delta: Double?, val predicted: Double?, val knownIntake: Double? = null, val knownExercise: Double? = null)

@Entity(tableName = "analysis_snapshots")
data class AnalysisSnapshot(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val date: String, val generatedAt: String = OffsetDateTime.now().toString(),
    val mode: String, val fingerprint: String,
    val modelVersion: String = EnergyModel.VERSION,
    val heightCm: Double, val age: Int, val sex: String, val activityFactor: Double, val lambda: Double,
    val weight: Double, val weightTime: String, val intake: Double, val bmr: Double, val baseline: Double,
    val exercise: Double, val total: Double, val deficit: Double, val delta: Double, val predicted: Double
)

object EnergyModel {
    const val VERSION = "energy-balance-extra-exercise-v1"
    fun fingerprint(records: List<Record>) = MessageDigest.getInstance("SHA-256").digest(records.sortedBy { it.id }.joinToString("\n") { it.toString() }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun exerciseEnergy(record: Record, weight: Double?): Double? = record.calories ?: if(record.met != null && record.amount != null && weight != null) record.met * weight * 1.05 * record.amount / 60.0 else null
    fun calculate(day: List<Record>, profile: ModelProfile): EnergyDay {
        val measurement = day.filter { it.kind == Kinds.WEIGHT }.maxByOrNull { OffsetDateTime.parse(it.occurredAt).toInstant() }
        val weight = measurement?.amount
        val meals = day.filter { it.kind == Kinds.MEAL }
        val movements = day.filter { it.kind == Kinds.EXERCISE }
        val missingMeals = meals.count { it.calories == null }
        val exerciseValues = movements.map { exerciseEnergy(it, weight) }
        val missingExercise = exerciseValues.count { it == null }
        val mealValues = meals.mapNotNull { it.calories }
        val knownMovements = exerciseValues.filterNotNull()
        val intake = mealValues.sum()
        val exercise = knownMovements.sum()
        val bmr = if(profile.confirmed && weight != null) 10 * weight + 6.25 * profile.heightCm - 5 * profile.age + (if(profile.sex == "male") 5 else -161) else null
        val baseline = bmr?.times(profile.activityFactor)
        val total = if(missingExercise == 0) baseline?.plus(exercise) else null
        val deficit = if(missingMeals == 0 && meals.isNotEmpty()) total?.minus(intake) else null
        val delta = deficit?.let { -it / profile.lambda }
        return EnergyDay(weight, measurement?.occurredAt, intake, missingMeals, missingExercise, bmr, baseline, exercise, total, deficit, delta, if(delta != null && weight != null) weight + delta else null, mealValues.takeIf { it.isNotEmpty() }?.sum(), knownMovements.takeIf { it.isNotEmpty() }?.sum())
    }
    fun snapshot(day: List<Record>, profile: ModelProfile, date: String, mode: String): AnalysisSnapshot {
        profile.validate()
        val result = calculate(day, profile)
        require(result.predicted != null) { "请补全当天体重、饮食热量、运动消耗并确认模型设置" }
        return AnalysisSnapshot(date = date, mode = mode, fingerprint = fingerprint(day), heightCm = profile.heightCm, age = profile.age, sex = profile.sex, activityFactor = profile.activityFactor, lambda = profile.lambda, weight = result.weight!!, weightTime = result.weightTime!!, intake = result.intake, bmr = result.bmr!!, baseline = result.baseline!!, exercise = result.exercise, total = result.total!!, deficit = result.deficit!!, delta = result.delta!!, predicted = result.predicted)
    }
}
