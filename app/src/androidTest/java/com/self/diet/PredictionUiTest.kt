package com.self.diet

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate

class PredictionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val end = LocalDate.parse("2026-10-14")
    private val profile = ModelProfile(confirmed = true)
    private fun fixture() = (0..6).flatMap { i ->
        val date = end.minusDays(6L - i).toString()
        listOf(
            Record(kind = Kinds.WEIGHT, date = date, occurredAt = "${date}T08:00:00+08:00", amount = 80.0 - i * .08 + if(i % 2 == 0) .15 else -.15),
            Record(kind = Kinds.MEAL, date = date, occurredAt = "${date}T19:00:00+08:00", name = "示例饮食", calories = 1800.0),
            Record(kind = Kinds.EXERCISE, date = date, occurredAt = "${date}T18:00:00+08:00", name = "示例运动", amount = 40.0, met = 4.8)
        )
    }
    @Test fun cumulativeModeAndCustomRangeKeepSelectionAndSignedErrors() {
        compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF24523E), background = Color(0xFFF6F7F2))) {
            TrendsScreen(fixture(), emptyList(), profile, end, onBack = {}, onOpenDay = {}, onSettings = {}, preview = true)
        } } }
        compose.onNodeWithText("累计预测").performClick()
        compose.onNodeWithText("7 天").performClick()
        compose.onNodeWithText("自定义区间").performClick()
        compose.onNodeWithText("开始：2026-10-08").assertExists()
        compose.onNodeWithText("结束：2026-10-14").assertExists()
        compose.onNodeWithText("应用区间").performClick()
        compose.onNodeWithTag("selected-trend-date").assertTextEquals("2026-10-14")
        screenshot("cumulative-overview.png")
        compose.onNodeWithTag("trends-list").performScrollToNode(hasText("每日变化"))
        compose.onNodeWithTag("chart-ERROR").performScrollTo().assertExists()
        screenshot("cumulative-chart.png")
        compose.onNodeWithTag("trends-list").performScrollToNode(hasText("预测误差分布"))
        compose.onNodeWithTag("error-histogram").assertExists()
        compose.onNodeWithText("展开逐日误差").performScrollTo().performClick()
        compose.onNodeWithText("误差 = 预测 − 实际，单位 kg，保留正负号。只统计两者都有值的日期；累计起点不参与。").assertExists()
        screenshot("cumulative-errors.png")
    }
    @Test fun emptyCumulativeRangeStaysUsable() {
        compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF24523E), background = Color(0xFFF6F7F2))) {
            TrendsScreen(emptyList(), emptyList(), profile, end, onBack = {}, onOpenDay = {}, onSettings = {})
        } } }
        compose.onNodeWithText("累计预测").performClick()
        compose.onNodeWithText("此区间没有有效的起点体重").assertExists()
        compose.onNodeWithText("保存长图").assertIsNotEnabled()
        compose.onNodeWithTag("trends-list").performScrollToNode(hasText("预测误差分布"))
        compose.onNodeWithTag("error-histogram").assertDoesNotExist()
    }
    @Test fun analysisShowsNextDayPredictionWithoutConfirmation() {
        compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF24523E), background = Color(0xFFF6F7F2))) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AnalysisPanel(fixture().take(3), end.minusDays(6), profile, emptyList(), onSettings = {}, onSave = {})
            }
        } } }
        compose.onNodeWithText("已记录全天饮食和额外运动").assertDoesNotExist()
        compose.onNodeWithText("另存计算快照（可选）").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("预测已自动更新，无需确认或保存。上方按钮仅用于另存一份历史快照，不影响趋势图。").assertExists()
    }
    @Test fun mealReferenceAppliesConvertedAmountAndCanBeEditedBeforeSaving() {
        var saved: Record? = null
        compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF24523E), background = Color(0xFFF6F7F2))) {
            RecordEditor(Kinds.MEAL, end, null, false, onDismiss = {}, onSave = { r -> saved = r })
        } } }
        compose.onNodeWithText("食物重量（可选）").performTextInput("250")
        compose.onNodeWithText("热量参考").performScrollTo().performClick()
        screenshot("food-reference.png")
        compose.onNodeWithText("采用 325.0 kcal").performClick()
        compose.onNodeWithText("热量（可选）").performScrollTo().performTextReplacement("330")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertNotNull(saved); assertEquals("白米饭", saved!!.name)
            assertEquals(330.0, saved!!.calories!!, 0.0)
            assertEquals(250.0, saved!!.amount!!, 0.0)
            assertTrue(saved!!.note.contains("168878"))
        }
    }
    @Test fun metReferenceSearchAppliesToExerciseEditor() {
        var saved: Record? = null
        compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF24523E), background = Color(0xFFF6F7F2))) {
            RecordEditor(Kinds.EXERCISE, end, null, false, onDismiss = {}, onSave = { r -> saved = r })
        } } }
        compose.onNodeWithText("运动时长").performTextInput("30")
        compose.onNodeWithText("运动参考").performScrollTo().performClick()
        compose.onNodeWithText("搜索名称或条件").performTextInput("快走")
        screenshot("met-reference.png")
        compose.onNodeWithText("采用").performClick()
        compose.onNodeWithText("运动强度 MET（可选）").performScrollTo().assertTextContains("4.8")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals("快走", saved!!.name); assertEquals(4.8, saved!!.met!!, 0.0)
            assertNull(saved!!.calories)
        }
    }
    @Test fun referenceWithoutFoodWeightCannotInventCalories() {
        compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF24523E), background = Color(0xFFF6F7F2))) {
            ReferenceDialog(true, grams = null, onDismiss = {}, onApply = { _, _ -> fail("No mass") })
        } } }
        compose.onNodeWithText("搜索名称或条件").performTextInput("白米饭")
        compose.onNodeWithText("采用").assertIsNotEnabled()
        compose.onNodeWithText("搜索名称或条件").performTextReplacement("不存在的食物")
        compose.onNodeWithText("暂无匹配项目，可手动填写数值。").assertExists()
    }
    private fun screenshot(name: String) {
        val folder = File(compose.activity.getExternalFilesDir(null), "verification").apply { mkdirs() }
        compose.waitForIdle()
        android.os.SystemClock.sleep(600) // Wait for Android dialog/IME window animations before capturing.
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
