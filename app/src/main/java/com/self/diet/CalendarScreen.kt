package com.self.diet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

private val CalendarUp = Color(0xFFB65E53)
private val CalendarUpFill = Color(0xFFF7E6DF)
private val CalendarDown = Color(0xFF285E49)
private val CalendarDownFill = Color(0xFFE1EEE3)
private val CalendarWarning = Color(0xFFA46D21)

@Composable
fun CalendarScreen(
    records: List<Record>, profile: ModelProfile, month: YearMonth, metric: CalendarMetric,
    selectedDate: LocalDate, onMonth: (YearMonth) -> Unit, onMetric: (CalendarMetric) -> Unit,
    onOpenDay: (LocalDate) -> Unit, onBack: () -> Unit, onSettings: () -> Unit,
    today: LocalDate = LocalDate.now(), preview: Boolean = false
) {
    BackHandler(onBack = onBack)
    var monthPicker by rememberSaveable { mutableStateOf(false) }
    val days = remember(records, profile, month, metric, today) { DietCalendar.month(records, profile, month, metric, today) }
    val grid = remember(month) { DietCalendar.grid(month) }
    val actual = days.filter { it.complete && !it.date.isAfter(today) }
    val summary = if(metric == CalendarMetric.WEIGHT) {
        if(actual.size >= 2) actual.last().value!! - actual.first().value!! else null
    } else Trends.mean(actual.mapNotNull { it.value })
    val summaryLabel = when(metric) { CalendarMetric.WEIGHT -> "本月体重变化"; CalendarMetric.MEAL -> "日均已记录摄入"; CalendarMetric.EXERCISE -> "日均已记录运动消耗"; CalendarMetric.DEFICIT -> "日均热量缺口" }
    Scaffold(topBar = {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 返回") }
                Text("减肥日历", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onMonth(YearMonth.from(today)) }) { Text("本月") }
            }
        }
    }) { inset ->
        LazyColumn(Modifier.fillMaxSize().padding(inset).testTag("calendar-list"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if(preview) Text("示例预览 · 不写入你的记录", fontSize = 12.sp, color = CalendarWarning, modifier = Modifier.padding(bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onMonth(month.minusMonths(1)) }, modifier = Modifier.testTag("previous-month")) { Text("‹", fontSize = 24.sp) }
                    TextButton(onClick = { monthPicker = true }, modifier = Modifier.weight(1f).testTag("choose-month")) { Text("${month.year} 年 ${month.monthValue} 月 ⌄", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { onMonth(month.plusMonths(1)) }, modifier = Modifier.testTag("next-month")) { Text("›", fontSize = 24.sp) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFEBEEE7)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    CalendarMetric.entries.forEach { item ->
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if(metric == item) WeightColor else Color.Transparent).clickable(role = Role.Tab) { onMetric(item) }.semantics { selected = metric == item }.padding(vertical = 12.dp).testTag("calendar-metric-${item.name}"), contentAlignment = Alignment.Center) {
                            Text(item.label, color = if(metric == item) Color.White else MutedColor, fontWeight = if(metric == item) FontWeight.SemiBold else FontWeight.Normal, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(WeightColor).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(summaryLabel, color = Color(0xFFCDDFC9), fontSize = 11.sp)
                        Text("${if(metric == CalendarMetric.WEIGHT || metric == CalendarMetric.DEFICIT) signedNumber(summary, if(metric == CalendarMetric.WEIGHT) 2 else 0) else chartNumber(summary, 0)} ${metric.unit}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${actual.size} 天有值", color = Color.White, fontSize = 12.sp)
                        Text("${days.count { it.needsAttention }} 天待补", color = Color(0xFFE6CC91), fontSize = 12.sp)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label -> Text(label, Modifier.weight(1f), textAlign = TextAlign.Center, color = if(index >= 5) CalendarWarning else MutedColor, fontSize = 12.sp) }
                        }
                        grid.chunked(7).forEach { week ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                week.forEach { date ->
                                    if(date == null) Spacer(Modifier.weight(1f).height(72.dp))
                                    else CalendarCell(days[date.dayOfMonth - 1], metric, today, selectedDate, Modifier.weight(1f), onClick = { onOpenDay(date) })
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CalendarKey(if(metric == CalendarMetric.DEFICIT) "热量缺口" else "下降", CalendarDown)
                    CalendarKey(if(metric == CalendarMetric.DEFICIT) "热量盈余" else "上升", CalendarUp)
                    CalendarKey("缺项", CalendarWarning, warning = true)
                    Text("单位 ${metric.unit}", color = MutedColor, fontSize = 11.sp)
                }
            }
            item {
                Text(when(metric) {
                    CalendarMetric.WEIGHT -> "每天显示最后一次体重，小字为较上一次有效记录日的变化；漏记不补零。"
                    CalendarMetric.MEAL -> "每天显示已记录的摄入热量，小字为较上一次有效记录日的变化；≈ 表示部分热量未填。"
                    CalendarMetric.EXERCISE -> "每天显示额外运动消耗，小字为较上一次有效记录日的变化；未记录不表示没有运动。"
                    CalendarMetric.DEFICIT -> "缺口 = 总消耗 − 摄入，正数为缺口、负数为盈余；小字为较上次有效记录日的变化。按当前模型及已有记录试算。"
                }, color = MutedColor, fontSize = 12.sp)
                Text("点日期查看或补记。! 只标记开始记录后至今天的所选项目缺项；未来日期不提醒。日均值只按当天该项已填齐的记录计算。", color = MutedColor, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                if(metric != CalendarMetric.DEFICIT) Text("颜色表示数值升降，不代表好坏。", color = MutedColor, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                if(metric == CalendarMetric.DEFICIT && !profile.confirmed) TextButton(onClick = onSettings) { Text("核对模型设置，启用缺口计算") }
            }
        }
    }
    if(monthPicker) MonthJumpDialog(month, onDismiss = { monthPicker = false }, onSelect = { onMonth(it); monthPicker = false })
}

@Composable
private fun CalendarCell(day: CalendarDay, metric: CalendarMetric, today: LocalDate, selected: LocalDate, modifier: Modifier, onClick: () -> Unit) {
    val signed = metric == CalendarMetric.DEFICIT
    val signal = if(day.complete) if(signed) day.value else day.change else null
    val threshold = if(metric == CalendarMetric.WEIGHT) .005 else .5
    val positive = signal != null && signal > threshold
    val negative = signal != null && signal < -threshold
    val green = if(signed) positive else negative
    val red = if(signed) negative else positive
    val color = if(green) CalendarDown else if(red) CalendarUp else MutedColor
    val fill = if(green) CalendarDownFill else if(red) CalendarUpFill else if(day.date.isAfter(today) && !day.hasRecords) Color(0xFFFAFAF7) else Color(0xFFF1F3EE)
    val value = day.value?.let { (if(!day.complete) "≈" else "") + DietCalendar.compact(it, metric == CalendarMetric.WEIGHT, signed) }
    val change = day.change?.let { signedNumber(it, if(metric == CalendarMetric.WEIGHT) 2 else 0) }
    val description = buildString {
        append("${day.date}，${metric.label}，${day.value?.let { "${Units.display(it)} ${metric.unit}" } ?: "未记录或数据不足"}")
        if(day.change != null) append("，较 ${day.previousDate} 变化 ${Units.display(day.change)} ${metric.unit}")
        if(day.needsAttention) append("，缺项提醒")
        append("，查看当天记录")
    }
    Column(modifier.height(72.dp).clip(RoundedCornerShape(10.dp)).background(fill)
        .then(if(day.date == selected) Modifier.border(1.5.dp, WeightColor, RoundedCornerShape(10.dp)) else Modifier)
        .clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = description }
        .testTag("calendar-day-${day.date}").padding(horizontal = 2.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(Modifier.size(19.dp).then(if(day.date == today) Modifier.background(WeightColor, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                Text(day.date.dayOfMonth.toString(), fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = if(day.date == today) Color.White else if(day.date.isAfter(today)) Color(0xFF929B93) else Color(0xFF394C3E))
            }
            if(day.needsAttention) Box(Modifier.padding(start = 1.dp).size(12.dp).background(Color(0xFFF5E5C3), CircleShape).testTag("calendar-warning-${day.date}"), contentAlignment = Alignment.Center) { Text("!", color = CalendarWarning, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold) }
        }
        Text(value ?: "—", color = color, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(change ?: day.hint, color = color.copy(alpha = .85f), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1)
    }
}

@Composable private fun CalendarKey(label: String, color: Color, warning: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(if(warning) "! $label" else label, color = MutedColor, fontSize = 11.sp)
    }
}

@Composable private fun MonthJumpDialog(month: YearMonth, onDismiss: () -> Unit, onSelect: (YearMonth) -> Unit) {
    var year by rememberSaveable { mutableIntStateOf(month.year) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("跳转月份") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { year-- }, enabled = year > 1900, modifier = Modifier.testTag("calendar-previous-year")) { Text("‹") }
                Text("$year 年", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                TextButton(onClick = { year++ }, enabled = year < 2100, modifier = Modifier.testTag("calendar-next-year")) { Text("›") }
            }
            (1..12).chunked(3).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m -> OutlinedButton(onClick = { onSelect(YearMonth.of(year, m)) }, modifier = Modifier.weight(1f).testTag("calendar-jump-$m"), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("${m}月", fontWeight = if(year == month.year && m == month.monthValue) FontWeight.Bold else FontWeight.Normal) } }
            } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
