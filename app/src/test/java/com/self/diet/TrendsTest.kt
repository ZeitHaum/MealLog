package com.self.diet

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.nio.file.Files
import kotlin.math.abs

class TrendsTest {
    private val profile = ModelProfile(confirmed = true)
    private val start = LocalDate.parse("2026-10-01")
    private fun weight(day: Int, value: Double = 90.0, time: String = "08:00:00"): Record {
        val date = start.plusDays(day.toLong()).toString()
        return Record(kind = Kinds.WEIGHT, date = date, occurredAt = "${date}T$time+08:00", createdAt = "${date}T$time+08:00", amount = value)
    }
    private fun meal(day: Int, energy: Double? = 1800.0): Record {
        val date = start.plusDays(day.toLong()).toString()
        return Record(kind = Kinds.MEAL, date = date, occurredAt = "${date}T12:00:00+08:00", name = "示例饮食", calories = energy)
    }
    private fun exercise(day: Int, calories: Double? = null, met: Double? = 4.0) =
        meal(day).copy(kind = Kinds.EXERCISE, name = "示例运动", calories = calories, amount = 30.0, met = met)
    private fun days(rows: List<Record>, mode: PredictionMode = PredictionMode.DAILY, end: Int = 3, from: Int = 0, p: ModelProfile = profile) =
        Trends.days(rows, emptyList(), p, start.plusDays(from.toLong()), start.plusDays(end.toLong()), mode)
    @Test fun calendarGapsStayNullAndDoNotBecomeZeroMeasurements() {
        val rows = days(listOf(weight(0), weight(2, 89.5)), end = 2)
        assertEquals(3, rows.size)
        assertNull(rows[1].weight); assertNull(rows[1].intake); assertNull(rows[1].energy.deficit)
        assertEquals(-0.5, Trends.weightChange(rows)!!, 1e-10)
        assertNull(Trends.weightChange(rows.take(1)))
    }
    @Test fun separateBmrActivityAndExtraExerciseSumToTotal() {
        val d = days(listOf(weight(0), meal(0), exercise(0, 300.0)), end = 0).single()
        assertEquals(1817.5, d.energy.bmr!!, 1e-8)
        assertEquals(363.5, d.activity!!, 1e-8)
        assertEquals(300.0, d.energy.exercise, 1e-8)
        assertEquals(2481.0, d.energy.total!!, 1e-8)
        assertEquals(681.0, d.energy.deficit!!, 1e-8)
    }
    @Test fun dailyPredictionRequiresNoConfirmationAndUsesExactlyPreviousDayIncludingOutsideRange() {
        val d = days(listOf(weight(0), meal(0), weight(1, 89.0)), from = 1, end = 1).single()
        assertEquals(90.0 - 381.0 / 7500, d.prediction!!.predicted, 1e-10)
        assertEquals(start, d.prediction.sourceDate)
        assertEquals(0.9492, d.error!!.kilograms, 1e-10)
    }
    @Test fun dailyEditsAndSettingsRecomputeWhileLegacySnapshotsDoNotOverride() {
        val rows = listOf(weight(0), meal(0), weight(1))
        val archived = EnergyModel.snapshot(rows.take(2), profile, start.toString(), "same_day_estimate").copy(predicted = 50.0)
        val actual = Trends.days(rows, listOf(archived), profile, start, start.plusDays(1))[1]
        assertEquals(89.9492, actual.prediction!!.predicted, 1e-10)
        val changed = days(listOf(weight(0, 91.0), meal(0, 1900.0)), p = profile.copy(lambda = 10000.0))
        assertEquals(91.0 - (2193 - 1900) / 10000.0, changed[1].prediction!!.predicted, 1e-10)
        assertNull(days(listOf(weight(0), meal(0)), p = profile.copy(confirmed = false))[1].prediction)
    }
    @Test fun dailyMissingSourceDoesNotReuseEarlierDaysAndMissingTargetHasNoError() {
        val rows = days(listOf(weight(0), meal(0), weight(2), meal(2)))
        assertNotNull(rows[1].prediction); assertNull(rows[1].error)
        assertNull(rows[2].prediction); assertNull(rows[2].error)
        assertNotNull(rows[3].prediction)
        assertNull(days(listOf(weight(0), meal(0, null)))[1].prediction)
        assertNull(days(listOf(meal(0)))[1].prediction)
        assertNull(days(listOf(weight(0), meal(0), exercise(0, met = null)))[1].prediction)
        assertNotNull(days(listOf(weight(0), meal(0, 0.0)))[1].prediction)
    }
    @Test fun signedErrorsKeepPositiveNegativeAndZeroAndUseLatestMeasurement() {
        val predicted = 89.9492
        for((actual, expected) in listOf(89.0 to 0.9492, 91.0 to -1.0508, predicted to 0.0)) {
            val late = weight(1, actual, "20:00:00")
            val d = days(listOf(weight(0), meal(0), weight(1, 100.0), late))[1]
            assertEquals(late.id, d.error!!.actual.id)
            assertEquals(expected, d.error.kilograms, 1e-9)
        }
    }
    @Test fun cumulativeUsesPredictedWeightForBothBmrAndMetAndNeverReanchors() {
        val inputs = listOf(weight(0), meal(0), exercise(0), meal(1), exercise(1), meal(2), exercise(2))
        val reference = days(inputs, PredictionMode.CUMULATIVE)
        val changed = days(inputs + weight(1, 55.0) + weight(2, 150.0), PredictionMode.CUMULATIVE)
        var w = 90.0
        for(i in 0..3) {
            assertEquals(w, reference[i].prediction!!.predicted, 1e-10)
            assertEquals(w, changed[i].prediction!!.predicted, 1e-10)
            if(i < 3) {
                val bmr = 10 * w + 6.25 * 170 - 5 * 30 + 5
                val extra = 4 * w * 1.05 * 0.5
                assertEquals(bmr, changed[i].energy.bmr!!, 1e-10)
                assertEquals(extra, changed[i].energy.exercise, 1e-10)
                w -= (bmr * 1.2 + extra - 1800) / 7500
            }
        }
        assertNull(reference[1].error)
        assertTrue(changed[1].error!!.kilograms > 0)
        assertTrue(changed[2].error!!.kilograms < 0)
        assertNull(changed[0].error)
    }
    @Test fun cumulativeAnchorIsEarliestValidMeasurementInsideRange() {
        val first = weight(1, 89.0, "07:00:00")
        val rows = days(listOf(weight(-1), weight(1, 92.0), first, meal(1)), PredictionMode.CUMULATIVE)
        assertNull(rows[0].prediction); assertEquals("before_anchor", rows[0].predictionStatus)
        assertEquals(first.id, rows[1].anchor!!.id)
        assertEquals(first.id, rows[1].weight!!.id)
        assertEquals(89.0, rows[1].prediction!!.predicted, 0.0)
        assertNull(rows[1].error)
        assertTrue(days(listOf(weight(-1)), PredictionMode.CUMULATIVE).all { it.predictionStatus == "no_anchor" })
    }
    @Test fun cumulativeGapsBreakChainAndLaterActualCannotRestartItButRepairCan() {
        val inputs = listOf(weight(0), meal(0), weight(2), meal(2))
        val rows = days(inputs, PredictionMode.CUMULATIVE)
        assertNotNull(rows[1].prediction)
        assertNull(rows[2].prediction); assertNull(rows[3].prediction)
        assertEquals(start.plusDays(1), rows[2].blockedOn)
        assertNotNull(days(inputs + meal(1), PredictionMode.CUMULATIVE)[3].prediction)
        assertNull(days(inputs + meal(1) + exercise(1, met = null), PredictionMode.CUMULATIVE)[2].prediction)
        assertNotNull(days(inputs + meal(1, 0.0), PredictionMode.CUMULATIVE)[2].prediction)
    }
    @Test fun incompleteProfileAndUnphysicalPredictionRemainNull() {
        val inputs = listOf(weight(0), meal(0), meal(1))
        val rows = days(inputs, PredictionMode.CUMULATIVE, p = ModelProfile())
        assertEquals(90.0, rows[0].prediction!!.predicted, 0.0)
        assertNull(rows[1].prediction); assertNull(rows[2].error)
        assertNull(days(listOf(weight(0), meal(0), exercise(0, 1e9)), PredictionMode.CUMULATIVE)[1].prediction)
        assertNull(EnergyModel.calculateAtWeight(listOf(weight(0), meal(0)), profile, null).predicted)
    }
    @Test fun invalidRangeIsRejectedAndEmptySingleDayIsSafe() {
        assertThrows(IllegalArgumentException::class.java) { days(emptyList(), end = -1) }
        assertThrows(IllegalArgumentException::class.java) { days(emptyList(), end = 36600) }
        assertEquals(1, days(emptyList(), PredictionMode.CUMULATIVE, end = 0).size)
    }
    @Test fun histogramIncludesAllSignedSamplesAtEdgesAndHandlesZero() {
        listOf(emptyList(), listOf(0.0), listOf(-1.0, 0.0, 1.0), listOf(-12.0, .001, .002, 50.0)).forEach { values ->
            val result = Trends.distribution(values)
            assertEquals(values.size, result.bins.sumOf { it.count })
            assertTrue(values.all { abs(it) <= result.extent })
        }
        val d = Trends.distribution(listOf(-1.0, 0.0, 1.0))
        assertEquals(1, d.bins.take(4).sumOf { it.count })
        assertEquals(1, d.bins[4].count)
        assertEquals(1, d.bins.drop(5).sumOf { it.count })
        assertNull(Trends.mean(emptyList()))
    }
    @Test fun rangeExportContainsReproduciblePriorInputsAndSeparateCumulativeSignedErrors() {
        val folder = Files.createTempDirectory("trend-export").toFile()
        try {
            val out = ByteArrayOutputStream()
            val sourceWeight = weight(0)
            val input = listOf(sourceWeight, meal(0), weight(1), meal(1), weight(2, 91.0))
            Export.writeZip(out, input, folder, start.plusDays(1), start.plusDays(2), profile)
            val files = mutableMapOf<String, String>()
            ZipInputStream(out.toByteArray().inputStream()).use { zip -> while(true) {
                val item = zip.nextEntry ?: break; files[item.name] = zip.readBytes().toString(Charsets.UTF_8)
            } }
            assertTrue(files.getValue("prediction_context.json").contains(sourceWeight.id))
            assertFalse(files.getValue("records.json").contains(sourceWeight.id))
            fun exportedError(file: String) = files.getValue(file).trimEnd().split("\r\n")[1].substringAfterLast(",").trim('"').toDouble()
            assertEquals(-0.0508, exportedError("prediction_errors.csv"), 1e-10)
            assertFalse(files.getValue("prediction_errors.csv").contains("percent"))
            assertEquals(-1.0508, exportedError("cumulative_errors.csv"), 1e-10)
            assertTrue(files.getValue("cumulative_daily.csv").contains("\"anchor\""))
            assertTrue(files.getValue("manifest.json").contains("\"format_version\":\"2.0\""))
        } finally { folder.deleteRecursively() }
    }
    @Test fun referenceCaloriesConvertMassWithoutFillingUnknownsOrZeros() {
        val rice = ReferenceTables.foods.first { it.id == "168878" }
        assertEquals(325.0, ReferenceTables.foodEnergy(rice, Units.convert("0.25", Units.food[1]))!!, 1e-10)
        assertNull(ReferenceTables.foodEnergy(rice, null))
        assertNull(ReferenceTables.foodEnergy(rice, -5.0))
        assertNull(ReferenceTables.foodEnergy(rice, Double.NaN))
        assertEquals(4.8, ReferenceTables.movements.first { it.id == "17200" }.value, 0.0)
    }
}
