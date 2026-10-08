package com.self.diet

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun TrendsScreen(records: List<Record>, snapshots: List<AnalysisSnapshot>, profile: ModelProfile, initialDate: LocalDate, onBack: () -> Unit, onOpenDay: (LocalDate) -> Unit, onSettings: () -> Unit, preview: Boolean = false) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var window by rememberSaveable { mutableIntStateOf(30) }
    var endString by rememberSaveable { mutableStateOf(initialDate.toString()) }
    val end = LocalDate.parse(endString)
    val start = if(window == 0) records.minOfOrNull { LocalDate.parse(it.date) }?.coerceAtMost(end) ?: end else end.minusDays(window - 1L)
    val days = remember(records, snapshots, profile, start, end) { Trends.days(records, snapshots, profile, start, end) }
    var selectedDate by rememberSaveable { mutableStateOf(initialDate.toString()) }
    val selected = days.indexOfFirst { it.date.toString() == selectedDate }.takeIf { it >= 0 } ?: days.lastIndex
    val picked = days[selected]
    val errors = remember(days) { days.mapNotNull { it.error } }
    val measured = days.count { it.weight != null }
    val deficits = days.mapNotNull { it.energy.deficit }
    var details by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val source = exportFile
        if(uri == null || source == null) { source?.delete(); exportFile = null; exporting = false }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: error("无法写入所选位置") }
                snackbar.showSnackbar("趋势长图已保存")
            } catch(e: Exception) { snackbar.showSnackbar("保存失败：${e.message}") }
            finally { source.delete(); exportFile = null; exporting = false }
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 返回") }
                Text("趋势总览", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    exporting = true
                    scope.launch {
                        try {
                            exportFile = withContext(Dispatchers.IO) { TrendsImage.create(context, days, profile, preview) }
                            imagePicker.launch("Diet_Trends_${start}_${end}.png")
                        } catch(e: Exception) { exporting = false; snackbar.showSnackbar("生成长图失败：${e.message}") }
                    }
                }, enabled = !exporting && days.any { it.hasRecords }) { Text(if(exporting) "生成中" else "保存长图") }
            }
        }
    }, bottomBar = {
        Surface(shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedDate = days[selected - 1].date.toString() }, enabled = selected > 0, modifier = Modifier.testTag("previous-trend-day")) { Text("‹") }
                Column(Modifier.weight(1f)) {
                    Text(picked.date.toString(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.testTag("selected-trend-date"))
                    Text("本区间第 ${selected + 1} 天", color = MutedColor, fontSize = 11.sp)
                }
                TextButton(onClick = { selectedDate = days[selected + 1].date.toString() }, enabled = selected < days.lastIndex, modifier = Modifier.testTag("next-trend-day")) { Text("›") }
                TextButton(onClick = { onOpenDay(picked.date) }, enabled = !preview) { Text("查看当天") }
            }
        }
    }) { inset ->
        LazyColumn(Modifier.fillMaxSize().padding(inset).testTag("trends-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                if(preview) Text("示例预览 · 不写入你的记录", color = IntakeColor, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 30, 90, 0).forEach { size -> FilterChip(selected = window == size, onClick = { window = size }, label = { Text(if(size == 0) "全部" else "$size 天") }) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(start.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) + " — " + end.format(DateTimeFormatter.ofPattern("MM.dd")), color = MutedColor, fontSize = 12.sp)
                    TextButton(onClick = { DatePickerDialog(context, { _, y, m, d -> endString = LocalDate.of(y, m + 1, d).toString(); selectedDate = endString }, end.year, end.monthValue - 1, end.dayOfMonth).show() }) { Text("截止日期") }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = WeightColor), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("这一段时间", color = Color(0xFFCEE0D4), fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryMetric("体重变化", signedNumber(Trends.weightChange(days), 2), "kg · $measured 天测量", Modifier.weight(1f))
                            SummaryMetric("日均缺口", chartNumber(Trends.mean(deficits), 0), "kcal · ${deficits.size} 天可算", Modifier.weight(1f))
                            SummaryMetric("平均绝对误差", chartNumber(Trends.mean(errors.map { abs(it.kilograms) }), 2), "kg · ${errors.size} 天配对", Modifier.weight(1f))
                        }
                    }
                }
            }
            if(!profile.confirmed) item { OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("核对模型设置，启用消耗计算") } }
            item {
                ChartCard {
                    Text("每日变化", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("三图共用日期轴 · 点击图表联动选日", fontSize = 12.sp, color = MutedColor)
                    ChartHeading("01", "体重", "${chartNumber(picked.weight?.amount, 2)} kg")
                    LegendRow(listOf("实际体重" to WeightColor, "已存预测 · 虚线" to PredictionColor))
                    DailyChart(days, DailyChartKind.WEIGHT, selected, { selectedDate = days[it].date.toString() }, showXAxis = false)
                    Text("当日已存预测 ${chartNumber(picked.prediction?.predicted, 3)} kg · 误差 ${signedNumber(picked.error?.kilograms, 3)} kg", fontSize = 11.sp, color = MutedColor)
                    HorizontalDivider(color = GridColor)
                    ChartHeading("02", "摄入与消耗", "kcal / 天")
                    LegendRow(listOf("摄入 · 折线" to IntakeColor, "基础代谢" to WeightColor))
                    LegendRow(listOf("日常活动" to ActivityColor, "额外运动" to ExerciseColor))
                    DailyChart(days, DailyChartKind.ENERGY, selected, { selectedDate = days[it].date.toString() }, showXAxis = false)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DetailMetric("当日摄入", chartNumber(picked.intake, 0), Modifier.weight(1f))
                        DetailMetric("总消耗", chartNumber(picked.energy.total, 0), Modifier.weight(1f))
                    }
                    Text("基础 ${chartNumber(picked.energy.bmr, 0)} + 日常活动 ${chartNumber(picked.activity, 0)} + 额外运动 ${chartNumber(picked.energy.knownExercise, 0)}", fontSize = 11.sp, color = MutedColor)
                    HorizontalDivider(color = GridColor)
                    ChartHeading("03", "热量缺口", "${signedNumber(picked.energy.deficit, 0)} kcal")
                    LegendRow(listOf("正值 · 缺口" to WeightColor, "负值 · 盈余" to IntakeColor))
                    DailyChart(days, DailyChartKind.DEFICIT, selected, { selectedDate = days[it].date.toString() })
                    Text("缺测留空，不当作 0。体重取每日最后一次；淡色消耗柱表示有缺项。日常活动 = 基础代谢 ×（活动系数 − 1），额外运动另外相加。", fontSize = 11.sp, color = MutedColor)
                }
            }
            item {
                ChartCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("预测误差分布", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${errors.size} 天", fontSize = 12.sp, color = MutedColor)
                    }
                    Text("负值：预测偏轻　正值：预测偏重", fontSize = 12.sp, color = MutedColor)
                    ErrorHistogram(errors)
                    ErrorMetric("平均绝对相对误差", "${chartNumber(Trends.mean(errors.map { abs(it.percent) }), 3)}%")
                    ErrorMetric("平均有向误差", "${signedNumber(Trends.mean(errors.map { it.percent }), 3)}%")
                    Text("相对误差 =（预测 − 实际）÷ 实际 × 100%。仅采用前一日保存、且早于目标日首次称重及录入的最后一份预测；不纳入回算和计划估计。", fontSize = 11.sp, color = MutedColor)
                    if(errors.size in 1..4) Text("样本还少，先积累更多天再判断分布。", fontSize = 12.sp, color = IntakeColor)
                    if(errors.isNotEmpty()) TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth()) { Text(if(details) "收起逐日误差" else "展开逐日误差") }
                }
            }
            if(details) items(errors.reversed(), key = { "error-${it.date}" }) { error ->
                Card(onClick = { if(!preview) onOpenDay(error.date) }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(error.date.toString(), fontWeight = FontWeight.SemiBold); Text("${signedNumber(error.percent, 3)}%", color = if(error.percent <= 0) WeightColor else IntakeColor) }
                        Text("预测 ${chartNumber(error.snapshot.predicted, 3)} → 实际 ${chartNumber(error.actual.amount, 3)} kg", fontSize = 13.sp)
                        Text("误差 ${signedNumber(error.kilograms, 3)} kg · 预测保存于 ${OffsetDateTime.parse(error.snapshot.generatedAt).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}", fontSize = 11.sp, color = MutedColor)
                    }
                }
            }
            item { Text("能量收支按当前模型参数和已记录数据试算，不代表当天已全部录完。预测与误差保留各次保存时的参数。不同称重时刻和水分变化也会影响误差。", color = MutedColor, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        }
    }
}

@Composable private fun SummaryMetric(label: String, value: String, hint: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(label, color = Color(0xFFCEE0D4), fontSize = 11.sp); Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(hint, color = Color(0xFFCEE0D4), fontSize = 10.sp) }
}
@Composable private fun DetailMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(label, color = MutedColor, fontSize = 11.sp); Text(value, color = WeightColor, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
}
@Composable private fun ErrorMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp, color = MutedColor)
        Text(value, color = WeightColor, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}
@Composable private fun ChartHeading(index: String, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text(index, color = MutedColor, fontSize = 11.sp); Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp)); Text(value, color = WeightColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
}
@Composable private fun ChartCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(24.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}
@Composable private fun LegendRow(entries: List<Pair<String, Color>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { entries.forEach { (label, color) ->
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { Canvas(Modifier.size(8.dp)) { drawCircle(color) }; Text(label, fontSize = 10.sp, color = MutedColor, modifier = Modifier.padding(start = 5.dp)) }
    } }
}
