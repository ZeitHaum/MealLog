package com.self.diet

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

class CalendarUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val today = LocalDate.parse("2026-10-09")
    private fun fixture(): List<Record> = buildList {
        for(day in 1..9) {
            if(day == 4) continue
            val date = "2026-10-${day.toString().padStart(2, '0')}"
            val time = "${date}T08:00:00+08:00"
            add(Record(kind = Kinds.WEIGHT, date = date, occurredAt = time, amount = 90.0 - day * .08 + if(day == 6) .3 else 0.0))
            add(Record(kind = Kinds.MEAL, date = date, occurredAt = time, name = "示例饮食", calories = if(day == 8) null else 1700.0 + day % 3 * 300))
            if(day % 3 != 0) add(Record(kind = Kinds.EXERCISE, date = date, occurredAt = time, name = "示例运动", amount = 30.0, calories = 180.0 + day % 2 * 80))
        }
    }
    @Test fun metricsWarningsAndMonthNavigationWorkWithSparseRecords() {
        val month = mutableStateOf(YearMonth.from(today))
        val metric = mutableStateOf(CalendarMetric.WEIGHT)
        var opened: LocalDate? = null
        compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF24523E), background = Color(0xFFF6F7F2))) {
            CalendarScreen(fixture(), ModelProfile(confirmed = true), month.value, metric.value, today,
                onMonth = { month.value = it }, onMetric = { metric.value = it }, onOpenDay = { opened = it }, onBack = {}, onSettings = {}, today = today, preview = true)
        } } }
        compose.onNodeWithTag("calendar-warning-2026-10-04", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("calendar-warning-2026-10-10", useUnmergedTree = true).assertDoesNotExist()
        saveScreenshot("calendar-weight-ui.png")
        CalendarMetric.entries.forEach {
            compose.onNodeWithTag("calendar-metric-${it.name}").performClick().assertIsSelected()
        }
        compose.onNodeWithTag("calendar-warning-2026-10-08", useUnmergedTree = true).assertExists()
        saveScreenshot("calendar-deficit-ui.png")
        compose.onNodeWithTag("calendar-day-2026-10-04").performClick()
        compose.runOnIdle { assertEquals(LocalDate.parse("2026-10-04"), opened) }
        compose.onNodeWithTag("previous-month").performClick()
        compose.onNodeWithTag("calendar-day-2026-09-30").assertExists()
        compose.onNodeWithTag("next-month").performClick()
        compose.onNodeWithTag("choose-month").performClick()
        compose.onNodeWithTag("calendar-previous-year").performClick().performClick()
        compose.onNodeWithTag("calendar-jump-2").performClick()
        compose.onNodeWithTag("calendar-day-2024-02-29").assertExists()
        compose.onNodeWithText("本月").performClick()
        compose.runOnIdle { assertEquals(YearMonth.from(today), month.value) }
    }
    @Test fun emptyCalendarCanSwitchEveryMetricAndOpenEmptyDay() {
        val metric = mutableStateOf(CalendarMetric.WEIGHT)
        var opened: LocalDate? = null
        compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme {
            CalendarScreen(emptyList(), ModelProfile(), YearMonth.from(today), metric.value, today,
                onMonth = {}, onMetric = { metric.value = it }, onOpenDay = { opened = it }, onBack = {}, onSettings = {}, today = today)
        } } }
        CalendarMetric.entries.forEach {
            compose.onNodeWithTag("calendar-metric-${it.name}").performClick()
            compose.onNodeWithTag("calendar-warning-2026-10-09", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("calendar-warning-2026-10-08", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("calendar-day-2026-10-09").performClick()
        compose.runOnIdle { assertEquals(today, opened) }
    }
    @Test fun actualAppOpensDailyRecordsAndReturnsToSameMonthAndMetric() {
        val lastMonth = YearMonth.now().minusMonths(1)
        compose.onNodeWithText("日历").performClick()
        compose.onNodeWithTag("previous-month").performClick()
        compose.onNodeWithTag("calendar-metric-MEAL").performClick()
        compose.onNodeWithTag("calendar-day-${lastMonth.atDay(2)}").performClick()
        compose.onNodeWithText("每日汇总").assertIsSelected()
        compose.onNodeWithText(lastMonth.atDay(2).toString()).assertExists()
        compose.onNodeWithText("＋ 饮食").assertExists()
        compose.onNodeWithText("‹ 日历").performClick()
        compose.onNodeWithTag("calendar-metric-MEAL").assertIsSelected()
        compose.onNodeWithTag("calendar-day-${lastMonth.atDay(2)}").assertExists()
    }
    private fun saveScreenshot(name: String) {
        val folder = File(compose.activity.getExternalFilesDir(null), "verification").apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
