package com.self.diet

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class MissingDataUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val date = LocalDate.parse("2026-10-09")
    private val profile = ModelProfile(confirmed = true)
    private fun row(kind: String, amount: Double? = null, energy: Double? = null, met: Double? = null) = Record(kind = kind, date = date.toString(), occurredAt = "${date}T08:00:00+08:00", name = "空值测试", amount = amount, calories = energy, met = met)
    @Test fun entirelyEmptyRangeCanScrollSwitchRangesAndOpenSelectedDay() {
        compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme {
            TrendsScreen(emptyList(), emptyList(), profile, date, onBack = {}, onOpenDay = {}, onSettings = {})
        } } }
        compose.onNodeWithText("保存长图").assertIsNotEnabled()
        for(label in listOf("全部", "7 天", "90 天", "30 天")) compose.onNodeWithText(label).performClick()
        compose.onNodeWithTag("previous-trend-day").performClick()
        compose.onNodeWithTag("selected-trend-date").assertTextEquals("2026-10-08")
        compose.onNodeWithTag("trends-list").performScrollToNode(hasText("预测误差分布"))
        compose.onNodeWithTag("error-histogram").assertDoesNotExist()
        compose.onNodeWithText("展开逐日误差").assertDoesNotExist()
        compose.onNodeWithText("查看当天").performClick()
    }
    @Test fun sparseDaysAndAnalysisRenderWithoutCrashing() {
        val scenarios = listOf(
            emptyList(), listOf(row(Kinds.MEAL)), listOf(row(Kinds.WEIGHT, 90.0)),
            listOf(row(Kinds.EXERCISE, 30.0, met = 4.0)),
            listOf(row(Kinds.WEIGHT, 90.0), row(Kinds.MEAL, energy = 500.0), row(Kinds.EXERCISE, 30.0))
        )
        val index = mutableIntStateOf(0)
        val page = mutableIntStateOf(0)
        compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme {
            if(page.intValue == 0) TrendsScreen(scenarios[index.intValue], emptyList(), profile, date, onBack = {}, onOpenDay = {}, onSettings = {})
            else Column(Modifier.verticalScroll(rememberScrollState())) { AnalysisPanel(scenarios[index.intValue], date, profile, emptyList(), onSettings = {}, onSave = {}) }
        } } }
        scenarios.indices.forEach { i ->
            compose.runOnIdle { index.intValue = i; page.intValue = 0 }
            compose.onNodeWithText("趋势总览").assertExists()
            compose.onNodeWithTag("trends-list").performScrollToNode(hasText("每日变化"))
            compose.runOnIdle { page.intValue = 1 }
            compose.onNodeWithText("保存当日预测快照").assertIsNotEnabled()
            compose.onNodeWithText("待补全数据").assertExists()
            if(i == 0) compose.onNodeWithText("0 kcal").assertDoesNotExist()
        }
    }
    @Test fun emptyRequiredInputShowsValidationInsteadOfCrashing() {
        compose.onNodeWithText("＋ 体重").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("请输入有效数字").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("＋ 运动").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("请输入有效数字").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("食记").assertExists()
    }
}
