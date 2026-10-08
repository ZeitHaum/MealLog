package com.self.diet

import org.junit.Assert.*
import org.junit.Test

class EnergyModelTest {
    private val profile = ModelProfile(confirmed = true)
    private fun row(kind: String, amount: Double? = null, energy: Double? = null, met: Double? = null) = Record(kind = kind, occurredAt = "2026-10-08T08:00:00+08:00", date = "2026-10-08", name = "样本", amount = amount, calories = energy, met = met)
    private val weight = row(Kinds.WEIGHT, 90.0)
    private val meal = row(Kinds.MEAL, energy = 1800.0)
    @Test fun extraExerciseIsAddedInFullOutsideActivityFactor() {
        val result = EnergyModel.calculate(listOf(weight, meal, row(Kinds.EXERCISE, 30.0, 300.0)), profile)
        assertEquals(1817.5, result.bmr!!, 1e-9)
        assertEquals(2181.0, result.baseline!!, 1e-9)
        assertEquals(2481.0, result.total!!, 1e-9)
        assertEquals(681.0, result.deficit!!, 1e-9)
        assertEquals(-0.0908, result.delta!!, 1e-9)
        assertEquals(89.9092, result.predicted!!, 1e-9)
        val higherActivity = EnergyModel.calculate(listOf(weight, meal, row(Kinds.EXERCISE, 30.0, 300.0)), profile.copy(activityFactor = 1.5))
        assertEquals(1817.5 * 1.5 + 300, higherActivity.total!!, 1e-9)
    }
    @Test fun metUsesFullEstimateWithLatestSameDayWeight() {
        val result = EnergyModel.calculate(listOf(weight.copy(amount = 80.0), weight.copy(occurredAt = "2026-10-08T09:00:00+08:00"), meal, row(Kinds.EXERCISE, 30.0, met = 4.0)), profile)
        assertEquals(189.0, result.exercise, 1e-9)
        assertEquals(2370.0, result.total!!, 1e-9)
    }
    @Test fun missingValuesPreventFalsePredictions() {
        assertNull(EnergyModel.calculate(listOf(weight, meal), profile.copy(confirmed = false)).predicted)
        assertNull(EnergyModel.calculate(listOf(meal), profile).predicted)
        assertNull(EnergyModel.calculate(listOf(weight), profile).predicted)
        assertNull(EnergyModel.calculate(listOf(weight, meal, row(Kinds.MEAL)), profile).predicted)
        assertNull(EnergyModel.calculate(listOf(weight, meal, row(Kinds.EXERCISE, 30.0)), profile).predicted)
        assertNotNull(EnergyModel.calculate(listOf(weight, meal.copy(calories = 0.0)), profile).predicted)
    }
    @Test fun surplusPredictsPositiveChangeAndFemaleFormulaUsesCorrectConstant() {
        val result = EnergyModel.calculate(listOf(weight, meal.copy(calories = 2500.0)), profile.copy(sex = "female"))
        assertEquals(1651.5, result.bmr!!, 1e-9)
        assertEquals(-518.2, result.deficit!!, 1e-9)
        assertTrue(result.delta!! > 0)
    }
    @Test fun snapshotsRemainFrozenAndExportKeepsParametersAndIdentity() {
        val records = listOf(weight, meal)
        val snapshot = EnergyModel.snapshot(records, profile, weight.date, "retrospective")
        val before = Export.snapshotTable(listOf(snapshot))
        EnergyModel.calculate(records, profile.copy(lambda = 10000.0))
        assertEquals(before, Export.snapshotTable(listOf(snapshot)))
        assertTrue(before.contains("\"7500\""))
        assertTrue(before.contains("\"retrospective\""))
        assertEquals(EnergyModel.fingerprint(records), EnergyModel.fingerprint(records.reversed()))
        assertNotEquals(snapshot.fingerprint, EnergyModel.fingerprint(listOf(weight, meal.copy(calories = 2000.0))))
        assertTrue(Export.analysisTable(records, profile).contains("trial_current_settings"))
        assertTrue(Export.analysisTable(listOf(weight), profile).contains("incomplete"))
    }
}
