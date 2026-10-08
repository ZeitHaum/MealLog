package com.self.diet

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

internal val WeightColor = Color(0xFF245C46)
internal val PredictionColor = Color(0xFF8275A8)
internal val ActivityColor = Color(0xFF9AC5B3)
internal val ExerciseColor = Color(0xFFD7A64C)
internal val IntakeColor = Color(0xFFC66B47)
internal val GridColor = Color(0xFFE9EDE7)
internal val MutedColor = Color(0xFF657168)

internal fun chartNumber(value: Double?, places: Int = 1): String = value?.let { String.format(Locale.US, "%.${places}f", if(abs(it) < 0.5 * 10.0.pow(-places)) 0.0 else it) } ?: "—"
internal fun signedNumber(value: Double?, places: Int = 1): String = if(value == null) "—" else (if(value > 0) "+" else "") + chartNumber(value, places)
enum class DailyChartKind { WEIGHT, ENERGY, DEFICIT }
internal data class Axis(val low: Double, val high: Double, val ticks: List<Double>)

internal fun chartAxis(values: List<Double>, kind: DailyChartKind): Axis = when(kind) {
    DailyChartKind.WEIGHT -> {
        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 1.0
        val padding = maxOf(0.15, (max - min) * 0.12)
        val step = Trends.niceCeiling((max - min + padding * 2) / 4)
        val low = floor((min - padding) / step) * step
        val high = ceil((max + padding) / step) * step
        Axis(low, high, (0..((high - low) / step).roundToInt()).map { low + it * step })
    }
    DailyChartKind.ENERGY -> {
        val limit = maxOf(100.0, (values.maxOrNull() ?: 1.0) * 1.08)
        val step = Trends.niceCeiling(limit / 3)
        val high = ceil(limit / step) * step
        Axis(0.0, high, (0..(high / step).roundToInt()).map { it * step })
    }
    DailyChartKind.DEFICIT -> {
        val limit = maxOf(100.0, (values.maxOfOrNull { abs(it) } ?: 1.0) * 1.1)
        val step = Trends.niceCeiling(limit / 3)
        val high = ceil(limit / step) * step
        Axis(-high, high, listOf(-high, -high / 2, 0.0, high / 2, high))
    }
}

@Composable
fun DailyChart(days: List<TrendDay>, kind: DailyChartKind, selected: Int, onSelect: (Int) -> Unit, showXAxis: Boolean = true) {
    val density = LocalDensity.current
    val labelPaint = remember(density.density, density.fontScale) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MutedColor.toArgb(); textSize = with(density) { 10.sp.toPx() } } }
    val values = remember(days, kind) { days.flatMap { d -> when(kind) {
        DailyChartKind.WEIGHT -> listOfNotNull(d.weight?.amount, d.prediction?.predicted)
        DailyChartKind.ENERGY -> listOfNotNull(d.intake, (d.energy.baseline ?: 0.0).plus(d.energy.exercise).takeIf { d.energy.bmr != null || it > 0 })
        DailyChartKind.DEFICIT -> listOfNotNull(d.energy.deficit)
    } } }
    if(values.isEmpty()) {
        ChartEmpty(when(kind) { DailyChartKind.WEIGHT -> "记录体重后，这里会显示逐日变化"; DailyChartKind.ENERGY -> "补充饮食热量、体重及模型设置后查看"; DailyChartKind.DEFICIT -> "补全当天收支后，这里会显示热量缺口" })
        return
    }
    val axis = remember(values, kind) { chartAxis(values, kind) }
    val yTexts = axis.ticks.map { chartNumber(it, if(kind == DailyChartKind.WEIGHT) 1 else 0) }
    val left = maxOf(with(density) { 42.dp.toPx() }, yTexts.maxOf { labelPaint.measureText(it) } + with(density) { 10.dp.toPx() })
    val right = with(density) { 12.dp.toPx() }
    val title = when(kind) { DailyChartKind.WEIGHT -> "体重趋势图"; DailyChartKind.ENERGY -> "每日能量图"; DailyChartKind.DEFICIT -> "热量缺口图" }
    Canvas(Modifier.fillMaxWidth().height(if(showXAxis) 205.dp else 175.dp).testTag("chart-${kind.name}").semantics { contentDescription = "$title，可点击选择日期；准确数值见图下方" }
        .pointerInput(days, left, right) { detectTapGestures { point ->
            val step = (size.width - left - right) / days.size
            onSelect(floor((point.x - left) / step).toInt().coerceIn(days.indices))
        } }) {
        drawDailyPlot(days, kind, selected, axis, labelPaint, left, right, showXAxis)
    }
}

internal fun DrawScope.drawDailyPlot(days: List<TrendDay>, kind: DailyChartKind, selected: Int, axis: Axis, labelPaint: Paint, left: Float, right: Float, showXAxis: Boolean = true) {
        val top = 22.dp.toPx()
        val bottom = size.height - (if(showXAxis) 40.dp else 12.dp).toPx()
        val width = size.width - left - right
        val step = width / days.size
        fun x(i: Int) = left + step * (i + 0.5f)
        fun y(value: Double) = bottom - ((value - axis.low) / (axis.high - axis.low)).toFloat() * (bottom - top)
        axis.ticks.forEach { value ->
            val position = y(value)
            drawLine(if(value == 0.0) Color(0xFFBBC8BD) else GridColor, Offset(left, position), Offset(size.width - right, position), if(value == 0.0) 1.2.dp.toPx() else 1.dp.toPx())
            label(chartNumber(value, if(kind == DailyChartKind.WEIGHT) 1 else 0), left - 7.dp.toPx(), position + labelPaint.textSize * 0.35f, labelPaint, Paint.Align.RIGHT)
        }
        label(if(kind == DailyChartKind.WEIGHT) "kg" else "kcal", left, 11.dp.toPx(), labelPaint, Paint.Align.LEFT)
        if(selected in days.indices) drawLine(Color(0xFFBCCCBF), Offset(x(selected), top), Offset(x(selected), bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        fun line(get: (TrendDay) -> Double?, color: Color, dashed: Boolean = false) {
            var last: Offset? = null
            days.forEachIndexed { index, day ->
                val v = get(day)
                if(v == null) { last = null } else {
                    val point = Offset(x(index), y(v))
                    last?.let { drawLine(color, it, point, 2.dp.toPx(), StrokeCap.Round, if(dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null) }
                    if(days.size <= 31 || index == selected || last == null) {
                        drawCircle(Color.White, if(index == selected) 5.dp.toPx() else 3.dp.toPx(), point)
                        drawCircle(color, if(index == selected) 3.5.dp.toPx() else 2.dp.toPx(), point)
                    }
                    last = point
                }
            }
        }
        when(kind) {
            DailyChartKind.WEIGHT -> { line({ it.prediction?.predicted }, PredictionColor, true); line({ it.weight?.amount }, WeightColor) }
            DailyChartKind.ENERGY -> {
                val barWidth = minOf(18.dp.toPx(), step * 0.66f)
                days.forEachIndexed { i, day ->
                    if(day.hasRecords) {
                        var sum = 0.0
                        val alpha = if(day.energy.total == null) 0.35f else 1f
                        listOf((day.energy.bmr ?: 0.0) to WeightColor, (day.activity ?: 0.0) to ActivityColor, day.energy.exercise to ExerciseColor).forEach { (value, color) ->
                            if(value > 0) drawRect(color.copy(alpha = alpha), Offset(x(i) - barWidth / 2, y(sum + value)), Size(barWidth, y(sum) - y(sum + value)))
                            sum += value
                        }
                    }
                }
                line({ it.intake }, IntakeColor)
            }
            DailyChartKind.DEFICIT -> {
                val barWidth = minOf(18.dp.toPx(), step * 0.66f)
                days.forEachIndexed { i, day -> day.energy.deficit?.let { value ->
                    if(abs(value) < 1e-8) drawCircle(WeightColor, 2.dp.toPx(), Offset(x(i), y(0.0)))
                    else drawRect(if(value >= 0) WeightColor else IntakeColor, Offset(x(i) - barWidth / 2, minOf(y(value), y(0.0))), Size(barWidth, abs(y(value) - y(0.0))))
                } }
            }
        }
        if(showXAxis) listOf(0, (days.size - 1) / 2, days.lastIndex).distinct().forEach { i ->
            val position = when(i) { 0 -> left; days.lastIndex -> size.width - right; else -> x(i) }
            val align = when(i) { 0 -> Paint.Align.LEFT; days.lastIndex -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            label("第 ${i + 1} 天", position, bottom + 17.dp.toPx(), labelPaint, align)
            label(days[i].date.format(DateTimeFormatter.ofPattern("MM/dd")), position, bottom + 32.dp.toPx(), labelPaint, align)
        }
}

@Composable
fun ErrorHistogram(errors: List<PredictionError>) {
    if(errors.isEmpty()) { ChartEmpty("保存当日预测，再记录次日体重\n这里会自动生成误差分布"); return }
    val histogram = remember(errors) { Trends.distribution(errors.map { it.percent }) }
    val density = LocalDensity.current
    val paint = remember(density.density, density.fontScale) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MutedColor.toArgb(); textSize = with(density) { 10.sp.toPx() } } }
    var selected by remember(errors) { mutableIntStateOf(-1) }
    val left = with(density) { 32.dp.toPx() }
    val right = with(density) { 12.dp.toPx() }
    Canvas(Modifier.fillMaxWidth().height(210.dp).testTag("error-histogram").semantics { contentDescription = "相对预测误差分布，横轴误差百分比，纵轴天数。点击柱子查看范围和数量。" }
        .pointerInput(histogram) { detectTapGestures { selected = floor((it.x - left) / (size.width - left - right) * histogram.bins.size).toInt().coerceIn(histogram.bins.indices) } }) {
        drawHistogramPlot(histogram, selected, paint, left, right)
    }
    if(selected >= 0) {
        val bin = histogram.bins[selected]
        Text("${chartNumber(bin.lower, 3)}% 至 ${chartNumber(bin.upper, 3)}%：${bin.count} 天", color = WeightColor, fontSize = 12.sp)
    }
}

internal fun DrawScope.drawHistogramPlot(histogram: ErrorDistribution, selected: Int, paint: Paint, left: Float, right: Float) {
        val top = 22.dp.toPx(); val bottom = size.height - 40.dp.toPx(); val width = size.width - left - right
        val maxCount = histogram.bins.maxOf { it.count }.coerceAtLeast(1)
        listOf(0, (maxCount + 1) / 2, maxCount).distinct().forEach { count ->
            val y = bottom - count.toFloat() / maxCount * (bottom - top)
            drawLine(GridColor, Offset(left, y), Offset(size.width - right, y), 1.dp.toPx())
            label(count.toString(), left - 7.dp.toPx(), y + paint.textSize * .35f, paint, Paint.Align.RIGHT)
        }
        label("天", left, 11.dp.toPx(), paint, Paint.Align.LEFT)
        val slot = width / histogram.bins.size
        histogram.bins.forEachIndexed { i, bin ->
            val height = bin.count.toFloat() / maxCount * (bottom - top)
            val color = if(i < histogram.bins.size / 2) WeightColor else if(i == histogram.bins.size / 2) ActivityColor else IntakeColor
            drawRect(color.copy(alpha = if(selected == -1 || selected == i) 1f else .4f), Offset(left + i * slot + slot * .12f, bottom - height), Size(slot * .76f, height))
        }
        drawLine(MutedColor.copy(alpha = .5f), Offset(left + width / 2, top), Offset(left + width / 2, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
        val places = if(histogram.extent < 0.1) 3 else if(histogram.extent < 1) 2 else 1
        label("−${chartNumber(histogram.extent, places)}%", left, bottom + 20.dp.toPx(), paint, Paint.Align.LEFT)
        label("0", left + width / 2, bottom + 20.dp.toPx(), paint, Paint.Align.CENTER)
        label("+${chartNumber(histogram.extent, places)}%", size.width - right, bottom + 20.dp.toPx(), paint, Paint.Align.RIGHT)
}

internal fun DrawScope.label(value: String, x: Float, y: Float, paint: Paint, align: Paint.Align) {
    paint.textAlign = align
    drawContext.canvas.nativeCanvas.drawText(value, x, y, paint)
}

@Composable
internal fun ChartEmpty(message: String) {
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MutedColor, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
