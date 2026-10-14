package com.self.diet

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ReferenceEntry(val id: String, val name: String, val category: String, val detail: String, val value: Double, val source: String)

object ReferenceTables {
    // USDA SR Legacy, energy nutrient 208, kcal per 100 g edible portion. Verified 2026-10-14.
    val foods = listOf(
        food("168878", "白米饭", "主食", "长粒白米，煮熟，不含额外油脂", 130.0),
        food("171688", "苹果", "果蔬", "生，带皮，去除不可食部分", 52.0),
        food("173944", "香蕉", "果蔬", "生，去皮后的可食重量", 89.0),
        food("171705", "牛油果", "果蔬", "生，果肉", 160.0),
        food("170379", "西兰花", "果蔬", "生，不含烹调油", 34.0),
        food("170457", "番茄", "果蔬", "红色熟果，生，不含调料", 18.0),
        food("171077", "鸡胸肉", "肉蛋", "生，去皮去骨；不能套用于熟肉重量", 120.0),
        food("173424", "水煮蛋", "肉蛋", "煮熟全蛋，去壳重量", 155.0),
        food("171413", "橄榄油", "油脂", "食用油重量，不能按毫升直接套用", 884.0)
    )
    private fun food(id: String, name: String, category: String, detail: String, value: Double) =
        ReferenceEntry(id, name, category, detail, value, "https://fdc.nal.usda.gov/food-details/$id/nutrients")
    // 2024 Adult Compendium of Physical Activities; IDs identify exact intensity rows.
    val movements = listOf(
        met("17152", "慢走", "步行", "平地，约 3.2–3.9 km/h", 2.8, "walking"),
        met("17170", "步行", "步行", "平地，约 4.0 km/h", 3.0, "walking"),
        met("17190", "中速走", "步行", "平地，约 4.5–5.5 km/h", 3.8, "walking"),
        met("17200", "快走", "步行", "平地，约 5.6–6.3 km/h", 4.8, "walking"),
        met("17131", "爬楼梯", "步行", "一般速度上楼", 6.8, "walking"),
        met("12020", "慢跑", "跑步", "自主配速的一般慢跑", 7.5, "running"),
        met("12030", "跑步 8 km/h", "跑步", "约 8.0–8.4 km/h", 8.5, "running"),
        met("12050", "跑步 10 km/h", "跑步", "约 9.7–10.1 km/h", 9.3, "running"),
        met("12080", "跑步 12 km/h", "跑步", "约 12.1 km/h", 11.8, "running"),
        met("01010", "休闲骑行", "骑行", "低于约 16.1 km/h", 4.0, "bicycling"),
        met("01011", "通勤骑行", "骑行", "自主配速", 6.8, "bicycling"),
        met("02054", "力量训练", "健身", "多种动作，每组 8–15 次", 3.5, "conditioning-exercise"),
        met("02050", "高强度举重", "健身", "高强度训练", 6.0, "conditioning-exercise"),
        met("02048", "椭圆机", "健身", "中等强度", 5.0, "conditioning-exercise"),
        met("02049", "椭圆机（高强度）", "健身", "高强度", 9.0, "conditioning-exercise"),
        met("02068", "跳绳", "健身", "一般跳绳", 11.0, "conditioning-exercise"),
        met("02101", "轻度拉伸", "健身", "轻柔伸展", 2.3, "conditioning-exercise"),
        met("02175", "瑜伽", "健身", "一般瑜伽", 2.3, "conditioning-exercise")
    )
    private fun met(id: String, name: String, category: String, detail: String, value: Double, page: String) =
        ReferenceEntry(id, name, category, detail, value, "https://pacompendium.com/$page/")
    fun foodEnergy(entry: ReferenceEntry, grams: Double?): Double? = grams?.takeIf { it.isFinite() && it > 0 }
        ?.let { it * entry.value / 100 }?.takeIf { it.isFinite() }
}

@Composable
fun ReferenceDialog(food: Boolean, grams: Double? = null, onDismiss: () -> Unit, onApply: (ReferenceEntry, Double) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    var linkError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val entries = if(food) ReferenceTables.foods else ReferenceTables.movements
    val filtered = entries.filter { (category == "全部" || it.category == category) && "$it".contains(query.trim(), ignoreCase = true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if(food) "热量参考" else "运动参考 · MET") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if(food) "按每 100 g 可食部分估算；生熟状态、油和调料须分别核对，包装营养表优先。"
                else "选择与你的速度、强度相近的项目。采用后仍可修改；消耗作为活动系数之外的额外运动相加。", fontSize = 12.sp)
            OutlinedTextField(query, { query = it }, label = { Text("搜索名称或条件") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("全部") + entries.map { it.category }.distinct()).forEach { c ->
                    FilterChip(category == c, onClick = { category = c }, label = { Text(c) })
                }
            }
            if(food) Text(grams?.takeIf { it > 0 && it.isFinite() }?.let { "按当前 ${chartNumber(it, 1)} g 计算；采用会替换热量。" } ?: "填写食物重量后可采用估算；现在可先查阅。", fontSize = 12.sp)
            if(linkError) Text("没有可用的浏览器；参考表仍可离线查阅。", fontSize = 12.sp)
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if(filtered.isEmpty()) item { Text("暂无匹配项目，可手动填写数值。") }
                items(filtered, key = { it.id }) { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.name, fontWeight = FontWeight.SemiBold)
                        Text(entry.detail, fontSize = 12.sp)
                        Text(if(food) "${chartNumber(entry.value, 0)} kcal / 100 g · ${chartNumber(entry.value / 100, 2)} kcal / g"
                            else "${chartNumber(entry.value, 1)} MET", color = WeightColor, fontWeight = FontWeight.SemiBold)
                        val value = if(food) ReferenceTables.foodEnergy(entry, grams) else entry.value
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = {
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.source))) }
                                catch(_: Exception) { linkError = true }
                            }) { Text("来源 ${entry.id}", fontSize = 10.sp) }
                            TextButton(onClick = { value?.let { onApply(entry, it) } }, enabled = value != null) {
                                Text(if(food && value != null) "采用 ${chartNumber(value, 1)} kcal" else "采用")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Text(if(food) "USDA FoodData Central · SR Legacy" else "2024 Adult Compendium of Physical Activities", fontSize = 10.sp, color = MutedColor)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
