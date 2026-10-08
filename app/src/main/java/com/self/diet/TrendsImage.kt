package com.self.diet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object TrendsImage {
    fun create(context: Context, days: List<TrendDay>, profile: ModelProfile, preview: Boolean = false): File {
        require(days.isNotEmpty())
        val bitmap = Bitmap.createBitmap(1440, 2700, Bitmap.Config.ARGB_8888)
        val target = File.createTempFile("diet-trends-", ".png", context.cacheDir)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val errors = days.mapNotNull { it.error }
            val deficits = days.mapNotNull { it.energy.deficit }
            CanvasDrawScope().draw(Density(3f, 1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(1440f, 2700f)) {
                drawRect(Color(0xFFF6F7F2))
                fun text(value: String, x: Float, y: Float, size: Float = 30f, color: Color = WeightColor, bold: Boolean = false) {
                    paint.textSize = size; paint.color = color.toArgb(); paint.isFakeBoldText = bold
                    label(value, x, y, paint, Paint.Align.LEFT)
                }
                fun card(y: Float, height: Float, color: Color = Color.White) = drawRoundRect(color, Offset(56f, y), Size(1328f, height), CornerRadius(32f))
                fun legend(value: String, color: Color, x: Float, y: Float) {
                    drawCircle(color, 7f, Offset(x + 7f, y - 9f)); text(value, x + 26f, y, 26f, MutedColor)
                }
                text(if(preview) "食记 · 趋势总览（示例）" else "食记 · 趋势总览", 72f, 92f, 58f, bold = true)
                text("${days.first().date} — ${days.last().date}   /   ${days.size} 个日历日", 76f, 144f, 30f, MutedColor)
                card(184f, 164f, WeightColor)
                listOf(
                    Triple("体重变化", "${signedNumber(Trends.weightChange(days), 2)} kg", "${days.count { it.weight != null }} 天测量"),
                    Triple("日均热量缺口", "${chartNumber(Trends.mean(deficits), 0)} kcal", "${deficits.size} 天可计算"),
                    Triple("平均绝对预测误差", "${chartNumber(Trends.mean(errors.map { abs(it.kilograms) }), 3)} kg", "${errors.size} 天有效配对")
                ).forEachIndexed { index, (label, value, hint) ->
                    val x = 100f + index * 435f
                    text(label, x, 225f, 26f, Color(0xFFCEE0D4)); text(value, x, 284f, 45f, Color.White, true); text(hint, x, 323f, 24f, Color(0xFFCEE0D4))
                }
                card(380f, 1420f)
                text("每日变化", 100f, 440f, 38f, bold = true)
                text("共同日期轴 · 缺测留空", 980f, 440f, 26f, MutedColor)
                val sections = listOf(Triple(DailyChartKind.WEIGHT, 498f, "01   实际体重与已存预测"), Triple(DailyChartKind.ENERGY, 914f, "02   摄入与消耗"), Triple(DailyChartKind.DEFICIT, 1330f, "03   热量缺口"))
                sections.forEach { (kind, y, title) ->
                    text(title, 100f, y, 31f, bold = true)
                    when(kind) {
                        DailyChartKind.WEIGHT -> { legend("实际体重", WeightColor, 700f, y); legend("已存预测（虚线）", PredictionColor, 1000f, y) }
                        DailyChartKind.ENERGY -> { legend("摄入（折线）", IntakeColor, 480f, y); legend("基础代谢", WeightColor, 780f, y); legend("日常活动", ActivityColor, 1020f, y); legend("额外运动", ExerciseColor, 1020f, y + 40f) }
                        DailyChartKind.DEFICIT -> { legend("正值：缺口", WeightColor, 760f, y); legend("负值：盈余", IntakeColor, 1050f, y) }
                    }
                    val values = days.flatMap { d -> when(kind) {
                        DailyChartKind.WEIGHT -> listOfNotNull(d.weight?.amount, d.prediction?.predicted)
                        DailyChartKind.ENERGY -> listOfNotNull(d.intake, ((d.energy.baseline ?: 0.0) + d.energy.exercise).takeIf { d.energy.bmr != null || it > 0 })
                        DailyChartKind.DEFICIT -> listOfNotNull(d.energy.deficit)
                    } }
                    if(values.isEmpty()) text("暂无可绘制数据", 560f, y + 210f, 32f, MutedColor)
                    else inset(88f, y + 56f, 88f, size.height - y - 410f) {
                        paint.textSize = 28f; paint.color = MutedColor.toArgb(); paint.isFakeBoldText = false
                        val chartAxis = chartAxis(values, kind)
                        val left = maxOf(105f, chartAxis.ticks.maxOf { paint.measureText(chartNumber(it, if(kind == DailyChartKind.WEIGHT) 1 else 0)) } + 24f)
                        drawDailyPlot(days, kind, -1, chartAxis, paint, left, 24f, showXAxis = kind == DailyChartKind.DEFICIT)
                    }
                }
                text("总消耗 = 基础代谢 + 日常活动 + 额外运动；日常活动 = 基础代谢 ×（活动系数 − 1）。", 100f, 1762f, 25f, MutedColor)
                card(1832f, 600f)
                text("相对预测误差分布", 100f, 1894f, 38f, bold = true)
                text("${errors.size} 天有效配对", 1090f, 1894f, 26f, MutedColor)
                if(errors.isEmpty()) text("保存当日预测并记录次日体重后生成误差分布", 300f, 2105f, 32f, MutedColor)
                else inset(100f, 1930f, 100f, size.height - 2260f) {
                    paint.textSize = 28f; paint.color = MutedColor.toArgb(); paint.isFakeBoldText = false
                    drawHistogramPlot(Trends.distribution(errors.map { it.percent }), -1, paint, 80f, 24f)
                }
                text("平均绝对相对误差  ${chartNumber(Trends.mean(errors.map { abs(it.percent) }), 3)}%", 100f, 2288f, 29f, bold = true)
                text("平均有向误差  ${signedNumber(Trends.mean(errors.map { it.percent }), 3)}%", 790f, 2288f, 29f, bold = true)
                text("误差 =（预测 − 实际）÷ 实际 × 100%；负值为预测偏轻，正值为预测偏重。", 100f, 2340f, 26f, MutedColor)
                text("仅比较事先保存的前一日预测；回算不纳入。体重取每日最后一次称重。", 100f, 2385f, 26f, MutedColor)
                text("收支按当前参数与已录数据试算；淡色消耗柱为有缺项，未记录不等于零。", 76f, 2490f, 27f, MutedColor)
                text("模型：身高 ${Units.display(profile.heightCm)} cm · ${profile.age} 岁 · ${if(profile.sex == "male") "男性公式" else "女性公式"} · 活动系数 ${Units.display(profile.activityFactor)} · λ ${Units.display(profile.lambda)} kcal/kg", 76f, 2536f, 27f, MutedColor)
                text("预测沿用当时保存参数。实际秤重也受称重时刻、水分等影响；小样本不足以判断模型优劣。", 76f, 2582f, 26f, MutedColor)
                text("生成于 ${OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX"))} · 食记 0.2", 76f, 2640f, 25f, MutedColor)
            }
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return target
        } catch(e: Exception) { target.delete(); throw e }
        finally { bitmap.recycle() }
    }
}
