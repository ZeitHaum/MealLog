package com.self.diet

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

class DietViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = DietDatabase.get(app).records()
    val records = dao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val snapshots = dao.observeSnapshots().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val preferences = app.getSharedPreferences("model", 0)
    private val defaults = ModelProfile()
    var profile by mutableStateOf(ModelProfile(
        heightCm = preferences.getString("height", defaults.heightCm.toString())!!.toDouble(), age = preferences.getInt("age", defaults.age), sex = preferences.getString("sex", defaults.sex)!!,
        activityFactor = preferences.getString("activity", defaults.activityFactor.toString())!!.toDouble(), lambda = preferences.getString("lambda", defaults.lambda.toString())!!.toDouble(), confirmed = preferences.getBoolean("confirmed", false)
    ))
        private set
    var message by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
    var exporting by mutableStateOf(false)
    private var preparedExport: File? = null

    fun saveProfile(value: ModelProfile, done: () -> Unit) = viewModelScope.launch {
        try {
            value.validate()
            val saved = withContext(Dispatchers.IO) { preferences.edit().putString("height", value.heightCm.toString()).putInt("age", value.age).putString("sex", value.sex).putString("activity", value.activityFactor.toString()).putString("lambda", value.lambda.toString()).putBoolean("confirmed", true).commit() }
            check(saved) { "模型设置保存失败" }
            profile = value.copy(confirmed = true); done(); message = "模型设置已保存"
        } catch(e: Exception) { message = e.message }
    }
    fun savePrediction(date: String) = viewModelScope.launch {
        try {
            val day = dao.all().filter { it.date == date }
            val selected = LocalDate.parse(date)
            val mode = when { selected.isBefore(LocalDate.now()) -> "retrospective"; selected.isAfter(LocalDate.now()) -> "planned"; else -> "same_day_estimate" }
            dao.saveSnapshot(EnergyModel.snapshot(day, profile, date, mode))
            message = "已保存计算快照，后续修改不会覆盖这次结果"
        } catch(e: Exception) { message = e.message ?: "保存预测失败" }
    }

    fun save(record: Record, done: () -> Unit) {
        if(saving) return
        saving = true
        viewModelScope.launch {
            try {
                record.validate()
                val previous = dao.all().firstOrNull { it.id == record.id }
                dao.save(record)
                withContext(Dispatchers.IO) { previous?.photoNames()?.filter { it !in record.photoNames() }?.forEach { File(PhotoFiles.directory(getApplication()), it).delete() } }
                done(); message = "已保存"
            }
            catch (e: Exception) { message = e.message ?: "保存失败，请重试" }
            finally { saving = false }
        }
    }
    fun delete(record: Record) = viewModelScope.launch {
        try {
            dao.delete(record.id)
            withContext(Dispatchers.IO) { record.photoNames().forEach { File(PhotoFiles.directory(getApplication()), it).delete() } }
            message = "已删除记录"
        } catch(e: Exception) { message = "删除失败：${e.message}" }
    }
    fun prepareExport(start: LocalDate, end: LocalDate, done: (String) -> Unit) {
        if(exporting) return
        exporting = true
        viewModelScope.launch {
            try {
                val snapshot = dao.all()
                val predictions = dao.snapshots()
                val model = profile
                val file = withContext(Dispatchers.IO) {
                    File.createTempFile("diet-export-", ".zip", getApplication<Application>().cacheDir).also { target ->
                        try { target.outputStream().use { Export.writeZip(it, snapshot, PhotoFiles.directory(getApplication()), start, end, model, predictions) } }
                        catch (e: Exception) { target.delete(); throw e }
                    }
                }
                preparedExport?.delete(); preparedExport = file
                done("Diet_${start}_${end}.zip")
            } catch(e: Exception) { message = e.message ?: "生成导出失败" }
            finally { exporting = false }
        }
    }
    fun copyExport(uri: Uri?) {
        if(uri == null) { preparedExport?.delete(); preparedExport = null; return }
        val source = preparedExport ?: run { message = "导出已过期，请重新导出"; return }
        exporting = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } } ?: error("无法写入所选位置")
                }
                message = "导出成功：ZIP 包含每日汇总、原始明细和照片"
            } catch(e: Exception) { message = "导出失败：${e.message}" }
            finally { source.delete(); preparedExport = null; exporting = false }
        }
    }
}
