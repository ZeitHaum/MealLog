package com.self.diet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate
import kotlin.math.sin

class TrendsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val end = LocalDate.parse("2026-10-09")
    private val profile = ModelProfile(confirmed = true)
    private fun fixture(): Pair<List<Record>, List<AnalysisSnapshot>> {
        val rows = mutableListOf<Record>(); val snapshots = mutableListOf<AnalysisSnapshot>()
        for(i in 0 until 30) {
            val date = end.minusDays(29L - i).toString()
            if(i == 11) continue
            val time = "${date}T08:00:00+08:00"
            val day = mutableListOf(
                Record(kind = Kinds.WEIGHT, date = date, occurredAt = time, createdAt = time, amount = 90 - i * .065 + sin(i * 1.7) * .22),
                Record(kind = Kinds.MEAL, date = date, occurredAt = "${date}T19:00:00+08:00", name = "示例饮食", calories = if(i == 17) null else 2000 + sin(i * .8) * 560)
            )
            if(i % 3 != 0) day.add(Record(kind = Kinds.EXERCISE, date = date, occurredAt = "${date}T18:00:00+08:00", name = "示例运动", amount = 40.0, calories = 180.0 + i % 4 * 50))
            rows.addAll(day)
            if(i != 17) snapshots.add(EnergyModel.snapshot(day, profile, date, "same_day_estimate").copy(generatedAt = "${date}T23:05:00+08:00"))
        }
        return rows to snapshots
    }
    @Test fun dateControlsChartsAndDetailsWorkWithoutTouchingSavedRecords() {
        val (rows, snapshots) = fixture()
        var opened: LocalDate? = null
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF24523E), background = Color(0xFFF6F7F2))) {
                TrendsScreen(rows, snapshots, profile, end, onBack = {}, onOpenDay = { opened = it }, onSettings = {})
            }
        } }
        compose.onNodeWithText("7 天").performClick()
        compose.onNodeWithTag("previous-trend-day").performClick()
        compose.onNodeWithTag("selected-trend-date").assertTextEquals("2026-10-08")
        compose.onNodeWithTag("trends-list").performScrollToNode(hasText("每日变化"))
        compose.onNodeWithTag("chart-WEIGHT").performTouchInput { click(Offset(1f, height / 2f)) }
        compose.onNodeWithTag("selected-trend-date").assertTextEquals("2026-10-03")
        saveScreenshot("trends-weight-ui.png")
        compose.onNodeWithTag("trends-list").performScrollToNode(hasText("预测误差分布"))
        compose.onNodeWithTag("error-histogram").assertExists()
        compose.onNodeWithText("展开逐日误差").performScrollTo().performClick()
        compose.onNodeWithText("收起逐日误差").assertExists()
        saveScreenshot("trends-errors-ui.png")
        compose.onNodeWithText("查看当天").performClick()
        compose.runOnIdle { assertEquals(LocalDate.parse("2026-10-03"), opened) }
    }
    @Test fun exportsReadableCombinedPngForFullSingleAndEmptyData() {
        val (rows, snapshots) = fixture()
        val context = compose.activity
        val folder = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val sets = listOf(
            "trends-sample.png" to Trends.days(rows, snapshots, profile, end.minusDays(29), end),
            "trends-single.png" to Trends.days(rows, snapshots, profile, end, end),
            "trends-empty.png" to Trends.days(emptyList(), emptyList(), profile, end.minusDays(6), end)
        )
        sets.forEach { (name, days) ->
            val file = TrendsImage.create(context, days, profile, preview = true)
            try {
                val bitmap = BitmapFactory.decodeFile(file.path)
                assertNotNull(bitmap); assertEquals(1440, bitmap.width); assertEquals(2700, bitmap.height); bitmap.recycle()
                file.copyTo(File(folder, name), overwrite = true)
            } finally { file.delete() }
        }
    }
    private fun saveScreenshot(name: String) {
        val folder = File(compose.activity.getExternalFilesDir(null), "verification").apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
