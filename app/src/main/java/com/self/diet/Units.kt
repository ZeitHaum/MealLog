package com.self.diet

data class MeasureUnit(val label: String, val factor: Double)
object Units {
    val weight = listOf(MeasureUnit("kg", 1.0), MeasureUnit("斤", 0.5), MeasureUnit("磅", 0.45359237))
    val food = listOf(MeasureUnit("g", 1.0), MeasureUnit("kg", 1000.0), MeasureUnit("斤", 500.0), MeasureUnit("两", 50.0))
    val duration = listOf(MeasureUnit("分钟", 1.0), MeasureUnit("小时", 60.0), MeasureUnit("秒", 1.0 / 60.0))
    val energy = listOf(MeasureUnit("kcal", 1.0), MeasureUnit("kJ", 1.0 / 4.184))
    fun forKind(kind: String) = when(kind) { Kinds.WEIGHT -> weight; Kinds.MEAL -> food; else -> duration }
    fun convert(raw: String, unit: MeasureUnit, optional: Boolean = false, allowZero: Boolean = false): Double? {
        if (raw.isBlank() && optional) return null
        val number = raw.trim().toDoubleOrNull() ?: throw IllegalArgumentException("请输入有效数字")
        val canonical = number * unit.factor
        require(canonical.isFinite() && if(allowZero) canonical >= 0 else canonical > 0) { if(allowZero) "数值不能小于 0" else "数值必须大于 0" }
        return canonical
    }
    fun display(value: Double?): String = value?.let { java.math.BigDecimal.valueOf(it).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() } ?: "未记录"
}
