package com.self.diet

import java.io.File
import java.io.OutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Export {
    const val FORMAT_VERSION = "1.2"
    fun number(value: Double?): String = value?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() } ?: ""
    fun csv(rows: List<List<String>>): String = "\uFEFF" + rows.joinToString("\r\n", postfix = "\r\n") { row ->
        row.joinToString(",") { value -> "\"${value.replace("\"", "\"\"")}\"" }
    }
    private fun text(value: String): String = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || value.startsWith('\t') || value.startsWith('\r')) "'$value" else value
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.map { c -> when (c) {
            '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
            else -> if (c.code < 32) "\\u%04x".format(c.code) else c.toString()
        } }.joinToString("") + "\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> error("Unsupported JSON value")
    }
    fun tables(records: List<Record>): Map<String, String> {
        val sorted = records.sortedWith(compareBy<Record> { OffsetDateTime.parse(it.occurredAt).toInstant() }.thenBy { it.id })
        val weights = listOf(listOf("id", "date", "measured_at", "weight_kg", "note", "created_at", "updated_at")) + sorted.filter { it.kind == Kinds.WEIGHT }.map {
            listOf(it.id, it.date, it.occurredAt, number(it.amount), text(it.note), it.createdAt, it.updatedAt)
        }
        val meals = listOf(listOf("id", "date", "eaten_at", "food_name", "weight_g", "energy_kcal", "photo_paths", "note", "created_at", "updated_at")) + sorted.filter { it.kind == Kinds.MEAL }.map {
            listOf(it.id, it.date, it.occurredAt, text(it.name), number(it.amount), number(it.calories), it.photoNames().joinToString(";") { name -> "photos/$name" }, text(it.note), it.createdAt, it.updatedAt)
        }
        val exercise = listOf(listOf("id", "date", "started_at", "exercise_name", "duration_min", "energy_kcal", "met", "energy_method", "note", "created_at", "updated_at")) + sorted.filter { it.kind == Kinds.EXERCISE }.map {
            listOf(it.id, it.date, it.occurredAt, text(it.name), number(it.amount), number(it.calories), number(it.met), if(it.calories != null) "manual" else if(it.met != null) "met" else "unknown", text(it.note), it.createdAt, it.updatedAt)
        }
        val daily = listOf(listOf("date", "weight_measurement_count", "latest_weight_kg", "latest_measured_at", "meal_count", "known_food_weight_g", "food_weight_missing_count", "known_energy_kcal", "energy_missing_count", "exercise_count", "exercise_duration_min")) + sorted.groupBy { it.date }.toSortedMap().map { (date, rows) ->
            val w = rows.filter { it.kind == Kinds.WEIGHT }
            val m = rows.filter { it.kind == Kinds.MEAL }
            val e = rows.filter { it.kind == Kinds.EXERCISE }
            fun sumOrBlank(values: List<Double>) = if (values.isEmpty()) "" else number(values.sum())
            listOf(date, w.size.toString(), number(w.lastOrNull()?.amount), w.lastOrNull()?.occurredAt ?: "", m.size.toString(), sumOrBlank(m.mapNotNull { it.amount }), m.count { it.amount == null }.toString(), sumOrBlank(m.mapNotNull { it.calories }), m.count { it.calories == null }.toString(), e.size.toString(), sumOrBlank(e.mapNotNull { it.amount }))
        }
        return linkedMapOf("weights.csv" to csv(weights), "meals.csv" to csv(meals), "exercises.csv" to csv(exercise), "daily_summary.csv" to csv(daily))
    }
    fun analysisTable(records: List<Record>, profile: ModelProfile): String {
        val header = listOf("date", "status", "model_version", "record_fingerprint", "height_cm", "age", "sex", "activity_factor", "lambda_kcal_per_kg", "weight_kg", "weight_measured_at", "known_intake_kcal", "missing_meal_energy_count", "missing_exercise_energy_count", "bmr_kcal", "baseline_kcal", "known_extra_exercise_kcal", "total_expenditure_kcal", "deficit_kcal", "predicted_change_kg", "predicted_next_day_weight_kg")
        return csv(listOf(header) + records.groupBy { it.date }.toSortedMap().map { (date, day) ->
            val r = EnergyModel.calculate(day, profile)
            listOf(date, if(r.predicted == null) "incomplete" else "trial_current_settings", EnergyModel.VERSION, EnergyModel.fingerprint(day), number(profile.heightCm), profile.age.toString(), profile.sex, number(profile.activityFactor), number(profile.lambda), number(r.weight), r.weightTime ?: "", number(r.knownIntake), r.missingMeals.toString(), r.missingExercise.toString(), number(r.bmr), number(r.baseline), number(r.knownExercise), number(r.total), number(r.deficit), number(r.delta), number(r.predicted))
        })
    }
    fun snapshotTable(snapshots: List<AnalysisSnapshot>): String {
        val header = listOf("id", "date", "generated_at", "mode", "model_version", "record_fingerprint", "height_cm", "age", "sex", "activity_factor", "lambda_kcal_per_kg", "weight_kg", "weight_measured_at", "intake_kcal", "bmr_kcal", "baseline_kcal", "extra_exercise_kcal", "total_expenditure_kcal", "deficit_kcal", "predicted_change_kg", "predicted_next_day_weight_kg")
        return csv(listOf(header) + snapshots.sortedWith(compareBy<AnalysisSnapshot> { it.date }.thenBy { OffsetDateTime.parse(it.generatedAt).toInstant() }).map { s ->
            listOf(s.id, s.date, s.generatedAt, s.mode, s.modelVersion, s.fingerprint, number(s.heightCm), s.age.toString(), s.sex, number(s.activityFactor), number(s.lambda), number(s.weight), s.weightTime, number(s.intake), number(s.bmr), number(s.baseline), number(s.exercise), number(s.total), number(s.deficit), number(s.delta), number(s.predicted))
        })
    }
    fun writeZip(output: OutputStream, records: List<Record>, photosDir: File, start: LocalDate, end: LocalDate, profile: ModelProfile = ModelProfile(), snapshots: List<AnalysisSnapshot> = emptyList()) {
        require(!end.isBefore(start)) { "结束日期不能早于开始日期" }
        val selected = records.filter { LocalDate.parse(it.date) in start..end }
        require(selected.isNotEmpty()) { "所选日期没有记录" }
        selected.forEach { it.validate() }
        val photos = selected.flatMap { it.photoNames() }.distinct().sorted()
        val trendDays = Trends.days(records, snapshots, profile, start, end)
        val contextSnapshotIds = trendDays.mapNotNull { it.prediction?.id }.toSet()
        val selectedSnapshots = snapshots.filter { LocalDate.parse(it.date) in start..end || it.id in contextSnapshotIds }
        photos.forEach { require(File(photosDir, it).isFile) { "照片缺失，无法完整导出：$it" } }
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            tables(selected).forEach { (name, body) -> entry(name, body.toByteArray(Charsets.UTF_8)) }
            entry("analysis.csv", analysisTable(selected, profile).toByteArray(Charsets.UTF_8))
            Trends.chartTables(trendDays).forEach { (name, body) -> entry(name, body.toByteArray(Charsets.UTF_8)) }
            entry("prediction_snapshots.csv", snapshotTable(selectedSnapshots).toByteArray(Charsets.UTF_8))
            entry("model_settings.json", json(linkedMapOf("model_version" to EnergyModel.VERSION, "confirmed" to profile.confirmed, "height_cm" to profile.heightCm, "age" to profile.age, "sex" to profile.sex, "activity_factor" to profile.activityFactor, "lambda_kcal_per_kg" to profile.lambda, "exercise_accounting" to "additional_to_activity_factor")).toByteArray(Charsets.UTF_8))
            entry("manifest.json", json(linkedMapOf("format" to "diet-records", "format_version" to FORMAT_VERSION, "exported_at" to OffsetDateTime.now().toString(), "start_date" to start.toString(), "end_date" to end.toString(), "record_count" to selected.size, "photo_count" to photos.size, "snapshot_count" to selectedSnapshots.size)).toByteArray(Charsets.UTF_8))
            entry("records.json", json(selected.sortedBy { it.id }.map { r -> linkedMapOf("id" to r.id, "kind" to r.kind, "occurred_at" to r.occurredAt, "date" to r.date, "name" to r.name, "amount" to r.amount, "amount_unit" to when(r.kind) { Kinds.WEIGHT -> "kg"; Kinds.MEAL -> "g"; else -> "min" }, "energy_kcal" to r.calories, "met" to r.met, "photos" to r.photoNames().map { "photos/$it" }, "note" to r.note, "created_at" to r.createdAt, "updated_at" to r.updatedAt) }).toByteArray(Charsets.UTF_8))
            entry("README.txt", """
食记导出格式 v$FORMAT_VERSION

weights.csv：id 唯一编号；date 测量当地日期；measured_at 称重时间；weight_kg 体重；note 备注；created_at 首次录入时间；updated_at 最后修改时间。
meals.csv：eaten_at 进食时间；food_name 食物名称；weight_g 克数；energy_kcal 热量；photo_paths 照片相对路径，多张照片以分号分隔。其余公共字段同上。
exercises.csv：started_at 运动开始时间；exercise_name 运动名称；duration_min 分钟数；energy_kcal 手动填写的额外消耗；met 运动强度；energy_method 为 manual/met/unknown。MET 估算 = MET × 当天最后体重 kg × 1.05 × 时长小时，完整结果作为活动系数之外的额外消耗相加。
daily_summary.csv：按实际发生日期汇总。latest_weight_kg/latest_measured_at 为当天最后一次测量；known_food_weight_g/known_energy_kcal 只合计已填写值；*_missing_count 表示缺失条数；exercise_duration_min 为已记录运动时长。
analysis.csv：导出时按当前设置重算的逐日结果。status=incomplete 表示不足以计算；trial_current_settings 表示按已录入数据试算，不代表用户已完成全天记录。known_* 仅合计已知值；没有记录不能推断实际为零。模型参数、最新体重及其时间、BMR、日常消耗、额外运动、总消耗、缺口、一日变化和次日体重均分列保存。
prediction_snapshots.csv：用户确认全天记录后主动保存的计算快照。generated_at 为保存时间；mode=retrospective 是历史回算，same_day_estimate 是当日估计，planned 是未来日期估计。每次计算的参数、输入汇总、结果、模型版本和记录指纹固定保留，修改记录或参数不会改写旧快照。比较预测时优先使用次日实际称重之前保存的快照，并区分回算。record_fingerprint 用于识别记录是否改变。
model_settings.json：导出时的当前模型参数；confirmed=false 时不计算基础代谢或预测。
trend_daily.csv：趋势图对应的逐日数据，连续日历日保留空行，不会把缺测当成零。day_index 为区间内第几天；daily_activity_kcal = BMR ×（活动系数−1）；saved_prediction_kg 是目标日期前一日保存的有效预测，区别于按当前参数试算的次日体重。
prediction_errors.csv：每个目标日期仅保留最后一份有效事前预测与每日最后实测体重的配对。relative_error_percent =（预测−实测）÷ 实测 ×100；error_kg = 预测−实测。排除历史回算、计划估计，以及保存时间不早于目标日任一次称重或体重录入的快照。预测使用保存时的参数；实测使用导出时的最新记录，修改实际体重会改变误差。用于配对的区间前一日快照也一并包含在 prediction_snapshots.csv 中。

CSV：UTF-8 BOM 编码，CRLF 换行，英文固定列名，双引号转义。时间为含时区偏移的 ISO 8601。空白表示未知，0 表示明确记录为零。没有记录的日期只在 trend_daily.csv 保留日期行，其余汇总不生成行；不能据此认定当日没有进食或运动。时区改变不重分配已有记录的日期。
CSV 文本若以 =、+、-、@ 或制表符等开头，增加单引号以避免 Excel 当公式执行；records.json 保留原始文本，无此前缀。
records.json：结构化原始记录，amount 单位由 amount_unit 指定；photos 为照片相对路径列表。manifest.json 包含格式版本、导出日期范围和数量。当前版本仅支持导出，不提供导入恢复。
photos/：本次记录照片的本地副本，已转换为最大边 2048 像素的 JPEG。

计算约定：BMR = 10 × 体重kg + 6.25 × 身高cm − 5 × 年龄 + 性别参数（男性5，女性−161），采用 Mifflin–St Jeor 静息代谢估计式。总消耗 = BMR × 日常活动系数 + 额外运动；热量缺口 = 总消耗 − 摄入；预测变化kg = −缺口 / λ；理论次日体重 = 当天最后体重 + 预测变化。λ 默认7500 kcal/kg，可修改。这是简化能量模型的一日折算；水分、糖原和胃肠内容物会影响实际秤重。称重时刻也会影响比较，建议逐日比较相近时间的测量。
饮食热量为手动输入，未进行照片识别；食物克数不会自动等同于热量。运动和日常活动系数按用户模型独立计入。原始明细、试算、保存快照分别保留供后续分析。
""".trimIndent().toByteArray(Charsets.UTF_8))
            photos.forEach { name -> zip.putNextEntry(ZipEntry("photos/$name")); File(photosDir, name).inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
        }
    }
}
