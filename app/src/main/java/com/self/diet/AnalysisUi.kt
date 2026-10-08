package com.self.diet

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun AnalysisPanel(day: List<Record>, date: LocalDate, profile: ModelProfile, snapshots: List<AnalysisSnapshot>, onSettings: () -> Unit, onSave: () -> Unit) {
    val result = remember(day, profile) { EnergyModel.calculate(day, profile) }
    val fingerprint = remember(day) { EnergyModel.fingerprint(day) }
    var complete by rememberSaveable(date.toString(), fingerprint) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("热量与体重", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = onSettings) { Text("模型设置") } }
        if(!profile.confirmed) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBC5))) { Column(Modifier.padding(16.dp)) { Text("先核对个人资料", fontWeight = FontWeight.SemiBold); Text("身高、年龄和公式参数用于估算基础代谢。", fontSize = 13.sp); TextButton(onClick = onSettings) { Text("设置模型") } } }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EnergyLine("已记录摄入", result.knownIntake)
                HorizontalDivider()
                EnergyLine("基础代谢估计", result.bmr)
                EnergyLine("日常消耗 × ${Units.display(profile.activityFactor)}", result.baseline)
                EnergyLine("＋ 额外运动", result.knownExercise)
                EnergyLine("预计总消耗", result.total)
                Text("总消耗 = 基础代谢 × 活动系数 + 额外运动。运动按独立项全额相加。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val problems = buildList {
            if(result.weight == null) add("请补充当天体重")
            if(day.none { it.kind == Kinds.MEAL }) add("请补充当天饮食记录")
            if(result.missingMeals > 0) add("${result.missingMeals} 项饮食缺少热量")
            if(result.missingExercise > 0) add("${result.missingExercise} 项运动缺少可计算的消耗")
        }
        if(problems.isNotEmpty()) Text(problems.joinToString("；"), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(complete, onCheckedChange = { complete = it }); Text("已记录全天饮食和额外运动", fontSize = 13.sp) }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if(complete) "当日热量缺口（估计）" else "按已记录数据试算", fontWeight = FontWeight.SemiBold)
                Text(result.deficit?.let { "${Units.display(it)} kcal" } ?: "待补全数据", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("缺口 = 消耗 − 摄入；正数为缺口，负数为盈余。", fontSize = 12.sp)
                if(result.predicted != null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("模型的一日体重变化", fontSize = 13.sp)
                    Text("${if(result.delta!! >= 0) "+" else ""}${Units.display(result.delta)} kg", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("理论下一日体重：${Units.display(result.predicted)} kg", fontWeight = FontWeight.SemiBold)
                    Text("基于当天最后一次称重 ${Units.display(result.weight)} kg（${OffsetDateTime.parse(result.weightTime).format(DateTimeFormatter.ofPattern("HH:mm"))}），按当日收支折算一天。", fontSize = 12.sp)
                }
            }
        }
        Text("Δ体重 = −热量缺口 ÷ ${Units.display(profile.lambda)} kcal/kg。这是你的简化能量模型估计，不保证次日秤重；水分、糖原和胃肠内容物也会改变读数。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onSave, enabled = complete && result.predicted != null, modifier = Modifier.fillMaxWidth()) { Text(when { date.isBefore(LocalDate.now()) -> "保存回算快照"; date.isAfter(LocalDate.now()) -> "保存计划估计"; else -> "保存当日预测快照" }) }
        Text("确认全天记录后可保存快照。历史日期标为回算；记录或参数修改不会覆盖旧快照。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        snapshots.firstOrNull()?.let { snapshot ->
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已保存 ${snapshots.size} 次计算", fontWeight = FontWeight.SemiBold)
                Text("最近一次：${Units.display(snapshot.predicted)} kg · ${when(snapshot.mode) { "retrospective" -> "历史回算"; "planned" -> "计划估计"; else -> "当日估计" }}", fontSize = 13.sp)
                Text(OffsetDateTime.parse(snapshot.generatedAt).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), fontSize = 12.sp)
                if(snapshot.fingerprint != fingerprint || snapshot.heightCm != profile.heightCm || snapshot.age != profile.age || snapshot.sex != profile.sex || snapshot.activityFactor != profile.activityFactor || snapshot.lambda != profile.lambda) Text("记录或模型参数已变化；旧快照保留供比较。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            } }
        }
    }
}

@Composable private fun EnergyLine(label: String, value: Double?) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, fontSize = 14.sp); Text(value?.let { "${Units.display(it)} kcal" } ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) } }

@Composable
fun ModelSettingsDialog(profile: ModelProfile, onDismiss: () -> Unit, onSave: (ModelProfile) -> Unit) {
    var height by rememberSaveable { mutableStateOf(Export.number(profile.heightCm)) }
    var age by rememberSaveable { mutableStateOf(profile.age.toString()) }
    var sex by rememberSaveable { mutableStateOf(profile.sex) }
    var factor by rememberSaveable { mutableStateOf(Export.number(profile.activityFactor)) }
    var lambda by rememberSaveable { mutableStateOf(Export.number(profile.lambda)) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("模型设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("首次使用的预填值仅为示例，请按自己的情况核对。", fontSize = 12.sp)
            OutlinedTextField(height, { height = it }, label = { Text("身高 cm") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(age, { age = it }, label = { Text("年龄（岁）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            Text("基础代谢公式参数", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(sex == "male", { sex = "male" }, label = { Text("男性公式") }); FilterChip(sex == "female", { sex = "female" }, label = { Text("女性公式") }) }
            OutlinedTextField(factor, { factor = it }, label = { Text("日常活动系数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            Text("只代表平常生活；额外运动单独相加。", fontSize = 12.sp)
            OutlinedTextField(lambda, { lambda = it }, label = { Text("λ：kcal / kg") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            Text("基础代谢采用 Mifflin–St Jeor 静息代谢估计式。λ 默认 7500，是可调整的模型参数。", fontSize = 12.sp)
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pubmed.ncbi.nlm.nih.gov/2305711/"))) }) { Text("查看代谢公式来源") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = { try { val value = ModelProfile(height.toDouble(), age.toInt(), sex, factor.toDouble(), lambda.toDouble(), true); value.validate(); onSave(value) } catch(e: Exception) { error = e.message ?: "请检查数字" } }) { Text("保存设置") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
