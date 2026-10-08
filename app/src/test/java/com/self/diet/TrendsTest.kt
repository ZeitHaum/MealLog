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
    private fun weight(date: String, value: Double = 90.0, time: String = "08:00:00") = Record(kind = Kinds.WEIGHT, date = date, occurredAt = "${date}T$time+08:00", createdAt = "${date}T$time+08:00", amount = value)
    private fun meal(date: String, energy: Double? = 1800.0) = Record(kind = Kinds.MEAL, date = date, occurredAt = "${date}T12:00:00+08:00", name = "饭", calories = energy)
    private fun snapshot(predicted: Double = 89.0, generated: String = "2026-10-01T23:00:00+08:00", mode: String = "same_day_estimate") = EnergyModel.snapshot(listOf(weight("2026-10-01"), meal("2026-10-01")), profile, "2026-10-01", mode).copy(predicted = predicted, generatedAt = generated)
    @Test fun calendarGapsStayNullAndDoNotBecomeZeroMeasurements() {
        val rows = Trends.days(listOf(weight("2026-10-01"), weight("2026-10-03", 89.5)), emptyList(), profile, start, start.plusDays(2))
        assertEquals(3, rows.size)
        assertNull(rows[1].weight); assertNull(rows[1].intake); assertNull(rows[1].energy.deficit)
        assertEquals(-0.5, Trends.weightChange(rows)!!, 1e-10)
        assertNull(Trends.weightChange(rows.take(1)))
        assertEquals(start.plusDays(1), rows[1].date)
    }
    @Test fun separateBmrActivityAndExtraExerciseSumToTotal() {
        val exercise = Record(kind = Kinds.EXERCISE, date = start.toString(), occurredAt = "${start}T18:00:00+08:00", name = "跑步", amount = 30.0, calories = 300.0)
        val day = Trends.days(listOf(weight(start.toString()), meal(start.toString()), exercise), emptyList(), profile, start, start).single()
        assertEquals(1817.5, day.energy.bmr!!, 1e-8)
        assertEquals(363.5, day.activity!!, 1e-8)
        assertEquals(300.0, day.energy.exercise, 1e-8)
        assertEquals(day.energy.total!!, day.energy.bmr!! + day.activity!! + day.energy.exercise, 1e-8)
        assertEquals(681.0, day.energy.deficit!!, 1e-8)
        val unknown = Trends.days(listOf(weight(start.toString()), meal(start.toString()), meal(start.toString(), null)), emptyList(), profile, start, start).single()
        assertNull(unknown.intake); assertNull(unknown.energy.deficit)
    }
    @Test fun predictionTargetsNextDayAndRetainsSavedParameters() {
        val actual = weight("2026-10-02", 88.0)
        val rows = Trends.days(listOf(actual), listOf(snapshot()), profile.copy(lambda = 12000.0), start.plusDays(1), start.plusDays(1))
        val error = rows.single().error!!
        assertEquals(1.0, error.kilograms, 1e-9)
        assertEquals(100.0 / 88.0, error.percent, 1e-9)
        assertEquals(7500.0, error.snapshot.lambda, 1e-9)
    }
    @Test fun rejectsHindsightAndChoosesOnlyOneLastEligibleSnapshotPerDay() {
        val actual = weight("2026-10-02", 89.0)
        val valid = snapshot()
        val newer = snapshot(88.7, "2026-10-01T23:30:00+08:00")
        val candidates = listOf(valid, newer, snapshot(89.0, "2026-10-02T10:00:00+08:00"), snapshot(89.0, mode = "retrospective"), snapshot(89.0, mode = "planned"))
        val day = Trends.days(listOf(actual), candidates, profile, start.plusDays(1), start.plusDays(1)).single()
        assertEquals(newer.id, day.prediction!!.id)
        assertEquals(-0.3, day.error!!.kilograms, 1e-9)
        // A target-day early weigh-in with a different offset has an earlier instant than the snapshot.
        val early = actual.copy(id = "early", occurredAt = "2026-10-02T00:15:00+10:00", createdAt = "2026-10-02T00:16:00+10:00")
        assertNull(Trends.days(listOf(actual, early), candidates, profile, start.plusDays(1), start.plusDays(1)).single().prediction)
    }
    @Test fun actualIsLatestMeasurementAndNoPredictionOrMeasurementMeansNoError() {
        val a = weight("2026-10-02", 89.1)
        val b = weight("2026-10-02", 88.9, "20:00:00")
        val day = Trends.days(listOf(b, a), listOf(snapshot()), profile, start.plusDays(1), start.plusDays(1)).single()
        assertEquals(b.id, day.error!!.actual.id)
        assertNull(Trends.days(emptyList(), listOf(snapshot()), profile, start.plusDays(1), start.plusDays(1)).single().error)
        assertNull(Trends.days(listOf(a), emptyList(), profile, start.plusDays(1), start.plusDays(1)).single().error)
    }
    @Test fun histogramIncludesAllSamplesAtEdgesAndHandlesZeroAndOutliers() {
        listOf(emptyList(), listOf(0.0), listOf(-1.0, 0.0, 1.0), listOf(-12.0, .001, .002, 50.0)).forEach { values ->
            val result = Trends.distribution(values)
            assertEquals(values.size, result.bins.sumOf { it.count })
            assertTrue(result.extent > 0)
            assertTrue(values.all { abs(it) <= result.extent })
        }
        assertEquals(1, Trends.distribution(listOf(0.0)).bins[4].count)
        assertNull(Trends.mean(emptyList()))
    }
    @Test fun rangeExportIncludesPriorDaySnapshotUsedForErrorAndCsvSigns() {
        val folder = Files.createTempDirectory("trend-export").toFile()
        try {
            val out = ByteArrayOutputStream()
            val snapshot = snapshot(89.0)
            Export.writeZip(out, listOf(weight("2026-10-02", 90.0)), folder, start.plusDays(1), start.plusDays(1), profile, listOf(snapshot))
            val files = mutableMapOf<String, String>()
            ZipInputStream(out.toByteArray().inputStream()).use { zip -> while(true) { val item = zip.nextEntry ?: break; files[item.name] = zip.readBytes().toString(Charsets.UTF_8) } }
            assertTrue(files.getValue("prediction_snapshots.csv").contains(snapshot.id))
            assertTrue(files.getValue("prediction_errors.csv").contains("\"-1\""))
            assertTrue(files.getValue("trend_daily.csv").contains("\"89\""))
        } finally { folder.deleteRecursively() }
    }
}
