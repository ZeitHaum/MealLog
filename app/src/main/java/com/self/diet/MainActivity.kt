package com.self.diet

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF24523E), secondary = Color(0xFF79603C), background = Color(0xFFF6F7F2), surface = Color(0xFFF6F7F2), surfaceContainer = Color.White, primaryContainer = Color(0xFFE1EDDA), onPrimaryContainer = Color(0xFF163725))) {
                DietApp()
            }
        }
    }
}

@Composable
fun DietApp(vm: DietViewModel = viewModel()) {
    val records by vm.records.collectAsStateWithLifecycle()
    var dateString by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val date = LocalDate.parse(dateString)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var editorKind by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Record?>(null) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var modelSettings by rememberSaveable { mutableStateOf(false) }
    var trendsOpen by rememberSaveable { mutableStateOf(false) }
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var calendarReturn by rememberSaveable { mutableStateOf(false) }
    var calendarMonth by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var calendarMetric by rememberSaveable { mutableStateOf(CalendarMetric.WEIGHT.name) }
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { vm.copyExport(it) }
    val day = records.filter { it.date == dateString }.sortedByDescending { OffsetDateTime.parse(it.occurredAt).toInstant() }
    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }

    if(calendarOpen) {
        CalendarScreen(records, vm.profile, YearMonth.parse(calendarMonth), CalendarMetric.valueOf(calendarMetric), date,
            onMonth = { calendarMonth = it.toString() }, onMetric = { calendarMetric = it.name },
            onOpenDay = { dateString = it.toString(); tab = 1; filter = "all"; calendarOpen = false; calendarReturn = true },
            onBack = { calendarOpen = false; calendarReturn = false }, onSettings = { modelSettings = true })
        if(modelSettings) ModelSettingsDialog(vm.profile, onDismiss = { modelSettings = false }, onSave = { vm.saveProfile(it) { modelSettings = false } })
        return
    }

    if(trendsOpen) {
        TrendsScreen(records, snapshots, vm.profile, date, onBack = { trendsOpen = false }, onOpenDay = { dateString = it.toString(); tab = 2; trendsOpen = false }, onSettings = { modelSettings = true })
        if(modelSettings) ModelSettingsDialog(vm.profile, onDismiss = { modelSettings = false }, onSave = { vm.saveProfile(it) { modelSettings = false } })
        return
    }

    BackHandler(enabled = calendarReturn && editorKind == null && deleting == null && !exportOpen && !modelSettings) {
        calendarOpen = true; calendarReturn = false
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }, bottomBar = {
        Surface(shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Kinds.WEIGHT, Kinds.MEAL, Kinds.EXERCISE).forEach { kind ->
                    Button(onClick = { editingId = null; editorKind = kind }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Text("＋ ${Kinds.label(kind)}") }
                }
            }
        }
    }) { inset ->
        LazyColumn(Modifier.fillMaxSize().padding(inset), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if(calendarReturn) {
                        TextButton(onClick = { calendarOpen = true; calendarReturn = false }) { Text("‹ 日历", fontSize = 18.sp) }
                        Spacer(Modifier.weight(1f))
                    } else {
                        Text("食记", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { calendarMonth = YearMonth.from(date).toString(); calendarOpen = true }) { Text("日历") }
                    }
                    TextButton(onClick = { trendsOpen = true }) { Text("趋势") }
                    OutlinedButton(onClick = { exportOpen = true }, enabled = records.isNotEmpty() && !vm.exporting) { Text(if(vm.exporting) "导出中" else "导出") }
                }
                Text(if(calendarReturn) "当天详细记录 · 可以查看、修改或补记" else "记录每一天，留给未来的自己", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { dateString = date.minusDays(1).toString() }) { Text("‹ 前一天") }
                    DateButton(date, onChange = { dateString = it.toString() })
                    TextButton(onClick = { dateString = date.plusDays(1).toString() }) { Text("后一天 ›") }
                }
                if(date != LocalDate.now()) TextButton(onClick = { dateString = LocalDate.now().toString() }, modifier = Modifier.fillMaxWidth()) { Text("回到今天") }
            }
            item { DaySummary(day) }
            item {
                SecondaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("时间线") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("每日汇总") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("分析") })
                }
            }
            if(tab == 0) {
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("all") + listOf(Kinds.WEIGHT, Kinds.MEAL, Kinds.EXERCISE)).forEach { kind -> FilterChip(selected = filter == kind, onClick = { filter = kind }, label = { Text(if(kind == "all") "全部" else Kinds.label(kind)) }) }
                } }
                val visible = day.filter { filter == "all" || it.kind == filter }
                if(visible.isEmpty()) item { EmptyCard("这一天还没有${if(filter == "all") "" else Kinds.label(filter)}记录", "点下方按钮开始记录，也可以切换日期补记。") }
                items(visible, key = { it.id }) { record -> RecordCard(record, edit = { editingId = record.id; editorKind = record.kind }, delete = { deleting = record }) }
            } else if(tab == 1) {
                listOf(Kinds.MEAL, Kinds.EXERCISE, Kinds.WEIGHT).forEach { kind ->
                    val group = day.filter { it.kind == kind }
                    item { Text("${Kinds.label(kind)} · ${group.size} 条", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                    if(group.isEmpty()) item { Text("未记录", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)) }
                    items(group, key = { it.id }) { record -> RecordCard(record, edit = { editingId = record.id; editorKind = record.kind }, delete = { deleting = record }) }
                }
                item { Text("汇总只计算已填写的数据。未记录不等于零；同一天可记录多次体重。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            } else {
                item { AnalysisPanel(day, date, vm.profile, snapshots.filter { it.date == dateString }, onSettings = { modelSettings = true }, onSave = { vm.savePrediction(dateString) }) }
            }
        }
    }
    editorKind?.let { kind ->
        val existing = records.firstOrNull { it.id == editingId }
        key(editingId ?: kind) {
            RecordEditor(kind, date, existing, vm.saving, onDismiss = { editorKind = null }, onSave = { vm.save(it) { editorKind = null; dateString = it.date } })
        }
    }
    deleting?.let { record -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条${Kinds.label(record.kind)}记录？") }, text = { Text("记录及其照片将从手机中移除。") }, confirmButton = { TextButton(onClick = { vm.delete(record); deleting = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    if(exportOpen) ExportDialog(records, vm.exporting, onDismiss = { exportOpen = false }, onExport = { start, end -> vm.prepareExport(start, end) { name -> exportOpen = false; exportPicker.launch(name) } })
    if(modelSettings) ModelSettingsDialog(vm.profile, onDismiss = { modelSettings = false }, onSave = { vm.saveProfile(it) { modelSettings = false } })
}

@Composable
private fun DaySummary(day: List<Record>) {
    val meals = day.filter { it.kind == Kinds.MEAL }
    val weights = day.filter { it.kind == Kinds.WEIGHT }
    val exercise = day.filter { it.kind == Kinds.EXERCISE }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF24523E)), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("当日概览", color = Color(0xFFCDDFC9), fontSize = 12.sp)
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val known = meals.mapNotNull { it.calories }
                listOf(Triple("最近体重", weights.firstOrNull()?.amount?.let { "${Units.display(it)} kg" } ?: "—", "${weights.size} 次测量"), Triple("已记录热量", if(known.isEmpty()) "—" else "${Units.display(known.sum())}", "${meals.size} 项 · kcal"), Triple("额外运动", if(exercise.isEmpty()) "—" else "${Units.display(exercise.sumOf { it.amount ?: 0.0 })}", "${exercise.size} 次 · 分钟")).forEach { (label, value, hint) ->
                    Column(Modifier.weight(1f)) { Text(label, color = Color(0xFFCDDFC9), fontSize = 12.sp); Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(vertical = 6.dp)); Text(hint, color = Color(0xFFCDDFC9), fontSize = 11.sp) }
                }
            }
            val missing = meals.count { it.calories == null }
            if(missing > 0) Text("还有 $missing 项饮食未填写热量", color = Color(0xFFF0D79F), fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String) = Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
private fun RecordCard(record: Record, edit: () -> Unit, delete: () -> Unit) {
    Card(onClick = edit, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(OffsetDateTime.parse(record.occurredAt).format(DateTimeFormatter.ofPattern("HH:mm")), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("  ${Kinds.label(record.kind)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = edit, contentPadding = PaddingValues(4.dp)) { Text("编辑") }
                TextButton(onClick = delete, contentPadding = PaddingValues(4.dp)) { Text("删除", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(if(record.kind == Kinds.WEIGHT) "${Units.display(record.amount)} kg" else record.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if(record.kind != Kinds.WEIGHT) Text(buildString {
                append(if(record.amount == null) "重量未记录" else "${Units.display(record.amount)} ${Kinds.unit(record.kind)}")
                if(record.kind == Kinds.MEAL) append("  ·  ${if(record.calories == null) "热量未记录" else "${Units.display(record.calories)} kcal"}")
                if(record.kind == Kinds.EXERCISE) append("  ·  ${if(record.calories != null) "${Units.display(record.calories)} kcal" else if(record.met != null) "${Units.display(record.met)} MET" else "消耗待补填"}")
            }, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if(record.note.isNotBlank()) Text(record.note, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            if(record.photoNames().isNotEmpty()) Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { record.photoNames().forEach { PhotoThumb(it) } }
        }
    }
}

@Composable
private fun PhotoThumb(name: String, large: Boolean = false) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, name) { value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(File(PhotoFiles.directory(context), name).path, BitmapFactory.Options().apply { inSampleSize = if(large) 1 else 4 }) } }
    var preview by remember { mutableStateOf(false) }
    bitmap?.let { Image(it.asImageBitmap(), "饮食照片", Modifier.size(if(large) 280.dp else 76.dp).clickable { preview = true }, contentScale = ContentScale.Crop) }
    if(preview) Dialog(onDismissRequest = { preview = false }) { Surface(shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { bitmap?.let { Image(it.asImageBitmap(), "饮食照片预览", Modifier.fillMaxWidth().heightIn(max = 500.dp), contentScale = ContentScale.Fit) }; TextButton(onClick = { preview = false }) { Text("关闭") } } } }
}

@Composable
private fun DateButton(date: LocalDate, onChange: (LocalDate) -> Unit) {
    val context = LocalContext.current
    TextButton(onClick = { DatePickerDialog(context, { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d)) }, date.year, date.monthValue - 1, date.dayOfMonth).show() }) { Text(date.toString(), fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun RecordEditor(kind: String, selectedDate: LocalDate, existing: Record?, saving: Boolean, onDismiss: () -> Unit, onSave: (Record) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = existing?.let { OffsetDateTime.parse(it.occurredAt) }
    var dateString by rememberSaveable { mutableStateOf((original?.toLocalDate() ?: selectedDate).toString()) }
    var timeString by rememberSaveable { mutableStateOf((original?.toLocalTime() ?: LocalTime.now()).format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var amount by rememberSaveable { mutableStateOf(existing?.amount?.let { Export.number(it) } ?: "") }
    var unitIndex by rememberSaveable { mutableIntStateOf(0) }
    var calories by rememberSaveable { mutableStateOf(existing?.calories?.let { Export.number(it) } ?: "") }
    var energyIndex by rememberSaveable { mutableIntStateOf(0) }
    var met by rememberSaveable { mutableStateOf(existing?.met?.let { Export.number(it) } ?: "") }
    var exerciseMode by rememberSaveable { mutableIntStateOf(if(existing?.met != null && existing.calories == null) 1 else 0) }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var photos by rememberSaveable { mutableStateOf(existing?.photos ?: "") }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val units = Units.forKind(kind)
    fun importPhoto(uri: Uri) { importing = true; scope.launch { try { val filename = withContext(Dispatchers.IO) { PhotoFiles.import(context, uri) }; photos = (photos.split(';').filter { it.isNotBlank() } + filename).joinToString(";") } catch(e: Exception) { error = e.message ?: "添加照片失败" } finally { importing = false } } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if(uri != null) importPhoto(uri) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success -> cameraPath?.let { path -> if(success) importPhoto(Uri.fromFile(File(path))) else File(path).delete() }; cameraPath = null }
    Dialog(onDismissRequest = { if(!saving && !importing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !saving && !importing) { Text("取消") }
                    Text("${if(existing == null) "记录" else "编辑"}${Kinds.label(kind)}", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Button(onClick = {
                        try {
                            val eventLocal = LocalDateTime.of(LocalDate.parse(dateString), LocalTime.parse(timeString))
                            val timestamp = if(original != null) eventLocal.atOffset(original.offset) else eventLocal.atZone(ZoneId.systemDefault()).toOffsetDateTime()
                            val record = Record(id = existing?.id ?: java.util.UUID.randomUUID().toString(), kind = kind, occurredAt = timestamp.toString(), date = timestamp.toLocalDate().toString(), name = name.trim(), amount = Units.convert(amount, units[unitIndex], optional = kind == Kinds.MEAL), calories = if(kind == Kinds.MEAL || kind == Kinds.EXERCISE && exerciseMode == 0) Units.convert(calories, Units.energy[energyIndex], optional = true, allowZero = true) else null, met = if(kind == Kinds.EXERCISE && exerciseMode == 1) Units.convert(met, MeasureUnit("MET", 1.0), optional = true) else null, photos = photos, note = note.trim(), createdAt = existing?.createdAt ?: OffsetDateTime.now().toString())
                            record.validate(); onSave(record)
                        } catch(e: Exception) { error = e.message ?: "请检查输入" }
                    }, enabled = !saving && !importing) { Text(if(saving) "保存中" else "保存") }
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("发生时间", fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DateButton(LocalDate.parse(dateString)) { dateString = it.toString() }
                        OutlinedButton(onClick = { val t = LocalTime.parse(timeString); TimePickerDialog(context, { _, h, m -> timeString = "%02d:%02d".format(h, m) }, t.hour, t.minute, true).show() }) { Text(timeString) }
                    }
                    if(kind != Kinds.WEIGHT) OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(if(kind == Kinds.MEAL) "食物名称" else "运动名称") }, placeholder = { Text(if(kind == Kinds.MEAL) "例如：米饭、鸡胸肉" else "例如：快走、跑步") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    UnitInput(if(kind == Kinds.EXERCISE) "运动时长" else if(kind == Kinds.MEAL) "食物重量（可选）" else "体重", amount, { amount = it }, units, unitIndex, { newIndex ->
                        amount.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { amount = Export.number(it * units[unitIndex].factor / units[newIndex].factor) }; unitIndex = newIndex
                    })
                    if(kind == Kinds.MEAL) UnitInput("热量（可选）", calories, { calories = it }, Units.energy, energyIndex, { newIndex ->
                        calories.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { calories = Export.number(it * Units.energy[energyIndex].factor / Units.energy[newIndex].factor) }; energyIndex = newIndex
                    })
                    if(kind == Kinds.EXERCISE) {
                        Text("活动系数之外的额外消耗", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(exerciseMode == 0, onClick = { exerciseMode = 0 }, label = { Text("填写热量") })
                            FilterChip(exerciseMode == 1, onClick = { exerciseMode = 1 }, label = { Text("按 MET 估算") })
                        }
                        if(exerciseMode == 0) UnitInput("运动消耗（可选）", calories, { calories = it }, Units.energy, energyIndex, { newIndex -> calories.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { calories = Export.number(it * Units.energy[energyIndex].factor / Units.energy[newIndex].factor) }; energyIndex = newIndex })
                        else {
                            OutlinedTextField(met, { met = it }, label = { Text("运动强度 MET（可选）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text("消耗 ≈ MET × 当天最后体重(kg) × 1.05 × 时长(小时)，在分析页计算。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if(kind == Kinds.MEAL) {
                        Text("饮食照片 · 可选", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !importing) { Text("从相册选择") }
                            OutlinedButton(onClick = {
                                try { val file = File.createTempFile("capture-", ".jpg", File(context.cacheDir, "camera").apply { mkdirs() }); cameraPath = file.path; camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }
                                catch(e: Exception) { error = "无法打开相机：${e.message}" }
                            }, enabled = !importing) { Text("拍照") }
                        }
                        if(importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) { photos.split(';').filter { it.isNotBlank() }.forEach { filename -> Column(horizontalAlignment = Alignment.CenterHorizontally) { PhotoThumb(filename); TextButton(onClick = { photos = photos.split(';').filter { it != filename }.joinToString(";") }) { Text("移除") } } } }
                    }
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                    Text(if(kind == Kinds.EXERCISE) "保存后统一换算为分钟和 kcal。消耗可稍后补填，空白不会记为零。" else "${if(kind == Kinds.MEAL) "重量和热量可稍后补填，空白不会记为零。\n" else ""}保存后统一换算为 ${Kinds.unit(kind)}${if(kind == Kinds.MEAL) " 和 kcal" else ""}。斤、两使用市制：1 斤 = 500 g，1 两 = 50 g。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun UnitInput(label: String, value: String, onValue: (String) -> Unit, units: List<MeasureUnit>, index: Int, onUnit: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value, onValue, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
            Box { OutlinedButton(onClick = { menu = true }) { Text("${units[index].label} ▾") }; DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { units.forEachIndexed { i, unit -> DropdownMenuItem(text = { Text(unit.label) }, onClick = { onUnit(i); menu = false }) } } }
        }
        val canonical = value.toDoubleOrNull()?.times(units[index].factor)
        if(canonical != null && canonical.isFinite()) Text("= ${Units.display(canonical)} ${units.first().label}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 5.dp, start = 12.dp))
    }
}

@Composable
private fun ExportDialog(records: List<Record>, busy: Boolean, onDismiss: () -> Unit, onExport: (LocalDate, LocalDate) -> Unit) {
    var start by rememberSaveable { mutableStateOf(records.minOfOrNull { it.date } ?: LocalDate.now().toString()) }
    var end by rememberSaveable { mutableStateOf(records.maxOfOrNull { it.date } ?: LocalDate.now().toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val count = records.count { it.date >= start && it.date <= end }
    AlertDialog(onDismissRequest = { if(!busy) onDismiss() }, title = { Text("导出记录") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("选择日期范围，在下一步选择手机中的保存位置。")
            Row(verticalAlignment = Alignment.CenterVertically) { Text("从"); DateButton(LocalDate.parse(start)) { start = it.toString() } }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("至"); DateButton(LocalDate.parse(end)) { end = it.toString() } }
            Text("共 $count 条记录", fontWeight = FontWeight.SemiBold)
            Text("ZIP 包含每日汇总、三类明细、模型分析、预测快照、原始数据和照片。Excel 可查看 CSV；单位统一，空值保留。", fontSize = 13.sp)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = { if(end < start) error = "结束日期不能早于开始日期" else onExport(LocalDate.parse(start), LocalDate.parse(end)) }, enabled = !busy && count > 0) { Text(if(busy) "打包中" else "导出 ZIP") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}
