package com.self.diet

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

class MissingDataTest {
    private val date = LocalDate.parse("2026-10-09")
    private val profile = ModelProfile(confirmed = true)
    private fun row(kind: String, amount: Double? = null, energy: Double? = null, met: Double? = null) = Record(kind = kind, date = date.toString(), occurredAt = "${date}T08:00:00+08:00", name = "测试", amount = amount, calories = energy, met = met)
    @Test fun emptyAndUnknownSumsStayBlankButExplicitZeroIsPreserved() {
        val empty = EnergyModel.calculate(emptyList(), profile)
        assertNull(empty.knownIntake); assertNull(empty.knownExercise); assertNull(empty.predicted)
        val unknown = EnergyModel.calculate(listOf(row(Kinds.MEAL), row(Kinds.EXERCISE, 30.0)), profile)
        assertNull(unknown.knownIntake); assertNull(unknown.knownExercise); assertNull(unknown.deficit)
        val zero = EnergyModel.calculate(listOf(row(Kinds.MEAL, energy = 0.0), row(Kinds.EXERCISE, 30.0, 0.0)), profile)
        assertEquals(0.0, zero.knownIntake!!, 0.0); assertEquals(0.0, zero.knownExercise!!, 0.0)
    }
    @Test fun mixedMissingFieldsAreSafeAcrossCalculationAndExport() {
        val weights = listOf(emptyList(), listOf(row(Kinds.WEIGHT, 90.0)))
        val meals = listOf(emptyList(), listOf(row(Kinds.MEAL)), listOf(row(Kinds.MEAL, energy = 0.0)), listOf(row(Kinds.MEAL, 200.0, 350.0)), listOf(row(Kinds.MEAL), row(Kinds.MEAL, energy = 350.0)))
        val exercises = listOf(emptyList(), listOf(row(Kinds.EXERCISE, 30.0)), listOf(row(Kinds.EXERCISE, 30.0, 0.0)), listOf(row(Kinds.EXERCISE, 30.0, 200.0)), listOf(row(Kinds.EXERCISE, 30.0, met = 4.0)))
        for(w in weights) for(m in meals) for(e in exercises) for(confirmed in listOf(false, true)) {
            val records = w + m + e
            records.forEach { it.validate() }
            val result = EnergyModel.calculate(records, profile.copy(confirmed = confirmed))
            assertTrue(listOfNotNull(result.weight, result.knownIntake, result.knownExercise, result.bmr, result.baseline, result.total, result.deficit, result.delta, result.predicted).all { it.isFinite() })
            val days = Trends.days(records, emptyList(), profile.copy(confirmed = confirmed), date.minusDays(14), date.plusDays(14))
            assertEquals(29, days.size)
            assertTrue(days.filter { it.date != date }.all { it.weight == null && it.intake == null && it.energy.deficit == null && it.error == null })
            (Export.tables(records).values + Export.analysisTable(records, profile) + Trends.chartTables(days).values).forEach { assertFalse(it.contains("NaN")); assertFalse(it.contains("Infinity")) }
        }
    }
    @Test fun noActualNextDayDoesNotPairPredictionWithLaterMeasurement() {
        val source = listOf(row(Kinds.WEIGHT, 90.0), row(Kinds.MEAL, energy = 1800.0))
        val snapshot = EnergyModel.snapshot(source, profile, date.toString(), "same_day_estimate").copy(generatedAt = "${date}T23:00:00+08:00")
        val laterDate = date.plusDays(3).toString()
        val later = row(Kinds.WEIGHT, 89.5).copy(date = laterDate, occurredAt = "${laterDate}T08:00:00+08:00", createdAt = "${laterDate}T08:05:00+08:00")
        val days = Trends.days(source + later, listOf(snapshot), profile, date, date.plusDays(3))
        assertNotNull(days[1].prediction); assertNull(days[1].weight)
        assertTrue(days.all { it.error == null })
    }
    @Test fun zipWithLongGapsAndOptionalFieldsKeepsBlankCsvAndNullJson() {
        val folder = Files.createTempDirectory("missing-data-export").toFile()
        try {
            val out = ByteArrayOutputStream()
            Export.writeZip(out, listOf(row(Kinds.MEAL)), folder, date.minusDays(30), date, profile)
            val files = mutableMapOf<String, String>()
            ZipInputStream(out.toByteArray().inputStream()).use { zip -> while(true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes().toString(Charsets.UTF_8) } }
            assertTrue(files.getValue("records.json").contains("\"amount\":null"))
            assertTrue(files.getValue("records.json").contains("\"energy_kcal\":null"))
            val rows = files.getValue("trend_daily.csv").removePrefix("\uFEFF").trimEnd().split("\r\n")
            assertEquals(32, rows.size)
            val fields = rows.last().removeSurrounding("\"").split("\",\"")
            assertEquals("", fields[2]); assertEquals("", fields[4]); assertEquals("", fields[7]); assertEquals("", fields[9])
            assertEquals("incomplete", fields.last())
        } finally { folder.deleteRecursively() }
    }
}
