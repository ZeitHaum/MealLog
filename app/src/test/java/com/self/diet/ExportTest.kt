package com.self.diet

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipInputStream

class ExportTest {
    private fun record(kind: String, time: String = "2026-10-08T08:00:00+08:00", amount: Double? = null, calories: Double? = null, name: String = "样本", photos: String = "") = Record(kind = kind, occurredAt = time, date = time.substring(0, 10), amount = amount, calories = calories, name = name, photos = photos)
    @Test fun convertsStandardUnitsWithoutConfusingMassAndEnergy() {
        assertEquals(90.0, Units.convert("180", Units.weight[1])!!, 1e-10)
        assertEquals(45.359237, Units.convert("100", Units.weight[2])!!, 1e-10)
        assertEquals(250.0, Units.convert("0.25", Units.food[1])!!, 1e-10)
        assertEquals(150.0, Units.convert("3", Units.food[3])!!, 1e-10)
        assertEquals(90.0, Units.convert("1.5", Units.duration[1])!!, 1e-10)
        assertEquals(2.0, Units.convert("120", Units.duration[2])!!, 1e-10)
        assertEquals(100.0, Units.convert("418.4", Units.energy[1])!!, 1e-10)
    }
    @Test fun blankUnknownAndKnownZeroRemainDifferent() {
        assertNull(Units.convert("", Units.energy[0], optional = true, allowZero = true))
        assertEquals(0.0, Units.convert("0", Units.energy[0], optional = true, allowZero = true)!!, 0.0)
        for(value in listOf("NaN", "Infinity", "-1", "abc")) {
            assertThrows(IllegalArgumentException::class.java) { Units.convert(value, Units.energy[0], optional = true, allowZero = true) }
        }
        val result = Export.tables(listOf(record(Kinds.MEAL), record(Kinds.MEAL, calories = 0.0)))
        val daily = parseCsv(result.getValue("daily_summary.csv"))
        val row = daily[0].zip(daily[1]).toMap()
        assertEquals("0", row["known_energy_kcal"])
        assertEquals("1", row["energy_missing_count"])
        assertEquals("", row["known_food_weight_g"])
    }
    @Test fun latestMeasurementUsesActualTimeAndKeepsAllMeasurements() {
        val data = listOf(record(Kinds.WEIGHT, "2026-10-08T08:00:00+08:00", 90.0), record(Kinds.WEIGHT, "2026-10-08T07:30:00+07:00", 89.9))
        val table = parseCsv(Export.tables(data).getValue("daily_summary.csv"))
        val values = table[0].zip(table[1]).toMap()
        assertEquals("89.9", values["latest_weight_kg"])
        assertEquals("2", values["weight_measurement_count"])
        assertEquals(3, parseCsv(Export.tables(data).getValue("weights.csv")).size)
    }
    @Test fun zipPreservesChineseQuotesMultilinePhotosAndDateRange() {
        val dir = Files.createTempDirectory("diet-export-test").toFile()
        val photo = "11111111-1111-1111-1111-111111111111.jpg"
        java.io.File(dir, photo).writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            val included = record(Kinds.MEAL, amount = 250.0, name = "米饭,\"小碗\"\n备注", photos = photo)
            val excluded = record(Kinds.MEAL, "2026-10-09T08:00:00+08:00", amount = 500.0)
            val buffer = ByteArrayOutputStream()
            Export.writeZip(buffer, listOf(included, excluded), dir, LocalDate.parse("2026-10-08"), LocalDate.parse("2026-10-08"))
            val files = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(buffer.toByteArray())).use { zip -> while(true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() } }
            assertEquals(setOf("weights.csv", "meals.csv", "exercises.csv", "daily_summary.csv", "analysis.csv", "trend_daily.csv", "prediction_errors.csv", "prediction_snapshots.csv", "model_settings.json", "records.json", "manifest.json", "README.txt", "photos/$photo"), files.keys)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), files["photos/$photo"])
            val meals = parseCsv(files.getValue("meals.csv").toString(Charsets.UTF_8))
            assertEquals(2, meals.size)
            assertEquals(included.name, meals[1][3])
            assertEquals("", meals[1][5])
            assertEquals("photos/$photo", meals[1][6])
            assertTrue(files.getValue("records.json").toString(Charsets.UTF_8).contains("\\\"小碗\\\"\\n"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsMissingPhotoRatherThanSilentlyLosingIt() {
        val dir = Files.createTempDirectory("diet-photo-test").toFile()
        try { assertThrows(IllegalArgumentException::class.java) { Export.writeZip(ByteArrayOutputStream(), listOf(record(Kinds.MEAL, photos = "11111111-1111-1111-1111-111111111111.jpg")), dir, LocalDate.parse("2026-10-08"), LocalDate.parse("2026-10-08")) } }
        finally { dir.deleteRecursively() }
    }
    @Test fun spreadsheetTextCannotBecomeFormulaAndRawJsonKeepsOriginal() {
        val csv = parseCsv(Export.tables(listOf(record(Kinds.MEAL, name = "=1+1"))).getValue("meals.csv"))
        assertEquals("'=1+1", csv[1][3])
    }
    private fun parseCsv(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val value = StringBuilder(); var quoted = false; var i = 0
        val text = input.removePrefix("\uFEFF")
        while(i < text.length) { val c = text[i]; when {
            c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { value.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { row.add(value.toString()); value.clear() }
            c == '\r' && !quoted -> { row.add(value.toString()); value.clear(); rows.add(row); row = mutableListOf(); if(i + 1 < text.length && text[i + 1] == '\n') i++ }
            else -> value.append(c)
        }; i++ }
        return rows
    }
}
